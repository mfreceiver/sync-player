# 修复计划：Settings / Security Lane（BUG-003 / BUG-004 / BUG-005 / BUG-014）

> 范围：4 个 BUG 的窄聚焦修复计划。仅本计划所列文件允许修改。
> 核对基准：源码实际核对于 commit `2f785ce`（`docs(audit): round-2 deep bug-hunting report`）。
> 缺陷来源：`todo.md`（权威，仅收录 R1 的 BUG-003/004/005）+ `docs/automatic/20260905-0012_bug-hunting.md`（R1，@b716dab）+ `docs/automatic/20260905-0114_bug-hunting.md`（R2，@caad832，BUG-014 唯一来源）。
> 禁改声明：本计划独立设计，未参考已否决的 `docs/ocmar/plans/2026-09-05-comprehensive-bug-repair.md`（未打开、未复用）。
> 修订 v2（2026-09-05）：按 Oracle 门禁评审（FAIL 7.8/10）7 项意见原地修订——BUG-014 缓存版本化发布协议（§3.2③）+ 冷启动有界等待（§3.2②）；refresh 代次失效（§2.2）；退避函数编译修正（§1.2）；虚拟时间/测试矩阵修正（§1.3/§2.3/§3.3）；删 DAO 主线程违规结论、认证矩阵拆分 DEVICE_CREDENTIAL（§2.5/§3.2/§7）。已核实的骨架（retryWhen 方向、三函数守卫、仓库安全默认 false、写域边界）不变。
> 修订 v4（2026-09-05）：处理三轮复审（FAIL 9.2/10）唯一阻断项——T4a/T4b 补成功写入桩：两用例 Arrange 显式先调 `setUpMocks()` 基线、显式加 `setMediaStorageTreeUri("content://tree/external") returns Unit`；Assert₁ 增该 setter 的 `coVerify(exactly=1)`（成功桩与调用验证结合，建立 B 成功完成写调用的前提，堵住「B 写失败被 catch 吞掉仍假绿」——单独调用次数只证调用发生）；补红阶段归因（T4a 须红在 Assert₂ 旧结果覆盖、不得红在 MockK 未配置异常；T4b 语义红以「保留异常守卫、仅移除 catch 内代次检查」变异验证）（§2.3 脚手架注/T4a/T4b）。
> 修订 v3（2026-09-05）：按二轮复审（FAIL 8.0/10）剩余 5 项 + 申诉裁决（a 接受设计但须修 T14 与边界表述 / b 方案可行但先修锁外快照原子性 / c 接受并补准确表述 / d 拒绝）原地返工——③② 缓存协议收口为同锁协议单入口（「需冷读」判定+generation 快照融合入同一非挂起临界区、条件检查与发布同锁、`publishCache` 唯一入口+`expectGeneration` 条件模式、新增 T12a 覆盖另一交错顺序，§3.2③②）；T14 改专用可清理线程隔离+测试线程有界等待+「临时移除生产超时」变异验证，并收窄「主线程最多阻塞 2s」绝对保证为协作式取消边界表述（§3.2 有界等待取舍、§3.3/§3.4/§3.5）；T8 恢复段每次 `source.value` 变更后、生命周期事件前补 `runCurrent()` 同步点（§3.3 T8）；T4a/T4b 显式桩住目录显示名解析边界（mockkObject 作用域自清理）+「先断言 C 已发布成功态、再释放 A」栅栏顺序（§2.3）；T4 删 `usableSpaceLabel` 相等断言（裁决 d），保留布尔断言+补调用次数证据（§2.3 T4）；§2.2 补代次「检查到赋值非原子、当前 Main confinement 下成立」边界表述（裁决 c）。

## 0. 公共设计决策：为什么是 `retryWhen`，而不是两份报告推荐的裸 `.catch`

四个 BUG 同根：`UserPreferencesRepository`（`UserPreferencesRepository.kt:20`，`preferencesDataStore(name = "player_prefs")`）暴露的所有 Flow 都是裸 `dataStore.data.map { ... }`（如 `:96-98` 的 `secureLockEnabled`），而 DataStore 官方契约是**读 IO 故障以 `IOException` 形式抛给收集者**（含 `CorruptionException`，它是 `IOException` 子类）。消费端全部未做恢复处理，导致三种症状：收集协程永久死亡（BUG-003/014-A）、launch 协程 uncaught 崩溃进程（BUG-004/005/014-B）。

修复位置有两级选择，本计划**全部选消费端（ViewModel / Manager）级别**，理由：

1. **仓库级统一 `.catch { emit(emptyPreferences()) }`（DataStore 官方文档模式）被否决**：`emptyPreferences()` 会让 `secureLockEnabled` 落回默认值 `false`（`UserPreferencesRepository.kt:97`）。对安全开关而言"读故障 → 视为未启用"是 **fail-open**，恰好制造 BUG-014 要消灭的静默解锁面。官方模式只对非安全键安全，不能全局套用。
2. **裸 `.catch`（无 emit）不能恢复连续收集**：`Flow.catch` 的语义是——若 action 不 emit，下游流**正常 complete**。对 `init` 里的 `combine().collect` 而言，complete = 收集协程正常返回 = 状态照样永久冻结（只是不再走 catch 分支打日志）。**即 R1/R2 报告推荐的 `.catch` 无法通过报告自己建议的回归测试**（"IOException 后 pref 变更仍要被看到"要求下游仍活着）。
3. **`.catch { emit(lastKnown) }` 同样不能恢复**：emit 一次后流仍会 complete，collector 终结，后续真正的偏好变更（写成功、DataStore 重发）永远收不到——R2 报告 BUG-014 推荐的此方案在其推荐的 E3a 回归（流 `emit(false)` → IOException → pref=true → ON_START 应 `isLocked==true`）下不成立。

因此连续收集场景统一使用 **`retryWhen { cause, attempt -> if (cause is IOException) { delay(backoff); true } else false }`**：`retryWhen` 会**重新订阅上游**（`combine` 的全部上游流、`dataStore.data` 会重读），瞬态故障自愈、持久故障按退避重试不忙转；非 `IOException`（含 `CancellationException`）原样传播，不吞、不重试。一次性读取场景（`first()`）用**窄 catch `IOException` + 显式降级语义**（BUG-014 冷启动为 fail-closed **且有界等待**——见 §3.2②，无界 `runBlocking` 不作为残余风险保留；BUG-004 为降级状态）。退避策略按消费场景独立定义、不共享常量与实现：**Settings 用指数退避 1s→2s→4s→…→30s 封顶**（§1.2 `ioRetryBackoffMs`），**AppLock 用固定 5s**（§3.2 `LOCK_PREF_RETRY_BACKOFF_MS`）——锁的恢复节奏与设置页无耦合，公共描述不混称两者。

`CancellationException`（`kotlinx` 别名，继承 `IllegalStateException` 而非 `IOException`）天然不进任何 `is IOException` 分支——红线 3（CE 先传播）在所有改动点结构性成立，并由守卫测试锁定。

## 1. BUG-003：SettingsViewModel 偏好收集被单次 IOException 永久冻结

### 1.1 现状与根因（源码核对 @2f785ce）

`app/src/main/java/cn/vectory/syncplayer/settings/SettingsViewModel.kt:52-78`，init 中一次性收集四个偏好流：

```kotlin
init {
    viewModelScope.launch {
        try {
            combine(
                userPrefs.volumeBalance,        // :56
                userPrefs.quickRecycle,
                userPrefs.secureLockEnabled,
                userPrefs.mediaStorageTreeUri,
            ) { ... SettingsState(...) }        // :60-67 构造 base
            .collect { base ->                  // :68
                _settingsState.value = base
                refreshUsableSpace()
            }
        } catch (e: CancellationException) {    // :72-73
            throw e
        } catch (e: Exception) {                // :74-76
            Log.e(TAG, "Failed to load settings", e)
        }
    }
}
```

**根因链**：四个上游都是 `dataStore.data.map`（`UserPreferencesRepository.kt:79-100`）→ 任一次 DataStore 读 IO 故障抛 `IOException` → `combine` 取消其余上游并重抛 → 落入 `:74` 通用 catch → 只打日志，协程正常结束 → **`_settingsState` 永久冻结在最后快照**。此后用户在设置页的任何开关（写入实际成功）不再反映到 UI，重启进程才跳变到新值——即 R1 实验 `BH_DataStoreFailurePropagationTest` 复现的 `Frozen instead: SettingsState(volumeBalance=false,...)`。

注意：这是"冻结"不是"崩溃"——所以症状隐蔽；且 `:66` 保留 `usableSpaceLabel` 的写法说明状态是增量覆盖语义，恢复收集后无兼容问题。

### 1.2 修复方案（函数级）

改动一个函数体 + 一个伴生对象辅助函数，均在本文件：

1. 在 `combine { ... }` 与 `.collect { ... }` 之间插入 `retryWhen`（上游 `kotlinx.coroutines.flow.*` 已由 `SettingsViewModel.kt:19` 覆盖导入；**新增 import** `kotlinx.coroutines.delay`、`java.io.IOException`）：

```kotlin
init {
    viewModelScope.launch {
        try {
            combine(...) { ... }
                .retryWhen { cause, attempt ->
                    if (cause !is IOException) return@retryWhen false
                    // BUG-003：DataStore 读 IO 故障是文档化的瞬态面；退避后重订阅
                    // 全部偏好流，防止单次 IOException 永久冻结设置状态。
                    // CancellationException 非 IOException → 原样传播（红线 3）。
                    delay(ioRetryBackoffMs(attempt))
                    true
                }
                .collect { base -> ... /* 原样保留 :68-71 */ }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load settings", e)
        }
    }
}
```

2. `companion object`（`:190-192`）新增退避函数与常量：

```kotlin
private const val SETTINGS_RETRY_INITIAL_MS = 1_000L
private const val SETTINGS_RETRY_MAX_MS = 30_000L
/** 指数退避：1s,2s,4s,8s,16s,30s(封顶)。attempt 先收敛到 [0,5] 再转 Int。 */
internal fun ioRetryBackoffMs(attempt: Long): Long =
    minOf(SETTINGS_RETRY_MAX_MS, SETTINGS_RETRY_INITIAL_MS shl attempt.coerceIn(0L, 5L).toInt())
```

> 编译修正（Oracle #5）：初稿的 `attempt.coerceAtMost(5)` 结果仍为 `Long`，而 `Long.shl` 的位移参数要求 `Int`——**不能编译**。现改为 `attempt.coerceIn(0L, 5L).toInt()`：限幅（防大 attempt 位移溢出）与 Int 转换一步到位。`minOf` 封顶语义不变：attempt=5 时 `1_000 shl 5 = 32_000 > 30_000` → 取 30_000。函数可见性 `private` → `internal` 供 T2a 直测序列断言。

