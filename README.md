# Samsung Music (RC)

> 一个仿 Samsung Music 界面风格的**本地音乐播放器**。纯本地曲库，不联网、不充值、无会员，
> 你的 FLAC / WAV / DSD 转码文件就该跑在一条干净的输出链路上。
>
> Compose + Media3 + Room，外加一层 **Oboe 原生输出**（AAudio / USB DAC 独占）与**软件 EQ**；
> 并且完整适配了 **vivo 车机 / JoviInCar 原子随身听**的歌词投屏，以及 OriginOS **原子通知歌词**。

- 最低系统：Android 8.0（API 26）｜目标：Android 15（API 35）｜ABI：arm64-v8a
- 语言：简体中文 / English 跟随系统
- 包名：`com.spotify.music`（车机与部分系统音乐入口按包名识别，故沿用；与官方 Samsung Music 无关）
- 许可证：**Apache-2.0**（全文见 [LICENSE](LICENSE)，第三方组件声明见 [NOTICE](NOTICE)）

---

## 为什么做这个

手机自带的音乐播放器要么在推流媒体会员，要么把本地文件当成二等公民：封面糊、歌词不准、
输出被系统重采样、车机上只显示一行歌词。这个项目想要的东西很朴素——

**一个能安静放本地歌、音质链路可控、上车就能显示歌词的播放器。**

界面致敬 Samsung Music（One UI 那套紫色强调色、底部迷你条展开成整页、黑胶唱片动效），
实现全部重写，功能按自己的需求往前多做了一步。

---

## 特性一览

### 曲库

- 收藏 / 播放列表 / 歌曲 / 专辑 / 歌手 / 文件夹 六大标签，标签顺序与显隐可自定义
- 支持 MP3 / FLAC / M4A / AAC / WAV / OGG / Opus / WMA / AMR / AIFF / APE
- 自定义扫描目录、手动"立即扫描"、回前台自动增量检测，无需系统媒体库授权
- 隐藏文件夹、按名称/艺术家/专辑/时长/添加时间排序、右侧 A–Z 索引气泡快速跳转
- 中英文混排按拼音排序，搜索覆盖歌曲 / 专辑 / 艺术家 / 文件夹

### 播放

- Media3 ExoPlayer + 前台服务，锁屏与通知栏播控，耳机线控
- 底部迷你播放条 ↔ 全屏播放页连续形变（封面收成黑胶唱片、播控与歌词一起位移）
- 播放队列：随机、单曲/列表循环、**淡入淡出（Crossfade）**、**跳过静音段**、**倍速播放**、睡眠定时器
- 队列规则可配：是否允许重复、是否仅播放当前列表
- 与其他应用同时播放、息屏继续播放、允许外部设备（车机/蓝牙）发起播放
- 长标题自动跑马灯，冷启动恢复上次队列与进度（保持暂停，不抢焦点）
- 加入队列 / 下一首播放、收藏、新建与编辑歌单、分享、设为铃声、删除文件

### 音质与输出

- **三种输出后端**：系统 AudioTrack / Oboe AAudio / Oboe OpenSL ES，可切换对比
- **USB DAC 独占模式**（AAudio）：支持采样率、位深、声道数直出，插拔自动路由与热切换
- 输出设备可选：扬声器 / 有线耳机 / 蓝牙 / USB 音频设备 / 听筒
- 实时显示当前真实输出参数（设备、模式、采样率、位深、声道），不玩"看起来开了"
- 采样率支持"跟随音源"或固定；位深支持 16 / 24 / 32 / Float
- **软件 EQ 全链路**：10 段双二阶（RBJ Peaking）+ 前置增益 + 低音搁架 + 高音 + 环绕增强 + 总增益，
  配软限幅防削波；不依赖系统音效，换手机、接 DAC 表现一致
- 预设：平直 / 流行 / 摇滚 / 爵士 / 古典 / 电子 / 人声

### 歌词

- 同名 `.lrc` / `.txt` 侧挂文件优先，其次读内嵌标签：MP3 ID3v2 `USLT`、FLAC Vorbis Comment
  `LYRICS`/`UNSYNCEDLYRICS`、M4A `©lyr`
