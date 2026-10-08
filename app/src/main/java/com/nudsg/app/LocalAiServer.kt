package com.nudsg.app

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight Ollama-compatible bridge running entirely on the Android device.
 *
 * The selected GGUF is kept in the app-private files directory. Multiple GGUFs
 * may be installed; ModelManager decides which one is active.
 */
class LocalAiServer(private val context: Context) : NanoHTTPD("127.0.0.1", 11434) {
    private val models = ModelManager(context)
    private var modelHandle: LlamaModel? = null
    private var loadedPath: String? = null
    private val running = AtomicBoolean(false)

    fun startIfModelAvailable(): Boolean {
        val model = models.selectedModel()?.file ?: return false
        if (running.get() && loadedPath == model.absolutePath && modelHandle != null) return true
        return try {
            if (running.get()) super.stop()
            running.set(false)
            loadModel(model)
            start(SOCKET_READ_TIMEOUT, false)
            running.set(true)
            true
        } catch (t: Throwable) {
            Log.e("NudSG", "Could not start local AI runtime", t)
            false
        }
    }

    fun reloadSelectedModel(): Boolean {
        stop()
        return startIfModelAvailable()
    }

    private fun loadModel(file: File) {
        if (loadedPath == file.absolutePath && modelHandle != null) return
        modelHandle?.let { try { Llama.releaseModel(it) } catch (_: Throwable) {} }
        modelHandle = runBlocking {
            Llama.loadModel(
                modelPath = file.absolutePath,
                config = LlamaConfig(
                    contextSize = 4096,
                    threads = maxOf(2, Runtime.getRuntime().availableProcessors() - 1)
                )
            )
        }
        loadedPath = file.absolutePath
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && session.uri == "/api/tags" -> {
                    val installed = models.installedModels()
                    json(Response.Status.OK, JSONObject().put("models", JSONArray().apply {
                        installed.forEach { item ->
                            put(JSONObject()
                                .put("name", item.modelId)
                                .put("display_name", item.displayName)
                                .put("path", item.file.name)
                                .put("size", item.file.length())
                                .put("selected", item.isSelected))
                        }
                    }))
                }
                session.method == Method.POST && session.uri == "/api/chat" -> chat(session)
                else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "Not found"))
            }
        } catch (t: Throwable) {
            Log.e("NudSG", "Local API error", t)
            json(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("error", t.message ?: "Local inference failed")
            )
        }
    }

    private fun chat(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val request = JSONObject(body["postData"] ?: "{}")
        val messages = request.optJSONArray("messages") ?: JSONArray()
        val prompt = buildPrompt(messages)
        val model = modelHandle ?: throw IllegalStateException("No GGUF model is installed")
        val started = System.nanoTime()

        val result = runBlocking {
            Llama.complete(
                model,
                prompt = prompt,
                systemPrompt = "You are NudSG's local AI assistant.",
                maxTokens = request.optInt("num_predict", 512)
            )
        }

        val elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0
        val response = JSONObject()
            .put("model", models.selectedModel()?.modelId ?: request.optString("model", "local"))
            .put("message", JSONObject().put("role", "assistant").put("content", result.text))
            .put("done", true)
            .put("elapsed_seconds", elapsedSeconds)
            .put("tokens_per_second", result.tokensPerSecond)
        return json(Response.Status.OK, response)
    }

    private fun buildPrompt(messages: JSONArray): String {
        val out = StringBuilder()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val content = m.optString("content", "")
            out.append(role.replaceFirstChar { it.uppercase() })
                .append(": ")
                .append(content)
                .append("\n")
        }
        out.append("Assistant:")
        return out.toString()
    }

    private fun json(status: Response.Status, value: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", value.toString())

    override fun stop() {
        if (running.getAndSet(false)) super.stop()
        modelHandle?.let { try { Llama.releaseModel(it) } catch (_: Throwable) {} }
        modelHandle = null
        loadedPath = null
    }
}
