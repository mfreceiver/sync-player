# 模拟器媒体注入与黑盒复现基建

> 不依赖真实 NAS / 同步服务器，在本地模拟器完成「媒体注入 → 应用内播放 → 黑盒验证/复现」闭环。
> 适用：播放器 UI 回归、崩溃复现、端到端冒烟。本文件是这套流程的权威操作手册。

## 架构

```
[模拟器 emulator-5554]                    [宿主机]
  app (SFTP 同步) ──10.0.2.2:2222──▶ 127.0.0.1:2222
                                         scripts/dev/sftp-server.py   (paramiko, 路径囚笼)
                                              └──▶ /tmp/opencode/sftp-media/  (测试媒体)
```

- `10.0.2.2` 是模拟器访问**宿主 loopback** 的固定别名，无需宿主局域网 IP；服务器只 bind `127.0.0.1`，无局域网暴露。
- 凭据 `test` / `test123`（个人环境专用，写死在脚本常量里）。
- Host key 持久化于 `/tmp/opencode/sftp_host_key.pem`：指纹稳定，应用 TOFU（首连指纹确认）只需接受一次，重启服务器不变。

## 一次性准备

```bash
# 1. SFTP 服务器依赖（venv 隔离）
python3 -m venv /tmp/opencode/sftp-venv
/tmp/opencode/sftp-venv/bin/pip install "paramiko>=3"

# 2. 测试视频（640x360 横屏 45s，~2.3MB，faststart 便于流式取帧）
mkdir -p /tmp/opencode/sftp-media
ffmpeg -y -f lavfi -i testsrc2=size=640x360:rate=24:duration=45 \
  -f lavfi -i "sine=frequency=440:duration=45" \
  -c:v libx264 -preset veryfast -crf 30 -c:a aac -b:a 64k \
  -pix_fmt yuv420p -movflags +faststart /tmp/opencode/sftp-media/test-landscape.mp4

# 3. 模拟器（共享资源：AVD ocdroid，Android 14+，headless）
/home/mar/personal_projects/ocdroid/scripts/emulator.sh status   # 0=运行 1=未运行 2=锁残留
```

adb 不在 PATH：使用全路径 `/home/mar/android-sdk/platform-tools/adb`。

## Runbook（每次）

```bash
# 1. 启动模拟器（已运行则跳过）
/home/mar/personal_projects/ocdroid/scripts/emulator.sh start

# 2. 启动 SFTP 服务器（setsid 脱离会话；注意：shell 里 & 起的后台进程可能让工具调用等到超时，属预期）
setsid nohup /tmp/opencode/sftp-venv/bin/python scripts/dev/sftp-server.py \
  > /tmp/opencode/sftp_server.log 2>&1 < /dev/null &

# 3. 自测 SFTP（列目录 + stat + 读首块；客户端线程非 daemon，务必 finally close，否则挂住）
/tmp/opencode/sftp-venv/bin/python - <<'EOF'
import paramiko
t = paramiko.Transport(("127.0.0.1", 2222)); t.banner_timeout = 10
try:
    t.connect(username="test", password="test123")
    s = paramiko.SFTPClient.from_transport(t)
    print(s.listdir("/"), s.stat("/test-landscape.mp4").st_size)
    with s.open("/test-landscape.mp4", "rb") as f: print(f.read(16))
finally:
    t.close()
EOF

# 4. 安装被测 APK（基线 tag 用 git worktree 隔离构建，见下节）
/home/mar/android-sdk/platform-tools/adb install -r <apk路径>

# 5. 启动应用
/home/mar/android-sdk/platform-tools/adb shell am start -n cn.vectory.syncplayer/.MainActivity
```

### 应用内配置（一次性，数据随模拟器 AVD 持久）

1. 首启引导弹窗 →「配置数据源」
2. 新建数据源：协议 **SFTP**，主机 `10.0.2.2`，端口 `2222`，用户 `test`，密码 `test123`
3. TOFU 首连指纹弹窗 → 接受
4. 新建同步关系：数据源 ↔ 本地目录（**本地目录不可嵌套已有同步目录**，否则校验失败）
5. SyncPreview → 下载 tab → 选择 `test-landscape.mp4` → 同步；完成后 Browser 显示条目（如 `0:45 · 640×360 · 416Kbps`）

