package com.example.makeitmeme

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONException
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var etPlayerName: EditText
    private lateinit var etRoomCode: EditText
    private lateinit var btnJoinRoom: Button
    private lateinit var btnCreateRoom: Button
    private lateinit var spinnerRounds: Spinner

    companion object {
        private const val SERVER_URL = "https://makeitmemebackend.onrender.com"
    }

    override fun onStart() {
        super.onStart()
        // Parar el servicio en segundo plano al volver al menú principal
        stopService(Intent(this, GameForegroundService::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etPlayerName = findViewById(R.id.etPlayerName)
        etRoomCode = findViewById(R.id.etRoomCode)
        btnJoinRoom = findViewById(R.id.btnJoinRoom)
        btnCreateRoom = findViewById(R.id.btnCreateRoom)
        spinnerRounds = findViewById(R.id.spinnerRounds)

        connectToServer()

        btnCreateRoom.setOnClickListener {
            if (!SocketHandler.isConnected()) {
                Toast.makeText(this, "Sin conexión al servidor, espera un momento", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val name = etPlayerName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Ingresa tu nombre", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val data = JSONObject()
            data.put("name", name)
            val numRounds = spinnerRounds.selectedItem.toString().toInt()
            data.put("num_rounds", numRounds)
            SocketHandler.getSocket().emit("create_room", data)
        }

        btnJoinRoom.setOnClickListener {
            if (!SocketHandler.isConnected()) {
                Toast.makeText(this, "Sin conexión al servidor, espera un momento", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val name = etPlayerName.text.toString().trim()
            val room = etRoomCode.text.toString().trim().uppercase()
            if (name.isEmpty() || room.isEmpty()) {
                Toast.makeText(this, "Ingresa nombre y código de sala", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val data = JSONObject()
            data.put("name", name)
            data.put("room_id", room)
            SocketHandler.getSocket().emit("join_room", data)
        }
    }

    private fun connectToServer() {
        if (SocketHandler.isInitialized()) {
            SocketHandler.getSocket().off()
            SocketHandler.closeConnection()
        }

        SocketHandler.setSocket(SERVER_URL)
        val mSocket = SocketHandler.getSocket()

        mSocket.on("room_created") { args ->
            val data = args[0] as JSONObject
            runOnUiThread {
                try {
                    val intent = Intent(this, LobbyActivity::class.java)
                    intent.putExtra("ROOM_ID", data.getString("room_id"))
                    intent.putExtra("PLAYER_NAME", etPlayerName.text.toString())
                    intent.putExtra("PLAYER_ID", data.getString("player_id"))
                    intent.putExtra("IS_HOST", true)
                    intent.putExtra("INITIAL_PLAYERS", data.getJSONArray("players").toString())
                    startActivity(intent)
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
        }

        mSocket.on("joined_room") { args ->
            val data = args[0] as JSONObject
            runOnUiThread {
                try {
                    val intent = Intent(this, LobbyActivity::class.java)
                    intent.putExtra("ROOM_ID", data.getString("room_id"))
                    intent.putExtra("PLAYER_NAME", etPlayerName.text.toString())
                    intent.putExtra("PLAYER_ID", data.getString("player_id"))
                    intent.putExtra("IS_HOST", false)
                    intent.putExtra("INITIAL_PLAYERS", data.getJSONArray("players").toString())
                    startActivity(intent)
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
        }

        mSocket.on("error") { args ->
            runOnUiThread {
                val data = args[0] as JSONObject
                Toast.makeText(this, data.optString("message", "Error"), Toast.LENGTH_SHORT).show()
            }
        }

        SocketHandler.establishConnection()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (SocketHandler.isInitialized()) {
            val mSocket = SocketHandler.getSocket()
            mSocket.off("room_created")
            mSocket.off("joined_room")
            mSocket.off("error")
        }
    }
}
