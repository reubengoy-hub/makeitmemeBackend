package com.example.makeitmeme

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONException
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var etPlayerName: EditText
    private lateinit var etRoomCode: EditText
    private lateinit var btnJoinRoom: Button
    private lateinit var btnCreateRoom: Button
    private lateinit var etServerIp: EditText
    private lateinit var btnConnect: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etPlayerName = findViewById(R.id.etPlayerName)
        etRoomCode = findViewById(R.id.etRoomCode)
        btnJoinRoom = findViewById(R.id.btnJoinRoom)
        btnCreateRoom = findViewById(R.id.btnCreateRoom)
        etServerIp = findViewById(R.id.etServerIp)
        btnConnect = findViewById(R.id.btnConnect)

        btnConnect.setOnClickListener {
            val ip = etServerIp.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "Ingresa la IP del servidor", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            connectToServer(ip)
        }

        btnCreateRoom.setOnClickListener {
            if (!SocketHandler.isInitialized()) {
                Toast.makeText(this, "Conéctate al servidor primero", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val name = etPlayerName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Ingresa tu nombre", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val data = JSONObject()
            data.put("name", name)
            data.put("num_rounds", 3)
            SocketHandler.getSocket().emit("create_room", data)
        }

        btnJoinRoom.setOnClickListener {
            if (!SocketHandler.isInitialized()) {
                Toast.makeText(this, "Conéctate al servidor primero", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val name = etPlayerName.text.toString().trim()
            val room = etRoomCode.text.toString().trim().uppercase()
            if (name.isEmpty() || room.isEmpty()) {
                Toast.makeText(this, "Ingresa nombre y código", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val data = JSONObject()
            data.put("name", name)
            data.put("room_id", room)
            SocketHandler.getSocket().emit("join_room", data)
        }
    }

    private fun connectToServer(ip: String) {
        if (SocketHandler.isInitialized()) {
            val oldSocket = SocketHandler.getSocket()
            SocketHandler.closeConnection()
            oldSocket.off()
        }

        SocketHandler.setSocket(ip)
        val mSocket = SocketHandler.getSocket()

        // Fix: register listeners BEFORE connecting to avoid race condition on EVENT_CONNECT
        mSocket.on(io.socket.client.Socket.EVENT_CONNECT) {
            runOnUiThread {
                Toast.makeText(this, "Conectado al servidor Socket.IO", Toast.LENGTH_SHORT).show()
            }
        }

        mSocket.on(io.socket.client.Socket.EVENT_CONNECT_ERROR) { args ->
            val error = if (args.isNotEmpty()) args[0].toString() else "Error desconocido"
            runOnUiThread {
                Toast.makeText(this, "Error de conexión: $error", Toast.LENGTH_LONG).show()
            }
        }

        mSocket.on("room_created") { args ->
            val data = args[0] as JSONObject
            runOnUiThread {
                try {
                    val roomId = data.getString("room_id")
                    val playerId = data.getString("player_id")
                    val intent = Intent(this, LobbyActivity::class.java)
                    intent.putExtra("ROOM_ID", roomId)
                    intent.putExtra("PLAYER_NAME", etPlayerName.text.toString())
                    intent.putExtra("PLAYER_ID", playerId)
                    intent.putExtra("IS_HOST", true)
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
                    val roomId = data.getString("room_id")
                    val playerId = data.getString("player_id")
                    val intent = Intent(this, LobbyActivity::class.java)
                    intent.putExtra("ROOM_ID", roomId)
                    intent.putExtra("PLAYER_NAME", etPlayerName.text.toString())
                    intent.putExtra("PLAYER_ID", playerId)
                    intent.putExtra("IS_HOST", false)
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
            mSocket.off(io.socket.client.Socket.EVENT_CONNECT)
            mSocket.off(io.socket.client.Socket.EVENT_CONNECT_ERROR)
            mSocket.off("room_created")
            mSocket.off("joined_room")
            mSocket.off("error")
        }
    }
}
