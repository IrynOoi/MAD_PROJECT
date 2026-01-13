//MainActivity.kt
package edu.utem.ftmk.slm02



import android.Manifest

import android.content.Context

import android.content.Intent

import android.content.pm.PackageManager

import android.os.Build

import android.os.Bundle

import android.util.Log

import android.view.View

import android.widget.*

import androidx.appcompat.app.AppCompatActivity

import androidx.core.app.ActivityCompat

import androidx.core.content.ContextCompat

import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext

import java.io.File



class MainActivity : AppCompatActivity() {



    companion object {

        init {

            System.loadLibrary("native-lib")

            System.loadLibrary("ggml-base")

            System.loadLibrary("ggml-cpu")

            System.loadLibrary("llama")

        }

    }



// --- JNI Definition (Must match C++ signature exactly) ---

    external fun inferAllergens(input: String, modelPath: String, reportProgress: Boolean): String



// Services

    private lateinit var csvReader: CsvReader

    private lateinit var datasetManager: DatasetManager

    private lateinit var firebaseService: FirebaseService

    private lateinit var notificationManager: NotificationManager



// UI Components

    private lateinit var spinnerDataset: Spinner

    private lateinit var spinnerFoodItem: Spinner

    private lateinit var spinnerModel: Spinner

    private lateinit var tvDatasetInfo: TextView

    private lateinit var btnLoadDataset: Button

    private lateinit var btnPredictItem: Button

    private lateinit var btnPredictAll: Button

    private lateinit var btnViewResults: Button

    private lateinit var btnViewDashboard: Button

    private lateinit var progressBar: ProgressBar

    private lateinit var tvProgress: TextView



// Data State

    private var allFoodItems: List<FoodItem> = emptyList()

    private var datasets: List<Dataset> = emptyList()

    private var selectedDataset: Dataset? = null

    private var predictionResults: MutableList<PredictionResult> = mutableListOf()



// Model Selection State

    private var selectedModelFilename: String = "qwen2.5-1.5b-instruct-q4_k_m.gguf" // Default

    private val modelsList = listOf(

        "qwen2.5-1.5b-instruct-q4_k_m.gguf",

        "qwen2.5-3b-instruct-q4_k_m.gguf",

        "Llama-3.2-3B-Instruct-Q4_K_M.gguf",

        "Llama-3.2-1B-Instruct-Q4_K_M.gguf",

        "Phi-3.5-mini-instruct-Q4_K_M.gguf",


        "Phi-3-mini-4k-instruct-q4.gguf",

        "Vikhr-Gemma-2B-instruct-Q4_K_M.gguf"

    )



    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)



        initializeServices()

        initializeUI()

        checkAndRequestPermissions()



// Load CSV Data

        loadDataAsync()

    }



// --- Core Logic: Copy Model Dynamically ---

    private suspend fun copyModelToInternalStorage(context: Context, modelName: String): String {

        val outFile = File(context.filesDir, modelName)



// If file exists and is reasonably large (>10MB), assume it's valid

        if (outFile.exists() && outFile.length() > 10 * 1024 * 1024) {

            return outFile.absolutePath

        }



        return withContext(Dispatchers.IO) {

            try {

// Check if file is in assets

                val assets = context.assets.list("")



                Log.d("MODEL", "Looking for: $modelName")

                Log.d("MODEL", "Files found in Assets: ${assets?.joinToString()}")



                if (assets?.contains(modelName) == true) {

                    context.assets.open(modelName).use { input ->

                        outFile.outputStream().use { output -> input.copyTo(output) }

                    }

                    Log.d("MODEL", "Copied $modelName")

                    outFile.absolutePath

                } else {

                    Log.e("MODEL", "Model $modelName not found in Assets.")

                    "" // Return empty on failure

                }

            } catch (e: Exception) {

                Log.e("MODEL", "Failed copy", e)

                ""

            }

        }

    }



