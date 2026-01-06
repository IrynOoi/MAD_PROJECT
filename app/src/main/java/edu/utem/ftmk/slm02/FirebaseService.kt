// FirebaseService.kt
package edu.utem.ftmk.slm02

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

class FirebaseService {

    private val db = FirebaseFirestore.getInstance()
    private val collectionPrediction = db.collection("project_predictions_final")

    // Define 3 separate collections for the tables
    private val colQuality = db.collection("project_benchmarks_quality")
    private val colSafety = db.collection("project_benchmarks_safety")
    private val colEfficiency = db.collection("project_benchmarks_efficiency")

    suspend fun saveBenchmark(
        modelName: String,
        // Table 2: Quality
        avgPrecision: Double,
        avgRecall: Double,
        avgF1: Double,
        avgEmr: Double,
        avgHamming: Double,
        avgFnr: Double,
        // Table 3: Safety
        abstentionAccuracy: Double, // TNR
        hallucinationRate: Double,
        overPredictionRate: Double,
        // Table 4: Efficiency
        avgLatency: Double,
        avgTtft: Double,
        avgItps: Double,
        avgOtps: Double,
        avgOet: Double,
        avgJavaHeap: Double,
        avgNativeHeap: Double,
        avgPss: Double
    ) {
        val timestamp = FieldValue.serverTimestamp()

        // 1. Prepare Data for Table 2 (Quality) - Using PDF Attribute Names
        val qualityData = hashMapOf(
            "modelName" to modelName,
            "Precision" to avgPrecision,
            "Recall" to avgRecall,
            "F1 Score" to avgF1,
            "Exact Match Ratio" to avgEmr,
            "Hamming Loss" to avgHamming,
            "False Negative Rate" to avgFnr,
            "timestamp" to timestamp
        )

        // 2. Prepare Data for Table 3 (Safety) - Using PDF Attribute Names
        val safetyData = hashMapOf(
            "modelName" to modelName,
            "Abstention Accuracy" to abstentionAccuracy,
            "Hallucination Rate" to hallucinationRate,
            "Over-Prediction Rate" to overPredictionRate,
            "timestamp" to timestamp
        )

        // 3. Prepare Data for Table 4 (Efficiency) - Using PDF Attribute Names
        val efficiencyData = hashMapOf(
            "modelName" to modelName,
            "Latency" to avgLatency,
            "Time-to-First-Token" to avgTtft,
            "Input Token Per Second" to avgItps,
            "Output Token Per Second" to avgOtps,
            "Output Evaluation Time" to avgOet,
            "Total Time" to avgLatency, // Mapping Latency to Total Time as they are effectively the same in this context
            "Java Heap" to avgJavaHeap,
            "Native Heap" to avgNativeHeap,
            "Proportional Set Size" to avgPss,
            "timestamp" to timestamp
        )

        try {
            // Write to all 3 collections concurrently
            val t1 = colQuality.document(modelName).set(qualityData)
            val t2 = colSafety.document(modelName).set(safetyData)
            val t3 = colEfficiency.document(modelName).set(efficiencyData)

            Tasks.whenAll(t1, t2, t3).await()
            Log.d("FIREBASE", "All benchmarks saved successfully.")
        } catch (e: Exception) {
            Log.e("FIREBASE", "Error saving benchmarks: ${e.message}")
        }
    }

    // Fetches from all 3 collections and merges data by modelName
    suspend fun getAllBenchmarks(): List<Map<String, Any>> {
        return try {
            val t1 = colQuality.get()
            val t2 = colSafety.get()
            val t3 = colEfficiency.get()

            Tasks.whenAllSuccess<QuerySnapshot>(t1, t2, t3).await()

            val qualityDocs = t1.result?.documents ?: emptyList()
            val safetyDocs = t2.result?.documents ?: emptyList()
            val efficiencyDocs = t3.result?.documents ?: emptyList()

            // Merge logic: Use Quality docs as base
            val mergedList = mutableListOf<Map<String, Any>>()

            qualityDocs.forEach { qDoc ->
                val modelName = qDoc.id
                val qData = qDoc.data ?: emptyMap()

                // Find corresponding docs
                val sData = safetyDocs.find { it.id == modelName }?.data ?: emptyMap()
                val eData = efficiencyDocs.find { it.id == modelName }?.data ?: emptyMap()

                // Combine maps
                val combined = qData + sData + eData
                mergedList.add(combined)
            }
            mergedList
        } catch (e: Exception) {
            Log.e("FIREBASE", "Error fetching benchmarks: ${e.message}")
            emptyList()
        }
    }

    suspend fun savePredictionResult(result: PredictionResult): String {
        return try {
            val data = hashMapOf<String, Any>(
                "dataId" to result.foodItem.id,
                "name" to result.foodItem.name,
                "ingredients" to result.foodItem.ingredients,
                "allergens" to result.foodItem.allergens,
                "mappedAllergens" to result.foodItem.allergensMapped,
                "predictedAllergens" to result.predictedAllergens,
                "timestamp" to FieldValue.serverTimestamp()
            )

            result.metrics?.let { metrics ->
                data["metrics"] = hashMapOf(
                    "latencyMs" to metrics.latencyMs,
                    "ttft" to metrics.ttft,
                    "itps" to metrics.itps,
                    "otps" to metrics.otps,
                    "oet" to metrics.oet,
                    "javaHeapKb" to metrics.javaHeapKb,
                    "nativeHeapKb" to metrics.nativeHeapKb,
                    "totalPssKb" to metrics.totalPssKb
                )
            }

            val documentRef = collectionPrediction.add(data).await()
            documentRef.id
        } catch (e: Exception) {
            Log.e("FIREBASE", "Error saving to Firestore: ${e.message}")
            ""
        }
    }

    suspend fun saveBatchResults(results: List<PredictionResult>): Pair<Int, Int> {
        var successCount = 0
        var failureCount = 0

        results.forEach { result ->
            try {
                val docId = savePredictionResult(result)
                if (docId.isNotEmpty()) {
                    successCount++
                } else {
                    failureCount++
                }
                delay(20) // Small delay to prevent write exhaustion
            } catch (e: Exception) {
                failureCount++
            }
        }
        return Pair(successCount, failureCount)
    }
}