语义决策：
- **只重试 `IOException`**：它是 DataStore 文档化的读故障面（含 `CorruptionException ⊂ IOException`）；其他异常视为代码缺陷，维持现状（通用 catch 记日志后结束），避免把真 bug 重试掩盖成忙转。
- **无限次重试 + 指数退避封顶 30s**：瞬态故障自愈；持久损坏下每 30s 一次磁盘重读，开销可忽略（VM 生命周期内），且损坏被修复后自动恢复——无需引入"重试上限后放弃"的额外状态。
- **重订阅即重读**：`retryWhen` 重订阅时 `combine` 的全部四个上游重新收集，`dataStore.data` 重新读取，`SettingsState` 由真实持久层重建，无陈旧值混入。
- **退避期 UI 表现（已对照 `SettingsScreen.kt` 核实）**：首个快照到达前 `settingsState == null` → 设置页显示加载态（既有行为）；已有快照时退避期间保留旧值展示，属**暂时陈旧**，重订阅成功后自动纠正——不为此新增 UI 状态或文案。

### 1.3 TDD 测试计划

测试文件：**追加**到现有 `app/src/test/java/cn/vectory/syncplayer/settings/SettingsViewModelTest.kt`（沿用其 `UnconfinedTestDispatcher` + `mockkStatic(Log)` + 严格断言调用次数的写法；`returnDefaultValues=true` 见 `app/build.gradle.kts:110`）。

| # | 方法名（backtick 风格随现有类） | 红/绿 | Arrange–Act–Assert |
|---|---|---|---|
| T1 | `init collect survives transient datastore IOException and reflects later change` | **红**（修复前） | **A**：`userPrefs.volumeBalance` 返回可重收集的 flaky 流（计数器：第 1 次收集 `emit(false)` 后 `throw IOException("transient")`；第 2 次收集 `emit(true)`）；其余三个流用 `MutableStateFlow`（默认值）。**Act**：构造 `SettingsViewModel`（init 在 Unconfined Main 立即启动）+ `advanceUntilIdle()`（虚拟时钟消化退避 delay）。**Assert**：`vm.settingsState.value?.volumeBalance == true`（重试后状态反映新值）；`verify(exactly = 0) { Log.e(any(), any(), any<Throwable>()) }`（未落入通用失败分支）。修复前：协程死于 IOException，`volumeBalance` 冻结在 `false`，`Log.e` 恰好 1 次 → 两断言均红。 |
| T2 | `init collect non-IOException keeps current fail-fast semantics` | 绿（行为锁） | **A**：`val gate = CompletableDeferred<Unit>()`；`mediaStorageTreeUri` 返回 `flow { emit(null); gate.await(); throw IllegalStateException("bug") }`，其余 `MutableStateFlow`。**Act₁**：构造 VM + `advanceUntilIdle()`；**同步点断言** `vm.settingsState.value != null`——栅栏保证 combine 的首个完整快照（四流齐发）已投递后才放行异常，消除"异常先于快照构造"的时序不确定（Oracle #6：裸 `emit 后立刻 throw` 不保证 combine 已构造完整快照）。**Act₂**：`gate.complete(Unit)` + `advanceUntilIdle()`。**Assert**：`vm.settingsState.value != null`（最后快照保留）；`verify(exactly = 1) { Log.e(any(), any(), any<Throwable>()) }`。锁定"修复不过度捕获"：非 IO 异常仍走既有 fail-fast 分支。 |
| T2a | `ioRetryBackoffMs returns capped exponential sequence` | 红→绿（新函数直测） | **A**：无 mock，直测 §1.2 的 `internal` 退避函数。**Assert**：`ioRetryBackoffMs(0L..5L) == [1_000, 2_000, 4_000, 8_000, 16_000, 30_000]`（attempt=5：`1_000 shl 5 = 32_000` 被 `minOf` 封顶为 30_000）；`ioRetryBackoffMs(6L) == ioRetryBackoffMs(100L) == ioRetryBackoffMs(Long.MAX_VALUE) == 30_000`（大 attempt 限幅不溢出、不抛）。修复前：函数不存在（且 `coerceAtMost` 版编译不过）→ 红；实现后绿。 |
| T3 | 既有 `init collect cancelled does not run failure log` | 绿（回归锁） | 不改动。已锁定 CE 从 `mediaStorageTreeUri` 抛出时无失败日志；`retryWhen` 对 CE 返回 false 原样重抛，行为不变。 |

要点：flaky 流用 `var collections` 计数 + `flow { }` 构造器（`flow` 块在每次重收集时重新执行，天然模拟"瞬态故障后恢复"）；断言枚举值/调用次数，不触碰任何用户文案。

### 1.4 验收矩阵（本 BUG）

| 命令 | 期望 |
|---|---|
| `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.settings.SettingsViewModelTest'`（实现前，T1/T2a 已写入） | T1 失败（volumeBalance 冻结 false / Log.e=1）；T2a 红（`ioRetryBackoffMs` 未实现——初稿 `coerceAtMost` 版本本身不可编译，见 §1.2 编译修正）；T2 + 既有 CE 回归测试绿 |
| 同上（实现后） | 全部绿（含 T1、T2a） |
| `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin` | BUILD SUCCESSFUL |

### 1.5 风险与守卫

- **红线 3（CE 先传播）**：`retryWhen` 谓词仅对 `IOException` 返回 true；CE 不是 `IOException`，结构上不可能被吞。T3 回归锁。
- **红线 6（敏感信息不进 UI）**：修复不新增任何用户可见文案、不改 `strings.xml`；日志仅 `Log.e(TAG, ..., e)`（项目现有模式，logcat 诊断允许，见 `conventions.md` i18n 节）。
- **性能约定**：退避下限 1s、封顶 30s，无忙转；`delay` 挂起而非自旋；无新增阻塞 I/O（收集本身已是协程挂起语义）。
- **行为面收窄**：`SettingsState` 结构、`refreshUsableSpace()`、各 setter 全部不动；改动只发生在 init 的一个算子插入点。

### 1.6 涉及文件（BUG-003）

| 文件 | 操作 |
|---|---|
| `app/src/main/java/cn/vectory/syncplayer/settings/SettingsViewModel.kt` | 修改：init 插入 `retryWhen`；companion 增加退避常量/函数；新增 2 个 import |
| `app/src/test/java/cn/vectory/syncplayer/settings/SettingsViewModelTest.kt` | 追加 T1、T2、T2a |

## 2. BUG-004 / BUG-005：StorageManagementViewModel 的 DataStore 读写无守卫（进程崩溃）

两个 BUG 同文件、同模式（`StorageManagementViewModel.kt` 的 DataStore 读路径与写路径都无守卫），合并为一个修复单元、分开的红测试。

### 2.1 现状与根因（源码核对 @2f785ce）

**BUG-004（读路径）** `app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt:62-95`：

```kotlin
fun refresh() {
    viewModelScope.launch {
        val treeUriStr = userPrefs.getMediaStorageTreeUri()   // :64 —— 无守卫
        val isExternal = treeUriStr != null
        val externalPath = if (isExternal) resolveTreeDisplayName(context, treeUriStr!!) else null
        val count = withContext(ioDispatcher) {
            try { videoItemDao.getTotalItemCount() } catch ... // :68-73 count 有守卫（fail-closed: Int.MAX_VALUE）
        }
        val usableSpaceLabel = withContext(ioDispatcher) { try { ... } ... }  // :75-82 usableSpace 有守卫
        ...
        _state.value = StorageState(...)                      // :84-93
    }
}
```

`:64` 的 `getMediaStorageTreeUri()`（→ `UserPreferencesRepository.kt:106` 的 `mediaStorageTreeUri.first()`）抛 `IOException` 时直接逃逸 launch。viewModelScope 无 `CoroutineExceptionHandler` → Android 默认 uncaught handler → **进程死亡**（R1 报告真实线程实验确认）。`init { refresh() }`（`:58-60`）意味着进入存储管理页即触发。

**BUG-005（写路径）** `StorageManagementViewModel.kt:97-118`：

```kotlin
fun switchToInternal() {
    viewModelScope.launch {
        syncEngine.storageOperationMutex.withLock {
            val count = videoItemDao.getTotalItemCount()      // :101 —— 无守卫（Room 异常同逃逸）
            if (count > 0) return@withLock
            userPrefs.setMediaStorageTreeUri(null)            // :103 —— DataStore edit，无守卫
        }
        refresh()                                             // :105
    }
}
// switchToExternal(treeUri) :109-118 完全同构（:112 count、:114 set）
```

`setMediaStorageTreeUri`（`UserPreferencesRepository.kt:108-110`，`dataStore.edit`）抛 `IOException`、或 `:101/:112` 的 DAO 查询抛任意异常，均逃逸 → 崩溃。mutex 在 `withLock` finally 中释放，无死锁次生灾害。

对照组：同文件 `resetStorage()`（`:120-165`）已有标准守卫（`:158-163` CE 先 rethrow、通用 catch 置 `resetWarning`），SettingsViewModel 的各 setter（`SettingsViewModel.kt:80-126`）同样全守卫——**refresh/switch 是全文件仅有的两处裸奔点**，todo.md"与同类 setter 一致的捕获+降级"即指对齐此模式。

### 2.2 修复方案（函数级）

仅改 `StorageManagementViewModel.kt` 三个函数 + 一个新增字段，新增 import `java.util.concurrent.atomic.AtomicLong`（其余 `CancellationException`/`update`/`launch` 均已导入 `:21-27`）。

**③（新增）`refresh()` 代次失效（epoch）——发布顺序保证**：`refresh()` 每次被调用领取单调递增代次；任一时刻**只有最新代次的 refresh 允许写 `_state`**。旧代次在成功路径与降级路径的发布点都做代次比对，失配即丢弃。这消灭"过期刷新覆盖"（Oracle #4）：每次 `refresh()` 独立 `launch`、跨多个挂起点（URI 首读 → DAO → 空间查询）后整体发布，switch 的 mutex 不覆盖 refresh——没有代次闸门时，失败后的旧刷新 A 可以在 switch B 成功、其收尾刷新 C 发布之后迟到覆盖（Oracle 交错 a/b 均见下方"消灭的交错"）。

**① `refresh()`**：整段包 try/catch + 代次失效：

```kotlin
private val refreshEpoch = AtomicLong(0)

fun refresh() {
    val epoch = refreshEpoch.incrementAndGet()   // 领取代次；旧 refresh 发布权即时失效
    viewModelScope.launch {
        try {
            // :64-93 原体不动（首读 URI、count/空间的内层守卫均保留）
            ...
            if (refreshEpoch.get() != epoch) return@launch   // 乱序守卫：发布前自检代次
            _state.value = StorageState(...)                 // :84-93 原体
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh storage state", e)
            if (refreshEpoch.get() != epoch) return@launch   // 旧失败不得覆盖新成功、重新禁用操作
            // BUG-004 降级（仅 URI 首读失败路径，见下方分类矩阵）：
            // 状态不可知 → 禁用切换/重置（对存储位置变更 fail-closed，
            // 对齐 :68-73 count 未知按 MAX_VALUE 处理的既有约定）；空间显示 "—"。
            _state.update { it.copy(usableSpaceLabel = "—", canSwitch = false, canReset = false) }
        }
    }
}
```

**② `switchToInternal()` / `switchToExternal()`**：withLock 段包 try/catch，`refresh()` 移到 catch 之后（成功、count>0 早退、失败三种路径都重读真实状态；**收尾 refresh 领新代次，自动作废一切在飞旧刷新**）：