// --- Core Logic: Single Prediction (UPDATED FOR DASHBOARD SYNC) ---
    private fun predictAndShowSingleItem(item: FoodItem) {

        lifecycleScope.launch(Dispatchers.IO) {

            withContext(Dispatchers.Main) {

                progressBar.visibility = View.VISIBLE

                progressBar.progress = 0

                tvProgress.visibility = View.VISIBLE

                tvProgress.text = "Loading Model..."

                btnPredictItem.isEnabled = false

            }



            try {

// 1. Prepare Model

                val modelPath = copyModelToInternalStorage(this@MainActivity, selectedModelFilename)

                if (modelPath.isEmpty()) {

                    throw Exception("Model file not found! Did you put $selectedModelFilename in assets?")

                }



                val prompt = buildPrompt(item.ingredients)



// 2. Run Inference

                val javaBefore = MemoryReader.javaHeapKb()

                val nativeBefore = MemoryReader.nativeHeapKb()

                val pssBefore = MemoryReader.totalPssKb()

                val startNs = System.nanoTime()



// This takes time, which is fine (generating text)

                val rawResult = inferAllergens(prompt, modelPath, true)



                val latencyMs = (System.nanoTime() - startNs) / 1_000_000

                val javaAfter = MemoryReader.javaHeapKb()

                val nativeAfter = MemoryReader.nativeHeapKb()

                val pssAfter = MemoryReader.totalPssKb()



                val (predicted, cppMetrics) = parseRawResult(rawResult)



                val finalMetrics = InferenceMetrics(

                    latencyMs = latencyMs,

                    javaHeapKb = javaAfter - javaBefore,

                    nativeHeapKb = nativeAfter - nativeBefore,

                    totalPssKb = pssAfter - pssBefore,

                    ttft = cppMetrics.ttft,

                    itps = cppMetrics.itps,

                    otps = cppMetrics.otps,

                    oet = cppMetrics.oet

                )



                val result = PredictionResult(

                    foodItem = item,

                    predictedAllergens = predicted,

                    modelName = selectedModelFilename, // <--- ADD THIS LINE

                    metrics = finalMetrics

                )





// --- FIX: FIRE AND FORGET ---

// We launch a NEW independent coroutine to handle saving.

// We do NOT wait for this to finish.

                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {

                    try {

                        firebaseService.savePredictionAndRefreshDashboard(result, selectedModelFilename)

                        Log.d("MAIN", "Background save completed")

                    } catch (e: Exception) {

                        Log.e("MAIN", "Background save failed", e)

                    }

                }



// 3. Open Screen IMMEDIATELY

                withContext(Dispatchers.Main) {

                    hideProgress()

                    btnPredictItem.isEnabled = true



                    val intent = Intent(this@MainActivity, ResultsActivity::class.java).apply {

                        putParcelableArrayListExtra("results", ArrayList(listOf(result)))

                    }

                    startActivity(intent)

                }



            } catch (e: Exception) {

                withContext(Dispatchers.Main) {

                    hideProgress()

                    btnPredictItem.isEnabled = true

                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()

                }

            }

        }

    }