### 黑盒驱动与日志采集

```bash
ADB=/home/mar/android-sdk/platform-tools/adb
# 读界面（text/content-desc/resource-id/bounds）定位控件中心
$ADB shell uiautomator dump /sdcard/ui.xml && $ADB shell cat /sdcard/ui.xml
$ADB shell input tap X Y          # 点击
$ADB shell input text "..."       # 输入（特殊字符注意转义/分次）
$ADB shell input keyevent 4       # 返回键
$ADB exec-out screencap -p > x.png  # 截图留证

# 崩溃采集
$ADB logcat -c                    # 操作前清缓冲
# ...执行操作序列...
$ADB logcat -b crash -d           # crash buffer（空=无 Java 崩溃）
$ADB shell pidof cn.vectory.syncplayer   # 空=进程死亡
$ADB shell getprop ro.build type  # 模拟器镜像非 user build 可 adb root 取 tombstone：
$ADB root && $ADB shell ls -la /data/tombstones/   # 完事 $ADB unroot
```

### 清理（用毕必做——模拟器是共享资源）

```bash
/home/mar/personal_projects/ocdroid/scripts/emulator.sh stop   # 同时清 /tmp/ocdroid-emulator.lock
fuser -k 2222/tcp                                                # 停 SFTP（勿用 pkill，见坑 1）
```

## 基线版本构建

复现「上一个版本」的行为时用 worktree 隔离，勿切主工作树：

```bash
git worktree add /tmp/opencode/sync-vX.Y.Z vX.Y.Z
cd /tmp/opencode/sync-vX.Y.Z && JAVA_HOME=/home/mar/android-studio/jbr ./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk（~90MB）
# 冷缓存 + --no-daemon 首次构建可能 >7 分钟：别叠加 -q 与短超时；用毕 git worktree remove --force
```

## 已知坑

1. **`pkill -f sftp-server.py` 自杀**：执行 pkill 的 shell 自身命令行含同样字符串，会匹配到自己。按端口清理：`fuser -k 2222/tcp`。
2. **`./gradlew --stop` 不停 Kotlin 编译守护进程**（kotlin-compiler-embeddable，常驻 ~1GB）。空闲时手动 `kill <pid>`（`ps -eo pid,rss,args | grep KotlinCompileDaemon`）。
3. **paramiko `SFTPServerInterface` 默认方法是 no-op**（返回 `OP_UNSUPPORTED`）——`list_folder/open/stat/lstat` 等必须自己实现对本地 FS 的操作，`scripts/dev/sftp-server.py` 已含完整实现（含路径囚笼：虚拟根 `/` 映射 `sftp-media/`，拒绝 `../` 逃逸）。
4. **paramiko 客户端线程非 daemon**：自测脚本异常退出前不 `t.close()` 会永久挂住——必须 try/finally。
5. **SFTP 客户端会把 `../` 规范化**：`listdir("../")` 在客户端就被解析，测不出服务端逃逸防护；防护逻辑在服务端 `_jail()`，单测覆盖。

## 实测记录

- **2026-09-04**：用于复现用户报告「按横屏按钮切横屏后退出播放闪退」（v12.3.2 debug APK）。
  注入管线全程走通：数据源/关系配置 → TOFU 接受 → 下载 2.3MB → 播放。
  18+ 操作变体（退出按钮 / 系统返回键 / 横屏后立即退 / 等 4-5s 退 / 唤出控制区后退 / 播放中退 / 连续进出 / 快速方向切换）**均未复现**；无 `FATAL EXCEPTION` / native tombstone / ANR，进程全程存活。
  观察：横屏切换窗口 1080x2400 ↔ 2400x1080 正常；播放器 2-3 秒自动退出（疑似自动化 tap 命中控制区所致，未深究）。
  产物：`/tmp/opencode/logs/crash-repro/`（18 组 dump/截图/PID/logcat，ephemeral）。

## 关联

- `docs/specs/sync.md`：同步引擎、SFTP 协议、错误码
- `docs/specs/storage-security.md`：TOFU、凭证存储（EncryptedSharedPreferences）
