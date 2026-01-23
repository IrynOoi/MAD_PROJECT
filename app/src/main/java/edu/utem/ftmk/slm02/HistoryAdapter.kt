//HistoryAdapter.kt
package edu.utem.ftmk.slm02

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val historyList: List<PredictionResult>,
    private val onItemClick: (PredictionResult) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    class HistoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFoodName: TextView = view.findViewById(R.id.tvHistoryFoodName)
        val tvModelName: TextView = view.findViewById(R.id.tvHistoryModelName)
        val tvPredicted: TextView = view.findViewById(R.id.tvHistoryPredicted)
        val tvDate: TextView = view.findViewById(R.id.tvHistoryDate)
        val tvLatency: TextView = view.findViewById(R.id.tvHistoryLatency)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val item = historyList[position]

        // 1️ Food Name
        holder.tvFoodName.text = item.foodItem.name

        // 2️ Full Model Name
        holder.tvModelName.text = "Model: ${item.modelName}"

        // 3️ Predicted Allergens
        holder.tvPredicted.text = "Predicted: ${item.predictedAllergens}"

        // 4⃣ Timestamp (format: dd MMM HH:mm)
        val sdf = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
        holder.tvDate.text = sdf.format(Date(item.timestamp))
        // 5⃣ Latency (if available)
        if (item.metrics != null) {
            holder.tvLatency.text = "${item.metrics.latencyMs} ms"
            holder.tvLatency.visibility = View.VISIBLE
        } else {
            holder.tvLatency.visibility = View.GONE
        }

        // 6⃣ Click listener to open detail
        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount() = historyList.size
}
