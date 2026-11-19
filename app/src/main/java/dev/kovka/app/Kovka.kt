package dev.kovka.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private enum class Destination(val title: String, val icon: ImageVector) {
    CATALOG("Каталог", Icons.Rounded.Search),
    BUILDS("Сборки", Icons.Rounded.Build),
    ENVIRONMENT("Среда", Icons.Rounded.Settings)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Kovka(
    environment: Environment,
    onEnvironmentChanged: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenTermux: () -> Unit,
    onPermission: () -> Unit,
    onSettings: () -> Unit,
    onCopy: (String) -> Unit,
    onStart: (BuildRequest?) -> Unit,
    onInstall: (File) -> Unit,
    onShare: (File) -> Unit
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val status by BuildService.status.collectAsState()
    var destination by rememberSaveable { mutableStateOf(Destination.CATALOG) }
    var search by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableIntStateOf(1) }
    var refresh by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf(CatalogPage(emptyList(), false)) }
    var fetching by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Repository?>(null) }
    var history by remember { mutableStateOf(emptyList<BuildRecord>()) }

    LaunchedEffect(query, language, page, refresh) {
        fetching = true
        error = null
        try {
            results = GitHub.search(query, language, page)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = when (failure) {
                is java.net.UnknownHostException, is java.net.SocketTimeoutException -> "Проверьте подключение к интернету."
                else -> failure.localizedMessage ?: "Не удалось открыть каталог."
            }
        } finally {
            fetching = false
        }
    }
    LaunchedEffect(status?.phase) {
        history = withContext(Dispatchers.IO) { BuildHistory.list(context) }
        if (status?.setup == true) onEnvironmentChanged()
    }
    BackHandler(destination != Destination.CATALOG) { destination = Destination.CATALOG }

    fun submit() {
        focus.clearFocus()
        query = search.trim()
        page = 1
        refresh++
    }

    Scaffold(
        containerColor = Ink,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { focus.clearFocus(); destination = item },
                        icon = {
                            BadgedBox(badge = {
                                if (item == Destination.BUILDS && status?.active == true && status?.setup == false) {
                                    Badge(containerColor = Ember)
                                }
                            }) { Icon(item.icon, null) }
                        },
                        label = { Text(item.title) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Copper, selectedIconColor = Ember, selectedTextColor = Paper,
                            unselectedIconColor = Muted, unselectedTextColor = Muted
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            AnimatedContent(targetState = destination, label = "Раздел Ковки") { section ->
                LazyColumn(
                    modifier = Modifier.widthIn(max = 700.dp).fillMaxSize().imePadding(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (section) {
                        Destination.CATALOG -> {
                            item {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(color = Copper, shape = RoundedCornerShape(14.dp)) {
                                        Icon(Icons.Rounded.Build, null, Modifier.padding(10.dp).size(20.dp), tint = Ember)
                                    }
                                    Text("Ковка", Modifier.weight(1f).padding(start = 11.dp), style = MaterialTheme.typography.titleLarge)
                                    AssistChip(
                                        onClick = { destination = Destination.ENVIRONMENT },
                                        label = { Text(if (environment.ready) "Среда готова" else "Настроить") },
                                        leadingIcon = {
                                            Icon(if (environment.ready) Icons.Rounded.Check else Icons.Rounded.Settings, null,
                                                Modifier.size(16.dp), tint = if (environment.ready) Mint else Ember)
                                        }
                                    )
                                }
                            }
                            if (query.isBlank() && page == 1) item { ForgeBanner() }
                            item {
                                TextField(
                                    value = search, onValueChange = { search = it.take(120) },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                                    placeholder = { Text("Название или ссылка GitHub", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                                    trailingIcon = {
                                        if (search.isNotEmpty()) Row {
                                            IconButton(onClick = { search = ""; submit() }) {
                                                Icon(Icons.Rounded.Close, "Очистить")
                                            }
                                            IconButton(onClick = ::submit) {
                                                Icon(Icons.Rounded.KeyboardArrowRight, "Искать")
                                            }
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                                    shape = CircleShape,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Raised, unfocusedContainerColor = Raised,
                                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                        focusedLeadingIconColor = Ember, unfocusedLeadingIconColor = Muted
                                    )
                                )
                            }
                            item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(null, "Kotlin", "Java").forEach { value ->
                                        FilterChip(
                                            selected = language == value,
                                            onClick = { if (language != value) { language = value; page = 1 }; focus.clearFocus() },
                                            label = { Text(value ?: "Все") },
                                            leadingIcon = if (language == value) {
                                                { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                                            } else null,
                                            shape = CircleShape,
                                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Copper)
                                        )
                                    }
                                }
                            }
                            item {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (query.isBlank()) "Проекты для Android" else "Результаты поиска",
                                        Modifier.weight(1f), style = MaterialTheme.typography.titleLarge
                                    )
                                    Text("GitHub", color = Muted, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            when {
                                fetching -> item { LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp)) }
                                error != null -> item {
                                    MessageCard("Каталог недоступен", error.orEmpty()) {
                                        TextButton(onClick = { refresh++ }) { Text("Повторить") }
                                    }
                                }
                                results.repositories.isEmpty() -> item {
                                    MessageCard("Нет проектов на этой странице", "Уточните запрос или вставьте ссылку на репозиторий.")
                                }
                                else -> items(results.repositories, key = { it.fullName }) { repository ->
                                    RepoCard(repository) { selected = repository }
                                }
                            }
                            if (!fetching && error == null && (page > 1 || results.hasMore)) item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    TextButton(onClick = { page-- }, enabled = page > 1) {
                                        Icon(Icons.Rounded.ArrowBack, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Назад")
                                    }
                                    TextButton(onClick = { page++ }, enabled = results.hasMore) {
                                        Text("Дальше")
                                        Icon(Icons.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        Destination.BUILDS -> {
                            item { PageHeading("Сборки", "APK, собранные на этом устройстве") }
                            val job = status?.takeUnless { it.setup }
                            if (job != null) item { JobCard(job, onCopy) }
                            if (history.isEmpty() && job == null) item {
                                MessageCard("Пока пусто", "Откройте репозиторий в каталоге и запустите первую сборку.") {
                                    Button(onClick = { destination = Destination.CATALOG }, shapes = ButtonDefaults.shapes()) {
                                        Text("Открыть каталог")
                                    }
                                }
                            }
                            items(history, key = { it.id }) { record -> ArtifactCard(record, onInstall, onShare) }
                        }
                        Destination.ENVIRONMENT -> {
                            item { PageHeading("Среда", "Инструменты сборки на телефоне") }
                            item {
                                EnvironmentCard(
                                    environment, status?.active == true, onOpenTermux, onPermission,
                                    onSettings, onOpenUrl, onCopy, { onStart(null) }
                                )
                            }
                            status?.takeIf { it.setup }?.let { job -> item { JobCard(job, onCopy) } }
                        }
                    }
                }
            }
        }
    }

    selected?.let { repository ->
        RepositoryDialog(
            repository = repository, environmentReady = environment.ready,
            busy = status?.active == true, onDismiss = { selected = null },
            onOpen = { onOpenUrl(repository.url) },
            onConfigure = { selected = null; destination = Destination.ENVIRONMENT },
            onBuild = { request ->
                selected = null
                destination = Destination.BUILDS
                onStart(request)
            }
        )
    }
}

@Composable
internal fun PageHeading(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

@Composable
internal fun MessageCard(title: String, description: String, actions: @Composable () -> Unit = {}) {
    Surface(Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = Muted)
            actions()
        }
    }
}

@Composable
private fun ForgeBanner() {
    Surface(Modifier.fillMaxWidth(), color = Ember, contentColor = Color(0xFF2E1807),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 10.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 164.dp)) {
            if (maxWidth >= 290.dp) {
                ForgeIllustration(Modifier.align(Alignment.CenterEnd).width(120.dp).height(150.dp))
            }
            Column(Modifier.padding(start = 19.dp, top = 21.dp, bottom = 20.dp, end = if (maxWidth >= 290.dp) 108.dp else 16.dp)) {
                Text("Из кода\nв APK", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text("Сборка на телефоне", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ForgeIllustration(modifier: Modifier) {
    Canvas(modifier) {
        val unit = size.width / 120f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun face(color: Color, vararg vertices: Pair<Float, Float>) {
            drawPath(Path().apply {
                vertices.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * unit, y * unit) else lineTo(x * unit, y * unit) }
                close()
            }, color)
        }
        val dark = Color(0xFF59351D)
        val mid = Color(0xFF955221)
        val light = Color(0xFFFFD8A9)
        repeat(3) { i ->
            val y = 23f + i * 6
            drawLine(mid, point(12f, y), point(12f + 19f - i * 4, y), unit * 1.5f)
        }
        face(light, 16f to 61f, 55f to 39f, 94f to 61f, 55f to 83f)
        face(mid, 16f to 65f, 53f to 86f, 53f to 123f, 16f to 102f)
        face(dark, 57f to 86f, 94f to 65f, 94f to 102f, 57f to 123f)
        drawLine(light, point(65f, 101f), point(85f, 89f), unit * 2)
        drawCircle(dark, 6f * unit, point(93f, 33f), style = Stroke(unit * 1.5f))
        drawLine(dark, point(93f, 23f), point(93f, 43f), unit)
        drawLine(dark, point(83f, 33f), point(103f, 33f), unit)
        drawLine(mid, point(98f, 119f), point(110f, 112f), unit * 2)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RepoCard(repository: Repository, onClick: () -> Unit) {
    val accents = listOf(Ember, Mint, Color(0xFFF0B9CA), Color(0xFFE5C2A2))
    val accent = accents[Math.floorMod(repository.fullName.hashCode(), accents.size)]
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Panel) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
            Surface(color = accent.copy(alpha = 0.15f), contentColor = accent, shape = RoundedCornerShape(15.dp)) {
                Box(Modifier.size(47.dp), contentAlignment = Alignment.Center) {
                    Text(repository.name.take(2).uppercase(Locale.ROOT), style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                Text(repository.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(repository.owner, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (repository.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(repository.description, color = Muted, style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(7.dp))
                Text(listOf(repository.language, repository.license).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Star, "Звёзды GitHub", Modifier.size(14.dp), tint = Ember)
                    Spacer(Modifier.width(3.dp))
                    Text(formatStars(repository.stars), style = MaterialTheme.typography.labelSmall, color = Ember)
                }
                Icon(Icons.Rounded.KeyboardArrowRight, null, Modifier.padding(top = 9.dp).size(20.dp), tint = Muted)
            }
        }
    }
}

private fun formatStars(stars: Int): String =
    if (stars < 1000) stars.toString() else String.format(Locale.ROOT, "%.1f тыс.", stars / 1000f)