```kotlin
fun switchToInternal() {
    viewModelScope.launch {
        try {
            syncEngine.storageOperationMutex.withLock { /* :100-104 原体不动 */ }
        } catch (e: CancellationException) {
            throw e                                    // CE：跳过 refresh（协程已取消）
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch to internal storage", e)   // BUG-005：不 crash
        }
        refresh()                                      // 内部已自守卫 + 领新代次（发布顺序闸门）
    }
}
// switchToExternal 同构，tag "Failed to switch to external storage"
```

**失败分类动作矩阵**（Oracle #4：明确定义"哪类读失败允许哪些动作"，替代原稿"读失败禁用一切存储变更"的不完整表述——外层 catch 的全禁用**只**覆盖 URI 首读失败路径，不是所有读失败的统一禁用）：

| 失败类别 | 捕获层 | 动作策略 |
|---|---|---|
| URI 首读失败（`:64` `getMediaStorageTreeUri()` 抛，经新外层 catch） | 本修复新增 | `isExternal`/`count` 均不可知 → `canSwitch=false`、`canReset=false`（未知状态禁一切存储变更，fail-closed 对齐红线 2 精神与 `:68-73` 既有注释）；空间标签置未知哨兵 `"—"` |
| DAO 计数失败（`:68` `getTotalItemCount()` 抛） | 既有内层守卫 `:68-73` | count 未知按 `Int.MAX_VALUE`（"可能有文件"）→ `canSwitch=false`；`canReset = count>0 && !syncRunning`（通常 true——reset 自身有独立守卫+互斥，允许发起）。既有 fail-closed 约定，不变 |
| 空间查询失败（`:77` `mediaVolumeProvider.current()`/`usableSpace()` 抛） | 既有内层守卫 `:75-82` | 仅展示降级（`"—"`）；动作门控输入是 `isExternal`/`count`/`syncRunning`，不含空间读数——空间读失败不禁用动作（Oracle #4 指出原稿声称的全禁用与此内层行为矛盾，现按实际门控输入显式定义） |

**消灭的交错**（对应 Oracle #4 的两个场景，均有栅栏测试）：

- 交错 a（失败后的旧刷新 A 先读 internal 并在 DAO/空间挂起点暂停 → switch B 成功切 external、其收尾刷新 C 完成发布 → A 恢复欲发布 internal）：A 的代次 < C 的代次 → A 发布点代次比对失败 → 丢弃，状态保持 external（T4a 复现）。
- 交错 b（旧失败刷新的降级 update 迟到覆盖新成功状态、重新禁用操作）：catch 内同样代次比对 → 旧失败不发布（T4b 复现）。
- mutex 与 refresh 的关系不变：`storageOperationMutex` 只串行化写侧（switch/reset 与同步引擎），refresh 不持锁、以代次保证发布序——不扩大锁范围、不改 sync/DAO（写域红线）。

语义决策：
- **代次而非取消**（Oracle #4 "明确刷新取消/代次失效"二选一，选代次）：`Job.cancel()` 只在挂起点生效，已过全部挂起点的旧刷新照样能跑完并发布——纯取消不充分；发布点代次比对是单一、完备的闸门，且避免取消与 `resetStorage()` 尾部 `refresh()`（`:157`）等既有调用方产生交互新面。
- **代次检查的边界表述（裁决 c 落实）**：`refreshEpoch.get() != epoch` 检查与其后的 `_state.value = ...` 赋值是**非原子**的两条相邻语句（无锁）。该保证当前成立依赖两个结构性前提：`refresh()` 的全部 launch 都在 viewModelScope（Main dispatcher 单线程 confinement）上执行，且检查与赋值之间**无挂起点**——故没有其他 refresh 能在其间交错。这是「检查到赋值非原子、**当前 Main confinement 下成立**」的条件保证而非普遍不变量：若日后 refresh 移出 Main、或在检查与赋值之间插入挂起点，该闸门即失效，须改用锁/赋值后复检协议（本计划不改代码——现行结构满足前提，此处显式记录约束）。catch 分支的两处代次检查与 `_state.update` 同理。
- **写失败不设 `resetWarning`/新错误文案**（偏离 R1 报告建议，见 §6-D2）：`resetWarning` 语义绑定 reset 操作（`:154-156`），复用会混淆；写失败后 DataStore 值未变（edit 原子性），收尾 `refresh()` 重读出的状态即真相——且代次机制保证这份真相不会被旧结果覆盖（"回弹即真相"现在有发布顺序保证）。与 SettingsViewModel 各 setter 的静默降级一致，零新增 strings/i18n 面。
- **`:101/:112` 的 DAO 查询顺势获得覆盖**：外层 catch 在 withLock 之外，锁内任何异常（DAO/DataStore）都进同一降级路径。

### 2.3 TDD 测试计划

测试文件：**追加**到现有 `app/src/test/java/cn/vectory/syncplayer/settings/StorageManagementViewModelTest.kt`（沿用 `StandardTestDispatcher` 注入 ioDispatcher + `advanceUntilIdle()` + `setUpMocks()`/`createVmWith()` 脚手架）。该类未 mock `Log`，靠 `unitTests.isReturnDefaultValues = true`（`app/build.gradle.kts:109-114`）保证 Log 调用安全，因此断言只做状态形状与 `coVerify` 次数。

> **脚手架陷阱（Oracle #6，已核对源码）**：`setUpMocks()` 默认 `coEvery { videoItemDao.getTotalItemCount() } returns 1`（`StorageManagementViewModelTest.kt:72`）——而 switch 的 `if (count > 0) return@withLock`（`StorageManagementViewModel.kt:102/:113`）会提前返回，**写路径根本不可达**（原稿 T5 所称"setUpMocks 返回 count=0"与实际不符）。因此所有须走到写/失败路径的 switch 测试（T5/T5a/T7/T7a）必须**显式覆盖** `coEvery { videoItemDao.getTotalItemCount() } returns 0`；DAO 失败类（T6/T6a）用 `throws` 覆盖默认桩即可。
>
> **外部 URI 脚手架（二轮复审第 4 项，已核对 `StorageManagementViewModel.kt:191-212`）**：T4a/T4b 是本测试文件**首次引入非空 external URI** 的用例。纯 JVM（`isReturnDefaultValues`）下 `resolveTreeDisplayName` 链上的 Android 静态（`toUri()`→`Uri.parse`、`DocumentsContract.*`、`contentResolver.query`）及 `fallbackUriLabel` 内的 `android.net.Uri.decode` 均返回 null/桩值——`decoded.substringAfterLast` 抛 NPE 且从 `catch` 内逃逸（catch 再调同一 fallback 仍逃逸）→ 新刷新 C 走**降级路径**而非成功发布 → 测到的是环境故障而非「过期发布」语义。故两用例必须显式桩住解析边界：整个 Arrange–Act–Assert 包在 `mockkObject(StorageManagementViewModel.Companion) { every { StorageManagementViewModel.resolveTreeDisplayName(any(), any()) } returns "external" ... }` 作用域内（作用域形式自动 unmock——静态桩测试后清理）；且**释放旧刷新 A 栅栏前先断言 C 已发布成功态**（见 T4a/T4b 的 Assert₁），确保测的是过期发布而非环境异常。既有 3 个 reset 测试与其余新用例的 URI 读回均为 null（internal），不触此链，无需桩。
>
> **成功写入桩（三轮复审补充，已核对 `StorageManagementViewModelTest.kt:70-83`）**：`setUpMocks()` 只桩了 `getMediaStorageTreeUri() returns null`，**未桩 `setMediaStorageTreeUri()`**。严格 mock 下 T4a/T4b 的 B 写入将抛 MockK 未配置异常：修复前红错因（环境故障冒充乱序语义）；修复后被新外层 catch 吞掉 → B 实际写失败、C 仍可发布成功态 → 假阳性。故两用例 Arrange 必须先 `setUpMocks()` 再覆盖各自桩，并显式加 `coEvery { userPrefs.setMediaStorageTreeUri("content://tree/external") } returns Unit`；Assert₁ 补该 setter 的 `coVerify(exactly = 1)`（B 成功写入的直接证据）。

