package com.slte.desktop.model

import kotlinx.serialization.Serializable

@Serializable
data class Session(
    val email: String,
    val authData: String,
    val subscribeToken: String,
)

data class UserInfo(
    val email: String,
    val balance: Long = 0,
)

data class SubscriptionInfo(
    val planId: Int = 0,
    val planName: String = "",
    val transferEnable: Long = 0,
    val upload: Long = 0,
    val download: Long = 0,
    val expiredAt: Long = 0,
    val resetDay: Int? = null,
) {
    val usedTraffic: Long get() = (upload + download).coerceAtLeast(0)
    val usageRatio: Float
        get() = if (transferEnable <= 0) 0f else (usedTraffic.toDouble() / transferEnable).toFloat().coerceIn(0f, 1f)
}

enum class ProxyMode(val apiValue: String, val displayName: String) {
    Rule("rule", "规则"),
    Global("global", "全局"),
    Direct("direct", "直连"),
}

data class ProxyGroup(
    val name: String,
    val type: String,
    val current: String,
    val proxies: List<String>,
)

enum class ConnectionStatus {
    Disconnected,
    DownloadingCore,
    Starting,
    Connected,
    Stopping,
    Failed,
}

@Serializable
data class DesktopSettings(
    val autoStart: Boolean = false,
    val systemProxy: Boolean = true,
    val proxyMode: ProxyMode = ProxyMode.Rule,
)

data class UiState(
    val initializing: Boolean = true,
    val session: Session? = null,
    val user: UserInfo? = null,
    val subscription: SubscriptionInfo? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val coreProgress: Float? = null,
    val coreVersion: String = "",
    val groups: List<ProxyGroup> = emptyList(),
    val selectedGroup: String? = null,
    val settings: DesktopSettings = DesktopSettings(),
    val message: String? = null,
    val error: String? = null,
) {
    val isConnected: Boolean get() = connectionStatus == ConnectionStatus.Connected
    val isBusy: Boolean get() = connectionStatus in setOf(
        ConnectionStatus.DownloadingCore,
        ConnectionStatus.Starting,
        ConnectionStatus.Stopping,
    )
}
