// PredictionResult.kt
package edu.utem.ftmk.slm02

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
@Parcelize
data class PredictionResult(
    val foodItem: FoodItem,
    val predictedAllergens: String,
    val modelName: String = "Unknown Model",
    val timestamp: Long = System.currentTimeMillis(),
    val metrics: InferenceMetrics? = null,
    val firestoreId: String = ""
) : Parcelable