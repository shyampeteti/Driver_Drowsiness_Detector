package com.example.drowsiness

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.drowsiness.data.SessionRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SessionHistoryActivity : AppCompatActivity() {

    private lateinit var repository: SessionRepository
    private lateinit var adapter: SessionAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var rvSessions: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_history)

        repository = SessionRepository(this)
        adapter = SessionAdapter()

        tvEmpty = findViewById(R.id.tvEmptyHistory)
        rvSessions = findViewById(R.id.rvSessions)
        rvSessions.layoutManager = LinearLayoutManager(this)
        rvSessions.adapter = adapter

        findViewById<MaterialButton>(R.id.btnClearHistory).setOnClickListener {
            showClearConfirmDialog()
        }

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        loadSessions()
    }

    private fun loadSessions() {
        repository.getAllSessions { sessions ->
            runOnUiThread {
                if (sessions.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    rvSessions.visibility = View.GONE
                } else {
                    tvEmpty.visibility = View.GONE
                    rvSessions.visibility = View.VISIBLE
                    adapter.submitList(sessions)
                }
            }
        }
    }

    private fun showClearConfirmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear Session History")
            .setMessage("Are you sure you want to delete all recorded driving sessions? This cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                repository.clearAllSessions {
                    loadSessions()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
