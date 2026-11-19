package dev.kovka.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class Repository(
    val fullName: String,
    val description: String,
    val language: String,
    val license: String,
    val stars: Int,
    val branch: String
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

    suspend fun search(query: String, language: String?, page: Int): CatalogPage {
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
            val item = parse(get("repos/$name"))
                ?: throw IOException("GitHub должен распознать открытую лицензию этого проекта.")
            return CatalogPage(listOf(item), false)
        }

        val words = value.split(Regex("\\s+"))
            .map { it.filter { character -> character.isLetterOrDigit() || character in "_.+-" } }
            .filter { it.isNotEmpty() }
        val qualifiers = buildList {
            addAll(words)
            add("topic:android-app")
            add("is:public")
            add("archived:false")
            add("fork:false")
            if (language != null) add("language:$language")
        }
        val sort = if (words.isEmpty()) "&sort=stars&order=desc" else ""
        val search = get("search/repositories?q=${Uri.encode(qualifiers.joinToString(" "))}&per_page=40&page=$page$sort")
        val items = search.getJSONArray("items")
        return CatalogPage(
            (0 until items.length()).mapNotNull { parse(items.getJSONObject(it)) },
            page * 40 < minOf(search.getInt("total_count"), 1000)
        )
    }

    suspend fun revision(repository: Repository): String {
        val commit = get("repos/${repository.fullName}/commits/${Uri.encode(repository.branch)}")
            .getString("sha")
        require(sha.matches(commit)) { "GitHub вернул некорректный SHA коммита." }
        return commit
    }

    private fun validName(name: String): Boolean =
        repoName.matches(name) && name.substringAfter('/') !in listOf(".", "..")

    private fun parse(item: JSONObject): Repository? {
        val name = item.getString("full_name")
        val license = item.optJSONObject("license")?.optString("spdx_id").orEmpty()
        if (item.optBoolean("private") || !validName(name) || license !in recognizedLicenses) return null
        return Repository(
            name, item.text("description"), item.text("language"), license,
            item.optInt("stargazers_count"), item.getString("default_branch")
        )
    }

    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL("https://api.github.com/$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 18_000
            connection.setRequestProperty("User-Agent", "Kovka-Android")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            when (val code = connection.responseCode) {
                200 -> JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                403, 429 -> throw IOException("GitHub ограничил число запросов. Попробуйте чуть позже.")
                404 -> throw IOException("GitHub не нашёл репозиторий или ветку.")
                else -> throw IOException("GitHub вернул HTTP $code.")
            }
        } finally {
            connection.disconnect()
        }
    }
}
