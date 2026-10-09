#!/usr/bin/env python3
"""生成 v1.0.0 的 Release 正文（文件名一律 <包名>.apk）。"""
import hashlib, os, re, subprocess

DEL = "/var/minis-euleros/shared/SamsungMusicRC"
ROWS = [
    ("com.spotify.music",          "Spotify"),
    ("com.apple.android.music",    "Apple Music"),
    ("com.luna.music.car",         "网易云音乐·车载版"),
    ("com.tencent.wecarflow",      "腾讯 AUTO"),
    ("com.kugou.android.auto",     "酷狗音乐·车机版"),
    ("cn.kuwo.kwmusiccar",         "酷我音乐·车机版"),
    ("com.tencent.ibg.joox",       "JOOX"),
    ("com.tencent.weread",         "微信读书"),
    ("com.qidian.QDReader",        "起点读书"),
    ("cn.aqzscn.stream_music",     "音流"),
]
sha = {}
for p, _ in ROWS:
    f = os.path.join(DEL, p + ".apk")
    sha[p] = hashlib.sha256(open(f, "rb").read()).hexdigest()
# 版本号直接从 app/build.gradle 读，不依赖临时文件
gradle = open(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           "app", "build.gradle"), encoding="utf-8").read()
m = re.search(r'versionName\s+"([^"]+)"', gradle)
if not m:
    raise SystemExit("app/build.gradle 里找不到 versionName")
ver = m.group(1)
head = subprocess.run(["git", "-C", "/var/minis-euleros/workspace/SamsungMusicRC", "rev-parse", "--short", "HEAD"],
                      capture_output=True, text=True).stdout.strip()

L = []
L.append("# SamsungMusicRC v%s" % ver)
L.append("")
L.append("纯本地音乐播放器（本地曲库扫描 + Media3 播放 + AAudio 原生输出 + 10 段均衡器），")
L.append("横屏车机双栏布局，专为 **vivo 车机 / JoviInCar** 投屏做了适配。")
L.append("")
L.append("> **文件怎么命名**：10 个包 **代码完全相同，只有包名不同**，所以直接按包名命名 —— 想要哪个装哪个，")
L.append("> 看文件名就知道它是伪装成谁的。版本号看这里的 tag（v%s），不用从文件名里猜。" % ver)
L.append("> 安装后系统里显示的应用名统一是 `Samsung Music`。")
L.append("")
L.append("## 下载哪个")
L.append("")
L.append("| APK 文件 | 包名 | 这个包名对应的正版应用 |")
L.append("| --- | --- | --- |")
for p, o in ROWS:
    L.append("| `%s.apk` | `%s` | %s |" % (p, p, o))
L.append("")
L.append("**这 10 个包名都能在 JoviInCar 桌面出滚动歌词**（实机验证，`com.spotify.music` 在桌面显示为「本应用」）。")
L.append("额外能力：**原子通知里的逐行滚动歌词**，实机验证目前只有 `com.spotify.music` 与")
L.append("`com.apple.android.music` 会被系统下发 —— 由 vivo 系统按包名决定，本仓库没有实现任何 vivo 私有通知通道。")
L.append("详见仓库 README 的《关于 vivo 原子通知 / JoviInCar 的适配》一节。")
L.append("")
L.append("## 安装注意")
L.append("")
L.append("* **包名即身份**：装 `com.spotify.music` / `com.apple.android.music` 之前请先卸载同名正版应用，")
L.append("  否则签名不一致装不上（`INSTALL_FAILED_UPDATE_INCOMPATIBLE` /「与已安装的应用程序签名不一致」）。")
L.append("  不想卸正主的，从上面表格换一行包名。")
L.append("* 同一包名只能装一个，两个不同的包名之间互不影响，可以共存。")
L.append("* 全部 APK 用同一个 `7tattoo` keystore 签名（v1/v2/v3），")
L.append("  证书 SHA-256 `4EBE0621216F41021D11FAFA36DAE5B7FD74950BCF47F692A98FB8D9C3B76BF6`。")
L.append("* 只含 arm64-v8a（手机和车机都是 arm64）。")
L.append("* 自行构建：仓库不含 `gradlew`，用本地 Gradle 8.9；换包名 "
         "`gradle :app:assembleRelease -PoverrideAppId=<包名>`，"
         "10 个包一次出齐用 `bash scripts/build10.sh`。")
L.append("")
L.append("## 版本说明")
L.append("")
L.append("* APK 内 `versionCode=1 / versionName=%s` —— 和 tag 一致。" % ver)
L.append("* 早期文件名里的 `v0.7.2` 是上游 MusicFree 插件工程的版本，不适用于本 App，已全部更正。")
L.append("* 这 10 个包出自同一个提交：`%s`。" % head)
L.append("")
L.append("## SHA-256")
L.append("")
L.append("```")
for p, _ in ROWS:
    L.append("%s  %s.apk" % (sha[p], p))
L.append("```")
open("/tmp/rel_v100_body.md", "w").write("\n".join(L) + "\n")
print("正文已生成 %d 字节 / %d 行" % (os.path.getsize("/tmp/rel_v100_body.md"), len(L)))
print("\n".join(L[:22]))
