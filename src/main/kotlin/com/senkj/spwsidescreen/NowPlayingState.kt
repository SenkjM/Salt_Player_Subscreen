package com.senkj.spwsidescreen

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * 插件内共享的“正在播放”状态。
 *
 * 宿主进入托盘后主窗不再渲染，但本插件窗口独立于宿主窗口，由自己的 Swing Timer 持续重绘；
 * 这里的数据源只依赖 Workshop API 的播放回调与音乐库查询，不依赖宿主窗口是否可见。
 */
object NowPlayingState {
    @Volatile
    var media: PlaybackExtensionPoint.MediaItem? = null
        private set

    @Volatile
    var lines: List<PlaybackExtensionPoint.LyricsLine> = emptyList()
        private set

    @Volatile
    var playing: Boolean = false
        private set

    @Volatile
    var state: PlaybackExtensionPoint.State = PlaybackExtensionPoint.State.Idle
        private set

    @Volatile
    var cover: BufferedImage? = null
        private set

    @Volatile
    var colors: ColorAccents.Colors = ColorAccents.fallback()

    @Volatile
    private var basePosition: Long = 0L

    @Volatile
    private var baseNanos: Long = 0L

    @Volatile
    private var lastPosition: Long = -1L

    @Volatile
    private var trackKey: String = ""

    @Volatile
    private var linesKey: String = ""

    @Volatile
    private var linesAttempts: Int = 0

    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "sidescreen-worker").apply { isDaemon = true }
    }
    private var started = false

    fun start() {
        if (started) return
        started = true
        worker.scheduleWithFixedDelay({ runCatching { pollTrack() } }, 0, 1, TimeUnit.SECONDS)
    }

    fun stop() {
        if (!started) return
        started = false
        worker.shutdownNow()
    }

    private fun keyOf(item: PlaybackExtensionPoint.MediaItem): String = "${item.id}|${item.path}"

    /** 只读轮询：宿主隐藏到托盘时回调可能停止，轮询保证换曲与歌词仍能跟上。 */
    private fun pollTrack() {
        if (!started) return
        val current = runCatching { WorkshopApi.playback.getCurrentMediaItem() }.getOrNull()
        val key = current?.let(::keyOf) ?: ""
        if (key == trackKey) {
            if (current != null && lines.isEmpty() && linesKey != key && linesAttempts < 6) {
                linesAttempts++
                loadLines(key)
            }
            return
        }
        applyMedia(current)
    }

    fun onMedia(item: PlaybackExtensionPoint.MediaItem?) = applyMedia(item)

    private fun applyMedia(item: PlaybackExtensionPoint.MediaItem?) {
        val newKey = item?.let(::keyOf) ?: ""
        if (newKey == trackKey && item != null) return
        trackKey = newKey
        media = item
        lines = emptyList()
        linesKey = ""
        linesAttempts = 0
        cover = null
        basePosition = 0
        baseNanos = System.nanoTime()
        lastPosition = -1L
        if (item == null) {
            playing = false
            return
        }
        loadCover(item.id)
        loadLines(newKey)
    }

    private fun loadCover(trackId: String) {
        if (trackId.isEmpty()) return
        val stage = runCatching { WorkshopApi.library.getCoverById(trackId) }.getOrNull() ?: return
        stage.whenComplete { bytes, error ->
            if (error != null || bytes == null) return@whenComplete
            runCatching {
                val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return@runCatching
                cover = image
                colors = ColorAccents.extract(image)
            }
        }
    }

    private fun loadLines(key: String) {
        val list = runCatching { WorkshopApi.playback.getLyricsLines() }.getOrDefault(emptyList())
        if (key != trackKey) return
        if (list.isNotEmpty()) {
            lines = list
            linesKey = key
            linesAttempts = 0
        } else if (lines.isEmpty()) {
            linesKey = key
        }
    }

    /** 用最近一次位置回调 + 单调时钟外推当前播放位置。 */
    fun currentPositionMs(): Long {
        val base = basePosition
        if (!playing) return base
        val delta = (System.nanoTime() - baseNanos) / 1_000_000L
        return base + delta.coerceAtLeast(0)
    }

    fun onPosition(position: Long) {
        val previous = lastPosition
        if (previous >= 0 && position > previous + 80 && !playing) playing = true
        basePosition = position
        baseNanos = System.nanoTime()
        lastPosition = position
    }

    fun onSeek(position: Long) = onPosition(position)

    fun onPlaying(isPlaying: Boolean) {
        if (!isPlaying && playing) basePosition = currentPositionMs()
        if (isPlaying && !playing) baseNanos = System.nanoTime()
        playing = isPlaying
    }

    fun onState(value: PlaybackExtensionPoint.State) {
        state = value
        if (value == PlaybackExtensionPoint.State.Idle) playing = false
    }

    fun onLyricsLines(list: List<PlaybackExtensionPoint.LyricsLine>) {
        lines = list
        if (list.isNotEmpty()) {
            linesKey = trackKey
            linesAttempts = 0
        }
    }
}