| # | 方法名 | 红/绿 | Arrange–Act–Assert |
|---|---|---|---|
| T4（BUG-004） | `refresh datastore read failure degrades state without crashing` | **红** | **A**：`setUpMocks()` 后 `coEvery { userPrefs.getMediaStorageTreeUri() } throws IOException("disk")`。**Act**：`createVmWith(cleanVolume)`（init 即 refresh）+ `advanceUntilIdle()`。**Assert**：`state.canSwitch == false`、`state.canReset == false`（未知状态禁变更——布尔状态形状断言）；**调用次数证据**（二轮复审第 5 项/裁决 d：`StorageState().usableSpaceLabel` 仍属用户可见展示文案，conventions.md 无「引用默认值即可例外」条款，标签相等断言删除；不为测试扩展生产状态模型）：`coVerify(exactly = 1) { userPrefs.getMediaStorageTreeUri() }`（失败恰发生在 URI 首读）、`coVerify(exactly = 0) { videoItemDao.getTotalItemCount() }`、`coVerify(exactly = 0) { mediaVolumeProvider.current() }`（DAO/空间查询均未执行——证明降级态源于 URI 首读失败而非后续阶段）。修复前：IOException 逃逸 → `runTest` 以 uncaught 异常失败 → 红。 |
| T4a（乱序-旧成功覆盖） | `stale successful refresh cannot overwrite newer refresh result` | **红** | **A**：`setUpMocks()`（基线脚手架，三轮复审补——否则 syncEngine.progress 等严格 mock 未配置）后，整个用例包在 `mockkObject(StorageManagementViewModel.Companion) { ... }` 作用域内并 `every { StorageManagementViewModel.resolveTreeDisplayName(any(), any()) } returns "external"`（JVM 脚手架，理由见上方「外部 URI 脚手架」；作用域结束自动 unmock）；`coEvery { userPrefs.getMediaStorageTreeUri() } returnsMany listOf(null, "content://tree/external")`（第 1 次=init 刷新 A 读 internal；第 2 次=switch 收尾刷新 C 读 external）；`coEvery { userPrefs.setMediaStorageTreeUri("content://tree/external") } returns Unit`（成功写入桩——setUpMocks 未桩 setter，见「成功写入桩」注）；`val gateA = CompletableDeferred<Unit>()`；`var daoCalls = 0`；`coEvery { videoItemDao.getTotalItemCount() } coAnswers { daoCalls++; if (daoCalls == 1) gateA.await(); 0 }`（A 的计数读挂起在栅栏，B/C 的调用立即返回）。**Act₁**：`createVmWith(cleanVolume)` + `advanceUntilIdle()`（A 挂在 gateA）；`vm.switchToExternal("content://tree/external")` + `advanceUntilIdle()`（B 成功写、C 完成发布）。**Assert₁（释放栅栏前的围栏——先证 C 走成功发布而非环境降级）**：`state.isExternal == true`、`state.canSwitch == true`（若显示名桩缺失致 C 走 catch 降级，此处立即红——环境故障不冒充乱序语义）；`coVerify(exactly = 1) { userPrefs.setMediaStorageTreeUri("content://tree/external") }`（B 确已成功写入——「C 发布成功态」不能反证「B 写入成功」；与 `returns Unit` 成功桩结合建立该前提，单独调用次数不构成成功证明）。**Act₂**：`gateA.complete(Unit)` + `advanceUntilIdle()`（A 恢复欲发布 internal）。**Assert₂**：`state.isExternal == true`（旧结果未覆盖新结果）。修复前：A 无代次守卫直接发布 internal → Assert₂ 红。**红阶段归因（三轮复审补）**：T4a 须红在 Assert₂ 的旧结果覆盖断言，**不得**红在 MockK 未配置异常（后者说明桩缺失，属环境故障非乱序语义）。 | |
| T4b（乱序-旧失败覆盖） | `stale failed refresh cannot re-disable after newer success` | **红** | **A**：同 T4a——`setUpMocks()` 基线 + `mockkObject(StorageManagementViewModel.Companion)` 显示名桩作用域 + `coEvery { userPrefs.setMediaStorageTreeUri("content://tree/external") } returns Unit`（成功写入桩，理由同 T4a）；`coEvery { videoItemDao.getTotalItemCount() } returns 0`；`val gateA = CompletableDeferred<Unit>()`；`var uriCalls = 0`；`coEvery { userPrefs.getMediaStorageTreeUri() } coAnswers { uriCalls++; if (uriCalls == 1) { gateA.await(); throw IOException("disk") } else "content://tree/external" }`（A 的首读挂起后抛；C 读到 external）。**Act₁**：`createVmWith(cleanVolume)` + `advanceUntilIdle()`（A 挂在 gateA）；`vm.switchToExternal("content://tree/external")` + `advanceUntilIdle()`（B 成功、C 发布良好状态）。**Assert₁（释放栅栏前的围栏）**：`state.canSwitch == true`、`state.isExternal == true`（先证 C 已发布成功态——桩缺失/环境故障在此显红，不与乱序断言混淆）；`coVerify(exactly = 1) { userPrefs.setMediaStorageTreeUri("content://tree/external") }`（与成功桩结合建立 B 成功完成写调用的前提）。**Act₂**：`gateA.complete(Unit)` + `advanceUntilIdle()`（A 首读抛 IOException → 外层 catch）。**Assert₂**：`state.canSwitch == true`、`state.isExternal == true`（旧失败未覆盖新成功、未重新禁用操作）。修复前：A 的 IOException uncaught（红）或降级 update 覆盖 canSwitch=false（红）。**红阶段归因（三轮复审补）**：uncaught 红不能区分守卫缺失；T4b 的乱序断言语义须以变异验证——保留异常守卫（外层 catch 存在）、仅移除 catch 内代次检查 → Assert₂ 须变红（旧失败的降级 update 覆盖新成功态）。 | |
| T5（BUG-005，external 写失败） | `switchToExternal write failure does not crash and keeps state truthful` | **红** | **A**：`setUpMocks()` 后**显式** `coEvery { videoItemDao.getTotalItemCount() } returns 0`（覆盖 `:72` 默认 1——否则 `count>0` 提前 `return@withLock`、写路径不可达、桩永不触发）；`coEvery { userPrefs.setMediaStorageTreeUri(any()) } throws IOException("disk")`。**Act**：`createVmWith(cleanVolume)`（init refresh 完成）+ `vm.switchToExternal("content://tree/primary%3ATest")` + `advanceUntilIdle()`。**Assert**：`state.isExternal == false`（写失败回弹真相——收尾 refresh 读回 null）；`coVerify(exactly = 1) { userPrefs.setMediaStorageTreeUri("content://tree/primary%3ATest") }`（确实尝试过写）。修复前：uncaught → runTest 失败。 |
| T5a（BUG-005，internal 写失败） | `switchToInternal write failure does not crash and keeps state truthful` | **红** | 与 T5 同构（count=0 + `setMediaStorageTreeUri(any()) throws IOException`）。**Act**：`vm.switchToInternal()`。**Assert**：无 uncaught；`state.isExternal == false`；`coVerify(exactly = 1) { userPrefs.setMediaStorageTreeUri(null) }`（Oracle #6：原稿只覆盖 external，internal 写失败无回归）。修复前红。 |
| T6（DAO 计数失败，external 侧） | `switchToExternal db count failure does not crash and degrades` | **红** | **A**：`setUpMocks()` 后 `coEvery { videoItemDao.getTotalItemCount() } throws RuntimeException("room closed")`（该桩同时命中 refresh 内层守卫与 switch 锁内调用）。**Act**：`createVmWith(cleanVolume)` + `vm.switchToExternal("content://tree/x")` + `advanceUntilIdle()`。**Assert**：无 uncaught（测试自身绿即证明）；`state.isExternal == false`、`state.canSwitch == false`（收尾 refresh 内层 count 守卫按 `MAX_VALUE` fail-closed）。修复前：switch 锁内 RuntimeException uncaught → 红。 |
| T6a（DAO 计数失败，internal 侧） | `switchToInternal db count failure does not crash and degrades` | **红** | 与 T6 同构（throws 桩覆盖默认 count=1）。**Act**：`vm.switchToInternal()`。**Assert**：同 T6——无 uncaught、`state.isExternal == false`、`state.canSwitch == false`。修复前红（Oracle #6：internal 侧 DAO 失败无回归）。 |
| T7（CE 守卫，internal 侧） | `switch rethrows CancellationException and skips refresh` | 绿（行为锁） | **A**：同 T5 的 count=0 显式桩（Oracle #6：否则到不了写、CE 桩不触发、测试退化为恒真），但 `setMediaStorageTreeUri(any()) throws CancellationException("cancelled")`。**Act**：`vm.switchToInternal()` + `advanceUntilIdle()`。**Assert**：`coVerify(exactly = 1) { userPrefs.getMediaStorageTreeUri() }`（仅 init 的 refresh 调过；CE 分支 rethrow 后跳过 catch 尾部的 refresh——若实现错误地吞 CE，会变成 2 次而红）。 |
| T7a（CE 守卫，external 侧） | `switchToExternal rethrows CancellationException and skips refresh` | 绿（行为锁） | 与 T7 同构（count=0 + CE 桩）。**Act**：`vm.switchToExternal("content://tree/x")`。**Assert**：`coVerify(exactly = 1) { userPrefs.getMediaStorageTreeUri() }`（Oracle #6：两种 switch 均覆盖 CE；修复前 CE 在协程内即取消语义、refresh 同样被跳过，故修复前后均绿）。 |

### 2.4 验收矩阵（本组）

| 命令 | 期望 |
|---|---|
| `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.settings.StorageManagementViewModelTest'`（实现前） | T4/T5/T5a/T6/T6a 红（uncaught 异常）；**T4a 红在 Assert₂**（旧结果覆盖断言，不得红在 MockK 未配置异常）；**T4b 红于 A 的 IOException 逃逸**；T7/T7a + 既有 3 个 reset 测试绿 |
| 同上（变异验收：保留异常守卫、仅移除 catch 内代次检查） | **T4b 红在 Assert₂**（旧失败降级覆盖新成功态）——隔离验证 catch 发布闸门；恢复检查后转绿 |
| 同上（实现后） | 全部绿 |
| `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin` | BUILD SUCCESSFUL |

### 2.5 风险与守卫

- **红线 2（卷所有权 fail-closed）**：switch 的语义（volumeId 校验、withLock 内重校验 count）**零改动**，只包异常边界；降级态反而把"未知状态下变更存储"显式关闭。密文删除逻辑（resetStorage）不触碰。
- **红线 3（CE 先传播）**：两处 catch 均先 rethrow CE；T7 用 `coVerify` 次数锁定"CE 不落入通用 catch"。
- **红线 6（敏感信息）**：降级不新增任何用户文案（`"—"` 是既有哨兵值，`StorageState.usableSpaceLabel` 默认值，`StorageManagementViewModel.kt:35`）；日志仅异常对象，`resetWarning` 非泄漏契约由既有测试覆盖、不动。
- **性能约定**：无新增 I/O。原稿曾称"`:101/:112` 的 DAO 在主线程调用构成性能违规"——**已纠正并删除**（Oracle #7）：`getTotalItemCount()` 是 **suspend** 查询（`VideoItemDao.kt:222` `suspend fun getTotalItemCount(): Int`），在 viewModelScope（Main）上挂起执行、SQL 由 Room 自有后台执行器完成，**不阻塞主线程**；不能仅凭"Main 协程调用"断定 Room 主线程阻塞 SQL。switch 路径不包 `withContext(ioDispatcher)` 仅与 refresh 的写法风格不一致，非违规，不借本 BUG 扩面（§7 同步删除该条）。
- **行为面收窄**：`StorageState` 结构、`resetStorage()`、`resolveTreeDisplayName()`、`isValid*FileName()` 全部不动。

### 2.6 涉及文件（本组）

| 文件 | 操作 |
|---|---|
| `app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt` | 修改：`refresh()`（守卫+代次失效）、`switchToInternal()`、`switchToExternal()` 三函数体（§2.2 ①②③） |
| `app/src/test/java/cn/vectory/syncplayer/settings/StorageManagementViewModelTest.kt` | 追加 T4/T4a/T4b/T5/T5a/T6/T6a/T7/T7a |

## 3. BUG-014：AppLockManager DataStore 故障面——锁静默失效 / 冷启动主线程崩溃

### 3.1 现状与根因（源码核对 @2f785ce）

`app/src/main/java/cn/vectory/syncplayer/security/AppLockManager.kt`（进程级 `@Singleton`，`SyncPlayerApp.onCreate` → `appLockManager.register()`，`SyncPlayerApp.kt:35`；`MainActivity.kt:54-64` 按 `isLocked` 挂 `LockScreenOverlay`）。

**故障点 1（变体 A：锁静默永久失效）** `AppLockManager.kt:46-54`：

```kotlin
fun register() {
    ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    scope.launch {                                    // scope = SupervisorJob() + Dispatchers.IO (:33)
        userPrefs.secureLockEnabled.collect {         // :49 —— 无任何 catch/重启
            cachedSecureLockEnabled = it
            cacheInitialized = true
        }
    }
}
```

流抛 `IOException`（`secureLockEnabled` 是裸 `dataStore.data.map`，`UserPreferencesRepository.kt:96-98`）→ collector 终结 → 缓存（`:30-31` 的 `@Volatile cachedSecureLockEnabled/cacheInitialized`）**永久过期**。此后用户启用锁（写成功，死 collector 看不见）→ ON_STOP→ON_START（`:66-79`）读到陈旧 `false` → 不上锁。R2 实验 E3a 复现：`assertFalse("app-lock silently disabled after DataStore IOException", m.isLocked.value)` 为绿（复现成功）。另注：该协程死于 `Dispatchers.IO` 上的 uncaught `IOException`，在真机同样可能直接崩溃进程——无论哪种，安全语义都是 **fail-open**。

**故障点 2（变体 B：冷启动主线程崩溃）** `AppLockManager.kt:72-75`：

```kotlin
if (!cacheInitialized) {
    cachedSecureLockEnabled = runBlocking { userPrefs.secureLockEnabled.first() }   // :73 —— 无 try/catch，主线程
    cacheInitialized = true
}
```

首帧 ON_START 且 collector 尚未首发时，`first()` 抛 `IOException` 直接逃逸 `onStateChanged`（主线程回调）→ 崩溃。R2 实验 E3b 复现 `assertNotNull(thrown)`。

