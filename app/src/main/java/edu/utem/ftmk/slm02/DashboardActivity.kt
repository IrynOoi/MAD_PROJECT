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
            exportToExcel()
        }

        // Initialize Tables
        tableQuality = findViewById(R.id.tableQuality)
        tableSafety = findViewById(R.id.tableSafety)
        tableEfficiency = findViewById(R.id.tableEfficiency)

        // Load Data
        fetchAndDisplayBenchmarks()
    }

    private fun exportToExcel() {
        if (currentBenchmarkData.isEmpty()) {
            Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder()

        // 1. Start HTML Document
        sb.append("<html><body>")

        // Title
        sb.append("<h1>Model Performance Dashboard Report</h1>")
        sb.append("<p>Generated on: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}</p>")
        sb.append("<hr>")

        // ==========================================
        // TABLE 1: PREDICTION QUALITY
        // ==========================================
        sb.append("<h3>1. PREDICTION QUALITY METRICS</h3>")
        sb.append("<table border='1' cellspacing='0' cellpadding='5'>")
        // Header
        sb.append("<tr style='background-color:#E0E0E0; font-weight:bold;'>")
        sb.append("<th>Model</th><th>Precision</th><th>Recall</th><th>F1 Score</th><th>Exact Match (%)</th><th>Hamming Loss</th><th>FNR (%)</th>")
        sb.append("</tr>")

        // Data
        for (row in currentBenchmarkData) {
            val model = (row["modelName"] as? String ?: "?").replace(".gguf", "")
            val prec = (row["Precision"] as? Number)?.toDouble() ?: 0.0
            val rec = (row["Recall"] as? Number)?.toDouble() ?: 0.0
            val f1 = (row["F1 Score"] as? Number)?.toDouble() ?: 0.0
            val emr = (row["Exact Match Ratio (%)"] as? Number)?.toDouble() ?: 0.0
            val ham = (row["Hamming Loss"] as? Number)?.toDouble() ?: 0.0
            val fnr = (row["False Negative Rate (%)"] as? Number)?.toDouble() ?: 0.0

            sb.append("<tr>")
            sb.append("<td>$model</td>")
            sb.append("<td>${"%.4f".format(prec)}</td>")
            sb.append("<td>${"%.4f".format(rec)}</td>")
            sb.append("<td>${"%.4f".format(f1)}</td>")
            sb.append("<td>${"%.1f".format(emr)}%</td>")
            sb.append("<td>${"%.4f".format(ham)}</td>")
            sb.append("<td>${"%.1f".format(fnr)}%</td>")
            sb.append("</tr>")
        }
        sb.append("</table><br><br>")

        // ==========================================
        // TABLE 2: SAFETY METRICS
        // ==========================================
        sb.append("<h3>2. SAFETY METRICS</h3>")
        sb.append("<table border='1' cellspacing='0' cellpadding='5'>")
        sb.append("<tr style='background-color:#E0E0E0; font-weight:bold;'>")
        sb.append("<th>Model</th><th>Hallucination Rate (%)</th><th>Over-Prediction Rate (%)</th><th>Abstention Accuracy (%)</th>")
        sb.append("</tr>")

        for (row in currentBenchmarkData) {
            val model = (row["modelName"] as? String ?: "?").replace(".gguf", "")
            val hall = (row["Hallucination Rate (%)"] as? Number)?.toDouble() ?: 0.0
            val over = (row["Over-Prediction Rate (%)"] as? Number)?.toDouble() ?: 0.0
            val abst = (row["Abstention Accuracy (%)"] as? Number)?.toDouble() ?: 0.0

            sb.append("<tr>")
            sb.append("<td>$model</td>")
            sb.append("<td>${"%.1f".format(hall)}%</td>")
            sb.append("<td>${"%.1f".format(over)}%</td>")
            sb.append("<td>${"%.1f".format(abst)}%</td>")
            sb.append("</tr>")
        }
        sb.append("</table><br><br>")

        // ==========================================
        // TABLE 3: EFFICIENCY METRICS
        // ==========================================
        sb.append("<h3>3. ON-DEVICE EFFICIENCY METRICS</h3>")
        sb.append("<table border='1' cellspacing='0' cellpadding='5'>")
        sb.append("<tr style='background-color:#E0E0E0; font-weight:bold;'>")
        sb.append("<th>Model</th><th>Latency (s)</th><th>Total Time (s)</th><th>TTFT (s)</th><th>Input T/s</th><th>Output T/s</th><th>Eval Time (s)</th><th>Java Heap (MB)</th><th>Native Heap (MB)</th><th>PSS (MB)</th>")
        sb.append("</tr>")

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

            sb.append("<tr>")
            sb.append("<td>$model</td>")
            sb.append("<td>${"%.2f".format(lat)}</td>")
            sb.append("<td>${"%.2f".format(total)}</td>")
            sb.append("<td>${"%.2f".format(ttft)}</td>")
            sb.append("<td>${"%.1f".format(itps)}</td>")
            sb.append("<td>${"%.1f".format(otps)}</td>")
            sb.append("<td>${"%.2f".format(oet)}</td>")
            sb.append("<td>${"%.1f".format(java)}</td>")
            sb.append("<td>${"%.1f".format(nat)}</td>")
            sb.append("<td>${"%.1f".format(pss)}</td>")
            sb.append("</tr>")
        }
        sb.append("</table>")
        sb.append("</body></html>")

        // ==========================================
        // SAVE AND SHARE
        // ==========================================
        try {
            // Save as .xls (Excel) so it opens with formatting
            val fileName = "benchmark_report.xls"
            val file = File(cacheDir, fileName)
            file.writeText(sb.toString())

            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND)

            // Use MIME type for Excel/HTML
            intent.type = "application/vnd.ms-excel"
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