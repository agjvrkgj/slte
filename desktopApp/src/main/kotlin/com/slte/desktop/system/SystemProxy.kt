package com.slte.desktop.system

import com.slte.desktop.storage.atomicWrite
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText

interface SystemProxy {
    val supported: Boolean
    suspend fun recoverIfNeeded()
    suspend fun enable(port: Int)
    suspend fun restore()
}

fun createSystemProxy(
    snapshotFile: Path = Platform.appDataDirectory.resolve("system-proxy-snapshot.json"),
): SystemProxy = when (Platform.os) {
    OperatingSystem.Windows -> WindowsSystemProxy(snapshotFile)
    OperatingSystem.MacOS -> MacSystemProxy(snapshotFile)
    OperatingSystem.Linux -> UnsupportedSystemProxy
}

@Serializable
private data class WindowsSnapshot(
    val proxyEnable: Int? = null,
    val proxyServer: String? = null,
    val proxyOverride: String? = null,
)

@Serializable
private data class MacProxyState(
    val enabled: Boolean,
    val server: String,
    val port: Int,
)

@Serializable
private data class MacServiceSnapshot(
    val name: String,
    val web: MacProxyState,
    val secureWeb: MacProxyState,
    val socks: MacProxyState,
)

private abstract class SnapshotSystemProxy(
    protected val snapshotFile: Path,
) : SystemProxy {
    protected val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    override suspend fun recoverIfNeeded() {
        if (snapshotFile.exists()) restore()
    }

    protected fun writeSnapshot(value: String) {
        atomicWrite(snapshotFile, value.toByteArray(StandardCharsets.UTF_8))
    }
}

private class WindowsSystemProxy(snapshotFile: Path) : SnapshotSystemProxy(snapshotFile) {
    override val supported: Boolean = true

    override suspend fun enable(port: Int) = withContext(Dispatchers.IO) {
        require(port in 1..65535)
        if (!snapshotFile.exists()) {
            val snapshot = WindowsSnapshot(
                proxyEnable = registryIntOrNull("ProxyEnable"),
                proxyServer = registryStringOrNull("ProxyServer"),
                proxyOverride = registryStringOrNull("ProxyOverride"),
            )
            writeSnapshot(json.encodeToString(snapshot))
        }
        Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, REGISTRY_PATH, "ProxyEnable", 1)
        Advapi32Util.registrySetStringValue(
            WinReg.HKEY_CURRENT_USER,
            REGISTRY_PATH,
            "ProxyServer",
            "http=127.0.0.1:$port;https=127.0.0.1:$port;socks=127.0.0.1:$port",
        )
        Advapi32Util.registrySetStringValue(
            WinReg.HKEY_CURRENT_USER,
            REGISTRY_PATH,
            "ProxyOverride",
            buildString {
                append("localhost;127.*;10.*;")
                append((16..31).joinToString(";") { "172.$it.*" })
                append(";192.168.*;<local>")
            },
        )
        notifySettingsChanged()
    }

    override suspend fun restore() = withContext(Dispatchers.IO) {
        if (!snapshotFile.exists()) return@withContext
        val snapshot = json.decodeFromString<WindowsSnapshot>(snapshotFile.readText())
        restoreRegistryValue("ProxyEnable", snapshot.proxyEnable)
        restoreRegistryValue("ProxyServer", snapshot.proxyServer)
        restoreRegistryValue("ProxyOverride", snapshot.proxyOverride)
        notifySettingsChanged()
        snapshotFile.deleteIfExists()
    }

    private fun registryIntOrNull(name: String): Int? = runCatching {
        if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, REGISTRY_PATH, name)) {
            Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, REGISTRY_PATH, name)
        } else null
    }.getOrNull()

    private fun registryStringOrNull(name: String): String? = runCatching {
        if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, REGISTRY_PATH, name)) {
            Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, REGISTRY_PATH, name)
        } else null
    }.getOrNull()

    private fun restoreRegistryValue(name: String, value: Int?) {
        if (value == null) deleteRegistryValue(name)
        else Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, REGISTRY_PATH, name, value)
    }

    private fun restoreRegistryValue(name: String, value: String?) {
        if (value == null) deleteRegistryValue(name)
        else Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, REGISTRY_PATH, name, value)
    }

    private fun deleteRegistryValue(name: String) {
        if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, REGISTRY_PATH, name)) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, REGISTRY_PATH, name)
        }
    }

    private fun notifySettingsChanged() {
        WinInetLibrary.instance.InternetSetOptionW(null, INTERNET_OPTION_SETTINGS_CHANGED, null, 0)
        WinInetLibrary.instance.InternetSetOptionW(null, INTERNET_OPTION_REFRESH, null, 0)
    }

    private interface WinInetLibrary : StdCallLibrary {
        fun InternetSetOptionW(handle: Pointer?, option: Int, buffer: Pointer?, bufferLength: Int): Boolean

        companion object {
            val instance: WinInetLibrary by lazy {
                Native.load("wininet", WinInetLibrary::class.java, W32APIOptions.DEFAULT_OPTIONS)
            }
        }
    }

    companion object {
        private const val REGISTRY_PATH = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
        private const val INTERNET_OPTION_REFRESH = 37
        private const val INTERNET_OPTION_SETTINGS_CHANGED = 39
    }
}

