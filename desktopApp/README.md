# SLTE Desktop

SLTE 的 Windows / macOS 桌面代理客户端，使用 Compose Desktop 构建，并管理独立的
[mihomo](https://github.com/MetaCubeX/mihomo) 进程。

## 功能

- XiaoV2b / Xboard 账户登录与订阅信息
- 订阅下载、规则/全局/直连模式、节点组与节点选择
- Windows WinINET 系统代理与 macOS `networksetup` 系统代理
- 原系统代理快照与异常退出后的自动恢复
- Windows DPAPI / macOS Keychain 会话加密
- 托盘运行与开机启动
- mihomo 官方 Release 自动安装与 GitHub SHA-256 digest 校验
- MSI、Intel DMG、Apple Silicon DMG 自动构建

桌面首版采用系统代理模式，不启用需要管理员权限的 TUN。mihomo 仅监听回环地址，
REST 控制器使用每次启动随机生成的 256 位密钥。

## 本地运行

需要 JDK 17：

```bash
export SLTE_API_BASE_URL=https://api.example.com
export SLTE_ALLOWED_DOMAINS=example.com
./gradlew :desktopApp:run
```

Linux 仅用于源码调试，需要提供本机 mihomo：

```bash
SLTE_MIHOMO_PATH=/path/to/mihomo ./gradlew :desktopApp:run
```

## 构建安装包

安装包只能在目标操作系统构建：

```bash
# Windows
./gradlew :desktopApp:packageMsi

# macOS
./gradlew :desktopApp:packageDmg
```

GitHub Actions 使用仓库 Variables 注入以下品牌配置：

| Variable | 说明 |
|---|---|
| `SLTE_API_BASE_URL` | 默认面板 API 地址 |
| `SLTE_API_TYPE` | `xiaov2b` 或 `xboard` |
| `SLTE_REMOTE_CONFIG_URLS` | OSS 配置 JSON 地址，多个用逗号分隔 |
| `SLTE_ALLOWED_DOMAINS` | 允许接收凭据的自有域名后缀，多个用逗号分隔 |

发布前还应配置 Windows Authenticode 与 Apple Developer ID/Notarization；当前 CI 产物为未签名测试包。
