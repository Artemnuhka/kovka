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
    val apks: List<File>
)

internal object BuildHistory {
    fun staging(context: Context, id: String): File = File(context.filesDir, "staging/$id")

    fun save(context: Context, request: BuildRequest, id: String): BuildRecord {
        val staging = staging(context, id)
        val dir = File(context.filesDir, "artifacts/$id")
        val metadata = JSONObject()
            .put("repository", request.repository)
            .put("revision", request.revision)
            .put("task", request.task)
            .put("time", System.currentTimeMillis())
        File(staging, "build.json").writeText(metadata.toString())
        dir.parentFile?.mkdirs()
        check(staging.renameTo(dir)) { "Не удалось сохранить APK." }
        return requireNotNull(read(dir))
    }

    fun list(context: Context): List<BuildRecord> =
        File(context.filesDir, "artifacts").listFiles().orEmpty()
            .mapNotNull { runCatching { read(it) }.getOrNull() }.sortedByDescending { it.time }

    private fun read(directory: File): BuildRecord? {
        val metadata = File(directory, "build.json")
        if (!metadata.isFile) return null
        val data = JSONObject(metadata.readText())
        val apks = directory.listFiles().orEmpty().filter { it.isFile && it.extension == "apk" }.sorted()
        if (apks.isEmpty()) return null
        return BuildRecord(
            directory.name, data.getString("repository"), data.getString("revision"),
            data.getString("task"), data.getLong("time"), apks
        )
    }
}
