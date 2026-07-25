# 加密外部存储 — 真机手测清单

**版本基线**：DB schema v1（destructive，无 migration）；切卷后无需重启 App。  
**APK**：`app/build/outputs/apk/debug/app-debug.apk`  
**前置**：卸载旧版后全新安装（毁灭性升级，旧库不迁移）。

---

## 自动化已覆盖（可勾）

- [x] **T15-C1** `assembleDebug` 成功
- [x] **T15-C2** `:app:testDebugUnitTest` 全绿（含 MediaVolumeProviderTest、EncryptedMediaStore 切卷测试等）
- [x] **T15-C3** 原 plan 要求 `schemas/.../18.json` — **已作废**：用户决策为 DB v1 + `exportSchema=false` + `fallbackToDestructiveMigration`，无历史 schema 导出

---

## T15-C4 内部卷全流程

- [ ] 全新安装，**不**改媒体存储位置（默认内部）
- [ ] 配置同步源 + 同步关系，同步至少一个视频
- [ ] 浏览器出现条目与缩略图
- [ ] 进入播放：可播、可 seek、拖动进度预览取帧正常
- [ ] 设置 → 增益分析：可跑；故意失败项可「重试失败项」
- [ ] 长按/菜单分享到第三方播放器可透传（content URI + 临时读权限）
- [ ] 删除单文件：DB + 密文 + 缩略图一致消失；列表刷新

## T15-C5 外部 SAF 卷全流程

- [ ] 设置 → 系统集成 → **媒体存储位置** → 选外置/共享目录
- [ ] 首次 null→SAF：**无**切卷警告；直接授权
- [ ] 列表显示「外部目录：… · 可用 …」
- [ ] **全量重同步**（旧内部索引在「重建元数据」中为其他卷残留）
- [ ] 重复 T15-C4 全流程（播放 / seek / 缩略图 / 增益 / 分享）
- [ ] 再换一个 SAF 目录：出现 **切卷警告 Dialog**，确认后才切换
- [ ] 切回内部：同警告；确认后 `clearMediaStorageTreeUri`
- [ ] 切卷后**不杀进程**即可对新卷同步/播放（热切卷）

## T15-C6 密文布局（文件管理器）

- [ ] 用文件管理器打开选定目录
- [ ] 仅见类似 `<id/1000>/<id>.enc` 与 `thumbs/<id>.enc`（或项目约定路径）
- [ ] **无**明文视频真名、无未加密媒体

## T15-C7 重启持久化

- [ ] 杀进程 / 重启设备后打开 App
- [ ] SAF 目录仍显示为当前存储；既有文件可播
- [ ] 密钥解包正常（无反复初始化失败）

## T15-C8 风险回归

| 风险 | 手测 | 结果 |
|------|------|------|
| R1 OEM/SAF seek | 外置卷上 seek + 预览取帧 | [ ] |
| R4 SAF 授权被系统撤销 | 撤销后打开媒体应失败/可提示；重建扫描勿误清（若表现为 NOT_FOUND 记备注） | [ ] |
| R6 切卷索引 | 重建元数据：其他卷残留 **不**自动删；强确认后只清 DB 索引，**不**动当前卷物理文件 | [ ] |

## 删除 / 元数据 / 增益（回归）

- [ ] 文件夹删除部分失败：有 error 文案，不假装全成功
- [ ] 密文/缩略图删除 IO 失败时 DB 行仍在
- [ ] 重建元数据：当前卷 orphan / incomplete 可处理；other-volume 红卡 + 确认
- [ ] 无「新建文件夹」入口（加密卷不支持空目录）
- [ ] 搜索页无 `videoItemId` 时 Snackbar，不崩溃

## 已知可接受行为

- other-volume **只清 DB 索引**，旧卷磁盘 `.enc` 需用户自行清理
- deleteFolder 多 relation 同路径：产品可接受
- Provider 仍 EntryPoint→DAO（S2 未做）
- SAF `exists` 部分权限错误可能表现为 NOT_FOUND 而非 IO_ERROR
