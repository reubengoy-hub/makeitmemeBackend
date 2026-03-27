package com.example.makeitmeme

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class VotingActivity : AppCompatActivity() {

    private lateinit var tvVotingStatus: TextView
    private lateinit var ivMemeToVote: ImageView
    private lateinit var tvScoreLabel: TextView
    private lateinit var sbVote: SeekBar
    private lateinit var btnSubmitVote: Button
    private lateinit var tvWaitingVotes: TextView
    private lateinit var voteControls: View

    private var roomId: String = ""
    private var memesList = mutableListOf<JSONObject>()
    private var currentMemeIndex = 0
    private val votes = JSONObject()
    private var mySid: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voting)

        tvVotingStatus = findViewById(R.id.tvVotingStatus)
        ivMemeToVote = findViewById(R.id.ivMemeToVote)
        tvScoreLabel = findViewById(R.id.tvScoreLabel)
        sbVote = findViewById(R.id.sbVote)
        btnSubmitVote = findViewById(R.id.btnSubmitVote)
        tvWaitingVotes = findViewById(R.id.tvWaitingVotes)
        voteControls = findViewById(R.id.voteControls)

        roomId = intent.getStringExtra("ROOM_ID") ?: ""
        val memesData = intent.getStringExtra("MEMES_DATA") ?: "[]"

        val mSocket = SocketHandler.getSocket()
        // Fix: mSocket.id() can return null if not connected
        mySid = mSocket.id() ?: ""

        try {
            val array = JSONArray(memesData)
            for (i in 0 until array.length()) {
                val meme = array.getJSONObject(i)
                if (meme.getString("sid") != mySid) {
                    memesList.add(meme)
                }
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }

        sbVote.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val score = progress + 1
                tvScoreLabel.text = "Puntuación: $score"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnSubmitVote.setOnClickListener {
            val score = sbVote.progress + 1
            val currentMeme = memesList[currentMemeIndex]
            votes.put(currentMeme.getString("sid"), score)

            currentMemeIndex++
            showNextMeme()
        }

        mSocket.on("partial_results") { args ->
            val data = args[0] as JSONObject
            runOnUiThread {
                val intent = Intent(this, ScoreboardActivity::class.java)
                intent.putExtra("ROOM_ID", roomId)
                intent.putExtra("RESULTS_DATA", data.toString())
                intent.putExtra("IS_FINAL", false)
                startActivity(intent)
                finish()
            }
        }

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

        showNextMeme()
    }

    private fun showNextMeme() {
        if (currentMemeIndex < memesList.size) {
            tvVotingStatus.text = "Vota el Meme (${currentMemeIndex + 1}/${memesList.size})"
            val meme = memesList[currentMemeIndex]
            val base64Image = meme.getString("image")
            val imageBytes = Base64.decode(base64Image, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ivMemeToVote.setImageBitmap(bitmap)

            sbVote.progress = 4 // Default 5
            tvScoreLabel.text = "Puntuación: 5"
        } else {
            voteControls.visibility = View.GONE
            ivMemeToVote.setImageBitmap(null)
            tvVotingStatus.text = "¡Votación terminada!"
            tvWaitingVotes.visibility = View.VISIBLE

            val payload = JSONObject()
            payload.put("room_id", roomId)
            payload.put("votes", votes)
            SocketHandler.getSocket().emit("submit_vote", payload)
        }
    }

    // Fix: remove listeners to prevent memory leaks and duplicate callbacks
    override fun onDestroy() {
        super.onDestroy()
        if (SocketHandler.isInitialized()) {
            val mSocket = SocketHandler.getSocket()
            mSocket.off("partial_results")
            mSocket.off("game_over")
        }
    }
}
