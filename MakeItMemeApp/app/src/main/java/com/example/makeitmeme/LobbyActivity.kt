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
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class LobbyActivity : AppCompatActivity() {

    private lateinit var tvRoomCode: TextView
    private lateinit var lvPlayers: ListView
    private lateinit var hostControls: LinearLayout
    private lateinit var btnStartGame: Button
    private lateinit var tvWaitingMessage: TextView

    private var roomId: String = ""
    private var myPlayerId: String = ""
    private var isHost: Boolean = false
    private val playersList = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lobby)

        tvRoomCode = findViewById(R.id.tvRoomCode)
        lvPlayers = findViewById(R.id.lvPlayers)
        hostControls = findViewById(R.id.hostControls)
        btnStartGame = findViewById(R.id.btnStartGame)
        tvWaitingMessage = findViewById(R.id.tvWaitingMessage)

        roomId = intent.getStringExtra("ROOM_ID") ?: ""
        myPlayerId = intent.getStringExtra("PLAYER_ID") ?: ""
        isHost = intent.getBooleanExtra("IS_HOST", false)

        tvRoomCode.text = "Sala: $roomId"

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, playersList)
        lvPlayers.adapter = adapter

        updateHostUI()

        val mSocket = SocketHandler.getSocket()

        mSocket.on("player_list_update") { args ->
            runOnUiThread {
                try {
                    val array = args[0] as JSONArray
                    playersList.clear()
                    for (i in 0 until array.length()) {
                        val player = array.getJSONObject(i)
                        val name = player.getString("name")
                        val host = player.getBoolean("is_host")
                        playersList.add(if (host) "$name (Anfitrión)" else name)
                    }
                    adapter.notifyDataSetChanged()
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
        }

        // Fix: handle host transfer when original host disconnects
        mSocket.on("host_changed") { args ->
            val data = args[0] as JSONObject
            runOnUiThread {
                val newHostSid = data.getString("host_sid")
                isHost = (newHostSid == myPlayerId)
                updateHostUI()
            }
        }

        mSocket.on("round_started") { args ->
            val data = args[0] as JSONObject
            runOnUiThread {
                try {
                    val currentRound = data.getInt("round")
                    val totalRounds = data.getInt("total_rounds")
                    val intent = Intent(this, EditorActivity::class.java)
                    intent.putExtra("ROOM_ID", roomId)
                    intent.putExtra("CURRENT_ROUND", currentRound)
                    intent.putExtra("TOTAL_ROUNDS", totalRounds)
                    startActivity(intent)
                    finish()
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
        }

        btnStartGame.setOnClickListener {
            val data = JSONObject()
            data.put("room_id", roomId)
            mSocket.emit("start_game", data)
        }
    }

    private fun updateHostUI() {
        if (isHost) {
            hostControls.visibility = View.VISIBLE
            tvWaitingMessage.visibility = View.GONE
        } else {
            hostControls.visibility = View.GONE
            tvWaitingMessage.visibility = View.VISIBLE
        }
    }

    // Fix: remove listeners to prevent memory leaks and duplicate callbacks
    override fun onDestroy() {
        super.onDestroy()
        if (SocketHandler.isInitialized()) {
            val mSocket = SocketHandler.getSocket()
            mSocket.off("player_list_update")
            mSocket.off("host_changed")
            mSocket.off("round_started")
        }
    }
}
