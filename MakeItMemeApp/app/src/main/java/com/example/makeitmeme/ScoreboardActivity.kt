package com.example.makeitmeme

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class ScoreboardActivity : AppCompatActivity() {

    private lateinit var tvScoreboardTitle: TextView
    private lateinit var lvScores: ListView
    private lateinit var btnNextRound: Button
    private lateinit var winnerSection: LinearLayout
    private lateinit var tvWinnerText: TextView
    private lateinit var tvRunnerUpText: TextView
    private lateinit var btnPlayAgain: Button

    private var roomId: String = ""
    private var isFinal: Boolean = false
    private val scoresList = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scoreboard)

        tvScoreboardTitle = findViewById(R.id.tvScoreboardTitle)
        lvScores = findViewById(R.id.lvScores)
        btnNextRound = findViewById(R.id.btnNextRound)
        winnerSection = findViewById(R.id.winnerSection)
        tvWinnerText = findViewById(R.id.tvWinnerText)
        tvRunnerUpText = findViewById(R.id.tvRunnerUpText)
        btnPlayAgain = findViewById(R.id.btnPlayAgain)

        roomId = intent.getStringExtra("ROOM_ID") ?: ""
        isFinal = intent.getBooleanExtra("IS_FINAL", false)
        val resultsData = intent.getStringExtra("RESULTS_DATA") ?: "{}"

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, scoresList)
        lvScores.adapter = adapter

        try {
            val data = JSONObject(resultsData)
            val resultsArray = data.getJSONArray("results")

            for (i in 0 until resultsArray.length()) {
                val player = resultsArray.getJSONObject(i)
                val name = player.getString("name")
                val totalScore = player.getInt("total_score")

                var roundScoreStr = ""
                if (!isFinal && player.has("round_score")) {
                    val rs = player.getInt("round_score")
                    roundScoreStr = " (+$rs esta ronda)"
                }

                scoresList.add("${i + 1}. $name - $totalScore pts$roundScoreStr")
            }
            adapter.notifyDataSetChanged()

            if (isFinal) {
                tvScoreboardTitle.text = "Resultados Finales"
                btnNextRound.visibility = View.GONE
                winnerSection.visibility = View.VISIBLE

                if (resultsArray.length() > 0) {
                    val winner = resultsArray.getJSONObject(0).getString("name")
                    tvWinnerText.text = "🏆 Ganador: $winner 🏆"
                }
                if (resultsArray.length() > 1) {
                    val runnerUp = resultsArray.getJSONObject(1).getString("name")
                    tvRunnerUpText.text = "🥈 Subcampeón: $runnerUp"
                } else {
                    tvRunnerUpText.visibility = View.GONE
                }
            } else {
                val currentRound = data.getInt("round")
                val totalRounds = data.getInt("total_rounds")
                tvScoreboardTitle.text = "Fin de Ronda $currentRound/$totalRounds"
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        val mSocket = SocketHandler.getSocket()

        mSocket.on("round_started") { args ->
            val data = args[0] as JSONObject
            runOnUiThread {
                val intent = Intent(this, EditorActivity::class.java)
                intent.putExtra("ROOM_ID", roomId)
                intent.putExtra("CURRENT_ROUND", data.getInt("round"))
                intent.putExtra("TOTAL_ROUNDS", data.getInt("total_rounds"))
                startActivity(intent)
                finish()
            }
        }

        // Fix: use startActivity instead of recreate() — putExtra on the current intent
        // has no effect on recreate() since it uses the original intent from launch
        mSocket.on("game_over") { args ->
            val data = args[0] as JSONObject
            runOnUiThread {
                val intent = Intent(this, ScoreboardActivity::class.java)
                intent.putExtra("ROOM_ID", roomId)
                intent.putExtra("RESULTS_DATA", data.toString())
                intent.putExtra("IS_FINAL", true)
                startActivity(intent)
                finish()
            }
        }

        btnNextRound.setOnClickListener {
            btnNextRound.isEnabled = false
            btnNextRound.text = "Esperando..."
            val payload = JSONObject()
            payload.put("room_id", roomId)
            mSocket.emit("ready_next_round", payload)
        }

        btnPlayAgain.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }
    }

    // Fix: remove listeners to prevent memory leaks and duplicate callbacks
    override fun onDestroy() {
        super.onDestroy()
        if (SocketHandler.isInitialized()) {
            val mSocket = SocketHandler.getSocket()
            mSocket.off("round_started")
            mSocket.off("game_over")
        }
    }
}