// --- Core Logic: Batch Prediction (No Changes Needed Here) ---

    private fun startBatchPrediction(dataset: Dataset) {

        lifecycleScope.launch(Dispatchers.IO) {

            withContext(Dispatchers.Main) {

                btnPredictAll.isEnabled = false

                progressBar.visibility = View.VISIBLE

                progressBar.progress = 0

                tvProgress.visibility = View.VISIBLE

                tvProgress.text = "Initializing Batch Prediction..."

                predictionResults.clear()

            }



            val modelPath = copyModelToInternalStorage(this@MainActivity, selectedModelFilename)

            if (modelPath.isEmpty()) {

                withContext(Dispatchers.Main) {

                    Toast.makeText(this@MainActivity, "Error: Model missing!", Toast.LENGTH_LONG).show()

                    hideProgress()

                    btnPredictAll.isEnabled = true

                }

                return@launch

            }



            val results = mutableListOf<PredictionResult>()



// 1. Quality Accumulators

            var totalPrecision = 0.0

            var totalRecall = 0.0

            var totalF1 = 0.0

            var totalLatency = 0.0

            var totalEmrCount = 0

            var totalHamming = 0.0

            var totalFnr = 0.0



// 2. Safety Accumulators

            var overPredictionCount = 0

            var hallucinationCount = 0

            var abstentionCorrectCount = 0

            var abstentionTotalCount = 0



// 3. Efficiency Accumulators

            var totalTtft = 0.0

            var totalOtps = 0.0

            var totalItps = 0.0

            var totalOet = 0.0

            var totalJavaHeap = 0.0

            var totalNativeHeap = 0.0

            var totalPss = 0.0



            var validSamples = 0

            var successCount = 0

            var failCount = 0



            for ((index, item) in dataset.foodItems.withIndex()) {

                withContext(Dispatchers.Main) {

                    val percent = ((index.toFloat() / dataset.foodItems.size) * 100).toInt()

                    progressBar.progress = percent

                    tvProgress.text = "Processing ${index + 1}/${dataset.foodItems.size}: ${item.name}"

                }



                try {

                    val prompt = buildPrompt(item.ingredients)



// Capture Memory BEFORE

                    val javaBefore = MemoryReader.javaHeapKb()

                    val nativeBefore = MemoryReader.nativeHeapKb()

                    val pssBefore = MemoryReader.totalPssKb()

                    val startNs = System.nanoTime()



// Run Inference

                    val rawResult = inferAllergens(prompt, modelPath, false)



// Capture Metrics AFTER

                    val latencyMs = (System.nanoTime() - startNs) / 1_000_000

                    val javaDiff = MemoryReader.javaHeapKb() - javaBefore

                    val nativeDiff = MemoryReader.nativeHeapKb() - nativeBefore

                    val pssDiff = MemoryReader.totalPssKb() - pssBefore



                    val (predictedStr, cppMetrics) = parseRawResult(rawResult)

                    val metrics = MetricsCalculator.calculate(item.allergensMapped, predictedStr)



// --- Aggregate Quality ---

                    totalPrecision += metrics.precision

                    totalRecall += metrics.recall

                    totalF1 += metrics.f1Score

                    totalLatency += latencyMs

                    if (metrics.exactMatch) totalEmrCount++

                    totalHamming += metrics.hammingLoss

                    totalFnr += metrics.falseNegativeRate



// --- Aggregate Safety ---

                    if (metrics.isOverPrediction) overPredictionCount++

                    if (metrics.isHallucination) hallucinationCount++

                    if (metrics.isAbstentionCase) {

                        abstentionTotalCount++

                        if (metrics.isAbstentionSuccess) abstentionCorrectCount++

                    }



// --- Aggregate Efficiency ---

                    totalTtft += cppMetrics.ttft

                    totalOtps += cppMetrics.otps

                    totalItps += cppMetrics.itps

                    totalOet += cppMetrics.oet

                    totalJavaHeap += javaDiff

                    totalNativeHeap += nativeDiff

                    totalPss += pssDiff



                    validSamples++



                    val finalMetrics = InferenceMetrics(

                        latencyMs = latencyMs,

                        javaHeapKb = javaDiff,

                        nativeHeapKb = nativeDiff,

                        totalPssKb = pssDiff,

                        ttft = cppMetrics.ttft,

                        itps = cppMetrics.itps,

                        otps = cppMetrics.otps,

                        oet = cppMetrics.oet

                    )



                    val result = PredictionResult(

                        foodItem = item,

                        predictedAllergens = predictedStr,

                        modelName = selectedModelFilename, // <--- ADD THIS LINE

                        metrics = finalMetrics

                    )

                    results.add(result)

                    successCount++



                    notificationManager.showProgressNotification(index + 1, dataset.foodItems.size, item.name)



                } catch (e: Exception) {

                    failCount++

                    Log.e("BATCH", "Failed on item ${item.name}", e)

                }

            }



// Save individual results

            val (fbSuccess, fbFail) = firebaseService.saveBatchResults(results)

            predictionResults.clear()

            predictionResults.addAll(results)



// --- Calculate Final Averages ---

            val avgPrecision = if (validSamples > 0) totalPrecision / validSamples else 0.0

            val avgRecall = if (validSamples > 0) totalRecall / validSamples else 0.0

            val avgF1 = if (validSamples > 0) totalF1 / validSamples else 0.0

            val avgLatency = if (validSamples > 0) totalLatency / validSamples else 0.0

            val avgEmr = if (validSamples > 0) (totalEmrCount.toDouble() / validSamples) * 100 else 0.0

            val avgHamming = if (validSamples > 0) totalHamming / validSamples else 0.0

            val avgFnr = if (validSamples > 0) (totalFnr / validSamples) * 100 else 0.0



            val hallucinationRate = if (validSamples > 0) (hallucinationCount.toDouble() / validSamples) * 100 else 0.0

            val overPredictionRate = if (validSamples > 0) (overPredictionCount.toDouble() / validSamples) * 100 else 0.0

            val abstentionAccuracy = if (abstentionTotalCount > 0) (abstentionCorrectCount.toDouble() / abstentionTotalCount) * 100 else 0.0



// Log Debug Info for TNR

            Log.d("BATCH", "TNR Calc: Correct=$abstentionCorrectCount / Total=$abstentionTotalCount = $abstentionAccuracy")



            val avgTtft = if (validSamples > 0) totalTtft / validSamples else 0.0

            val avgOtps = if (validSamples > 0) totalOtps / validSamples else 0.0

            val avgItps = if (validSamples > 0) totalItps / validSamples else 0.0

            val avgOet = if (validSamples > 0) totalOet / validSamples else 0.0

            val avgJavaHeap = if (validSamples > 0) totalJavaHeap / validSamples else 0.0

            val avgNativeHeap = if (validSamples > 0) totalNativeHeap / validSamples else 0.0

            val avgPss = if (validSamples > 0) totalPss / validSamples else 0.0



// --- Save Benchmark Summary ---

            try {

// This saves the calculated averages directly to Dashboard collections

                firebaseService.saveBenchmark(
                    modelName = selectedModelFilename,

                    // Quality
                    avgPrecision = avgPrecision,
                    avgRecall = avgRecall,
                    avgF1 = avgF1,
                    avgEmr = avgEmr,
                    avgHamming = avgHamming,
                    avgFnr = avgFnr,

                    // Safety
                    abstentionAccuracy = abstentionAccuracy,
                    hallucinationRate = hallucinationRate,
                    overPredictionRate = overPredictionRate,

                    // Efficiency
                    avgLatency = avgLatency,
                    avgTotalTime = avgLatency,   // ⭐ 补这一行
                    avgTtft = avgTtft,
                    avgItps = avgItps,
                    avgOtps = avgOtps,
                    avgOet = avgOet,
                    avgJavaHeap = avgJavaHeap / 1024.0,
                    avgNativeHeap = avgNativeHeap / 1024.0,
                    avgPss = avgPss / 1024.0
                )


                Log.d("BATCH", "Benchmark saved for $selectedModelFilename")

            } catch (e: Exception) {

                Log.e("BATCH", "Failed to save benchmark summary", e)

            }



            withContext(Dispatchers.Main) {

                progressBar.progress = 100

                tvProgress.text = "Batch Complete!\nF1: %.2f | TNR: %.0f%%".format(avgF1, abstentionAccuracy)

                btnPredictAll.isEnabled = true

                btnViewResults.visibility = View.VISIBLE

                notificationManager.showCompletionNotification(successCount, dataset.foodItems.size, dataset.name, fbSuccess, fbFail)

                Toast.makeText(this@MainActivity, "Batch Complete. Saved to Dashboard.", Toast.LENGTH_LONG).show()

            }

        }

    }



    private fun parseRawResult(rawResult: String): Pair<String, InferenceMetrics> {

        val parts = rawResult.split("|", limit = 2)

        val meta = parts[0]

        val rawOutput = if (parts.size > 1) parts[1] else ""



        Log.d("DEBUG_MODEL", "Model Raw Output: $rawOutput")



        var ttft = 0L; var itps = 0L; var otps = 0L; var oet = 0L

        meta.split(";").forEach {

            when {

                it.startsWith("TTFT_MS=") -> ttft = it.removePrefix("TTFT_MS=").toLongOrNull() ?: 0L

                it.startsWith("ITPS=") -> itps = it.removePrefix("ITPS=").toLongOrNull() ?: 0L

                it.startsWith("OTPS=") -> otps = it.removePrefix("OTPS=").toLongOrNull() ?: 0L

                it.startsWith("OET_MS=") -> oet = it.removePrefix("OET_MS=").toLongOrNull() ?: 0L

            }

        }



        val cleanedString = rawOutput

            .replace("Assistant:", "", ignoreCase = true)

            .replace("System:", "", ignoreCase = true)

            .replace("User:", "", ignoreCase = true)

            .lowercase()



        val allowedAllergens = setOf(

            "milk", "egg", "peanut", "tree nut",

            "wheat", "soy", "fish", "shellfish", "sesame"

        )

        val detectedSet = mutableSetOf<String>()

        for (allergen in allowedAllergens) {

            val regex = "\\b${Regex.escape(allergen)}\\b".toRegex()

            if (regex.containsMatchIn(cleanedString)) {

                detectedSet.add(allergen)

            }

        }



        val finalAllergens = detectedSet.joinToString(", ").ifEmpty { "EMPTY" }



        return Pair(finalAllergens, InferenceMetrics(0, 0, 0, 0, ttft, itps, otps, oet))

    }



    fun updateNativeProgress(percent: Int) {

        runOnUiThread {

            if (progressBar.visibility == View.VISIBLE && !progressBar.isIndeterminate) {

                if (!btnPredictItem.isEnabled) {

                    progressBar.setProgress(percent, true)

                    tvProgress.text = "Generating: $percent%"

                }

            }

        }

    }



    private fun initializeServices() {

        csvReader = CsvReader(this)

        datasetManager = DatasetManager()

        firebaseService = FirebaseService()

        notificationManager = NotificationManager(this)

    }



    private fun initializeUI() {

        spinnerDataset = findViewById(R.id.spinnerDataset)

        spinnerFoodItem = findViewById(R.id.spinnerFoodItem)

        tvDatasetInfo = findViewById(R.id.tvDatasetInfo)

        btnLoadDataset = findViewById(R.id.btnLoadDataset)

        btnPredictItem = findViewById(R.id.btnPredictItem)

        btnPredictAll = findViewById(R.id.btnPredictAll)

        btnViewResults = findViewById(R.id.btnViewResults)

        progressBar = findViewById(R.id.progressBar)

        tvProgress = findViewById(R.id.tvProgress)



        spinnerModel = findViewById(R.id.spinnerModel)

        btnViewDashboard = findViewById(R.id.btnViewDashboard)



        val modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modelsList)

        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        spinnerModel.adapter = modelAdapter

        spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {

            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {

                selectedModelFilename = modelsList[pos]

            }

            override fun onNothingSelected(p0: AdapterView<*>?) {}

        }



        btnLoadDataset.setOnClickListener {

            val pos = spinnerDataset.selectedItemPosition

            if (pos >= 0 && pos < datasets.size) {

                selectedDataset = datasets[pos]

                updateDatasetInfo()

                btnPredictAll.isEnabled = true

                setupFoodItemSpinner(selectedDataset!!)

                btnPredictItem.isEnabled = true

                Toast.makeText(this, "Loaded: ${selectedDataset?.name}", Toast.LENGTH_SHORT).show()

            }

        }



        btnPredictItem.setOnClickListener {

            selectedDataset?.let { ds ->

                val pos = spinnerFoodItem.selectedItemPosition

                if (pos >= 0 && pos < ds.foodItems.size) {

                    predictAndShowSingleItem(ds.foodItems[pos])

                }

            }

        }



        btnPredictAll.setOnClickListener {

            selectedDataset?.let { startBatchPrediction(it) }

        }



        btnViewResults.setOnClickListener {

            val intent = Intent(this, ResultsActivity::class.java).apply {

                putParcelableArrayListExtra("results", ArrayList(predictionResults))

            }

            startActivity(intent)

        }



        btnViewDashboard.setOnClickListener {

            val intent = Intent(this, DashboardActivity::class.java)

            startActivity(intent)

        }

    }



    private fun loadDataAsync() {

        lifecycleScope.launch(Dispatchers.IO) {

            allFoodItems = csvReader.readFoodItemsFromAssets()

            datasets = datasetManager.createDatasets(allFoodItems)

            withContext(Dispatchers.Main) {

                setupDatasetSpinner()

            }

        }

    }



    private fun setupDatasetSpinner() {

        val names = datasets.map { it.name }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)

        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        spinnerDataset.adapter = adapter

    }



    private fun setupFoodItemSpinner(dataset: Dataset) {

        val names = dataset.foodItems.map { it.name }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)

        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        spinnerFoodItem.adapter = adapter

    }



    private fun updateDatasetInfo() {

        selectedDataset?.let {

            tvDatasetInfo.text = "Selected: ${it.name}\nItems: ${it.foodItems.size}"

        }

    }



    private fun buildPrompt(ingredients: String): String {

        // 1. CONTENT SECTION:
        // Add a "Reference Guide" that maps derived ingredients to allergens.
        // This significantly improves accuracy while still being Zero-Shot
        // (definitions and rules, not examples).
        val sysMsg = """
        You are a strict Food Safety Officer. 
        Analyze the ingredients list and extract ONLY allergens from this specific list: 
        [milk, egg, peanut, tree nut, wheat, soy, fish, shellfish, sesame].
        
        Reference Guide (Derived Ingredients Mapping):
        - milk: butter, cheese, cream, yogurt, whey, casein, lactose, ghee
        - egg: egg white, egg yolk, albumin, mayonnaise, meringue
        - peanut: peanut butter, arachis oil, goober
        - tree nut: almond, walnut, cashew, pecan, pistachio, macadamia, hazelnut
        - wheat: flour, semolina, bread crumbs, gluten, spelt, couscous, durum
        - soy: soy sauce, tofu, soy protein, edamame, lecithin, miso, tempeh
        - fish: salmon, tuna, cod, anchovy, bass, tilapia
        - shellfish: shrimp, crab, lobster, prawn, clam, oyster, scallop
        - sesame: tahini, sesame oil, benne seeds, za'atar
        
        Rules:
        1. Identify allergens by direct mention OR by matching any item from the Reference Guide.
        2. Output ONLY detected allergens from the target list (e.g., "milk, wheat").
        3. Format the output as a lowercase, comma-separated list.
        4. If no allergens are found, output exactly: EMPTY
        5. NEVER include explanations, preambles, or extra text.
    """.trimIndent()

        val userMsg = "Ingredients to analyze:\n$ingredients"

        // 2. FORMAT SECTION:
        // Ensure the prompt is structured correctly for each model family,
        // so the model properly follows system and user instructions.
        return when {
            // Qwen 2.5 (ChatML format)
            selectedModelFilename.contains("qwen", true) ->
                "<|im_start|>system\n$sysMsg<|im_end|>\n<|im_start|>user\n$userMsg<|im_end|>\n<|im_start|>assistant\n"

            // Phi-3 (Phi format)
            // System instructions are merged into the user message
            // to improve instruction adherence.
            selectedModelFilename.contains("Phi", true) ->
                "<|user|>\n$sysMsg\n\n$userMsg<|end|>\n<|assistant|>\n"

            // Llama 3 (Llama 3 chat format)
            selectedModelFilename.contains("Llama-3", true) ->
                "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n$sysMsg<|eot_id|>" +
                        "<|start_header_id|>user<|end_header_id|>\n\n$userMsg<|eot_id|>" +
                        "<|start_header_id|>assistant<|end_header_id|>\n"

            // Gemma (Gemma chat format)
            selectedModelFilename.contains("Gemma", true) ->
                "<start_of_turn>user\n$sysMsg\n\n$userMsg<end_of_turn>\n<start_of_turn>model\n"

            // Default / fallback format (e.g., LLaMA-style [INST])
            else ->
                "[INST] $sysMsg \n\n $userMsg [/INST]\n"
        }
    }





    private fun hideProgress() {

        progressBar.visibility = View.GONE

        tvProgress.visibility = View.GONE

    }



    private fun checkAndRequestPermissions() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)

            }

        }

    }

}
