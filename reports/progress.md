# 审计进度
## 全局
- source_HEAD: 116dd4de17f99833d9e2490b15e9ff4af1270de7
- source_status: ?? docs/agent-code-audit-prompt.md（合法；reports/ 建立后另有 ?? reports/ 亦合法）
- 工具链: GNU grep 3.12 / bash（set -o pipefail 语义执行）
- 采样哈希字节: e7 = 231（`git rev-parse HEAD | grep -oE '[0-9a-fA-F]{2}$'`）

## Phase 状态表
| Phase | 状态(TODO/IN_PROGRESS/DONE) | 唯一发现(P0/P1/P2) | CANDIDATE交叉 | U条目 | 完成时间 |
|---|---|---|---|---|---|
| 0 | DONE | 0/0/0 | 0 | 0 | 2026-08-17 |
| 1 | DONE | 0/0/3 | 1(F-1-3) | 0 | 2026-08-17 |
| 2 | DONE | 10/17/4 | 18 | 0 | 2026-08-17 |
| 3 | DONE | 0/0/1 | 0 | 0 | 2026-08-17 |
| 4 | DONE | 0/2/3 | 0 | 0 | 2026-08-17 |
| 5 | DONE | 0/0/1 | 1 | 0 | 2026-08-17 |
| 6 | DONE | 0/0/0(全REF) | 0 | 0 | 2026-08-17 |
| 7 | DONE | 0/0/1 | 1 | 0 | 2026-08-17 |
| 8 | DONE | 0/0/1 | 0 | 0 | 2026-08-17 |
| 9 | DONE | 0/0/4 | 2 | 0 | 2026-08-17 |
| 10 | DONE | 0/0/2 | 1 | 1 | 2026-08-17 |
| 11 | DONE | 汇总产物 | - | 1(引用) | 2026-08-17 |
| 12 | DONE | 复核通过 | - | 0 | 2026-08-17 |

## 检查项断点（大型清单必填；每 20 项或 1 文件一更）
| 项 | scan_command | scan_total | adjudicated | excluded_scope_or_FP | unknown | next_path | next_line | last_finding_id |
|---|---|---|---|---|---|---|---|---|
| P2-R2 删除候选分类 | grep -rnE '(delete\|remove\|purge\|clear\|unlink)' main | 363 | 86 | 277 | 0 | DONE | - | F-2-1 |
| P2-R3 通用捕获分类 | 三扫描见 02-redlines.md 2.3 | 153 | 125(90 PASS+35 FAIL) | 0 | 28 | DONE | - | F-2-25 |

## canonical registry（去重登记；新建 Finding 前必查）
| key（规则族+归一化路径+主证据行 / 规则族+归一化范围+缺失对象） | canonical ID | 规则别名 | REF 出现处 |
|---|---|---|---|
| ARCH-2 + settings/StorageManagementViewModel.kt + :46 | F-1-1 | ARCH-3 | |
| ARCH-2 + settings/AboutScreen.kt + :133 | F-1-2 | | |
| ARCH-2/3 + ViewModel android 框架耦合 + settings/SyncRelationEditViewModel.kt:5 | F-1-3 | | |
| R2 + FileOperationsUseCase.kt + :592 | F-2-1 | C-SY-卷所有权条款 | |
| R3 + SyncPreviewViewModel.kt + :224 | F-2-2 | | |
| R3 + BrowserViewModel.kt + :398(8站点) | F-2-3 | | |
| R3 + SettingsViewModel.kt + :71(7站点) | F-2-4 | | |
| R3 + SyncPreviewViewModel.kt + :85(5站点) | F-2-5 | | |
| R3 + SyncRelationEditViewModel.kt + :183(3站点) | F-2-6 | | |
| R3 + PlayerViewModel.kt + :111(4站点) | F-2-7 | | |
| R3 + ImageViewerViewModel.kt + :86(2站点) | F-2-8 | | |
| R3 + SyncSourceEditViewModel.kt + :94(3站点) | F-2-9 | | |
| R3 + GainAnalysisViewModel.kt + :93 | F-2-10 | | |
| R3 + StorageManagementViewModel.kt + :152 | F-2-11 | | |
| R3 + SyncEngine.kt + :480/:642 | F-2-12 | | |
| R3 + SyncEngine.kt + :562/:563 | F-2-13 | | |
| R3 + SyncEngine.kt + :603 | F-2-14 | | |
| R3 + SyncEngine.kt + :998/:1002 | F-2-15 | | |
| R3 + SyncEngine.kt + :1016/:1020 | F-2-16 | | |
| R3 + BrowserViewModel.kt + :116/:121/:126 | F-2-17 | | |
| R3 + SettingsViewModel.kt + :162 | F-2-18 | | |
| R3 + StorageManagementViewModel.kt + :66/:75 | F-2-19 | | |
| R3 + VideoMetadataExtractor.kt + :45/:65/:80 | F-2-20 | | |
| R3 + ImageMetadataExtractor.kt + :61 | F-2-21 | | |
| R3 + MetadataRebuildViewModel.kt + :188 | F-2-22 | | |
| R3 + EncryptedMediaStore.kt + :137/:321/:240 | F-2-23 | C-CV/R7 关联 | |
| R3 + SafVolume.kt + :132/:156 | F-2-24 | | |
| R3 + SyncPlayerApp.kt + :48 | F-2-25 | | |
| R4/C-CR + EncryptedMediaStore.kt + :178 | F-2-26 | R4/C-CR | |
| R6/C-ST-日志契约 + SyncEngine.kt + :399(7处) | F-2-27 | R6 | |
| R6 + DebugLogger.kt + :52/:57 | F-2-28 | R7 | |
| R6 + SyncPlayerHostKeyVerifier.kt + :49/:59 | F-2-29 | | |
| R6/R7 + EncryptedMediaStore.kt + :137 | F-2-30 | | |
| R6 + ErrorMessageMapper.kt + :89/:94 | F-2-31 | | |
| P3-丢失cause + EncryptedMediaStore.kt + :136 | F-3-1 | R6(REF F-2-30) | |
| C-CV-性能 + ItemContextMenu.kt + :185(share链) | F-4-1 | | |
| C-CV-性能 + 业务层 withContext 硬编码 14 处 | F-4-2 | | |
| P4-可变流暴露 + PlayerViewModel.kt:83 + ImageViewerViewModel.kt:54 | F-4-3 | | |
| C-CV-性能 + FileVolume.kt + :17-38 | F-4-4 | | |
| C-CV-性能 + SyncRelationEditViewModel.kt + :261 | F-4-5 | | |
| P5-临时文件 + SyncPlayerHostKeyVerifier.kt + :88 | F-5-1 | | |
| 测试缺口 + TOFU/协议分类器 + 无直接单测 | F-7-1 | C-ST | |
| C-CV-6 + SyncEngine.kt + :960 | F-8-1 | | |
| P9-超长函数 + 12 处清单 | F-9-1 | | |
| P9-重复 + PlayerViewModel.kt:209 ≡ ImageViewerViewModel.kt:119 | F-9-2 | | |
| P9-命名/归属 + debug/DebugLogger.kt + :17 | F-9-3 | | |
| P9-未引用 + StoragePaths.kt + :99 | F-9-4 | | |
| P10-权限 + AndroidManifest.xml + POST_NOTIFICATIONS | F-10-1 | | |
| P10-权限 + AndroidManifest.xml + WAKE_LOCK | F-10-2 | | |

ALL-DONE
