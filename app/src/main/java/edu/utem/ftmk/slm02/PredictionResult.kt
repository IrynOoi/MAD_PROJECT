// PredictionResult.kt

package edu.utem.ftmk.slm02

import android.os.Parcelable
import edu.utem.ftmk.slm02.InferenceMetrics
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue

@Parcelize
data class PredictionResult(
    val foodItem: FoodItem,
    val predictedAllergens: String,
    val timestamp: Long = System.currentTimeMillis(),
    // If InferenceMetrics is NOT Parcelable, use @RawValue to treat it as a generic object
    // If you own InferenceMetrics code, make it @Parcelize and remove @RawValue
    // CHANGED: Removed @RawValue because InferenceMetrics is now Parcelable
    val metrics: InferenceMetrics? = null,
    val firestoreId: String = ""
) : Parcelable