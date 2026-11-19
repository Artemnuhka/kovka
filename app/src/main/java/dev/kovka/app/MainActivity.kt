package dev.kovka.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File

internal data class Environment(val installed: Boolean, val allowed: Boolean, val ready: Boolean)

class MainActivity : ComponentActivity() {
    private var environment by mutableStateOf(Environment(false, false, false))
    private var pendingInstall: File? = null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updateEnvironment()
        if (!it) toast("Разрешение также доступно в настройках приложения.")
    }

    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val file = pendingInstall
        pendingInstall = null
        if (file != null && packageManager.canRequestPackageInstalls()) install(file)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        updateEnvironment()
        setContent {
            KovkaTheme {
                Kovka(
                    environment = environment,
                    onEnvironmentChanged = ::updateEnvironment,
                    onOpenUrl = ::openUrl,
                    onOpenTermux = ::openTermux,
                    onPermission = { permission.launch(TERMUX_PERMISSION) },
                    onSettings = ::appSettings,
                    onCopy = ::copy,
                    onStart = ::startBuild,
                    onInstall = ::install,
                    onShare = ::share
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateEnvironment()
    }

    private fun updateEnvironment() {
        val installed = packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE) != null
        val allowed = installed && BuildService.hasPermission(this)
        val ready = allowed && getSharedPreferences("environment", MODE_PRIVATE).getBoolean("ready", false)
        environment = Environment(installed, allowed, ready)
    }

    private fun startBuild(request: BuildRequest?) {
        runCatching { BuildService.start(this, request) }
            .onFailure { toast(it.localizedMessage ?: "Не удалось запустить сборку.") }
    }

    private fun appSettings() = launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun openTermux() {
        val intent = packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
        if (intent != null) launch(intent) else openUrl("https://f-droid.org/packages/com.termux/")
    }

    private fun openUrl(url: String) = launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ковка", text))
        if (Build.VERSION.SDK_INT < 33) toast("Скопировано")
    }

    private fun install(file: File) {
        if (!file.isFile) {
            toast("Файл APK недоступен.")
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstall = file
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            try {
                installPermission.launch(intent)
            } catch (_: ActivityNotFoundException) {
                pendingInstall = null
                toast("Откройте настройки и разрешите Ковке устанавливать приложения.")
            }
            return
        }
        val uri = apkUri(file)
        launch(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("APK", uri)
        })
    }

    private fun share(file: File) {
        if (!file.isFile) return
        val uri = apkUri(file)
        launch(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = APK_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("APK", uri)
        }, "Поделиться APK"))
    }

    private fun apkUri(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.artifacts", file)

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("Android не нашёл приложение для этого действия.")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
