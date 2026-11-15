package com.senkj.spwsidescreen

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.pf4j.Extension

/** 把宿主播放回调转发给 [NowPlayingState]。 */
@Extension
class PlaybackBridge : PlaybackExtensionPoint {
    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        NowPlayingState.onState(state)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        NowPlayingState.onPlaying(isPlaying)
    }

    override fun onSeekTo(position: Long) {
        NowPlayingState.onSeek(position)
    }

    override fun onPositionUpdated(position: Long) {
        NowPlayingState.onPosition(position)
    }

    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        NowPlayingState.onMedia(mediaItem)
        return null
    }

    override fun onAfterLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        NowPlayingState.onMedia(mediaItem)
        return null
    }

    override fun onLyricsLinesUpdated(lyricsLines: List<PlaybackExtensionPoint.LyricsLine>) {
        NowPlayingState.onLyricsLines(lyricsLines)
    }
}
