package com.slte.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slte.desktop.DesktopController
import com.slte.desktop.model.ConnectionStatus
import com.slte.desktop.model.ProxyGroup
import com.slte.desktop.model.ProxyMode
import com.slte.desktop.model.SubscriptionInfo
import com.slte.desktop.model.UiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ln
import kotlin.math.pow

private enum class DesktopPage { Dashboard, Nodes, Settings }

@Composable
fun SlteDesktopApp(controller: DesktopController, state: UiState) {
    SlteTheme {
        Box(Modifier.fillMaxSize().background(SlteBackground)) {
            when {
                state.initializing -> LoadingScreen("正在初始化…")
                state.session == null -> LoginScreen(
                    busy = state.message?.startsWith("正在登录") == true,
                    onLogin = controller::login,
                )
                else -> LoggedInScreen(controller, state)
            }
            NoticeBanner(
                message = state.error ?: state.message,
                isError = state.error != null,
                onDismiss = controller::clearNotice,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun LoadingScreen(label: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = SlteBlue)
        Spacer(Modifier.height(18.dp))
        Text(label, color = SlteMuted)
    }
}

@Composable
private fun LoginScreen(busy: Boolean, onLogin: (String, String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val submit = { if (!busy) onLogin(email, password) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.width(430.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = SlteSurface),
        ) {
            Column(Modifier.padding(36.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(SlteBlue),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("S", color = Color(0xFF08223A), fontSize = 25.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("SLTE", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("Windows / macOS", color = SlteMuted)
                    }
                }
                Spacer(Modifier.height(34.dp))
                Text("登录账户", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("使用与 Android 客户端相同的机场账户", color = SlteMuted)
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("邮箱") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                        if (it.key == Key.Enter && it.type == KeyEventType.KeyUp) {
                            submit()
                            true
                        } else false
                    },
                    label = { Text("密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = submit,
                    enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text("登录", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "凭据由 Windows DPAPI 或 macOS 钥匙串加密保存。",
                    color = SlteMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun LoggedInScreen(controller: DesktopController, state: UiState) {
    var page by rememberSaveable { mutableStateOf(DesktopPage.Dashboard) }
    Row(Modifier.fillMaxSize()) {
        Sidebar(
            page = page,
            email = state.user?.email ?: state.session?.email.orEmpty(),
            connected = state.isConnected,
            onPage = { page = it },
            onLogout = controller::logout,
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            AnimatedContent(page) { current ->
                when (current) {
                    DesktopPage.Dashboard -> DashboardPage(controller, state)
                    DesktopPage.Nodes -> NodesPage(controller, state)
                    DesktopPage.Settings -> SettingsPage(controller, state)
                }
            }
        }
    }
}

@Composable
private fun Sidebar(
    page: DesktopPage,
    email: String,
    connected: Boolean,
    onPage: (DesktopPage) -> Unit,
    onLogout: () -> Unit,
) {
    Column(
        modifier = Modifier.width(225.dp).fillMaxHeight().background(SlteSurface).padding(18.dp),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(SlteBlue),
                contentAlignment = Alignment.Center,
            ) {
                Text("S", color = Color(0xFF08223A), fontWeight = FontWeight.Black, fontSize = 21.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("SLTE", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(if (connected) "已连接" else "未连接", color = if (connected) SlteGreen else SlteMuted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(30.dp))
        NavigationItem("首页", Icons.Rounded.Home, page == DesktopPage.Dashboard) { onPage(DesktopPage.Dashboard) }
        NavigationItem("节点", Icons.Rounded.Dns, page == DesktopPage.Nodes) { onPage(DesktopPage.Nodes) }
        NavigationItem("设置", Icons.Rounded.Settings, page == DesktopPage.Settings) { onPage(DesktopPage.Settings) }
        Spacer(Modifier.weight(1f))
        HorizontalDivider(color = SlteSurfaceLight)
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.AccountCircle, null, tint = SlteBlue, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(9.dp))
            Text(email, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
            IconButton(onClick = onLogout) { Icon(Icons.AutoMirrored.Rounded.Logout, "退出", tint = SlteMuted) }
        }
    }
}

@Composable
private fun NavigationItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) SlteSurfaceLight else Color.Transparent
    val color = if (selected) SlteBlue else SlteMuted
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(background)
            .clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color)
        Spacer(Modifier.width(13.dp))
        Text(label, color = if (selected) MaterialTheme.colorScheme.onSurface else color, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
    Spacer(Modifier.height(6.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardPage(controller: DesktopController, state: UiState) {
    Scaffold(
        containerColor = SlteBackground,
        topBar = {
            TopAppBar(
                title = { Text("首页", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SlteBackground),
                actions = {
                    IconButton(onClick = controller::refreshAccount) { Icon(Icons.Rounded.Refresh, "刷新") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(horizontal = 34.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SubscriptionCard(state.subscription)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                InfoCard(
                    icon = Icons.Rounded.Router,
                    title = "当前节点",
                    value = state.groups.firstOrNull { it.name == state.selectedGroup }?.current ?: "--",
                    modifier = Modifier.weight(1f),
                )
                InfoCard(
                    icon = Icons.Rounded.Shield,
                    title = "代理模式",
                    value = state.settings.proxyMode.displayName,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(18.dp))
            ConnectionCard(controller, state)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SubscriptionCard(info: SubscriptionInfo?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = SlteSurface),
    ) {
        Column(Modifier.padding(25.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(info?.planName?.takeIf(String::isNotBlank) ?: "暂无套餐", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${formatBytes(info?.usedTraffic ?: 0)} / ${formatBytes(info?.transferEnable ?: 0)}",
                        fontSize = 16.sp,
                    )
                }
                Text(
                    if (info != null && info.planId > 0) "有效" else "未开通",
                    color = if (info != null && info.planId > 0) SlteGreen else SlteMuted,
                    modifier = Modifier.clip(CircleShape).background(SlteSurfaceLight).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            LinearProgressIndicator(
                progress = { info?.usageRatio ?: 0f },
                modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                color = SlteBlue,
                trackColor = SlteSurfaceLight,
            )
            Spacer(Modifier.height(13.dp))
            Row {
                Text("已使用 ${((info?.usageRatio ?: 0f) * 100).toInt()}%", color = SlteMuted)
                Spacer(Modifier.weight(1f))
                Text(formatExpiry(info?.expiredAt ?: 0), color = SlteMuted)
            }
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SlteSurface),
    ) {
        Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(SlteSurfaceLight),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = SlteBlue) }
            Spacer(Modifier.width(15.dp))
            Column {
                Text(title, color = SlteMuted, fontSize = 13.sp)
                Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ConnectionCard(controller: DesktopController, state: UiState) {
    val connected = state.isConnected
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SlteSurface),
    ) {
        Row(Modifier.fillMaxWidth().padding(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(62.dp).clip(CircleShape).background(if (connected) SlteGreen.copy(alpha = .18f) else SltePink.copy(alpha = .16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PowerSettingsNew, null, tint = if (connected) SlteGreen else SltePink, modifier = Modifier.size(31.dp))
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(connectionLabel(state.connectionStatus), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (connected) "系统流量正在通过 mihomo" else "点击连接后自动接管系统代理",
                    color = SlteMuted,
                )
                state.coreProgress?.let { progress ->
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(260.dp))
                }
            }
            Button(
                onClick = controller::toggleConnection,
                enabled = state.session != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (connected) SltePink else SlteBlue,
                    contentColor = Color(0xFF08223A),
                ),
                modifier = Modifier.width(132.dp).height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (state.isBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(if (connected) "断开" else "连接", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodesPage(controller: DesktopController, state: UiState) {
    val group = state.groups.firstOrNull { it.name == state.selectedGroup }
    Scaffold(
        containerColor = SlteBackground,
        topBar = {
            TopAppBar(
                title = { Text("节点选择", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SlteBackground),
                actions = { IconButton(onClick = controller::refreshGroups, enabled = state.isConnected) { Icon(Icons.Rounded.Refresh, "刷新") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 34.dp, vertical = 12.dp)) {
            if (!state.isConnected) {
                EmptyState(Icons.Rounded.Lan, "连接后可选择节点")
                return@Column
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.groups.forEach { item ->
                    if (item.name == state.selectedGroup) {
                        Button(onClick = { controller.selectGroup(item.name) }) { Text(item.name) }
                    } else {
                        OutlinedButton(onClick = { controller.selectGroup(item.name) }) { Text(item.name) }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            if (group == null) {
                EmptyState(Icons.Rounded.Dns, "订阅中没有可选择的节点组")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(group.proxies, key = { index, proxy -> "$index:$proxy" }) { _, proxy ->
                        ProxyRow(proxy, selected = proxy == group.current) {
                            controller.selectProxy(group.name, proxy)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProxyRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) SlteSurfaceLight else SlteSurface),
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, SlteBlue) else null,
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(if (selected) SlteBlue else SlteSurfaceLight),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Dns, null, tint = if (selected) Color(0xFF08223A) else SlteBlue) }
            Spacer(Modifier.width(14.dp))
            Text(name, modifier = Modifier.weight(1f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            if (selected) Icon(Icons.Rounded.CheckCircle, "当前节点", tint = SlteGreen)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(controller: DesktopController, state: UiState) {
    Scaffold(
        containerColor = SlteBackground,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SlteBackground),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 34.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsCard("代理模式", "规则模式适合日常使用") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProxyMode.entries.forEach { mode ->
                        if (mode == state.settings.proxyMode) {
                            Button(onClick = { controller.setMode(mode) }) { Text(mode.displayName) }
                        } else {
                            OutlinedButton(onClick = { controller.setMode(mode) }) { Text(mode.displayName) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            ToggleSetting(
                icon = Icons.Rounded.Shield,
                title = "系统代理",
                subtitle = "连接后自动配置 Windows / macOS 系统代理",
                checked = state.settings.systemProxy,
                onChecked = controller::setSystemProxy,
            )
            Spacer(Modifier.height(14.dp))
            ToggleSetting(
                icon = Icons.Rounded.CloudDownload,
                title = "开机启动",
                subtitle = "登录系统后在托盘启动 SLTE",
                checked = state.settings.autoStart,
                onChecked = controller::setAutoStart,
            )
            Spacer(Modifier.height(14.dp))
            SettingsCard("内核信息", "mihomo 由官方 GitHub Release 下载并校验 SHA-256") {
                Text(state.coreVersion.ifBlank { "首次连接时自动安装" }, color = SlteBlue)
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SlteSurface),
    ) {
        Column(Modifier.padding(22.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = SlteMuted, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            content()
        }
    }
}

@Composable
private fun ToggleSetting(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SlteSurface),
    ) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(SlteSurfaceLight), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = SlteBlue)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = SlteMuted, fontSize = 13.sp)
            }
            Switch(checked = checked, onCheckedChange = onChecked)
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, text: String) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = SlteMuted, modifier = Modifier.size(54.dp))
        Spacer(Modifier.height(14.dp))
        Text(text, color = SlteMuted)
    }
}

@Composable
private fun NoticeBanner(message: String?, isError: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (message == null) return
    Card(
        modifier = modifier.padding(22.dp).clickable(onClick = onDismiss),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = if (isError) MaterialTheme.colorScheme.error else SlteSurfaceLight),
    ) {
        Text(
            message,
            color = if (isError) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

private fun connectionLabel(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.Disconnected -> "未连接"
    ConnectionStatus.DownloadingCore -> "正在准备"
    ConnectionStatus.Starting -> "正在连接"
    ConnectionStatus.Connected -> "已连接"
    ConnectionStatus.Stopping -> "正在断开"
    ConnectionStatus.Failed -> "连接失败"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    val group = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
    return "%.2f %s".format(bytes / 1024.0.pow(group), units[group])
}

private fun formatExpiry(timestamp: Long): String {
    if (timestamp <= 0) return "无到期时间"
    val date = Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    return "${date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))} 到期"
}
