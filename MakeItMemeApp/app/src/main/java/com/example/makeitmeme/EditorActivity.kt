package com.example.makeitmeme

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.random.Random

class EditorActivity : AppCompatActivity() {

    private lateinit var tvRoundInfo: TextView
    private lateinit var ivMeme: ImageView
    private lateinit var etMemeText: EditText
    private lateinit var btnSubmitMeme: Button

    private var roomId: String = ""
    private var currentRound: Int = 0
    private var totalRounds: Int = 0

    private var originalBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        tvRoundInfo = findViewById(R.id.tvRoundInfo)
        ivMeme = findViewById(R.id.ivMeme)
        etMemeText = findViewById(R.id.etMemeText)
        btnSubmitMeme = findViewById(R.id.btnSubmitMeme)

        roomId = intent.getStringExtra("ROOM_ID") ?: ""
        currentRound = intent.getIntExtra("CURRENT_ROUND", 1)
        totalRounds = intent.getIntExtra("TOTAL_ROUNDS", 3)

        tvRoundInfo.text = "Ronda $currentRound/$totalRounds - ¡Crea tu meme!"

        loadRandomMeme()

        val mSocket = SocketHandler.getSocket()

        mSocket.on("start_voting") { args ->
            val data = args[0] as JSONArray
            runOnUiThread {
                val intent = Intent(this, VotingActivity::class.java)
                intent.putExtra("ROOM_ID", roomId)
                intent.putExtra("MEMES_DATA", data.toString())
                startActivity(intent)
                finish()
            }
        }

        btnSubmitMeme.setOnClickListener {
            val text = etMemeText.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            btnSubmitMeme.isEnabled = false
            btnSubmitMeme.text = "Enviando..."

            Thread {
                val base64Image = generateMemeImage(text)
                if (base64Image != null) {
                    val payload = JSONObject()
                    payload.put("room_id", roomId)
                    payload.put("image", base64Image)
                    mSocket.emit("submit_meme", payload)
                } else {
                    // Fix: guard against updating UI on a destroyed activity
                    if (!isFinishing && !isDestroyed) {
                        runOnUiThread {
                            btnSubmitMeme.isEnabled = true
                            btnSubmitMeme.text = "Error, intenta de nuevo"
                        }
                    }
                }
            }.start()
        }
    }

    private fun loadRandomMeme() {
        try {
            val files = assets.list("memes")?.filter { it.endsWith(".jpg") || it.endsWith(".png") }
            if (!files.isNullOrEmpty()) {
                val selectedMemeFileName = files[Random.nextInt(files.size)]
                val istr: InputStream = assets.open("memes/$selectedMemeFileName")
                originalBitmap = BitmapFactory.decodeStream(istr)
                ivMeme.setImageBitmap(originalBitmap)
                istr.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun generateMemeImage(text: String): String? {
        val bitmap = originalBitmap ?: return null

        val width = bitmap.width
        val originalHeight = bitmap.height
        val extraHeight = originalHeight / 5
        val newHeight = originalHeight + extraHeight

        val resultBitmap = Bitmap.createBitmap(width, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        val textPaint = TextPaint()
        textPaint.color = Color.BLACK
        textPaint.isAntiAlias = true
        textPaint.textSize = extraHeight * 0.4f

        val padding = 20
        val textWidth = width - (padding * 2)

        val staticLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, textPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, textPaint, textWidth, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false)
        }

        canvas.save()
        val textY = originalHeight + (extraHeight - staticLayout.height) / 2f
        canvas.translate(padding.toFloat(), textY)
        staticLayout.draw(canvas)
        canvas.restore()

        val outputStream = ByteArrayOutputStream()
        resultBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    // Fix: remove listeners to prevent memory leaks and duplicate callbacks
    override fun onDestroy() {
        super.onDestroy()
        if (SocketHandler.isInitialized()) {
            SocketHandler.getSocket().off("start_voting")
        }
    }
}
