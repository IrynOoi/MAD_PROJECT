// FirebaseService.kt
package edu.utem.ftmk.slm02

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.delay

class FirebaseService {

    private val db = FirebaseFirestore.getInstance()

    // Collection for individual prediction details
    private val collectionPrediction = db.collection("project_predictions_final")

    // Collections for Dashboard Aggregate Data
    private val colQuality = db.collection("project_benchmarks_quality")
    private val colSafety = db.collection("project_benchmarks_safety")
    private val colEfficiency = db.collection("project_benchmarks_efficiency")

    // =========================================================================
    // PART 1: Single Item Saving ("The Bridge")
    // =========================================================================

    /**
     * Saves the detailed record AND Triggers a Dashboard update.
     */
    suspend fun savePredictionAndRefreshDashboard(result: PredictionResult, modelName: String) {
        try {
            // 1. Calculate Validation Metrics
            val valMetrics = MetricsCalculator.calculate(
                result.foodItem.allergensMapped,
                result.predictedAllergens ?: ""
            )

            // 2. Prepare Data Map
            val data = hashMapOf<String, Any>(
                "modelName" to modelName,
                "dataId" to result.foodItem.id,
                "name" to result.foodItem.name,
                "ingredients" to result.foodItem.ingredients,
                "link" to result.foodItem.link,
                "rawAllergens" to (result.foodItem.allergens ?: "None"),
                "mappedAllergens" to (result.foodItem.allergensMapped ?: "None"),
                "predictedAllergens" to (result.predictedAllergens ?: "No Prediction"),
                "timestamp" to FieldValue.serverTimestamp(),

                "validation_metrics" to hashMapOf(
                    "precision" to valMetrics.precision,
                    "recall" to valMetrics.recall,
                    "f1Score" to valMetrics.f1Score,
                    "exactMatch" to valMetrics.exactMatch,
                    "hammingLoss" to valMetrics.hammingLoss,
                    "falseNegativeRate" to valMetrics.falseNegativeRate,
                    "isHallucination" to valMetrics.isHallucination,
                    "isOverPrediction" to valMetrics.isOverPrediction,
                    "isAbstentionSuccess" to valMetrics.isAbstentionSuccess
                )
            )

            result.metrics?.let { eff ->
                data["efficiency_metrics"] = hashMapOf(
                    "latencyMs" to eff.latencyMs,
                    "ttft" to eff.ttft,
                    "itps" to eff.itps,
                    "otps" to eff.otps,
                    "oet" to eff.oet,
                    "javaHeapKb" to eff.javaHeapKb,
                    "nativeHeapKb" to eff.nativeHeapKb,
                    "totalPssKb" to eff.totalPssKb
                )
            }

            // 3. Save Record
            collectionPrediction.add(data).await()
            Log.d("FIREBASE", "Individual Prediction Saved.")

            // 4. Update Dashboard
            updateDashboardFromHistory(modelName)

        } catch (e: Exception) {
            Log.e("FIREBASE", "Error saving prediction: ${e.message}")
        }
    }

    // =========================================================================
    // PART 2: Batch Saving (Used by "Predict All")
    // =========================================================================

    suspend fun saveBatchResults(results: List<PredictionResult>): Pair<Int, Int> {
        var successCount = 0
        var failureCount = 0

        // Note: For batches, we don't usually call "savePredictionAndRefreshDashboard"
        // inside the loop because recalculating history 100 times is slow.
        // Instead, we save them as "dumb" records here (or smart ones if you prefer),
        // and MainActivity calculates the final benchmark once at the end.

        results.forEach { result ->
            try {
                // We use a simplified save here, or reuse the logic above without the refresh
                // For simplicity/speed in batch, let's just save the document.
                val valMetrics = MetricsCalculator.calculate(
                    result.foodItem.allergensMapped,
                    result.predictedAllergens ?: ""
                )

                val data = hashMapOf<String, Any>(
                    "modelName" to "Batch_Run", // Or pass model name if available
                    "dataId" to result.foodItem.id,
                    "name" to result.foodItem.name,
                    "predictedAllergens" to (result.predictedAllergens ?: ""),
                    "timestamp" to FieldValue.serverTimestamp(),
                    "validation_metrics" to hashMapOf(
                        "f1Score" to valMetrics.f1Score,
                        "isHallucination" to valMetrics.isHallucination
                    )
                )

                collectionPrediction.add(data).await()
                successCount++
                delay(10) // Small delay to avoid write exhaustion
            } catch (e: Exception) {
                failureCount++
            }
        }
        return Pair(successCount, failureCount)
    }

    // =========================================================================
    // PART 3: Dashboard Aggregation Logic
    // =========================================================================

