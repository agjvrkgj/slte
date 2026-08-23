package com.slte.desktop.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Properties

data class BuildConfig(
    val apiBaseUrl: String,
    val apiType: String,
    val remoteConfigUrls: List<String>,
    val allowedDomains: List<String>,
)

data class ResolvedConfig(
    val apiCandidates: List<String>,
    val apiType: String,
    val directDomains: List<String>,
)

object DesktopConfigLoader {
    fun load(): BuildConfig {
        val properties = Properties().apply {
            DesktopConfigLoader::class.java.getResourceAsStream("/slte-desktop.properties")?.use(::load)
        }
        fun value(environment: String, property: String, fallback: String = ""): String =
            System.getenv(environment)?.takeIf { it.isNotBlank() }
                ?: properties.getProperty(property)?.takeIf { it.isNotBlank() }
                ?: fallback

        val domains = buildList {
            add("example.com")
            value("SLTE_ALLOWED_DOMAINS", "allowedDomains")
                .split(',')
                .map(String::trim)
                .map(String::lowercase)
                .filter(String::isNotBlank)
                .forEach { if (it !in this) add(it) }
        }
        return BuildConfig(
            apiBaseUrl = value("SLTE_API_BASE_URL", "apiBaseUrl", "https://api.example.com").trimEnd('/'),
            apiType = value("SLTE_API_TYPE", "apiType", "xiaov2b").lowercase(),
            remoteConfigUrls = value("SLTE_REMOTE_CONFIG_URLS", "remoteConfigUrls")
                .split(',')
                .map(String::trim)
                .filter { it.startsWith("https://") },
            allowedDomains = domains,
        )
    }
}

class RemoteConfigResolver(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun resolve(build: BuildConfig): ResolvedConfig {
        val fetched = fetchRemote(build.remoteConfigUrls)
        val apiType = fetched?.get("api_type").asString()
            ?.takeIf { it == build.apiType }
            ?: build.apiType
        val candidates = buildList {
            fetched?.get("api_base_url").asString()?.let(::add)
            fetched?.get("api_base_urls").asStrings().forEach(::add)
            add(build.apiBaseUrl)
        }.map(String::trim)
            .map { it.trimEnd('/') }
            .filter { isAllowedApiUrl(it, build.allowedDomains) }
            .distinct()
        val directDomains = buildList {
            fetched?.get("direct_domains").asStrings().forEach(::add)
            candidates.mapNotNull { runCatching { URI(it).host }.getOrNull() }.forEach(::add)
        }.map(String::lowercase)
            .filter { isAllowedDomain(it, build.allowedDomains) }
            .distinct()

        require(candidates.isNotEmpty()) {
            "未配置受信任的 HTTPS 面板地址，请设置 SLTE_API_BASE_URL 与 SLTE_ALLOWED_DOMAINS"
        }
        return ResolvedConfig(
            apiCandidates = candidates,
            apiType = apiType,
            directDomains = directDomains,
        )
    }

    private suspend fun fetchRemote(urls: List<String>): JsonObject? = coroutineScope {
        urls.map { url ->
            async(Dispatchers.IO) {
                try {
                    val request = HttpRequest.newBuilder(URI(url))
                        .timeout(Duration.ofSeconds(6))
                        .header("Accept", "application/json")
                        .GET()
                        .build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                    require(response.statusCode() in 200..299)
                    require(response.body().toByteArray().size <= MAX_REMOTE_CONFIG_BYTES)
                    json.parseToJsonElement(response.body()).jsonObject
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll().firstOrNull { it != null }
    }

    companion object {
        private const val MAX_REMOTE_CONFIG_BYTES = 256 * 1024

        fun isAllowedApiUrl(value: String, suffixes: List<String>): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "https" && uri.userInfo == null && uri.host != null &&
                isAllowedHost(uri.host, suffixes)
        }.getOrDefault(false)

        fun isAllowedDomain(value: String, suffixes: List<String>): Boolean {
            val host = value.trim().lowercase().trimEnd('.')
            if (host.length !in 3..253 || host.split('.').size < 2) return false
            return isAllowedHost(host, suffixes)
        }

        private fun isAllowedHost(value: String, suffixes: List<String>): Boolean {
            val host = value.lowercase().trimEnd('.')
            return suffixes.any { suffix -> host == suffix || host.endsWith(".$suffix") }
        }
    }
}

private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.asStrings(): List<String> = when (this) {
    is JsonPrimitive -> listOfNotNull(contentOrNull)
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    else -> emptyList()
}
