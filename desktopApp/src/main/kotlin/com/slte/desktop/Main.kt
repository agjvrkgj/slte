package com.slte.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.slte.desktop.ui.SlteDesktopApp

fun main(args: Array<String>) {
    val controller = DesktopController()
    application {
        var windowVisible by remember { mutableStateOf("--minimized" !in args) }
        val state by controller.state.collectAsState()
        val icon = painterResource("ic_launcher.png")

        LaunchedEffect(Unit) { controller.initialize() }

        Tray(
            icon = icon,
            tooltip = if (state.isConnected) "SLTE · 已连接" else "SLTE · 未连接",
            onAction = { windowVisible = true },
            menu = {
                Item("打开 SLTE", onClick = { windowVisible = true })
                Item(
                    if (state.isConnected) "断开" else "连接",
                    onClick = controller::toggleConnection,
                    enabled = state.session != null && !state.isBusy,
                )
                Separator()
                Item("退出", onClick = {
                    controller.close()
                    exitApplication()
                })
            },
        )

        Window(
            visible = windowVisible,
            onCloseRequest = { windowVisible = false },
            title = "SLTE",
            icon = icon,
            state = rememberWindowState(width = 1120.dp, height = 760.dp),
        ) {
            SlteDesktopApp(controller, state)
        }
    }
}
