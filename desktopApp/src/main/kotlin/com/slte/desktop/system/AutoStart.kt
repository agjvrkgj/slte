package com.slte.desktop.system

import com.slte.desktop.storage.atomicWrite
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

class AutoStartManager {
    val supported: Boolean = Platform.os != OperatingSystem.Linux

    private val target: Path? = when (Platform.os) {
        OperatingSystem.Windows -> System.getenv("APPDATA")?.let {
            Path(it, "Microsoft", "Windows", "Start Menu", "Programs", "Startup", "SLTE.cmd")
        }
        OperatingSystem.MacOS -> Path(
            System.getProperty("user.home"),
            "Library",
            "LaunchAgents",
            "com.slte.desktop.plist",
        )
        OperatingSystem.Linux -> null
    }

    fun isEnabled(): Boolean = target?.exists() == true

    fun setEnabled(enabled: Boolean) {
        val file = target ?: return
        if (!enabled) {
            file.deleteIfExists()
            return
        }
        val command = ProcessHandle.current().info().command().orElse(null)
            ?: error("无法确定 SLTE 可执行文件路径")
        require(command.substringAfterLast('/').substringAfterLast('\\').lowercase() !in setOf("java", "java.exe", "javaw.exe")) {
            "开机启动仅在安装后的 SLTE 客户端中可用"
        }
        val content = when (Platform.os) {
            OperatingSystem.Windows -> windowsScript(command)
            OperatingSystem.MacOS -> macLaunchAgent(command)
            OperatingSystem.Linux -> return
        }
        atomicWrite(file, content.toByteArray(StandardCharsets.UTF_8))
    }

    private fun windowsScript(command: String): String {
        require(!command.contains('"') && !command.contains('\r') && !command.contains('\n'))
        return "@echo off\r\nstart \"\" \"$command\" --minimized\r\n"
    }

    private fun macLaunchAgent(command: String): String {
        val escaped = command.xmlEscape()
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>com.slte.desktop</string>
  <key>ProgramArguments</key>
  <array>
    <string>$escaped</string>
    <string>--minimized</string>
  </array>
  <key>RunAtLoad</key>
  <true/>
</dict>
</plist>
"""
    }
}

private fun String.xmlEscape(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")