- LRC 解析支持 `[mm:ss]` / `[mm:ss.xx]` / `[mm:ss.xxx]`、一行多时间戳、`[h:mm:ss]` 变体、
  元信息标签与 `offset`、增强型逐字时间戳自动剥离
- 编码嗅探：UTF-8 / UTF-16LE / UTF-16BE / GBK 都能认，带 BOM 自动跳过
- 有时间戳逐行跟随进度滚动，无时间戳静态显示；播放页与歌词页均可用

### 车机 / 系统适配

- **vivo 车机 / JoviInCar 原子随身听**深度适配（详见下一节）
- 横屏与方屏自适应：按长宽比自动切换车机双栏布局，1024×608、880×860 一类近方屏单独调过
- 高刷新率请求、全页面滑动返回（边缘手势 + 系统预测返回）
- 内置崩溃与诊断日志，便于定位"某台机器不响"这类问题

---

## 截图

### 手机端

<table>
<tr>
<td align="center"><img src="docs/screenshots/phone-01-library.jpg" width="165"><br>曲库列表</td>
<td align="center"><img src="docs/screenshots/phone-02-miniplayer.jpg" width="165"><br>迷你播放条</td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/phone-03-player.jpg" width="165"><br>全屏播放页</td>
<td align="center"><img src="docs/screenshots/phone-04-equalizer.jpg" width="165"><br>均衡器</td>
</tr>
</table>

左侧 A–Z 索引、顶部标签栏可自定义显隐；底部圆形封面即迷你播放条，点按展开为整页播放器。
均衡器页为 10 段 + 前置增益 + 低音/高音/环绕增强，下方为预设列表。

### 车载投屏（vivo 车机 / JoviInCar）

<table>
<tr><td align="center"><img src="docs/screenshots/car-01-library.jpg" width="330"><br>车机曲库（横屏双栏）</td></tr>
<tr><td align="center"><img src="docs/screenshots/car-02-player.jpg" width="330"><br>车机播放页</td></tr>
<tr><td align="center"><img src="docs/screenshots/car-03-lyrics.jpg" width="330"><br>车机歌词页</td></tr>
<tr><td align="center"><img src="docs/screenshots/car-04-vivo-desktop-widget.jpg" width="330"><br>JoviInCar 桌面滚动歌词</td></tr>
</table>

横屏车机自动切换为双栏布局（左信息 / 右歌词），底部保留 Dock 播控条。
最后一张是投屏到车机后 vivo 桌面卡片上的滚动歌词——这条能力按包名白名单下发，
详见下方《关于 vivo 原子通知 / JoviInCar 的适配》。

最右那张是 **vivo 手机桌面（OriginOS 系统界面）** 上的"小V建议 · 音乐原子组件"卡片，不是车机界面：
车机版通过 JoviInCar 协议把播放状态注册到系统，OriginOS 便在手机桌面上渲染这张卡片 —— 封面、曲名、
作词作曲与滚动歌词一并呈现，进度条跟着实际播放走。

---

## 关于 vivo 原子通知 / JoviInCar 的适配

这是本项目最"不体面"但最有用的一部分——车机生态没有公开文档，只能靠抓包和试错对齐。
如果你也在做安卓音乐 App 上 vivo 车机，这段可以省你几天。

### 1. 暴露给车机的服务

车机侧（原子随身听 / JoviInCar）不是"读通知栏"，而是通过 `MediaBrowserService` 连接播放器，
所以要显式声明它能识别的 action：

```xml
<service android:name=".playback.PlaybackService" android:exported="true">
    <intent-filter>
        <action android:name="android.media.browse.MediaBrowserService" />
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>
    <!-- vivo 车机 / JoviInCar 原子随身听入口 -->
    <intent-filter>
        <action android:name="com.vivo.musicwidgetmix.support.service" />
    </intent-filter>
</service>
```

