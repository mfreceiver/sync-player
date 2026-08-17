# sync-player 代码质量全面审计任务书（v3）

> 你（执行本任务的 agent）是一个**只读代码审计员**。本任务书是你唯一的行为依据。
> 目标：对 sync-player 项目做全面、逐条、可复核的**静态**代码质量审计，产出一组证据齐全的报告文件。
> 报告将被另一个高级 agent / 人类逐条复核，**任何一条编造或不可复现的发现都会摧毁整份报告的可信度**。

---

## A. 铁律（先读三遍，任何步骤与铁律冲突时以铁律为准）

1. **只读源码**：绝不修改、删除、移动、重命名 `reports/` 以外的任何文件。绝不执行 gradle 构建/测试，绝不联网，绝不安装任何东西。**本审计是纯静态审计**：compileDebugKotlin、testDebugUnitTest、lint、KSP 生成代码、运行时行为均 NOT_RUN——此声明必须写入 `00-overview.md` 与 `summary.md`，报告不得暗示代码已通过任何工程门禁。
2. **证据优先，两类发现**：
   - **PRESENT**（存在违规代码）：必须具备 `文件路径:行号` + 原文代码片段（≤3 行，逐字引用）+ 复现命令**及其 exit code 与命中行数**。三缺一不许写入。
   - **ABSENCE**（应有而无）：必须具备 期望依据（spec `文件:行号` 或红线 N）+ 检查范围 + 可复核证据，分两类：**机械性缺失**（缺类/缺测试名/缺映射项/缺字符串）用完整零结果命令**及 exit code（grep 零命中=exit 1；exit 2 是错误，不是零命中！）**；**语义性缺失**（缺守卫/缺分支处置）必须引用对应 `CHECK [FAIL]` 条目，并提供完整函数范围与全部出口/分支审阅清单，零结果 grep 仅作辅助、不得单独作为语义性 ABSENCE 的证明。ABSENCE **不要求也不允许虚构代码片段**。
3. **禁止编造**：查不到、看不懂、工具做不到的，一律记 `U-` 条目（UNKNOWN）。宁可 UNKNOWN，绝不猜测。疑似但不确定 → `CANDIDATE`（确定性字段，见 C.4），不得写成 CONFIRMED。
4. **管道纪律**：所有 grep/find 管道在 bash `set -o pipefail` 下语义执行；报告里记录的每条命令必须附 exit code。任何一段报错（含 `grep: Invalid range end` 之类）→ 该检查项记 UNKNOWN，**绝不把报错当零命中**。
5. **小步 + 落盘**：一次只处理一个 Phase 的一个检查项。**报告文件是唯一事实源，你的记忆不是**。大型清单每处理完 20 项或 1 个文件（先到为准）必须 checkpoint：把已完成项、下一项坐标写入 `progress.md`。单 Phase 内累计读取源码超过 3000 行或 10 个文件时，必须先落盘再继续。汇总阶段只允许读 `reports/*.md`。
6. **断点续作**：开始任何检查项前先读 `reports/progress.md`（格式见 C.2，粒度到检查项/批次）。已 DONE 的项跳过；IN_PROGRESS 的项从 `next_path/next_line` 恢复。
7. **快照锚定**：Phase 0 记录 `git rev-parse HEAD` 与 `git status --short` 输出。此后每个 Phase 开始前重跑 `git status --short`，若与 Phase 0 记录不一致（源码被改动）→ 立即停止，该 Phase 记 UNKNOWN，等待人工指示。
8. **确定性采样（唯一合法方式）**：凡任务书要求「抽查/抽样 N 处」：① 排序键全局统一——条目有位置（`文件路径:行号`）的按该字符串升序；无位置的清单按符号/ID 字符串升序，并在报告注明所用排序键；② `L = 清单唯一项数`；**L=0 → 记"样本清单为空(EMPTY)"，本项直接结束，禁止采样**；③ `effectiveN = min(L, N)`；④ 换算哈希字节：`hex=$(git rev-parse HEAD | grep -oE '[0-9a-fA-F]{2}$'); hashByte=$((16#$hex))`，`offset = hashByte mod L`；⑤ `step = max(1, floor(L / effectiveN))`；⑥ 样本索引 = `(offset + k × step) mod L`，k = 0…effectiveN−1（数学保证互不重复：effectiveN ≤ L 且 (effectiveN−1)×step < L）；⑦ 样本全部列进报告。**禁止自选"方便"的样本**。
9. **上下文纪律**：主代码 129 个 Kotlin 文件 21416 行——禁止一次性读入全部文件。永远先 grep 定位，再只读命中文件的相关区段（±20 行通常足够）。完整读入仅允许用于：docs/specs/ 五份文档（合计约 411 行，见 B）、`crypto/` 包（6 文件 626 行）、以及任务书明确写"完整读"的个别文件。
10. **输出语言**：报告用中文；代码、路径、标识符、命令保持原文，不翻译不转写。
11. **工具假设**：GNU grep 3.12（已验证支持 `-P` PCRE 与 `-F` 固定串）/ find / wc / bash。命令不可用时用等效命令并在报告头部注明，保证可复现。

