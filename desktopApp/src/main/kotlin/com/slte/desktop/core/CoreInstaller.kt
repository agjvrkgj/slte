package com.slte.desktop.core

import com.slte.desktop.storage.atomicWrite
import com.slte.desktop.system.CpuArchitecture
import com.slte.desktop.system.OperatingSystem
import com.slte.desktop.system.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText

data class CoreBinary(val executable: Path, val version: String)

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val assets: List<GitHubAsset>,
)

@Serializable
internal data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long,
    val digest: String? = null,
)

class CoreInstaller(
    private val directory: Path = Platform.appDataDirectory.resolve("core"),
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val executable = directory.resolve(if (Platform.os == OperatingSystem.Windows) "mihomo.exe" else "mihomo")
    private val versionFile = directory.resolve("version.txt")

    suspend fun ensureInstalled(onProgress: (Float?) -> Unit = {}): CoreBinary = withContext(Dispatchers.IO) {
        System.getenv("SLTE_MIHOMO_PATH")?.takeIf(String::isNotBlank)?.let { configured ->
            val path = Path.of(configured).toAbsolutePath().normalize()
            require(path.exists() && Files.isRegularFile(path)) { "SLTE_MIHOMO_PATH 指向的文件不存在" }
            return@withContext CoreBinary(path, "external")
        }
        if (executable.exists() && versionFile.exists()) {
            return@withContext CoreBinary(executable, versionFile.readText().lineSequence().first().trim())
        }
        require(Platform.os != OperatingSystem.Linux) {
            "正式客户端仅支持 Windows 与 macOS；Linux 可通过 SLTE_MIHOMO_PATH 指定开发用内核"
        }
        directory.createDirectories()
        val release = fetchLatestRelease()
        val expectedName = expectedAssetName(release.tagName, Platform.os, Platform.arch)
        val asset = release.assets.firstOrNull { it.name == expectedName }
            ?: error("mihomo ${release.tagName} 未提供 $expectedName")
        val expectedDigest = asset.digest
            ?.takeIf { it.startsWith("sha256:") }
            ?.removePrefix("sha256:")
            ?: error("GitHub Release 未提供 SHA-256，拒绝安装未校验的内核")
        require(asset.downloadUrl.startsWith("https://github.com/MetaCubeX/mihomo/releases/download/")) {
            "mihomo 下载地址不受信任"
        }

        val archive = directory.resolve("${asset.name}.download")
        val unpacked = directory.resolve("mihomo.unpacking")
        try {
            download(asset, archive, expectedDigest, onProgress)
            extract(archive, unpacked, asset.name)
            makeExecutable(unpacked)
            runCatching {
                Files.move(
                    unpacked,
                    executable,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                Files.move(unpacked, executable, StandardCopyOption.REPLACE_EXISTING)
            }
            atomicWrite(
                versionFile,
                "${release.tagName}\nsha256:$expectedDigest\n${asset.name}\n".toByteArray(),
            )
            onProgress(1f)
            CoreBinary(executable, release.tagName)
        } finally {
            archive.deleteIfExists()
            unpacked.deleteIfExists()
        }
    }

    private fun fetchLatestRelease(): GitHubRelease {
        val request = HttpRequest.newBuilder(RELEASE_API)
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SLTE-Desktop/1.0.0")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "获取 mihomo 版本失败（HTTP ${response.statusCode()}）" }
        require(response.body().toByteArray().size <= MAX_RELEASE_JSON_BYTES) { "mihomo Release 元数据异常" }
        return json.decodeFromString(response.body())
    }

    private fun download(
        asset: GitHubAsset,
        target: Path,
        expectedDigest: String,
        onProgress: (Float?) -> Unit,
    ) {
        require(asset.size in 1..MAX_ARCHIVE_BYTES) { "mihomo 压缩包大小异常" }
        val request = HttpRequest.newBuilder(URI(asset.downloadUrl))
            .timeout(Duration.ofMinutes(3))
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "SLTE-Desktop/1.0.0")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        require(response.statusCode() in 200..299) { "下载 mihomo 失败（HTTP ${response.statusCode()}）" }
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        response.body().use { input ->
            BufferedInputStream(input).use { source ->
                BufferedOutputStream(Files.newOutputStream(target)).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        received += read
                        require(received <= MAX_ARCHIVE_BYTES) { "mihomo 下载超过大小上限" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress((received.toFloat() / asset.size).coerceIn(0f, 1f))
                    }
                }
            }
        }
        require(received == asset.size) { "mihomo 下载不完整" }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual.equals(expectedDigest, ignoreCase = true)) { "mihomo SHA-256 校验失败" }
    }

    private fun extract(archive: Path, target: Path, name: String) {
        var written = 0L
        val output = BufferedOutputStream(Files.newOutputStream(target))
        output.use { sink ->
            val source = if (name.endsWith(".zip")) {
                val zip = ZipInputStream(BufferedInputStream(Files.newInputStream(archive)))
                var entry = zip.nextEntry
                while (entry != null && (entry.isDirectory || !entry.name.substringAfterLast('/').startsWith("mihomo"))) {
                    entry = zip.nextEntry
                }
                require(entry != null) { "mihomo ZIP 中未找到可执行文件" }
                zip
            } else {
                GZIPInputStream(BufferedInputStream(Files.newInputStream(archive)))
            }
            source.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    written += read
                    require(written <= MAX_EXECUTABLE_BYTES) { "mihomo 解压后大小异常" }
                    sink.write(buffer, 0, read)
                }
            }
        }
        require(written > 0) { "mihomo 解压结果为空" }
    }

    private fun makeExecutable(file: Path) {
        file.toFile().setExecutable(true, true)
        runCatching {
            val permissions = Files.getPosixFilePermissions(file).toMutableSet()
            permissions += PosixFilePermission.OWNER_EXECUTE
            Files.setPosixFilePermissions(file, permissions)
        }
    }

    companion object {
        private val RELEASE_API = URI("https://api.github.com/repos/MetaCubeX/mihomo/releases/latest")
        private const val MAX_RELEASE_JSON_BYTES = 2 * 1024 * 1024
        private const val MAX_ARCHIVE_BYTES = 80L * 1024 * 1024
        private const val MAX_EXECUTABLE_BYTES = 200L * 1024 * 1024

        internal fun expectedAssetName(
            tag: String,
            os: OperatingSystem,
            arch: CpuArchitecture,
        ): String = when (os) {
            OperatingSystem.Windows -> "mihomo-windows-${arch.mihomoName}-$tag.zip"
            OperatingSystem.MacOS -> "mihomo-darwin-${arch.mihomoName}-$tag.gz"
            OperatingSystem.Linux -> error("Linux 不是 SLTE Desktop 发布目标")
        }
    }
}