**fail-closed 论证基准**：当前两个故障点的行为分别是"fail-open"与"fail-crash"，均违反 todo/Lane 要求"异常/边界下必须 fail-closed，不得 fail-open"。

### 3.2 修复方案（类/函数级）

仅改 `AppLockManager.kt`。分四步（TDD 顺序见 §3.3 的 Step 0；③ 是 ①② 共同依赖的发布协议，故先行声明）：

**Step 0（纯重构，行为不变）**：从 `register()` 抽出可测缝：

```kotlin
fun register() {
    ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    startPreferenceCollection(scope)
}

/**
 * internal 供单测注入测试 scope（register 依赖 ProcessLifecycleOwner，单测不可用，
 * 见 AppLockManagerTest 既有注释）。
 */
internal fun startPreferenceCollection(scope: CoroutineScope) { ... }
```

**③（新增）缓存写入顺序：版本化发布协议（同锁协议单入口，条件模式）**。原稿 ①② 让 collector（IO 线程，`AppLockManager.kt:48-52`）与冷启动（主线程，`:72-75`）各自直写 `:30-31` 的 `@Volatile` 缓存——**两个缓存写入者之间无顺序保护**（Oracle #1，Critical；二轮复审进一步指出：v2 草稿的 `cacheInitialized` 判定与 generation 快照均在锁外，AtomicLong + 两个 `@Volatile` 不构成整体原子快照）。v2 草稿可达的陈旧交错：冷启动在锁外检查 `cacheInitialized==false` → collector 在 synchronized 内已 `incrementAndGet`（generation→1）但尚未写字段时暂停 → 冷启动快照 `genBefore==1`、`first()` 返回旧 `false` → collector 随后写 fail-closed `true`+`initialized=true` → 冷启动进锁发现 generation 仍 1==genBefore → 发布 `false` **覆盖故障态**。修复三要素——判定与快照融合入锁、条件检查与发布同锁、全部写者收敛单入口：

```kotlin
private val cacheWriteLock = Any()
private val cacheGeneration = java.util.concurrent.atomic.AtomicLong(0)

/**
 * 缓存唯一发布入口：先递增 generation 再写字段；发布段为纯内存操作，锁不跨任何挂起点。
 * 无条件模式（默认）供 collector：每次发射/故障都是更新鲜的知识，覆盖即正确。
 * 条件模式（expectGeneration 非空）供冷读：generation 失配即丢弃陈旧值，返回锁内完全发布值的当前有效值。
 */
private fun publishCache(value: Boolean, expectGeneration: Long? = null): Boolean {
    synchronized(cacheWriteLock) {
        if (expectGeneration != null && cacheGeneration.get() != expectGeneration) {
            // 冷读结果已陈旧（快照之后已有写者发布过），先发布者胜；不写任何字段
            return cachedSecureLockEnabled
        }
        cacheGeneration.incrementAndGet()
        cachedSecureLockEnabled = value
        cacheInitialized = true
        return value
    }
}
```

协议性质（为什么闭合全部交错）：
- **判定与快照融合**：冷启动「是否需要冷读」判定与 generation 快照在**同一个** `cacheWriteLock` 非挂起临界区内完成（已初始化则跳过冷读并直接在锁内读回完全发布值，见 ②）；`first()` 仍完全在锁外——**锁不跨任何挂起点**（Oracle #1 约束不变）。
- **中间态结构性消除**：「已递增 generation 尚未写字段」只存在于 `publishCache` 临界区**内部**；判定+快照融合入锁后，锁外代码（冷启动判定、② 的锁决策）结构上不可能观察到该中间态——v2 的「锁外判定/锁外快照」两语句窗口不复存在。
- **条件检查与发布同锁原子**：「generation 未变」判定与「递增+写字段」是同一 synchronized 块内的相邻语句，检查与发布之间无任何写者可插入。
- **单一入口，无第二条直写字段路径**：collector（无条件模式）与冷读（条件模式）都经 `publishCache`——v2 草稿 ② 的锁内直写字段与「publishCache 唯一入口」声明不符的问题已消除，声明现在准确。代价：`publishCache` 返回**有效值**（条件失配时返回先发布者的值），供 ② 的锁决策消费。
- **交错空间穷举闭合**（按锁获取序）：① 冷启动判定+快照完成在前，collector 在冷读期间发布 → 条件模式 generation 失配 → 陈旧值丢弃（T12 真实线程+双栅栏确定性复现）；② collector 发布**完成**在冷启动判定之前（含 v2 的「已递增未写字段」窗口）→ 融合临界区必然看到发布后完整态 `initialized==true` → 整体跳过冷读、锁内读回故障态 true（T12a 复现该顺序的可观测结果）；③ 双侧并发发布 → 锁串行化，后入者要么是条件冷读（失配即弃）要么是无条件 collector（更新鲜知识覆盖，正确）。
- **等价单写者语义**：无需把冷读搬到 IO 线程或引入线程 confinement——上述三要素合起来即达成顺序保证。

**① collector 加 `retryWhen` + 故障期 fail-closed**（`startPreferenceCollection` 内，写缓存一律经 ③）：

```kotlin
internal fun startPreferenceCollection(scope: CoroutineScope) {
    scope.launch {
        userPrefs.secureLockEnabled
            .retryWhen { cause, _ ->
                if (cause !is IOException) return@retryWhen false   // CE/其他异常原样传播（红线 3）
                // BUG-014 fail-closed：读故障期间按"已启用"处理，直到成功读到新值；
                // 绝不让 IO 故障静默关闭应用锁。经 ③ 版本化发布写入；
                // cacheInitialized 置真同时阻断冷启动重复读同一坏源。
                publishCache(true)
                Log.w(TAG, "secureLockEnabled read failed; failing closed", cause)
                delay(LOCK_PREF_RETRY_BACKOFF_MS)
                true
            }
            .collect { publishCache(it) }
    }
}
```

**② 冷启动：融合判定 + 有界同步读 + fail-closed + 版本化条件发布**（`:72-75` 改为下述 helper + ON_START 接线；helper 名示意）：

```kotlin
androidx.lifecycle.Lifecycle.Event.ON_START -> {
    if (hasBeenInBackground || isFirstStart) {
        isFirstStart = false
        if (lockEnabledForStart()) {
            _isLocked.value = true
        }
    }
}

/**
 * ② 冷启动体。临界区一（非挂起）：「需要冷读」判定 + generation 快照融合在同一锁内，
 * 已初始化则跳过冷读并直接在锁内读回完全发布值；first() 完全在锁外；临界区二（publishCache 内）：
 * 条件检查+发布同锁原子。返回值永远是锁内读到的完全发布值（绝不观察发布中间态）。
 */
private fun lockEnabledForStart(): Boolean {
    val genBefore = synchronized(cacheWriteLock) {
        if (cacheInitialized) return cachedSecureLockEnabled   // 温路径：已初始化 → 跳过冷读
        cacheGeneration.get()                                   // 冷路径：快照（与判定同一临界区）
    }
    val value = try {
        runBlocking {
            // 有界等待（Oracle #2）：本地读取超时由 withTimeoutOrNull 转为 null——
            // TimeoutCancellationException 不落入 CancellationException 传播面；
            // 外部取消仍原样穿透（红线 3）。
            withTimeoutOrNull(coldStartReadTimeoutMs) { userPrefs.secureLockEnabled.first() }
        } ?: true                                   // 无首值/本地超时 → fail-closed
    } catch (e: CancellationException) {
        throw e                                     // 外部取消：原样传播，不得吞（红线 3）
    } catch (e: IOException) {
        // BUG-014：偏好不可读时按已启用处理（fail-closed），不得 fail-open，也不得崩溃主线程
        Log.w(TAG, "secureLock unreadable at cold start; failing closed", e)
        true
    }
    return publishCache(value, expectGeneration = genBefore)  // 唯一入口：失配弃陈旧值并返回有效值
}
```

新增：import `java.io.IOException`、`android.util.Log`、`kotlinx.coroutines.delay`、`kotlinx.coroutines.flow.retryWhen`、`kotlinx.coroutines.withTimeoutOrNull`、`java.util.concurrent.atomic.AtomicLong`、`androidx.annotation.VisibleForTesting`；companion 增 `private const val TAG = "AppLockManager"`、`private const val LOCK_PREF_RETRY_BACKOFF_MS = 5_000L`（固定 5s——与 §1.2 Settings 指数退避是两套独立策略）、`private const val COLD_START_READ_TIMEOUT_MS = 2_000L` 与 `@VisibleForTesting internal var coldStartReadTimeoutMs = COLD_START_READ_TIMEOUT_MS`（测试注入点，控制方式见 §3.3 测试调度说明）。

**有界等待取舍（Oracle #2 + 裁决 a）**：原稿把无界 `runBlocking` 留作残余风险——不可接受："不发首值"不只来自内核挂死，也可能来自上游初始化/调度/同步等待；`_isLocked` 初值 `false`、真正置锁在阻塞读取之后，无响应即事实 fail-open。现纳入设计：`withTimeoutOrNull` 有界等待，超时语义 = 本地读取失败 = 按已启用 fail-closed（与 IOException 路径同语义，不引入"超时后开锁"面）。**边界表述（裁决 a 落实：保留 2s 有界同步等待设计，但收窄绝对保证）**：2s 是协作式取消协议的**设计目标**而非硬实时截止——挂起原因为**可取消挂起**（DataStore 读不首发/`awaitCancellation` 等规范挂起面，T14 即锁定此面）时 `withTimeoutOrNull` 按时返回；若底层读取以**非协作方式阻塞线程**（真磁盘级 hang 死在阻塞调用里），协程超时无法抢占，冷启动线程可能阻塞至该调用返回。残余面由「无界挂死未处理」收窄为「极端非协作阻塞可能超过 2s 设计上界」，**不声称一般磁盘 hang 已无条件消除**。正常冷启动 DataStore 首读为几十 ms 量级；2s 设计上界低于 5s 输入派发 ANR 阈值。**本地读取超时**（`withTimeoutOrNull` 转 null，走 fail-closed）与**外部取消**（CE 原样传播）显式分界，不广泛吞 CancellationException。

**fail-closed 行为矩阵（逐路径）**：

