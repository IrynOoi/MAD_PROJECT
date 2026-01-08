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
    // PART 3: Dashboard Aggregation Logic (FIXED)
    // =========================================================================

    private suspend fun updateDashboardFromHistory(modelName: String) {
        try {
            val snapshot = collectionPrediction.whereEqualTo("modelName", modelName).get().await()
            val docs = snapshot.documents
            if (docs.isEmpty()) return

            val count = docs.size.toDouble()

            // --- 1. Quality Accumulators ---
            var sumPrecision = 0.0
            var sumRecall = 0.0
            var sumF1 = 0.0
            var sumEmr = 0.0
            var sumHamming = 0.0
            var sumFnr = 0.0

            // --- 2. Safety Accumulators (Counts) ---
            var countHallucination = 0.0
            var countOverPrediction = 0.0
            var countAbstentionSuccess = 0.0
            var countAbstentionCases = 0.0

            // --- 3. Efficiency Accumulators ---
            var sumLat = 0.0
            var sumTtft = 0.0
            var sumItps = 0.0
            var sumOtps = 0.0
            var sumOet = 0.0

            // Memory (Sum in KB first)
            var sumJavaKb = 0.0
            var sumNativeKb = 0.0
            var sumPssKb = 0.0

            for (doc in docs) {
                // Get Sub-Maps
                val valMap = doc.get("validation_metrics") as? Map<String, Any>
                val effMap = doc.get("efficiency_metrics") as? Map<String, Any>

                // --- Accumulate Quality ---
                if (valMap != null) {
                    sumPrecision += (valMap["precision"] as? Number)?.toDouble() ?: 0.0
                    sumRecall += (valMap["recall"] as? Number)?.toDouble() ?: 0.0
                    sumF1 += (valMap["f1Score"] as? Number)?.toDouble() ?: 0.0

                    // Boolean checks for Exact Match (assuming stored as boolean)
                    val isEm = (valMap["exactMatch"] as? Boolean) == true
                    if (isEm) sumEmr += 1.0

                    sumHamming += (valMap["hammingLoss"] as? Number)?.toDouble() ?: 0.0
                    sumFnr += (valMap["falseNegativeRate"] as? Number)?.toDouble() ?: 0.0

                    // Safety Booleans
                    if ((valMap["isHallucination"] as? Boolean) == true) countHallucination += 1.0
                    if ((valMap["isOverPrediction"] as? Boolean) == true) countOverPrediction += 1.0

                    // Abstention Logic
                    // We check if this was an abstention case using raw allergens from the document root
                    val rawAllergens = doc.getString("rawAllergens") ?: ""
                    val isAbstentionCase = rawAllergens.equals("None", ignoreCase = true) ||
                            rawAllergens.equals("empty", ignoreCase = true) ||
                            rawAllergens.isBlank()

                    if (isAbstentionCase) {
                        countAbstentionCases += 1.0
                        if ((valMap["isAbstentionSuccess"] as? Boolean) == true) {
                            countAbstentionSuccess += 1.0
                        }
                    }
                }

                // --- Accumulate Efficiency ---
                if (effMap != null) {
                    sumLat += (effMap["latencyMs"] as? Number)?.toDouble() ?: 0.0
                    sumTtft += (effMap["ttft"] as? Number)?.toDouble() ?: 0.0
                    sumItps += (effMap["itps"] as? Number)?.toDouble() ?: 0.0
                    sumOtps += (effMap["otps"] as? Number)?.toDouble() ?: 0.0
                    sumOet += (effMap["oet"] as? Number)?.toDouble() ?: 0.0

                    sumJavaKb += (effMap["javaHeapKb"] as? Number)?.toDouble() ?: 0.0
                    sumNativeKb += (effMap["nativeHeapKb"] as? Number)?.toDouble() ?: 0.0
                    sumPssKb += (effMap["totalPssKb"] as? Number)?.toDouble() ?: 0.0
                }
            }

            // --- 4. Calculate Averages ---

            // Safety Rates (0.0 to 100.0)
            val avgHallucinationRate = (countHallucination / count) * 100.0
            val avgOverPredictionRate = (countOverPrediction / count) * 100.0
            val abstentionAccuracy = if (countAbstentionCases > 0) {
                (countAbstentionSuccess / countAbstentionCases) * 100.0
            } else {
                0.0
            }

            // Memory Conversion (Average KB -> MB)
            // 1 KB = 1/1024 MB
            val avgJavaMb = (sumJavaKb / count) / 1024.0
            val avgNativeMb = (sumNativeKb / count) / 1024.0
            val avgPssMb = (sumPssKb / count) / 1024.0

            // Calls the PUBLIC saveBenchmark
            saveBenchmark(
                modelName = modelName,
                avgPrecision = sumPrecision / count,
                avgRecall = sumRecall / count,
                avgF1 = sumF1 / count,
                avgEmr = sumEmr / count,
                avgHamming = sumHamming / count,
                avgFnr = sumFnr / count,

                abstentionAccuracy = abstentionAccuracy,
                hallucinationRate = avgHallucinationRate,
                overPredictionRate = avgOverPredictionRate,

                avgLatency = sumLat / count,
                avgTtft = sumTtft / count,
                avgItps = sumItps / count,
                avgOtps = sumOtps / count,
                avgOet = sumOet / count,

                avgJavaHeap = avgJavaMb,
                avgNativeHeap = avgNativeMb,
                avgPss = avgPssMb
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