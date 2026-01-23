// native-lib.cpp
#include "llama.h"
#include <vector>
#include <jni.h>
#include <string>
#include <cstring>
#include <android/log.h>
#include <chrono>
#include <atomic>
#include <mutex>
#include <condition_variable>

#define LOG_TAG "SLM_NATIVE"
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOG_ERROR(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Global variables for single initialization
static std::once_flag g_backend_init_flag;
static std::atomic<bool> g_backend_initialized{false};
static std::mutex g_inference_mutex; // Mutex for thread-safe inference

// Simple RAII wrapper for llama_context
class LlamaContext {
private:
    llama_context* m_ctx;
    llama_model* m_model;

public:
    LlamaContext(const char* model_path, int n_ctx, int n_threads)
            : m_ctx(nullptr), m_model(nullptr) {

        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0; // Set to 0 for CPU-only on Android
        m_model = llama_model_load_from_file(model_path, model_params);

        if (!m_model) {
            LOG_ERROR("Failed to load model: %s", model_path);
            return;
        }

        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = n_ctx;
        ctx_params.n_threads = n_threads;
        ctx_params.n_threads_batch = n_threads;


        m_ctx = llama_init_from_model(m_model, ctx_params);
        if (!m_ctx) {
            LOG_ERROR("Failed to create context for model: %s", model_path);
            llama_free_model(m_model);
            m_model = nullptr;
        } else {
            LOG_INFO("Context created successfully with n_ctx=%d", n_ctx);
        }
    }

    ~LlamaContext() {
        if (m_ctx) {
            llama_free(m_ctx);
            LOG_INFO("Context freed");
        }
        if (m_model) {
            llama_free_model(m_model);
            LOG_INFO("Model freed");
        }
    }

    operator bool() const { return m_ctx != nullptr; }

    llama_context* get() { return m_ctx; }
    llama_model* get_model() { return m_model; }

    // Disable copy
    LlamaContext(const LlamaContext&) = delete;
    LlamaContext& operator=(const LlamaContext&) = delete;
};

// Initialize llama backend (thread-safe, called once)
static void initialize_backend() {
    if (!g_backend_initialized.exchange(true)) {
        llama_backend_init(); // ✅ no arguments
        LOG_INFO("Llama backend initialized");
    }
}


// Tokenize input with bounds checking
static std::vector<llama_token> tokenize_input(llama_context* ctx, const std::string& prompt) {
    const llama_vocab* vocab = llama_model_get_vocab(llama_get_model(ctx));
    if (!vocab) {
        LOG_ERROR("Failed to get vocabulary");
        return {};
    }

    // Reserve space (max 512 tokens for safety)
    std::vector<llama_token> tokens(512);

    int n_tokens = llama_tokenize(
            vocab,
            prompt.c_str(),
            prompt.size(),
            tokens.data(),
            tokens.size(),
            true,  // add_bos
            false  // special
    );

    if (n_tokens < 0) {
        LOG_ERROR("Tokenization failed for prompt (size: %zu)", prompt.size());
        return {};
    }

    if (n_tokens == 0) {
        LOG_ERROR("No tokens generated from prompt");
        return {};
    }

    if (n_tokens > 512) {
        LOG_ERROR("Prompt too long: %d tokens (max 512)", n_tokens);
        return {};
    }

    tokens.resize(n_tokens);
    LOG_INFO("Tokenized %d tokens", n_tokens);

    return tokens;
}

// Main inference function
static std::string run_inference(
        JNIEnv* env,
        jobject thiz,
        const std::string& prompt,
        const std::string& model_path,
        bool report_progress) {

    // Initialize backend once
    std::call_once(g_backend_init_flag, initialize_backend);

    LOG_INFO("Starting inference with model: %s", model_path.c_str());
    LOG_INFO("Prompt: %s", prompt.substr(0, 100).c_str()); // Log first 100 chars

    // Lock for thread-safe inference (prevent multiple concurrent inferences)
    std::unique_lock<std::mutex> lock(g_inference_mutex);

    // Load model and create context
    LlamaContext ctx(model_path.c_str(), 512, 4);
    if (!ctx) {
        return "ERROR|Failed to load model or create context";
    }

    // Tokenize input
    std::vector<llama_token> prompt_tokens = tokenize_input(ctx.get(), prompt);
    if (prompt_tokens.empty()) {
        return "ERROR|Tokenization failed";
    }

    int n_prompt = prompt_tokens.size();

    // Get JNI callback for progress updates
    jclass activity_cls = nullptr;
    jmethodID progress_method = nullptr;
    if (report_progress) {
        activity_cls = env->GetObjectClass(thiz);
        if (activity_cls) {
            progress_method = env->GetMethodID(activity_cls, "updateNativeProgress", "(I)V");
        }
    }

    // Start timing for overall inference
    auto t_inference_start = std::chrono::high_resolution_clock::now();

    // --- PROMPT PROCESSING ---
    llama_batch batch = llama_batch_init(n_prompt, 0, 1);
    if (!batch.token) {
        return "ERROR|Failed to allocate batch";
    }

    for (int i = 0; i < n_prompt; i++) {
        batch.token[i] = prompt_tokens[i];
        batch.pos[i] = i;
        batch.seq_id[i][0] = 0;
        batch.n_seq_id[i] = 1;
        batch.logits[i] = (i == n_prompt - 1); // Logits only for last token
    }
    batch.n_tokens = n_prompt;

    LOG_INFO("Decoding prompt with %d tokens", n_prompt);

    // Decode prompt
    int decode_result = llama_decode(ctx.get(), batch);
    if (decode_result != 0) {
        LOG_ERROR("Prompt decoding failed with code: %d", decode_result);
        llama_batch_free(batch);
        return "ERROR|Prompt decoding failed";
    }

    // Calculate prompt processing metrics
    auto t_prompt_end = std::chrono::high_resolution_clock::now();
    long prompt_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            t_prompt_end - t_inference_start).count();
    long itps = (prompt_ms > 0) ? (n_prompt * 1000L) / prompt_ms : 0;

    LOG_INFO("Prompt processing: %ld ms, ITPS: %ld", prompt_ms, itps);

    // --- TOKEN GENERATION ---
    llama_sampler* sampler = llama_sampler_init_greedy();
    if (!sampler) {
        llama_batch_free(batch);
        return "ERROR|Failed to create sampler";
    }

    std::string output;
    int generated_tokens = 0;
    long ttft_ms = -1;
    bool first_token_seen = false;

    auto t_gen_start = std::chrono::high_resolution_clock::now();

    const int MAX_GEN_TOKENS = 32; // Reduced from 64 for stability
    int n_pos = n_prompt;

    // Get vocabulary for token conversion
    const llama_vocab* vocab = llama_model_get_vocab(ctx.get_model());

    while (generated_tokens < MAX_GEN_TOKENS) {
        // Sample next token
        llama_token token = llama_sampler_sample(sampler, ctx.get(), -1);

        // EOS check (OLD API)
        if (token == llama_token_eos(vocab)) {
            LOG_INFO("End of sequence token received");
            break;
        }

        // Time to first token
        if (!first_token_seen) {
            auto t_now = std::chrono::high_resolution_clock::now();
            ttft_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                    t_now - t_inference_start).count();
            first_token_seen = true;
            LOG_INFO("First token received at %ld ms", ttft_ms);
        }

        // Token → text
        char buffer[128];

        int32_t n_chars = llama_token_to_piece(
                vocab,                 // const llama_vocab *
                token,                 // llama_token
                buffer,                // char *
                (int32_t)sizeof(buffer), // length
                0,                     // lstrip (usually 0)
                false                  // special tokens? false
        );

        ;

        if (n_chars > 0) {
            output.append(buffer, n_chars);
            LOG_INFO("Generated token %d: '%.*s'",
                     generated_tokens + 1, n_chars, buffer);

            if (output.find('\n') != std::string::npos) {
                LOG_INFO("Newline detected, stopping generation");
                break;
            }
        } else if (n_chars < 0) {
            LOG_ERROR("Failed to convert token to piece");
            break;
        }

        generated_tokens++;

        // Progress callback
        if (report_progress && progress_method) {
            int percent = (generated_tokens * 100) / MAX_GEN_TOKENS;
            env->CallVoidMethod(thiz, progress_method, percent);
        }

        // Decode next token
        llama_batch_free(batch);
        batch = llama_batch_init(1, 0, 1);

        batch.token[0] = token;
        batch.pos[0] = n_pos++;
        batch.seq_id[0][0] = 0;
        batch.n_seq_id[0] = 1;
        batch.logits[0] = true;
        batch.n_tokens = 1;

        if (llama_decode(ctx.get(), batch) != 0) {
            LOG_ERROR("Generation decoding failed");
            break;
        }
    }

    // Clean up sampler and batch
    llama_sampler_free(sampler);
    llama_batch_free(batch);

    // Calculate final metrics
    auto t_inference_end = std::chrono::high_resolution_clock::now();

    // Generation time only
    long gen_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            t_inference_end - t_gen_start).count();

    // Output tokens per second
    long otps = (gen_ms > 0 && generated_tokens > 0) ?
                (generated_tokens * 1000L) / gen_ms : 0;

    // Overall evaluation time
    long oet_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            t_inference_end - t_inference_start).count();

    LOG_INFO("Inference complete: %d tokens generated in %ld ms", generated_tokens, oet_ms);
    LOG_INFO("Final metrics: ITPS=%ld, OTPS=%ld, TTFT=%ldms", itps, otps, ttft_ms);

    // Format result: METADATA|OUTPUT
    std::string result;
    if (generated_tokens > 0) {
        result = "TTFT_MS=" + std::to_string(ttft_ms) +
                 ";ITPS=" + std::to_string(itps) +
                 ";OTPS=" + std::to_string(otps) +
                 ";OET_MS=" + std::to_string(oet_ms) +
                 ";GEN_TOKENS=" + std::to_string(generated_tokens) +
                 "|" + output;
    } else {
        result = "ERROR|No tokens generated";
    }

    LOG_INFO("Result length: %zu characters", result.length());
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_edu_utem_ftmk_slm02_MainActivity_inferAllergens(
        JNIEnv* env,
        jobject thiz,
        jstring input_prompt,
        jstring model_path,
        jboolean report_progress) {

    LOG_INFO("Java inferAllergens called");

    // Convert Java strings to C++ strings
    const char* prompt_cstr = env->GetStringUTFChars(input_prompt, nullptr);
    const char* path_cstr = env->GetStringUTFChars(model_path, nullptr);

    if (!prompt_cstr || !path_cstr) {
        LOG_ERROR("Failed to get Java string UTF chars");
        if (prompt_cstr) env->ReleaseStringUTFChars(input_prompt, prompt_cstr);
        if (path_cstr) env->ReleaseStringUTFChars(model_path, path_cstr);
        return env->NewStringUTF("ERROR|Invalid input parameters");
    }

    std::string prompt(prompt_cstr);
    std::string model_path_str(path_cstr);

    LOG_INFO("Running inference with prompt length: %zu, model path: %s",
             prompt.length(), model_path_str.c_str());

    // Run inference
    std::string result;
    try {
        result = run_inference(env, thiz, prompt, model_path_str, report_progress);
    } catch (const std::exception& e) {
        LOG_ERROR("Exception during inference: %s", e.what());
        result = "ERROR|Exception during inference: " + std::string(e.what());
    } catch (...) {
        LOG_ERROR("Unknown exception during inference");
        result = "ERROR|Unknown exception during inference";
    }

    // Release Java strings
    env->ReleaseStringUTFChars(input_prompt, prompt_cstr);
    env->ReleaseStringUTFChars(model_path, path_cstr);

    LOG_INFO("Inference completed, returning result");
    return env->NewStringUTF(result.c_str());
}

// Optional: Cleanup function
extern "C" JNIEXPORT void JNICALL
Java_edu_utem_ftmk_slm02_MainActivity_cleanupNative(
        JNIEnv* env,
        jclass clazz) {

    LOG_INFO("cleanupNative called");
    if (g_backend_initialized.exchange(false)) {
        // Note: llama_backend_free() might not be available in older versions
        // Check if it exists before calling
#ifdef HAVE_LLAMA_BACKEND_FREE
        llama_backend_free();
#endif
        LOG_INFO("Native cleanup completed");
    }
}

// Optional: Test function to verify llama is working
extern "C" JNIEXPORT jstring JNICALL
Java_edu_utem_ftmk_slm02_MainActivity_testLlama(
        JNIEnv* env,
        jclass clazz) {

    LOG_INFO("testLlama called");
    return env->NewStringUTF("Llama test successful - native library loaded");
}