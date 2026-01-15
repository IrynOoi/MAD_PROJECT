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

        // ==========================================
        // 1. REPORT HEADER
        // ==========================================
        // We use commas (,) to separate columns and \n for new lines
        sb.append("MODEL PERFORMANCE DASHBOARD REPORT\n")
        sb.append("Generated on:,${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n")
        sb.append("\n") // Empty row for spacing

        // ==========================================
        // 2. SECTION: PREDICTION QUALITY
        // ==========================================
        sb.append("1. PREDICTION QUALITY METRICS\n")
        // Table Headers
        sb.append("Model,Precision,Recall,F1 Score,Exact Match (%),Hamming Loss,FNR (%)\n")

        // Data Rows
        for (row in currentBenchmarkData) {
            val model = (row["modelName"] as? String ?: "?").replace(".gguf", "")
            val prec = (row["Precision"] as? Number)?.toDouble() ?: 0.0
            val rec = (row["Recall"] as? Number)?.toDouble() ?: 0.0
            val f1 = (row["F1 Score"] as? Number)?.toDouble() ?: 0.0
            val emr = (row["Exact Match Ratio (%)"] as? Number)?.toDouble() ?: 0.0
            val ham = (row["Hamming Loss"] as? Number)?.toDouble() ?: 0.0
            val fnr = (row["False Negative Rate (%)"] as? Number)?.toDouble() ?: 0.0

            sb.append("$model,")
            sb.append("${"%.4f".format(prec)},")
            sb.append("${"%.4f".format(rec)},")
            sb.append("${"%.4f".format(f1)},")
            sb.append("${"%.1f".format(emr)}%,")
            sb.append("${"%.4f".format(ham)},")
            sb.append("${"%.1f".format(fnr)}%\n")
        }
        sb.append("\n") // Empty row for spacing

        // ==========================================
        // 3. SECTION: SAFETY METRICS
        // ==========================================
        sb.append("2. SAFETY METRICS\n")
        sb.append("Model,Hallucination Rate (%),Over-Prediction Rate (%),Abstention Accuracy (%)\n")

        for (row in currentBenchmarkData) {
            val model = (row["modelName"] as? String ?: "?").replace(".gguf", "")
            val hall = (row["Hallucination Rate (%)"] as? Number)?.toDouble() ?: 0.0
            val over = (row["Over-Prediction Rate (%)"] as? Number)?.toDouble() ?: 0.0
            val abst = (row["Abstention Accuracy (%)"] as? Number)?.toDouble() ?: 0.0

            sb.append("$model,")
            sb.append("${"%.1f".format(hall)}%,")
            sb.append("${"%.1f".format(over)}%,")
            sb.append("${"%.1f".format(abst)}%\n")
        }
        sb.append("\n")

        // ==========================================
        // 4. SECTION: EFFICIENCY METRICS
        // ==========================================
        sb.append("3. ON-DEVICE EFFICIENCY METRICS\n")
        sb.append("Model,Latency (s),Total Time (s),TTFT (s),Input T/s,Output T/s,Eval Time (s),Java Heap (MB),Native Heap (MB),PSS (MB)\n")

        for (row in currentBenchmarkData) {
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

            sb.append("$model,")
            sb.append("${"%.2f".format(lat)},")
            sb.append("${"%.2f".format(total)},")
            sb.append("${"%.2f".format(ttft)},")
            sb.append("${"%.1f".format(itps)},")
            sb.append("${"%.1f".format(otps)},")
            sb.append("${"%.2f".format(oet)},")
            sb.append("${"%.1f".format(java)},")
            sb.append("${"%.1f".format(nat)},")
            sb.append("${"%.1f".format(pss)}\n")
        }

        // ==========================================
        // 5. SAVE AND SHARE
        // ==========================================
        try {
            val fileName = "benchmark_report.csv" // Using .csv extension
            val file = File(cacheDir, fileName)
            file.writeText(sb.toString())

            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND)

            // MIME type for CSV
            intent.type = "text/csv"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            startActivity(Intent.createChooser(intent, "Export Benchmark Report"))

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