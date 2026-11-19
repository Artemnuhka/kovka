package dev.kovka.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RepositoryDialog(
    repository: Repository,
    sdkTerms: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onOpenTerms: () -> Unit,
    onBuild: (BuildRequest) -> Unit
) {
    var revision by remember(repository) { mutableStateOf<String?>(null) }
    var error by remember(repository) { mutableStateOf<String?>(null) }
    var retry by remember(repository) { mutableIntStateOf(0) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var path by rememberSaveable { mutableStateOf(".") }
    var task by rememberSaveable { mutableStateOf("assembleDebug") }
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
                        repository.license, repository.branch.ifBlank { "Основная ветка" },
                        revision?.take(12) ?: if (error == null) "получаем коммит" else "коммит недоступен"
                    ).joinToString("  ·  "),
                    color = Ember, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium
                )
                if (error != null) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { retry++ }) { Text("Запросить коммит снова") }
                }
                TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Посмотреть исходники на GitHub") }
                Text("Собирайте проекты, которым доверяете.")
                if (!sdkTerms) {
                    Text("При первой сборке Ковка загрузит инструменты.")
                    TextButton(onClick = onOpenTerms, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Условия Android SDK") }
                }
                run {
                    TextButton(
                        onClick = { advanced = !advanced }, contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text(if (advanced) "Скрыть параметры" else "Параметры сборки") }
                }
                if (advanced) {
                    OutlinedTextField(
                        value = path, onValueChange = { path = it.take(100) },
                        label = { Text("Папка Gradle") }, singleLine = true, isError = !pathOkay
                    )
                    OutlinedTextField(
                        value = task, onValueChange = { task = it.take(120) },
                        label = { Text("Задача Gradle") }, singleLine = true, isError = !taskOkay
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { revision?.let { onBuild(BuildRequest(repository.fullName, it, path, task)) } },
                enabled = revision != null && pathOkay && taskOkay && !busy,
                shapes = ButtonDefaults.shapes()
            ) { Text(if (busy) "Идёт сборка" else if (sdkTerms) "Собрать APK" else "Принять и собрать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ActiveBuildCard(job: JobStatus, onLogs: () -> Unit) {
    MessageCard(job.title, job.phase.title) {
        LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        TextButton(onClick = onLogs) { Text("Логи") }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LogsDialog(
    repository: String, status: String, running: Boolean, buildLog: String,
    installLog: String, onCopy: (String) -> Unit, onDismiss: () -> Unit
) {
    val scroll = rememberScrollState()
    val cleanLog = remember(buildLog, installLog) {
        (buildLog + if (installLog.isBlank()) "" else "\nУстановка\n$installLog")
            .replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"), "").replace('\r', '\n').trimEnd()
    }
    LaunchedEffect(cleanLog) { if (running) scroll.scrollTo(scroll.maxValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Логи") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(repository, style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.labelLarge, color = Ember)
                if (running) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                if (cleanLog.isNotBlank()) Surface(color = Ink, shape = RoundedCornerShape(16.dp)) {
                    SelectionContainer {
                        Text(cleanLog, Modifier.fillMaxWidth().heightIn(max = 350.dp).verticalScroll(scroll).padding(12.dp),
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Paper)
                    }
                }
            }
        },
        dismissButton = { if (cleanLog.isNotBlank()) TextButton(onClick = { onCopy(cleanLog) }) { Text("Копировать") } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ArtifactCard(record: BuildRecord, onInstall: (File) -> Unit, onShare: (File) -> Unit, onLogs: () -> Unit) {
    val timestamp = remember(record.time) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.time)) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = Panel) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(record.repository, style = MaterialTheme.typography.titleMedium)
            Text("$timestamp  ·  ${record.revision.take(10)}", fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium, color = Muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (record.success) "Готово" else "Сборка не завершилась",
                    Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = if (record.success) Mint else Muted)
                TextButton(onClick = onLogs) { Text("Логи") }
            }
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