| 路径 | 修复前 | 修复后 |
|---|---|---|
| 收集中 IOException（变体 A：曾读 false，随后故障，用户启用锁） | 缓存冻结 false → ON_START 不上锁（**fail-open**） | retryWhen 重订阅；故障期 `publishCache(true)` → ON_START 上锁；恢复读到 true → 保持上锁；若真相是 false → 缓存回落 false，后续 ON_START 不再上锁（E3a 推荐Regression 直接覆盖；T8 另含恢复后真实 false 不重锁断言） |
| 冷启动读 IOException（变体 B） | 主线程崩溃 | 有界读 + 窄 catch → 缓存 true → 首个 ON_START 上锁（fail-closed）；进程下次启动若源恢复则回真值 |
| **冷读与 collector 故障发布交错**（Oracle #1 新增行） | `@Volatile` 无顺序保证：冷读旧 false 可覆盖故障态 true → 本次 ON_START 不上锁、退避期缓存错保持 false | 同锁协议单入口（判定+快照融合、条件检查+发布同锁，§3.2③）：发布发生在冷读**期间** → generation 失配丢弃陈旧值 → 首个 ON_START 仍上锁、退避期保持 true（T12）；发布**完成**在冷启动判定**之前**（含 v2「已递增未写字段」窗口）→ 融合临界区必见 initialized=true → 跳过冷读、锁内读回故障态（T12a） |
| 无首值 / 磁盘 hang 不抛不返回 | 主线程**无界**卡死 | **有界等待**（默认 2s，可注入）：超时 = 本地读取失败 → fail-closed 上锁；上界对可取消挂起面成立（T14 专用线程隔离锁定），非协作阻塞可超 2s——协作式取消 ≠ 硬实时（边界见 §3.2 有界等待取舍。原稿将本行列为"不变+残余风险"，已按 Oracle #2 纳入设计） |
| 进程重建 | `@Volatile` 缓存随进程消失 | 重建后走冷启动 ②（有界+fail-closed）或 collector 首发 ①：读成功=真值；读失败/超时=fail-closed true（T15） |
| 生物识别弹窗错误/冷却（硬件可用但验证失败） | LockScreenOverlay `onError` → failCount++，5 次后 30s 冷却（`LockScreenOverlay.kt:35-42,45-57`），有"重试验证"按钮（`:104-117`），成功即 `unlock()`（`:64/:111`） | **不变**。fail-closed 造成的"过度上锁"对该路径可恢复（重新验证即可） |
| **无生物识别、但有设备凭据**（Oracle #7 拆分） | （原稿未区分） | `BiometricAuthManager` 的 `authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL`（`BiometricAuthManager.kt:22-24`；`:83` `setAllowedAuthenticators` 传入 PromptInfo）→ 可用锁屏 PIN/图案/密码验证解锁；fail-closed 过度上锁**可自恢复**（原稿"删指纹只能重录指纹恢复"不准确，已纠正） |
| **无任何可用认证**（无生物识别**且**无设备凭据） | （原稿未区分） | 无法通过任何验证 → **保持锁定**；需先在系统设置录入生物识别或设置设备凭据后方可解锁；**不得声称重试即可恢复**（T16 可执行锁定"无 unlock() 不解锁"） |
| CancellationException | 传播 | 传播（①谓词返回 false；②显式 CE rethrow 先于 IOException catch；`withTimeoutOrNull` 只把**自身**超时转为 null、不吞外部 CE）——T11 分界锁定 |
| 非 IOException 异常 | collector 协程死亡 | 不变（只有 IOException 是 DataStore 文档化故障面；其余按 fail-fast 处理，防止掩盖代码缺陷） |

**过度上锁代价分析**：故障期把缓存视为 true，若用户实际未启用锁，下次前台会出现一次"多余"的验证——安全侧误差，故障恢复后自动回归。代价按认证面拆分（Oracle #7）：**有设备凭据**（PIN/图案/密码）时走 DEVICE_CREDENTIAL 验证一次即解锁，代价最低；**无任何可用认证**时停留在锁屏，需先在系统设置配置认证，这是 todo 方向明示接受的代价。FLAG_SECURE 常开（`MainActivity`，`AppLockManager.kt:58-62` 注释）独立覆盖切换瞬间的截屏/最近任务泄漏，与锁状态正交。取舍符合 todo.md 对 BUG-014 的明示方向"读失败按 fail-closed（视为已启用）处理更符合安全语义"。

### 3.3 TDD 测试计划

测试文件：**追加**到现有 `app/src/test/java/cn/vectory/syncplayer/security/AppLockManagerTest.kt`（沿用其直构 `AppLockManager(context, userPrefs)` + 驱动 `onStateChanged` 的写法；新增 import 按需：`kotlinx.coroutines.flow.flow/emitAll`、`kotlinx.coroutines.awaitCancellation`、`kotlinx.coroutines.CompletableDeferred`、`kotlin.concurrent.thread`、`kotlinx.coroutines.test.runCurrent/advanceTimeBy`、`java.io.IOException`、`kotlinx.coroutines.CancellationException`、`io.mockk.every`、`org.junit.Assert.assertEquals`、`java.util.concurrent.Executors`/`TimeUnit`/`TimeoutException`——T14 专用线程隔离，见测试调度说明）。

**测试调度说明（Oracle #3/#2，执行本表前必读）**：

- **虚拟时间与重订阅推进**：固定序列 `runCurrent()` → `advanceTimeBy(5_000)` → `runCurrent()`。coroutines 1.10.1 语义：`advanceUntilIdle()` 在**只剩 backgroundScope 任务时即停止**，不会消化 5 秒退避——原稿 T8"advanceUntilIdle 消化退避"的理由错误；原稿 T9"该配置下 advanceUntilIdle 会无限推进/死循环"的理由同样不成立（它不会死循环、只是提前停）。两个理由均已废弃，改为显式推进。且 `advanceTimeBy` **不执行恰好落在目标时刻的任务**，故末尾必须再 `runCurrent()` 才执行退避到期后的重订阅。
- **冷启动等待的控制**：外层 `runTest` 的虚拟时钟只作用于测试调度器；② 内层 `runBlocking` 建立独立事件循环、使用**真实单调时钟**——虚拟时间不会渗透进去。因此所有触及冷启动等待的测试统一经 `coldStartReadTimeoutMs` 注入控制：超时类（T14）设小值（100ms 真实时间）使等待确定且快；交错类（T12）设大值（60s）防止栅栏等待被超时打断。
- **T12 的冷启动调用放在真实 `thread {}` 而非测试线程**：`runBlocking` 阻塞调用线程，若在测试线程上调用将死锁测试调度器（collector 的 background 任务无法推进）。T12a 的 ON_START 走温路径（锁内判定+读，非阻塞），可在测试线程直接调用。
- **T14 的冷启动调用放在独立可清理的单线程 executor**（二轮复审第 2 项）：若在测试线程直接调 `onStateChanged`，实现前的无界 `runBlocking` 永不返回时外层 `runTest` 驱动（与测试线程同体）一并挂死——**`dispatchTimeoutMs` 不是线程看门狗**（它只约束测试调度器自身任务的派发耗时，救不了同线程阻塞）。正确形态：`pool.submit { manager.onStateChanged(mockk(), ON_START) }` 后测试线程 `future.get(5, TimeUnit.SECONDS)` 有界等待；超时立即以 AssertionError 失败并 `shutdownNow()` interrupt 回收工作线程（`runBlocking` 把线程 interrupt 转为协程取消，`awaitCancellation`/`first` 等可取消挂起点会响应），`awaitTermination` 断言无线程残留；工作线程内异常经 Future 包装为 `ExecutionException` 传回测试（真实原因浮出，不被吞）。

实施顺序（严格 TDD）：
1. **Step 0 重构**（§3.2 抽缝）+ `coldStartReadTimeoutMs` 注入 var（此时尚未被生产路径引用、零行为变化——提前与测试同盘，使 T12/T14 红阶段可编译）单独提交粒度：实现后**既有 2 测试必须仍绿**（冷启动真值路径 `runBlocking.first()` 未动）。
2. 写 T8–T16（含 T12a）→ 跑红（红：T8/T9/T10/T12/T12a/T14/T15；恒绿守卫：T11/T13/T16——守卫测试与实现同步编写）。
3. 实现 ③①② → 全绿。

