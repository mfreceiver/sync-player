# Plan: sync-player test coverage (VideoUtils + FormatUtils)

## Goal
为 `VideoUtils.kt` 与 `FormatUtils.kt` 补全单元测试。AGENTS.md 要求 "Aim for high coverage on core logic like SyncEngine and VideoUtils"。当前 `VideoUtilsTest` 只覆盖 `extractParentPath`（4/5 函数未测），`FormatUtils` 零测试。

## Scope
- 纯测试文件：追加 `VideoUtilsTest.kt` + 新建 `FormatUtilsTest.kt`
- **不改任何产线 Kotlin**（`VideoUtils.kt` / `FormatUtils.kt` 只读）
- 不 commit（ocmar 默认 record diff）

## Global Constraints
- **验证命令**：`JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest`（exit 0，0 failures）
- **测试框架**：JUnit 4（`org.junit.Test` + `org.junit.Assert.*`）。纯函数测试，**不使用** MockK / Coroutines Test / Turbine
- **被测函数为 `internal`**：测试在 app 模块内可直接访问。现有 `VideoUtilsTest` 包名 `cn.vectory.syncplayer.util`，用 **individual import**（如 `import cn.vectory.syncplayer.ui.util.extractParentPath`，非 wildcard）。新增测试沿用 individual import 模式
- **设备安全**：仅单测，不涉及设备/模拟器
- 被测源码（只读引用，勿改）：
  - `app/src/main/java/cn/vectory/syncplayer/ui/util/VideoUtils.kt`
  - `app/src/main/java/cn/vectory/syncplayer/ui/util/FormatUtils.kt`

## Context (interfaces under test)

### VideoUtils.kt
```
VIDEO_EXTENSIONS = {mp4,mkv,avi,mov,flv,wmv,webm,ts,m4v,3gp,mpeg,mpg}
IMAGE_EXTENSIONS = {jpg,jpeg,png,gif,bmp,webp,heic,heif}
isVideoFile(fileName): Boolean   // ext after last '.', lowercase, in VIDEO_EXTENSIONS
isImageFile(fileName): Boolean   // 同上，IMAGE_EXTENSIONS
isMediaFile(fileName): Boolean   // isVideoFile || isImageFile
getMimeType(fileName): String    // when ext:
  // video ext -> "video/*"; jpg|jpeg->"image/jpeg"; png->"image/png"; gif->"image/gif";
  // bmp->"image/bmp"; webp->"image/webp"; heic|heif->"image/heic";
  // mp3->"audio/mpeg"; flac->"audio/flac"; wav->"audio/wav"; aac->"audio/aac"; ogg->"audio/ogg";
  // srt|ass|nfo|txt->"text/plain"; else->"application/octet-stream"
```

### FormatUtils.kt
```
formatFileSize(bytes: Long): String
  // bytes<=0 -> "0 B"
  // units=[B,KB,MB,GB,TB]; digitGroups=(log10(bytes)/log10(1024)).toInt(); coerceAtMost(4)
  // "%.1f %s".format(bytes/1024^digitGroups, units[digitGroups])
formatDuration(ms: Long?): String
  // null -> ""; ms<=0 -> "0:00"
  // hours>0 -> "%d:%02d:%02d"; else "%d:%02d"
buildMetadataLine(duration,width,height,bitrate,extension,hasGainAdjusted=false): String
  // parts joined by " · "; duration(if>0 as formatDuration); width×height(if both>0); "GAIN"(if hasGainAdjusted);
  // bitrate(if>0: mbps=bps/1e6; >=1.0->"%.1fMbps" else "%.0fKbps"); extension.uppercase()(if not empty)
```

## Tasks

### Task 1: VideoUtils 测试补全
**File**: `app/src/test/java/cn/vectory/syncplayer/util/VideoUtilsTest.kt`（追加到现有文件，**保留**现有 4 个 `extractParentPath` 测试）

**覆盖**:
- `isVideoFile`: 正常扩展名(mp4/mkv)→true; 大写(.MKV)→true; 无扩展名→false; 非视频(.txt)→false; 多点(a.b.mp4)→true
- `isImageFile`: 正常(.jpg/.jpeg)→true; 大写(.PNG)→true; 非图像(.pdf)→false
- `isMediaFile`: 视频→true; 图像→true; 音频(.mp3)→false; 非媒体→false
- `getMimeType`: 每个 video ext→"video/*"; jpeg/jpg→"image/jpeg"; png/gif/bmp/webp 各一; heic+heif→"image/heic"; mp3/flac/wav/aac/ogg 各一; srt/ass/nfo/txt→"text/plain"; 大写(.MP4)→"video/*"; 无扩展名→"application/octet-stream"; 未知(.xyz)→"application/octet-stream"

### Task 2: FormatUtils 测试
**File**: `app/src/test/java/cn/vectory/syncplayer/util/FormatUtilsTest.kt`（新建）

**覆盖**:
- `formatFileSize`: 0→"0 B"; 负数→"0 B"; 1→"1.0 B"; 1023→"1023.0 B"; 1024→"1.0 KB"; 1048576→"1.0 MB"; 1073741824→"1.0 GB"; 大数(TB 级)→"x.x TB"
- `formatDuration`: null→""; 0→"0:00"; 负数→"0:00"; 30000→"0:30"; 65000→"1:05"; 3661000→"1:01:01"; 3600000→"1:00:00"
- `buildMetadataLine`: 全空→""; duration only; width×height only; 全字段组合; hasGainAdjusted 含 "GAIN"; bitrate>=1Mbps→"x.xMbps"; bitrate<1Mbps→"xxKbps"; extension 大写
