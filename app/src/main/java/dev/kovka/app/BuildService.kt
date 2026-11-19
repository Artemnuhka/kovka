package dev.kovka.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import java.util.UUID

internal const val TERMUX_PACKAGE = "com.termux"
internal const val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"
private const val RESULT_BUNDLE = "result"
private const val TERMUX = "com.termux.RUN_COMMAND"

internal data class BuildRequest(
    val repository: String,
    val revision: String,
    val path: String,
    val task: String,
    val java: String
)

internal enum class Phase(val title: String) {
    SETUP("Настройка среды"),
    START("Запуск Termux"),
    SOURCE("Загрузка исходников"),
    TOOLS("Подготовка Gradle"),
    COMPILE("Сборка APK"),
    COLLECT("Сохранение APK"),
    FINISHED("APK готов"),
    CONFIGURED("Среда готова"),
    FAILED("Сборка не завершилась"),
    SETUP_FAILED("Среда не настроена")
}

internal data class JobStatus(
    val id: String,
    val title: String,
    val setup: Boolean,
    val phase: Phase,
    val log: String = "",
    val detail: String = "",
    val record: BuildRecord? = null
) {
    val active get() = phase !in setOf(Phase.FINISHED, Phase.CONFIGURED, Phase.FAILED, Phase.SETUP_FAILED)
}

