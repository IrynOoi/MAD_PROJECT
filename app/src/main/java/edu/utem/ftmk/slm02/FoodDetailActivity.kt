// FoodDetailActivity.kt
package edu.utem.ftmk.slm02

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.Color

class FoodDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.food_detail)

        val btnBack = findViewById<ImageButton>(R.id.btnBackDetail)
        btnBack.setOnClickListener { finish() }

        val result = intent.getParcelableExtra<PredictionResult>("prediction_result")

        if (result != null) {
            populateOriginalFields(result)
            populateTable2Quality(result)
            populateTable3Safety(result)
            populateTable4Efficiency(result)
        }
    }

    private fun populateOriginalFields(result: PredictionResult) {
        val food = result.foodItem

        // 1. Basic Info
        findViewById<TextView>(R.id.tvDetailName).text = food.name
        findViewById<TextView>(R.id.tvDetailId).text = "#${food.id}"
        findViewById<TextView>(R.id.tvDetailLink).text = food.link

        // 2. Composition
        findViewById<TextView>(R.id.tvDetailIngredients).text = food.ingredients

        val rawAllergens = if (food.allergens.isNullOrEmpty() || food.allergens == "empty")
            "None" else food.allergens
        findViewById<TextView>(R.id.tvDetailRawAllergens).text = rawAllergens

        val mappedAllergens = if (food.allergensMapped.isNullOrEmpty())
            "None" else food.allergensMapped
        findViewById<TextView>(R.id.tvDetailMappedAllergens).text = mappedAllergens

        // 3. Predicted
        findViewById<TextView>(R.id.tvDetailPredicted).text = result.predictedAllergens ?: "No Prediction"

        // 4. Timestamp
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        findViewById<TextView>(R.id.tvDetailTimestamp).text = sdf.format(Date(result.timestamp))
    }

    private fun populateTable2Quality(result: PredictionResult) {
        val metrics = MetricsCalculator.calculate(
            result.foodItem.allergensMapped,
            result.predictedAllergens ?: ""
        )

        findViewById<TextView>(R.id.tvValPrecision).text = "%.2f".format(metrics.precision)
        findViewById<TextView>(R.id.tvValRecall).text = "%.2f".format(metrics.recall)
        findViewById<TextView>(R.id.tvValF1).text = "%.2f".format(metrics.f1Score)

        val tvExact = findViewById<TextView>(R.id.tvValExactMatch)
        if (metrics.exactMatch) {
            tvExact.text = "YES"
            tvExact.setTextColor(Color.parseColor("#2E7D32")) // Green
        } else {
            tvExact.text = "NO"
            tvExact.setTextColor(Color.parseColor("#C62828")) // Red
        }

        findViewById<TextView>(R.id.tvValHamming).text = "%.3f".format(metrics.hammingLoss)
        findViewById<TextView>(R.id.tvValFNR).text = "%.2f".format(metrics.falseNegativeRate)
    }

    private fun populateTable3Safety(result: PredictionResult) {
        val metrics = MetricsCalculator.calculate(
            result.foodItem.allergensMapped,
            result.predictedAllergens ?: ""
        )

        // --- UPDATED to include (0%) or (100%) ---

        // Hallucination
        val tvHallu = findViewById<TextView>(R.id.tvValHallucination)
        if (metrics.isHallucination) {
            tvHallu.text = "DETECTED (100%)"
            tvHallu.setTextColor(Color.parseColor("#C62828")) // Red
        } else {
            tvHallu.text = "None (0%)"
            tvHallu.setTextColor(Color.parseColor("#2E7D32")) // Green
        }

        // Over-Prediction
        val tvOver = findViewById<TextView>(R.id.tvValOverPred)
        if (metrics.isOverPrediction) {
            tvOver.text = "YES (100%)"
            tvOver.setTextColor(Color.parseColor("#C62828"))
        } else {
            tvOver.text = "No (0%)"
            tvOver.setTextColor(Color.parseColor("#2E7D32"))
        }

        // Abstention Accuracy
        val tvAbst = findViewById<TextView>(R.id.tvValAbstention)
        if (metrics.isAbstentionCase) {
            if (metrics.isAbstentionSuccess) {
                tvAbst.text = "Success (100%)"
                tvAbst.setTextColor(Color.parseColor("#2E7D32")) // Green
            } else {
                tvAbst.text = "Failed (0%)"
                tvAbst.setTextColor(Color.parseColor("#C62828")) // Red
            }
        } else {
            tvAbst.text = "N/A"
            tvAbst.setTextColor(Color.GRAY)
        }
    }

    private fun populateTable4Efficiency(result: PredictionResult) {
        val inf = result.metrics

        if (inf != null) {
            // --- UPDATED with Total Time ---
            findViewById<TextView>(R.id.tvValLatency).text = "${inf.latencyMs} ms"
            findViewById<TextView>(R.id.tvValTotalTime).text = "${inf.latencyMs} ms" // Same as Latency

            findViewById<TextView>(R.id.tvValTTFT).text = "${inf.ttft} ms"
            findViewById<TextView>(R.id.tvValOET).text = "${inf.oet} ms"
            findViewById<TextView>(R.id.tvValITPS).text = "${inf.itps} t/s"
            findViewById<TextView>(R.id.tvValOTPS).text = "${inf.otps} t/s"

            // Memory (Convert KB to MB)
            val javaMb = inf.javaHeapKb / 1024.0
            val nativeMb = inf.nativeHeapKb / 1024.0
            val pssMb = inf.totalPssKb / 1024.0

            findViewById<TextView>(R.id.tvValJava).text = "%.1f MB".format(javaMb)
            findViewById<TextView>(R.id.tvValNative).text = "%.1f MB".format(nativeMb)
            findViewById<TextView>(R.id.tvValPSS).text = "%.1f MB".format(pssMb)
        } else {
            findViewById<TextView>(R.id.tvValLatency).text = "N/A"
        }
    }
}