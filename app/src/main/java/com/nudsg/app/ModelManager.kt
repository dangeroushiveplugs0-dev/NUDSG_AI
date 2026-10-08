package com.nudsg.app

import android.content.Context
import java.io.File
import java.util.Locale

data class InstalledModel(
    val file: File,
    val displayName: String,
    val modelId: String,
    val isSelected: Boolean
)

data class ModelCatalogEntry(
    val id: String,
    val displayName: String,
    val family: String,
    val variant: String,
    val quantization: String,
    val sizeLabel: String,
    val sourceUrl: String
)

class ModelManager(private val context: Context) {
    companion object {
        private const val PREFS = "nudsg"
        private const val SELECTED_FILE = "selected_local_model"

        val OLMO_2_1B_BASE = ModelCatalogEntry(
            id = "olmo-2-0425-1b-base",
            displayName = "OLMo 2 1B Base",
            family = "OLMo 2",
            variant = "Base",
            quantization = "Q4_K_M",
            sizeLabel = "~936 MB",
            sourceUrl = "https://huggingface.co/allenai/OLMo-2-0425-1B-GGUF"
        )
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun installedModels(): List<InstalledModel> {
        val selected = prefs.getString(SELECTED_FILE, null)
        return context.filesDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".gguf", true) }
            .sortedBy { it.name.lowercase(Locale.US) }
            .map { file ->
                InstalledModel(
                    file = file,
                    displayName = displayNameFor(file),
                    modelId = modelIdFor(file),
                    isSelected = file.name == selected
                )
            }
    }

    fun selectedModel(): InstalledModel? {
        val models = installedModels()
        val selected = prefs.getString(SELECTED_FILE, null)
        return models.firstOrNull { it.file.name == selected }
            ?: models.firstOrNull { it.file.name.contains("qwen", true) }
            ?: models.firstOrNull()
    }

    fun select(file: File): Boolean {
        if (!file.isFile || !file.name.endsWith(".gguf", true)) return false
        prefs.edit().putString(SELECTED_FILE, file.name).apply()
        return true
    }

    fun importModel(source: java.io.InputStream, fileName: String): File {
        val safeName = fileName
            .substringAfterLast("/")
            .replace("[^A-Za-z0-9._-]".toRegex(), "_")
            .let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
        val target = File(context.filesDir, safeName)
        source.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    fun displayNameFor(file: File): String {
        val n = file.nameWithoutExtension
        return when {
            n.contains("OLMo-2-0425-1B", true) -> "OLMo 2 1B Base"
            n.contains("Qwen2.5", true) || n.contains("qwen", true) -> "Qwen 2.5 1.5B Instruct"
            else -> n.replace('_', ' ').replace('-', ' ')
        }
    }

    fun modelIdFor(file: File): String {
        val n = file.nameWithoutExtension.lowercase(Locale.US)
        return when {
            n.contains("olmo-2-0425-1b") -> OLMO_2_1B_BASE.id
            n.contains("qwen") -> "qwen2.5-1.5b-instruct"
            else -> n
        }
    }
}
