package dev.kovka.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

internal object InstallJournal {
    private val revision = MutableStateFlow(0)
    val updates = revision.asStateFlow()
    private val actions = MutableStateFlow<Intent?>(null)
    val userAction = actions.asStateFlow()

    fun consumeAction(): Intent? {
        val action = actions.value
        actions.value = null
        return action
    }

    fun read(context: Context, id: String): String {
        val log = File(BuildHistory.directory(context, id), "install.log")
        return if (log.isFile) log.readText() else ""
    }

    @Synchronized
    fun append(context: Context, id: String, line: String) {
        val directory = BuildHistory.directory(context, id)
        if (!directory.isDirectory) return
        File(directory, "install.log").appendText("$line\n")
        revision.update { it + 1 }
    }

    fun commit(context: Context, file: File) {
        val id = requireNotNull(file.parentFile).name
        append(context, id, "Подготовка APK к установке")
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { output ->
                    file.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val intent = Intent(context, InstallResult::class.java)
                    .setData(Uri.parse("kovka://install/$id/$sessionId")).putExtra("buildId", id)
                val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val callback = PendingIntent.getBroadcast(context, sessionId, intent, mutable or PendingIntent.FLAG_UPDATE_CURRENT)
                append(context, id, "APK передан системному установщику")
                session.commit(callback.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    @Suppress("DEPRECATION")
    fun receive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("buildId") ?: return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirmation == null) append(context, id, "Android не открыл окно подтверждения.")
                else {
                    append(context, id, "Ожидаем подтверждения установки")
                    actions.value = confirmation
                }
            }
            PackageInstaller.STATUS_SUCCESS -> append(context, id, "Приложение установлено")
            PackageInstaller.STATUS_FAILURE_ABORTED -> append(context, id, "Установка отменена")
            else -> append(context, id, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                ?.take(500)?.let { "Установка не завершилась. $it" } ?: "Установка не завершилась")
        }
    }
}

class InstallResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = InstallJournal.receive(context, intent)
}
