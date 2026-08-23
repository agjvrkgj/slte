package com.slte.desktop.storage

import com.slte.desktop.model.DesktopSettings
import com.slte.desktop.model.Session
import com.slte.desktop.system.OperatingSystem
import com.slte.desktop.system.Platform
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes

class AppStorage(
    private val directory: Path = Platform.appDataDirectory,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true },
) {
    private val settingsFile = directory.resolve("settings.json")
    private val secretStore: SecretStore = when (Platform.os) {
        OperatingSystem.Windows -> WindowsSecretStore(directory.resolve("session.bin"))
        OperatingSystem.MacOS -> MacKeychainSecretStore()
        OperatingSystem.Linux -> RestrictedFileSecretStore(directory.resolve("session.json"))
    }

    fun loadSettings(): DesktopSettings = runCatching {
        json.decodeFromString<DesktopSettings>(settingsFile.readText())
    }.getOrDefault(DesktopSettings())

    fun saveSettings(settings: DesktopSettings) {
        atomicWrite(settingsFile, json.encodeToString(settings).toByteArray(StandardCharsets.UTF_8))
    }

    fun loadSession(): Session? = secretStore.load()?.let { raw ->
        runCatching { json.decodeFromString<Session>(raw) }.getOrNull()
    }

    fun saveSession(session: Session) = secretStore.save(json.encodeToString(session))

    fun clearSession() = secretStore.clear()
}

private interface SecretStore {
    fun load(): String?
    fun save(value: String)
    fun clear()
}

/** Windows 使用当前用户 DPAPI；密文离开当前 Windows 用户上下文后无法解密。 */
private class WindowsSecretStore(private val file: Path) : SecretStore {
    override fun load(): String? = runCatching {
        if (!file.exists()) return null
        Crypt32Util.cryptUnprotectData(file.readBytes()).toString(StandardCharsets.UTF_8)
    }.getOrNull()

    override fun save(value: String) {
        val encrypted = Crypt32Util.cryptProtectData(value.toByteArray(StandardCharsets.UTF_8))
        atomicWrite(file, encrypted)
    }

    override fun clear() {
        file.deleteIfExists()
    }
}

/**
 * 直接调用 macOS Security.framework，令牌不会出现在命令行参数或普通配置文件中。
 */
private class MacKeychainSecretStore : SecretStore {
    private val service = "com.slte.desktop.session".toByteArray(StandardCharsets.UTF_8)
    private val account = "SLTE".toByteArray(StandardCharsets.UTF_8)

    override fun load(): String? {
        val length = IntByReference()
        val data = PointerByReference()
        val item = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(
            null,
            service.size,
            service,
            account.size,
            account,
            length,
            data,
            item,
        )
        if (status == ERR_SEC_ITEM_NOT_FOUND) return null
        checkStatus(status, "读取")
        return try {
            data.value.getByteArray(0, length.value).toString(StandardCharsets.UTF_8)
        } finally {
            security.SecKeychainItemFreeContent(null, data.value)
            item.value?.let(coreFoundation::CFRelease)
        }
    }

    override fun save(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val length = IntByReference()
        val data = PointerByReference()
        val item = PointerByReference()
        val find = security.SecKeychainFindGenericPassword(
            null,
            service.size,
            service,
            account.size,
            account,
            length,
            data,
            item,
        )
        if (find == 0) {
            security.SecKeychainItemFreeContent(null, data.value)
            try {
                checkStatus(
                    security.SecKeychainItemModifyAttributesAndData(item.value, null, bytes.size, bytes),
                    "更新",
                )
            } finally {
                item.value?.let(coreFoundation::CFRelease)
            }
            return
        }
        if (find != ERR_SEC_ITEM_NOT_FOUND) checkStatus(find, "查找")
        checkStatus(
            security.SecKeychainAddGenericPassword(
                null,
                service.size,
                service,
                account.size,
                account,
                bytes.size,
                bytes,
                null,
            ),
            "保存",
        )
    }

    override fun clear() {
        val length = IntByReference()
        val data = PointerByReference()
        val item = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(
            null,
            service.size,
            service,
            account.size,
            account,
            length,
            data,
            item,
        )
        if (status == ERR_SEC_ITEM_NOT_FOUND) return
        checkStatus(status, "查找")
        security.SecKeychainItemFreeContent(null, data.value)
        try {
            checkStatus(security.SecKeychainItemDelete(item.value), "删除")
        } finally {
            item.value?.let(coreFoundation::CFRelease)
        }
    }

    private fun checkStatus(status: Int, action: String) {
        if (status != 0) error("macOS 钥匙串${action}失败（OSStatus $status）")
    }

    private interface SecurityLibrary : Library {
        fun SecKeychainFindGenericPassword(
            keychain: Pointer?,
            serviceNameLength: Int,
            serviceName: ByteArray,
            accountNameLength: Int,
            accountName: ByteArray,
            passwordLength: IntByReference,
            passwordData: PointerByReference,
            itemRef: PointerByReference,
        ): Int

        fun SecKeychainAddGenericPassword(
            keychain: Pointer?,
            serviceNameLength: Int,
            serviceName: ByteArray,
            accountNameLength: Int,
            accountName: ByteArray,
            passwordLength: Int,
            passwordData: ByteArray,
            itemRef: PointerByReference?,
        ): Int

        fun SecKeychainItemModifyAttributesAndData(
            itemRef: Pointer,
            attributes: Pointer?,
            length: Int,
            data: ByteArray,
        ): Int

        fun SecKeychainItemDelete(itemRef: Pointer): Int
        fun SecKeychainItemFreeContent(attributes: Pointer?, data: Pointer): Int
    }

    private interface CoreFoundationLibrary : Library {
        fun CFRelease(value: Pointer)
    }

    companion object {
        private const val ERR_SEC_ITEM_NOT_FOUND = -25300
        private val security: SecurityLibrary by lazy { Native.load("Security", SecurityLibrary::class.java) }
        private val coreFoundation: CoreFoundationLibrary by lazy {
            Native.load("CoreFoundation", CoreFoundationLibrary::class.java)
        }
    }
}

/** Linux 仅用于源码开发；发布目标 Windows/macOS 分别使用 DPAPI/Keychain。 */
private class RestrictedFileSecretStore(private val file: Path) : SecretStore {
    override fun load(): String? = runCatching {
        if (file.exists()) file.readText() else null
    }.getOrNull()

    override fun save(value: String) {
        atomicWrite(file, value.toByteArray(StandardCharsets.UTF_8))
        runCatching {
            Files.setPosixFilePermissions(file, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
    }

    override fun clear() {
        file.deleteIfExists()
    }
}

internal fun atomicWrite(file: Path, bytes: ByteArray) {
    Files.createDirectories(file.parent)
    val temporary = file.resolveSibling("${file.fileName}.tmp")
    temporary.writeBytes(bytes)
    runCatching {
        Files.move(
            temporary,
            file,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }.getOrElse {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
    }
}