| # | 方法名 | 红/绿 | Arrange–Act–Assert |
|---|---|---|---|
| T8（E3a 回归 + 恢复证据） | `collector survives transient IOException, resubscribes, and follows recovered source` | **红**（Step 0 后实现前） | **A**：`val source = MutableStateFlow(false)`；`var collections = 0`；`every { userPrefs.secureLockEnabled } returns flow { collections++; if (collections == 1) { emit(false); throw IOException("DataStore read corruption") } else emitAll(source) }`。**Act**：`manager.startPreferenceCollection(backgroundScope)`；`runCurrent()`（首轮：emit(false) → 缓存 false → throw → 谓词 fail-closed 发布 true → 挂起 delay(5s)）；`advanceTimeBy(5_000)`；`runCurrent()`（执行重订阅：collections=2，`emitAll(source)` 投递 source 当前值 false → 缓存回落 false）。**Assert（防假阳性，Oracle #3）**：`assertEquals(2, collections)`——原稿只断言 `isLocked==true`，"故障缓存置 true 后永久停止收集"的错误实现同样通过；订阅计数是重订阅发生的直接证据。随后行为断言（二轮复审第 3 项：**每次 `source.value` 变更后、生命周期事件前显式 `runCurrent()`**——StandardTestDispatcher 下 StateFlow.value 赋值仅调度 emitAll collector 的续体、不立即执行，直接发 ON_STOP/ON_START 会让首次上锁断言在未消费的旧缓存上假失败；写 false 场景同病）：置 `source.value = true` + `runCurrent()`（collector 消费 → `publishCache(true)`）→ ON_STOP/ON_START → `assertTrue(manager.isLocked.value)`；再置 `source.value = false` + `runCurrent()`（缓存回落 false）+ `manager.unlock()`（模拟认证成功）→ ON_STOP/ON_START → `assertFalse(manager.isLocked.value)`（**恢复源保持存活继续投递真实值**——不因陈旧故障缓存重新上锁）。实现前：collector 死于 IOException、collections==1、不锁 → 红（与 R2 E3a 实验同构，去除 Robolectric/register/200ms 实等依赖）。 |
| T9（故障期 fail-closed） | `during datastore outage lock preference is treated as enabled` | **红** | **A**：`every { userPrefs.secureLockEnabled } returns flow { throw IOException("corrupt") }`（恒抛）。**Act**：`startPreferenceCollection(backgroundScope)` + `runCurrent()`（精确执行首轮失败：谓词 fail-closed 发布 true → 挂起 delay(5s)。推进理由已按 Oracle #3 纠正：原稿"禁止 advanceUntilIdle 因死循环"不成立——advanceUntilIdle 在只剩 backgroundScope 任务时只会**提前停**、既不死循环也不消化退避；选 `runCurrent` 是因为它精确表达"只跑首轮"的意图、不依赖推进边界语义）；`onStateChanged(ON_STOP)`、`onStateChanged(ON_START)`。**Assert**：`assertTrue(manager.isLocked.value)`；测试正常结束（backgroundScope 结束时取消重试协程，无 uncaught 泄漏）。实现前：collector 已死、缓存未初始化 → ON_START 走 runBlocking → 恒抛流 → 测试以 IOException 失败 → 红。 |
| T10（E3b 回归） | `cold start read failure is fail-closed not crash` | **红** | **A**：不调用 `startPreferenceCollection`（冷启动）；`every { userPrefs.secureLockEnabled } returns flow { throw IOException("corrupt") }`。**Act**：`manager.onStateChanged(mockk(), ON_START)`。**Assert**：不抛（方法正常返回）且 `assertTrue(manager.isLocked.value)`（IOException 从 `first()` 穿过 `withTimeoutOrNull` 抛出、由 ② 外层窄 catch 收敛为 fail-closed）。实现前：runBlocking 抛 IOException 逃逸 → 测试红（即 E3b）。 |
| T11（CE 守卫/分界） | `cold start rethrows CancellationException` | 绿（守卫） | **A**：冷启动 + `returns flow { throw CancellationException("cancelled") }`。**Act/Assert**：`assertFailsWith<CancellationException> { manager.onStateChanged(mockk(), ON_START) }`——锁定 ② 的 catch 只收 IOException；且 `withTimeoutOrNull` 只把**自身**超时转为 null，流内抛出的 CE 原样穿透 runBlocking（**本地超时与外部取消在此显式分界**，红线 3；T14 覆盖前者，本测试覆盖后者）。修复前后均应绿。 |
| T12（版本化发布交错，Oracle #1） | `stale cold read cannot overwrite collector fail-closed during interleaved outage` | **红** | **A**：`var collections = 0`；`val outageGate = CompletableDeferred<Unit>()`、`val coldStartGate = CompletableDeferred<Unit>()`、`val coldStarted = CompletableDeferred<Unit>()`；`every { userPrefs.secureLockEnabled } returns flow { collections++; if (collections == 1) { outageGate.await(); throw IOException("outage") } else { coldStarted.complete(Unit); coldStartGate.await(); emit(false) } }`；`manager.coldStartReadTimeoutMs = 60_000`（大值防栅栏等待被真实超时打断，见测试调度说明）。**Act**：`startPreferenceCollection(backgroundScope)` + `runCurrent()`（collector 第 1 次收集挂起在 outageGate，尚未发布任何值）；`val coldThread = thread { manager.onStateChanged(mockk(), ON_START) }`（真实线程模拟主线程冷启动；锁内判定+快照已完成、generation=0，其 `first()` 即第 2 次收集、挂起在 coldStartGate）；`coldStarted.await()`（确定性确认冷读已在飞）；`outageGate.complete(Unit)` + `runCurrent()`（collector 收 IOException → 谓词 `publishCache(true)` → 挂起退避）；`coldStartGate.complete(Unit)`；`coldThread.join(5_000)`（冷读返回旧 false → `publishCache` 条件模式 generation 失配 → 陈旧值丢弃、有效值为 fail-closed true）。**Assert**：`assertTrue(manager.isLocked.value)`（Oracle #1 交错下**首个 ON_START 仍锁定**）；不推进虚拟时间（collector 仍在退避中）再做 ON_STOP→ON_START → `assertTrue(manager.isLocked.value)`（**重试等待期缓存保持 fail-closed true**，未被陈旧 false 覆盖）。实现前：无版本化发布 → 冷读 false 覆盖 true → 不锁 → 红。 |
| T12a（版本化发布交错——另一顺序，二轮复审第 1 项） | `already-published fail-closed cache skips cold read entirely` | **红**（实现前；实现后兼作交错守卫） | **A**：`var collections = 0`；`every { userPrefs.secureLockEnabled } returns flow { collections++; if (collections == 1) throw IOException("outage") else emit(false) }`（无需 `coldStartReadTimeoutMs` 注入——回归实现会走快速冷读，collections 计数立即背叛，不涉超时）。**Act**：`manager.startPreferenceCollection(backgroundScope)` + `runCurrent()`（第 1 次收集抛 → 谓词 `publishCache(true)`：generation→1、initialized=true → 挂起退避）；随后**测试线程直接** `manager.onStateChanged(mockk(), ON_START)`（走温路径，非阻塞）。**Assert**：`collections == 1`（**跳过冷读的直接证据**——若判定未能按同锁协议看到 initialized=true 而发起 `first()`，collections 变 2）；`assertTrue(manager.isLocked.value)`；再 ON_STOP→ON_START（仍在退避中）→ `assertTrue(manager.isLocked.value)`（缓存保持故障态 true，未被重读/覆盖）。覆盖 T12 的互补顺序：collector 发布**完成**于冷启动判定**之前**——v2 的「已递增 generation 尚未写字段」窗口是该顺序的内部态，判定+快照融合入锁后仅锁内可见、锁外结构不可观测（§3.2③ 协议性质）；本测试锁定该顺序的**可观测结果**=跳过冷读。实现前（原始内部）：collector 死于 IOException 未发布任何值 → 冷启动重读 emit(false) → collections==2 且不锁 → 红。 |
| T13（退避期取消守卫，Oracle #3） | `collector cancelled during backoff does not resubscribe` | 绿（守卫） | **A**：恒抛 IOException 流 + `var collections = 0`；`val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))`（自建可取消 scope——backgroundScope 只在测试结束才取消，无法中途取消）。**Act**：`startPreferenceCollection(scope)` + `runCurrent()`（首轮失败 → 进入退避）；`scope.cancel()`；`advanceTimeBy(10_000)` + `runCurrent()`。**Assert**：`collections == 1`（取消后不再订阅；谓词对取消路径无重订阅副作用，CE 不被退避机制转化为重试）。修复前后均绿。 |
| T14（无首值→本地超时，Oracle #2 + 二轮复审第 2 项） | `cold start with no first value times out fail-closed and returns promptly` | **红** | **A**：不启动 collector；`every { userPrefs.secureLockEnabled } returns flow { awaitCancellation() }`（永不首发——覆盖"上游初始化/调度/同步等待不发首值"面）；`manager.coldStartReadTimeoutMs = 100`（真实时间注入，见测试调度说明）；`val pool = Executors.newSingleThreadExecutor()`（**独立可清理真实线程**——冷启动调用绝不上测试线程，理由见调度说明「dispatchTimeoutMs 不是线程看门狗」）。**Act**：`val future = pool.submit { manager.onStateChanged(mockk(), ON_START) }`；测试线程 `try { future.get(5, TimeUnit.SECONDS) } catch (e: TimeoutException) { throw AssertionError("cold start did not return within bounded time", e) } finally { pool.shutdownNow(); assertTrue("worker thread must be reclaimed", pool.awaitTermination(5, TimeUnit.SECONDS)) }`。**Assert**：方法正常返回（实现后在 ~100ms 真实超时处返回）且 `assertTrue(manager.isLocked.value)`（**本地读取超时 = 按已启用**，与 IOException 同语义）；工作线程内异常经 Future 包装为 `ExecutionException` 传回测试（真实原因浮出，不被吞）。实现前：`runBlocking.first()` 永不返回 → `future.get` 5s 有界超时 → AssertionError → **红且不挂死套件**；`shutdownNow` interrupt 工作线程（`runBlocking` 把 interrupt 转为协程取消，`awaitCancellation` 可取消）→ 线程回收、awaitTermination 通过。**变异验证（实施期验证步骤，非自动化测试）**：临时移除生产 `withTimeoutOrNull`（回退无界 `runBlocking.first()`）单跑 T14——必须**限时内红**（future.get 5s 超时路径）且**无线程残留**（awaitTermination true）；恢复超时后转绿——证明测试的时间界与回收能力不依赖生产代码正确。 |
| T15（进程重建仍不可读，Oracle #2） | `process rebuild with still-unreadable source fails closed again` | **红** | **A**：全新 `AppLockManager` 实例（模拟进程重建：`@Volatile` 缓存随进程消失、cacheInitialized=false）+ 恒抛 IOException 流。**Act**：`manager.onStateChanged(mockk(), ON_START)`。**Assert**：不抛且 `assertTrue(manager.isLocked.value)`（T10 的重建视角镜像：重建后冷路径同样 fail-closed，不依赖上一进程的任何状态）。实现前红（未捕获 IOException）。 |
| T16（认证不可用不解锁——可执行验证，Oracle #6） | `locked state persists across background cycles until unlock` | 绿（行为锁） | **A**：`MutableStateFlow(true)` 冷启动置锁（等价现有 L1-C1 路径）。**Act**：不调用 `unlock()`，连做两轮 ON_STOP→ON_START。**Assert**：每轮后 `assertTrue(manager.isLocked.value)`——锁定态不因前台切换或时间推移自行解除，只有 `unlock()`（即认证成功回调，`LockScreenOverlay.kt:64/:111`）能解锁。这是行为矩阵"无任何可用认证 ⇒ 保持锁定"行的**可执行**对应物（Oracle #6：原稿只有静态行为表；BiometricPrompt/UI 层不可 JVM 化，其 5 次/30s 冷却与重试按钮仍由矩阵静态行覆盖）。修复前后均绿。 |
| 既有 | `cold start with lock enabled locks on first ON_START`、`cache warm path unchanged` | 绿（回归锁） | 不改动。②保留成功路径 `runBlocking.first()` 原语义。 |

断言全部为布尔状态/异常类型，零文案断言。

### 3.4 验收矩阵（本 BUG）

| 命令 | 期望 |
|---|---|
| `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.security.AppLockManagerTest'`（Step 0 后、实现前） | T8/T9/T10/T12/T12a/T14/T15 红（T14 红 = 专用线程隔离 + `future.get` 5s 有界等待把无界挂死转为限时 AssertionError，且工作线程回收无残留——不依赖 dispatchTimeout），T11/T13/T16 + 既有 2 个绿 |
| 同上（实现后） | 全部绿 |
| `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin` | BUILD SUCCESSFUL |

### 3.5 风险与守卫（重点：fail-closed 语义与 App Lock 红线）

