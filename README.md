# Salt_Player_Subscreen

Salt Player for Windows 工坊插件：把播放界面（封面 + 歌词）投送到指定显示器，适配 640x340 / 320x170 等低分辨率小屏。

- 插件 ID：`com.senkj.spwsidescreen`
- 应用内显示名称：副屏播放界面
- 作者：SenkjM
- 依赖：SPW Workshop API（`com.github.Moriafly:spw-workshop-api`），插件运行在宿主 JVM 内，独立顶层窗口不随宿主进入托盘而隐藏。

## 交互

1. 启动后出现一个可拖动的控制小窗。
2. 把小窗拖到目标显示器，点“全屏”（或双击小窗）。
3. 全屏播放界面右上角在鼠标悬停时显示“退出全屏”按钮，按 ESC 也可退出。
4. 记住上次全屏使用的显示器，下次启动自动恢复（主屏不会自动恢复，避免遮挡主屏后难以操作）。

## 配置（工坊 → 模组管理）

- `记住全屏屏幕`：退出后记住本次全屏使用的显示器并在下次启动恢复。
- `启动时自动全屏`：启动后自动在副屏全屏显示，无需再点控制小窗的“全屏”；找不到副屏时仍显示控制小窗。
- `全屏时窗口置顶`：全屏播放时保持在其他窗口之上。
- `封面区域显示播放控件`：在封面左下方叠加播放/暂停、上一首、下一首与进度条，默认开启。

## 播放控件

- 控件只画在左侧封面区域内：底部有一条由透明到深色的渐变遮罩，上面是“上一首 / 播放暂停 / 下一首”和一条可点击跳转的进度条。
- 全程用 `Graphics2D` 绘制图标（不依赖字体图标），低分辨率下也不会出现缺字方框。
- 播放暂停按钮的状态跟随宿主播放状态；点击进度条按 `duration` 计算目标位置并 `seekTo`。
- 鼠标悬停时控件圆底会变亮，离开后恢复。

## 歌词

- 当前歌词行按可用宽度自动折行（最多三行；超长时第三行以省略号截断），避免为了塞进一行把字号压得过小；当前块第一行中心位于高度 1/4 处。
- 下方最多 3 行非当前歌词，非当前行使用 AMLL 公式的高斯模糊：`sigma = 1.1 × 距离 + 0.2 × 距离²`（上限 12，并按非当前行字号比例收敛），并按距离递增透明度衰减，越远越糊越小。
- 模糊图按设备像素（HiDPI 1.5x/2x）渲染后再 1:1 贴回，避免高分屏上“低分辨率模糊图被二次放大”造成的过度模糊。
- 低分辨率（如 320x170）使用紧凑布局：字号/行距随高度收缩，上句在空间不足时自动省略。
- 歌词来自宿主歌词链路（官方歌词、spw-lyrics、AMLL-into-SPW 等对宿主歌词的注入都会生效），逐字高亮按 `LyricsLine.lyricsCells` 的时间轴计算；`pureSubText` 翻译显示在当前块下方。

## 封面取色

取色思路对齐 [AMLL-into-SPW](https://github.com/GaBoron/AMLL-into-SPW)（独立实现，未复制其代码）：

- 封面约 96×96 步进采样，转 Oklab 感知空间；`lightness 0.12–0.95` 才参与统计。
- 彩色像素（`chroma ≥ 0.035`）按 24 个色相族投票，chroma 越大权重越高；取票数最高的族做加权平均。
- 近中性封面（`chroma < 0.025`）回退为白色歌词 + 品牌紫高亮，避免灰白封面把颜色拉灰。
- 产出三组颜色：背景 4 色 palette（固定采样点压暗）、歌词前景色（提亮降饱和）、发光/进度色（更饱和）。

## 构建

本仓库只包含插件源码，构建时由 `spw-workshop-api` 仓库统一驱动（需要它提供 API 模块与 `com.xuncorp.spw.workshop` Gradle 插件）：

1. 克隆 <https://github.com/Moriafly/spw-workshop-api>（下文称 `API_referer`）。
2. 在 `API_referer/settings.gradle.kts` 里挂载本目录：

   ```kotlin
   include(":Salt_Player_Subscreen")
   project(":Salt_Player_Subscreen").projectDir = file("../Salt_Player_Subscreen")
   ```

3. 在 `API_referer` 根目录执行（需要 JDK 25）：

   ```bash
   ./gradlew :Salt_Player_Subscreen:plugin
   ```

4. 产物：`build/libs/plugin-com.senkj.spwsidescreen-<version>.spmod`。

安装：把 `.spmod` 复制到 `%APPDATA%\Salt Player for Windows\workshop\plugins\`，在工坊中启用并授予 `library-read` 权限，重启宿主。
