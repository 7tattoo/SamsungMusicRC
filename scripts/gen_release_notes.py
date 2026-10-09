#!/usr/bin/env python3
"""生成 10-in-1 的 Release 正文。

产物命名约定：v<versionName>_<包名>.apk —— 版本从 app/build.gradle 读，不写死。
用法：
    python3 scripts/gen_release_notes.py                 # 读 shared/SamsungMusicRC/，写到 stdout
    DEL_DIR=/path/to/apks python3 scripts/gen_release_notes.py --out /tmp/body.md
"""
import hashlib, os, re, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEL = os.environ.get("DEL_DIR", "/var/minis-euleros/shared/SamsungMusicRC")

ROWS = [
    ("com.spotify.music",          "Spotify"),
    ("com.apple.android.music",    "Apple Music"),
    ("com.luna.music.car",         "汽水音乐·车机版"),
    ("com.tencent.wecarflow",      "腾讯爱趣听"),
    ("com.kugou.android.auto",     "酷狗音乐·车机版"),
    ("cn.kuwo.kwmusiccar",         "酷我音乐·车机版"),
    ("com.tencent.ibg.joox",       "JOOX"),
    ("com.tencent.weread",         "微信读书"),
    ("com.qidian.QDReader",        "起点读书"),
    ("cn.aqzscn.stream_music",     "音流"),
]

# 版本号从 app/build.gradle 读（唯一真源）
gradle = open(os.path.join(ROOT, "app", "build.gradle"), encoding="utf-8").read()
m = re.search(r'versionName\s+"([^"]+)"', gradle)
c = re.search(r'versionCode\s+(\d+)', gradle)
if not m:
    raise SystemExit("app/build.gradle 里找不到 versionName")
ver, code = m.group(1), (c.group(1) if c else "?")

head = subprocess.run(["git", "-C", ROOT, "rev-parse", "--short", "HEAD"],
                      capture_output=True, text=True).stdout.strip()

def fname(pkg):
    return "v%s_%s.apk" % (ver, pkg)

missing = [p for p, _ in ROWS if not os.path.exists(os.path.join(DEL, fname(p)))]
if missing:
    raise SystemExit("缺文件：%s（期望 %s/，命名 v%s_<包名>.apk）"
                     % (", ".join(missing), DEL, ver))

sha = {p: hashlib.sha256(open(os.path.join(DEL, fname(p)), "rb").read()).hexdigest()
       for p, _ in ROWS}

L = []
L.append("# SamsungMusicRC v%s（10 合 1）" % ver)
L.append("")
L.append("纯本地音乐播放器（本地曲库扫描 + Media3 播放 + AAudio 原生输出 + 10 段均衡器），")
L.append("横屏车机双栏布局，专为 **vivo 车机 / JoviInCar** 投屏做了适配。")
L.append("")
L.append("> **文件怎么命名**：`v%s_<包名>.apk`。这 10 个包 **代码完全相同，只有包名不同**，" % ver)
L.append("> 所以文件名直接带上包名 —— 想要哪个装哪个，看名字就知道它是伪装成谁的。")
L.append("> 安装后系统里显示的应用名统一是 `Samsung Music`。")
L.append("")
L.append("## 下载哪个")
L.append("")
L.append("| APK 文件 | 这个包名对应的正版应用 |")
L.append("| --- | --- |")
for p, o in ROWS:
    L.append("| `%s` | %s |" % (fname(p), o))
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
L.append("* 同一包名只能装一个；不同包名之间互不影响，可以共存。")
L.append("* 全部 APK 用同一个 `7tattoo` keystore 签名（v1/v2/v3），")
L.append("  证书 SHA-256 `4EBE0621216F41021D11FAFA36DAE5B7FD74950BCF47F692A98FB8D9C3B76BF6`。")
L.append("* 只含 arm64-v8a（手机和车机都是 arm64），minSdk 26。")
L.append("* 自行构建：仓库不含 `gradlew`，用本地 Gradle 8.9；换包名 "
         "`gradle :app:assembleRelease -PoverrideAppId=<包名>`，"
         "10 个包一次出齐用 `bash scripts/build10.sh`。")
L.append("")
L.append("## 版本说明")
L.append("")
L.append("* APK 内 `versionCode=%s / versionName=%s` —— 与 tag 一致。" % (code, ver))
L.append("* 早期文件名里的 `v0.7.2` 是上游 MusicFree 插件工程的版本，不适用于本 App，已全部更正。")
L.append("* 这 10 个包出自同一个提交：`%s`。" % head)
L.append("")
L.append("## SHA-256")
L.append("")
L.append("```")
for p, _ in ROWS:
    L.append("%s  %s" % (sha[p], fname(p)))
L.append("```")

body = "\n".join(L) + "\n"
if "--out" in sys.argv:
    out = sys.argv[sys.argv.index("--out") + 1]
    open(out, "w").write(body)
    print("已写 %s（%d 字节 / %d 行）" % (out, len(body.encode("utf-8")), len(L)))
else:
    print(body)