- **App Lock 红线（Lane 要求"异常/边界下 fail-closed"）**：§3.2 行为矩阵逐路径论证——变体 A/B 从 fail-open/fail-crash 收敛为 fail-closed；发布顺序由 ③ 同锁协议单入口保证（交错两顺序分由 T12/T12a 锁定）；冷启动等待有界（设计上界 2s，协作式取消边界表述见 §3.2 有界等待取舍——可取消挂起面由 T14 锁定）；进程重建路径依赖冷启动 ②，天然 fail-closed（T15）。
- **缓存写入顺序（Oracle #1 + 二轮复审第 1 项）**：`@Volatile` 只保证可见性；顺序性由 generation+锁的发布协议保证，锁不跨任何挂起点（§3.2③）；「需冷读」判定与 generation 快照融合入同一非挂起临界区（已初始化即跳过冷读），条件检查与发布同锁；全部写者经 `publishCache` 单一入口（冷读走 `expectGeneration` 条件模式，不再有锁内直写字段的第二路径）。
- **过度上锁的残余风险（按 Oracle #7 拆分认证面）**：故障期视为已启用 → 若用户实际未启用：**有设备凭据**（PIN/图案/密码，`BiometricAuthManager.kt:22-24` 允许 DEVICE_CREDENTIAL）→ 验证一次即解锁，可自恢复；**无任何可用认证**（无生物识别且无设备凭据）→ 停留在锁屏，需先在系统设置录入生物识别或设置设备凭据，**不得声称重试即可恢复**（T16 可执行锁定"无认证成功即不解锁"）。发生条件是"DataStore 故障 ∧ 未启用锁 ∧ 无任何可用认证"三者同时成立，概率与危害（多输一次凭据/先配置系统认证 vs 静默解锁全部媒体）权衡明确偏向 fail-closed。
- **冷启动主线程挂死（Oracle #2，已收敛）**：原稿把无界 `runBlocking` 留作残余风险——不接受："不发首值"也来自上游初始化/调度等待，不抛不返回即事实 fail-open（`_isLocked` 初值 false、置锁在阻塞读取之后）。现 ② 为 `withTimeoutOrNull` 有界等待（默认 2s 设计上界 < 5s 输入派发 ANR 阈值）：本地超时=按已启用（与 IOException 同语义，不引入"超时后开锁"面），外部取消 CE 原样传播（红线 3，T11/T14 分界覆盖）。残余面由「无界挂死未处理」收窄为「极端非协作阻塞可能超过 2s 设计上界」（协作式取消 ≠ 硬实时；可取消挂起面由 T14 的专用线程隔离+有界等待锁定）——如实声明而非声称无条件消除。
- **红线 3（CE 先传播）**：①谓词 `cause !is IOException → false`；②显式 CE rethrow 先于 IOException catch，且 `withTimeoutOrNull` 只吞自身超时（本地超时转 null 不落入 CE 传播面）；CE 结构性穿透，T11 守卫。
- **红线 6（敏感信息）**：日志仅 `Log.w(TAG, 稳定诊断短语, cause)`（异常对象，不打印任何 key 值/URI/路径拼接）；UI 无新增文案；锁屏 UI 不动。
- **FLAG_SECURE**：不动 `MainActivity` 的常开策略（`AppLockManager.kt:58-62` 注释阐明其与 ON_STOP 置锁时机的分工），本修复不影响。
- **性能约定**：重试为固定 5s 退避挂起，无忙转、无轮询；冷启动同步等待有界（设计上界 2s，协作式取消边界见 §3.2）且仅在缓存未初始化的首个 ON_START 发生一次。
- **测试确定性（Oracle #3 修正后）**：重订阅推进一律 `runCurrent → advanceTimeBy(5_000) → runCurrent`（advanceUntilIdle 在只剩 backgroundScope 任务时即停、不消化后台退避；advanceTimeBy 不执行恰落在目标时刻的任务，故末尾必须 runCurrent）；T8 以 `collections` 订阅计数防"故障缓存 true + 永久停止收集"的假阳性，且恢复段每次 `source.value` 变更后、生命周期事件前显式 `runCurrent()`（二轮复审第 3 项——StandardTestDispatcher 不在赋值时立即执行 collector）；T12 冷启动在真实线程 + 双栅栏控制交错，T12a 温路径在测试线程直接断言；冷启动等待经 `coldStartReadTimeoutMs` 注入真实时间控制（外层虚拟时钟不管内层 runBlocking，§3.3 测试调度说明）；T14 冷启动在独立可清理单线程 executor + 测试线程 `future.get` 有界等待 + 变异验证（临时移除生产超时须限时红且无线程残留）。

### 3.6 涉及文件（本 BUG）

| 文件 | 操作 |
|---|---|
| `app/src/main/java/cn/vectory/syncplayer/security/AppLockManager.kt` | 修改：抽 `startPreferenceCollection(scope)`（internal 缝）；`publishCache` 版本化发布协议（generation+锁、唯一入口+`expectGeneration` 条件模式，§3.2③）；collector 加 retryWhen + 故障期 fail-closed；冷启动改为 `lockEnabledForStart()`：锁内融合判定+快照 → 锁外有界读（withTimeoutOrNull）+ 窄 catch → `publishCache` 条件发布；新增 TAG/常量/超时注入点/7 个 import |
| `app/src/test/java/cn/vectory/syncplayer/security/AppLockManagerTest.kt` | 追加 T8–T16 |

## 4. 汇总：涉及文件清单（写域）与合并策略

| 文件 | 涉及 BUG | 操作 | 合并策略 |
|---|---|---|---|
| `app/src/main/java/cn/vectory/syncplayer/settings/SettingsViewModel.kt` | 003 | init 插 `retryWhen` + companion 退避 | 单 BUG 独占 |
| `app/src/main/java/cn/vectory/syncplayer/settings/StorageManagementViewModel.kt` | 004/005 | `refresh()`（守卫 + `refreshEpoch` 代次失效）/ `switchToInternal()` / `switchToExternal()` 三函数体包守卫 | 004/005 同章统一设计，一次改完 |
| `app/src/main/java/cn/vectory/syncplayer/security/AppLockManager.kt` | 014 | 抽 `startPreferenceCollection(scope)` 缝 + `publishCache` 版本化发布（同锁单入口/条件模式）+ retryWhen fail-closed + 冷启动融合判定/有界读/条件发布 | 单 BUG 独占 |
| `app/src/test/java/cn/vectory/syncplayer/settings/SettingsViewModelTest.kt` | 003 | 追加 T1/T2/T2a | 单文件追加 |
| `app/src/test/java/cn/vectory/syncplayer/settings/StorageManagementViewModelTest.kt` | 004/005 | 追加 T4/T4a/T4b/T5/T5a/T6/T6a/T7/T7a | 单文件追加 |
| `app/src/test/java/cn/vectory/syncplayer/security/AppLockManagerTest.kt` | 014 | 追加 T8–T16 | 单文件追加 |

各 BUG 写域互不重叠（两个 settings VM 是不同文件），无需跨章合并仲裁。**不修改**：`UserPreferencesRepository.kt`（仓库级 catch 被否决，见 §0-1）、`SyncPlayerApp.kt`、`MainActivity.kt`、`LockScreenOverlay.kt`、`BiometricAuthManager.kt`、`strings.xml`、任何 DAO/Store/Engine。生产代码 3 文件、测试 3 文件，全部为现有文件局部修改，无新文件。

## 5. 汇总验收矩阵（可执行命令与期望结果）

| # | 命令 | 期望 | 覆盖 |
|---|---|---|---|
| A1 | `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.settings.SettingsViewModelTest'` | 全绿（T1/T2/T2a 新增 + 既有 CE 回归测试） | BUG-003 |
| A2 | `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.settings.StorageManagementViewModelTest'` | 全绿（T4/T4a/T4b/T5/T5a/T6/T6a/T7/T7a 新增 + 既有 3 个 reset 测试） | BUG-004/005 |
| A3 | `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.security.AppLockManagerTest'` | 全绿（T8–T16 新增（含 T12a）+ 既有 2 个） | BUG-014 |
| A4 | `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin` | BUILD SUCCESSFUL | 全 Lane |
| A5 | `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest` | BUILD SUCCESSFUL，现有全量 57 个测试文件零回归（R2 报告基线） | 全 Lane |

TDD 顺序：每 BUG 先写表内新增测试跑红（A1–A3 阶段性红）→ 实现 → 单类绿 → A4 → A5。BUG-014 的 Step 0（抽缝）必须先单独通过 A3（既有 2 测试绿）再写 T8。

## 6. 与缺陷报告不符之处（逐条，不隐瞒）

- **D1（BUG-003 / BUG-014 修复算子）**：R1（BUG-003）与 R2（BUG-014）均推荐 `.catch {}`（DataStore 官方模式）；本计划改用 `retryWhen`。原因见 §0 详述：裸 `.catch` 无 emit 时下游流正常 complete，收集协程照样终结——状态冻结、缓存过期依旧，**报告推荐的方案通不过报告各自建议的回归测试**（故障后偏好变更须仍被看到）。`retryWhen` 通过重订阅实现真恢复，覆盖官方模式的意图。
- **D2（BUG-005 错误反馈）**：R1 建议"失败时置 resetWarning/专用错误态"；本计划不设 `resetWarning`（该字段语义绑定 reset 操作 `:154-156`，复用造成状态语义混淆；写失败后 `refresh()` 重读出的回弹状态即真相），静默降级 + 日志，与 SettingsViewModel 同类 setter（`SettingsViewModel.kt:80-126`）一致——即 todo.md"与同类 setter 一致"的字面方向。（v2 补充：Oracle #4 指出"回弹即真相"原缺发布顺序保证——多次 refresh 独立 launch、可乱序迟到覆盖；现由 §2.2 的 `refreshEpoch` 代次失效机制补齐，旧刷新/旧失败均无法覆盖收尾 refresh 的读数。）
- **D3（BUG-014 仓库层）**：R2 将 `UserPreferencesRepository.kt:96-98` 列为根因位置之一并建议在流上加 catch；本计划**不改仓库层**（§0-1：统一降级到 `emptyPreferences` 默认 false 对 `secureLockEnabled` 是 fail-open），仅在该流的全部三个生产消费点（AppLockManager collector / 冷启动 / SettingsViewModel combine）做消费端恢复。`getSecureLockEnabled()`（`:125`）经 grep 确认无生产调用方，不处理。
- **D4（BUG-014 变体 A 症状定性）**：R2 定性为"锁静默失效"；补充：该 collector 死于 `Dispatchers.IO` 上的 uncaught `IOException`，真机上同样可能直接崩溃进程（fail-crash 而非 fail-open）。无论哪种定性，修复相同；计划按更严重面评估（§3.1）。
- **D5（BUG-003 严重级）**：todo.md 定级 Medium，症状"写入成功但 UI 永久冻结"、无数据损坏——与 todo.md 一致，确认无升降级理由。
- **D6（todo.md 收录范围）**：todo.md 仅收录 R1 的 BUG-001–010，**未收录** R2 的 BUG-014；按任务规则"冲突以 todo.md 为准"，todo.md 对 BUG-014 无相反主张，故以 R2 报告（BUG-014 唯一来源）为准。
- **D7（R2 Inconclusive-1）**：R2 列 `SyncRelationEditViewModel.deleteRelation`（:316-322）为同家族未实验项，不在本 Lane 四 BUG 清单内，本计划不修，仅在 §7 列为风险。

## 7. 报告未提及的强相关风险（只列不展开）

- `MediaVolumeProvider.kt:22` 在 `current()` 内调 `getMediaStorageTreeUri()`，且 `MediaContentProvider` 卷解析走 `runBlocking`（storage-security.md）——同一 DataStore IO 故障面可在 Provider 路径引爆，影响加密媒体读取。
- `SyncRelationEditViewModel.kt:112` 读 `getMediaStorageTreeUri()` 同样无守卫（R2 Inconclusive-1 的读侧；其 `deleteRelation` :316-322 是写侧）。
- ~~`StorageManagementViewModel.switchToInternal/External` 的 `getTotalItemCount()` 在主线程执行——性能约定违规~~ **已删除该结论（Oracle #7）**：`VideoItemDao.kt:222` 是 `suspend fun getTotalItemCount(): Int`，Main 协程上挂起执行、SQL 走 Room 自有后台执行器，不阻塞主线程；不能仅凭 Main 协程调用断定 Room 主线程阻塞 SQL。switch 不包 `withContext(ioDispatcher)` 仅为风格差异，非违规。
- ~~`AppLockManager` 冷启动 `runBlocking` 无超时：极端磁盘 hang 时主线程卡死~~ **已由本计划收敛（Oracle #2）**：§3.2② 改为 `withTimeoutOrNull` 有界等待（默认 2s，本地超时 fail-closed），不再是未覆盖的残余风险；保留本条以记录来源与修订轨迹。
- 仓库层其余 18 个裸 `dataStore.data.map` 流（`UserPreferencesRepository.kt:47-104`）的其他消费方（Player/Browser 等 ViewModel）存在同家族冻结风险，本 Lane 仅覆盖 Settings/AppLock 消费点。
- `SettingsViewModel.syncLogs`（`:49-50`，Room flow + stateIn）未做同族处理——Room 流异常面与 DataStore 不同，本计划未覆盖。
