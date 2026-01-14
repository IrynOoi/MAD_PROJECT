// DashboardActivity.kt
package edu.utem.ftmk.slm02

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class DashboardActivity : AppCompatActivity() {

    private lateinit var tableQuality: TableLayout
    private lateinit var tableSafety: TableLayout
    private lateinit var tableEfficiency: TableLayout
    private val firebaseService = FirebaseService()

    // 1. Variable to store data for export
    private var currentBenchmarkData: List<Map<String, Any>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        // Setup Back Button
        val btnBack = findViewById<ImageButton>(R.id.btnBackDashboard)
        btnBack.setOnClickListener { finish() }

        // 2. Setup Export Button
        // Ensure you have an ImageButton or Button with id 'btnExportDashboard' in your XML
        val btnExport = findViewById<View>(R.id.btnExportDashboard)
        btnExport.setOnClickListener {
            exportToCsv()
        }

        // Initialize Tables
        tableQuality = findViewById(R.id.tableQuality)
        tableSafety = findViewById(R.id.tableSafety)
        tableEfficiency = findViewById(R.id.tableEfficiency)

        // Load Data
        fetchAndDisplayBenchmarks()
    }

    private fun exportToCsv() {
        if (currentBenchmarkData.isEmpty()) {
            Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder()
        // CSV Header
        sb.append("Model,Precision,Recall,F1 Score,Exact Match(%),Hamming Loss,FNR(%),Hallucination(%),Over-Prediction(%),Abstention Acc(%),Latency(s),Total Time(s),Java Heap(MB)\n")

        for (row in currentBenchmarkData) {
            val model = (row["modelName"] as? String ?: "?").replace(".gguf", "")

            // Quality
            val prec = (row["Precision"] as? Number)?.toDouble() ?: 0.0
            val rec = (row["Recall"] as? Number)?.toDouble() ?: 0.0
            val f1 = (row["F1 Score"] as? Number)?.toDouble() ?: 0.0
            val emr = (row["Exact Match Ratio (%)"] as? Number)?.toDouble() ?: 0.0
            val ham = (row["Hamming Loss"] as? Number)?.toDouble() ?: 0.0
            val fnr = (row["False Negative Rate (%)"] as? Number)?.toDouble() ?: 0.0

            // Safety
            val hall = (row["Hallucination Rate (%)"] as? Number)?.toDouble() ?: 0.0
            val over = (row["Over-Prediction Rate (%)"] as? Number)?.toDouble() ?: 0.0
            val abst = (row["Abstention Accuracy (%)"] as? Number)?.toDouble() ?: 0.0

            // Efficiency
            val lat = (row["Latency (s)"] as? Number)?.toDouble() ?: 0.0
            val total = (row["Total Time (s)"] as? Number)?.toDouble() ?: 0.0
            val java = (row["Java Heap (MB)"] as? Number)?.toDouble() ?: 0.0

            sb.append("$model,$prec,$rec,$f1,$emr,$ham,$fnr,$hall,$over,$abst,$lat,$total,$java\n")
        }

        try {
            // Save to internal storage (cache directory)
            val fileName = "benchmark_export.csv"
            val file = File(cacheDir, fileName)
            file.writeText(sb.toString())

            // Share using FileProvider
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/csv"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Export Dashboard Data"))

        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun fetchAndDisplayBenchmarks() {
        lifecycleScope.launch {
            try {
                val data = firebaseService.getAllBenchmarks()
                currentBenchmarkData = data // <--- Store data for export
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