private class MacSystemProxy(snapshotFile: Path) : SnapshotSystemProxy(snapshotFile) {
    override val supported: Boolean = true

    override suspend fun enable(port: Int) = withContext(Dispatchers.IO) {
        require(port in 1..65535)
        val services = listServices()
        if (!snapshotFile.exists()) {
            val snapshots = services.map { service ->
                MacServiceSnapshot(
                    name = service,
                    web = getState(service, "-getwebproxy"),
                    secureWeb = getState(service, "-getsecurewebproxy"),
                    socks = getState(service, "-getsocksfirewallproxy"),
                )
            }
            writeSnapshot(json.encodeToString(snapshots))
        }
        services.forEach { service ->
            runNetworkSetup("-setwebproxy", service, "127.0.0.1", port.toString())
            runNetworkSetup("-setsecurewebproxy", service, "127.0.0.1", port.toString())
            runNetworkSetup("-setsocksfirewallproxy", service, "127.0.0.1", port.toString())
            runNetworkSetup("-setwebproxystate", service, "on")
            runNetworkSetup("-setsecurewebproxystate", service, "on")
            runNetworkSetup("-setsocksfirewallproxystate", service, "on")
        }
    }

    override suspend fun restore() = withContext(Dispatchers.IO) {
        if (!snapshotFile.exists()) return@withContext
        val snapshots = json.decodeFromString<List<MacServiceSnapshot>>(snapshotFile.readText())
        snapshots.forEach { service ->
            restoreState(service.name, "webproxy", service.web)
            restoreState(service.name, "securewebproxy", service.secureWeb)
            restoreState(service.name, "socksfirewallproxy", service.socks)
        }
        snapshotFile.deleteIfExists()
    }

    private fun listServices(): List<String> = runNetworkSetup("-listallnetworkservices")
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot { it.startsWith("An asterisk", ignoreCase = true) || it.startsWith('*') }
        .toList()

    private fun getState(service: String, action: String): MacProxyState {
        val values = runNetworkSetup(action, service)
            .lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator < 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
            }.toMap()
        return MacProxyState(
            enabled = values["Enabled"].equals("Yes", ignoreCase = true),
            server = values["Server"].orEmpty(),
            port = values["Port"]?.toIntOrNull() ?: 0,
        )
    }

    private fun restoreState(service: String, type: String, state: MacProxyState) {
        if (state.server.isNotBlank() && state.port in 1..65535) {
            runNetworkSetup("-set$type", service, state.server, state.port.toString())
        }
        runNetworkSetup("-set${type}state", service, if (state.enabled) "on" else "off")
    }

    private fun runNetworkSetup(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("/usr/sbin/networksetup") + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor(10, TimeUnit.SECONDS)) { "networksetup 执行超时" }
        check(process.exitValue() == 0) { output.trim().ifBlank { "networksetup 执行失败" } }
        return output
    }
}

private object UnsupportedSystemProxy : SystemProxy {
    override val supported: Boolean = false
    override suspend fun recoverIfNeeded() = Unit
    override suspend fun enable(port: Int) = Unit
    override suspend fun restore() = Unit
}
