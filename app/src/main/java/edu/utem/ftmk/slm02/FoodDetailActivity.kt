// FoodDetailActivity.kt
package edu.utem.ftmk.slm02

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FoodDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.food_detail)

        val btnBack = findViewById<ImageButton>(R.id.btnBackDetail)
        btnBack.setOnClickListener { finish() }

        val predictionResult = intent.getParcelableExtra<PredictionResult>("prediction_result")

        if (predictionResult != null) {
            val food = predictionResult.foodItem

            // 1. Data ID
            // XML has "Data ID: "; Code sets "#123"
            findViewById<TextView>(R.id.tvDetailId).text = "#${food.id}"

            // 2. Name
            // XML has "Name" label; Code sets "Pizza"
            findViewById<TextView>(R.id.tvDetailName).text = food.name

            // 3. Ingredients
            findViewById<TextView>(R.id.tvDetailIngredients).text = food.ingredients

            // 4. Raw Allergens
            val raw = if (food.allergens.isNullOrEmpty() || food.allergens == "empty") "None" else food.allergens
            findViewById<TextView>(R.id.tvDetailRawAllergens).text = raw

            // 5. Mapped Allergens
            val mapped = if (food.allergensMapped.isNullOrEmpty()) "None" else food.allergensMapped
            findViewById<TextView>(R.id.tvDetailMappedAllergens).text = mapped

            // 6. Predicted Allergens
            findViewById<TextView>(R.id.tvDetailPredicted).text = predictionResult.predictedAllergens

            // 7. Inference Metrics
            val metricsText = predictionResult.metrics?.toString() ?: "No metrics available"
            findViewById<TextView>(R.id.tvDetailMetrics).text = metricsText

            // 8. Link
            findViewById<TextView>(R.id.tvDetailLink).text = food.link

            // 9. Timestamp
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val dateStr = sdf.format(Date(predictionResult.timestamp))
            findViewById<TextView>(R.id.tvDetailTimestamp).text = dateStr
        }
    }
}