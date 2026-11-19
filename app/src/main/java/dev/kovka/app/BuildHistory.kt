package dev.kovka.app

import android.content.Context
import org.json.JSONObject
import java.io.File

internal data class BuildRecord(
    val id: String,
    val repository: String,
    val revision: String,
    val task: String,
    val time: Long,
    val apks: List<File>,
    val success: Boolean,
    val log: String
)

internal object BuildHistory {
    fun staging(context: Context, id: String): File = File(context.filesDir, "staging/$id")
    fun directory(context: Context, id: String): File = File(context.filesDir, "artifacts/$id")

    fun save(context: Context, request: BuildRequest, id: String, log: String, success: Boolean) {
        val staging = staging(context, id).apply { mkdirs() }
        val destination = directory(context, id)
        val metadata = JSONObject()
            .put("repository", request.repository)
            .put("revision", request.revision)
            .put("task", request.task)
            .put("time", System.currentTimeMillis())
            .put("success", success)
        File(staging, "build.json").writeText(metadata.toString())
        File(staging, "build.log").writeText(log)
        destination.parentFile?.mkdirs()
        check(staging.renameTo(destination)) { "Не удалось сохранить сборку." }
    }

    fun list(context: Context): List<BuildRecord> =
        File(context.filesDir, "artifacts").listFiles().orEmpty()
            .mapNotNull { runCatching { read(it) }.getOrNull() }.sortedByDescending { it.time }

    private fun read(directory: File): BuildRecord? {
        val metadata = File(directory, "build.json")
        if (!metadata.isFile) return null
        val data = JSONObject(metadata.readText())
        val apks = directory.listFiles().orEmpty().filter { it.isFile && it.extension == "apk" }.sorted()
        val journal = File(directory, "build.log")
        return BuildRecord(
            directory.name, data.getString("repository"), data.getString("revision"),
            data.getString("task"), data.getLong("time"), apks, data.optBoolean("success", apks.isNotEmpty()),
            if (journal.isFile) journal.readText() else ""
        )
    }
}
