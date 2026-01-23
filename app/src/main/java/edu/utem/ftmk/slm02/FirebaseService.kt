// FirebaseService.kt
package edu.utem.ftmk.slm02

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
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
    // PART 1: Single Item Saving
    // =========================================================================

    suspend fun savePredictionAndRefreshDashboard(result: PredictionResult, modelName: String) {
        try {
            val valMetrics = MetricsCalculator.calculate(
                result.foodItem.allergensMapped,
                result.predictedAllergens ?: ""
            )

            val raw = result.foodItem.allergens
            val safeRaw = if (raw.isNullOrEmpty() || raw.equals("empty", ignoreCase = true)) "EMPTY" else raw
            val mapped = result.foodItem.allergensMapped
            val safeMapped = if (mapped.isNullOrEmpty() || mapped.equals("empty", ignoreCase = true)) "EMPTY" else mapped

            val data = hashMapOf<String, Any>(
                "modelName" to modelName,
                "dataId" to result.foodItem.id,
                "name" to result.foodItem.name,
                "ingredients" to result.foodItem.ingredients,
                "link" to result.foodItem.link,
                "rawAllergens" to safeRaw,
                "mappedAllergens" to safeMapped,
                "predictedAllergens" to (result.predictedAllergens ?: "No Prediction"),
                "timestamp" to FieldValue.serverTimestamp(),

                "quality_metrics" to hashMapOf(
                    "Precision" to valMetrics.precision,
                    "Recall" to valMetrics.recall,
                    "F1 Score" to valMetrics.f1Score,
                    "Exact Match" to valMetrics.exactMatch,
                    "Hamming Loss" to valMetrics.hammingLoss,
                    "False Negative Rate" to valMetrics.falseNegativeRate
                ),

                "safety_metrics" to hashMapOf(
                    "isHallucination" to valMetrics.isHallucination,
                    "isOverPrediction" to valMetrics.isOverPrediction,
                    "isAbstentionSuccess" to valMetrics.isAbstentionSuccess
                )
            )

            // --- CHANGED: Added Units and "Total Time" ---
            result.metrics?.let { eff ->
                data["efficiency_metrics"] = hashMapOf(
                    // Time metrics in Seconds (s)
                    "Latency (s)" to (eff.latencyMs / 1000.0),
                    "Total Time (s)" to (eff.latencyMs / 1000.0), // Explicitly added as per Table 4
                    "Time-to-First-Token (s)" to (eff.ttft / 1000.0),
                    "Output Eval Time (s)" to (eff.oet / 1000.0),

                    // Throughput metrics in tokens per second
                    "Input Token Per Second (tokens/s)" to eff.itps.toDouble(),
                    "Output Token Per Second (tokens/s)" to eff.otps.toDouble(),

                    // Memory metrics in MB
                    "Java Heap (MB)" to (eff.javaHeapKb / 1024.0),
                    "Native Heap (MB)" to (eff.nativeHeapKb / 1024.0),
                    "Proportional Set Size (MB)" to (eff.totalPssKb / 1024.0)
                )
            }

            collectionPrediction.add(data).await()
            Log.d("FIREBASE", "Individual Prediction Saved with Units.")

            updateDashboardFromHistory(modelName)

        } catch (e: Exception) {
            Log.e("FIREBASE", "Error saving prediction: ${e.message}")
        }
    }

    // =========================================================================
    // PART 2: Batch Saving
    // =========================================================================

    suspend fun saveBatchResults(results: List<PredictionResult>): Pair<Int, Int> {
        var successCount = 0
        var failureCount = 0

        results.forEach { result ->
            try {
                val valMetrics = MetricsCalculator.calculate(
                    result.foodItem.allergensMapped,
                    result.predictedAllergens ?: ""
                )

                val raw = result.foodItem.allergens
                val safeRaw = if (raw.isNullOrEmpty() || raw.equals("empty", ignoreCase = true)) "EMPTY" else raw
                val mapped = result.foodItem.allergensMapped
                val safeMapped = if (mapped.isNullOrEmpty() || mapped.equals("empty", ignoreCase = true)) "EMPTY" else mapped

                val data = hashMapOf<String, Any>(
                    "modelName" to result.modelName,
                    "dataId" to result.foodItem.id,
                    "name" to result.foodItem.name,
                    "ingredients" to result.foodItem.ingredients,
                    "link" to result.foodItem.link,
                    "rawAllergens" to safeRaw,
                    "mappedAllergens" to safeMapped,
                    "predictedAllergens" to (result.predictedAllergens ?: "No Prediction"),
                    "timestamp" to FieldValue.serverTimestamp(),

                    "quality_metrics" to hashMapOf(
                        "Precision" to valMetrics.precision,
                        "Recall" to valMetrics.recall,
                        "F1 Score" to valMetrics.f1Score,
                        "Exact Match" to valMetrics.exactMatch,
                        "Hamming Loss" to valMetrics.hammingLoss,
                        "False Negative Rate" to valMetrics.falseNegativeRate
                    ),

                    "safety_metrics" to hashMapOf(
                        "isHallucination" to valMetrics.isHallucination,
                        "isOverPrediction" to valMetrics.isOverPrediction,
                        "isAbstentionSuccess" to valMetrics.isAbstentionSuccess
                    )
                )

                // --- CHANGED: Added Units and "Total Time" ---
                result.metrics?.let { eff ->
                    data["efficiency_metrics"] = hashMapOf(
                        "Latency (s)" to (eff.latencyMs / 1000.0),
                        "Total Time (s)" to (eff.latencyMs / 1000.0),
                        "Time-to-First-Token (s)" to (eff.ttft / 1000.0),
                        "Output Eval Time (s)" to (eff.oet / 1000.0),
                        "Input Token Per Second (tokens/s)" to eff.itps.toDouble(),
                        "Output Token Per Second (tokens/s)" to eff.otps.toDouble(),
                        "Java Heap (MB)" to (eff.javaHeapKb / 1024.0),
                        "Native Heap (MB)" to (eff.nativeHeapKb / 1024.0),
                        "Proportional Set Size (MB)" to (eff.totalPssKb / 1024.0)
                    )
                }

                collectionPrediction.add(data).await()
                successCount++
                delay(10)
            } catch (e: Exception) {
                failureCount++
                Log.e("FIREBASE", "Batch save failed for item ${result.foodItem.name}: ${e.message}")
            }
        }
        return Pair(successCount, failureCount)
    }

    // =========================================================================
    // PART 3: Dashboard Aggregation
    // =========================================================================

    private suspend fun updateDashboardFromHistory(modelName: String) {
        try {
            val snapshot = collectionPrediction.whereEqualTo("modelName", modelName).get().await()
            val docs = snapshot.documents
            if (docs.isEmpty()) return

            val count = docs.size.toDouble()

            // Accumulators
            var sumPrecision = 0.0; var sumRecall = 0.0; var sumF1 = 0.0
            var sumEmr = 0.0; var sumHamming = 0.0; var sumFnr = 0.0
            var countHallucination = 0.0; var countOverPrediction = 0.0
            var countAbstentionSuccess = 0.0; var countAbstentionCases = 0.0

            // Efficiency Accumulators
            var sumLat = 0.0; var sumTotalTime = 0.0; var sumTtft = 0.0; var sumOet = 0.0
            var sumItps = 0.0; var sumOtps = 0.0
            var sumJavaMb = 0.0; var sumNativeMb = 0.0; var sumPssMb = 0.0

            for (doc in docs) {
                val qualMap = doc.get("quality_metrics") as? Map<String, Any>
                val safeMap = doc.get("safety_metrics") as? Map<String, Any>
                val effMap = doc.get("efficiency_metrics") as? Map<String, Any>

                if (qualMap != null) {
                    sumPrecision += (qualMap["Precision"] as? Number)?.toDouble() ?: 0.0
                    sumRecall += (qualMap["Recall"] as? Number)?.toDouble() ?: 0.0
                    sumF1 += (qualMap["F1 Score"] as? Number)?.toDouble() ?: 0.0
                    if ((qualMap["Exact Match"] as? Boolean) == true) sumEmr += 1.0
                    sumHamming += (qualMap["Hamming Loss"] as? Number)?.toDouble() ?: 0.0
                    sumFnr += (qualMap["False Negative Rate"] as? Number)?.toDouble() ?: 0.0
                }

                if (safeMap != null) {
                    if ((safeMap["isHallucination"] as? Boolean) == true) countHallucination += 1.0
                    if ((safeMap["isOverPrediction"] as? Boolean) == true) countOverPrediction += 1.0

                    val rawAllergens = doc.getString("rawAllergens") ?: ""
                    val isAbstentionCase = rawAllergens.equals("None", ignoreCase = true) || rawAllergens.equals("EMPTY", ignoreCase = true) || rawAllergens.isBlank()

                    if (isAbstentionCase) {
                        countAbstentionCases += 1.0
                        if ((safeMap["isAbstentionSuccess"] as? Boolean) == true) countAbstentionSuccess += 1.0
                    }
                }

                if (effMap != null) {
                    // Aggregate using the NEW unit keys
                    sumLat += (effMap["Latency (s)"] as? Number)?.toDouble() ?: 0.0
                    sumTotalTime += (effMap["Total Time (s)"] as? Number)?.toDouble() ?: 0.0
                    sumTtft += (effMap["Time-to-First-Token (s)"] as? Number)?.toDouble() ?: 0.0
                    sumOet += (effMap["Output Eval Time (s)"] as? Number)?.toDouble() ?: 0.0
                    sumItps += (effMap["Input Token Per Second (tokens/s)"] as? Number)?.toDouble() ?: 0.0
                    sumOtps += (effMap["Output Token Per Second (tokens/s)"] as? Number)?.toDouble() ?: 0.0
                    sumJavaMb += (effMap["Java Heap (MB)"] as? Number)?.toDouble() ?: 0.0
                    sumNativeMb += (effMap["Native Heap (MB)"] as? Number)?.toDouble() ?: 0.0
                    sumPssMb += (effMap["Proportional Set Size (MB)"] as? Number)?.toDouble() ?: 0.0
                }
            }

            val avgHallucinationRate = (countHallucination / count) * 100.0
            val avgOverPredictionRate = (countOverPrediction / count) * 100.0
            val abstentionAccuracy = if (countAbstentionCases > 0) (countAbstentionSuccess / countAbstentionCases) * 100.0 else 0.0

            saveBenchmark(
                modelName, sumPrecision/count, sumRecall/count, sumF1/count, sumEmr/count, sumHamming/count, sumFnr/count,
                abstentionAccuracy, avgHallucinationRate, avgOverPredictionRate,
                sumLat/count, sumTotalTime/count, sumTtft/count, sumItps/count, sumOtps/count, sumOet/count,
                sumJavaMb/count, sumNativeMb/count, sumPssMb/count
            )

        } catch (e: Exception) {
            Log.e("FIREBASE", "Error updating dashboard: ${e.message}")
        }
    }

    suspend fun saveBenchmark(
        modelName: String,
        avgPrecision: Double, avgRecall: Double, avgF1: Double, avgEmr: Double, avgHamming: Double, avgFnr: Double,
        abstentionAccuracy: Double, hallucinationRate: Double, overPredictionRate: Double,
        avgLatency: Double, avgTotalTime: Double, avgTtft: Double, avgItps: Double, avgOtps: Double, avgOet: Double,
        avgJavaHeap: Double, avgNativeHeap: Double, avgPss: Double
    ) {
        val timestamp = FieldValue.serverTimestamp()

        val qualityData = hashMapOf(
            "modelName" to modelName, "Precision" to avgPrecision, "Recall" to avgRecall,
            "F1 Score" to avgF1, "Exact Match Ratio (%)" to avgEmr, "Hamming Loss" to avgHamming,
            "False Negative Rate (%)" to avgFnr, "timestamp" to timestamp
        )

        val safetyData = hashMapOf(
            "modelName" to modelName, "Abstention Accuracy (%)" to abstentionAccuracy,
            "Hallucination Rate (%)" to hallucinationRate, "Over-Prediction Rate (%)" to overPredictionRate,
            "timestamp" to timestamp
        )

        // EFFICIENCY WITH UNITS (Already averaged)
        val efficiencyData = hashMapOf(
            "modelName" to modelName,
            "Latency (s)" to avgLatency,
            "Total Time (s)" to avgTotalTime,
            "Time-to-First-Token (s)" to avgTtft,
            "Input Token Per Second (tokens/s)" to avgItps,
            "Output Token Per Second (tokens/s)" to avgOtps,
            "Output Evaluation Time (s)" to avgOet,
            "Java Heap (MB)" to avgJavaHeap,
            "Native Heap (MB)" to avgNativeHeap,
            "Proportional Set Size (MB)" to avgPss,
            "timestamp" to timestamp
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
    // PART 4: History Retrieval
    // =========================================================================

    suspend fun getPredictionHistory(): List<PredictionResult> {
        return try {
            // Fetch ALL predictions, ordered by newest first
            // REMOVED .limit(100) so it retrieves everything
            val snapshot = collectionPrediction
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .await()

            snapshot.documents.mapNotNull { doc ->
                try {
                    // Reconstruct FoodItem
                    val foodItem = FoodItem(
                        id = doc.get("dataId")?.toString() ?: "0",
                        name = doc.getString("name") ?: "Unknown",
                        ingredients = doc.getString("ingredients") ?: "",
                        link = doc.getString("link") ?: "",
                        allergens = doc.getString("rawAllergens") ?: "",
                        allergensMapped = doc.getString("mappedAllergens") ?: ""
                    )

                    // Reconstruct Metrics (Partial)
                    val effMap = doc.get("efficiency_metrics") as? Map<String, Any>
                    val metrics = if (effMap != null) {
                        InferenceMetrics(
                            latencyMs = ((effMap["Latency (s)"] as? Number)?.toDouble() ?: 0.0 * 1000).toLong(),
                            javaHeapKb = 0, nativeHeapKb = 0, totalPssKb = 0, ttft = 0, itps = 0, otps = 0, oet = 0
                        )
                    } else null

                    PredictionResult(
                        foodItem = foodItem,
                        predictedAllergens = doc.getString("predictedAllergens") ?: "",
                        modelName = doc.getString("modelName") ?: "Unknown",
                        metrics = metrics,
                        firestoreId = doc.id
                    )
                } catch (e: Exception) {
                    // ADDED: Log the error so you know if a specific item is broken
                    Log.e("FIREBASE_HISTORY", "Error parsing item ${doc.id}", e)
                    null // Skip malformed documents
                }
            }
        } catch (e: Exception) {
            Log.e("FIREBASE", "Error fetching history", e)
            emptyList()
        }
    }

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
            emptyList()
        }
    }
}