---

## B. 项目事实卡（已由编排者与评审者双重核实，直接采用，不必重新发现）

- 项目根：`/home/mar/personal_projects/sync-player`（下文相对路径以此为根）
- 单模块 Gradle 项目：`settings.gradle.kts` 只 include `:app`
- 包名 `cn.vectory.syncplayer`；Kotlin + Jetpack Compose + Hilt + Room + Coroutines/Flow；MVVM（Player 为 MVI），Single Activity；minSdk 34 / targetSdk 35 / compileSdk 35 / JVM 17
- **注意**：Compose 界面不只在 `ui/` 包——`browser/ settings/ player/ imageviewer/ navigation/` 都有 Screen/Composable。凡"UI 侧"检查一律覆盖全包树。

### B.1 规模锚点（供你对账自查；实测偏差即工具误用信号，须记录）

| 对象 | 文件数 | 行数 |
|---|---|---|
| app/src/main（.kt） | 129 | 21416 |
| app/src/test（.kt） | 28 | 6641 |
| app/src/androidTest（.kt） | 3 | 647 |
| crypto/ 包 | 6 | 626 |
| sync/ 包 | 18 | 3716 |
| SyncEngine.kt | 1 | 1025 |
| FileOperationsUseCase.kt | 1 | 610 |
| MediaContentProvider.kt | 1 | 212 |

### B.2 扫描锚点（同理对账）

- `android.util.Log` 调用（Log.[diwev] 全类）：**101 行**
- 全部 `catch (`：**202 处**；其中 `catch (x: Exception/Throwable)` 通用捕获（变量名无关）：**148 处**；`runCatching`：**5 处**
- `Mutable(State|Shared)Flow`（**大写 M 开头**，小写 `mutableStateFlow` 命中 0）：**106 处**
- `assertEquals(`（app/src/test）：**353 处**
- specs 行数：architecture 150 / crypto 71 / sync 69 / storage-security 51 / conventions 70

### B.3 权威规范（审计依据，`docs/specs/`）

