// FoodDetailActivity.kt
package edu.utem.ftmk.slm02

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.Color

class FoodDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.food_detail)

        // 1. Retrieve the Parcelable object passed from ResultsActivity
        val result = intent.getParcelableExtra<PredictionResult>("prediction_result")

        // 2. Setup Back Button
        findViewById<ImageButton>(R.id.btnBackDetail).setOnClickListener {
            finish()
        }

        // 3. Populate Data if result is not null
        result?.let { populateUI(it) }
    }

    private fun populateUI(result: PredictionResult) {
        // --- 1. Basic Header Info ---
        findViewById<TextView>(R.id.tvDetailName).text = result.foodItem.name
        findViewById<TextView>(R.id.tvDetailId).text = "#${result.foodItem.id}"
        findViewById<TextView>(R.id.tvDetailLink).text = result.foodItem.link

        // --- 2. Food Composition ---
        findViewById<TextView>(R.id.tvDetailIngredients).text = result.foodItem.ingredients

        val rawAllergens = if (result.foodItem.allergens == "empty" || result.foodItem.allergens.isEmpty())
            "None" else result.foodItem.allergens
        findViewById<TextView>(R.id.tvDetailRawAllergens).text = rawAllergens

        val mappedAllergens = if (result.foodItem.allergensMapped.isEmpty())
            "None" else result.foodItem.allergensMapped
        findViewById<TextView>(R.id.tvDetailMappedAllergens).text = mappedAllergens

        // --- 3. AI Prediction ---
        findViewById<TextView>(R.id.tvDetailPredicted).text = result.predictedAllergens ?: "No Prediction"

        // --- 4. Inference Metrics (Hardware Performance) ---
        val metricsText = StringBuilder()
        result.metrics?.let {
            metricsText.append("Latency: ${it.latencyMs} ms\n")
            metricsText.append("TTFT:    ${it.ttft} ms\n")
            metricsText.append("ITPS:    ${it.itps} t/s\n")
            metricsText.append("OTPS:    ${it.otps} t/s\n")
            metricsText.append("Memory:  ${it.totalPssKb / 1024} MB (PSS)")
        } ?: metricsText.append("No Metrics Available")

        findViewById<TextView>(R.id.tvDetailMetrics).text = metricsText.toString()

        // --- 5. Timestamp ---
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        findViewById<TextView>(R.id.tvDetailTimestamp).text = sdf.format(Date(result.timestamp))

        // =========================================================================
        // NEW LOGIC: Metric Validation (Consistency Check)
        // This ensures the item-level results match the dashboard aggregation.
        // =========================================================================

        // A. Calculate metrics specifically for this single item
        val metrics = MetricsCalculator.calculate(
            result.foodItem.allergensMapped,
            result.predictedAllergens ?: ""
        )

        // B. Populate Quality Metrics (F1 & Exact Match)
        findViewById<TextView>(R.id.tvDetailF1).text = "Item F1 Score: %.2f".format(metrics.f1Score)

        val tvEMR = findViewById<TextView>(R.id.tvDetailEMR)
        if (metrics.exactMatch) {
            tvEMR.text = "Exact Match: YES (Contributes to Accuracy)"
            tvEMR.setTextColor(Color.parseColor("#2E7D32")) // Green
        } else {
            tvEMR.text = "Exact Match: NO (Reduces Accuracy)"
            tvEMR.setTextColor(Color.parseColor("#D32F2F")) // Red
        }

        // C. Populate Safety Metrics
        val tvSafety = findViewById<TextView>(R.id.tvDetailSafety)
        val safetyStatus = when {
            metrics.isHallucination -> "⚠️ Hallucination (False Positive)"
            metrics.isOverPrediction -> "⚠️ Over-Prediction (Extra Labels)"
            else -> "✅ Safe Prediction"
        }
        tvSafety.text = safetyStatus

        // D. Populate Abstention Metrics (TNR Check)
        val tvAbstention = findViewById<TextView>(R.id.tvDetailAbstention)
        if (metrics.isAbstentionCase) {
            // Ground truth was empty
            if (metrics.isAbstentionSuccess) {
                tvAbstention.text = "✅ TNR Success: Correctly predicted 'Empty'"
                tvAbstention.setTextColor(Color.parseColor("#2E7D32")) // Green
            } else {
                tvAbstention.text = "❌ TNR Fail: Predicted allergens when none existed"
                tvAbstention.setTextColor(Color.parseColor("#D32F2F")) // Red
            }
        } else {
            // Ground truth had allergens, so this item is irrelevant to TNR
            tvAbstention.text = "N/A (Input had allergens, ignored for TNR)"
            tvAbstention.setTextColor(Color.DKGRAY)
        }
    }
}