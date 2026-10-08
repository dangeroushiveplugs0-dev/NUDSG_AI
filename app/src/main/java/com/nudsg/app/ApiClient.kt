package com.nudsg.app

import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

data class ChatMessage(val role: String, val content: String, val images: List<String> = emptyList())

class ApiClient {
    private val executor = Executors.newCachedThreadPool()

    fun streamChat(baseUrl: String, model: String, messages: List<ChatMessage>,
                   onChunk: (String) -> Unit, onComplete: () -> Unit,
                   onError: (Throwable) -> Unit) {
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(baseUrl.trimEnd('/') + "/api/chat").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 0
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/x-ndjson, application/json")
                }
                val jsonMessages = JSONArray()
                messages.forEach {
                    jsonMessages.put(JSONObject().apply { put("role", it.role); put("content", it.content); if (it.images.isNotEmpty()) put("images", JSONArray(it.images)) })
                }
                val payload = JSONObject().put("model", model).put("messages", jsonMessages).put("stream", true)
                connection.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("Local AI returned HTTP $code")
                BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isBlank()) return@forEach
                        val obj = JSONObject(line)
                        val chunk = obj.optJSONObject("message")?.optString("content").orEmpty()
                        if (chunk.isNotEmpty()) onChunk(chunk)
                    }
                }
                onComplete()
            } catch (t: Throwable) {
                onError(t)
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun shutdown() = executor.shutdownNow()
}
