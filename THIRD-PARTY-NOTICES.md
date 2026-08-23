# 第三方声明

本项目基于以下开源项目构建：

- **mihomo**（Clash.Meta 内核，GPL-3.0）：https://github.com/MetaCubeX/mihomo
  - 源码位于 `kernel-core/src/foss/golang/clash/`,随项目一并提供。
- **Clash Meta for Android（CMA，GPL-3.0）**：https://github.com/MetaCubeX/ClashMetaForAndroid
  - `kernel-common` / `kernel-core` / `kernel-service` / `kernel-hideapi` 模块派生自 CMA。
- **Compose Multiplatform（Apache-2.0）**：https://github.com/JetBrains/compose-multiplatform
  - 用于 Windows / macOS 桌面客户端界面与原生安装包构建。
- **Java Native Access（JNA，Apache-2.0 或 LGPL-2.1-or-later）**：https://github.com/java-native-access/jna
  - 用于 Windows DPAPI、WinINET 与 macOS Keychain 系统接口。
- **SnakeYAML（Apache-2.0）**：https://bitbucket.org/snakeyaml/snakeyaml
  - 用于安全解析并生成桌面端 mihomo YAML 配置。
- **Kotlin Coroutines（Apache-2.0）**：https://github.com/Kotlin/kotlinx.coroutines
  - 用于桌面端异步任务与 Swing 调度。

各第三方组件保留其原许可证与版权声明。SLTE 整体按 GPL-3.0 分发，许可证文本见 `LICENSE`。