class BuildService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var bridge: BuildBridge? = null
    private var pending: PendingIntent? = null
    private var request: BuildRequest? = null
    private var currentId: String? = null
    private val timeout = Runnable { fail("Termux не вернул результат вовремя.") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (currentId != null) return START_NOT_STICKY
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val setup = intent.action == "setup"
        if (setup) getSharedPreferences("environment", MODE_PRIVATE).edit().putBoolean("ready", false).apply()
        request = if (setup) null else BuildRequest(
            intent.getStringExtra("repository").orEmpty(),
            intent.getStringExtra("revision").orEmpty(),
            intent.getStringExtra("path") ?: ".",
            intent.getStringExtra("task") ?: "assembleDebug",
            intent.getStringExtra("java") ?: "auto"
        )
        val id = UUID.randomUUID().toString()
        val title = request?.repository ?: "Termux · Android SDK"
        currentId = id
        runningService = this
        state.value = JobStatus(id, title, setup, if (setup) Phase.SETUP else Phase.START)
        startForeground(1, notification(title))
        handler.postDelayed(timeout, if (setup) 30 * 60_000L else 120 * 60_000L)

        scope.launch {
            try {
                val activeRequest = request
                val arguments = if (activeRequest == null) emptyArray() else {
                    val server = withContext(Dispatchers.IO) {
                        BuildBridge(this@BuildService, id) { event, text ->
                            state.update { job ->
                                if (job?.id != id || !job.active) job
                                else if (event == "log") job.copy(log = text)
                                else job.copy(phase = when (text) {
                                    "source" -> Phase.SOURCE
                                    "tools" -> Phase.TOOLS
                                    "compile" -> Phase.COMPILE
                                    "collect" -> Phase.COLLECT
                                    else -> job.phase
                                })
                            }
                        }
                    }
                    bridge = server
                    server.start()
                    arrayOf(server.endpoint, id, activeRequest.repository, activeRequest.revision,
                        activeRequest.path, activeRequest.task, activeRequest.java)
                }
                val script = assets.open(if (setup) "setup.sh" else "build.sh")
                    .bufferedReader().use { it.readText() }
                runInTermux(id, title, script, arguments)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fail(failure.localizedMessage ?: "Не удалось запустить Termux.")
            }
        }
        return START_NOT_STICKY
    }

    private fun runInTermux(id: String, title: String, script: String, args: Array<String>) {
        val result = Intent(this, TermuxResult::class.java).setData(Uri.parse("kovka://result/$id"))
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val callback = PendingIntent.getBroadcast(this, 0, result, mutable or PendingIntent.FLAG_ONE_SHOT)
        pending = callback
        val command = Intent(TERMUX).apply {
            component = ComponentName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            putExtra("${TERMUX}_PATH", "\$PREFIX/bin/bash")
            putExtra("${TERMUX}_ARGUMENTS", arrayOf("-c", script, "kovka", *args))
            putExtra("${TERMUX}_WORKDIR", "~/")
            putExtra("${TERMUX}_BACKGROUND", true)
            putExtra("${TERMUX}_COMMAND_LABEL", title)
            putExtra("${TERMUX}_PENDING_INTENT", callback)
        }
        if (startService(command) == null) error("Установите и откройте Termux.")
    }

    internal fun receive(id: String, result: Bundle?) {
        if (id != currentId) return
        if (result == null) {
            fail("Termux не передал результат команды.")
            return
        }

        val output = listOf(result.getString("stdout"), result.getString("stderr"))
            .filterNot { it.isNullOrBlank() }.joinToString("\n").takeLast(32_768)
        val error = result.getString("errmsg").orEmpty()
        val exit = result.getInt("exitCode", -1)
        val termuxError = result.getInt("err", -1)
        if (exit != 0 || termuxError !in listOf(-1, 0)) {
            fail(error.ifBlank { "Termux завершился с кодом $exit. Подробности в журнале." }, output)
            return
        }

        val completedRequest = request
        if (completedRequest == null) {
            getSharedPreferences("environment", MODE_PRIVATE).edit().putBoolean("ready", true).apply()
            finish(Phase.CONFIGURED, output = output)
            return
        }
        scope.launch {
            try {
                val record = withContext(Dispatchers.IO) {
                    check(BuildHistory.staging(this@BuildService, id).listFiles().orEmpty().any { it.extension == "apk" }) {
                        "Gradle завершился, но подписанный APK не получен."
                    }
                    BuildHistory.save(this@BuildService, completedRequest, id)
                }
                finish(Phase.FINISHED, record = record)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fail(failure.localizedMessage ?: "Не удалось сохранить APK.")
            }
        }
    }

    private fun fail(detail: String, output: String = "") {
        val status = state.value ?: return
        finish(if (status.setup) Phase.SETUP_FAILED else Phase.FAILED, detail, output)
    }

    private fun finish(phase: Phase, detail: String = "", output: String = "", record: BuildRecord? = null) {
        val id = currentId ?: return
        handler.removeCallbacks(timeout)
        bridge?.close()
        pending?.cancel()
        state.update {
            if (it?.id != id) it else it.copy(
                phase = phase, log = it.log.ifBlank { output }, detail = detail, record = record
            )
        }
        if (record == null) BuildHistory.staging(this, id).deleteRecursively()
        currentId = null
        runningService = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        fail("Android остановил фоновую службу. Откройте Ковку и запустите сборку снова.")
    }

    override fun onDestroy() {
        if (currentId != null) fail("Android остановил фоновую службу.")
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(title: String): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("build", "Локальная сборка", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, "build")
            .setSmallIcon(R.drawable.ic_build_status)
            .setContentTitle("Ковка · Локальная сборка")
            .setContentText(title)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private val state = MutableStateFlow<JobStatus?>(null)
        internal val status = state.asStateFlow()
        internal var runningService: BuildService? = null
            private set

        internal fun start(context: Context, request: BuildRequest? = null) {
            val intent = Intent(context, BuildService::class.java)
            intent.action = if (request == null) "setup" else "build"
            if (request != null) {
                intent.putExtra("repository", request.repository)
                intent.putExtra("revision", request.revision)
                intent.putExtra("path", request.path)
                intent.putExtra("task", request.task)
                intent.putExtra("java", request.java)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        internal fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }
}

class TermuxResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment ?: return
        BuildService.runningService?.receive(id, intent.getBundleExtra(RESULT_BUNDLE))
    }
}
