package dev.kovka.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private var pendingInstall: File? = null

    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val file = pendingInstall
        pendingInstall = null
        if (file != null && packageManager.canRequestPackageInstalls()) install(file)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingInstall = savedInstanceState?.getString("pendingInstall")?.let(::File)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                InstallJournal.userAction.collect { action ->
                    if (action != null) InstallJournal.consumeAction()?.let(::launchIntent)
                }
            }
        }
        setContent {
            KovkaTheme {
                Kovka(
                    onOpenUrl = ::openUrl,
                    onCopy = ::copy,
                    onStart = ::startBuild,
                    onInstall = ::install,
                    onShare = ::share
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingInstall?.let { outState.putString("pendingInstall", it.absolutePath) }
    }

    private fun startBuild(request: BuildRequest) {
        getSharedPreferences("environment", MODE_PRIVATE).edit().putBoolean("sdkTerms", true).apply()
        runCatching { BuildService.start(this, request) }
            .onFailure { toast(it.localizedMessage ?: "Не удалось запустить сборку.") }
    }

    private fun openUrl(url: String) = launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ковка", text))
        if (android.os.Build.VERSION.SDK_INT < 33) toast("Скопировано")
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
                toast("Разрешите Ковке устанавливать приложения в настройках Android.")
            }
            return
        }
        lifecycleScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { InstallJournal.commit(this@MainActivity, file) }.exceptionOrNull()
            }
            if (failure != null) {
                val id = requireNotNull(file.parentFile).name
                InstallJournal.append(this@MainActivity, id, failure.localizedMessage ?: "Не удалось начать установку")
                toast("Не удалось начать установку.")
            }
        }
    }

    private fun share(file: File) {
        if (!file.isFile) return
        val uri = FileProvider.getUriForFile(this, "$packageName.artifacts", file)
        launchIntent(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("APK", uri)
        }, "Поделиться APK"))
    }

    private fun launchIntent(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("Android не нашёл приложение для этого действия.")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
