package dev.kovka.app

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

internal class EmbeddedRuntime(private val context: Context) {
    private val home = File(context.filesDir, "builder")
    private val root = File(home, "ubuntu-24.04.5")
    private val libraries = File(home, "lib")
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val work = File(root, "kovka/jobs")
    @Volatile private var process: Process? = null

    private data class Platform(val abi: String, val ubuntu: String, val digest: String)

    private val platform = when (Build.SUPPORTED_64_BIT_ABIS.firstOrNull()) {
        "arm64-v8a" -> Platform("arm64-v8a", "arm64", "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2")
        "x86_64" -> Platform("x86_64", "amd64", "e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86")
        else -> throw IOException("Для сборки нужно 64-битное устройство.")
    }

    fun prepare(log: (String) -> Unit) {
        val runner = File(nativeDir, "libproot.so")
        val loader = File(nativeDir, "libproot-loader.so")
        check(runner.canExecute() && loader.canExecute()) { "Среда сборки недоступна на этом устройстве." }
        home.mkdirs()
        libraries.mkdirs()
        for (name in listOf("libtalloc.so.2", "libandroid-shmem.so")) {
            val output = File(libraries, name)
            if (!output.isFile) {
                val temporary = File(libraries, "$name.tmp")
                context.assets.open("runtime/${platform.abi}/$name").use { input ->
                    temporary.outputStream().use { input.copyTo(it) }
                }
                check(temporary.renameTo(output)) { "Не удалось подготовить среду." }
            }
        }
        if (!File(root, "kovka/rootfs-ready").isFile) {
            log("Загружаем среду сборки")
            val archive = File(home, "ubuntu.tar.gz")
            val staging = File(home, "ubuntu-staging")
            staging.deleteRecursively()
            try {
                val url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/" +
                    "ubuntu-base-24.04.5-base-${platform.ubuntu}.tar.gz"
                download(url, archive, platform.digest, log)
                log("Распаковываем среду сборки")
                staging.mkdirs()
                extract(archive, staging)
                File(staging, "kovka").mkdirs()
                File(staging, "kovka/rootfs-ready").createNewFile()
                root.deleteRecursively()
                check(staging.renameTo(root)) { "Не удалось сохранить среду сборки." }
            } finally {
                archive.delete()
                staging.deleteRecursively()
            }
        }
        work.mkdirs()
        File(root, "kovka/scripts").mkdirs()
        for (name in listOf("build.sh", "tools.sh", "toolchains.tsv")) {
            context.assets.open(name).use { input ->
                File(root, "kovka/scripts/$name").outputStream().use { input.copyTo(it) }
            }
        }
        configureNetwork()
    }

    fun run(id: String, request: BuildRequest, log: (String) -> Unit): File {
        val output = File(work, "$id/output")
        val arguments = listOf(
            File(nativeDir, "libproot.so").absolutePath, "--kill-on-exit", "--link2symlink",
            "-r", root.absolutePath,
            "-0", "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-w", "/kovka/jobs",
            "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LOGNAME=root",
            "PATH=/usr/local/bin:/usr/bin:/bin", "LANG=C.UTF-8", "TMPDIR=/tmp",
            "/bin/bash", "/kovka/scripts/build.sh", id, request.repository, request.revision,
            request.path, request.task, request.java
        )
        val temporary = File(home, "tmp").apply { mkdirs() }
        val builder = ProcessBuilder(arguments).directory(home).redirectErrorStream(true)
        builder.environment().apply {
            clear()
            put("PATH", "/system/bin")
            put("LD_LIBRARY_PATH", libraries.absolutePath)
            put("PROOT_TMP_DIR", temporary.absolutePath)
            put("PROOT_LOADER", File(nativeDir, "libproot-loader.so").absolutePath)
        }
        val child = builder.start()
        process = child
        try {
            child.outputStream.close()
            child.inputStream.bufferedReader().useLines { lines -> lines.forEach(log) }
            val status = child.waitFor()
            check(status == 0) { "Сборка завершилась с кодом $status. Подробности в журнале." }
            check(output.isDirectory) { "Сборка не создала папку с APK." }
            return output
        } finally {
            process = null
            child.destroy()
        }
    }

    fun discard(id: String) {
        File(work, id).deleteRecursively()
    }

    fun stop() {
        process?.destroy()
    }

    private fun configureNetwork() {
        val network = context.getSystemService(ConnectivityManager::class.java)
        val dns = network.activeNetwork?.let(network::getLinkProperties)?.dnsServers.orEmpty()
            .mapNotNull { it.hostAddress?.substringBefore('%') }
        val resolver = File(root, "etc/resolv.conf")
        Files.deleteIfExists(resolver.toPath())
        resolver.writeText(dns.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
            .joinToString("\n", postfix = "\n") { "nameserver $it" })
    }

    private fun download(url: String, target: File, expected: String, log: (String) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "Kovka-Android")
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Сервер инструментов вернул HTTP ${connection.responseCode}."
            }
            val sha = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        sha.update(buffer, 0, count)
                        received += count
                        check(received <= 128 * 1024 * 1024) { "Размер архива среды превышает ожидаемый." }
                        if (received - lastReport >= 8 * 1024 * 1024) {
                            log("Загружено ${received / (1024 * 1024)} МБ")
                            lastReport = received
                        }
                    }
                }
            }
            check(sha.digest().joinToString("") { "%02x".format(it) } == expected) {
                "Не совпала контрольная сумма среды сборки."
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extract(archive: File, destination: File) {
        val base = destination.canonicalFile.toPath()
        val links = mutableListOf<Triple<java.nio.file.Path, String, Boolean>>()
        TarArchiveInputStream(GZIPInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val path = base.resolve(entry.name).normalize()
                require(path.startsWith(base)) { "Некорректный путь в архиве среды." }
                if (path == base) continue
                val file = path.toFile()
                when {
                    entry.isDirectory -> file.mkdirs()
                    entry.isLink || entry.isSymbolicLink -> links.add(Triple(path, entry.linkName, entry.isSymbolicLink))
                    entry.isFile -> {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { tar.copyTo(it) }
                        Os.chmod(file.absolutePath, entry.mode and 0x1ff)
                    }
                }
            }
        }
        val hardLinks = mutableMapOf<java.nio.file.Path, java.nio.file.Path>()
        for ((path, name, symbolic) in links) {
            val target = when {
                name.startsWith("/") -> base.resolve(name.trimStart('/'))
                symbolic -> path.parent.resolve(name)
                else -> base.resolve(name)
            }.normalize()
            require(target.startsWith(base)) { "Некорректная ссылка в архиве среды." }
            path.parent.toFile().mkdirs()
            if (symbolic) Files.createSymbolicLink(path, path.parent.relativize(target))
            else hardLinks[path] = target
        }
        for ((path, target) in hardLinks) {
            var source = target
            val seen = mutableSetOf(path)
            while (source in hardLinks) {
                check(seen.add(source)) { "Обнаружен цикл ссылок в архиве среды." }
                source = hardLinks.getValue(source)
            }
            check(Files.isRegularFile(source) && source.toRealPath().startsWith(base)) {
                "В архиве среды отсутствует файл ${source.fileName}."
            }
            Files.copy(source, path)
            Os.chmod(path.toString(), Os.stat(source.toString()).st_mode and 0x1ff)
        }
    }
}
