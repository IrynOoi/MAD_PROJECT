package edu.utem.ftmk.slm02

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class HistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private val firebaseService = FirebaseService()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_results) // Reusing the Results layout

        // Find views
        recyclerView = findViewById(R.id.recyclerViewResults)
        val btnBack = findViewById<ImageButton>(R.id.btnBack)

        // You might need to add these IDs to your activity_results.xml or create a new layout
        // For now, let's assume you add a ProgressBar with id 'progressBarHistory'
        // and a TextView with id 'tvEmptyHistory' to activity_results.xml
        // OR create a dedicated activity_history.xml
        progressBar = findViewById(R.id.progressBar) // Assuming you have one, or reuse one

        // Setup RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)

        btnBack.setOnClickListener { finish() }

        loadHistory()
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            // Show loading state (if you have a progress bar)
            // progressBar.visibility = View.VISIBLE

            val historyList = firebaseService.getPredictionHistory()

            // Hide loading state
            // progressBar.visibility = View.GONE

            if (historyList.isNotEmpty()) {
                val adapter = ResultsActivity.ResultsAdapter(historyList)
                recyclerView.adapter = adapter
            } else {
                Toast.makeText(this@HistoryActivity, "No history found.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}