package com.slte.desktop.network

import com.slte.desktop.config.ResolvedConfig
import com.slte.desktop.model.Session
import com.slte.desktop.model.SubscriptionInfo
import com.slte.desktop.model.UserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class PanelException(message: String, val statusCode: Int? = null) : Exception(message)

class PanelApi(
    private val config: ResolvedConfig,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        // 认证请求禁止自动跟随重定向，避免 Authorization 被转发到非预期主机。
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    @Volatile
    private var activeBaseUrl: String = config.apiCandidates.first()

    suspend fun login(email: String, password: String): Session {
        val body = buildJsonObject {
            put("email", email.trim())
            put("password", password)
        }.toString()
        val root = requestJson(
            method = "POST",
            path = "/api/v1/passport/auth/login",
            body = body,
        )
        val data = root.requireDataObject()
        val token = data.string("token") ?: throw PanelException("登录响应缺少订阅令牌")
        val authData = data.string("auth_data") ?: throw PanelException("登录响应缺少认证令牌")
        return Session(email.trim(), authData, token)
    }

    suspend fun fetchUser(session: Session): UserInfo {
        val data = requestJson(
            method = "GET",
            path = "/api/v1/user/info",
            authorization = session.authData,
        ).requireDataObject()
        return UserInfo(
            email = data.string("email") ?: session.email,
            balance = data.long("balance"),
        )
    }

    suspend fun fetchSubscription(session: Session): SubscriptionInfo {
        val root = requestJson(
            method = "GET",
            path = "/api/v1/user/getSubscribe",
            authorization = session.authData,
            allowEmptyData = true,
        )
        val data = root["data"] as? JsonObject ?: return SubscriptionInfo()
        val plan = data["plan"] as? JsonObject
        return SubscriptionInfo(
            planId = data.int("plan_id"),
            planName = plan?.string("name").orEmpty(),
            transferEnable = data.long("transfer_enable"),
            upload = data.long("u"),
            download = data.long("d"),
            expiredAt = data.long("expired_at"),
            resetDay = data.nullableInt("reset_day"),
        )
    }

    suspend fun downloadSubscription(session: Session): String = withContext(Dispatchers.IO) {
        val token = URLEncoder.encode(session.subscribeToken, StandardCharsets.UTF_8)
        withCandidates { baseUrl ->
            val request = HttpRequest.newBuilder(URI("$baseUrl/api/v1/client/subscribe?token=$token"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/yaml, text/plain, */*")
                .header("Authorization", session.authData)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() !in 200..299) {
                throw httpError(response.statusCode(), response.body().decodeToString())
            }
            if (response.body().size > MAX_SUBSCRIPTION_BYTES) {
                throw PanelException("订阅文件超过 20 MB，已拒绝加载")
            }
            response.body().decodeToString().also { raw ->
                val trimmed = raw.trimStart()
                if (raw.isBlank() || trimmed.startsWith('<') || trimmed.startsWith('{') ||
                    !(raw.contains("proxies:") || raw.contains("proxy-providers:"))
                ) {
                    throw PanelException("服务端返回的不是有效 Clash/Mihomo 配置")
                }
            }
        }
    }

    private suspend fun requestJson(
        method: String,
        path: String,
        body: String? = null,
        authorization: String? = null,
        allowEmptyData: Boolean = false,
    ): JsonObject = withContext(Dispatchers.IO) {
        withCandidates { baseUrl ->
            val builder = HttpRequest.newBuilder(URI("$baseUrl$path"))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
            authorization?.let { builder.header("Authorization", it) }
            if (body != null) {
                builder.header("Content-Type", "application/json")
                builder.method(method, HttpRequest.BodyPublishers.ofString(body))
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody())
            }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (allowEmptyData && response.statusCode() == 403 && response.body().isBlank()) {
                return@withCandidates JsonObject(mapOf("data" to JsonNull))
            }
            if (response.statusCode() !in 200..299) {
                throw httpError(response.statusCode(), response.body())
            }
            if (response.body().toByteArray().size > MAX_JSON_BYTES) {
                throw PanelException("面板响应过大，已拒绝解析")
            }
            val root = runCatching { json.parseToJsonElement(response.body()).jsonObject }
                .getOrElse { throw PanelException("面板返回了无法解析的 JSON") }
            if (!allowEmptyData && root["data"] == null) {
                throw PanelException(root.string("message") ?: "服务器返回数据为空")
            }
            root
        }
    }

    private fun httpError(code: Int, rawBody: String): PanelException {
        val message = runCatching {
            json.parseToJsonElement(rawBody).jsonObject.string("message")
        }.getOrNull()?.takeIf(String::isNotBlank)
        return PanelException(message ?: "请求失败（HTTP $code）", code)
    }

    private fun <T> withCandidates(block: (String) -> T): T {
        val ordered = listOf(activeBaseUrl) + config.apiCandidates.filter { it != activeBaseUrl }
        var last: Throwable? = null
        for (baseUrl in ordered) {
            try {
                return block(baseUrl).also { activeBaseUrl = baseUrl }
            } catch (error: PanelException) {
                // 4xx 是业务/认证错误，切换镜像不会改变结果。
                if (error.statusCode in 400..499) throw error
                last = error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                last = error
            }
        }
        throw PanelException(last?.message?.take(240) ?: "所有 API 地址均不可用")
    }

    companion object {
        private const val USER_AGENT = "SLTE-Desktop/1.0.0 mihomo"
        private const val MAX_JSON_BYTES = 2 * 1024 * 1024
        private const val MAX_SUBSCRIPTION_BYTES = 20 * 1024 * 1024
    }
}

private fun JsonObject.requireDataObject(): JsonObject =
    this["data"] as? JsonObject ?: throw PanelException(string("message") ?: "服务器返回数据为空")

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: 0L

private fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: 0

private fun JsonObject.nullableInt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull
