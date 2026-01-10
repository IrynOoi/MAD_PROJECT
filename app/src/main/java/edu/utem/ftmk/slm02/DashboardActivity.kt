// DashboardActivity.kt
package edu.utem.ftmk.slm02

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageButton
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class DashboardActivity : AppCompatActivity() {

    private lateinit var tableQuality: TableLayout
    private lateinit var tableSafety: TableLayout
    private lateinit var tableEfficiency: TableLayout
    private val firebaseService = FirebaseService()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        val btnBack = findViewById<ImageButton>(R.id.btnBackDashboard)
        btnBack.setOnClickListener { finish() }

        tableQuality = findViewById(R.id.tableQuality)
        tableSafety = findViewById(R.id.tableSafety)
        tableEfficiency = findViewById(R.id.tableEfficiency)

        fetchAndDisplayBenchmarks()
    }

    private fun fetchAndDisplayBenchmarks() {
        lifecycleScope.launch {
            try {
                val data = firebaseService.getAllBenchmarks()
                if (data.isNotEmpty()) {
                    populateQualityTable(data)
                    populateSafetyTable(data)
                    populateEfficiencyTable(data)
                } else {
                    Toast.makeText(this@DashboardActivity, "No benchmark data found.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@DashboardActivity, "Error loading dashboard.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun populateQualityTable(data: List<Map<String, Any>>) {
        tableQuality.removeAllViews()
        val headers = listOf("Model", "Prec", "Rec", "F1", "EMR (%)", "Hamm", "FNR (%)")
        addHeaderRow(tableQuality, headers)

        for (row in data) {
            val model = (row["modelName"] as? String ?: "?").replace(".gguf", "")
            val prec = (row["Precision"] as? Number)?.toDouble() ?: 0.0
            val rec = (row["Recall"] as? Number)?.toDouble() ?: 0.0
            val f1 = (row["F1 Score"] as? Number)?.toDouble() ?: 0.0
            val emr = (row["Exact Match Ratio (%)"] as? Number)?.toDouble() ?: 0.0
            val ham = (row["Hamming Loss"] as? Number)?.toDouble() ?: 0.0
            val fnr = (row["False Negative Rate (%)"] as? Number)?.toDouble() ?: 0.0

            val values = listOf(
                model, "%.2f".format(prec), "%.2f".format(rec), "%.2f".format(f1),
                "%.1f%%".format(emr), "%.3f".format(ham), "%.1f%%".format(fnr)
            )
            addDataRow(tableQuality, values)
        }
    }

    private fun populateSafetyTable(data: List<Map<String, Any>>) {
        tableSafety.removeAllViews()
        val headers = listOf("Model", "Hallu (%)", "Over (%)", "Abst (%)")
        addHeaderRow(tableSafety, headers)

        for (row in data) {
            val model = (row["modelName"] as? String ?: "?").replace(".gguf", "")
            val hall = (row["Hallucination Rate (%)"] as? Number)?.toDouble() ?: 0.0
            val over = (row["Over-Prediction Rate (%)"] as? Number)?.toDouble() ?: 0.0
            val abst = (row["Abstention Accuracy (%)"] as? Number)?.toDouble() ?: 0.0

            val values = listOf(
                model, "%.1f%%".format(hall), "%.1f%%".format(over), "%.1f%%".format(abst)
            )
            addDataRow(tableSafety, values)
        }
    }

    private fun populateEfficiencyTable(data: List<Map<String, Any>>) {
        tableEfficiency.removeAllViews()
        val headers = listOf("Model", "Lat(s)", "Total(s)", "TTFT(s)", "ITPS(t/s)", "OTPS(t/s)", "OET(s)", "Java(MB)", "Nat(MB)", "PSS(MB)")
        addHeaderRow(tableEfficiency, headers)

        for (row in data) {
            val model = (row["modelName"] as? String ?: "?").replace(".gguf", "")

            // Reading NEW keys with Units from Firebase
            val lat = (row["Latency (s)"] as? Number)?.toDouble() ?: 0.0
            val total = (row["Total Time (s)"] as? Number)?.toDouble() ?: 0.0
            val ttft = (row["Time-to-First-Token (s)"] as? Number)?.toDouble() ?: 0.0
            val itps = (row["Input Token Per Second (tokens/s)"] as? Number)?.toDouble() ?: 0.0
            val otps = (row["Output Token Per Second (tokens/s)"] as? Number)?.toDouble() ?: 0.0
            val oet = (row["Output Evaluation Time (s)"] as? Number)?.toDouble() ?: 0.0
            val java = (row["Java Heap (MB)"] as? Number)?.toDouble() ?: 0.0
            val nat = (row["Native Heap (MB)"] as? Number)?.toDouble() ?: 0.0
            val pss = (row["Proportional Set Size (MB)"] as? Number)?.toDouble() ?: 0.0

            val values = listOf(
                model, "%.2f".format(lat), "%.2f".format(total), "%.2f".format(ttft),
                "%.1f".format(itps), "%.1f".format(otps), "%.2f".format(oet),
                "%.1f".format(java), "%.1f".format(nat), "%.1f".format(pss)
            )
            addDataRow(tableEfficiency, values)
        }
    }

    private fun addHeaderRow(table: TableLayout, headers: List<String>) {
        val row = TableRow(this)
        row.setBackgroundColor(Color.parseColor("#E0E0E0"))
        row.setPadding(8, 16, 8, 16)
        for (title in headers) {
            val tv = TextView(this)
            tv.text = title
            tv.setTypeface(null, Typeface.BOLD)
            tv.setTextColor(Color.BLACK)
            tv.setPadding(16, 16, 16, 16)
            tv.gravity = Gravity.CENTER
            row.addView(tv)
        }
        table.addView(row)
    }

    private fun addDataRow(table: TableLayout, values: List<String>) {
        val row = TableRow(this)
        row.setPadding(8, 16, 8, 16)
        for (value in values) {
            val tv = TextView(this)
            tv.text = value
            tv.setTextColor(Color.DKGRAY)
            tv.setPadding(16, 16, 16, 16)
            tv.gravity = Gravity.CENTER
            row.addView(tv)
        }
        table.addView(row)
    }
}