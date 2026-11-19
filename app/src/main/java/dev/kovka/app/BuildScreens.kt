package dev.kovka.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import java.io.File
import java.text.DateFormat
import java.util.Date

private const val TERMUX_CONFIG = "mkdir -p ~/.termux && touch ~/.termux/termux.properties && " +
    "sed -i '/^[[:space:]]*allow-external-apps[[:space:]]*=/d' ~/.termux/termux.properties && " +
    "printf '\\nallow-external-apps = true\\n' >> ~/.termux/termux.properties && termux-reload-settings"

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun EnvironmentCard(
    environment: Environment,
    busy: Boolean,
    onOpenTermux: () -> Unit,
    onPermission: () -> Unit,
    onSettings: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopy: (String) -> Unit,
    onSetup: () -> Unit
) {
    var consent by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MessageCard("Termux", "Установите Termux и откройте его, чтобы он подготовил свою среду.") {
            TextButton(onClick = onOpenTermux) {
                Text(if (environment.installed) "Открыть Termux" else "Скачать Termux")
                Icon(Icons.Rounded.KeyboardArrowRight, null)
            }
        }
        MessageCard("Доступ к сборке", "Скопируйте команду и выполните её в Termux. Затем разрешите Ковке запускать команды.") {
            TextButton(onClick = { onCopy(TERMUX_CONFIG) }) { Text("Скопировать команду") }
            if (environment.installed && !environment.allowed) {
                TextButton(onClick = onPermission) { Text("Разрешить запуск") }
                TextButton(onClick = onSettings) { Text("Открыть разрешения Android") }
            }
            if (environment.allowed) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = Mint)
                    Text("Разрешение выдано", color = Mint, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        MessageCard(
            if (environment.ready) "Среда готова" else "Android SDK и OpenJDK",
            "OpenJDK 25, 21 и 17 с обновлением безопасности от 17 ноября 2026. Gradle выбирает подходящую версию для проекта."
        ) {
            Button(
                onClick = { consent = true },
                enabled = environment.allowed && !busy,
                shapes = ButtonDefaults.shapes()
            ) { Text(if (environment.ready) "Обновить среду" else "Подготовить среду") }
        }
    }

    if (consent) {
        AlertDialog(
            onDismissRequest = { consent = false },
            title = { Text("Лицензия Android SDK") },
            text = {
                Column {
                    Text("При подготовке Ковка скачает Android SDK и примет его условия от вашего имени.")
                    TextButton(onClick = { onOpenUrl("https://developer.android.com/studio/terms") }) {
                        Text("Читать условия")
                    }
                }
            },
            confirmButton = {
                Button(onClick = { consent = false; onSetup() }, shapes = ButtonDefaults.shapes()) { Text("Принять и скачать") }
            },
            dismissButton = { TextButton(onClick = { consent = false }) { Text("Отмена") } }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RepositoryDialog(
    repository: Repository,
    environmentReady: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onConfigure: () -> Unit,
    onBuild: (BuildRequest) -> Unit
) {
    var revision by remember(repository) { mutableStateOf<String?>(null) }
    var error by remember(repository) { mutableStateOf<String?>(null) }
    var retry by remember(repository) { mutableIntStateOf(0) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var path by rememberSaveable { mutableStateOf(".") }
    var task by rememberSaveable { mutableStateOf("assembleDebug") }
    var selectedJava by rememberSaveable { mutableStateOf("auto") }
    val pathOkay = path == "." || (path.matches(Regex("[A-Za-z0-9_./-]{1,100}")) &&
        !path.startsWith('/') && path.trimEnd('/').split('/').none { it in listOf("", ".", "..") })
    val taskOkay = task.matches(Regex(":?[A-Za-z0-9_.][A-Za-z0-9_.:-]{0,119}"))

    LaunchedEffect(repository, retry) {
        error = null
        try {
            revision = GitHub.revision(repository)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.localizedMessage ?: "Не удалось получить коммит."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        shape = RoundedCornerShape(32.dp),
        icon = { Icon(Icons.Rounded.Build, null, tint = Ember) },
        title = { Text(repository.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text(repository.fullName, color = Muted, style = MaterialTheme.typography.labelLarge)
                if (repository.description.isNotBlank()) Text(repository.description)
                Text(
                    listOf(
                        repository.license, repository.branch,
                        revision?.take(12) ?: if (error == null) "получаем коммит" else "коммит недоступен"
                    ).joinToString("  ·  "),
                    color = Ember, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium
                )
                if (error != null) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { retry++ }) { Text("Запросить коммит снова") }
                }
                TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Посмотреть исходники на GitHub") }
                Text("Gradle запустит код репозитория с доступом Termux к своим файлам. Собирайте проекты, которым доверяете.")
                if (environmentReady) {
                    TextButton(
                        onClick = { advanced = !advanced }, contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text(if (advanced) "Скрыть параметры" else "Параметры сборки") }
                }
                if (environmentReady && advanced) {
                    OutlinedTextField(
                        value = path, onValueChange = { path = it.take(100) },
                        label = { Text("Папка Gradle") }, singleLine = true, isError = !pathOkay
                    )
                    OutlinedTextField(
                        value = task, onValueChange = { task = it.take(120) },
                        label = { Text("Задача Gradle") }, singleLine = true, isError = !taskOkay
                    )
                    JavaSelector(selectedJava) { selectedJava = it }
                }
            }
        },
        confirmButton = {
            if (!environmentReady) {
                Button(onClick = onConfigure, shapes = ButtonDefaults.shapes()) { Text("Настроить среду") }
            } else {
                Button(
                    onClick = {
                        revision?.let { onBuild(BuildRequest(repository.fullName, it, path, task, selectedJava)) }
                    },
                    enabled = revision != null && pathOkay && taskOkay && !busy,
                    shapes = ButtonDefaults.shapes()
                ) { Text(if (busy) "Занято" else "Собрать APK") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@Composable
private fun JavaSelector(selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val versions = linkedMapOf("auto" to "Автоматически", "17" to "OpenJDK 17", "21" to "OpenJDK 21", "25" to "OpenJDK 25")
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Java · ${versions.getValue(selected)}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            versions.forEach { (id, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(id); open = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun JobCard(job: JobStatus, onCopy: (String) -> Unit) {
    val error = job.phase in listOf(Phase.FAILED, Phase.SETUP_FAILED)
    val color = when {
        error -> MaterialTheme.colorScheme.error
        job.active -> Ember
        else -> Mint
    }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = Panel) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(job.phase.title, color = color, style = MaterialTheme.typography.titleLarge)
            Text(job.title, color = Muted, style = MaterialTheme.typography.bodyMedium)
            if (job.active) LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            if (job.detail.isNotBlank()) {
                Text(job.detail, style = MaterialTheme.typography.bodyMedium)
            }
            if (job.log.isNotBlank()) {
                val scroll = rememberScrollState()
                val cleanLog = remember(job.log) {
                    job.log.replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"), "").replace('\r', '\n').trimEnd()
                }
                LaunchedEffect(cleanLog) { if (job.active) scroll.scrollTo(scroll.maxValue) }
                Surface(color = Ink, shape = RoundedCornerShape(16.dp)) {
                    SelectionContainer {
                        Text(
                            cleanLog,
                            Modifier.fillMaxWidth().heightIn(max = 264.dp).verticalScroll(scroll).padding(12.dp),
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            color = Paper
                        )
                    }
                }
                TextButton(onClick = { onCopy(cleanLog) }) { Text("Копировать журнал") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ArtifactCard(record: BuildRecord, onInstall: (File) -> Unit, onShare: (File) -> Unit) {
    val timestamp = remember(record.time) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.time)) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = Panel) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(record.repository, style = MaterialTheme.typography.titleMedium)
            Text("$timestamp  ·  ${record.revision.take(10)}", fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium, color = Muted)
            Text(record.task, style = MaterialTheme.typography.labelMedium, color = Muted)
            record.apks.forEach { apk ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(apk.name.substringAfter('-'), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { onInstall(apk) }, shapes = ButtonDefaults.shapes()) {
                        Text("Установить")
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { onShare(apk) }) {
                        Icon(Icons.Rounded.Share, "Поделиться APK", tint = Ember)
                    }
                }
            }
        }
    }
}
