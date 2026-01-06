//DashboardActivity.kt

package edu.utem.ftmk.slm02

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class DashboardActivity : AppCompatActivity() {
    private lateinit var firebaseService: FirebaseService

    // 3 Tables (Combined Efficiency)
    private lateinit var tableQuality: TableLayout
    private lateinit var tableSafety: TableLayout
    private lateinit var tableEfficiency: TableLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        firebaseService = FirebaseService()

        // Find Views
        tableQuality = findViewById(R.id.tableQuality)
        tableSafety = findViewById(R.id.tableSafety)
        tableEfficiency = findViewById(R.id.tableEfficiency)

        // Back Button
        findViewById<View>(R.id.btnBackDashboard).setOnClickListener {
            finish()
        }

        loadBenchmarks()
    }

    private fun loadBenchmarks() {
        lifecycleScope.launch {
            // Clear tables
            tableQuality.removeAllViews()
            tableSafety.removeAllViews()
            tableEfficiency.removeAllViews()

            // 1. Setup Headers
            // Table 1: Quality (Table 2 in PDF)
            setupHeader(tableQuality, listOf("Model", "F1", "Prec", "Rec", "EMR", "Hamm", "FNR"))

            // Table 2: Safety (Table 3 in PDF)
            setupHeader(tableSafety, listOf("Model", "TNR", "Hallu%", "Over%"))

            // Table 3: Efficiency (Table 4 in PDF)
            setupHeader(tableEfficiency, listOf("Model", "Lat(s)", "TTFT(s)", "ITPS", "OTPS", "OET(s)", "Total(s)", "Java", "Nat", "PSS"))

            val data = firebaseService.getAllBenchmarks()
            // Updated sort key to match new Firebase attribute
            val sortedData = data.sortedByDescending { it["F1 Score"]?.toDoubleOrZero() ?: 0.0 }

            sortedData.forEach { metrics ->
                val name = (metrics["modelName"] as? String ?: "?").replace(".gguf", "")

                // Populate Table 1: Quality (Keys updated to match PDF)
                val f1 = metrics["F1 Score"].toDoubleOrZero()
                val prec = metrics["Precision"].toDoubleOrZero()
                val rec = metrics["Recall"].toDoubleOrZero()
                val emr = metrics["Exact Match Ratio"].toDoubleOrZero()
                val hamm = metrics["Hamming Loss"].toDoubleOrZero()
                val fnr = metrics["False Negative Rate"].toDoubleOrZero()

                addRow(tableQuality, listOf(
                    name,
                    "%.2f".format(f1),
                    "%.2f".format(prec),
                    "%.2f".format(rec),
                    "%.2f".format(emr),
                    "%.3f".format(hamm),
                    "%.2f".format(fnr)
                ))

                // Populate Table 2: Safety (Keys updated to match PDF)
                val safe = metrics["Abstention Accuracy"].toDoubleOrZero()
                val hallu = metrics["Hallucination Rate"].toDoubleOrZero()
                val over = metrics["Over-Prediction Rate"].toDoubleOrZero()

                addRow(tableSafety, listOf(
                    name,
                    "%.0f%%".format(safe), // TNR
                    "%.0f%%".format(hallu),
                    "%.0f%%".format(over)
                ))

                // Populate Table 3: Efficiency (Keys updated to match PDF)
                val lat = metrics["Latency"].toDoubleOrZero() / 1000.0 // ms to sec
                val ttft = metrics["Time-to-First-Token"].toDoubleOrZero() / 1000.0     // ms to sec
                val itps = metrics["Input Token Per Second"].toDoubleOrZero()
                val otps = metrics["Output Token Per Second"].toDoubleOrZero()
                val oet = metrics["Output Evaluation Time"].toDoubleOrZero() / 1000.0      // ms to sec
                val totalTime = metrics["Total Time"].toDoubleOrZero() / 1000.0 // ms to sec

                val java = metrics["Java Heap"].toDoubleOrZero() // Already MB
                val nat = metrics["Native Heap"].toDoubleOrZero() // Already MB
                val pss = metrics["Proportional Set Size"].toDoubleOrZero() // Already MB

                addRow(tableEfficiency, listOf(
                    name,
                    "%.2f".format(lat),
                    "%.2f".format(ttft),
                    "%.1f".format(itps),
                    "%.1f".format(otps),
                    "%.2f".format(oet),
                    "%.2f".format(totalTime),
                    "%.1f".format(java),
                    "%.1f".format(nat),
                    "%.1f".format(pss)
                ))
            }
        }
    }

    // Helper to safely convert any Number (Int, Long, Double) to Double
    private fun Any?.toDoubleOrZero(): Double {
        return (this as? Number)?.toDouble() ?: 0.0
    }

    private fun setupHeader(table: TableLayout, headers: List<String>) {
        val row = TableRow(this)
        row.setBackgroundColor(Color.parseColor("#E0E0E0"))
        headers.forEach { text ->
            val tv = TextView(this).apply {
                this.text = text
                setPadding(16, 16, 16, 16)
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.BLACK)
            }
            row.addView(tv)
        }
        table.addView(row)
    }

    private fun addRow(table: TableLayout, values: List<String>) {
        val row = TableRow(this)
        values.forEach { text ->
            val tv = TextView(this).apply {
                this.text = text
                setPadding(16, 16, 16, 16)
                textSize = 12f
                setTextColor(Color.DKGRAY)
            }
            row.addView(tv)
        }
        row.setBackgroundResource(android.R.drawable.list_selector_background)
        table.addView(row)
    }
}