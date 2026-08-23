package com.slte.desktop

import com.slte.desktop.config.DesktopConfigLoader
import com.slte.desktop.config.RemoteConfigResolver
import com.slte.desktop.config.ResolvedConfig
import com.slte.desktop.core.CoreInstaller
import com.slte.desktop.core.MihomoController
import com.slte.desktop.model.ConnectionStatus
import com.slte.desktop.model.DesktopSettings
import com.slte.desktop.model.ProxyMode
import com.slte.desktop.model.Session
import com.slte.desktop.model.UiState
import com.slte.desktop.network.PanelApi
import com.slte.desktop.storage.AppStorage
import com.slte.desktop.system.AutoStartManager
import com.slte.desktop.system.SystemProxy
import com.slte.desktop.system.createSystemProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class DesktopController(
    private val storage: AppStorage = AppStorage(),
    private val configResolver: RemoteConfigResolver = RemoteConfigResolver(),
    private val coreInstaller: CoreInstaller = CoreInstaller(),
    private val core: MihomoController = MihomoController(),
    private val systemProxy: SystemProxy = createSystemProxy(),
    private val autoStart: AutoStartManager = AutoStartManager(),
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private lateinit var resolvedConfig: ResolvedConfig
    private lateinit var panelApi: PanelApi
    private var connectionJob: Job? = null

    fun initialize() {
        scope.launch {
            runCatching {
                // 上次若异常退出，先恢复用户原来的系统代理，再初始化网络请求。
                systemProxy.recoverIfNeeded()
                val build = DesktopConfigLoader.load()
                resolvedConfig = configResolver.resolve(build)
                panelApi = PanelApi(resolvedConfig)
                val settings = storage.loadSettings().copy(autoStart = autoStart.isEnabled())
                val session = storage.loadSession()
                _state.value = _state.value.copy(
                    initializing = false,
                    session = session,
                    settings = settings,
                    error = null,
                )
                if (session != null) refreshAccountInternal(session)
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    initializing = false,
                    error = friendlyMessage(error),
                )
            }
        }
    }

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            showError("请输入邮箱和密码")
            return
        }
        scope.launch {
            _state.value = _state.value.copy(message = "正在登录…", error = null)
            runCatching { panelApi.login(email, password) }
                .onSuccess { session ->
                    storage.saveSession(session)
                    _state.value = _state.value.copy(session = session, message = null)
                    refreshAccountInternal(session)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(message = null, error = friendlyMessage(error))
                }
        }
    }

    fun refreshAccount() {
        val session = _state.value.session ?: return
        scope.launch { refreshAccountInternal(session) }
    }

    private suspend fun refreshAccountInternal(session: Session) {
        _state.value = _state.value.copy(message = "正在刷新账户…", error = null)
        val user = scope.async(Dispatchers.IO) { panelApi.fetchUser(session) }
        val subscription = scope.async(Dispatchers.IO) { panelApi.fetchSubscription(session) }
        runCatching { user.await() to subscription.await() }
            .onSuccess { (userInfo, subscriptionInfo) ->
                _state.value = _state.value.copy(
                    user = userInfo,
                    subscription = subscriptionInfo,
                    message = null,
                )
            }
            .onFailure { error ->
                _state.value = _state.value.copy(message = null, error = friendlyMessage(error))
            }
    }

    fun toggleConnection() {
        if (_state.value.isConnected || _state.value.isBusy) disconnect() else connect()
    }

    fun connect() {
        val session = _state.value.session ?: return
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch {
            _state.value = _state.value.copy(
                connectionStatus = ConnectionStatus.DownloadingCore,
                coreProgress = null,
                message = "正在获取订阅与代理内核…",
                error = null,
            )
            runCatching {
                val subscription = async(Dispatchers.IO) { panelApi.downloadSubscription(session) }
                val binary = async(Dispatchers.IO) {
                    coreInstaller.ensureInstalled { progress ->
                        _state.value = _state.value.copy(coreProgress = progress)
                    }
                }
                val raw = subscription.await()
                val installed = binary.await()
                _state.value = _state.value.copy(
                    connectionStatus = ConnectionStatus.Starting,
                    coreVersion = installed.version,
                    coreProgress = null,
                    message = "正在启动 mihomo…",
                )
                val running = core.start(
                    binary = installed,
                    subscriptionYaml = raw,
                    directDomains = resolvedConfig.directDomains,
                    mode = _state.value.settings.proxyMode,
                )
                if (_state.value.settings.systemProxy) systemProxy.enable(running.mixedPort)
                val groups = core.groups()
                _state.value = _state.value.copy(
                    connectionStatus = ConnectionStatus.Connected,
                    coreVersion = running.version,
                    groups = groups,
                    selectedGroup = groups.firstOrNull()?.name,
                    message = "已连接",
                )
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                runCatching { systemProxy.restore() }
                runCatching { core.stop() }
                _state.value = _state.value.copy(
                    connectionStatus = ConnectionStatus.Failed,
                    coreProgress = null,
                    message = null,
                    error = friendlyMessage(error),
                )
            }
        }
    }

    fun disconnect() {
        val jobToCancel = connectionJob
        jobToCancel?.cancel()
        connectionJob = scope.launch {
            jobToCancel?.join()
            _state.value = _state.value.copy(
                connectionStatus = ConnectionStatus.Stopping,
                message = "正在断开…",
                error = null,
            )
            val restoreError = runCatching { systemProxy.restore() }.exceptionOrNull()
            runCatching { core.stop() }
            _state.value = _state.value.copy(
                connectionStatus = ConnectionStatus.Disconnected,
                groups = emptyList(),
                selectedGroup = null,
                message = "已断开",
                error = restoreError?.let(::friendlyMessage),
            )
        }
    }

    fun refreshSubscription() {
        if (_state.value.isConnected) {
            scope.launch {
                disconnectAndWait()
                connect()
            }
        } else {
            refreshAccount()
        }
    }

    fun selectGroup(name: String) {
        _state.value = _state.value.copy(selectedGroup = name)
    }

    fun selectProxy(group: String, proxy: String) {
        scope.launch {
            runCatching {
                core.select(group, proxy)
                refreshGroupsInternal(group)
            }.onFailure(::showError)
        }
    }

    fun refreshGroups() {
        scope.launch {
            runCatching { refreshGroupsInternal(_state.value.selectedGroup) }
                .onFailure(::showError)
        }
    }

    private suspend fun refreshGroupsInternal(preferred: String?) {
        val groups = core.groups()
        _state.value = _state.value.copy(
            groups = groups,
            selectedGroup = preferred?.takeIf { name -> groups.any { it.name == name } }
                ?: groups.firstOrNull()?.name,
        )
    }

    fun setMode(mode: ProxyMode) {
        val settings = _state.value.settings.copy(proxyMode = mode)
        saveSettings(settings)
        if (_state.value.isConnected) {
            scope.launch { runCatching { core.setMode(mode) }.onFailure(::showError) }
        }
    }

    fun setSystemProxy(enabled: Boolean) {
        val settings = _state.value.settings.copy(systemProxy = enabled)
        saveSettings(settings)
        if (_state.value.isConnected) {
            scope.launch {
                runCatching {
                    if (enabled) {
                        // mixed 端口只在 RunningCore 内返回；切换时重启以重新应用并避免保存重复快照。
                        disconnectAndWait()
                        connect()
                    } else {
                        systemProxy.restore()
                    }
                }.onFailure(::showError)
            }
        }
    }

    fun setAutoStart(enabled: Boolean) {
        runCatching { autoStart.setEnabled(enabled) }
            .onSuccess { saveSettings(_state.value.settings.copy(autoStart = enabled)) }
            .onFailure(::showError)
    }

    fun logout() {
        scope.launch {
            disconnectAndWait()
            storage.clearSession()
            _state.value = UiState(
                initializing = false,
                settings = _state.value.settings,
                message = "已退出登录",
            )
        }
    }

    fun clearNotice() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    private fun saveSettings(settings: DesktopSettings) {
        storage.saveSettings(settings)
        _state.value = _state.value.copy(settings = settings)
    }

    private suspend fun disconnectAndWait() {
        connectionJob?.cancel()
        connectionJob?.join()
        runCatching { systemProxy.restore() }
        runCatching { core.stop() }
        _state.value = _state.value.copy(
            connectionStatus = ConnectionStatus.Disconnected,
            groups = emptyList(),
            selectedGroup = null,
        )
    }

    private fun showError(error: Throwable) = showError(friendlyMessage(error))

    private fun showError(message: String) {
        _state.value = _state.value.copy(error = message, message = null)
    }

    private fun friendlyMessage(error: Throwable): String {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .firstOrNull { it.isNotBlank() }
            ?.replace(Regex("(?i)(token|auth_data|authorization|password)([=: ]+)\\S+"), "$1$2***")
            ?.take(300)
        return message ?: "操作失败，请稍后重试"
    }

    override fun close() {
        runBlocking {
            connectionJob?.cancel()
            connectionJob?.join()
            runCatching { systemProxy.restore() }
            runCatching { core.stop() }
        }
        scope.cancel()
    }
}
