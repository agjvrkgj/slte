package com.slte.desktop.system

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

enum class OperatingSystem { Windows, MacOS, Linux }
enum class CpuArchitecture(val mihomoName: String) { X64("amd64"), Arm64("arm64") }

object Platform {
    val os: OperatingSystem = when {
        System.getProperty("os.name").lowercase().contains("win") -> OperatingSystem.Windows
        System.getProperty("os.name").lowercase().contains("mac") -> OperatingSystem.MacOS
        else -> OperatingSystem.Linux
    }

    val arch: CpuArchitecture = when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> CpuArchitecture.Arm64
        else -> CpuArchitecture.X64
    }

    val appDataDirectory: Path by lazy {
        val home = System.getProperty("user.home")
        val path = when (os) {
            OperatingSystem.Windows -> Path(
                System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
                    ?: Path(home, "AppData", "Local").toString(),
                "SLTE",
            )
            OperatingSystem.MacOS -> Path(home, "Library", "Application Support", "SLTE")
            OperatingSystem.Linux -> Path(
                System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank)
                    ?: Path(home, ".local", "share").toString(),
                "slte",
            )
        }
        path.createDirectories()
    }
}
