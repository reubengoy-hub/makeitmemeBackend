package com.example.makeitmeme

import io.socket.client.IO
import io.socket.client.Socket
import java.net.URISyntaxException

object SocketHandler {
    lateinit var mSocket: Socket

    @Synchronized
    fun setSocket(ip: String) {
        try {
            val url = if (ip.startsWith("http")) ip else "http://$ip:5000"
            mSocket = IO.socket(url)
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun isInitialized(): Boolean {
        return this::mSocket.isInitialized
    }

    @Synchronized
    fun getSocket(): Socket {
        return mSocket
    }
    
    @Synchronized
    fun establishConnection() {
        mSocket.connect()
    }

    @Synchronized
    fun closeConnection() {
        mSocket.disconnect()
    }
}
