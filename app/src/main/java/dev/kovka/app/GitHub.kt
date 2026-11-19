package dev.kovka.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal enum class ProjectKind(val title: String, val topic: String) {
    ANDROID("Android", "android-app"),
    FLUTTER("Flutter", "flutter"),
    REACT_NATIVE("React Native", "react-native"),
    CAPACITOR("Capacitor", "capacitor")
}

internal data class Repository(
    val fullName: String,
    val description: String,
    val language: String,
    val license: String,
    val stars: Int,
    val branch: String,
    val kind: ProjectKind? = null
) {
    val name get() = fullName.substringAfter('/')
    val owner get() = fullName.substringBefore('/')
    val url get() = "https://github.com/$fullName"
}

internal data class CatalogPage(val repositories: List<Repository>, val hasMore: Boolean)

internal object GitHub {
    private val repoName = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}")
    private val sha = Regex("[a-f0-9]{40}")
    private val recognizedLicenses = setOf(
        "0BSD", "AFL-3.0", "AGPL-3.0", "Apache-2.0", "Artistic-2.0",
        "BSD-2-Clause", "BSD-3-Clause", "BSD-3-Clause-Clear", "BSL-1.0",
        "ECL-2.0", "EPL-1.0", "EPL-2.0", "EUPL-1.1", "EUPL-1.2",
        "GPL-2.0", "GPL-3.0", "LGPL-2.0", "LGPL-2.1", "LGPL-3.0",
        "ISC", "MIT", "MPL-2.0", "MS-PL", "MS-RL", "MulanPSL-2.0",
        "OFL-1.1", "OSL-3.0", "Unlicense", "UPL-1.0", "Zlib"
    )

    private class RateLimited : IOException("GitHub ограничил число запросов. Попробуйте чуть позже.")

    private val featured = listOf(
        featured("localsend/localsend", "Обмен файлами между устройствами в одной сети.", "Apache-2.0", ProjectKind.FLUTTER),
        featured("TeamNewPipe/NewPipe", "Лёгкий плеер для видео и фонового воспроизведения.", "GPL-3.0", ProjectKind.ANDROID),
        featured("AntennaPod/AntennaPod", "Подкасты, подписки и выпуски для прослушивания офлайн.", "GPL-3.0", ProjectKind.ANDROID),
        featured("laurent22/joplin", "Заметки, списки дел и синхронизация между устройствами.", "AGPL-3.0-or-later", ProjectKind.REACT_NATIVE),
        featured("FossifyOrg/Gallery", "Просмотр фотографий и управление альбомами.", "GPL-3.0", ProjectKind.ANDROID),
        featured("nylxar/curium", "Сканер и редактор QR-кодов.", "AGPL-3.0", ProjectKind.REACT_NATIVE)
    )

    private fun featured(name: String, description: String, license: String, kind: ProjectKind) =
        Repository(name, description, "", license, 0, "", kind)

    suspend fun search(query: String, kind: ProjectKind?, page: Int): CatalogPage {
        val typed = query.trim()
        val value = if (typed.startsWith("github.com/", ignoreCase = true)) "https://$typed" else typed
        if (value.startsWith("https://") || repoName.matches(value)) {
            val name = if (value.startsWith("https://")) {
                val uri = Uri.parse(value)
                require(uri.host.equals("github.com", ignoreCase = true) && uri.pathSegments.size == 2) {
                    "Введите ссылку на репозиторий GitHub."
                }
                uri.pathSegments.joinToString("/").removeSuffix(".git")
            } else value.removeSuffix(".git")
            require(validName(name)) { "Введите ссылку вида github.com/владелец/проект." }
            val item = try {
                parse(get("repos/$name"))
            } catch (_: RateLimited) {
                featured.firstOrNull { it.fullName.equals(name, true) } ?: publicRepository(name)
            } ?: throw IOException("GitHub должен распознать открытую лицензию этого проекта.")
            return CatalogPage(listOf(item), false)
        }

        if (value.isBlank() && kind == null) return CatalogPage(featured, false)

        val words = value.split(Regex("\\s+"))
            .map { it.filter { character -> character.isLetterOrDigit() || character in "_.+-" } }
            .filter { it.isNotEmpty() }
        val qualifiers = buildList {
            addAll(words)
            add("topic:${kind?.topic ?: ProjectKind.ANDROID.topic}")
            if (kind != null && kind != ProjectKind.ANDROID) add("android in:name,description,readme")
            add("is:public")
            add("archived:false")
            add("fork:false")
        }
        val sort = if (words.isEmpty()) "&sort=stars&order=desc" else ""
        val search = get("search/repositories?q=${Uri.encode(qualifiers.joinToString(" "))}&per_page=40&page=$page$sort")
        val items = search.getJSONArray("items")
        return CatalogPage(
            (0 until items.length()).mapNotNull { parse(items.getJSONObject(it), kind) },
            page * 40 < minOf(search.getInt("total_count"), 1000)
        )
    }

    suspend fun revision(repository: Repository): String = withContext(Dispatchers.IO) {
        val name = repository.fullName
        require(validName(name))
        val connection = connect("https://github.com/$name.git/info/refs?service=git-upload-pack")
        try {
            connection.setRequestProperty("Git-Protocol", "version=0")
            connection.setRequestProperty("Accept", "application/x-git-upload-pack-advertisement")
            if (connection.responseCode != 200) throw IOException("Не удалось получить коммит GitHub.")
            val refs = connection.inputStream.bufferedReader().use { it.readLine() + "\n" + it.readLine() }
            val commit = Regex("([a-f0-9]{40}) HEAD(?:\u0000|$)").find(refs)?.groupValues?.get(1)
                ?: throw IOException("GitHub не вернул основную ветку проекта.")
            require(sha.matches(commit))
            commit
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun publicRepository(name: String): Repository? = withContext(Dispatchers.IO) {
        for (file in listOf("LICENSE", "LICENSE.md", "LICENSE.txt", "COPYING")) {
            val connection = connect("https://raw.githubusercontent.com/$name/HEAD/$file")
            try {
                if (connection.responseCode != 200) continue
                val opening = connection.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(768)
                    val count = reader.read(buffer)
                    if (count < 1) "" else String(buffer, 0, count).trimStart()
                }
                val title = opening.lineSequence().firstOrNull().orEmpty().trim('#', ' ')
                val license = when {
                    title == "Apache License" && "Version 2.0" in opening -> "Apache-2.0"
                    title == "MIT License" && "Permission is hereby granted" in opening -> "MIT"
                    title == "GNU GENERAL PUBLIC LICENSE" && "Version 3" in opening -> "GPL-3.0"
                    title == "GNU AFFERO GENERAL PUBLIC LICENSE" && "Version 3" in opening -> "AGPL-3.0"
                    else -> null
                }
                if (license != null) return@withContext Repository(name, "", "", license, 0, "")
            } finally {
                connection.disconnect()
            }
        }
        null
    }

    private fun validName(name: String): Boolean =
        repoName.matches(name) && name.substringAfter('/') !in listOf(".", "..")

    private fun parse(item: JSONObject, requestedKind: ProjectKind? = null): Repository? {
        val name = item.getString("full_name")
        val license = item.optJSONObject("license")?.optString("spdx_id").orEmpty()
        if (item.optBoolean("private") || !validName(name) || license !in recognizedLicenses) return null
        val topics = item.optJSONArray("topics")
        val kind = requestedKind ?: listOf(ProjectKind.FLUTTER, ProjectKind.REACT_NATIVE, ProjectKind.CAPACITOR)
            .firstOrNull { candidate ->
                topics != null && (0 until topics.length()).any { topics.optString(it) == candidate.topic }
            }
        return Repository(
            name, item.text("description"), item.text("language"), license,
            item.optInt("stargazers_count"), item.getString("default_branch"), kind
        )
    }

    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = connect("https://api.github.com/$path")
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            when (val code = connection.responseCode) {
                200 -> JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                403, 429 -> throw RateLimited()
                404 -> throw IOException("GitHub не нашёл репозиторий или ветку.")
                else -> throw IOException("GitHub вернул HTTP $code.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun connect(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 12_000
        readTimeout = 18_000
        setRequestProperty("User-Agent", "Kovka-Android")
    }
}
