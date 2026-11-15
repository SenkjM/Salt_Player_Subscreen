package com.senkj.spwsidescreen

import java.nio.file.Files
import java.nio.file.Path

/**
 * 记录窗口状态：上次全屏使用的显示器、控制小窗的位置。
 *
 * 显示器的身份同时记录 AWT 的 ID 字符串与当时的 bounds，恢复时先按 ID 匹配，
 * 其次按 bounds 匹配，最后按分辨率匹配，尽量适应显示器重排/重连。
 */
class StateStore(private val path: Path) {

    data class State(
        val displayId: String = "",
        val displayX: Int = 0,
        val displayY: Int = 0,
        val displayW: Int = 0,
        val displayH: Int = 0,
        val fullscreen: Boolean = false,
        val controlX: Int = Int.MIN_VALUE,
        val controlY: Int = Int.MIN_VALUE,
    )

    fun load(): State = runCatching {
        if (!Files.exists(path)) return State()
        parse(Files.readString(path))
    }.getOrDefault(State())

    fun save(state: State) {
        runCatching {
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, format(state))
        }
    }

    private fun parse(text: String): State {
        fun str(key: String): String =
            Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: ""

        fun num(key: String): Int =
            Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        fun bool(key: String): Boolean =
            Regex("\"$key\"\\s*:\\s*(true|false)").find(text)?.groupValues?.get(1) == "true"

        return State(
            displayId = str("displayId").replace("\\\\", "\\"),
            displayX = num("displayX"),
            displayY = num("displayY"),
            displayW = num("displayW"),
            displayH = num("displayH"),
            fullscreen = bool("fullscreen"),
            controlX = num("controlX").takeIf { Regex("\"controlX\"\\s*:\\s*-?\\d+").containsMatchIn(text) } ?: Int.MIN_VALUE,
            controlY = num("controlY").takeIf { Regex("\"controlY\"\\s*:\\s*-?\\d+").containsMatchIn(text) } ?: Int.MIN_VALUE,
        )
    }

    private fun format(state: State): String {
        val id = state.displayId.replace("\\", "\\\\").replace("\"", "\\\"")
        return buildString {
            append("{\n")
            append("  \"displayId\": \"").append(id).append("\",\n")
            append("  \"displayX\": ").append(state.displayX).append(",\n")
            append("  \"displayY\": ").append(state.displayY).append(",\n")
            append("  \"displayW\": ").append(state.displayW).append(",\n")
            append("  \"displayH\": ").append(state.displayH).append(",\n")
            append("  \"fullscreen\": ").append(state.fullscreen).append(",\n")
            append("  \"controlX\": ").append(state.controlX).append(",\n")
            append("  \"controlY\": ").append(state.controlY).append("\n")
            append("}\n")
        }
    }
}
