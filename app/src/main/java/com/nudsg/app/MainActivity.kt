package com.nudsg.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.content.Intent
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : Activity() {
    private lateinit var messagesLayout: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var attachmentStrip: LinearLayout
    private val api = ApiClient()
    private val messages = mutableListOf<ChatMessage>()
    private val pendingAttachments = mutableListOf<PendingAttachment>()
    private val prefs by lazy { getSharedPreferences("nudsg", MODE_PRIVATE) }

    private var endpoint: String
        get() = prefs.getString("api_base_url", BuildConfig.DEFAULT_API_BASE_URL) ?: BuildConfig.DEFAULT_API_BASE_URL
        set(value) { prefs.edit().putString("api_base_url", value.trim().trimEnd('/')).apply() }

    private val model: String
        get() = prefs.getString("model", "llama3.2") ?: "llama3.2"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 17, 22))
        }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(12), dp(10))
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
        val pluginsButton = TextView(this).apply {
            text = "  Plugins  "
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = rounded(Color.rgb(38, 40, 50), 18)
            setOnClickListener { showPluginsDialog() }
        }
        toolbar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(endpointButton)
        toolbar.addView(pluginsButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        root.addView(toolbar)

        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        messagesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(24))
        }
        scroll.addView(messagesLayout)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(10))
        }
        attachmentStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        composer.addView(attachmentStrip, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Message your local AI…"
            setHintTextColor(Color.rgb(145, 148, 160))
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 4
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = rounded(Color.rgb(31, 33, 41), 24)
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) scrollToBottomSoon() }
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
            setOnClickListener { sendMessage() }
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        row.addView(attach, LinearLayout.LayoutParams(dp(52), dp(48)).apply { marginEnd = dp(6) })
        row.addView(send, LinearLayout.LayoutParams(dp(78), dp(48)))
        composer.addView(row)
        root.addView(composer, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, 0, 0, maxOf(bars.bottom, ime.bottom))
            scrollToBottomSoon()
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty() && pendingAttachments.isEmpty()) return
        val imagePayloads = pendingAttachments.mapNotNull { it.base64Image }
        val attachmentNames = pendingAttachments.map { it.name }
        val displayText = if (attachmentNames.isEmpty()) text else
            (if (text.isEmpty()) "Attached files" else text) + "\n📎 " + attachmentNames.joinToString(", ")
        addUserBubble(displayText, pendingAttachments.toList())
        messages.add(ChatMessage("user", text, imagePayloads))
        pendingAttachments.clear()
        refreshAttachmentStrip()
        input.setText("")

        val aiBubble = addBubble("", false)
        messages.add(ChatMessage("assistant", ""))
        send.isEnabled = false
        scrollToBottomSoon()
        api.streamChat(endpoint, model, messages.dropLast(1),
            onChunk = { chunk ->
                runOnUiThread {
                    aiBubble.append(chunk)
                    messages[messages.lastIndex] = messages.last().copy(content = aiBubble.text.toString())
                    scrollToBottomSoon()
                }
            },
            onComplete = { runOnUiThread { send.isEnabled = true } },
            onError = { error ->
                runOnUiThread {
                    aiBubble.text = "Connection error: " + api.friendlyError(error, endpoint)
                    send.isEnabled = true
                    scrollToBottomSoon()
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
        uris.forEach { addAttachment(it) }
        refreshAttachmentStrip()
    }

    private fun addAttachment(uri: Uri) {
        val mime = contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "attachment"
        val size = querySize(uri)
        var imageBase64: String? = null
        if (mime.startsWith("image/")) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
                imageBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            } catch (_: Throwable) { return }
        }
        pendingAttachments.add(PendingAttachment(name, mime, size, imageBase64))
    }

    private fun refreshAttachmentStrip() {
        attachmentStrip.removeAllViews()
        if (pendingAttachments.isEmpty()) {
            attachmentStrip.visibility = View.GONE
            return
        }
        attachmentStrip.visibility = View.VISIBLE
        pendingAttachments.forEachIndexed { index, attachment ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(5), dp(5), dp(5))
                background = rounded(Color.rgb(31, 33, 41), 12)
            }
            if (attachment.base64Image != null) {
                val image = ImageView(this).apply {
                    val bytes = android.util.Base64.decode(attachment.base64Image, android.util.Base64.NO_WRAP)
                    setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                card.addView(image, LinearLayout.LayoutParams(dp(64), dp(64)))
            } else {
                val icon = TextView(this).apply {
                    text = fileEmoji(attachment.mime)
                    textSize = 30f
                    gravity = Gravity.CENTER
                }
                card.addView(icon, LinearLayout.LayoutParams(dp(64), dp(48)))
            }
            val label = TextView(this).apply {
                text = ellipsizeName(attachment.name)
                textSize = 10f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                maxLines = 1
            }
            card.addView(label, LinearLayout.LayoutParams(dp(80), dp(20)))
            card.setOnClickListener {
                pendingAttachments.removeAt(index.coerceAtMost(pendingAttachments.lastIndex))
                refreshAttachmentStrip()
            }
            attachmentStrip.addView(card, LinearLayout.LayoutParams(dp(88), dp(92)).apply { marginEnd = dp(6) })
        }
    }

    private fun addUserBubble(text: String, attachments: List<PendingAttachment>) {
        val wrapper = LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(0, dp(5), 0, dp(5))
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(Color.rgb(76, 67, 139), 20)
        }
        val images = attachments.filter { it.base64Image != null }
        if (images.isNotEmpty()) {
            val imageRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            images.forEach { attachment ->
                try {
                    val bytes = android.util.Base64.decode(attachment.base64Image, android.util.Base64.NO_WRAP)
                    val image = ImageView(this).apply {
                        setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    imageRow.addView(image, LinearLayout.LayoutParams(dp(120), dp(120)).apply { marginEnd = dp(6) })
                } catch (_: Throwable) { }
            }
            bubble.addView(imageRow)
        }
        val nonImages = attachments.filter { it.base64Image == null }
        if (nonImages.isNotEmpty()) {
            nonImages.forEach { attachment ->
                val file = TextView(this).apply {
                    text = "${fileEmoji(attachment.mime)}  ${attachment.name}\n${formatSize(attachment.size)}"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setPadding(dp(9), dp(7), dp(9), dp(7))
                    background = rounded(Color.argb(45, 255, 255, 255), 10)
                }
                bubble.addView(file, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
            }
        }
        if (text.isNotBlank()) {
            val message = TextView(this).apply {
                this.text = text
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(dp(5), dp(7), dp(5), dp(2))
            }
            bubble.addView(message)
        }
        wrapper.addView(bubble, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.82f).toInt(), -2))
        messagesLayout.addView(wrapper)
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

    private fun showPluginsDialog() {
        val items = arrayOf(
            "GitHub — connect account / token",
            "Google Drive — OAuth connector",
            "Web Links — fetch page context",
            "Image Vision — local model images"
        )
        AlertDialog.Builder(this).setTitle("NudSG Plugins").setItems(items) { _, which ->
            when (which) {
                0 -> showGitHubPluginDialog()
                1 -> showSimplePluginInfo("Google Drive", "The Drive connector is being built around OAuth so NudSG can browse and read your authorized files without a NudSG subscription.")
                2 -> showSimplePluginInfo("Web Links", "The web connector will fetch a URL, extract readable content, and provide that context to the model.")
                3 -> showSimplePluginInfo("Image Vision", "Images are already sent as Ollama-compatible base64 image inputs. Your selected model must support vision.")
            }
        }.setPositiveButton("Done", null).show()
    }

    private fun showGitHubPluginDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), 0, dp(22), 0)
        }
        val token = EditText(this).apply {
            hint = "GitHub token"
            setSingleLine()
            setText(prefs.getString("github_token", ""))
        }
        box.addView(token)
        AlertDialog.Builder(this)
            .setTitle("GitHub Plugin")
            .setMessage("This starts the real GitHub connector. The token is used for repository access; later this can be replaced with OAuth/PKCE.")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> prefs.edit().putString("github_token", token.text.toString().trim()).apply() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSimplePluginInfo(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Done", null).show()
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
            hint = "llama3.2"
        }
        box.addView(endpointInput)
        box.addView(modelInput)
        AlertDialog.Builder(this)
            .setTitle("Local AI connection")
            .setMessage("127.0.0.1 means this Android device. If the AI server is on another device, use that device's LAN address instead.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                endpoint = endpointInput.text.toString()
                prefs.edit().putString("model", modelInput.text.toString().trim()).apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun querySize(uri: Uri): Long =
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L

    private fun fileEmoji(mime: String): String = when {
        mime.startsWith("image/") -> "🖼️"
        mime.contains("pdf") -> "📕"
        mime.contains("zip") || mime.contains("compressed") -> "🗜️"
        mime.contains("json") || mime.contains("text") -> "📄"
        mime.contains("gltf") || mime.contains("blend") || mime.contains("octet") -> "🧊"
        else -> "📎"
    }

    private fun ellipsizeName(name: String): String =
        if (name.length <= 12) name else name.take(9) + "…"

    private fun formatSize(size: Long): String = when {
        size <= 0L -> "file"
        size < 1024L -> "${size} B"
        size < 1024L * 1024L -> "${size / 1024L} KB"
        else -> "${size / (1024L * 1024L)} MB"
    }

    private fun scrollToBottomSoon() {
        scroll.postDelayed({ scroll.fullScroll(View.FOCUS_DOWN) }, 80)
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
