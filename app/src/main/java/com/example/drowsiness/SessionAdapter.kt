package com.example.drowsiness

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.drowsiness.data.DrivingSession
import java.util.Locale

class SessionAdapter(private var sessions: List<DrivingSession> = emptyList()) :
    RecyclerView.Adapter<SessionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSessionDate: TextView = view.findViewById(R.id.tvSessionDate)
        val tvSafetyBadge: TextView = view.findViewById(R.id.tvSafetyBadge)
        val tvSessionDuration: TextView = view.findViewById(R.id.tvSessionDuration)
        val tvWarningCount: TextView = view.findViewById(R.id.tvWarningCount)
        val tvCriticalCount: TextView = view.findViewById(R.id.tvCriticalCount)
        val tvMaxClosure: TextView = view.findViewById(R.id.tvMaxClosure)
        val tvAvgPerclos: TextView = view.findViewById(R.id.tvAvgPerclos)
        val tvYawnCount: TextView = view.findViewById(R.id.tvYawnCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val s = sessions[position]
        holder.tvSessionDate.text = s.formattedDate
        holder.tvSessionDuration.text = "Duration: ${s.formattedDuration}"
        holder.tvSafetyBadge.text = s.safetyStatus
        holder.tvSafetyBadge.setTextColor(s.safetyColor)
        holder.tvWarningCount.text = s.warningEventsCount.toString()
        holder.tvCriticalCount.text = s.criticalEventsCount.toString()
        holder.tvMaxClosure.text = "${s.longestClosureMs} ms"
        holder.tvAvgPerclos.text = String.format(Locale.getDefault(), "%.1f%%", s.averagePerclos * 100.0)
        holder.tvYawnCount.text = s.yawnCount.toString()
    }

    override fun getItemCount(): Int = sessions.size

    fun submitList(newList: List<DrivingSession>) {
        sessions = newList
        notifyDataSetChanged()
    }
}