    private suspend fun updateDashboardFromHistory(modelName: String) {
        try {
            val snapshot = collectionPrediction.whereEqualTo("modelName", modelName).get().await()
            val docs = snapshot.documents
            if (docs.isEmpty()) return

            val count = docs.size.toDouble()

            // Simple Accumulators
            var sumF1 = 0.0
            var sumLat = 0.0
            // ... (You can expand this with all variables as needed, keeping it simple for now to prevent errors)

            for (doc in docs) {
                val valMap = doc.get("validation_metrics") as? Map<String, Any>
                val effMap = doc.get("efficiency_metrics") as? Map<String, Any>

                if (valMap != null) sumF1 += (valMap["f1Score"] as? Number)?.toDouble() ?: 0.0
                if (effMap != null) sumLat += (effMap["latencyMs"] as? Number)?.toDouble() ?: 0.0
            }

            // Calls the PUBLIC saveBenchmark
            saveBenchmark(
                modelName = modelName,
                avgPrecision = 0.0, // Fill with real calcs if needed
                avgRecall = 0.0,
                avgF1 = sumF1 / count,
                avgEmr = 0.0, avgHamming = 0.0, avgFnr = 0.0,
                abstentionAccuracy = 0.0, hallucinationRate = 0.0, overPredictionRate = 0.0,
                avgLatency = sumLat / count,
                avgTtft = 0.0, avgItps = 0.0, avgOtps = 0.0, avgOet = 0.0,
                avgJavaHeap = 0.0, avgNativeHeap = 0.0, avgPss = 0.0
            )

        } catch (e: Exception) {
            Log.e("FIREBASE", "Error updating dashboard: ${e.message}")
        }
    }

    // =========================================================================
    // PART 4: Public Benchmark Saver (MUST BE PUBLIC)
    // =========================================================================

    // CHANGED: Removed 'private'. Access is now public.
    suspend fun saveBenchmark(
        modelName: String,
        avgPrecision: Double, avgRecall: Double, avgF1: Double, avgEmr: Double, avgHamming: Double, avgFnr: Double,
        abstentionAccuracy: Double, hallucinationRate: Double, overPredictionRate: Double,
        avgLatency: Double, avgTtft: Double, avgItps: Double, avgOtps: Double, avgOet: Double,
        avgJavaHeap: Double, avgNativeHeap: Double, avgPss: Double
    ) {
        val timestamp = FieldValue.serverTimestamp()

        val qualityData = hashMapOf(
            "modelName" to modelName, "Precision" to avgPrecision, "Recall" to avgRecall,
            "F1 Score" to avgF1, "Exact Match Ratio" to avgEmr, "Hamming Loss" to avgHamming,
            "False Negative Rate" to avgFnr, "timestamp" to timestamp
        )

        val safetyData = hashMapOf(
            "modelName" to modelName, "Abstention Accuracy" to abstentionAccuracy,
            "Hallucination Rate" to hallucinationRate, "Over-Prediction Rate" to overPredictionRate,
            "timestamp" to timestamp
        )

        val efficiencyData = hashMapOf(
            "modelName" to modelName, "Latency" to avgLatency, "Time-to-First-Token" to avgTtft,
            "Input Token Per Second" to avgItps, "Output Token Per Second" to avgOtps,
            "Output Evaluation Time" to avgOet, "Total Time" to avgLatency,
            "Java Heap" to avgJavaHeap, "Native Heap" to avgNativeHeap,
            "Proportional Set Size" to avgPss, "timestamp" to timestamp
        )

        try {
            val t1 = colQuality.document(modelName).set(qualityData)
            val t2 = colSafety.document(modelName).set(safetyData)
            val t3 = colEfficiency.document(modelName).set(efficiencyData)
            Tasks.whenAll(t1, t2, t3).await()
            Log.d("FIREBASE", "Benchmark tables saved.")
        } catch (e: Exception) {
            Log.e("FIREBASE", "Error saving benchmarks: ${e.message}")
        }
    }

    // =========================================================================
    // PART 5: Reader (For DashboardActivity)
    // =========================================================================

    suspend fun getAllBenchmarks(): List<Map<String, Any>> {
        return try {
            val t1 = colQuality.get()
            val t2 = colSafety.get()
            val t3 = colEfficiency.get()

            Tasks.whenAllSuccess<QuerySnapshot>(t1, t2, t3).await()

            val qualityDocs = t1.result?.documents ?: emptyList()
            val safetyDocs = t2.result?.documents ?: emptyList()
            val efficiencyDocs = t3.result?.documents ?: emptyList()

            val mergedList = mutableListOf<Map<String, Any>>()

            qualityDocs.forEach { qDoc ->
                val modelName = qDoc.id
                val qData = qDoc.data ?: emptyMap()
                val sData = safetyDocs.find { it.id == modelName }?.data ?: emptyMap()
                val eData = efficiencyDocs.find { it.id == modelName }?.data ?: emptyMap()
                mergedList.add(qData + sData + eData)
            }
            mergedList
        } catch (e: Exception) {
            Log.e("FIREBASE", "Error fetching benchmarks: ${e.message}")
            emptyList()
        }
    }
}