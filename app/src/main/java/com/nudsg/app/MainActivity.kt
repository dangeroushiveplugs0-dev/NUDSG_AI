package com.nudsg.app

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.*
import android.app.Activity

class MainActivity : Activity() {
    private lateinit var messagesLayout: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: Button
    private val api = ApiClient()
    private val messages = mutableListOf<ChatMessage>()
    private val pendingImages = mutableListOf<Pair<String, String>>()
    private val prefs by lazy { getSharedPreferences("nudsg", MODE_PRIVATE) }

    private var endpoint: String
        get() = prefs.getString("api_base_url", BuildConfig.DEFAULT_API_BASE_URL) ?: BuildConfig.DEFAULT_API_BASE_URL
        set(value) { prefs.edit().putString("api_base_url", value.trim()).apply() }

    private val model: String
        get() = prefs.getString("model", "llama3.2") ?: "llama3.2"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 17, 22))
        }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(12), dp(12))
        }
        val title = TextView(this).apply {
            text = "NudSG"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        val endpointButton = TextView(this).apply {
            text = "  API  "
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(Color.rgb(38, 40, 50), 18)
            setOnClickListener { showEndpointDialog() }
        }
        toolbar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(endpointButton)
        val pluginsButton = TextView(this).apply {
            text = "  Plugins  "
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = rounded(Color.rgb(38, 40, 50), 18)
            setOnClickListener { showPluginsDialog() }
        }
        toolbar.addView(pluginsButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        root.addView(toolbar)

        messagesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(messagesLayout) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val composer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        input = EditText(this).apply {
            hint = "Message your local AI…"
            setHintTextColor(Color.rgb(145, 148, 160))
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 4
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = rounded(Color.rgb(31, 33, 41), 24)
        }
        val attach = Button(this).apply {
            text = "＋"
            textSize = 22f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(38, 40, 50), 22)
            setOnClickListener { chooseAttachment() }
        }
        send = Button(this).apply {
            text = "Send"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(112, 96, 220), 22)
            setOnClickListener { sendMessage(scroll) }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        composer.addView(attach, LinearLayout.LayoutParams(dp(52), dp(48)).apply { marginEnd = dp(6) })
        composer.addView(send, LinearLayout.LayoutParams(dp(78), dp(48)))
        root.addView(composer)
        setContentView(root)
    }

    private fun sendMessage(scroll: ScrollView) {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        val imagePayloads = pendingImages.map { it.second }
        val attachmentNames = pendingImages.map { it.first }
        val displayText = if (attachmentNames.isEmpty()) text else text + "\n📎 " + attachmentNames.joinToString(", ")
        addBubble(displayText, true)
        messages.add(ChatMessage("user", text, imagePayloads))
        pendingImages.clear()
        val aiBubble = addBubble("", false)
        messages.add(ChatMessage("assistant", ""))
        send.isEnabled = false
        api.streamChat(endpoint, model, messages.dropLast(1),
            onChunk = { chunk ->
                runOnUiThread {
                    aiBubble.append(chunk)
                    messages[messages.lastIndex] = messages.last().copy(content = aiBubble.text.toString())
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            },
            onComplete = { runOnUiThread { send.isEnabled = true } },
            onError = { error ->
                runOnUiThread {
                    aiBubble.text = "Connection error: " + (error.message ?: "unknown error")
                    send.isEnabled = true
                }
            })
    }

    private fun chooseAttachment() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, 1001)
    }

    @Deprecated("Android activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1001 || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } ?: data.data?.let { uris.add(it) }
        uris.forEach { uri ->
            val mime = contentResolver.getType(uri).orEmpty()
            if (mime.startsWith("image/")) {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@forEach
                    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val name = uri.lastPathSegment ?: "image"
                    pendingImages.add(name to encoded)
                } catch (_: Throwable) { }
            }
        }
        if (pendingImages.isNotEmpty()) {
            input.hint = pendingImages.size.toString() + " image(s) attached — add a message"
        }
    }

    private fun showPluginsDialog() {
        val plugins = arrayOf(
            "GitHub — planned OAuth connector",
            "Google Drive — planned OAuth connector",
            "Web Links — URL fetching connector",
            "Image Vision — enabled for compatible models"
        )
        AlertDialog.Builder(this)
            .setTitle("NudSG Plugins")
            .setItems(plugins, null)
            .setMessage("Plugins will be isolated providers that can supply files, links, and context to the local AI. GitHub and Drive need their own OAuth authorization.")
            .setPositiveButton("Done", null)
            .show()
    }

    private fun addBubble(text: String, isUser: Boolean): TextView {
        val wrapper = LinearLayout(this).apply {
            gravity = if (isUser) Gravity.END else Gravity.START
            setPadding(0, dp(5), 0, dp(5))
        }
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(15), dp(11), dp(15), dp(11))
            background = rounded(if (isUser) Color.rgb(76, 67, 139) else Color.rgb(31, 33, 41), 20)
        }
        wrapper.addView(bubble, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.82f).toInt(), -2))
        messagesLayout.addView(wrapper)
        return bubble
    }

    private fun showEndpointDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), 0)
        }
        val endpointInput = EditText(this).apply {
            setText(endpoint)
            setSingleLine()
            hint = "http://127.0.0.1:11434"
        }
        val modelInput = EditText(this).apply {
            setText(model)
            setSingleLine()
            hint = "Model name"
        }
        box.addView(endpointInput)
        box.addView(modelInput)
        AlertDialog.Builder(this)
            .setTitle("Local AI connection")
            .setMessage("The URL should expose an Ollama-compatible /api/chat endpoint.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                endpoint = endpointInput.text.toString()
                prefs.edit().putString("model", modelInput.text.toString().trim()).apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        api.shutdown()
        super.onDestroy()
    }
}
