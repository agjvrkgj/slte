package com.slte.desktop.core

import com.slte.desktop.model.ProxyGroup
import com.slte.desktop.model.ProxyMode
import com.slte.desktop.storage.atomicWrite
import com.slte.desktop.system.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories

data class RunningCore(
    val mixedPort: Int,
    val controllerPort: Int,
    val version: String,
)

class MihomoController(
    private val runtimeDirectory: Path = Platform.appDataDirectory.resolve("runtime"),
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val lifecycle = Mutex()
    private var process: Process? = null
    private var secret: String = ""
    private var controllerPort: Int = 0
    private var mixedPort: Int = 0
    private var version: String = ""

    suspend fun start(
        binary: CoreBinary,
        subscriptionYaml: String,
        directDomains: List<String>,
        mode: ProxyMode,
    ): RunningCore = lifecycle.withLock {
        stopUnlocked()
        runtimeDirectory.createDirectories()
        mixedPort = freePort()
        controllerPort = generateSequence(::freePort).first { it != mixedPort }
        secret = randomSecret()
        version = binary.version

        val prepared = prepareConfig(
            subscriptionYaml,
            mixedPort,
            controllerPort,
            secret,
            directDomains,
            mode,
        )
        val configFile = runtimeDirectory.resolve("config.yaml")
        atomicWrite(configFile, prepared.toByteArray(StandardCharsets.UTF_8))
        restrictToCurrentUser(configFile)

        val logFile = runtimeDirectory.resolve("mihomo.log").toFile()
        val started = ProcessBuilder(
            binary.executable.toAbsolutePath().toString(),
            "-d",
            runtimeDirectory.toAbsolutePath().toString(),
            "-f",
            configFile.toAbsolutePath().toString(),
        )
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
        process = started
        try {
            awaitController(started)
            RunningCore(mixedPort, controllerPort, binary.version)
        } catch (error: Exception) {
            stopUnlocked()
            throw error
        }
    }

    suspend fun stop() = lifecycle.withLock { stopUnlocked() }

    fun isRunning(): Boolean = process?.isAlive == true

    suspend fun groups(): List<ProxyGroup> {
        val root = request("GET", "/proxies")
        val proxies = root["proxies"] as? JsonObject ?: return emptyList()
        return proxies.mapNotNull { (name, element) ->
            val value = element as? JsonObject ?: return@mapNotNull null
            val type = value.string("type").orEmpty()
            val all = (value["all"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            if (!type.equals("Selector", ignoreCase = true) || all.isEmpty()) return@mapNotNull null
            ProxyGroup(
                name = name,
                type = type,
                current = value.string("now").orEmpty(),
                proxies = all,
            )
        }.sortedBy { it.name != "GLOBAL" }
    }

    suspend fun select(group: String, proxy: String) {
        request(
            method = "PUT",
            path = "/proxies/${encodePath(group)}",
            body = buildJsonObject { put("name", proxy) }.toString(),
        )
    }

    suspend fun setMode(mode: ProxyMode) {
        request(
            method = "PATCH",
            path = "/configs",
            body = buildJsonObject { put("mode", mode.apiValue) }.toString(),
        )
    }

    private suspend fun awaitController(started: Process) {
        repeat(50) {
            if (!started.isAlive) {
                throw IllegalStateException("mihomo 启动失败，请查看 ${runtimeDirectory.resolve("mihomo.log")}")
            }
            val ready = runCatching { request("GET", "/version") }.isSuccess
            if (ready) return
            delay(200)
        }
        throw IllegalStateException("mihomo 控制端口启动超时")
    }

    private suspend fun request(method: String, path: String, body: String? = null): JsonObject =
        withContext(Dispatchers.IO) {
            check(controllerPort > 0 && secret.isNotBlank() && process?.isAlive == true) { "mihomo 未运行" }
            val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$controllerPort$path"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer $secret")
                .header("Accept", "application/json")
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody())
            } else {
                builder.header("Content-Type", "application/json")
                builder.method(method, HttpRequest.BodyPublishers.ofString(body))
            }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) {
                "mihomo API 请求失败（HTTP ${response.statusCode()}）"
            }
            if (response.body().isBlank()) JsonObject(emptyMap())
            else json.parseToJsonElement(response.body()).jsonObject
        }

    private fun stopUnlocked() {
        val running = process
        process = null
        if (running != null && running.isAlive) {
            running.destroy()
            if (!running.waitFor(3, TimeUnit.SECONDS)) {
                running.destroyForcibly()
                running.waitFor(2, TimeUnit.SECONDS)
            }
        }
        secret = ""
        controllerPort = 0
        mixedPort = 0
    }

    companion object {
        internal fun prepareConfig(
            raw: String,
            mixedPort: Int,
            controllerPort: Int,
            secret: String,
            directDomains: List<String>,
            mode: ProxyMode,
        ): String {
            require(raw.toByteArray().size <= 20 * 1024 * 1024) { "订阅文件超过 20 MB" }
            val loaderOptions = LoaderOptions().apply {
                maxAliasesForCollections = 50
                codePointLimit = 20 * 1024 * 1024
            }
            val loaded = Yaml(SafeConstructor(loaderOptions)).load<Any?>(raw)
            @Suppress("UNCHECKED_CAST")
            val root = loaded as? MutableMap<Any?, Any?>
                ?: throw IllegalArgumentException("订阅 YAML 顶层不是对象")
            require(root.containsKey("proxies") || root.containsKey("proxy-providers")) {
                "订阅不包含代理节点或代理提供器"
            }

            root["mixed-port"] = mixedPort
            root["allow-lan"] = false
            root["bind-address"] = "127.0.0.1"
            root["external-controller"] = "127.0.0.1:$controllerPort"
            root["secret"] = secret
            root["mode"] = mode.apiValue

            @Suppress("UNCHECKED_CAST")
            val tun = (root["tun"] as? MutableMap<Any?, Any?>) ?: linkedMapOf<Any?, Any?>().also {
                root["tun"] = it
            }
            tun["enable"] = false

            @Suppress("UNCHECKED_CAST")
            val rules = (root["rules"] as? MutableList<Any?>) ?: mutableListOf<Any?>().also {
                root["rules"] = it
            }
            val directRules = directDomains
                .filter(String::isNotBlank)
                .map { "DOMAIN-SUFFIX,${it.lowercase()},DIRECT" }
                .filter { it !in rules }
            rules.addAll(0, directRules)

            @Suppress("UNCHECKED_CAST")
            val dns = root["dns"] as? MutableMap<Any?, Any?>
            @Suppress("UNCHECKED_CAST")
            val fakeIpFilter = dns?.get("fake-ip-filter") as? MutableList<Any?>
            fakeIpFilter?.let { filters ->
                directDomains.map { "+.${it.lowercase()}" }
                    .filter { it !in filters }
                    .forEach(filters::add)
            }

            val dumperOptions = DumperOptions().apply {
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                isPrettyFlow = true
                indent = 2
                width = 160
            }
            return Yaml(dumperOptions).dump(root)
        }

        private fun randomSecret(): String {
            val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun freePort(): Int = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

        private fun encodePath(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        private fun restrictToCurrentUser(file: Path) {
            runCatching {
                Files.setPosixFilePermissions(
                    file,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
        }
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
