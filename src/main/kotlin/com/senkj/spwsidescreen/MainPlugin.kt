@file:OptIn(UnstableSpwWorkshopApi::class)

package com.senkj.spwsidescreen

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import java.awt.EventQueue
import java.nio.file.Path

class MainPlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext) {
    private var window: SubscreenWindow? = null
    private var configListenerInstalled = false

    override fun start() {
        NowPlayingState.start()
        installConfigListener()
        EventQueue.invokeLater { recreateWindow() }
        WorkshopApi.ui.toast("副屏播放界面已启动", WorkshopApi.Ui.ToastType.Success)
    }

    override fun stop() {
        EventQueue.invokeLater {
            window?.dispose()
            window = null
        }
        NowPlayingState.stop()
    }

    private fun installConfigListener() {
        if (configListenerInstalled) return
        configListenerInstalled = true
        runCatching {
            WorkshopApi.manager.createConfigManager().addConfigChangeListener {
                EventQueue.invokeLater { recreateWindow() }
            }
        }
    }

    private fun recreateWindow() {
        val (settings, dataDir) = readSettings()
        window?.dispose()
        window = SubscreenWindow(
            pluginDataDir = dataDir,
            rememberScreen = settings.rememberScreen,
            alwaysOnTop = settings.alwaysOnTop,
            autoFullscreen = settings.autoFullscreen,
        ).also { it.open() }
    }

    private fun readSettings(): Pair<Settings, Path> {
        val fallback = Settings(true, true, false) to Path.of(".")
        return runCatching {
            val helper: ConfigHelper = WorkshopApi.manager.createConfigManager().getConfig("sidescreen.json")
            helper.reload()
            val remember = helper.get("remember_screen", true) as? Boolean ?: true
            val onTop = helper.get("always_on_top", true) as? Boolean ?: true
            val auto = helper.get("auto_fullscreen", false) as? Boolean ?: false
            val dataDir = helper.getConfigPath().parent ?: Path.of(".")
            Settings(rememberScreen = remember, alwaysOnTop = onTop, autoFullscreen = auto) to dataDir
        }.getOrDefault(fallback)
    }

    private data class Settings(
        val rememberScreen: Boolean,
        val alwaysOnTop: Boolean,
        val autoFullscreen: Boolean,
    )
}