会话用 `MediaLibrarySession`（不是普通 `MediaSession`）构建，并通过 `setSessionActivity()` 挂一个
指向播放页的 `PendingIntent`——车机卡片点击跳转、以及手机侧 `MediaSessionManager.getActiveSessions()`
发现本应用，都依赖这一项；服务同时声明 `foregroundServiceType="mediaPlayback"` 保证息屏后不被杀。

### 2. 播放列表必须"可播放"

给车机浏览树返回的每个 `MediaItem` 都要显式 `setIsPlayable(true)`，
并且 `onGetChildren` 的根节点要返回真实歌曲。vivo 侧拿到 `isPlayable=false` 的条目
会直接跳过或崩溃——列表里"只有专辑没有歌"通常就是这个原因。

### 3. 歌词走两条通道，同时喂

车机型号和系统版本不一，歌词通道有两套，本项目**同时发送**，谁支持谁生效：

| 通道 | 说明 |
| :--- | :--- |
| `ucar` | 车机媒体框架。一次给**整段 LRC**，由车机按进度自己滚动 |
| `vivomusicmix` | 原子随身听。逐行推送当前歌词行 |

对应字段名：

```text
ucar.media.metadata.LYRICS_WHOLE     整段 LRC（写入 MediaMetadata.extras）
ucar.media.metadata.LYRICS_STATUS    固定 0（有歌词）
vivomusicmix.extra.lrc_change        歌词变更事件（setSessionExtras）
```

踩过的坑，都写进了注释：

- `LYRICS_STATUS` 只能给 `0`。**负值会被车机判定为"本曲无歌词"**，整首歌的歌词位直接消失。
- **绝对不要写 `ucar.media.metadata.LYRICS_LINE`**：一旦存在，车机改走"逐行模式"，
  而它拿不到持续更新的行，结果是卡片永远停在第一行或空白。
- `LYRICS_WHOLE` 必须是**规范 LRC**：一行一个时间戳、去掉增强型逐字标签 `<mm:ss.xx>`。
  多时间戳行和逐字标签会让车机解析器整段丢弃。
- 切歌时要**先发空歌词再发新歌词**，否则上一首的词会残留在新歌开头。
- 原子随身听有事件合并/丢弃逻辑，所以带 `lrc_change` 的 extras 要**每 25 秒重发一次**兜底，
  否则停车怠速、界面重绘之后歌词就不再更新了。
- vivo 的扩展字段存在官方拼写错误（`meida` / `meidia`），**照抄，不要"顺手纠正"**，
  纠正之后字段就读不到了。

### 4. 让车机能"唤起"播放，但要防误触

车机点卡片播放按钮时，往往是在**没有 Activity** 的情况下通过 `MediaController` 下发
`COMMAND_PLAY_PAUSE`。本项目在 `onPlayerCommandRequest` 里区分"应用内"与"外部控制器"两种来源，
并做了两件事：

- **外部启动开关**：设置里的「允许外部设备开始播放」关闭时，外部控制器在**队列为空**的情况下
  无法启动播放（避免上车蓝牙一连、音乐自己响了）；队列已有内容时仍然放行。
- **暂停保护窗口**：刚在手机上手动暂停过，紧接着车机又发来一个 PLAY，会被判定为误触并丢弃
  （`RESULT_INFO_SKIPPED`），不会出现"我刚按停它又自己响了"。

另外，服务 `onCreate` 会 `restoreLastQueue()` 恢复上次的队列与进度（**恢复为暂停态，不抢音频焦点**），
这样车机连上时浏览树里始终有内容可显示，而不是一个空白会话。

### 5. 界面侧

车机分辨率千奇百怪，本项目按**长宽比**而非"是否车机"判定布局：比例 > 1.2 走双栏，
1024×608、880×860 这类近方屏单独收紧间距，避免标题压到 Dock。

> 以上适配默认开启，可在「设置 → 播放与音效 → 车载投屏歌词」关闭。
> 关闭后不再向车机推送歌词，其余播控不受影响。

### 6. 最后一关：包名

