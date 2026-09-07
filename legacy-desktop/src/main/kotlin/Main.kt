package org.example

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.example.app.AppBootstrap
import org.example.app.DesktopAppController
import org.example.config.LocalConfig
import org.example.config.LocalConfigStore
import org.example.desktop.DesktopApp
import org.example.llm.createLlmClient
import org.example.network.createHttpClient
import java.awt.Dimension

fun main() {
    System.setProperty("apple.awt.application.name", "LLM Workbench")
    val configStore = LocalConfigStore()
    val loadedConfig = runCatching(configStore::load)
    val bootstrap = AppBootstrap.from(loadedConfig.getOrDefault(LocalConfig()))
    val startupWarning = loadedConfig.exceptionOrNull()?.let {
        "Не удалось прочитать .env. Используются безопасные настройки по умолчанию."
    } ?: bootstrap.warning
    val httpClient = createHttpClient()
    val controller = DesktopAppController(
        initialSettings = bootstrap.settings,
        initialApiKeys = bootstrap.apiKeys,
        initialWarning = startupWarning,
        clientFactory = { kind, apiKey, model -> createLlmClient(kind, apiKey, httpClient, model) },
        persistSettings = configStore::save,
    )

    try {
        application {
            val appIcon = painterResource("icons/app-icon.png")
            val windowState = rememberWindowState(
                size = DpSize(1280.dp, 840.dp),
                position = WindowPosition.Aligned(Alignment.Center),
            )
            Window(
                onCloseRequest = ::exitApplication,
                title = "LLM Workbench",
                state = windowState,
                icon = appIcon,
            ) {
                LaunchedEffect(Unit) {
                    window.minimumSize = Dimension(960, 640)
                }
                DesktopApp(controller)
            }
        }
    } finally {
        controller.close()
        httpClient.close()
    }
}
