package com.slte.desktop.core

import com.slte.desktop.system.CpuArchitecture
import com.slte.desktop.system.OperatingSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreInstallerTest {
    @Test
    fun `selects exact release assets instead of cpu variants`() {
        assertEquals(
            "mihomo-windows-amd64-v1.19.30.zip",
            CoreInstaller.expectedAssetName("v1.19.30", OperatingSystem.Windows, CpuArchitecture.X64),
        )
        assertEquals(
            "mihomo-darwin-arm64-v1.19.30.gz",
            CoreInstaller.expectedAssetName("v1.19.30", OperatingSystem.MacOS, CpuArchitecture.Arm64),
        )
    }
}
