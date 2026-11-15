package com.senkj.spwsidescreen

import com.xuncorp.spw.workshop.api.WorkshopApi
import java.awt.Color
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants

/**
 * 插件自建的独立顶层窗口（不设置 owner，宿主最小化到托盘时不会被连带隐藏）。
 *
 * 两种形态：
 *  - 控制小窗：可拖到任意显示器，点“全屏”后在该显示器上铺满；
 *  - 全屏：显示封面 + 歌词，右上角鼠标悬停时显示“退出全屏”，ESC 也可退出。
 *
 * 全屏所用的显示器会被记录，下次启动自动恢复（主屏除外，避免误遮挡主屏后无法操作）。
 */
class SubscreenWindow(
    pluginDataDir: Path,
    private val rememberScreen: Boolean,
    private val alwaysOnTop: Boolean,
    private val autoFullscreen: Boolean,
    private val showControls: Boolean,
) : JFrame("SPW 副屏播放界面") {

    private enum class Mode { CONTROL, FULLSCREEN }

    private val store = StateStore(pluginDataDir.resolve("window_state.json"))
    private var state = store.load()
    private var mode = Mode.CONTROL
    private var targetDevice: GraphicsDevice? = null

    private val playerPanel = NowPlayingPanel()
    private val controlPanel = ControlPanel { enterFullscreen() }

    private val repaintTicker = Timer(33) {
        playerPanel.repaint()
        controlPanel.repaint()
    }
    private val topologyTicker = Timer(1000) { syncTopology() }

    init {
        isUndecorated = true
        isResizable = false
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        type = Window.Type.UTILITY
        isAutoRequestFocus = false
        // 小窗/全屏都只画圆角内容，圆角外露出的 rootPane 背景保持深色，避免白边。
        background = Color(15, 16, 21)
        rootPane.background = Color(15, 16, 21)

        playerPanel.onExitClick = { exitFullscreen() }
        playerPanel.controlsEnabled = showControls
        playerPanel.onPrevious = { runPlayback { WorkshopApi.playback.previous() } }
        playerPanel.onNext = { runPlayback { WorkshopApi.playback.next() } }
        playerPanel.onPlayPause = {
            runPlayback {
                if (NowPlayingState.playing) WorkshopApi.playback.pause() else WorkshopApi.playback.play()
            }
        }
        playerPanel.onSeek = { fraction ->
            runPlayback {
                val duration = NowPlayingState.media?.duration ?: 0L
                if (duration > 0L) {
                    WorkshopApi.playback.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
                }
            }
        }
        rootPane.registerKeyboardAction(
            { exitFullscreen() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW,
        )

        val auto = if (autoFullscreen) autoFullscreenDevice() else null
        val restore = if (auto == null) restoreDevice() else null
        when {
            auto != null -> enterFullscreen(auto, remember = false)
            restore != null -> enterFullscreen(restore, remember = false)
            else -> showControl()
        }
    }

    private inline fun runPlayback(block: () -> Unit) {
        runCatching(block)
    }

    fun open() {
        SwingUtilities.invokeLater {
            isVisible = true
            repaintTicker.start()
            topologyTicker.start()
        }
    }

    override fun dispose() {
        repaintTicker.stop()
        topologyTicker.stop()
        super.dispose()
    }

    // ------------------------------------------------------------ 形态切换

    fun exitFullscreen() {
        if (mode != Mode.FULLSCREEN) return
        showControl()
        saveState(fullscreen = false)
    }

    private fun showControl() {
        mode = Mode.CONTROL
        targetDevice = null
        playerPanel.showExitButton = false
        // 小窗保持置顶，便于随时拖到目标屏幕并点“全屏”。
        setAlwaysOnTop(true)
        contentPane = controlPanel
        setBounds(resolveControlBounds())
        controlPanel.revalidate()
    }

    private fun enterFullscreen(device: GraphicsDevice? = null, remember: Boolean = true) {
        val target = device ?: deviceAtWindow() ?: primaryDevice() ?: return
        if (remember && mode == Mode.CONTROL) {
            state = state.copy(controlX = x, controlY = y)
        }
        targetDevice = target
        mode = Mode.FULLSCREEN
        playerPanel.showExitButton = true
        contentPane = playerPanel
        setAlwaysOnTop(alwaysOnTop)
        setBounds(target.defaultConfiguration.bounds)
        if (remember) saveState(fullscreen = true)
    }

    // ------------------------------------------------------------ 显示器

    private fun devices(): List<GraphicsDevice> = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.toList()
    }.getOrDefault(emptyList())

    private fun primaryDevice(): GraphicsDevice? = devices().firstOrNull()

    private fun deviceId(device: GraphicsDevice?): String = runCatching {
        device?.getIDstring() ?: ""
    }.getOrDefault("")

    private fun deviceAtWindow(): GraphicsDevice? {
        val windowBounds = bounds
        var best: GraphicsDevice? = null
        var bestArea = 0L
        for (device in devices()) {
            val inter = device.defaultConfiguration.bounds.intersection(windowBounds)
            val area = if (inter.isEmpty) 0L else inter.width.toLong() * inter.height
            if (area > bestArea) {
                bestArea = area
                best = device
            }
        }
        return best
    }

    /**
     * 恢复上次全屏的显示器：只按“位置+尺寸”或“分辨率”匹配，
     * 不按 AWT 的 ID 字符串匹配（AWT 的 \DisplayN 编号会随主屏/拓扑变化而漂移）。
     * 匹配不到或目标为主屏时不自动全屏，回到控制小窗。
     */
    private fun restoreDevice(): GraphicsDevice? {
        if (!rememberScreen || !state.fullscreen) return null
        val saved = Rectangle(state.displayX, state.displayY, state.displayW, state.displayH)
        if (saved.width <= 0 || saved.height <= 0) return null
        val target = devices().firstOrNull { it.defaultConfiguration.bounds == saved }
            ?: devices().firstOrNull {
                val b = it.defaultConfiguration.bounds
                b.width == saved.width && b.height == saved.height
            }
            ?: return null
        if (deviceId(target) == deviceId(primaryDevice())) return null
        return target
    }

    /**
     * 启动时自动全屏的目标屏：优先上次记录的副屏，其次第一个非主屏。
     * 找不到副屏时返回 null（改用控制小窗，避免自动占满主屏）。
     */
    private fun autoFullscreenDevice(): GraphicsDevice? {
        val saved = Rectangle(state.displayX, state.displayY, state.displayW, state.displayH)
        val secondary = devices().filter { deviceId(it) != deviceId(primaryDevice()) }
        if (secondary.isEmpty()) return null
        secondary.firstOrNull { it.defaultConfiguration.bounds == saved }?.let { return it }
        if (saved.width > 0 && saved.height > 0) {
            secondary.firstOrNull {
                val b = it.defaultConfiguration.bounds
                b.width == saved.width && b.height == saved.height
            }?.let { return it }
        }
        return secondary.first()
    }

    private fun resolveControlBounds(): Rectangle {
        val width = 356
        val height = 104
        if (state.controlX != Int.MIN_VALUE && state.controlY != Int.MIN_VALUE) {
            val saved = Rectangle(state.controlX, state.controlY, width, height)
            if (devices().any { it.defaultConfiguration.bounds.intersects(saved) }) return saved
        }
        val screen = primaryDevice()?.defaultConfiguration?.bounds ?: Rectangle(0, 0, 1280, 720)
        return Rectangle(
            screen.x + (screen.width - width) / 2,
            screen.y + (screen.height - height) / 2,
            width,
            height,
        )
    }

    private fun syncTopology() {
        if (mode == Mode.FULLSCREEN) {
            val device = targetDevice ?: return
            val stillThere = devices().any { deviceId(it) == deviceId(device) }
            if (!stillThere) {
                exitFullscreen()
                return
            }
            val boundsNow = device.defaultConfiguration.bounds
            if (bounds != boundsNow) setBounds(boundsNow)
        } else {
            val windowBounds = bounds
            val visible = devices().any { it.defaultConfiguration.bounds.intersects(windowBounds) }
            if (!visible) setBounds(resolveControlBounds())
        }
    }

    // ------------------------------------------------------------ 状态保存

    private fun saveState(fullscreen: Boolean) {
        val device = targetDevice
        val displayBounds = device?.defaultConfiguration?.bounds
        state = state.copy(
            displayId = deviceId(device).ifEmpty { state.displayId },
            displayX = displayBounds?.x ?: state.displayX,
            displayY = displayBounds?.y ?: state.displayY,
            displayW = displayBounds?.width ?: state.displayW,
            displayH = displayBounds?.height ?: state.displayH,
            fullscreen = fullscreen,
            controlX = if (fullscreen) state.controlX else x,
            controlY = if (fullscreen) state.controlY else y,
        )
        store.save(state)
    }
}
