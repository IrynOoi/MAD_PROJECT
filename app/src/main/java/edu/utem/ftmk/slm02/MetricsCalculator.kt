//MetricsCalculator.kt
package edu.utem.ftmk.slm02

import java.util.Locale

object MetricsCalculator {

    val ALL_LABELS = setOf(
        "milk", "egg", "peanut", "tree nut", "wheat", "soy", "fish", "shellfish", "sesame"
    )

    data class AllMetrics(
        // Quality (Table 2)
        val precision: Double,
        val recall: Double,
        val f1Score: Double,
        val exactMatch: Boolean,
        val hammingLoss: Double,
        val falseNegativeRate: Double,

        // Safety (Table 3) - NEW
        val isHallucination: Boolean,    // Did it predict an allergen when none existed?
        val isOverPrediction: Boolean,   // Did it predict extra allergens (FP > 0)?
        val isAbstentionSuccess: Boolean, // If Ground Truth was empty, did it predict empty?
        val isAbstentionCase: Boolean     // Is this a case where Ground Truth is empty?
    )

    fun calculate(groundTruthStr: String, predictedStr: String): AllMetrics {
        val truthSet = parseLabels(groundTruthStr)
        val predSet = parseLabels(predictedStr)

        val tp = predSet.intersect(truthSet).size.toDouble()
        val fp = predSet.subtract(truthSet).size.toDouble()
        val fn = truthSet.subtract(predSet).size.toDouble()

        // --- Quality Metrics (Table 2) ---
        val precision = if ((tp + fp) > 0) tp / (tp + fp) else 0.0
        val recall = if ((tp + fn) > 0) tp / (tp + fn) else 0.0
        val f1 = if ((2 * tp + fp + fn) > 0) (2 * tp) / (2 * tp + fp + fn) else 0.0
        val exactMatch = truthSet == predSet
        val hammingLoss = (fp + fn) / ALL_LABELS.size.toDouble()
        val fnr = if ((tp + fn) > 0) fn / (tp + fn) else 0.0

        // --- Safety Metrics (Table 3) ---

        // 1. Over-Prediction: Frequency of unnecessary predictions (Any False Positive)
        val isOverPrediction = fp > 0

        // 2. Abstention Accuracy: Ability to predict empty when ground truth is empty
        val isAbstentionCase = truthSet.isEmpty()
        val isAbstentionSuccess = isAbstentionCase && predSet.isEmpty()

        // 3. Hallucination: Predicting allergens not in input.
        // Without ingredient parsing, we treat any False Positive as a potential hallucination for the benchmark.
        val isHallucination = fp > 0

        return AllMetrics(
            precision, recall, f1, exactMatch, hammingLoss, fnr,
            isHallucination, isOverPrediction, isAbstentionSuccess, isAbstentionCase
        )
    }

    private fun parseLabels(raw: String): Set<String> {
        if (raw.isBlank() || raw.equals("empty", ignoreCase = true) || raw.equals("none", ignoreCase = true)) return emptySet()
        return raw.split(",")
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it in ALL_LABELS }
            .toSet()
    }
}