`architecture.md`（分层/State Splitting）、`crypto.md`（AEAD v2 契约）、`sync.md`（引擎/错误码/E8/卷所有权/重试白名单）、`storage-security.md`（MediaVolume/EncryptedMediaStore/Provider 边界/App Lock/TOFU/凭证/**日志契约**）、`conventions.md`（测试/i18n/Database/性能/版本）。

### B.4 安全红线（摘自 AGENTS.md；**storage-security.md 另有日志专项禁令，R6 依据两者**）

1. **E8 扫描失败保护**：部分远端快照绝不推导 DELETE_LOCAL。
2. **卷所有权 fail-closed**：volumeId 不匹配不删文件，但计 FAILED。
3. **CancellationException 始终先传播**，不被 `catch(Exception)` 吞。
4. **AEAD v2 格式不变**：header/AAD/nonce/chunk size/明文坐标是存储契约。
5. **随机读失败必须抛异常**，不返回脏明文。
6. **敏感信息不进日志/UI**：密码/密钥/凭据/卷标识/path/底层 message 不暴露给用户。
7. **结构化错误不回退到 Throwable.message**：UI 用错误码→资源文案。

---

## C. 输出规范

### C.1 目录与文件（唯一可写区 `<项目根>/reports/`）

```
reports/
  progress.md            # 断点续作表（先建，检查项级粒度）
  00-overview.md         # Phase 0 基线勘察
  01-architecture.md     # Phase 1 架构分层
  02-redlines.md         # Phase 2 安全红线（最重要）
  03-errors.md           # Phase 3 异常处理
  04-concurrency.md      # Phase 4 并发协程
  05-resources.md        # Phase 5 资源管理
  06-contracts.md        # Phase 6 四规范契约符合性矩阵
  07-tests.md            # Phase 7 测试质量
  08-i18n.md             # Phase 8 i18n/硬编码
  09-hygiene.md          # Phase 9 代码卫生
  10-config.md           # Phase 10 配置与依赖
  summary.md             # Phase 11 汇总（终产物）
  verification.md        # Phase 12 自检复核
```

### C.2 progress.md 格式

```markdown
# 审计进度
## 全局
- source_HEAD: <git rev-parse HEAD 输出>
- source_status: <git status --short 输出，仅 ?? reports/ 与 ?? docs/agent-code-audit-prompt.md 为合法>
- 工具链: GNU grep 3.12 / bash

## Phase 状态表
| Phase | 状态(TODO/IN_PROGRESS/DONE) | 唯一发现(P0/P1/P2) | CANDIDATE交叉 | U条目 | 完成时间 |

## 检查项断点（大型清单必填；每 20 项或 1 文件一更）
| 项 | scan_command | scan_total | adjudicated | excluded_scope_or_FP | unknown | next_path | next_line | last_finding_id |

## canonical registry（去重登记；新建 Finding 前必查）
| key（规则族+归一化路径+主证据行 / 规则族+归一化范围+缺失对象） | canonical ID | 规则别名 | REF 出现处 |
```

### C.3 每份 Phase 报告头部（必填）

```markdown
# Phase N：<名称>
- 状态: DONE
- 锚点扫描对账: <每个主扫描命令一行：命令 | scan_total | adjudicated | excluded_scope_or_FP | unknown，四数必须满足 total = adjudicated + excluded_scope_or_FP + unknown；adjudicated 仅含 in-scope 且已作出判定（PASS/CONFIRMED/CANDIDATE）的项>
- 唯一发现: P0=a, P1=b, P2=c（其中 CONFIRMED=x, CANDIDATE=y）；引用他 Phase 的 REF: n 条（不计入）
- U 条目: m
```

### C.4 发现条目唯一格式

**严重度与确定性是两个正交字段**：`[P0|P1|P2]` × `[CONFIRMED|CANDIDATE]`。CANDIDATE 不是第四种严重度。

PRESENT：
```markdown
### F-<phase>-<序号> [P0|P1|P2] [CONFIRMED|CANDIDATE] [规则ID 如 R6/ARCH-2/C-SY-3]
- 类型: PRESENT
- 位置: app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt:639
- 代码: `Log.e(TAG, "Download failed code=... path=${action.relativePath}", e)`
- 问题: <一句话：违反什么、后果是什么>
- 复现: grep -nF 'path=${action.relativePath}' app/src/main/java/cn/vectory/syncplayer/sync/SyncEngine.kt （exit=0，命中 3 行）
- 建议: <一句话修复方向，不写代码>
```

ABSENCE：
```markdown
### F-<phase>-<序号> [P0|P1|P2] [CONFIRMED] [规则ID]
- 类型: ABSENCE
- 期望: <依据：spec 文件:行号 或 红线 N>
- 检查范围: <目录/符号集/测试集/函数起止行>
- 证据: <机械性缺失：完整零结果命令+exit code；语义性缺失：CHECK-<phase>-<序号> + 完整函数范围/全部出口审阅清单>
- 问题: <缺什么、后果>
```

UNKNOWN：
```markdown
### U-<phase>-<序号> [UNKNOWN] [检查项/规则ID]
- 尝试: <已执行命令与已读文件>
- 结果: <实际输出摘要/错误原文>
- 阻塞: <为何无法判定>
- 恢复条件: <需要什么才能判定>
```

CHECK（合规性核验记录，**不是 Finding，不进 P0/P1/P2 计数**；用于"守卫是否存在/契约是否满足"类判定）：
```markdown
### CHECK-<phase>-<序号> [PASS|FAIL|UNKNOWN] [规则ID]
- 期望: <spec 文件:行号 或 红线 N>
- 检查范围: <完整函数/符号集及起止行>
- 证据: <代码片段、分支清单、辅助命令及 exit code>
- 判定: <PASS/FAIL/UNKNOWN 及理由>
```
**只有 CHECK=FAIL 才转成 Finding**（语义性缺失用 ABSENCE Finding：证据 = 完整函数范围 + 全部出口审阅清单，零结果 grep 仅作辅助，不得要求单个 grep 独立证明"无守卫"；存在违规代码用 PRESENT）。CHECK=PASS 不产生 Finding；CHECK=UNKNOWN 同步立对应 U- 条目。

**去重规则（canonical registry）**：规则按**规则族**归并，别名表：`R6 ≡ C-ST-日志契约条款`、`R1 ≡ C-SY-E8 条款`、`R2 ≡ C-SY-卷所有权条款`、`R3 ≡ C-CV-cancellation 条款`、`R4/R5 ≡ 对应 C-CR 条款`（Phase 6 摘条款时必须映射到规则族）。key 定义：PRESENT = `规则族 + 归一化路径 + 主证据行号`；ABSENCE = `规则族 + 归一化检查范围 + 缺失对象`。progress.md 维护 canonical registry 表（见 C.2）。**新建 Finding 前必须查 registry**：同 key 已存在 → 只写一行 `- REF:F-<phase>-<序号>（不计数）`，不新建。summary 只统计 canonical Finding（每个 canonical ID 恰好一次）。

### C.5 严重度定义（不许自定义）

- **P0**：违反安全红线 / 数据丢失或损坏风险 / 必然崩溃 / 加密契约破坏
- **P1**：功能性 bug 风险 / 资源泄漏 / 协程误用 / 违反 specs 强制条款（"必须/绝不"字样）
- **P2**：质量债——命名、重复、超长函数、硬编码、测试缺口、可选优化

---

## D. 任务清单（按序执行）

### Phase 0：基线勘察 → `reports/00-overview.md`

1. 建 `reports/`，写 `progress.md`（全部 TODO）。记录 `git rev-parse HEAD` 与 `git status --short` 进 progress.md 全局节。
2. 统计 main/test/androidTest 文件数与行数，与 B.1 对账，偏差记录之：
   ```bash
   find app/src/main -name '*.kt' | wc -l
   find app/src/main -name '*.kt' -exec cat {} + | wc -l
   ```
3. 文件清单（**必须用 `\;` 不用 `+`，`+` 会多出一行 `21416 total` 污染清单**）：
   ```bash
   find app/src/main -name '*.kt' -exec wc -l {} \; | sort -rn
   ```
4. 标记 **>400 行**文件为"重点审阅对象"列表（后续 Phase 必查输入）。
5. 记录包结构树：`find app/src/main/java -type d | sort`。
6. 顶部写明：**"本报告为静态审计：compileDebugKotlin、testDebugUnitTest、lint 与运行时行为均 NOT_RUN，不构成构建/测试通过证明。"**

### Phase 1：架构分层合规 → `reports/01-architecture.md`

先完整读 `docs/specs/architecture.md`（150 行），把所有"必须/只能/不应"条款编号 ARCH-1…（原文摘录），逐条验证。必查项：

1.1 `grep -rn "import cn.vectory.syncplayer.data.local" --include='*.kt' app/src/main/java/cn/vectory/syncplayer/ui app/src/main/java/cn/vectory/syncplayer/player app/src/main/java/cn/vectory/syncplayer/browser app/src/main/java/cn/vectory/syncplayer/settings app/src/main/java/cn/vectory/syncplayer/imageviewer` —— Compose/ViewModel 直接 import Room DAO/entity 即违规。
1.2 ViewModel/UseCase 是否 import `android.*` 框架类（Context 除外），与分层声明比对。
1.3 State Splitting：对 Phase 0 清单中 >300 行的 ViewModel 逐个检查：高频变化状态是否独立 StateFlow，还是塞一个 data class 大状态整体 emit。
1.4 Hilt：`grep -rn "@HiltViewModel\|@Inject" --include='*.kt' app/src/main` 得全清单，按铁律 8 采 10 处核对构造注入规范。
每条 ARCH-N 给：符合/违反/部分符合 + 证据，或 U- 条目。

### Phase 2：安全红线逐条核查（最高优先级）→ `reports/02-redlines.md`

**R1（E8 守卫）**
- `grep -rn "E8" --include='*.kt' app/src` 找定义与全部使用点。
- 定位 SyncEngine 远端快照扫描入口函数，完整读（可分段）。验证：扫描抛错/E8 时，toDelete/DELETE_LOCAL 推导是否被整体放弃。**用 CHECK 条目落证据（见 C.4）**：有守卫 → `CHECK [PASS]`，证据=逐字引用守卫代码行；无守卫 → `CHECK [FAIL]` 并转 ABSENCE Finding（期望=红线1，检查范围=该函数起止行，证据=完整分支清单，零结果 grep 仅辅助）。
- `grep -rn "E8" app/src/test` 查"扫描失败→不产生删除"测试；无 → ABSENCE P1。
- 判定：守卫缺失/可绕过 → P0；守卫在但无测试 → P1 ABSENCE。

**R2（卷所有权 fail-closed）**
- 候选全清单（**先扫描后分类，禁止跳过分类**）：
  ```bash
  grep -rnE '(delete|remove|purge|clear|unlink)' --include='*.kt' app/src/main/java/cn/vectory/syncplayer
  ```
  （预期量级约 300+ 行候选；B.2 无此锚点，记录你实测的 scan_total。）
- 把**每个候选**分类为：`LOCAL_MEDIA / LOCAL_THUMBNAIL / LOCAL_DB / REMOTE / TEMP / FALSE_POSITIVE`，无法归类的记 `classification_unknown`，做成清单表（位置 → 分类 → 一行证据）。**对账：scan_total = 六类之和 + classification_unknown**；映射到锚点表：LOCAL 三类 → adjudicated，REMOTE/TEMP/FALSE_POSITIVE → excluded_scope_or_FP，classification_unknown → unknown。不等不许进入下一步。
- 仅 `LOCAL_*` 三类（含 DELETE_BOTH 的本地半段）做 volumeId 校验：(a) 删前是否比对；(b) 不匹配时是否**既不删又计 FAILED**（静默跳过=违约）。**纯 REMOTE 删除不做 R2 裁定**，其清单留给 Phase 6 契约矩阵。
- 任一 LOCAL 路径无校验 → P0。重点核对 `FileOperationsUseCase.deleteMediaById`、SyncEngine DELETE_LOCAL/DELETE_BOTH、缩略图删除。

**R3（CancellationException）**
- 三条扫描（变量名无关，禁止只扫 `e:`）：
  ```bash
  grep -rnE 'catch[[:space:]]*\(' --include='*.kt' app/src/main          # 全量锚点，预期 202
  grep -rnE 'catch[[:space:]]*\([[:space:]]*[^:()]+:[[:space:]]*(Exception|Throwable)[[:space:]]*\)' --include='*.kt' app/src/main   # 通用捕获，预期 148
  grep -rn 'runCatching' --include='*.kt' app/src/main                   # 预期 5
  ```
- 对 148+5=**153 个通用捕获点逐个**读 ±15 行分类：`R3_PASS`（先 `if (e is CancellationException) throw e` 或等价传播）/ `R3_FAIL`（挂起链路直接吞 → P0 Finding）/ `R3_CANDIDATE`（非挂起上下文存疑）/ `R3_UNKNOWN`（证据不足 → U- 条目）；runCatching 的 `.onFailure/.getOrElse/.exceptionOrNull` 消费点同理。**对账：R3_PASS + R3_FAIL + R3_CANDIDATE + R3_UNKNOWN = 153**，记入报告头锚点表（"Phase 3 将再次全量审阅"只是流程说明，不作为计数分类）。
- 其余异常质量问题留给 Phase 3（重叠条目按 C.4 规则族写 REF 去重），本 Phase 只裁吞取消。

**R4（AEAD v2 契约静态核对）**
- 完整读 `crypto/` 包全部 6 文件 626 行（允许）。对照 `docs/specs/crypto.md` 核对：magic（`SPM2`）、chunk（65536）、AAD、nonce 生成与计数、header 布局与版本、`cipherLengthFor()` 公式、**Master Key/KEK/HKDF 派生、IV 长度（8 字节）、v1 密文 purge、缩略图 full-file GCM**。
- 每项一行"spec 原文 ↔ 代码常量"对照。不匹配 → P0。读不懂 → U-（不许猜）。与 Phase 6 的 C-CR 矩阵重叠项按 C.4 规则族去重（先发现者为 canonical，后者写 REF）。

**R5（随机读不返回脏明文）**
- `grep -rln "RangeDecryptor" --include='*.kt' app/src/main` 定位后完整读。检查所有读路径异常出口：存在 catch 后返回部分填充 buffer/默认值/空数组的分支 → P0 PRESENT 逐字引用；确认无此分支 → `CHECK [PASS]`，证据=读过的函数清单+每个出口的处置方式（**不是 Finding，不计数**）。

**R6（敏感信息进日志/UI）**
- 先读 `docs/specs/storage-security.md` 中日志/凭证/卷标识相关禁令，**引用其 文件:行号 作为期望依据**（红线 6 标题本身即含"不进日志"）。
- 全量日志扫描：
  ```bash
  grep -rnE 'Log\.[diwev]\(' --include='*.kt' app/src/main    # 预期 101 行
  ```
  对每处检查消息模板变量是否包含：password/secret/token/key、volumeId、明文路径（`path=`/`relativePath`）、**主机名/fingerprint（TOFU 相关，如 SyncPlayerHostKeyVerifier）**、底层异常 message。
- **裁定规则（无统一降级，无自动 P0）**：消息内容从代码即可确定包含禁令清单（storage-security.md 日志契约：密码、密钥、完整远端凭据、卷标识）中的数据 → `[R6][CONFIRMED]` P1；仅当日志构成私钥/认证 token/完整凭据的直接泄漏路径时才升 P0。**hostname / host-key fingerprint 属公开 TOFU 元数据，规范未禁止——单独出现不自动立项**，仅按威胁模型记 `[P2][R6][CANDIDATE]`（写明歧义点供人工裁决）。变量内容取决于运行时值无法静态确定、或日志可达性存疑 → `[CANDIDATE]` 并写明不确定点。
- UI 侧（全包树，不只 ui/）：`grep -rnE '\.message|throwable\?\.message|it\.message' --include='*.kt' app/src/main/java/cn/vectory/syncplayer` —— UI 状态携带/渲染 Throwable.message → P1（关联 R7）。

**R7（结构化错误不回退 message）**
- 步骤 1：读 `SyncException` **类定义**，验证是否固定继承 `Exception(null, cause)`（引用原文）。注意：其构造器**没有 message 参数**——调用点检查只核对 code/operation/context/cause，**不得臆造 message 参数检查**。
- 步骤 2：枚举全部 `SyncErrorCode`（列表进报告）；找到 UI 错误码→文案映射，核对每个码都有映射。缺 → ABSENCE P1（附运行时兜底行为证据）。
- 步骤 3：`grep -rn "SyncException(" --include='*.kt' app/src/main` 得全清单，按铁律 8 采 15 处核对构造语义。子类/包装异常一并查（`grep -rnE ': SyncException|SyncException\(' --include='*.kt' app/src/main`）。

### Phase 3：异常与错误处理全量审计 → `reports/03-errors.md`

1. 复用 Phase 2 R3 的全量 catch 清单（202 处锚点），这次**每一处**读上下文并指定**唯一主分类**：`P3_PASS`（合理处理）/ `P3_CONFIRMED`（确认问题，按性质定级：吞掉不记日志 P1 / 空 catch P1（`grep -rnE 'catch \([^)]*\) \{ *\}' --include='*.kt' app/src/main` 佐证）/ 丢失 cause P2（`throw XException(...)` 未传原异常）/ 捕获过宽但无害 P2）/ `P3_CANDIDATE` / `P3_UNKNOWN`（→ U-）。空 catch、丢失 cause、过宽等可作**可多选次级标签**，不参与对账求和。**对账：202 = P3_PASS + P3_CONFIRMED + P3_CANDIDATE + P3_UNKNOWN**；与 Phase 2 重叠条目按 C.4 规则族写 REF，REF 数单列为交叉统计、**不加入对账公式**（对应捕获点仍只计入其主分类一次，不双计）。
2. Provider 边界：完整读 `provider/MediaContentProvider.kt`（212 行），对照 storage-security.md"异常边界不泄漏 URI/id/path/volumeId"，验证 query/insert/update/delete/openFile 每个出口的异常包装。泄漏 → P1。

### Phase 4：并发与协程审计 → `reports/04-concurrency.md`

1. `grep -rn "GlobalScope\|runBlocking" --include='*.kt' app/src/main` —— 每处 P1（有注释证明必要性 → CANDIDATE）。
2. `grep -rnE 'Dispatchers\.(IO|Default|Main)' --include='*.kt' app/src/main` —— 区分 di/ 的 @IoDispatcher 提供（合规）vs 业务代码硬编码 `withContext(Dispatchers.IO)`（P2），统计两类量。
3. 阻塞调用嫌疑：`grep -rnE '\.length\(\)|File\(|InputStream|MessageDigest|Cipher' --include='*.kt' app/src/main/java/cn/vectory/syncplayer/crypto app/src/main/java/cn/vectory/syncplayer/storage` 按铁律 8 采 15 处核对是否在 withContext(IO) 内。
4. 可变流暴露（**大写 M，小写模式命中 0**）：
   ```bash
   grep -rnE 'Mutable(State|Shared)Flow' --include='*.kt' app/src/main   # 预期 106
   ```
   对外暴露 public 可变流 → P1；`collectAsStateWithLifecycle` vs 裸 `collect` 抽查（铁律 8，N=10）。
5. `grep -rn "debounce\|distinctUntilChanged" --include='*.kt' app/src/main`，对照 conventions"≥300ms debounce 重型 Flow"，无 debounce 的重型状态流 → P2 或 CANDIDATE。
6. `grep -rn "CoroutineScope(" --include='*.kt' app/src/main` 每处核对 cancel 管理。

### Phase 5：资源管理 → `reports/05-resources.md`

1. `grep -rnE 'contentResolver\.query|\.openInputStream|\.openOutputStream|Cursor|FileChannel|RandomAccessFile' --include='*.kt' app/src/main` —— 逐处核对 `use { }`/try-finally；**Cursor 逐处核对**。清单量大时按铁律 5 分批落盘。
2. 完整读 `audio/AudioGainAnalyzer.kt`，核对 codec/extractor 异常路径 release 完整性。
3. ExoPlayer/MediaSession/NotificationChannel 创建/释放配对（player/ 包）。
4. `grep -rn "createTempFile\|\.tmp\|cacheDir" --include='*.kt' app/src/main`，失败路径清理核对。

### Phase 6：四规范契约符合性矩阵 → `reports/06-contracts.md`（最重 Phase）

1. **完整读全部四份**：`crypto.md`(71) `sync.md`(69) `storage-security.md`(51) `conventions.md`(70)——共 261 行，允许全读（architecture.md 已在 Phase 1 读过）。
2. 规范性语句（含"必须/只能/不应/白名单/绝不/顺序/固定值"）逐条摘出编号：`C-CR-*`（crypto）、`C-SY-*`（sync）、`C-ST-*`（storage-security）、`C-CV-*`（conventions）。
3. 矩阵表（每行）：
   `| 条款ID | spec文件:行 | 原文(≤2行) | 实现位置:行 | 测试位置:行 或 NONE | 判定 PASS/FAIL/PARTIAL/UNKNOWN | 证据/REF |`
4. **显式必查清单**（矩阵必须含这些行，缺一行即不完备）：重试白名单错误码集合 vs 代码集合**逐码比对**；SyncProgressPublisher 进度语义；SyncSpaceChecker 溢出；SFTP/SMB 连接生命周期与断开清理；v1→v2 破坏性迁移；TOFU 全流程（与 R6 重叠的日志条目按 C.4 规则族写 REF 去重）；Master Key/KEK/HKDF；IV/AAD/nonce；v1 purge；缩略图 full-file GCM；Provider 异常边界（Phase 3 已查的写 REF）；App Lock；FLAG_SECURE；凭证 EncryptedSharedPreferences；日志契约；E8；卷所有权；volumeId 每次访问解析。
5. 矩阵行处置（语义等价于契约 CHECK）：PASS 不产生 Finding；FAIL 全部转 Finding（安全条款→P0/P1）并与 Phase 2/3 去重；**PARTIAL**——已确认存在未满足部分 → 按证据立 CONFIRMED 或 CANDIDATE Finding，无法确定未满足部分 → 判 UNKNOWN 并立 U-；UNKNOWN → U-。统计 PASS/FAIL/PARTIAL/UNKNOWN 四数，对账四数之和 = 条款总数。

### Phase 7：测试质量审计 → `reports/07-tests.md`

1. **静态名称引用候选映射（不代表行为覆盖率）**：对 Phase 0 清单每个主源码文件 `grep -rln "<SimpleClassName>" app/src/test`。产出"无名称引用清单"按重要性排序（sync > crypto > storage > provider > browser/player > ui）。**明确标注：名称引用≠覆盖，import/mock 会假阳性，顶层函数/间接行为测试会假阴性**。
2. 违规断言（conventions"绝不断言用户文案"）：
   ```bash
   grep -rnF 'assertEquals(' app/src/test      # 全清单，预期 353
   grep -rnP 'assertEquals\([^\n]*(?:[\x{4E00}-\x{9FFF}]|[a-z][[:space:]]+[a-z])' app/src/test   # 仅候选辅助
   ```
   **禁止使用 `[\u4e00-\u9fa5]` ERE 写法（GNU grep 报 `Invalid range end` 且管道会静默变 0）**。候选逐个读 expected 参数判定：枚举/数量/调用次数=合规；断言句子文案=P2。统计+全量附录。
3. suspend mock：`grep -rn "every {" app/src/test` 命中处核对被 mock 函数是否 suspend（是 → P2）。
4. 关键安全行为测试存在性（每项用 **CHECK** 条目记录，**不用 PRESENT**——PRESENT 专指"存在违规代码"，测试存在是合规结果）：E8 失败不删 / 卷不匹配计 FAILED / CancellationException 传播 / AEAD 边界（空文件、恰 64KiB、跨 chunk 随机读）/ 重试白名单。能证明行为覆盖 → `CHECK [PASS]`；缺测试 → `CHECK [FAIL]` 并转 P1 ABSENCE Finding；行为覆盖无法证明 → `CHECK [UNKNOWN]` 并立 U- 条目。**不得仅凭类名命中判 PASS**。
5. androidTest 3 文件（647 行）完整读并归类。

### Phase 8：i18n 与硬编码 → `reports/08-i18n.md`

1. Text 全量（**全包树，原 ui/-only 命令实测命中 0，非法**）：
   ```bash
   grep -rnE '(^|[^[:alnum:]_])Text[[:space:]]*\(' --include='*.kt' app/src/main/java/cn/vectory/syncplayer
   grep -rnP 'Text\s*\(\s*(?:text\s*=\s*)?"' --include='*.kt' app/src/main/java/cn/vectory/syncplayer   # 同行字面量辅助
   ```
   判定标准（机械化）：字面量含 CJK 字符，或 ≥2 个英文单词且非纯格式串（如 `"%d"`、`"yyyy-MM-dd"`）→ P2；无法机械判定 → CANDIDATE。
2. 同法建清单并审计：`Toast` / `Snackbar` / `Dialog` 文案、`contentDescription = "` 字面量。
3. `grep -rnE 'throw \w+Exception\("' --include='*.kt' app/src/main/java/cn/vectory/syncplayer/data` —— Repository 异常 message 用途逐处核对（进 UI → P1；仅诊断 → P2/CANDIDATE）。
4. strings.xml 完整性：按铁律 8 抽 10 个 UI key，核对 `values/strings.xml` 与 `values-zh*/`（如存在）成对。

### Phase 9：代码卫生 → `reports/09-hygiene.md`

1. `grep -rnE 'TODO|FIXME|HACK|XXX' --include='*.kt' app/src` 全清单（各带一行上下文说明）。
2. 超长函数（两步机械法）：对 >400 行文件逐个：
   ① 只定位 **fun 起点**（必须支持多修饰符叠加——旧单修饰符正则实测漏掉 7 处 `private suspend fun`，且会把主构造器 `val` 参数误当成员起点）：
   ```bash
   grep -nE '^([[:space:]]{4})?((public|private|protected|internal|open|final|abstract|override|tailrec|operator|infix|inline|external|suspend)[[:space:]]+)*fun[[:space:]]+' <file>
   ```
   ② 相邻起点差 >80 行仅是**候选**；每个候选必须打开源码确认该函数的结束大括号与真实行数，确认 >80 行才立 P2。起点差不能直接当函数长度。已知重点：SyncEngine.kt(1025)、FileOperationsUseCase.kt(610)。
3. 重复模式：对比 `VideoMetadataExtractor` vs `ImageMetadataExtractor`、sftp/ vs smb/、Video vs Image 的 ViewModel/UseCase 对，找 ≥15 行近似块 → P2 + 双方位置。
4. `debug/` 包用途与 release 构建归属（`grep -n "debug" app/build.gradle.kts` 核对 buildType/sourceSet）。
5. 疑似未引用符号：对候选文件枚举**顶层 class/object/interface/typealias 符号**，逐个 `grep -rnw '<Symbol>' --include='*.kt' app/src/main`，**剔除符号自身声明行后**统计其余引用（直接搜文件名通常只命中自身，非法）；零外部引用 → CANDIDATE，且须先排查 Hilt/Room/Manifest/反射/序列化入口（不判 P）。

### Phase 10：配置与依赖 → `reports/10-config.md`

1. 读 `app/build.gradle.kts` + `gradle/libs.versions.toml`（如存在）+ 根 `build.gradle.kts`：列依赖版本总表、实际解析来源、是否符合 architecture.md 集中管理约束。**"是否最新版本/是否存在 CVE"一律 U- UNKNOWN**（禁联网、无带日期的本地权威证据时不可复现），**禁止凭训练知识下结论、禁止编造 CVE**。
2. Manifest：
   ```bash
   grep -nE 'uses-permission|allowBackup|dataExtractionRules|exported|grantUriPermissions|readPermission|writePermission' app/src/main/AndroidManifest.xml
   grep -rnE 'Manifest\.permission\.|android\.permission\.' --include='*.kt' app/src
   ```
   权限逐条对代码佐证；`allowBackup` 按**数据暴露路径分析**定级（EncryptedSharedPreferences/Room DB/媒体密文哪个会被备份、备份出沙盒是否可解密），**不自动 P1**，分析不清 → CANDIDATE/U-。
3. `app/proguard-rules.pro`（如存在）：keep 规则 vs `grep -rnE 'Class\.forName|@Keep' --include='*.kt' app/src/main` 反射点交叉核对。
4. Room schema 导出、destructive migration（`dropAllTables=true`）与 conventions.md Database 条款核对。

### Phase 11：汇总 → `reports/summary.md`（终产物，自包含）

**只读 `reports/00-10` 与 progress.md 汇总，禁止引用记忆中未落盘内容。**

1. **执行摘要**：≤10 句总体判断 + 静态审计 NOT_RUN 免责声明（原文照抄 Phase 0 第 6 条）。
2. **Top 10 风险**：P0 → 高影响 P1 排序，每条内嵌完整 canonical Finding（含证据字段）。
3. **唯一发现索引表**：`| ID | 严重度 | 确定性 | 规则 | 位置 | 一句话 |`（**每个 canonical ID 恰好一行**；REF 不出现）。按严重度+Phase 排序。
4. **维度评分**：架构/安全红线/错误处理/并发/资源/契约/测试/i18n/卫生/配置 各 1-5 分 + 两行依据（引用各 Phase 锚点统计，如"Phase 3 审计 202 处 catch，吞异常 N 处"）。
5. **统计核对**：Σ各 Phase 唯一发现数（去重后）== 本表行数；CANDIDATE 为 P0/P1/P2 内部交叉统计（Σ CONFIRMED+CANDIDATE = 唯一发现总数）。不等 → 回修再写。
6. **U-/CANDIDATE 汇总**：集中列出供人工裁决，附 spec/红线原文引用。

### Phase 12：自检复核 → `reports/verification.md`（不通过则修正后重跑本 Phase）

0. **快照复核**：重跑 `git rev-parse HEAD` 与 `git status --short`，与 progress.md 全局节比对。不一致 → 全报告标 INVALID，写明原因，停止。
1. **P0 全量重验**：每条 P0 重新打开文件核对引用片段**逐字一致**；重跑复现命令核对 exit code 与命中数。不一致 → 修正原报告并记录修正过程。
2. **P1/P2 确定性抽验**：canonical Finding 按 `位置（路径:行号）` 升序排序（与铁律 8 排序键一致），铁律 8 算法（含 L=0 与 effectiveN=min(L,N) 规则）取样 `N = max(10, ceil(总数 × 20%))`，**样本 ID 全列**，逐条同法核对。
3. **锚点重扫**：重跑每个 Phase 报告头记录的主扫描命令，核对 scan_total 与记录一致。不等 → FAILED。
4. **对账复核**：各 Phase 锚点表 `scan_total = adjudicated + excluded_scope_or_FP + unknown`；progress.md / Phase 头部 / summary 三处计数及字段名两两一致。
5. **字段完整性（分类型校验，严禁要求补虚构字段）**：`grep -nE '^### ' reports/*.md` 列全部条目，先读每条的 `- 类型:` 行再按必填集校验：PRESENT=类型/位置/代码/问题/复现/建议；ABSENCE=类型/期望/检查范围/证据/问题；UNKNOWN=尝试/结果/阻塞/恢复条件；CHECK=期望/检查范围/证据/判定。缺字段 → 补**真实**内容；ABSENCE/UNKNOWN 本来就没有"位置/代码"字段，不算缺失，**禁止为它们虚构位置或代码**。
6. 末尾结论：`复核通过/未通过 + 重验条数 + 修正条数 + 样本清单`。全绿后 progress.md 加一行 `ALL-DONE`。

---

## E. 禁止事项（重申）

- 禁止修改 `reports/` 以外任何文件；禁止 git 写操作（commit/checkout/stash/add 等）；禁止 gradle；禁止网络。
- 禁止无证据结论；禁止把 CANDIDATE 写成 CONFIRMED；禁止 PRESENT 缺三要素；禁止 ABSENCE 虚构代码片段；禁止跳过 Phase 12。
- 禁止把 grep exit 2（错误）当零命中；禁止使用 `[\u4e00-\u9fa5]` ERE（GNU grep 不支持，须用 `-P` 与 `\x{4E00}-\x{9FFF}`）；禁止 `wc -l {} +` 形态（total 行污染）。
- 禁止在报告粘贴超过 5 行连续代码（引用 ≤3 行；更多上下文给起止行号让复核者自查）。
- 禁止汇总时凭记忆补充；禁止自选"方便"样本（一律铁律 8）。
- 禁止凭训练知识下依赖版本/CVE 结论（一律 U-）。

## F. 完成标志

`progress.md` 存在 `ALL-DONE`，且 00–10、summary、verification 共 13 份文件齐全。完成后输出一行：
`审计完成：P0=<n0> P1=<n1> P2=<n2>（其中 CANDIDATE=<nc>）；U=<nu>，详见 reports/summary.md`
