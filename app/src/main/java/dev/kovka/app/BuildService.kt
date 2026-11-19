package dev.kovka.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal data class BuildRequest(
    val repository: String,
    val revision: String,
    val path: String,
    val task: String,
    val java: String = "auto"
)

internal enum class Phase(val title: String) {
    START("Подготовка"),
    SOURCE("Загрузка исходников"),
    TOOLS("Загрузка инструментов"),
    COMPILE("Сборка APK"),
    COLLECT("Сохранение APK"),
    FINISHED("APK готов"),
    FAILED("Сборка не завершилась")
}

internal data class JobStatus(
    val id: String,
    val title: String,
    val phase: Phase,
    val detail: String = "",
    val log: String = ""
) {
    val active get() = phase != Phase.FINISHED && phase != Phase.FAILED
}

class BuildService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runtime: EmbeddedRuntime? = null
    private var runningId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (runningId != null) return START_NOT_STICKY
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val request = BuildRequest(
            intent.getStringExtra("repository").orEmpty(), intent.getStringExtra("revision").orEmpty(),
            intent.getStringExtra("path") ?: ".", intent.getStringExtra("task") ?: "assembleDebug",
            intent.getStringExtra("java") ?: "auto"
        )
        val id = UUID.randomUUID().toString()
        runningId = id
        state.value = JobStatus(id, request.repository, Phase.START)
        startForeground(1, notification(request.repository))

        scope.launch {
            val failure = withContext(Dispatchers.IO) { build(request, id) }
            complete(id, failure)
        }
        return START_NOT_STICKY
    }

    private fun build(request: BuildRequest, id: String): String? {
        var result: String? = null
        try {
            val engine = EmbeddedRuntime(this)
            runtime = engine
            engine.prepare { append(id, it) }
            val output = engine.run(id, request) { line ->
                val phase = when (line) {
                    "::kovka::environment" -> Phase.START
                    "::kovka::source" -> Phase.SOURCE
                    "::kovka::tools" -> Phase.TOOLS
                    "::kovka::compile" -> Phase.COMPILE
                    "::kovka::collect" -> Phase.COLLECT
                    else -> null
                }
                if (phase == null) append(id, line) else update(id) { it.copy(phase = phase) }
            }
            val staging = BuildHistory.staging(this, id)
            staging.mkdirs()
            output.listFiles().orEmpty().filter { apk ->
                apk.isFile && apk.extension == "apk" && apk.canonicalFile.parentFile == output.canonicalFile
            }.forEach { apk ->
                if (signedPackage(apk)) apk.copyTo(File(staging, apk.name))
                else append(id, "Пропущен файл без действительной подписи: ${apk.name}")
            }
            check(staging.listFiles().orEmpty().any { it.extension == "apk" }) { "Подписанный APK не найден." }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            result = failure.localizedMessage ?: "Не удалось собрать APK."
            append(id, result)
        } finally {
            runtime?.discard(id)
            runtime = null
        }
        val journal = state.value?.takeIf { it.id == id }?.log.orEmpty()
        try {
            BuildHistory.save(this, request, id, journal, result == null)
        } catch (failure: Exception) {
            return failure.localizedMessage ?: "Не удалось сохранить сборку."
        }
        return result
    }

    private fun signedPackage(file: File): Boolean {
        val info = packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        return !info?.signingInfo?.apkContentsSigners.isNullOrEmpty()
    }

    private fun append(id: String, line: String) = update(id) {
        it.copy(log = (it.log + line.takeLast(8_192) + "\n").takeLast(65_536))
    }

    private fun update(id: String, transform: (JobStatus) -> JobStatus) {
        state.update { if (it?.id == id && it.active) transform(it) else it }
    }

    private fun complete(id: String, error: String?) {
        update(id) {
            it.copy(phase = if (error == null) Phase.FINISHED else Phase.FAILED, detail = error.orEmpty())
        }
        runningId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        runtime?.stop()
        runningId?.let { complete(it, "Android остановил сборку в фоне.") }
    }

    override fun onDestroy() {
        runtime?.stop()
        runningId?.let { id -> update(id) { it.copy(phase = Phase.FAILED, detail = "Android остановил сборку.") } }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(title: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("build", "Сборки", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, "build")
            .setSmallIcon(R.drawable.ic_build_status)
            .setContentTitle("Ковка · Сборка APK")
            .setContentText(title)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private val state = MutableStateFlow<JobStatus?>(null)
        internal val status = state.asStateFlow()

        internal fun start(context: Context, request: BuildRequest) {
            val intent = Intent(context, BuildService::class.java)
                .putExtra("repository", request.repository)
                .putExtra("revision", request.revision)
                .putExtra("path", request.path)
                .putExtra("task", request.task)
                .putExtra("java", request.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