上面五件事全做对，桌面滚动歌词仍然可能不出——因为 vivo 侧还有一层**包名白名单**：
只有被列为"内容型播放应用"的包名才会获得 JoviInCar 桌面逐行歌词能力。
实测可用名单、以及本项目为何占用 `com.spotify.music`，见文末
[关于包名（以及一份实测白名单）](#关于包名以及一份实测白名单)。

---

## 构建

```bash
# JDK 17 + Android SDK（platform 35/36、build-tools、NDK 27.2.12479018、CMake 3.22.1）
gradle :app:assembleRelease --no-daemon --no-configuration-cache
# 产物：app/build/outputs/apk/release/app-release-unsigned.apk
```

仓库不含 `gradlew`，请用本地 Gradle 8.9（CI 同版本）。原生库 `libsamsung_oboe.so`（连同依赖的
`liboboe.so`）由 `app/src/main/cpp` 经 CMake 随构建自动编译，无需手工准备。

签名凭据**不入库**（仓库里没有任何口令字面量）。`app/build.gradle` 按以下顺序取值：
`local.properties` → 环境变量 → 都没有则产出 `app-release-unsigned.apk`。

```properties
# local.properties（已在 .gitignore 中）
key.store.file=7tattoo.jks        # 相对 app/ 的路径，或绝对路径
key.alias=你的别名
key.store.password=你的库口令
key.alias.password=你的键口令
```

同名环境变量（CI 用 Secrets 注入，无 `local.properties` 时生效）：
`KEYSTORE_FILE` / `KEY_ALIAS` / `KEYSTORE_PASSWORD` / `KEY_PASSWORD`。

自行签名：

```bash
zipalign -p -f 4 in.apk aligned.apk
apksigner sign --ks your.jks --ks-key-alias youralias \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true aligned.apk
```

CI：`.github/workflows/build.yml` 每次 push 构建 Release APK 并上传为构建产物；配了签名 Secrets
时产物直接是 7tattoo 签名包，没配则为 `app-release-unsigned.apk`。**本仓库两个 workflow 的 Secrets
已配置**，所以 CI 产物就是 7tattoo 签名包（v2/v3 方案；`minSdk 26` 不需要 v1，因此包内没有
`META-INF/*.SF`，这不是漏签）。

### 换包名出多个包（vivo 白名单）

`applicationId` 可由构建参数覆盖，`namespace`（代码包路径、R 类）不受影响，
`FileProvider` 的 authority 用 `${applicationId}` 会自动跟着换：

```bash
gradle :app:assembleRelease -PoverrideAppId=cn.kuwo.kwmusiccar   # 或环境变量 OVERRIDE_APP_ID
```

本地一条命令出齐这 10 个包（产物名就是 `<包名>.apk`，脚本不含任何口令）：

```bash
bash scripts/build10.sh                        # 全部 10 个 → dist/
PKGS="com.spotify.music" bash scripts/build10.sh   # 只构建其中几个
```

`.github/workflows/build-multi.yml`（**Build 10-in-1 APKs**）做同一件事：在 Actions 里手动运行，
10 个 job 并行，产出全部 10 个包名的已签名 APK，代码完全相同、只有包名不同，每次运行打包上传为一个
release 资产 `SamsungMusicRC-<tag>-10in1.zip`（可选 `make_release` 顺带建/更新 Draft Release 并挂上）。
zip 里的文件名是工作流固定的 `com.<…>.apk` 格式（和 Release 附件命名一致），**不带版本号**——
版本写在 tag 和 APK 内部（`versionName` / `versionCode`），跟正文一致。
**已上线的 Release v1.0.0 的 10 个包是本地构建后上传的**（当时还没有这两个脚本/工作流），
下一条版本可以直接用它们出包。

## 下载

**Release 页**：<https://github.com/7tattoo/SamsungMusicRC/releases/tag/v1.0.0>

附件直接按**包名**命名（`com.spotify.music.apk`、`com.apple.android.music.apk`……共 10 个）——
10 个包代码完全相同，只有包名不同，看文件名就知道它是哪个包名，想要哪个装哪个。
版本号不在文件名里（10 个包都来自同一 commit，版本写在 tag 和 APK 内部：
`versionName 1.0.0` / `versionCode 1`）。装进系统后显示的应用名统一是 `Samsung Music`。

Release 正文里有一张「文件名 → 包名 → 对应的正版应用」对照表，以及包名冲突、签名不一致的排查说明。

手机与车机都只装了 arm64-v8a，直接装对应包名的那个即可；同包名的包之间不能共存（会互相覆盖），
与正版同名 App 也**不能共存**——要先卸载原版才能装。

## 技术栈

| 组件 | 用途 |
| :--- | :--- |
| Kotlin 2.0 + Jetpack Compose (Material 3) | 全部界面 |
| AndroidX Media3 (ExoPlayer) 1.11 | 播放内核、通知与锁屏、车机会话 |
| Room 2.6 (SQLite) | 曲库、歌单、收藏、播放历史 |
| [Oboe](https://github.com/google/oboe) 1.9 (C++/JNI) | AAudio / OpenSL ES 输出与 USB DAC 独占 |
| 自研 `CoverLoader`（MediaMetadataRetriever + LruCache） | 封面提取、按需降采样与内存缓存 |
| `java.text.Collator` + 内置拼音首字表 | 曲名 A–Z 索引与中文排序 |

纯 Kotlin + 一层 C++，无 Retrofit/无埋点/无广告；清单里**没有 `INTERNET` 权限**，
抓包可以看到它一个字节都不往外发。

## 关于包名（以及一份实测白名单）

包名为 `com.spotify.music`。原因不体面但很实际：**vivo 的车机 / 桌面歌词能力按包名白名单下发**，
不在名单里的应用，代码写得再对也拿不到桌面滚动歌词。沿用名单内的包名是目前唯一可行的路子
（与官方 Spotify / Samsung Music 均无关系，本 App 完全本地播放、不联网）。

### 实测：支持 JoviInCar 桌面滚动歌词（且不套用车载音乐皮肤）的包名

以下包名在实机上验证过：投屏到车机后能在 JoviInCar 桌面看到**逐行滚动歌词**，
同时系统**不会**给它套"车载音乐皮肤"（即界面仍按应用自己的布局渲染，不会被强制替换成车机版式）。

- `com.tencent.wecarflow` — 腾讯爱趣听
- `com.tencent.ibg.joox` — Joox Music
- `com.spotify.music` — Spotify / 声破天（**本项目采用**；另支持**原子通知歌词**）
- `com.apple.android.music` — Apple Music（另支持**原子通知歌词**）
- `com.luna.music.car` — 汽水音乐车机版
- `com.kugou.android.auto` — 酷狗音乐车载版
- `cn.kuwo.kwmusiccar` — 酷我音乐车机版
- `com.qidian.QDReader` — 起点读书
- `com.tencent.weread` — 微信读书
- `cn.aqzscn.stream_music` — 音流

两点值得留意：

- 名单里有**起点读书、微信读书、音流**三个非音乐应用 —— 说明 vivo 判定的不是"是不是音乐 App"，
  而是"是不是需要长文本随播放进度滚动"的内容型应用，歌词滚动复用的正是这条通道。
- 想换包名与其他音乐 App 共存：改 `app/build.gradle` 的 `applicationId` 即可，
  `namespace` 保持不变，代码、资源与 JNI 绑定都不用动（`FileProvider` 与 startup 的 authority 会自动跟随）。
  也可以不改文件，直接用构建参数出包：`gradle :app:assembleRelease -PoverrideAppId=<上表任一包名>`；
  Actions 里的 **Build 10-in-1 APKs** 就是把上表 10 个包名一次全出（见「下载」）。
  但**换成名单外的包名大概率失去桌面歌词**——除非换成上表里的另一个包名。

### 原子随身听（`com.spotify.music` / `com.apple.android.music`）

上表里 Spotify 与 Apple Music 两个包名，除桌面滚动歌词外，还被收录进 **vivo 原子随身听**
（系统级音乐聚合入口：桌面/锁屏/车机上的原子组件可直接接管播放）。本项目占用 `com.spotify.music`，
因此同样出现在原子随身听里 —— 这也是本项目能在车机上被"原子随身听"唤起的原因。

> 注：此条来自用户实机观察与包名白名单推断，**尚未做过 A/B 验证**（例如把包名换成
> `com.luna.music.car` 对比原子随身听是否消失）。有实测结论的同学欢迎开 Issue 补充。

### 原子通知歌词（仅 `com.spotify.music` / `com.apple.android.music`）

上表 10 个包名都能拿到 **JoviInCar 桌面滚动歌词**，但实测只有 **Spotify 与 Apple Music** 两个
还额外支持 OriginOS 的**原子通知歌词**——播放时在原子通知里逐行滚动当前歌词，手机桌面/锁屏的
通知卡片同样跟随，无需连接车机即可看到歌词。

本项目取的正是 `com.spotify.music`，所以**两条通道都命中**：连着车机投 JoviInCar 桌面歌词，
不连车机时手机上的原子通知也照常滚歌词。

> 原子通知歌词**不需要额外写代码**：它是系统按包名给的能力，App 只要正常发媒体通知
> （`MediaBrowserService` + 播放元数据），系统就把歌词渲染进原子通知。本项目没为它加任何私有通道，
> 机制层面尚未拆解，这里是实机观察结论。

实测小结（vivo 手机 + 车机投屏）：

- **JoviInCar 桌面滚动歌词**：上表全部 10 个包名都生效，需要连接车机。
- **原子通知歌词**：仅 `com.spotify.music`、`com.apple.android.music` 生效，**不需要**连车机。
- **不套用车载音乐皮肤**：上表 10 个包名都不被套皮肤。

这也是为什么不建议随意换包名：换成 `cn.kuwo.kwmusiccar`（酷我音乐车机版）之类仍能滚桌面歌词，
但**原子通知歌词会一起消失**。


## 来源与感谢

- 生产力 · **Minis** — https://github.com/OpenMinis
- UI · **Samsung** — https://www.samsung.com.cn/
- 音效 · **Halcyon** — https://github.com/Kifranei/Halcyon
- 作者 · **鬼蓝** — https://b23.tv/jqYmSVw

- **Minis**：全部代码编写、调试与打包在 Minis 中完成。
- **Samsung**：界面与交互（标签栏、迷你播放条 ↔ 黑胶唱片形变、均衡器版式）参考 Samsung Music。
- **Halcyon**：原生 Oboe 输出后端（`app/src/main/cpp/oboe_sink.cpp`）与软件 EQ 的思路源自 Halcyon /
  RawS Music（Apache-2.0），本项目为独立 Kotlin/C++ 实现，版式亦对齐 Halcyon 的音效设置结构。

## 许可与声明

- 本项目为**非官方**爱好者作品，与三星电子无隶属或授权关系。"Samsung" / "Samsung Music" 为三星电子商标，
  本项目仅作界面设计参考。
- 源码以 **Apache License 2.0** 授权（全文见 `LICENSE`）：可自由使用、修改、商用与二次分发，
  条件是保留版权与许可副本、说明你做过改动；同时附带专利授权条款。
- 本许可证**只覆盖本仓库的代码**，不覆盖三星的商标与 One UI 视觉设计。二次分发时请自行去掉
  "Samsung / Samsung Music" 名称与应用图标，不要以官方产品名义传播。
- 包名 `com.spotify.music` 同样不在授权范围内：它会与正版 Spotify 冲突，二次分发请修改
  `applicationId`（见上文「关于包名」）。
- 作者的个人请求（不是法律条款）：这是业余时间做的播放器，请别拿它打包卖钱。
- 因使用本软件造成的数据丢失、设备异常或流量费用，作者不承担责任（Apache-2.0 的"按原样提供"条款亦已免责）。
- 你播放的音乐文件版权归各自权利人所有，请自行确保使用权。

---

如果它在你的车上、在你的 USB DAC 上正常出声了，欢迎开个 issue 说一声；
不能出声的机型更欢迎来报——这类"我这台不行"的问题最有价值。

