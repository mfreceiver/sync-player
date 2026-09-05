# 修复计划 — Lane: player / browser / util（BUG-002 / 015 / 016 / 017 / 019 / 021 + Lane2 交接项 L4-1..L4-4）

> 生成日期：2026-09-05 ｜ 缺陷权威来源：`todo.md` > `docs/automatic/20260905-0012_bug-hunting.md`（R1）> `docs/automatic/20260905-0114_bug-hunting.md`（R2）
>
> **修订记录**：
> - v1（2026-09-05）：首版——6 BUG（002/015/016/017/019/021）。
> - **v2（2026-09-05，本轮）**：整合已过门禁（**9.6 PASS 定稿**）的 L2 计划「Lane4 交接项」章节（`2026-09-05-plan-smb-dao.md`:721-832，依据其「移出本 lane 写域 →『Lane4 交接项』」清单 :705-709）移交的 **L4-1..L4-4**：L4-1 保存入口协议校验（`SyncRelationEditViewModel.kt`）、L4-2 增益调用点单列 API 替换（`GainAnalysisViewModel.kt`）、L4-3 三个 VM 测试类、L4-4 实施门禁——见新增章节「Lane2 交接项」。**语义权威 = L2 计划原文；其 A/B 共用规则①②逐字照搬（:778-799），不得改写。** 依赖状态：L2 生产代码已就绪（`updateAudioGain(id, Float?)` 已落地 `VideoItemDao.kt:245-246`/`VideoRepository.kt:132-133`，本轮 grep 核实）——L4-2/L4-3 只改调用点/新增测试，禁改 DAO/仓库。
>
> - **v3（2026-09-05，本轮）**：oracle 首审 **FAIL 8.0/10**（6 Major + 2 Minor）返工——①BUG-002 单次保存 CE 隔离（受监督子 Job）+测试性质化改写；②BUG-019 耗尽策略对齐 UI 实际行为（结构化 `queueExhausted` + UI 消费退出，PlayerScreen.kt 入写域）；③L4-1 新增「保存输入同一快照」实现强化小节+门控测试；④BUG-017 接线测试 IV 显式绑定+内容断言；⑤BUG-002 绿锁改有状态 fake；⑥BUG-016 未定义变量/不存在回调修复（helper 签名改 incoming 类型/id、NoGuard 显式 errorFlow lambda）；⑦BUG-017 预创建失败窗口清理责任+UTF-8 字节预算+哨兵内容验证；⑧harness/锚点更正（ioDispatcher 注入语义、类 B discover 等待与 entered latch、seam 改 internal get/private set、updateAudioGain 实为 `VideoItemDao.kt:245-246`、MediaUris 实为 `provider/MediaUris.kt`、updateStar 已单列）。另按残留风险收紧：FIFO 声明限定「同一 VM」、消费者空闲 GC 不设绝对断言、格式测试固定 Locale+log10 边界警示、日志 cause 链按「不新增 path/凭据输出」收敛。
> - **v4（2026-09-05，第 2 轮返工）**：oracle 复审 FAIL 8.3/10（4 Major + 1 Major 测试参数 + 1 Minor）——①BUG-017 长名截断改「仅截 stem、完整保留 `-$videoId[-$n]` 后缀」消除不收敛死循环（+收敛 guard 与 5 个长名/重复分享用例）；②seam 语法修正为 `internal var analysisJob` + `private set`；③接线测试 stub 改按 `openCiphertextRead(Long id)` 真实签名、删除 helper 退化备选；④BUG-002 表述统一「逐次 join 保证串行，不保证时间/容量有界」、纠正 Job 父子关系（CE 子不取消父，无需监督层）、删「onCleared 取消 scope」错误说法、门控释放加 finally；⑤跨视频 fake 改 `secondArg`（position）并更正 CONFLATED 失败形态说明；⑥BUG-019 effect 移至 `currentOnExit` 声明后（前向引用编译错误）+ effect 体内再核对 VM 实时终态 + 用例 4 更名断言耗尽 + 新增「旧请求恢复不得发布耗尽」门控用例；⑦残留措辞同步（日志 Throwable、PB 边界对照、清理 catch 重抛、写域计数含 strings、L4-1 快照收窄为协议相关字段）。
> - **v5（2026-09-05，第 3 轮返工）**：ora-8 复审 FAIL 9.2/10（1 Major + 3 Minor）——①BUG-019 用例 6 改为**确定性可执行交错**（entered 侧信道 + `runCurrent()` 分步推进 + 放 gate 前断言新请求终态 + 变异验证注记）；②UI 变量名 `vm`→`viewModel`（对齐 PlayerScreen 实参）；③BUG-002 恢复被误删的 `saveProgress` 生产接线片段、英文注释改 `child job of the consumer`；④长名 guard 表述更正（`check(n<1_000)` = 最多 1000 次候选尝试、耗尽显式失败）+ 用例 9 声明收窄（repeat(100) 只限调用次数，不声称防单次挂死）+ 新增用例 11「预占全部候选 → guard 显式失败、零覆写、零额外产物」；⑤扫尾：`.bin`=4 字节更正、删除失效的 Robolectric 降级交叉引用、v3/v4 历史记录加「仅审计留痕、不可作实施指令」总注。
> - **v6（2026-09-05，最终小修）**：ora-8 v5 复审 FAIL 9.4（2 Minor+1 文字扫尾）——①BUG-019 用例 6 的步骤 1/2 整体包进真实 `try`，`finally` 同时 `gate.complete`+`advanceUntilIdle`，最终断言置于 finally 之后；`runCurrent()` 用 TestScope 扩展（禁 `testDispatcher.runCurrent()`）；②变异验证归属修正：用例 6 只声称「删**耗尽发布 guard** → 最终 `queueExhausted` 断言红」；删**成功提交 guard** 的 video 红证据归属 BUG-015 既有用例 1/4（不新建重复用例，删「用例 6 可测两 guard」错误表述）；③L4-1 精度：`localBasePath` 非「落库时刻现读」——保存使用此前已计算的 `normalizedBasePath` 局部值，本计划不改变其前置计算/保存时机。
> 写域边界：本 Lane 改 player/browser/ui(util) 的 UseCase/ViewModel/Repository 消费层，**及两个 settings VM**（`SyncRelationEditViewModel.kt`、`GainAnalysisViewModel.kt`）、其测试与 `app/src/main/res/values/strings.xml`（仅 L4-1 的 `relation_invalid_remote_path` +1 条）；DAO `@Query` 属 Lane2（`2026-09-05-plan-smb-dao.md`）写域，本计划零 DAO/Entity/仓库改动（L4-2 只消费 L2 已交付 API）。
> 评审基线 commit：R2 报告基于 `caad832`（main）；行号以当前 main 源码实读为准（下文逐一核对；v2 新增章节的行号锚点已对照 `SyncRelationEditViewModel.kt`/`GainAnalysisViewModel.kt` 现源逐一复核，与 L2 计划引用零漂移，见附录核对记录）。

## 目录

- [BUG-002 播放进度并发保存丢失更新](#bug-002)
- [BUG-015 loadVideo 乱序覆盖](#bug-015)
- [BUG-016 TrashToggle 覆盖挂起 HardDelete](#bug-016)
- [BUG-017 Share 临时文件同名冲突](#bug-017)
- [BUG-019 队列推进到已删除 id 静默无操作](#bug-019)
- [BUG-021 formatFileSize ≥1PiB 误标 TB](#bug-021)
- [Lane2 交接项 L4-1..L4-4（v2 新增）](#lane2-交接项l4-1l4-4本轮新增)
- [汇总：涉及文件清单（写域）](#写域汇总)
- [汇总：验收矩阵](#验收汇总)

---

## BUG-002 播放进度并发保存丢失更新

### 现状与根因（源码实读核对）

**`app/src/main/java/cn/vectory/syncplayer/data/repository/PlaybackRepository.kt:33-58`** — `saveProgress` 是非原子 read-modify-write，无事务、无单调位置保护：

```kotlin
suspend fun saveProgress(videoItemId: Long, position: Long, duration: Long) {
    val existing = playbackRecordDao.getByVideoId(videoItemId)      // 读
    val isCompleted = duration > 0 && position >= (duration * 0.95) // :35 完成阈值
    val now = System.currentTimeMillis()
    if (existing != null) {
        playbackRecordDao.upsert(existing.copy(position = position, ...))  // 改写整行
    } else { playbackRecordDao.upsert(PlaybackRecordEntity(...)) }
}
```

对比同类 `toggleTrashed`（:60-76）与 `markFolderCompleted`（:86-106）均已用 `appDatabase.withTransaction`，唯独 `saveProgress` 没有。

**`app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt:111`**：

```kotlin
private val progressSaveScope = CoroutineScope(SupervisorJob() + ioDispatcher)
```

**`PlayerViewModel.kt:252-263`** — 每次 `saveProgress` 调用独立 `launch`，无串行化：

```kotlin
fun saveProgress(position: Long, duration: Long) {
    val videoId = _uiState.value.videoItem?.id ?: return
    progressSaveScope.launch {
        try {
            playbackRepository.saveProgress(videoId, position, duration)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.e(TAG, "Failed to save progress", e) }
    }
}
```

**触发链（已核实调用点 `player/PlayerScreen.kt`）**：:113（dispose 最终保存）、:167/:338/:350（prev/next 切换时先 `saveProgress` 再 `loadVideo`）、:164-168（自动推进）。`Dispatchers.IO` 多线程下：save#1(旧位置) 读得 existing → save#2(新位置) 完整写入 → save#1 用旧快照 upsert 覆盖。R1 报告以真实 `Dispatchers.IO` 复现（`expected:<100000> but was:<30000>`）。后果：续播位置回退、`isCompleted`（95% 阈值，:35）被旧写翻回 false。

**前置约束（AND-001，`PlayerViewModel.kt:103-110` KDoc + 既有测试 `PlayerViewModelSaveProgressTest`）**：保存必须在独立于 `viewModelScope` 的 scope 完成（回收站滑动杀进程场景 `viewModelScope.launch` 是静默 no-op）。修复**不得**把保存移回 `viewModelScope`，也不得在 `onCleared` 取消该 scope。

### 修复方案（VM 层单消费者 Actor）

todo.md 权威指定 VM 层串行化。方案：`Channel<PendingProgressSave>(UNLIMITED)` FIFO 队列 + 在 `progressSaveScope` 内启动的唯一消费者协程。**所有** `repo.saveProgress` 调用退化为消费者串行执行，顺序 == 调用顺序（UI 线程上的调用顺序即用户意图顺序），交错窗口被结构性消除。

`PlayerViewModel.kt` 新增成员并替换 `saveProgress`：

```kotlin
private data class PendingProgressSave(val videoId: Long, val position: Long, val duration: Long)

// BUG-002: single consumer serializes all progress saves issued by THIS VM instance;
// FIFO order == call order. Each save runs in its own child job of the consumer so a
// CancellationException escaping one repository call cancels only that child —
// the consumer loop keeps serving the queue (oracle #1: CE 不得杀死唯一消费者).
private val progressSaveChannel = Channel<PendingProgressSave>(Channel.UNLIMITED)

init { // 与现有 init 块并列
    progressSaveScope.launch {
        for (save in progressSaveChannel) {
            val job = launch {                       // 每次保存一个子 Job（父=消费者协程）
                try {
                    playbackRepository.saveProgress(save.videoId, save.position, save.duration)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save progress", e)
                }
            }
            job.join()                               // 上一保存结束才取下一项：完成序 == 入队序（仅串行保证，无时延上界）
        }
    }
}

// 既有 saveProgress(position, duration) 函数体整体替换（原 :252-263 的 launch 型实现删除，
// 不得残留任何"直接向 repository 发起保存的独立 launch"——那会绕过串行化）：
fun saveProgress(position: Long, duration: Long) {
    val videoId = _uiState.value.videoItem?.id ?: return   // 无已加载视频：no-op（与现状语义一致）
    progressSaveChannel.trySend(PendingProgressSave(videoId, position, duration))
}
```

要点：
- **CE 隔离（oracle #1；v4 oracle #4 纠正 Job 父子关系表述）**：v1 草案的消费者把 repo 调用直接包在 `for` 循环内——`saveProgress` 抛出一次 CE 即终止唯一消费者协程，`SupervisorJob` 不会重启它，channel 保持 open、后续 `trySend` 表面成功实则永不落库（新增故障面）。终版：每次保存在消费者自己的 `launch` 子 Job 中执行——**父协程是消费者（普通 Job），不是 `progressSaveScope` 的 SupervisorJob 直接监督**；但 kotlinx 协程对子协程以 CE 完成的处理是良性的（`JobSupport.childCancelled` 对 CancellationException 视为已处理、不上浮取消父级），因此子 Job 内 CE rethrow 只终结该子 Job，`join()` 正常返回，循环继续服务队列——**隔离目标成立，无需为单次保存强加 SupervisorJob 层**。普通 `Exception` 照旧被子 Job 内 catch 消化。消费者协程自身只在 `progressSaveScope` 被取消时经 `receive` 的 CE 退出——该 scope 不在任何 `onCleared` 中取消（AND-001），其取消仅发生在进程终止级。
- **join 的保证边界（v4 oracle #4 统一表述）**：逐次 `join()` 保证的是**完成序串行**（完成序 == 入队序），**不保证时间/容量有界**——`join()` 挂起等待无时延上限，也不给队列积压建任何硬上界。若单次保存永久挂起（repo 挂死），后续保存将队头阻塞排队——这是**接受的残留风险**（个人 app、Room 毫秒级本地写、无网络面；挂死源本身属 repo/DB 层故障）。**不得**为修此文案擅自加"超时后丢弃保存"——那会制造新的丢更新面，与 BUG-002 修复目的相悖。
- **串行化声明范围（oracle 残留风险收紧）**：本修复串行化的是**同一 VM 实例**发出的进度保存调用（顺序 == 该 VM 的调用序）；不跨 VM 实例、不做全局排序承诺。
- **不 conflate**。`Channel.CONFLATED` 是全 channel 单槽合并，跨视频会挤掉前一个视频的最终保存（见测试 3 的定向红灯）；FIFO UNLIMITED 无此丢更新面。积压量级由保存频率（秒级周期）与单次 Room 写耗时（毫秒级）决定，个人用 app 下通常很小——这是量级判断而非硬上界（不宣称「必然清空/完全有界」，oracle #1）。
- AND-001 语义保留：消费者跑在 `progressSaveScope`（不随 `viewModelScope` 取消而取消）；页面销毁后 `trySend` 入队的最终保存仍被同一消费者完成。`trySend` 在 UNLIMITED channel 上永不失败。
- repo 层不加单调保护（`position >= existing.position`）：todo.md 指定 VM 层；且单调保护会破坏合法的"seek 回退后保存"语义（见测试 7 护栏）。`PlaybackRepository.saveProgress` 零改动（Lane2 无涉）。

### TDD 测试计划

文件：`app/src/test/java/cn/vectory/syncplayer/player/PlayerViewModelSaveProgressTest.kt`（沿用现有 mock 结构：全依赖 mockk、`ioTestDispatcher = UnconfinedTestDispatcher()`、`runTest` + `advanceUntilIdle`、`coVerify` 断言调用与次数）。

**断言策略（oracle #5）**：调用顺序/`coVerifyOrder` 只证明"进入了 mock"的顺序，证明不了完成序与最终持久值——一律用**有状态 fake**（mock 在完成点写入 `ConcurrentHashMap<Long, Long>` / `AtomicLong`）+ 断言最终持久值；顺序类断言仅作辅助。**门控释放纪律（v4 oracle #4）**：所有门控用例的 `gate.complete(Unit)`/`gate.countDown()` 一律放 `finally` 块——断言失败不得残留挂起中的保存任务（防测试进程卡死与跨用例污染）。

先写以下用例（现状下红/部分红），后实现：

1. `saveProgress serializes concurrent saves — final persisted value equals last call`
   - **Arrange**：`loadVideo42(vm)`；有状态 fake：`val persisted = AtomicLong(-1)`；门控慢保存：`val gate = CompletableDeferred<Unit>()`；
      `coEvery { playbackRepository.saveProgress(42L, 30_000L, 100_000L) } coAnswers { gate.await(); persisted.set(30_000L) }`
      `coEvery { playbackRepository.saveProgress(42L, 100_000L, 100_000L) } coAnswers { persisted.set(100_000L) }`
   - **Act**：`vm.saveProgress(30_000L, 100_000L)`（Unconfined 消费者立即进入 30k 并挂起在 gate）；`vm.saveProgress(100_000L, 100_000L)`；断言此刻 `persisted.get() != 100_000L`（现状：两个 launch 并行，100k 已写入 —— 红）；随后在 `finally` 中 `gate.complete(Unit)` + `advanceUntilIdle()`（v4：断言失败不残留挂起任务）。
   - **Assert**：`assertEquals(100_000L, persisted.get())` —— 串行 + FIFO：30k 先写、100k 后写，最终值 == 最后调用（现状下 gate 释放后 30k 迟到覆盖 → 30_000，红）。
2. `saveProgress keeps FIFO completion order across interleaved calls`
   - **Arrange/Act**：同 1，另记录完成事件 `events.add("30k"/"100k")` 于 fake 写入点。
   - **Assert**：`assertEquals(listOf("30k", "100k"), events.toList())`（完成序；辅助断言，主证据是 1 的最终值）。
3. `final save of previous video is not dropped when switching videos`（锁死"错误 conflate"实现——oracle #5 改写：先堵消费者，再入队两笔，再确认切换完成，最后释放；**v4 oracle #5：fake 完成点一律写 `secondArg<Long>()`（position）——`saveProgress(videoId, position, duration)` 的第三参是 duration，v3 草案 `thirdArg` 会把旧视频首笔 30k 也记成 100k（duration），掩盖 conflate 丢保存**）
   - **Arrange**：`val persistedById = ConcurrentHashMap<Long, Long>()`；`firstCall` AtomicBoolean；门控 42 的**首笔**保存：
      ```kotlin
      coEvery { playbackRepository.saveProgress(eq(42L), any(), any()) } coAnswers {
          if (firstCall.compareAndSet(true, false)) gate.await()   // 只堵第一笔（42@30k）
          persistedById[42L] = secondArg<Long>()                    // position（v4：不是 thirdArg=duration）
      }
      coEvery { playbackRepository.saveProgress(eq(43L), any(), any()) } coAnswers { persistedById[43L] = secondArg<Long>() }
      ```
      mock `getById(43L)`/`getByVideoId(43L)`。
   - **Act**（严格顺序）：加载 video 42 → `vm.saveProgress(30_000L, 100_000L)`（消费者取走 42@30k 并被堵在 gate）→ `vm.saveProgress(100_000L, 100_000L)`（旧视频最终保存**入队**）→ `vm.loadVideo(43L)`；`advanceUntilIdle()`；断言 `vm.uiState.value.videoItem?.id == 43L`（新视频加载完成、与被堵的保存链路无关）→ `vm.saveProgress(1_000L, 100_000L)`（新视频保存入队）→ `finally { gate.complete(Unit) }`；`advanceUntilIdle()`。
   - **Assert**：`persistedById == mapOf(42L to 100_000L, 43L to 1_000L)` —— 两个视频的保存都执行且取各自最后值。**失败形态（v4 更正）**：CONFLATED 实现下旧视频键**不缺失**——首笔 42@30k 已被消费者取走在途，`channel` 单槽只装着最新一项：42@100k 入队后又被 43@1k 顶掉，释放后最终 map 为 `{42→30_000, 43→1_000}`（旧视频**保留首笔 30k**、最终保存 100k 被丢）——`42L to 100_000L` 断言即红；FIFO UNLIMITED：全保留、各自末值。
4. `a save throwing CancellationException does not kill the consumer`（oracle #1 CE 隔离设计锁；对 v1 草案红、对现状与终版绿——不计入"现状红"证据，计为方案变体回归锁）
   - **Arrange**：`coEvery { playbackRepository.saveProgress(42L, 1_000L, 100_000L) } coAnswers { throw CancellationException("spurious CE from repo internals") }`；`coEvery { playbackRepository.saveProgress(42L, 2_000L, 100_000L) } coAnswers { persisted.set(2_000L) }`。
   - **Act**：`vm.saveProgress(1_000L, 100_000L)`；`vm.saveProgress(2_000L, 100_000L)`；`advanceUntilIdle()`。
   - **Assert**：`assertEquals(2_000L, persisted.get())` —— 首存抛 CE 后消费者仍服务第二笔（v1 草案：循环终止、channel 假活 —— 该方案下红）。说明：mock 抛 CE 模拟的是"repo 内部以 CE 作控制流泄漏"的病态输入；CE-first 纪律不变（子 Job 内 rethrow 在前）。
5. `consumer continues after a regular save exception`（oracle #1；现状绿护栏）
   - **Arrange**：首笔 `throws RuntimeException("db")`，次笔正常写 fake。
   - **Assert**：次笔持久值写入成功（`persisted.get() == 次笔值`），测试不崩。
6. 既有 3 用例（`persists position and duration`、`survives viewModelScope cancellation`、`no-op when no video is loaded`）保持绿（回归护栏；第 2 条即「取消 viewModelScope 后最终保存仍执行」的 AND-001 锁）。
7. `legal seek-backward save is not monotonicity-clamped`（护栏：锁死"不得引入单调保护"）
   - **Act**：42 视频先 `vm.saveProgress(100_000L, 100_000L)` 后 `vm.saveProgress(5_000L, 100_000L)`；`advanceUntilIdle()`。
   - **Assert**：fake 最终值 == `5_000L`（seek 回退后的保存按调用序生效，不被任何层"只进不退"地吃掉）。

### 验收矩阵

| 命令 | 期望 |
|---|---|
| `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.player.PlayerViewModelSaveProgressTest'` | 全部通过（新增 6（用例 1-5、7）+ 既有 3；v4 更正计数，与清单一致） |
| `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin` | BUILD SUCCESSFUL |
| 全量 `testDebugUnitTest` | 无回归 |

### 风险与守卫

- **CancellationException 先传播（红线 #3；oracle #1 强化；v4 #4 表述纠正）**：子 Job 内 `catch (CancellationException) { throw e }` 先于 `catch (Exception)`；单次保存的 CE 被隔离在子 Job（子以 CE 完成不上浮取消父消费者——见方案要点；测试 4 锁定）；消费者自身的取消仅当 `progressSaveScope` 被取消（该 scope 不在 `onCleared` 取消，AND-001；进程终止级事件）时经 `for` 循环的 `receive` CE 终止——该路径下未落盘的入队项丢失，与现状（未执行完的 launch）同类的既有丢失面，非本修复新增。
- **敏感信息/日志边界（红线 #6，oracle 收紧表述）**：日志文案沿用现有 `"Failed to save progress"` 与既有诊断异常输出（`Log.e(TAG, msg, e)`——异常对象含 cause 链是本仓库既定的诊断边界）；本修复**不新增**任何携带 path/volumeId/凭据的日志语句，UI 侧零新增输出。
- **性能约定（阻塞 I/O 在 IO）**：消费者运行于 `progressSaveScope = SupervisorJob + ioDispatcher`（`@IoDispatcher` 注入，测试可控），Room 调用不出现在 Main。
- **AND-001 不回归**：scope 不在 `onCleared` 取消；`trySend` 非挂起，teardown 路径无协程启动依赖。既有测试 2 是护栏。
- **声明范围（oracle 收紧）**：串行化承诺限于**同一 VM 实例**的进度保存调用；不跨实例、不承诺进程级全局序。
- 残余风险：进程被系统杀死时未落盘的队列丢失 —— 与现状等价的既有面，非本 BUG 范围；消费者空闲后随 VM 被 GC（channel 一并回收），不断言"必然完成清空"亦不断言泄漏。

### 涉及文件（写域）

- 生产：`app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt`（+init 消费者、`PendingProgressSave`、`Channel` import、`saveProgress` 函数体替换）
- 测试：`app/src/test/java/cn/vectory/syncplayer/player/PlayerViewModelSaveProgressTest.kt`（追加用例）

---

## BUG-015 loadVideo 乱序覆盖——旧请求迟到覆盖新请求

### 现状与根因（源码实读核对）

**`app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt:212-239`** — `loadVideo` 无请求代次/取消保护：

```kotlin
fun loadVideo(videoItemId: Long) {
    viewModelScope.launch {
        try {
            val video = videoRepository.getById(videoItemId)   // 挂起点 #1
            if (video != null) {
                val record = playbackRepository.getByVideoId(video.id)  // 挂起点 #2
                ...
                _uiState.update { it.copy(videoItem = video, ...) }     // 无条件提交
                _currentPosition.value = record?.position ?: 0L
            }
        } catch (e: CancellationException) { throw e }
        ...
    }
}
```

交错：launch#1（视频 A）在挂起点暂停 → launch#2（视频 B）发起并先完成提交 → launch#1 恢复后用 A 的数据 `_uiState.update` 覆盖 B。后果：画面与状态显示 A、播放进度读写 A 的 record（用户以为在播 B，`saveProgress` 却把进度写进 A —— 见 `saveProgress` :253 直接取 `_uiState.value.videoItem?.id`）。对比：同文件 `toggleTrashed`（:277-302）已对同类竞态做了"提交前校验 `state.videoItem?.id == videoId`"防护，`loadVideo` 没有。

触发场景：快速 prev/next 连击（`PlayerScreen.kt:121` `LaunchedEffect(videoItemId)` 每次切换都触发）+ Room 读耗时不均。

**同构路径（不在本 Lane 写域）**：`imageviewer/ImageViewerViewModel.kt:96-115 loadImage`（R2 报告确认同病）——见"与报告不符/风险"章节。

### 修复方案（generation guard）

`PlayerViewModel` 增加 `AtomicLong` 请求代次；`loadVideo` 发起时取号，所有挂起点结束后、任何状态写入前比对最新号，过期即静默丢弃：

```kotlin
private val loadGeneration = AtomicLong(0L)

fun loadVideo(videoItemId: Long) {
    val generation = loadGeneration.incrementAndGet()   // 发起即占代
    viewModelScope.launch {
        try {
            val video = videoRepository.getById(videoItemId)
            // ... BUG-019 的 null 跳过循环（见下节，与本修复合并落地）...
            if (video != null) {
                val record = playbackRepository.getByVideoId(video.id)
                if (generation != loadGeneration.get()) return@launch  // BUG-015: 旧代次迟到，丢弃
                val gain = video.audioGainDb ?: 0f
                val effectiveGain = if (_uiState.value.volumeBalance && video.audioGainDb != null) gain else 0f
                _uiState.update { it.copy(/* 现有字段不变 */) }
                _progressScrubState.value = ProgressScrubUiState()
                _currentPosition.value = record?.position ?: 0L
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.e(TAG, "Failed to load video $videoItemId", e) }
    }
}
```

要点：
- 检查点放在**两个挂起点（getById / getByVideoId）之后、首个状态写入之前**；通过检查后到函数结束无挂起点（`MutableStateFlow.update`/`value` 均同步），单次检查充分。
- generation guard 优于"cancel 上一个 load job"：`loadVideo` 与未来其他 viewModelScope 协程正交，误取消面为零；且无 Job 字段生命周期管理。
- 旧代次丢弃是幂等安全操作：新代次已完整提交状态，旧数据本就不该再出现。
- `updateAutoSwitchTime` 等 UI 侧行为不受影响（UI 以最终 `videoItem` 为准）。

### TDD 测试计划

文件：新建 `app/src/test/java/cn/vectory/syncplayer/player/PlayerViewModelLoadVideoTest.kt`（结构复制 `PlayerViewModelSaveProgressTest`：全依赖 mockk、`StandardTestDispatcher` Main、`UnconfinedTestDispatcher` io、`runTest`）。

先写用例（现状红）：

1. `stale load result does not overwrite newer video state`
   - **Arrange**：`videoEntity(1L)`/`videoEntity(2L)`；门控：`val gate = CompletableDeferred<Unit>()`；`coEvery { videoRepository.getById(1L) } coAnswers { gate.await(); videoEntity(1L) }`（慢请求）；`coEvery { videoRepository.getById(2L) } returns videoEntity(2L)`；`coEvery { playbackRepository.getByVideoId(2L) } returns null`。
   - **Act**：`vm.loadVideo(1L)`（launch#1 挂起在 gate）；`vm.loadVideo(2L)`；`advanceUntilIdle()`（#2 提交 video 2）；`gate.complete(Unit)`；`advanceUntilIdle()`（#1 恢复）。
   - **Assert**：`assertEquals(2L, vm.uiState.value.videoItem?.id)`（现状 #1 迟到覆盖为 1L —— 红）。
2. `load generation increments so only the latest request commits`
   - **Arrange/Act**：同上交错，另再发 `vm.loadVideo(3L)`（mock 同 2L 模式）在 gate.complete 之前。
   - **Assert**：`assertEquals(3L, vm.uiState.value.videoItem?.id)`；且 `_currentPosition` 跟随 3 的 record（用非零 position 的 record 断言数据成套提交，防半新半旧）。
3. `loadVideo is a no-op repo failure safe — exception publishes no state change`
   - **Arrange**：`coEvery { videoRepository.getById(1L) } throws RuntimeException("db")`。
   - **Act**：`vm.loadVideo(1L)`; `advanceUntilIdle()`。
   - **Assert**：`vm.uiState.value.videoItem == null`；测试不崩（catch 兜底行为保持）。[回归护栏，现状绿]
4. `stale result returning between the two suspension points is also gated`（v3 新增，oracle 核实建议：防未来把 guard 检查误置于第一个挂起点之后）
   - **Arrange**：`coEvery { videoRepository.getById(1L) } returns videoEntity(1L)`（快）；`coEvery { playbackRepository.getByVideoId(1L) } coAnswers { gate2.await(); null }`（**第二个**挂起点慢）；`coEvery { videoRepository.getById(2L) } returns videoEntity(2L)`；`coEvery { playbackRepository.getByVideoId(2L) } returns null`。
   - **Act**：`vm.loadVideo(1L)`（进入 getByVideoId(1) 门控）；`vm.loadVideo(2L)`；`advanceUntilIdle()`（#2 完整提交 video 2）；`gate2.complete(Unit)`；`advanceUntilIdle()`（#1 从第二个挂起点恢复）。
   - **Assert**：`assertEquals(2L, vm.uiState.value.videoItem?.id)` —— 过期请求在**任一**挂起点之后恢复都必须被拦下（若 guard 只放在 getById 之后、getByVideoId 之前，本用例红；现状无 guard 亦红）。

### 验收矩阵

| 命令 | 期望 |
|---|---|
| `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.player.PlayerViewModelLoadVideoTest'` | 全部通过（用例 1、2、4 由红转绿；3 现状绿护栏） |
| `compileDebugKotlin` / 全量 `testDebugUnitTest` | 通过、无回归（`PlayerViewModelSavedStateTest` 等既有 player 测试不变绿→红） |

### 风险与守卫

- **CancellationException 先传播（红线 #3）**：现有 catch 结构原样保留（CE rethrow 在前），generation 逻辑不引入新 catch。
- **敏感信息（红线 #6）**：沿用现有日志 `"Failed to load video $videoItemId"`（仅 id，与现状一致；id 非红线项——path/volumeId/凭据才是）。
- **Player UX 约定**：不改任何 UI 状态字段语义；`PlayerScreen.kt:121` 的 `LaunchedEffect(videoItemId)` 触发模式不变。
- **性能约定**：无新 Flow、无重组面变化；AtomicLong 开销可忽略。
- 残余：`ImageViewerViewModel.loadImage` 同构竞态不在本 Lane（见文末风险清单），本修复不改它。

### 涉及文件（写域）

- 生产：`app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt`（`loadVideo` 函数级重写，与 BUG-019 合并为一次编辑——同一函数体，先落 BUG-015 generation 骨架再嵌 BUG-019 循环，或一次成型；见 BUG-019 节"合并策略"）
- 测试：`app/src/test/java/cn/vectory/syncplayer/player/PlayerViewModelLoadVideoTest.kt`（新建，与 BUG-019 共用该文件）

---

## BUG-016 TrashToggle 覆盖挂起的 HardDelete——删除意图被静默丢弃

### 现状与根因（源码实读核对）

**`app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt`** — auto-commit 只存在于 `performHardDelete`（:218-243），两个 trash toggle 入口无条件 `undoState.set(TrashToggle)`：

有 base-path guard 版（:142-145，`undoState.set` 位于 :144）：

```kotlin
when (val result = fileOps.toggleTrashedChecked(videoItemId, basePaths)) {
    is ToggleTrashResult.Success -> {
        undoState.set(UndoableDelete.TrashToggle(videoItemId, previousTrashed))  // :144 无条件覆盖
        onSuccess()
    }
    ...
}
```

无 guard 版（:181-182）：

```kotlin
playbackRepository.toggleTrashed(videoItemId)
undoState.set(UndoableDelete.TrashToggle(videoItemId, previousTrashed))  // :182 同样无条件
```

对比 `performHardDelete` :218-243 的既有防护：

```kotlin
val existing = undoState.get()
if (existing is UndoableDelete.HardDelete && existing.id != videoItemId) {
    undoState.set(null)
    try {
        val result = withContext(Dispatchers.IO) { fileOps.deleteMediaById(existing.id) }  // auto-commit
        ...
    } ...
}
undoState.set(UndoableDelete.HardDelete(videoItemId))
```

**缺陷序列（R2 报告 C5 已实锤，`coVerify(exactly=0)`）**：浏览器对 item 10 `performHardDelete`（延迟删除挂起）→ 用户对 item 20 `performTrashToggle`（任一版本）→ `undoState` 被直接覆盖为 `TrashToggle(20)` → snackbar 到期 `commitDeferredDelete(expectedId=10)` 命中 :289 的 expectedId 不匹配返回 `NothingToUndo` → `deleteMediaById(10)` **从未执行** → UI 已把 10 从列表移除，但密文/缩略图/DB 行全部残留（用户视角"删除成功"，实际产生孤儿密文）。类 KDoc :71-73 明确承诺 *"Sequential hard deletes auto-commit the old pending delete before setting the new one, so no pending delete is ever silently lost"* —— 承诺只覆盖了 HardDelete→HardDelete，遗漏 HardDelete→TrashToggle。

### 修复方案（提取共享 auto-commit，三个入口统一防护）

在 `UndoDeleteUseCase` 内提取私有 suspend 函数（命名沿用本仓库 `*Locked` 后缀约定，表示"须在 [mutex] 持有下调用"）：

```kotlin
/**
 * Auto-commit a pending hard delete that is about to be overwritten by the incoming
 * operation. Must be called under [mutex]. Mirrors the existing performHardDelete
 * contract: a failed auto-commit surfaces via [onError] but does not block the incoming
 * operation.
 *
 * HardDelete -> HardDelete for the SAME id keeps the old pending state (the delete
 * intent continues, only the snackbar window resets). Any other overwrite of a
 * pending HardDelete (incl. TrashToggle) must commit it first or the delete intent
 * is silently lost (BUG-016).
 *
 * Signature note (oracle #6): takes the incoming op's type/id as plain values, NOT an
 * [UndoableDelete.TrashToggle] instance — the toggle call sites read `previousTrashed`
 * only AFTER entering the lock, so a TrashToggle value cannot exist yet at the
 * auto-commit insertion point without an undefined-variable error.
 */
private suspend fun autoCommitPendingHardDeleteLocked(
    incomingId: Long,
    incomingIsHardDelete: Boolean,
    onError: (String) -> Unit,
) {
    val existing = undoState.get()
    if (existing !is UndoableDelete.HardDelete) return
    if (incomingIsHardDelete && incomingId == existing.id) return
    undoState.set(null)
    try {
        val result = withContext(Dispatchers.IO) { fileOps.deleteMediaById(existing.id) }
        when (result) {
            is DeleteMediaResult.Success,
            is DeleteMediaResult.SkippedNotFound -> { /* committed cleanly */ }
            is DeleteMediaResult.Failed -> {
                Log.e(TAG, "Auto-commit of old pending delete failed id=${existing.id}: ${result.error.code}", result.error)
                onError(syncErrorMessage(result.error, context))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Failed to auto-commit old pending delete id=${existing.id}", e)
        onError(syncErrorMessage(e, context))
    }
}
```

调用点改造（均在 `mutex.withLock` 内、`undoState.set(TrashToggle/HardDelete)` 之前；**插入位置有唯一无歧义顺序**——oracle #6）：

1. `performHardDelete`（:217-247）：`mutex.withLock { autoCommitPendingHardDeleteLocked(videoItemId, incomingIsHardDelete = true, onError); undoState.set(...) }` —— 与现有 :218-243 行为逐语句等价（含同 id 跳过），纯提取重构；放 withLock 首行。
2. `performTrashToggle`（guarded，:136-152）：插在 **:140 `previousTrashed` 读取之后、:142 `toggleTrashedChecked` 之前**：`autoCommitPendingHardDeleteLocked(videoItemId, incomingIsHardDelete = false, onError)` —— 位置在读取之后避免引用未声明变量；在 toggle 之前保证旧 pending delete 先于新操作状态被提交。
3. `performTrashToggleNoGuard`（:169-192，实读核对）：该入口**没有 onError 重载**（签名 `(videoItemId, scope, errorFlow: MutableStateFlow<String?>, onSuccess)`，错误经 :189 `errorFlow.value = syncErrorMessage(...)` 上报）——插在 **:179 `previousTrashed` 读取之后、:181 `toggleTrashed` 之前**，onError 显式传 lambda：`autoCommitPendingHardDeleteLocked(videoItemId, incomingIsHardDelete = false) { errorFlow.value = it }`（oracle #6：不存在"onError 对应 errorFlow 变体"这一调用形式）。

auto-commit 失败语义与既有 HardDelete 分支**对称**（既有测试 :545 `...surfaces Failed of auto-committed old pending and still records new id` 已锁定该契约）：上报 `onError` 后继续本次 toggle 并记录 `TrashToggle`。选择对称而非"失败中止"的理由：与已发布行为可预测地一致；失败经 `syncErrorMessage`（错误码→资源文案）上达用户，不静默；toggle 本身是可逆软操作，重试无损。

类 KDoc :71-73 的承诺句更新为覆盖 TrashToggle：*"Sequential hard deletes auto-commit the old pending delete before setting the new one"* → *"Any new undoable operation that would overwrite a pending hard delete (another hard delete, or a trash toggle) auto-commits it first, so no pending delete is ever silently lost"*。

### TDD 测试计划

文件：`app/src/test/java/cn/vectory/syncplayer/browser/UndoDeleteUseCaseTest.kt` 追加（沿用现有模式；涉及真实 `Dispatchers.IO` 的用例照抄 :358-386 的 `runBlocking + CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) + pollUntil` 写法——`pollUntil` helper 已存在于 :607）。

先写用例（现状红）：

1. `trash toggle auto commits pending hard delete instead of overwriting it`（NoGuard 路径，对齐报告 C5 序列）
   - **Arrange**：`useCase.performHardDelete(10L, scope, errorFlow)` + `pollUntil { getPendingId() == 10L }`；`coEvery { fileOps.deleteMediaById(10L) } returns DeleteMediaResult.Success(10L)`；`coEvery { playbackRepository.getByVideoId(20L) } returns null`；`coEvery { playbackRepository.toggleTrashed(20L) } returns Unit`。
   - **Act**：`useCase.performTrashToggleNoGuard(20L, scope, errorFlow)`；`pollUntil { getPendingId() == 20L }`。
   - **Assert**：`coVerify(exactly = 1) { fileOps.deleteMediaById(10L) }`（现状 0 —— 红）；`assertEquals(20L, useCase.getPendingId())`；随后 `useCase.commitDeferredDelete(expectedId = 10L)` 返回 `UndoResult.NothingToUndo`（auto-commit 已消化，无双重删除面）。
2. `guarded trash toggle auto commits pending hard delete instead of overwriting it`
   - 同 1，但走 `performTrashToggle(20L, matchingBasePaths, scope, errorFlow)`，mock `fileOps.toggleTrashedChecked(20L, basePaths) returns ToggleTrashResult.Success`（写法对齐现有 :110）。
   - **Assert**：`coVerify(exactly = 1) { fileOps.deleteMediaById(10L) }`；`getPendingId() == 20L`。
3. `trash toggle surfaces failed auto commit and still records the toggle`（失败分支契约，对称 :545；oracle #6 改写——NoGuard 无 onError 参数，用真实 flow）
   - **Arrange**：pending `HardDelete(10L)`；`coEvery { fileOps.deleteMediaById(10L) } returns DeleteMediaResult.Failed(10L, SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.DELETE))`；**真实** `val errorFlow = MutableStateFlow<String?>(null)` 传入 `performTrashToggleNoGuard(20L, scope, errorFlow)`（onError lambda 即 `{ errorFlow.value = it }`）；需断言"恰一次错误通知"时用计数 adapter（镜像 `PlayerViewModel.kt:130-139` 的 value 拦截模式）：
     ```kotlin
     val errorCount = AtomicInteger(0)
     val countingFlow = object : MutableStateFlow<String?> by errorFlow {
         override var value: String?
             get() = errorFlow.value
             set(v) { errorFlow.value = v; if (v != null) errorCount.incrementAndGet() }
     }
     ```
   - **Act**：`useCase.performTrashToggleNoGuard(20L, scope, countingFlow)`；`pollUntil { getPendingId() == 20L }`。
   - **Assert**：`assertNotNull("failed auto-commit must surface to errorFlow", errorFlow.value)`（真实 flow 非空）；`assertEquals(1, errorCount.get())`（恰一次）；`getPendingId() == 20L`；`coVerify(exactly = 1) { playbackRepository.toggleTrashed(20L) }`（toggle 继续，与 HardDelete 分支对称）。
4. `same-id hard delete twice still keeps pending without auto commit`（提取重构的回归护栏，现状绿）
   - **Act**：`performHardDelete(10L, ...)` 两次（pollUntil 各一次）。
   - **Assert**：`coVerify(exactly = 0) { fileOps.deleteMediaById(any()) }`；`getPendingId() == 10L`。

### 验收矩阵

| 命令 | 期望 |
|---|---|
| `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.browser.UndoDeleteUseCaseTest'` | 全部通过（新增 4 + 既有全部不回归，特别是 :358/:545/:574/:594） |
| `compileDebugKotlin` / 全量 `testDebugUnitTest` | 通过、无回归 |

### 风险与守卫

- **CancellationException 先传播（红线 #3）**：提取函数内 `catch (CancellationException) { throw e }` 先于 `catch (Exception)`（草案已含）；CE 不被伪装成 onError。既有测试 :470/:517 模式延续。
- **结构化错误不回退 Throwable.message（红线 #7；v4 oracle #7 措辞更正）**：失败路径全走 `SyncException.code → syncErrorMessage(资源文案)`；日志沿现状 `:233` 的诊断形态（`Log.e(TAG, ..., e)` / 传 `result.error`——**Throwable 对象含 cause 链**，非"只打 code"）——本改动**不新增**任何携带 path/凭据/volumeId 的日志输出，Throwable 诊断边界为仓库既有约定。
- **卷所有权 fail-closed（红线 #2）**：auto-commit 调用的 `fileOps.deleteMediaById` 内部自带 N3 卷守卫（`FileOperationsUseCase.kt:482-493`，volumeId 不匹配→index-only delete），本改动不改其语义；浏览器/手动路径 index-only delete 返回成功为 AGENTS.md 认可的合法语义。
- **undo 幂等/expectedId 语义不变**：`undo`/`commitDeferredDelete`/`commitPendingSafely`（:261-347）零改动。
- 行为变化声明：TrashToggle 现在可能触发一次 `deleteMediaById`（原先静默覆盖）——这正是修复目的；失败时用户会看到错误提示（原先静默丢失）。

### 涉及文件（写域）

- 生产：`app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt`（新增私有函数、三处调用点、KDoc 两行）——与 Lane2（DAO 层）零交集
- 测试：`app/src/test/java/cn/vectory/syncplayer/browser/UndoDeleteUseCaseTest.kt`（追加用例）

---

## BUG-017 Share 临时文件同名冲突——第二次分享覆盖第一次的文件

### 现状与根因（源码实读核对）

**`app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt:260-264`** — 目标文件名只做 sanitize，无唯一化：

```kotlin
ShareTempFiles.cleanupOldShareTempFiles(context)
val safeName = ShareTempFiles.sanitizeFileName(video.fileName)
val outFile = File(ShareTempFiles.shareDir(context), safeName)   // :263 同名 → 同一路径
decryptWholeTo(video, outFile)                                    // :264
```

`decryptWholeTo` :315 以 `FileOutputStream(outFile).use { ... }` 打开——`FileOutputStream` 默认truncate，**原地截断重写**已有文件。

**`app/src/main/java/cn/vectory/syncplayer/ui/util/ShareTempFiles.kt:84-103`** — `sanitizeFileName` 只保证扁平叶子名，不做区分；:29 `FALLBACK_NAME = "video.mp4"` 意味着所有畸形名（空、全点、NUL、遍历）都坍缩到同一个叶子。对象内无任何唯一名 API（已通读全文核实）。

**后果（R2 报告 B12）**：DB 唯一键是 (relativePath, relationId)，不同目录同名文件是常态（folderA/ep1.mp4 与 folderB/ep1.mp4）。第一次 share ep1(A) → 接收方 A 拿到 URI 还在读 → 第二次 share ep1(B) 解密截断同一 `share/ep1.mp4` → A 读到 B 的明文（隐私错发）或读到截断中途的损坏流。1h TTL 清扫（`DEFAULT_MAX_AGE_MS`）救不了正在进行中的读取。

### 修复方案（id 后缀 + 存在即递增的独占创建）

分两层，`ShareTempFiles` 提供原语、`buildShareIntent` 消费：

**`ShareTempFiles.kt` 新增**：

```kotlin
/**
 * Create (exclusively) the temp file to decrypt a share of video [videoId] into.
 *
 * BUG-017: two entities may share the same [sanitized] leaf name (DB uniqueness is
 * (relativePath, relationId), not fileName; all malformed names collapse to
 * [FALLBACK_NAME]). The file name therefore carries the video id, and if a previous
 * share of the same id is still on disk (recipient may still be reading it), a
 * monotonically increasing suffix yields a fresh file instead of truncating the
 * in-use one. All leftovers remain subject to the TTL sweep.
 *
 * [sanitized] MUST already be a [sanitizeFileName] output (flat, non-empty leaf).
 */
private const val MAX_LEAF_BYTES = 240  // 完整叶名（stem+后缀+ext）的 UTF-8 字节预算 < 常见 NAME_MAX(255)（v3 oracle #7）
private const val MAX_EXT_BYTES = MAX_LEAF_BYTES / 2   // 受限扩展名上限：为 stem 与 "-<id>[-n]" 后缀保底留量
private const val FALLBACK_EXT = ".bin"  // 超长 ext 的固定 ASCII 回退（4 字节，必在预算内；v5 更正字节数）

fun uniqueShareFile(context: Context, sanitized: String, videoId: Long): File {
    val dir = shareDir(context)
    val dot = sanitized.lastIndexOf('.')
    val stem = if (dot > 0) sanitized.substring(0, dot) else sanitized
    val rawExt = if (dot > 0) sanitized.substring(dot) else ""   // 含 '.'；无扩展名即空串
    // v4 oracle #1（死循环修复）：截断只发生在 stem 上——"-<videoId>[-n]" 后缀完整保留，
    // 因此 n 每递增一次候选名必然变化，createNewFile() 的占用检查最终必然放行（磁盘可写前提下）。
    // ext 单独限长（截断或回退 .bin），保证任何分支（含 stem 预算非正的回退分支）的完整叶名字节数 ≤ MAX_LEAF_BYTES。
    val ext = if (rawExt.isEmpty()) "" else truncateUtf8(rawExt, MAX_EXT_BYTES) ?: FALLBACK_EXT
    val extBytes = ext.toByteArray(Charsets.UTF_8).size
    var n = 0
    while (true) {
        val suffix = if (n == 0) "-$videoId" else "-$videoId-$n"    // 后缀不截断
        val stemBudget = MAX_LEAF_BYTES - extBytes - suffix.toByteArray(Charsets.UTF_8).size
        // 预算装不下 stem 首字符（或预算非正）→ ASCII 回退 stem（"share"，5 字节）；reachable 分支下
        // 5 + 后缀 + ext ≤ MAX 成立（ext ≤ 120、后缀个位~两位十位数），非正预算仅在天文数字 n 时不可达
        val stemPart = if (stemBudget <= 0) "share" else truncateUtf8(stem, stemBudget) ?: "share"
        val candidate = File(dir, "$stemPart$suffix$ext")
        if (candidate.createNewFile()) return candidate             // 原子独占创建；false=已被占用
        check(++n < 1_000) { "uniqueShareFile exhausted 1000 candidate attempts for id=$videoId" }
        // guard 语义（v5 oracle #4）：单次调用**最多 1000 次候选尝试**（n=0..999），候选耗尽即
        // 显式失败（IllegalStateException，可诊断）——不是对收敛性的证明，是"绝不静默挂死"的保底。
    }
}

/** 按 UTF-8 字节预算在 Unicode code point 边界截断（不劈开代理对/surrogate pair）；预算装不下首字符返回 null（调用方走 ASCII 回退）。 */
private fun truncateUtf8(s: String, maxBytes: Int): String? { /* 按 codepoint 累积字节实现 */ }
```

正确性论证：`createNewFile()` 是原子 check-and-create（VFS 层 O_CREAT|O_EXCL 语义），同进程内无 TOCTOU；`stem` 非空由 sanitize 契约保证（输出为空即 FALLBACK_NAME；`trimStart('.')` 使首字符非点，`dot > 0` 时 stem 至少 1 字符）；无扩展名（README）走 `stem-id` 分支；**收敛性**：候选名 = `截断(stem) + 完整后缀 + 受限 ext`——后缀含递增 `n` 且从不被截断，占用冲突时下一候选名严格不同，冲突数上界 = 目录内同前缀文件数（有限），故循环必然终止；`check(n < 1_000)` 把单次调用限定在**最多 1000 次候选尝试**、耗尽后显式失败（可诊断的 `IllegalStateException`，经 `buildShareIntent` 既有异常边界转为分享失败返回）——guard 是"绝不静默挂死"的保底，不替代收敛性论证。**字节预算不变式**：所有分支的完整叶名 = stemPart(≤stemBudget) + 后缀 + ext(≤MAX_EXT_BYTES) ≤ MAX_LEAF_BYTES——回退分支（stem 预算非正→"share"）同样不保留超长 ext（ext 已先经 `MAX_EXT_BYTES` 截断或 `FALLBACK_EXT` 回退）；截断按 code point 边界进行，`truncateUtf8` 不产生孤立代理项。极端长名的可见名变化（含超长 ext 变 `.bin`）是可接受代价（仅影响展示名，id 后缀仍保证唯一）。TTL 语义不变：新文件 `lastModified` = 创建时刻，1h 后照常可清扫。

**`FileOperationsUseCase.kt:262-263` 替换（含解密准备阶段的清理责任，v3 oracle #7）**：

```kotlin
val safeName = ShareTempFiles.sanitizeFileName(video.fileName)
val outFile = ShareTempFiles.uniqueShareFile(context, safeName, video.id)
try {
    decryptWholeTo(video, outFile)
} catch (t: Throwable) {
    // 预创建文件的清理责任必须覆盖「从创建到解密返回」整段：decryptWholeTo 内层的
    // outFile.delete()(:335) 只兜 FileOutputStream 打开之后的失败——openCiphertextRead(:309)
    // 在其内层 try 之外，contentKey()/进入 IO 前的失败会遗留预创建的空文件。
    // 重复 delete 无害（decryptWholeTo 已删过则为 no-op）；CE 清理后原样重抛（红线 #3）。
    runCatching { outFile.delete() }
    throw t
}
```

（删除原 `File(ShareTempFiles.shareDir(context), safeName)` 行；`video.id` 在作用域内已存在，:239。解密**之后**的失败（如 `getUriForFile` 返回 null）不触发清理——产物无害且受 TTL 清扫，语义与现状一致。）

**既有测试的连锁更新（必做）**：`FileOperationsUseCaseTest.kt:507-543` 的截断用例断言 `shareDir.listFiles().filter { it.name == "movie.mp4" }` 为空——目标名现为 `movie-107.mp4`，断言改为"`share` 目录下无任何文件残留"（`assertEquals(0, shareDir.listFiles().orEmpty().size)`），语义更强且与命名解耦。

### TDD 测试计划

**A. `app/src/test/java/cn/vectory/syncplayer/ui/util/ShareTempFilesTest.kt` 追加**（纯 JVM，沿用 `TemporaryFolder` + `CacheOnlyContext` 写法）：

1. `uniqueShareFile embeds video id in the leaf name`
   - **Arrange**：`val dir = shareDir(ctx)`。
   - **Act**：`val f = uniqueShareFile(ctx, "ep1.mp4", 42L)`。
   - **Assert**：`assertEquals("ep1-42.mp4", f.name)`；`assertTrue(f.exists())`；`assertEquals(0L, f.length())`（空文件，等解密覆写）。
2. `uniqueShareFile handles extension-less names`
   - **Assert**：`uniqueShareFile(ctx, "README", 7L).name == "README-7"`。
3. `uniqueShareFile yields distinct files for same name different ids`
   - **Act**：`uniqueShareFile(ctx, "ep1.mp4", 1L)`、`uniqueShareFile(ctx, "ep1.mp4", 2L)`。
   - **Assert**：两个 `File` 路径不同、都存在（同叶子名跨实体永不互踩——BUG-017 核心）。
4. `uniqueShareFile falls back to suffix when same id file already exists`
   - **Arrange**：`val inUse = File(dir, "ep1-42.mp4").apply { writeBytes(ByteArray(64) { 7 }) }`——**预填非空哨兵内容**（v3 oracle #7：空文件"长度未变"不证未截断，须以内容验证）。
   - **Act**：`val f2 = uniqueShareFile(ctx, "ep1.mp4", 42L)`。
   - **Assert**：`assertEquals("ep1-42-1.mp4", f2.name)`；`assertArrayEquals(哨兵 64 字节, inUse.readBytes())`（in-use 文件零覆写——内容级断言，非仅 `length()`）。
5. `uniqueShareFile names are safe for FALLBACK collisions`
   - **Act/Assert**：`uniqueShareFile(ctx, FALLBACK_NAME, 1L).name == "video-1.mp4"`、`uniqueShareFile(ctx, FALLBACK_NAME, 2L).name == "video-2.mp4"`。
6. `long ASCII stem is truncated but suffix and budget hold`（v4 oracle #1）
   - **Arrange**：`sanitizeFileName` 输出 `"a".repeat(300) + ".mp4"`（经 sanitize 后仍超长；若 sanitize 截断则直接构造等价超长输入——测试对象是 `uniqueShareFile` 本身，sanitized 形参不设防）。
   - **Act**：`val f = uniqueShareFile(ctx, longName, 42L)`。
   - **Assert**：`f.name.toByteArray(UTF_8).size <= 240`；`f.name.endsWith("-42.mp4")`（**完整后缀+ext 保留**）；`f.name.startsWith("aaa")`（stem 前缀保留）；`f.exists()`。
7. `long CJK/emoji stem truncates at code point boundary`（v4 oracle #1：不劈 surrogate pair）
   - **Act**：`uniqueShareFile(ctx, "视".repeat(200) + ".mp4", 42L)`（600 字节 stem）与 `uniqueShareFile(ctx, "😀".repeat(100) + ".mp4", 42L)`（400 字节 stem）。
   - **Assert**：两结果的叶名字节 ≤ 240；以 `String.codePointCount`/`toByteArray(UTF_8)` 校验截断点在 code point 边界——叶名所含字符串无孤立代理项（`f.name` 可无损编解码且截断后首尾字符完整）；`endsWith("-42.mp4")`。
8. `over-long extension is capped or falls back`（v4 oracle #1）
   - **Act**：`uniqueShareFile(ctx, "ep1." + "x".repeat(200), 42L)`（ext 201 字节）。
   - **Assert**：叶名字节 ≤ 240；ext 部分被截到 ≤ `MAX_EXT_BYTES`（或回退 `.bin`）；文件创建成功、后续同 id 分享仍收敛（`-42-1`）。
9. `repeated shares of the same long name converge within bounds`（v4 oracle #1；**v5 oracle #4 表述收窄**）
   - **Arrange**：超长名（同 6）；测试侧 `repeat(100) { uniqueShareFile(ctx, longName, 42L) }`——`repeat(100)` 只限定**测试发起的调用次数**（100 个文件），**不约束单次调用内部的候选循环**；单次调用的挂死防护由生产 `check(n < 1_000)` guard 承担（最多 1000 次候选尝试、耗尽显式失败——见方案节）。本用例**不声称**"测试本身防单次调用挂死"（未引入独立墙钟/测试进程超时边界，不作此宣称）。
   - **Assert**：100 个叶名两两不同（后缀 `-42`, `-42-1`…`-42-99` 递增可见）；全部字节 ≤ 240；全部 `exists()`。
10. `long names with different ids never collide`（v4 oracle #1）
   - **Act**：`uniqueShareFile(ctx, longName, 1L)`、`uniqueShareFile(ctx, longName, 2L)`。
   - **Assert**：路径不同、都存在、都含各自完整 id 后缀。
11. `guard fails explicitly when all candidates are pre-occupied`（v5 oracle #4 新增——`check(n < 1_000)` 的行为锁）
   - **Arrange**：以测试内**独立小工具**复算候选名序列（同一预算规则：`truncateUtf8(stem, 240 - extBytes - suffixBytes)` + 完整后缀 + 受限 ext，n=0..999）并**预创建全部 1000 个候选**（各写入互异的哨兵字节）。工具与生产重复同规则属有意为之：若日后预算规则改变使两序列分叉，本用例会因"未耗尽而成功创建"转红——恰好把规则漂移暴露为红灯（guard 锁的附带价值）。
   - **Act**：`val thrown = runCatching { uniqueShareFile(ctx, longName, 42L) }.exceptionOrNull()`。
   - **Assert**：①`assertTrue(thrown is IllegalStateException)`（候选耗尽 → guard 显式失败，**不是**挂死或静默返回）；②预占的 1000 个文件内容逐字节不变（**零覆写**）；③`share` 目录文件数 == 1000（**零额外产物**——失败的尝试不创建第 1001 个文件）；④（UseCase 层语义）该 `IllegalStateException` 沿 `buildShareIntent` 既有 `catch (Exception)` 边界转为分享失败（返回 null），不逃逸为崩溃——由既有异常边界承担，无需新用例。

**B. `app/src/test/java/cn/vectory/syncplayer/browser/FileOperationsUseCaseTest.kt` 追加**（受限于既有注释 :419-422 的约束——happy path 的 `FileProvider.getUriForFile` 需要真 Android Context，纯 JVM 不可用；断言前移到文件系统层，用真密文构造沿用 :512-524 写法）：

6. `buildShareIntent for two same-named videos writes two distinct temp files`
   - **Arrange（v3 oracle #4：IV 显式绑定——`videoEntityWithIv()` fixture 每次调用 `newIv()`（:436），若沿用其随机 IV，实体 IV ≠ 加密 IV，GCM 认证先失败、解密走异常清理路径，永远到不了文件断言；v4 oracle #3：stub 按真实签名分发）**：每实体独立密文+独立 IV，实体 `.copy(iv = 加密所用 iv)` 绑定；**生产调用点实为 `encryptedMediaStore.openCiphertextRead(video.id)`（`FileOperationsUseCase.kt:309`，`Long` id 入参）**——stub 按 id（非 cipherUri）分发，每次 `answers` 返回对应密文的**新** handle：
      ```kotlin
      val plain = ByteArray(70_000) { ... }                      // 照抄 :513-521 的密文构造
      val (ivA, cipherA) = encryptFor(plain, videoId = 201L)     // SecretKeySpec + deriveContentKey + encryptStream
      val (ivB, cipherB) = encryptFor(plain, videoId = 202L)
      coEvery { videoRepository.getById(201L) } returns videoEntityWithIv(id = 201L, fileName = "ep1.mp4", fileSize = plain.size.toLong()).copy(iv = ivA)   // fixture 参数为 Long
      coEvery { videoRepository.getById(202L) } returns videoEntityWithIv(id = 202L, fileName = "ep1.mp4", fileSize = plain.size.toLong()).copy(iv = ivB)
      coEvery { encryptedMediaStore.openCiphertextRead(201L) } answers { MemSeekableHandle(cipherA) }   // 按 id 分发；每次调用新 handle
      coEvery { encryptedMediaStore.openCiphertextRead(202L) } answers { MemSeekableHandle(cipherB) }
      every { encryptedMediaStore.contentKey() } returns contentKey
      ```
      （两实体 id 必须可区分，stub 按入参 id 分发独立密文与 IV。）
    - **Act**：`useCase.buildShareIntent(201L)`；`useCase.buildShareIntent(202L)`（`getUriForFile` 在 mock context 下抛异常 → 函数按契约返回 null，但解密产物已落盘——恰好暴露文件层行为，无需 Robolectric）。
    - **Assert**：`val names = File(cacheRoot, "share").list()!!.toSet()`；`assertTrue("ep1-201.mp4" in names && "ep1-202.mp4" in names)`；两文件 `readBytes()` **逐字节等于 plain**（内容级断言，非仅长度——同时证明 IV 绑定正确、解密真实走通）（现状：仅一个 `ep1.mp4` —— 红）。
7. `second share of the same video does not truncate the first temp file`
   - **Arrange**：同 6 但仅 id 201；`coEvery { encryptedMediaStore.openCiphertextRead(201L) } answers { MemSeekableHandle(cipherA) }`（**每次调用返回新 handle**——`MemSeekableHandle` 持有读位置状态，复用同一实例第二次读会停在 EOF）。
   - **Act**：`val bytesAfterFirst = buildShareIntent(201L).let { File(cacheRoot, "share/ep1-201.mp4").readBytes() }`；再次 `buildShareIntent(201L)`。
   - **Assert**：`File(share/ep1-201.mp4).readBytes()` 与 `bytesAfterFirst` 逐字节相同（in-use 文件零覆写，内容级）；`share/` 下出现 `ep1-201-1.mp4` 且其内容 == plain（第二次解密同样成功——证明走的是新文件而非覆写旧文件）。
8. `failed decrypt preparation leaves no pre-created temp file behind`（v3 oracle #7 新增——覆盖 `openCiphertextRead`（:309，在内层 try 之外）抛出时预创建文件的清理责任）
   - **Arrange**：照 6 绑定一个实体；`coEvery { encryptedMediaStore.openCiphertextRead(201L) } throws SyncException(SyncErrorCode.STORAGE_ERROR, SyncOperation.READ)`（按 id stub；打开密文句柄失败——该失败点在 `decryptWholeTo` 内层 catch 覆盖范围之外）。
   - **Act**：`useCase.buildShareIntent(201L)`。
   - **Assert**：`assertEquals(0, File(cacheRoot, "share").list()!!.size)`——预创建文件已随外层清理删除，无空残留（红：无 call-site 外层 try/catch 时此用例失败，`ep1-201.mp4` 空文件残留）。

### 验收矩阵

| 命令 | 期望 |
|---|---|
| `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.ui.util.ShareTempFilesTest'` | 全部通过（新增 11（v5 +用例 11 guard 锁）+ 既有 25） |
| `... --tests 'cn.vectory.syncplayer.browser.FileOperationsUseCaseTest'` | 全部通过（新增 3 + 更新的截断用例 + 既有） |
| `compileDebugKotlin` / 全量 `testDebugUnitTest` | 通过、无回归 |

### 风险与守卫

- **敏感信息不进日志/UI（红线 #6）**：`uniqueShareFile` 文件名含明文 `fileName`——这是 share 功能的**固有契约**（文件名本就要展示给接收方，`buildShareIntent` KDoc :219-224 明示），非新增泄漏面；不含 path/volumeId。日志零新增。
- **AEAD v2 格式不变（红线 #4）/ 随机读失败必抛（红线 #5）**：`decryptWholeTo` 零改动；截断/篡改路径（`FileOperationsUseCaseTest:507`）继续生效，仅残留断言的文件名匹配方式更新。
- **CancellationException（红线 #3；v4 oracle #7 措辞更正）**：call-site **新增** cleanup catch（`catch (t: Throwable)`）——仅做 `runCatching { outFile.delete() }` 后**原样重抛**（CE 含在内：先清理、再重抛，不吞、不伪装）；既有内层 CE-first catch（:275-279）不动。
- **磁盘增长**：同 id 重复分享产生 `-1/-2...` 序列文件，均受 1h TTL 清扫；量级 = 用户分享频次，与现状（每次 share 留一文件）同阶。
- **长名截断的可见性（v3 oracle #7）**：字节预算只在叶名逼近 NAME_MAX（255 字节）时触发——极端长名经字符边界截断或 ASCII 回退，接收方看到的文件名可能与原始名不同；这是"截断展示名"与"创建失败"之间的取舍，选择前者（分享仍可用），仅影响极端名。
- **解密准备期失败窗口（v3 oracle #7）**：预创建→`openCiphertextRead`/`contentKey()` 抛出的窗口由 call-site 外层 try/catch 兜底（用例 8 锁定）；解密成功后的失败（`getUriForFile` null）不清理——产物无害、受 TTL 清扫，与现状语义一致。
- **并发 share 同 id**：单 Activity 个人 app，两个 `buildShareIntent` 并发概率趋零；即便发生，`createNewFile` 原子性保证各自拿到不同文件，正确性不依赖串行。
- ~~Robolectric 备选说明~~（**v4 oracle #3 删除**）：原备选允许用例 6/7 退化为 `ShareTempFiles` 层纯文件系统验证——helper 测试不能替代 UseCase 接线证据（「UseCase 确实调用了唯一名 API 且把 id 传对」只有 buildShareIntent 层用例能证明），备选作废；若 mock-context 下 `getUriForFile` 行为不稳定，修 stub 而不是降级断言层。

### 涉及文件（写域）

- 生产：`app/src/main/java/cn/vectory/syncplayer/ui/util/ShareTempFiles.kt`（新增 `uniqueShareFile`（仅截 stem、后缀完整保留、收敛 guard，v4）+ `truncateUtf8` + `MAX_LEAF_BYTES`/`MAX_EXT_BYTES`/`FALLBACK_EXT` 常量）；`app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt`（:262-263 替换为唯一名调用 + 外层解密准备期清理 try/catch）——后者与 BUG-016 无交集（不同函数），与 Lane2（DAO）零交集
- 测试：`ShareTempFilesTest.kt`（追加 11 用例，含 5 个长名/收敛用例 v4 + guard 行为锁用例 11 v5）、`FileOperationsUseCaseTest.kt`（追加 3 用例 + 更新 :540-542 断言）

---

## BUG-019 自动推进到已删除的队列 id——静默无操作

### 现状与根因（源码实读核对）

**`app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt:212-239`** 的 `loadVideo`，`if (video != null)`（:216）无 else 分支：

```kotlin
fun loadVideo(videoItemId: Long) {
    viewModelScope.launch {
        try {
            val video = videoRepository.getById(videoItemId)
            if (video != null) {          // :216 —— video == null 时函数静默结束
                ...提交状态...
            }
            // ← 无 else：已删除的 id 零反馈、零推进
        } catch ...
    }
}
```

**触发链（调用点已核实，v3 oracle #2 补全 else 分支）**：`PlayerScreen.kt:163-175` 播放结束的 STATE_ENDED 处理：

```kotlin
val nextId = viewModel.getNextVideoId()
if (nextId != null) {
    viewModel.updateAutoSwitchTime()
    viewModel.saveProgress(...)
    viewModel.loadVideo(nextId)          // nextId 仍在 _allVideoIds 快照里
} else {
    if (!isExiting) { isExiting = true; currentOnExit() }   // ← 正常耗尽 = 退出播放器
}
```

关键事实（v3 oracle #2 核实）：**正常耗尽（`nextId == null`）的既有行为是 `currentOnExit()` 退出播放器，不是"停住当前视频"**。已删 id 场景（`nextId != null` 但条目实际已被后台同步删除）下 `loadVideo` 静默无操作，播放器停在已结束画面、既不推进也不退出——与正常耗尽终态**不一致**，这才是用户感知的 bug：多按一次才会发现队列其实已经没了。

`getNextVideoId()`（`PlayerViewModel.kt:350-355`）基于 `_allVideoIds`——该列表只在 VM 构造时消费一次（`videoQueue.consumeIds()` :180 或 SavedState/DB 回填 :188-209），**不随后台同步的删除而更新**。后台 SyncEngine 执行 DELETE_LOCAL 删掉队列中的下一个 id 后：当前视频播完 → 自动推进 `loadVideo(已删id)` → `getById` 返回 null → 无操作 → 播放器停在已结束的视频上，无错误、不退出（用户必须手动退出或点 prev/next）。prev/next 手势路径（:334-343/:344-355）同样受影响。

### 修复方案（跳过已删 id + 结构化耗尽终态，对齐 UI 实际行为）

与 BUG-015 同函数，合并落地：`loadVideo` 在 `getById` 返回 null 时沿 `_allVideoIds` 向后寻找下一个**存在**的条目；**耗尽则发布结构化终态 `queueExhausted`（非 ErrorEvent、无文案），由 UI 消费后退出播放器——与正常耗尽（STATE_ENDED `nextId==null` → `currentOnExit()`）终态一致**。完整函数骨架（含 BUG-015 generation guard）：

```kotlin
private val loadGeneration = AtomicLong(0L)

fun loadVideo(videoItemId: Long) {
    val generation = loadGeneration.incrementAndGet()
    viewModelScope.launch {
        try {
            _uiState.update { it.copy(queueExhausted = false) }   // 每次加载先清终态
            var candidateId = videoItemId
            var video = videoRepository.getById(candidateId)
            // BUG-019: the queue snapshot may reference ids that background sync has since
            // deleted — skip forward to the next id that still exists; on exhaustion publish
            // a structured terminal so the UI exits exactly like normal EOF.
            val ids = _allVideoIds.value
            var idx = ids.indexOf(candidateId)
            while (video == null && idx in 0 until ids.size - 1) {
                candidateId = ids[++idx]
                video = videoRepository.getById(candidateId)
            }
            if (video != null) {
                val record = playbackRepository.getByVideoId(video.id)
                if (generation != loadGeneration.get()) return@launch   // BUG-015
                ...原提交逻辑（字段不变）...
            } else {
                if (generation != loadGeneration.get()) return@launch   // BUG-015：耗尽发布同样受代次门控
                // Queue exhausted (or id not in queue at all) — keep the current video and
                // publish the structured terminal; the UI consumes it and exits. No
                // ErrorEvent (this is normal EOF, not an error) and no Context-derived copy
                // (i18n: structured state carries the semantics, UI layer owns wording).
                _uiState.update { it.copy(queueExhausted = true) }
                Log.w(TAG, "loadVideo: id=$videoItemId gone and no later queue item exists")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.e(TAG, "Failed to load video $videoItemId", e) }
    }
}
```

`PlayerUiState`（`PlayerViewModel.kt:53-74`）新增字段：`val queueExhausted: Boolean = false`。

**UI 消费（`PlayerScreen.kt`，v3 oracle #2 新增写域；v4 oracle #6 修正插入位置与终态核对）**——新增同型 effect，**放在 `currentOnExit` 声明（:155）之后**（放 `LaunchedEffect(videoItemId)`（:120-122）旁会前向引用尚未声明的 `currentOnExit`——编译错误，不可编译不算接线）：

```kotlin
// BUG-019: consume the structured exhaustion terminal — exit exactly like normal EOF.
LaunchedEffect(uiState.queueExhausted) {
    // key 用组合值触发 relaunch；判断体再核对 VM 当前终态（StateFlow 现值）——
    // 防止读取"已被新请求成功加载清除 flag"的旧 composition 值而误退出（v4 oracle #6）。
    // v5 oracle #2：PlayerScreen 实际参数/调用名为 viewModel（vm 是 VM 层测试的习惯名，
    // UI 文件内不存在）——不加别名、不扩大写域，直接用 viewModel。
    if (viewModel.uiState.value.queueExhausted && !isExiting) {
        isExiting = true
        currentOnExit()
    }
}
```

（`currentOnExit` :155 / `isExiting` 已在作用域；STATE_ENDED 的 `nextId==null` 分支 :169-174 **保持原样**——两条路径经 `isExiting` 守卫幂等汇合到同一 `currentOnExit()`。`onDispose`（:113）的最终 save 语义不受影响——耗尽时进度已由 STATE_ENDED 分支保存或视频已结束无增量。**交错正确性（v4 走查项）**：effect 体首查 `viewModel.uiState.value.queueExhausted`（VM 实时值）而非闭包捕获的 `uiState`——若旧请求置 true 后、effect 执行前新请求成功加载已清除 flag，实时值为 false，不退出；若旧请求发布 true 时新请求已在途，VM 侧 generation 检查根本不会置 true（用例 6 锁定）——`isExiting` 只负责防重复退出，不承担该交错的正确性。）

边界语义：
- `videoItemId` 不在 `_allVideoIds`（如 nav 直接 push 单个 id）：`idx == -1`，`idx in 0 until ...` 为 false，不跳过——但**同样会发布 `queueExhausted=true`**（id 不存在且无队列可搜=耗尽语义），UI 退出（比现状"静默黑屏"更可预期）。只处理"加载点"场景，窄聚焦。
- 连续多个已删 id：while 循环逐个探测至首个存在项或队列尾（`getById` 为 Room 主键查，量级 = 队列长度上界的稀疏探测，个人媒体库规模无性能问题；`getById` 为 suspend DAO 调用，Room 自动调度，无 Main 线程 IO）。
- 新代次请求到来时，旧代次的跳过循环可能在途——其提交与**耗尽发布**均被 BUG-015 的 generation 检查拦下；循环本身的额外 `getById` 调用是浪费但无害（用户连击场景代次淘汰极快）。
- 队列耗尽**不**发 `ErrorEvent`、不生成文案：这是正常 EOF 语义而非错误（红线 #7 的正确用法是"错误码→文案"，终态不是错误）；VM 以结构化布尔字段承载语义，UI 层拥有行为（退出）——**"VM 无 Context"不再被用作不能发结构化事件的论据**（v3 oracle #2：无 Context 只排除文案生成，不排除结构化终态）。
- **prev 空洞边界（不宣称修复）**：prev 命中已删 id 时（队列 [1, 删2, 当前3]，`getPrevVideoId(3)` 按快照返回 2），`loadVideo(2)` 的跳过逻辑向前搜索最终加载 **3**——prev 在空洞处退化为"回到当前"，而非回退到 1。这是快照式 prev 的固有语义，超出本 BUG（自动推进静默失败）范围，记录在案不额外处理。

### TDD 测试计划

文件：`app/src/test/java/cn/vectory/syncplayer/player/PlayerViewModelLoadVideoTest.kt`（与 BUG-015 共用，AAA 写法同前节）。

先写用例（现状红）：

1. `loadVideo skips a deleted id and advances to the next existing one`
   - **Arrange**：`every { videoQueue.consumeIds() } returns listOf(1L, 2L, 3L)`（构造期注入队列，对齐现有 SaveProgress 测试的 mock 点）；`coEvery { videoRepository.getById(1L) } returns videoEntity(1L)`；`coEvery { videoRepository.getById(2L) } returns null`（已被后台同步删除）；`coEvery { videoRepository.getById(3L) } returns videoEntity(3L)`；`coEvery { playbackRepository.getByVideoId(3L) } returns null`。
   - **Act**：`vm.loadVideo(1L)` 正常加载后，`vm.loadVideo(2L)`；`advanceUntilIdle()`。
   - **Assert**：`assertEquals(3L, vm.uiState.value.videoItem?.id)`（现状：videoItem 仍是 1L —— 红）；`coVerify(exactly = 1) { videoRepository.getById(3L) }`。
2. `loadVideo skips a run of consecutive deleted ids`
   - **Arrange**：队列 `listOf(1L, 2L, 3L, 4L)`，2、3 均为 null，4 存在。
   - **Act/Assert**：`vm.loadVideo(2L)` → `assertEquals(4L, vm.uiState.value.videoItem?.id)`。
3. `loadVideo publishes the structured exhaustion terminal when queue is exhausted after deleted id`
   - **Arrange**：队列 `listOf(1L, 2L)`；先加载 1；`getById(2L) returns null`。
   - **Act**：`vm.loadVideo(2L)`；`advanceUntilIdle()`。
   - **Assert**：`assertEquals(1L, vm.uiState.value.videoItem?.id)`（保持不变，不停在半加载态）；`assertTrue(vm.uiState.value.queueExhausted)`（**结构化终态已发布——UI 消费退出的契约点**）；`vm.uiState.value.error == null`（不发错误——正常 EOF 语义）。
4. `loadVideo for an id not in the queue publishes the exhaustion terminal`（v4 oracle #6 更名：原名"stays a silent no-op"与「idx==-1 也发布耗尽」的边界语义（见上）冲突——不在队列 = 无队列可搜 = 耗尽语义）
   - **Arrange**：空队列（`consumeIds() returns emptyList()` 走 SavedState 空→DB fallback `getAllIdsSorted() returns emptyList()`）；已加载 video 42；`getById(99L) returns null`。
   - **Act/Assert**：`vm.loadVideo(99L)` → `videoItem?.id == 42L`（不停在半加载态）**且 `queueExhausted == true`**（结构化终态发布——UI 消费退出契约点；现状无此字段 —— 红）。
5. `successful load clears a previously published exhaustion flag`（v3 新增）
   - **Arrange**：队列 `listOf(1L, 2L)`；`getById(2L) returns null`、`getById(1L) returns videoEntity(1L)`、`getByVideoId(1L) returns null`。
   - **Act**：`vm.loadVideo(2L)` → `assertTrue(uiState.queueExhausted)`（前置）；再 `vm.loadVideo(1L)`。
   - **Assert**：`assertFalse(vm.uiState.value.queueExhausted)` 且 `videoItem?.id == 1L`（终态可复位，不粘滞）。
6. `stale request resuming after a newer successful load must not publish exhaustion`（v4 oracle #6 新增；**v5 oracle #1 重写为确定性可执行交错**——harness 为 `StandardTestDispatcher` Main（:242）：两次 `loadVideo` 连续调用后不推进调度，旧请求**未必**已进门控、新请求**未必**先完成，交错不成立、删 guard 也可能假绿）
   - **Arrange**：队列 `listOf(1L, 2L)`；`val gate = CompletableDeferred<Unit>()`；`val entered = java.util.concurrent.atomic.AtomicBoolean(false)`（进入侧信道）；`coEvery { videoRepository.getById(2L) } coAnswers { entered.set(true); gate.await(); null }`（旧请求的 DB 读进门控即报告、随后被挂起——释放后它将走到"查不到且队尾"=耗尽分支）；`getById(1L) returns videoEntity(1L)`、`getByVideoId(1L) returns null`。
   - **Act/Assert**（v6 ora-8 定稿形态：可能失败的步骤整体进真实 `try`，`finally` 同时释放门控与推进调度，最终断言置于 `finally` 之后——任一步骤断言失败也不会残留挂起协程；`runCurrent()` 用 `runTest` 作用域内的 TestScope 扩展函数，**禁止** `testDispatcher.runCurrent()`（TestDispatcher 无该 API））：

     ```kotlin
     try {
         vm.loadVideo(2L)                       // 旧代次：launch 入队、尚未跑
         runCurrent()                           // 跑已排队任务至首个挂起点
         assertTrue(entered.get())              // 旧请求确已进入 gate 挂起（交错第一腿）
         vm.loadVideo(1L)                       // 新代次
         advanceUntilIdle()                     // 新请求完成；旧请求仍挂 gate.await()（虚拟时钟不跳过 CompletableDeferred）
         assertEquals(1L, vm.uiState.value.videoItem?.id)       // 放 gate 前：新请求已提交（第二腿）
         assertFalse(vm.uiState.value.queueExhausted)           // 放 gate 前：未带终态
     } finally {
         gate.complete(Unit)                    // 无论成败都放行旧请求
         advanceUntilIdle()                     // 旧请求恢复、走到耗尽分支、被 generation 检查拦下
     }
     assertEquals(1L, vm.uiState.value.videoItem?.id)           // 终态：未被旧请求覆盖
     assertFalse(vm.uiState.value.queueExhausted)               // 终态：旧请求恢复后不得发布耗尽
     ```

   - **变异验证（v6 ora-8 归属修正）**：对实现做一次变异——仅删除**耗尽发布点的 generation 检查**（保留成功提交点的检查）——本用例必须转红（旧请求恢复后 `queueExhausted` 被置 true，`finally` 后的最终 `assertFalse` 失败）。删除**成功提交 guard** 的 video 红证据**不由本用例承担**——由 BUG-015 既有用例 1（旧请求迟到覆盖，`videoItem` 断言红）与用例 4（第二挂起点 `getByVideoId` 门控——guard 只放在 `getById` 之后时红）正确覆盖；不新建重复用例。

**UI 消费的可验证性声明（v3 oracle #2；v4 oracle #6 补交错覆盖）**：`PlayerScreen.kt` 的 `LaunchedEffect(uiState.queueExhausted)` 接线无 Compose UI 测试基建（项目单测层无 compose-ui-test 依赖，不为一个 effect 引入），以 `compileDebugKotlin` + 走查验证——pattern 与既有 `LaunchedEffect(videoItemId)`（:120-122）同型，VM 侧 flag 语义（发布/清除/耗尽条件/**旧请求不发布**）由用例 3/5/6 完整锁定；走查明确覆盖「effect 执行时新请求已清除 flag」的交错（effect 体首查 `viewModel.uiState.value` 实时值，见方案节交错正确性段），`isExiting` 只防重复退出、不承担该交错。

### 验收矩阵

| 命令 | 期望 |
|---|---|
| `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.player.PlayerViewModelLoadVideoTest'` | 全部通过（BUG-015 的 4 + 本节 6 用例（v4 新增用例 6）） |
| `compileDebugKotlin`（含 `PlayerScreen.kt` 新 effect）/ 全量 `testDebugUnitTest` | 通过、无回归（重点盯 `PlayerViewModelSavedStateTest`——`SAVED_VIDEO_IDS_KEY` 回填路径未被触碰） |

### 风险与守卫

- **E8 不涉及**：本修复只消费已删除条目，不推导任何 DELETE。
- **敏感信息（红线 #6）**：新增日志仅含 `id`（与现有 `loadVideo` 的 `Log.e` 同级），无 path/volumeId。
- **CancellationException（红线 #3）**：catch 结构原样；while 循环中无吞 CE 的调用。
- **Player UX 约定（conventions.md §Player UX）**：不新增可见 UI 元素、不改错误展示路径；耗尽终态=退出，与正常耗尽（STATE_ENDED else 分支）行为一致——修复的是"不一致"，不是引入新交互。
- **性能约定**：稀疏探测最多 `队列长度-1` 次主键查询，仅发生在删除空洞实际命中时；无重型 Flow 新增（`queueExhausted` 借道既有 `_uiState`，非独立高频 StateFlow，与架构约定的状态拆分不冲突——低频终态字段）。
- **分层声明（v3 oracle #2 修订）**：调用点防御（自动推进/prev/next/LaunchedEffect 全部经 `loadVideo`）统一收敛在 VM 单点；UI 层（`PlayerScreen.kt`）**只**消费结构化终态（一个 LaunchedEffect），不含任何跳过/探测逻辑——写域由此新增 `PlayerScreen.kt` 一行改动。

### 涉及文件（写域）

- 生产：`app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt`（`loadVideo` 函数级重写 + `PlayerUiState.queueExhausted` 字段，**与 BUG-015 是同一函数**）；`app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt`（新增耗尽消费 `LaunchedEffect`，v3 写域扩展）
- 测试：`app/src/test/java/cn/vectory/syncplayer/player/PlayerViewModelLoadVideoTest.kt`（同文件追加）

---

## BUG-021 formatFileSize 将 ≥1 PiB 误标为 TB

### 现状与根因（源码实读核对）

**`app/src/main/java/cn/vectory/syncplayer/ui/util/FormatUtils.kt:72-78`**：

```kotlin
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")                       // :74 止步 TB
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()  // :75 未 clamp
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())          // :76 除数未 clamp → 数值错
    return "%.1f %s".format(value, units[digitGroups.coerceAtMost(units.size - 1)])  // :77 仅下标 clamp
}
```

`digitGroups` 只在**下标**处 clamp（:77），**除数**（:76）用原始值。`bytes ≥ 2^50`（1 PiB）时 `digitGroups = 5`：下标 clamp 到 TB，但除数仍是 1024^5 → 显示 `1.0 TB`（正确应为 PB）；`bytes ≥ 2^60` 时显示 `1024.0 TB` 级别的失真。触发面：`fileSize` 累加场景（文件夹统计、`buildImageMetadataLine` :150-152 等）在个人 NAS 大库下可达 PiB 级。当前无调用方触发属"未爆"，但函数契约错误。

### 修复方案（clamp 提前 + units 扩档到 PB）

```kotlin
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        .coerceAtMost(units.size - 1)   // BUG-021: clamp 除数与单位同步（≥1 EiB 封顶显示 PB）
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "%.1f %s".format(value, units[digitGroups])
}
```

既有输入输出不变性：`bytes ∈ (0, 2^50)` 区间 `digitGroups ≤ 4`，clamp 不生效，输出逐字节等价；`2^50 ≤ bytes < 2^60` 由错误的 `x.x TB` 变为正确的 `x.x PB`；`bytes ≥ 2^60`（Long 上限 8 EiB）封顶 `PB` 档（`1024.0 PB` 封顶不失真方向）。选"扩档+clamp"而非"仅 clamp"：仅 clamp 则 PiB 文件永久显示为千级 TB，信息失真依旧。

### TDD 测试计划

文件：新建 `app/src/test/java/cn/vectory/syncplayer/ui/util/FormatUtilsTest.kt`（纯 JVM；`formatFileSize` 是无依赖顶层函数，断言其输出字符串即断言函数契约——非用户文案断言）。

**Locale 纪律（v3 oracle #8）**：`"%.1f".format` 与 `String.format` 默认走 JVM 默认 Locale——`de` 等 Locale 的小数逗号（`"1,0 TB"`）会让全部断言系统性变红/假绿。测试类固定 Locale 并在事后恢复，防宿主环境影响：

```kotlin
private val savedLocale = Locale.getDefault()

@Before fun setUp() { Locale.setDefault(Locale.US) }
@After fun tearDown() { Locale.setDefault(savedLocale) }
```

先写用例（现状红）：

1. `formatFileSize pins legacy boundary values`
   - **Assert**：`assertEquals("0 B", formatFileSize(0L))`；`assertEquals("0 B", formatFileSize(-5L))`；`assertEquals("1.0 B", formatFileSize(1L))`；`assertEquals("1.0 KB", formatFileSize(1024L))`；`assertEquals("1.0 MB", formatFileSize(1L shl 20))`；`assertEquals("1.0 TB", formatFileSize(1L shl 40))`。[护栏，现状绿]
   - **边界警示（v3 oracle #8）**：`Math.log10(bytes)/Math.log10(1024)` 在**紧邻档位边界**的输入上受 double 舍入影响，可能得 `3.999…`（截断成 3）而非精确 4——紧邻边界的档位**不能纯数学推导**。实施时若发现 `1L shl 40` 实际输出 `1024.0 GB`（浮点向下舍入），将边界断言的输入改为 `boundary + 1`（如 `(1L shl 40) + 1`，`%.1f` 下与边界值输出同串），以实际浮点行为为准锁定护栏。
2. `formatFileSize labels PiB correctly instead of TB`
   - **Assert**：`assertEquals("1.0 PB", formatFileSize(1L shl 50))`（现状 `"1.0 TB"` —— 红；同理受边界警示约束，必要时用 `(1L shl 50) + 1`）。
3. `formatFileSize clamps beyond PiB to PB unit without corrupting the value`
   - **Arrange**：`val oneEiB = 1L shl 60`。
   - **Assert**：`val out = formatFileSize(oneEiB)`；`assertTrue(out.endsWith(" PB"))`（现状 `1024.0 TB` —— 红）；数值部分 `assertEquals("1024.0", out.removeSuffix(" PB"))`。
4. `formatFileSize keeps sub-boundary TB values untouched`
   - **Assert**：`assertEquals("1.5 TB", formatFileSize((1.5 * (1L shl 40)).toLong()))`（`1.5×2^40` 精确可表示、远离档位边界——防 clamp 边界误伤，也避开 log10 舍入敏感区）。

说明：测试固定 `Locale.US` 且 `@After` 恢复默认（不泄漏到同 JVM 的其他测试类）；被测函数本身不触碰 Locale 语义（窄聚焦，格式化函数无 i18n 红线——它本就是数值格式化而非用户文案）。

### 验收矩阵

| 命令 | 期望 |
|---|---|
| `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.ui.util.FormatUtilsTest'` | 4 用例全过（2、3 由红转绿） |
| `compileDebugKotlin` / 全量 `testDebugUnitTest` | 通过、无回归 |

### 风险与守卫

- 无红线命中：纯展示格式化，无并发/错误处理/敏感信息面。
- 唯一行为变化：大输入（PiB 级）的显示档位（TB→PB）；既有所有调用方（`buildMetadataLine`、`buildImageMetadataLine*`）无断言依赖旧输出（已 grep 测试树：`FormatUtils` 现无测试引用）。~~唯一变化严格限 ≥1 PiB~~（v4 oracle #7 收紧）：紧邻浮点档位临界的输入（舍入敏感区）**不能纯数学推导断言"旧=新"**——实施时对临界邻域取实际值跑旧/新实现对照，确认无意外档位漂移后并入回归用例。
- `units` 新增 "PB" 为非翻译字面量，与现有 "KB/MB/GB/TB" 同类（数值单位非用户文案）。

### 涉及文件（写域）

- 生产：`app/src/main/java/cn/vectory/syncplayer/ui/util/FormatUtils.kt`（`formatFileSize` 单函数）
- 测试：`app/src/test/java/cn/vectory/syncplayer/ui/util/FormatUtilsTest.kt`（新建）

---

## Lane2 交接项（L4-1..L4-4，本轮新增）

> **来源与权威**：本章为修订 v2 新增。L2 计划（`docs/ocmar/plans/2026-09-05-plan-smb-dao.md`，门禁 **9.6 PASS 定稿**）在评审 #2/#3/#5（含 v3/v3.1 两轮收敛）后，把保存层校验/协议化归一化（`SyncRelationEditViewModel.kt`）、增益调用点替换（`GainAnalysisViewModel.kt`）、`strings.xml`（`relation_invalid_remote_path`）与三个 VM 层测试类移出其写域（清单见 L2 :705-709）。**本章语义以 L2 计划「Lane4 交接项」章节（:721-832）原文为准；A/B 共用规则①②逐字照搬（:778-799），不得改写。**
>
> **依赖状态（本轮实读/grep 核实）**：L2 生产代码已就绪——`suspend fun updateAudioGain(id: Long, audioGainDb: Float?)` 已落地于 `data/local/dao/VideoItemDao.kt:245-246` 与 `data/repository/VideoRepository.kt:132-133`。L4-2/L4-3 只改调用点/新增测试，**禁改 DAO/Repository**（API 由 L2 定稿交付）。L4-1 无外部依赖（纯 VM+strings）。
>
> **写域**：`settings/SyncRelationEditViewModel.kt`、`settings/GainAnalysisViewModel.kt`、`app/src/main/res/values/strings.xml`（仅 +1 条）+ 三个新测试类（见 L4-3）。与上游 6 BUG 的写域（player/browser/ui util）零文件交叠。

### L4-1 保存入口协议校验 + 协议化路径策略（`SyncRelationEditViewModel.kt`）

#### 现状锚点（源码实读核对，与 L2 计划引用一致）

- `save()` 的既有校验只有 isBlank 组合（:273-278）；`remotePath` 仅 `trim()` 后原样入库（:301）——SMB root `/`、两协议的 `.`/`..`/空段均可落库。
- 用户无需手输即可产生 root：`navigateRemoteUp()` 可自然导航到 `/`（:249-255，:253 `else "/"`），`selectRemotePath()`（:263-265）把 `/` 写入 `_remotePath`（BUG-006 入口通道；SMB 客户端层 1a/1b 已由 L2 fail-closed，本项是保存层提前拦截，把失败从"同步期报错"提前为"保存期反馈"）。
- 协议查找既有模式：`_availableSources.value.find { it.id == _selectedSourceId.value }`（`browseRemote` :176 / `testRemotePath` :212 同款）；`availableSources` 由 `syncSourceRepository.getAllWithCredentials()`（:106）异步加载。

#### 路径策略契约（SMB 与 SFTP 分离）——逐字照搬 L2 计划 :727-737

SFTP 客户端按原样解析路径（`remoteBasePath = remotePath.trim()`，`SftpFileClient.kt:106`；扫描递归 `sftp.ls(currentPath)`（:203）、浏览 `sftp.ls(path)`（:162）），前导 `/` 与裸 `.` 具备真实锚定语义，**不可持久化改变**：

| 规则 | SMB | SFTP |
|---|---|---|
| 前导 `/` | 剥离（`/media/movies`→`media/movies`；SMB 客户端模型为 share 根相对，无独立绝对语义） | **保留**——前导 `/` ⇔ 绝对路径；剥离会把绝对扫描翻转为服务器 cwd 相对，可能扫到另一个目录 |
| 空段折叠 `a//b`→`a/b` | 是（POSIX 等价） | 是（不改变锚定） |
| 非首 `.` 段折叠 `a/./b`→`a/b` | 是 | 是（段级等价） |
| 裸 `.` | 拒绝（等价 root，见下） | **拒绝（不得转 `/`）**——`.` 是 cwd 相对、随 cwd 漂移；转成 `/` 会把 cwd 相对翻转为绝对 root，扫错目录。产品约束：同步根须为明确稳定目录 |
| 裸 `/`（root） | **拒绝**（BUG-006 E8 洞；SMB 客户端层 1a 已 fail-closed，保存层提前拦截把失败从"同步期报错"提前为"保存期反馈"） | 保留（SFTP 客户端对 `/` 处理正确，`SftpFileClient.kt:176` removePrefix 产出干净相对路径；SFTP root 同步为既有合法功能） |
| `..` 段 | 拒绝 | 拒绝（**协议范围：两协议一致**；产品约束：同步根须为规范稳定路径——遍历段的有效根依赖服务器端解析结果，且破坏下游本地/远端前缀匹配不变量） |
| 含点名称（`shows/v2.0`、`My.Movies`） | `.` 仅**整段**特殊，段内点原样保留 | 同左 |

#### 实现要点（L2 :739-745）

`save()`（:273-278 isBlank 校验后）调用协议感知的 `normalizeRemotePath(raw, protocol): String?`（internal 顶层纯函数，可直测）；返回 null → `_error.value = context.getString(R.string.relation_invalid_remote_path)`（strings.xml +1，文案实现时定稿）+ `return@launch`；`:301` 改存归一化结果。

- **import**：L2 要求增补 `cn.vectory.syncplayer.data.model.SyncProtocol`——本轮实读该 import **已存在**（`SyncRelationEditViewModel.kt:11`），无需增补（偏差记录见附录）。
- **selectedSource == null（协议未知）时的确定策略（评审 v3 #2，三选一明示）**：
  - **选定：协议未知 → 不保存**（拒绝 + `relation_invalid_remote_path` 反馈，不落库）。理由：`_selectedSourceId` 查不到对应 source 本身是竞态/非法状态；SMB 层 1a 只拒空 share、**不能代协议选择**——若协议未知时按任何默认语义（如 SMB 剥前导 `/`）归一化并落库，会把 SFTP 绝对路径误持久化为相对路径；拒绝保存是唯一不依赖协议猜测的 fail-closed 终态，且用户可恢复（重试时 availableSources 已加载，按实际协议处理）。
  - 备选（若实施时改选，须整条替换，不得与选定混用）：(a) 有界等待 availableSources 加载（`withTimeoutOrNull` + `first()`），超时仍未知 → 按上款拒绝；(c) 放行但**仅保存原始 `trim()` 值、绝不套用任何协议默认归一化**，并标注为保存校验例外——(c) 会持久化未归一化路径（BUG-022 空段入口重新打开），仅在前两者均不可行时考虑。
  - **禁止**「不拦 + 默认协议归一化」：这正是评审指出的 SFTP 误剥前导 `/` 通道。

#### 实现强化：协议相关字段的同一快照（v3 oracle #3 新增；v4 oracle #7 收窄声明范围）

**时序洞（对 `SyncRelationEditViewModel.kt:279-301` 实读）**：前置校验按"校验时刻"的协议归一化（读 `_selectedSourceId.value` find source 得协议），但 `save()` 内首个挂起点之后——`withContext(ioDispatcher)`（:279-286）、`checkBasePathConflict()`（:287-293）——`:300-301` 构造 `SyncRelation` 时**再次读** `_selectedSourceId.value` / `_remotePath.value`。用户在挂起窗口内切换 source（`selectSource`，availableSources 已加载即可点）或再次 `setRemotePath`，会落库**「SMB 归一化 remotePath + SFTP syncSourceId」的错配关系**（归一化规则与持久化协议来自不同时刻的输入）。

**强化（v4 oracle #7 收窄声明范围；v6 ora-8 精度更正）**：`save()` 入口、**首个挂起点之前**一次性捕获**协议相关字段**（`sourceId`、`source`→协议、`rawRemotePath`）的同一快照——协议校验、路径归一化与 `SyncRelation` 的**对应字段**（`:300-301` 的 `syncSourceId`/`remotePath`）全程只用快照。**不宣称**「全部输入入口快照」：`name`（:299）未捕获、仍按落库时刻现读；`localBasePath`（:302）保存时使用的是**此前已计算的 `normalizedBasePath` 局部值**（:279-286 前置计算的产物，非落库时刻重读 StateFlow）——本计划**不改变**其前置计算/保存时机。这两个字段不参与协议归一化，不在本节修复面（与既有行为一致，无新增恶化）：

```kotlin
// isBlank 组校验旁（均在首个挂起点 :279 之前）：
val sourceId = _selectedSourceId.value                                        // 快照（协议相关）
val source = _availableSources.value.find { it.id == sourceId }               // 快照（协议随 source 定）
val rawRemotePath = _remotePath.value                                         // 快照（协议相关）
// …normalizeRemotePath(rawRemotePath, source?.protocol)…
// :296-304 SyncRelation 的 syncSourceId/remotePath 改用快照：syncSourceId = sourceId、remotePath = 归一化结果
//（不再于 :300-301 二次读取 StateFlow；name 维持现读、localBasePath 沿用前置计算的
//  normalizedBasePath 局部值——两者的时机本计划均不改变，不在本节范围）
```

**与 L2 契约的关系**：L2 :725-767 定义「按什么规则归一化」，本节定义「用哪一刻的输入归一化」——归一化规则逐字不变，只补 L2 未言明的时序面，且只覆盖协议相关字段。属**实现强化**，不回改 L2 引用原文（原文与之无冲突，只是未覆盖此窗口）；门控测试见契约测试第 5 项。

#### 契约测试（新 `app/src/test/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModelRemotePathTest.kt`；L2 :747-767）

Harness（本计划补充，锚点实读核对；v3 oracle #8 更正调度语义）：全依赖 mockk（`syncRelationRepository`/`syncSourceRepository`/`remoteFileClientFactory`/`storagePaths`/`userPreferencesRepository`/Context relaxed）；`storagePaths.normalizeAndValidateRelativePath(any())` 返回固定相对路径（避开 :283 SecurityException 分支）、`syncRelationRepository.checkBasePathConflict(any(), any())` 返回 emptyList（避开 :290 冲突分支）；`syncSourceRepository.getAllWithCredentials()` 按 fixture 返回 SMB/SFTP source（经 :106 加载路径）；error 文案断言前 stub `context.getString(any())`/`(any(), any())` 返回固定串（relaxed mock 对 String 返回不可依赖）；`setName`/`setRemotePath`/`setLocalBasePath`/`selectSource` 填齐 :273-274 既有校验字段。**调度两条**：①构造 VM 前先 stub `coEvery { userPreferencesRepository.getMediaStorageTreeUri() } returns null`——init 即跑 `loadStorageInfo()`（:110-126），不 stub 会在构造期就挂起/越权读真 repo；②`Dispatchers.setMain(UnconfinedTestDispatcher())` 只令 `viewModelScope.launch` 即时执行，**不保证构造注入的 `ioDispatcher` 同步**——构造 VM 时显式注入 `UnconfinedTestDispatcher()` 作为 `ioDispatcher` 参数，否则 `withContext(ioDispatcher)`（:279）等真实调度器，门控用例时序错乱。

1. **纯函数逐输入期望表**（`normalizeRemotePath(raw, protocol)`，两列分别直测，评审 v3 #2 补全）：

| 输入 | SMB | SFTP |
|---|---|---|
| `/media/movies` | `"media/movies"` | `"/media/movies"`（保留前导 `/`） |
| `media/movies` | `"media/movies"` | `"media/movies"` |
| `./a` | `"a"` | `"a"`（相对锚定不变） |
| `.` | `null`（等价 root，拒） | `null`（cwd 相对，拒；**不转 `/`**） |
| `/` | `null`（root，拒） | `"/"`（合法 root，保留） |
| `shows/v2.0` | `"shows/v2.0"`（点非整段，原样） | `"shows/v2.0"` |
| `a/../b` | `null`（`..` 拒） | `null`（`..` 拒） |
| `a//b` | `"a/b"` | `"a/b"` |
| `a/./b` | `"a/b"` | `"a/b"` |
| `""` / `"  "` | `null` | `null` |
| `" /a//b/. "` | `"a/b"`（trim→剥前导→折叠空段/尾 `.` 段） | `"/a/b"`（trim→保前导 `/`→折叠空段/尾 `.` 段） |

2. save() 行为用例：SMB root 拒绝（不落库+error 非空）、SFTP root 保留（`remotePath == "/"` 落库）、空段折叠落库（落库值为归一化结果而非原始串，`coVerify { syncRelationRepository.save(match { it.remotePath == "a/b" }) }` 型断言）、`..` 拒绝；
3. **source 未加载用例**（评审 v3 #2 新增）：`_selectedSourceId` 指向的 source 不在 availableSources（selectedSource==null）→ `setRemotePath("/a/b")` + `save()` → **不落库**（`coVerify(exactly = 0) { syncRelationRepository.save(any()) }`）且 error 非空——验证 `/a/b` 不会被按 SMB 默认策略剥成 `a/b` 落库；
4. **suspend 断言一律 `coVerify`**（评审 #6：`syncRelationRepository.save()` 为 suspend，原方案 `verify` 不适用）；
5. **保存输入同一快照门控用例（v3 oracle #3 新增）**：`coEvery { syncRelationRepository.checkBasePathConflict(any(), any()) } coAnswers { gate.await(); emptyList() }`（`gate = CompletableDeferred<Unit>()`）→ 填 SMB source + `setRemotePath("/media/movies")` → `save()` → **挂起窗口内** `selectSource(sftpId)` + `setRemotePath("/abs")` → `gate.complete(Unit)` → 断言落库 `slot<SyncRelation>`：`syncSourceId == 原 SMB id && remotePath == "media/movies"`（SMB 归一化值，两字段同源断言）——证明切换协议后不会把 SFTP 路径按 SMB 规则归一化、也不会把 SMB 归一化结果存进 SFTP 关系。快照断言须在 `runTest`/注入 Unconfined ioDispatcher 下才能确定性走到 gate 释放后（见 Harness 调度两条）。

**红/绿分工**：纯函数表是新增 API 的表征测试（随实现同提交，无红灯步骤——不得以「函数未编译」当红灯）；save() 行为用例 2/3/**5** 为行为性红灯（现状 root/`..`/协议未知均原样落库、快照未捕获时落库 `sftpId`+原始串 → `coVerify(exactly = 0)` 与快照断言可编译且红）；SFTP root 保留为绿锁（现状已保存 `/`）。

#### 验收矩阵

| 命令 | 期望 |
|---|---|
| `JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest --tests 'cn.vectory.syncplayer.settings.SyncRelationEditViewModelRemotePathTest'` | 全部通过 |
| `compileDebugKotlin` / 全量 `testDebugUnitTest` | 通过、无回归 |

#### 风险与守卫

- **敏感信息（红线 #6）**：拒绝反馈只用 `relation_invalid_remote_path` 资源文案（`context.getString`），不含用户输入的 path；`normalizeRemotePath` 不打日志、不回显输入。
- **结构化错误（红线 #7）**：入口预校验非 `SyncException` 域——UI 直接资源文案，不涉 `Throwable.message`，与既有 `relation_complete_fields`（:276）同构。
- **E8（红线 #1）**：L4-1 是体验前移（保存期反馈），**不承担 E8 兜底**——SMB root 的扫描期 fail-closed 已由 L2 客户端层 1a/1b 独立成立，两层互不依赖（L4-1 缺席时扫描亦安全失败）。
- **CE（红线 #3）**：`save()` 既有 CE-first catch（:306-307）不动；插入点在 try 块之外的前置校验区，无新 catch。
- **i18n**：strings.xml +1 条；VM 经 `context.getString` 消费（Repository 不依赖 Context 生成文案的约定不受影响）。
- **存量关系**：既有已落库的 SFTP `a//b` 型 remotePath 不被本项追溯清洗——L2 BUG-022 的 DAO 端防御消费空段行；彻底清洗属 sync lane（L2 风险清单已声明）。

#### 涉及文件（写域）

- 生产：`app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt`（save() 前置校验插入 + 挂起点前**协议相关字段**快照捕获（v3 oracle #3；v4 收窄）+ `normalizeRemotePath` internal 顶层纯函数 + `:301` 改存归一化结果）；`app/src/main/res/values/strings.xml`（+1 `relation_invalid_remote_path`）
- 测试：`app/src/test/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModelRemotePathTest.kt`（新建）

### L4-2 增益调用点替换单列 API（`GainAnalysisViewModel.kt`）

**替换（L2 :769-771 原文）**：`videoRepository.upsert(video.copy(audioGainDb = result.gainDb))` → `videoRepository.updateAudioGain(video.id, result.gainDb)`（API 由本 lane 先行交付）。

- **落点与锚点（实读核对）**：整行 upsert 在 `GainAnalysisViewModel.kt:160-162`；`startAnalysis()`（:104）与 `retryFailed()`（:112）两入口均汇入 `launchAnalysis`（:118）——调用点物理唯一（:160-162），一次替换同时覆盖两入口，与 L2「两入口改用」语义一致。
- **API 规格（只消费，不修改）**：`suspend fun updateAudioGain(id: Long, audioGainDb: Float?)`——已落地（`VideoItemDao.kt:245-246`、`VideoRepository.kt:132-133`，本轮 grep 核实）；行不存在 → 0 行静默 no-op（不复活）。调用点传非空 `result.gainDb`（L2 BUG-013 约定，`Float?` 形参为将来清除增益留通道）。
- **TDD 红灯步骤（评审 #6，L2 :773 原文）**：红态断言 `coVerify(exactly = 0) { videoRepository.upsert(any()) }`——调用点替换前**可编译且红**（现状 VM 走 upsert）；不得以"新 API 未编译"充当红灯。绿态：替换后追加 `coVerify(exactly = 1) { videoRepository.updateAudioGain(1L, 6f) }`。
- 快照仅继续用于 `id`/`fileName`（:153），只读无害。

#### 涉及文件（写域）

- 生产：`app/src/main/java/cn/vectory/syncplayer/settings/GainAnalysisViewModel.kt`（:160-162 单点替换 + L4-3 要求的 `:70` `analysisJob` seam（`internal var` + `private set`，模块内可读/类内可写——v4 语法定稿），见下节）
- 测试：类 A / 类 B（见 L4-3；红灯步骤落在类 A）

### L4-3 三个 VM 测试类（评审 v3 #1：拆为两类——mock 调用契约 + 真实 Room 交错集成）

三个新测试类：**L4-1 契约测试**（`SyncRelationEditViewModelRemotePathTest`，规格见 L4-1 节）+ **类 A**（`GainAnalysisViewModelUpdateTest`，mock 调用契约）+ **类 B**（`GainAnalysisConcurrencyTest`，真实 Room 交错集成）。**共用规则①②仅约束类 A/类 B**（两个 `GainAnalysis` 测试类）——L4-1 契约测试不适用：`SyncRelationEditViewModel.save()` 经**注入的** `ioDispatcher`（:280）切换且无长驻后台作业，Unconfined Main 下同步 settle，不存在 `GainAnalysisViewModel.kt:148` 型硬编码 `Dispatchers.IO`。

**A/B 共用规则①——双条件终态 + 真实时间有界等待（评审 v3.1 #1，A/B 同一契约）**：`GainAnalysisViewModel.kt:148` 的 `withContext(Dispatchers.IO)` **不因依赖被 mock 而消失**——类 A（mock 契约）与类 B（真实 Room）一律使用同一终态契约：等待目标 IDs（`completedIds`/`failedIds`）达预期 **且** `isAnalyzing == false`（:169 轮内即发布失败 ID、:207 finally 才清 `isAnalyzing`，两事件间有时间窗；期间 `retryFailed()` 被 :110 `if (_isAnalyzing.value) return` 拒绝——只等 IDs 必有竞态）。等待形式（A/B 相同）：

```kotlin
withContext(Dispatchers.Default) {                     // 真实调度器：真实 IO 线程的墙钟进度不进 runTest 虚拟时间
    withTimeoutOrNull(5_000) {
        combine(idsStateFlow, isAnalyzingStateFlow) { ids, analyzing ->
            analyzing || !ids.containsAll(expectedIds)
        }.first { !it }                                 // 双条件：目标 IDs 齐且 !isAnalyzing
    } ?: fail("analysis round did not settle in 5s")   // 超时必须断言失败，不得静默通过
}
```

- `retryFailed()` 调用（A/B 的 retry 链用例）必须发生在上述等待**成功之后**；
- **不得**把 `withTimeoutOrNull` 放在外层 `runTest` 测试调度器上（虚拟时钟不会推进真实 IO 阻塞）；**不得**依赖 `advanceUntilIdle()`（:148 硬编码 `Dispatchers.IO` 使空闲推断不可靠）。

**A/B 共用规则②——清理顺序（评审 v3.1 #2）**：`viewModelScope` **不会自动成为 `runTest` 的子任务**——测试结束不会替你取消/等待 VM 的分析作业；失败路径下先行 `unmockkAll()`/`resetMain()`/关库会与仍在写库的真实 IO 线程竞争（跨测试污染、关库后访问）。`@After` 统一顺序：

1. **取消/清理 VM 分析作业**（对 VM 调 `cancelAnalysis()`；注意该方法会**立即清除** `isAnalyzing`——该值不得作为作业已结束的证据）；
2. **释放门控**（`gate.countDown()`，finally 语义——让阻塞在 analyzer 内的 IO 线程得以返回；只取消不释放，步骤 3 会挂住）；
3. **有界等待本轮已启动的分析作业实际结束**——等待的是**作业完成**这一事实（已捕获的 job 引用 `join()`，配真实时间上限；实现手段由 Lane4 定：暴露作业引用/注入可观测 seam 等），**不是**轮询 `isAnalyzing`；
4. **还原全局环境**：`unmockkAll()`（还原 `mockkObject(MediaUris)` 全局 mock）→ `Dispatchers.resetMain()`；
5. （类 B）**关闭 in-memory DB**——最后执行。

**规则②步骤 3 的实现 seam（L2 授权 Lane4 定，本计划选定；v4 oracle #2 语法修正）**：`analysisJob` 现为 private（`GainAnalysisViewModel.kt:70`）——改声明为：

```kotlin
internal var analysisJob: Job? = null
    private set           // 模块内可读（friend-module 测试），赋值仍限类内
```

（Kotlin 访问器语义：`internal var` + `private set` = **模块内可读、类内可写**——属性声明为 `internal` 且 setter 单独收窄是合法 Kotlin；v3 草案 `var ... internal get` 语法非法（getter 可见性不得宽于属性且默认可见性冲突），作废。项目零 `@VisibleForTesting` 使用——grep 核实，不引入新注解惯例；改动一行。）测试须**在步骤 1 之前捕获该引用**（`cancelAnalysis()` 会将其置 null，:214）；join 配真实时间上限，如 `runBlocking { withTimeoutOrNull(5_000) { capturedJob.join() } } ?: fail("analysis job did not finish in 5s")`。该 seam 为生产代码一行改动，由 `compileDebugKotlin` 验证（编译错误不计 TDD 红灯——红灯断言只落在用例行为上）。

**类 A — mock 调用契约测试**（新 `app/src/test/java/cn/vectory/syncplayer/settings/GainAnalysisViewModelUpdateTest.kt`；`Dispatchers.setMain(UnconfinedTestDispatcher())` + `runTest`，mockk `VideoRepository`/`AudioGainAnalyzer`/Context(relaxed)/真实 `SavedStateHandle(mapOf("relationId" to 1L))`）：

- **mock 纠错**：`provider/MediaUris.kt:6-13`（v3 oracle #8 更正包路径：L2 原文作 `MediaUris.kt`，实际位于 `provider/` 子包）是**无 `@JvmStatic` 的 object** → 必须 `mockkObject(MediaUris)`（原案 `mockkStatic` 错误）；`analyze` 是**普通同步函数**（非 suspend，返回可空 `AudioGainResult`）——`every { audioGainAnalyzer.analyze(any()) } returns AudioGainResult(rmsDb = -20f, peakDb = -3f, gainDb = 6f)`（三参构造）。
- **类 A 只验证调用契约**：断言 `coVerify(exactly = 1) { videoRepository.updateAudioGain(1L, 6f) }` + `coVerify(exactly = 0) { videoRepository.upsert(any()) }`。并发改列/删行的**数据效果**（不回滚/不复活）由类 B 证明——无状态 mock 替身证明不了数据效果。
- **两个入口都要测**：`startAnalysis()`（:104）与 `retryFailed()`（:112），含「失败→`retryFailed()`→成功」链：analyze 首次返回 null（进 `failedIds`，:169）→ **按共用规则①双条件等待成功后**调 retry → retry 返回值——验证 retry 入口同样走单列 API（评审 v3.1 #1：mock 不消除 :148 的 IO 切换，类 A 的 retry 链与类 B 同构地受 :169/:207 时间窗约束）。
- **红灯步骤**：红态断言 `coVerify(exactly = 0) { upsert }` 在调用点替换前**可编译且红**（现状 :160-162 走 upsert），替换后转绿——不得以编译失败当红灯。

Harness 补充（本计划）：`mockkObject(MediaUris)` 后 stub `every { MediaUris.mediaUri(any()) } returns mockk()`（object mock 需显式 stub，供 :158 调用）；实体 fixture 以 `fileName = "ep1.mp4"`（过 `isVideoFile` 过滤，:83/:86）+ `audioGainDb = null`，经 `videoRepository.getBySyncRelationId(1L)` 返回后 `vm.discover()` 驱动（relationId=1L 分支 :85），再 `startAnalysis()`；清理按共用规则②（无门控则步骤 2 为空操作、无库则跳过步骤 5，规则文本不裁剪）。

**类 B — 真实 in-memory Room + Repository 交错集成测试**（新 `app/src/test/java/cn/vectory/syncplayer/settings/GainAnalysisConcurrencyTest.kt`；Robolectric + `Room.inMemoryDatabaseBuilder` 构造真实 `VideoItemDao`/`VideoRepository`，仅 mock `AudioGainAnalyzer` 与 Context；`Dispatchers.setMain(UnconfinedTestDispatcher())`）——证明"单列写不回滚并发列修改、删行不复活"：

- **同步 analyzer 的确定性门控（评审 v3 #1(i)；v3 oracle #8 补 entered latch）**：`analyze` 在 `Dispatchers.IO` 线程池被**同步**调用，门控用**非协程有界阻塞原语**在同步调用内桥接（不得用 `coAnswers`/`CompletableDeferred`——非 suspend 函数无法挂起）：

  ```kotlin
  val gate = java.util.concurrent.CountDownLatch(1)
  val entered = java.util.concurrent.CountDownLatch(1)   // "已进入 analyzer"的可观测证明
  every { audioGainAnalyzer.analyze(any()) } answers {
      entered.countDown()                                 // 先报告进入，再等门
      check(gate.await(5, TimeUnit.SECONDS)) { "gate timeout" }  // 有界阻塞：卡死即测试失败，可见可诊断
      AudioGainResult(rmsDb = -20f, peakDb = -3f, gainDb = 6f)
  }
  ```

  主侧**先有界等待 `entered` latch**（`check(entered.await(5, TimeUnit.SECONDS)) { "analyzer not entered" }`）——**不得**以观测 `activeAnalysis` 替代（v3 oracle #8 更正：`GainAnalysisViewModel.kt:152-155` 在 :158 analyzer 调用**之前**发布 `activeFiles`，观测到它只证明"即将调用"，不证明已进入门控；`entered` 在 mock 内 `gate.await()` 之前 countDown，才是门控占有的确证）→ 确认进入后在**真实 IO 线程上执行并发写**（`updateStar(id, 5)` / `deleteMediaRecord`，经真实 Repository 直调、不经 VM）join 后 `gate.countDown()` 放行。
- **顺序契约（评审 v3 #1(iii)；v3 oracle #8 更正观测点）**：启动分析 → 等待 analyzer 已进入门控（**以 mock 内 `entered` latch 为准**，理由见上）→ 执行并发写 → `countDown()` 释放 → **按共用规则①双条件等待整轮结束** → 断言。「失败→retry→成功」用例中 `retryFailed()` 调用必须发生在等待成功**之后**。终态等待形式即共用规则①的 `combine`+`withTimeoutOrNull`（真实调度器+真实毫秒），不再单列类 B 版本。
- **必备用例**：
  1. **改列交错**：analyze 门控挂起中注入 `updateStar(id, 5)`（真实 DB）→ 释放 → 双条件满足后断言 `star == 5 && audioGainDb == 6f`（单列写不回滚）；
  2. **删行交错**：analyze 门控挂起中注入 `deleteMediaRecord`（真实 DB 删行）→ 释放 → 双条件满足后断言按 id 查询为 null（不复活）、`updateAudioGain` 影响 0 行静默 no-op、无异常逃逸（CE-first catch :176-177/:201-202 不受影响）；
  3. **失败→`retryFailed()`→成功**（真实 DB 版）：analyze 首次返回 null → 等双条件 → `retryFailed()` → 等双条件 → 断言 `audioGainDb` 已写、`failedIds` 已移除该 id（:136 retry 起点剔除待重试 id）。
- **清理**：按**共用规则②**的五步顺序执行（类 B 含步骤 5 关闭 in-memory DB）。评审 v3.1 #2 修正点：失败路径下先行还原/关库会与仍在写库的真实 IO 线程竞争——门控释放后必须**有界等待分析作业实际结束**（作业完成事实，非 `isAnalyzing` 值），再动全局 mock/Main，最后关库。

Harness 补充（本计划；v3 oracle #8 补 discover 等待）：种子行经真实 DAO 直插（`fileName = "ep1.mp4"`、`audioGainDb = null`）；**`vm.discover()` 是 VM scope 内的异步作业（真实 Room 查询走真实调度器）——必须先等待 `_discoveredFiles` 含目标 id 且 `isDiscovering == false` 再 `startAnalysis()`**（轮询或 `combine(discovered, isDiscovering).first{...}` 均配真实时间上限；`advanceUntilIdle()` 对 :148 型硬编码 IO 不可靠——与共用规则①同因）。类 B 同样 `mockkObject(MediaUris)` + stub（规则②步骤 4 的 `unmockkAll()` 还原对象即指此）；并发写在 `Dispatchers.IO` 上 `launch` 执行并 join 后再 `countDown()`。

#### 涉及文件（写域）

- 测试（3 新建）：`app/src/test/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModelRemotePathTest.kt`、`app/src/test/java/cn/vectory/syncplayer/settings/GainAnalysisViewModelUpdateTest.kt`、`app/src/test/java/cn/vectory/syncplayer/settings/GainAnalysisConcurrencyTest.kt`
- 生产连带：`GainAnalysisViewModel.kt:70` `analysisJob` 改 `internal var` + `private set` seam（模块内可读、类内可写；规则②步骤 3 授权，一行；v4 语法定稿）

### L4-4 依赖顺序与集成门禁（L2 :828-832 原文）

1. **顺序**：Lane2 全部合入 → Lane4 实施 L4-1/L4-2/L4-3（L4-2/L4-3 依赖本 lane 的 `updateAudioGain` API 已存在）。**前置已满足**：L2 生产代码已落地（`updateAudioGain` 在 `VideoItemDao.kt:245-246`/`VideoRepository.kt:132-133`，本轮核实）。L4-1 与 L4-2/L4-3 无相互依赖，可并行；建议类 A 红灯（L4-2 的 TDD 步骤）先行、类 B 随后。
2. **Lane4 门禁**：`compileDebugKotlin` + `testDebugUnitTest` 全量（含 `SyncRelationEditViewModelRemotePathTest`、`GainAnalysisViewModelUpdateTest`（类 A）、`GainAnalysisConcurrencyTest`（类 B，真实 Room 交错））。
3. **宣称边界**：BUG-013 的端到端修复（不复活/不回滚）在 L4-2+L4-3 全绿（含类 B 交错用例）后方可宣称；L2 仅交付 API 层语义（DAO 用例锁定）。

#### 风险与守卫（L4-2/L4-3 补充）

- **CE（红线 #3）**：L4-2 只改一行调用，不触碰 `launchAnalysis` 的 CE-first catch（:176-177/:201-202）；测试代码在 `@After` 前的等待/join 均有界，不吞 CE。
- **测试稳定性**：规则①的 5s 上限与规则②步骤 3 的有界 join 是诊断性失败面（超时即 fail，可见可诊断），不是静默通过面；Robolectric 4.13 + in-memory Room 同 L2 DAO 测试栈（`VideoItemDaoPathSemanticsTest` 先例）。
- **写域红线**：禁改 DAO/Repository/SMB/SFTP 客户端（L2 交付物）；`strings.xml` 仅 +1 条。

---

## 写域汇总

### 生产文件（9，含 strings.xml = 8 Kotlin + 1 XML；v1 5 + v2 2 + v3 1 个 Kotlin，strings.xml 增 1 条）

| 文件 | BUG/项 | 改动类型 |
|---|---|---|
| `app/src/main/java/cn/vectory/syncplayer/player/PlayerViewModel.kt` | 002 + 015 + 019 | 三 BUG 同文件；`loadVideo`（:212-239）函数级重写一次成型（015 generation guard + 019 跳过循环交织在同一函数体，**不可拆两次提交**）；`saveProgress`（:252-263）替换为 channel 入队（每次保存为消费者内子 Job，CE 不上浮取消父——v4 #4 表述）；`PlayerUiState` 新增 `queueExhausted` 字段（019 结构化终态，v3）；新增 `PendingProgressSave`/`progressSaveChannel`/`loadGeneration`/init 消费者协程 |
| `app/src/main/java/cn/vectory/syncplayer/player/PlayerScreen.kt` | 019 | 新增耗尽消费 `LaunchedEffect(uiState.queueExhausted)`（v3 新增写域；结构化终态 → `currentOnExit()` 退出，与 STATE_ENDED 正常耗尽分支幂等汇合） |
| `app/src/main/java/cn/vectory/syncplayer/browser/UndoDeleteUseCase.kt` | 016 | 新增 `autoCommitPendingHardDeleteLocked`（incoming 类型/id 签名，v3）；三调用点改造；KDoc 更新 |
| `app/src/main/java/cn/vectory/syncplayer/browser/FileOperationsUseCase.kt` | 017 | `buildShareIntent` 内 :262-263 替换为唯一名调用 + 解密准备期外层清理 try/catch（v3）（与 BUG-016 同文件不同函数，无合并冲突） |
| `app/src/main/java/cn/vectory/syncplayer/ui/util/ShareTempFiles.kt` | 017 | 新增 `uniqueShareFile` + `truncateUtf8`（UTF-8 字节预算，v3） |
| `app/src/main/java/cn/vectory/syncplayer/ui/util/FormatUtils.kt` | 021 | `formatFileSize` 单函数 |
| `app/src/main/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModel.kt` | L4-1 | save()（:273-278 后）前置协议校验 + **挂起点前协议相关字段快照捕获（v3 oracle #3；v4 #7 收窄）** + `normalizeRemotePath(raw, protocol): String?` internal 顶层纯函数 + `:301` 改存归一化结果（v2 新增） |
| `app/src/main/java/cn/vectory/syncplayer/settings/GainAnalysisViewModel.kt` | L4-2/L4-3 | `:160-162` 单点替换为 `videoRepository.updateAudioGain(video.id, result.gainDb)`（两入口共用调用链）+ `:70` seam 改 `internal var` + `private set`（模块内可读、类内可写，v4 语法定稿；规则②步骤 3）（v2 新增） |
| `app/src/main/res/values/strings.xml` | L4-1 | 仅 +1 条 `relation_invalid_remote_path`（v2 新增） |

### 测试文件（9 = v1 的 6 + v2 的 3，其中 5 新建）

| 文件 | BUG/项 | 改动类型 |
|---|---|---|
| `app/src/test/java/cn/vectory/syncplayer/player/PlayerViewModelSaveProgressTest.kt` | 002 | 追加 6 用例（v4 更正计数） |
| `app/src/test/java/cn/vectory/syncplayer/player/PlayerViewModelLoadVideoTest.kt` | 015 + 019 | **新建**（两类用例同文件，loadVideo 是同一被测函数；015 4 用例 + 019 6 用例含 `queueExhausted` 终态发布/清除/**旧请求不发布**断言（v3+v4）） |
| `app/src/test/java/cn/vectory/syncplayer/browser/UndoDeleteUseCaseTest.kt` | 016 | 追加 4 用例 |
| `app/src/test/java/cn/vectory/syncplayer/browser/FileOperationsUseCaseTest.kt` | 017 | 追加 3 用例（IV 显式绑定 + 解密准备期清理，v3）+ 更新既有截断用例断言（:540-542 文件名匹配→目录空断言） |
| `app/src/test/java/cn/vectory/syncplayer/ui/util/ShareTempFilesTest.kt` | 017 | 追加 11 用例（v4：+5 长名/收敛；v5：+1 guard 行为锁） |
| `app/src/test/java/cn/vectory/syncplayer/ui/util/FormatUtilsTest.kt` | 021 | **新建**（4 用例） |
| `app/src/test/java/cn/vectory/syncplayer/settings/SyncRelationEditViewModelRemotePathTest.kt` | L4-1 | **新建**（纯函数期望表 + save() 行为用例 + source 未加载用例；suspend 断言一律 `coVerify`）（v2 新增） |
| `app/src/test/java/cn/vectory/syncplayer/settings/GainAnalysisViewModelUpdateTest.kt` | L4-2/L4-3 | **新建**（类 A：mock 调用契约，含红灯步骤）（v2 新增） |
| `app/src/test/java/cn/vectory/syncplayer/settings/GainAnalysisConcurrencyTest.kt` | L4-3 | **新建**（类 B：真实 in-memory Room 交错集成，3 必备用例）（v2 新增） |

### 落地顺序与合并策略

1. **PlayerViewModel.kt 必须一次编辑会话内完成 002/015/019**：`loadVideo` 重写同时承载 015 与 019（generation 取号在函数首行、跳过循环在 getById 之后、guard 检查在提交前），拆开会产生中间态（只修一半的 loadVideo）导致两套测试互红。建议顺序：先落 `saveProgress` actor（002，独立于 loadVideo）→ 再落 `loadVideo` 合并重写（015+019）→ 对应测试文件同步落盘。
2. FileOperationsUseCase.kt（017）与 UndoDeleteUseCase.kt（016）改动区不相交（`buildShareIntent` vs `performTrashToggle*`/新私有函数），可独立提交。
3. 每完成一个 BUG 即跑其验收命令（见下），全绿再进下一个；全部完成后跑全量门禁。
4. **Lane2 交接项（v2 新增）**：L4-1 无依赖，任意时点可做；L4-2+类 A 红灯先行（`updateAudioGain` API 已就绪，红灯断言可编译）；类 B 随后；全部完成跑 L4-4 全量门禁。`GainAnalysisViewModel.kt`（L4-2/L4-3）与 `SyncRelationEditViewModel.kt`（L4-1）均不与 6 BUG 的文件重叠（BUG-002/015/019 全在 `PlayerViewModel.kt`）。

### 与 Lane2 写域零冲突声明（v2 修订）

Lane2（`docs/ocmar/plans/2026-09-05-plan-smb-dao.md`）写域为 DAO 层（`@Query`/Entity）。本计划**零 DAO/Entity/仓库改动**：`PlaybackRepository.saveProgress`、`PlaybackRecordDao`、`VideoRepository` 均只读消费；L4-2 仅消费 L2 已交付的 `VideoRepository.updateAudioGain`（调用点替换，不改 API）。v2 吸收的 Lane4 交接项（L2 :705-709 移出清单原文）全部落在 VM/strings/测试层——两个 settings VM、`strings.xml`（+1 条）、三个测试类，与 L2 写域无文件交叠；共享文件仅为普通 Kotlin 类（UseCase/ViewModel/Util），无文件级交叠。

## 验收汇总

```bash
# 逐 BUG 门禁（每个 BUG 落地后立即执行）
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest \
  --tests 'cn.vectory.syncplayer.player.PlayerViewModelSaveProgressTest'
# BUG-015/019（新文件，实现前红、实现后绿）
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest \
  --tests 'cn.vectory.syncplayer.player.PlayerViewModelLoadVideoTest'
# BUG-016
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest \
  --tests 'cn.vectory.syncplayer.browser.UndoDeleteUseCaseTest'
# BUG-017
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest \
  --tests 'cn.vectory.syncplayer.browser.FileOperationsUseCaseTest' \
  --tests 'cn.vectory.syncplayer.ui.util.ShareTempFilesTest'
# BUG-021
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest \
  --tests 'cn.vectory.syncplayer.ui.util.FormatUtilsTest'
# L4-1（v2 新增）
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest \
  --tests 'cn.vectory.syncplayer.settings.SyncRelationEditViewModelRemotePathTest'
# L4-2/L4-3 类 A（v2 新增；含 L4-2 TDD 红灯→绿灯步骤）
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest \
  --tests 'cn.vectory.syncplayer.settings.GainAnalysisViewModelUpdateTest'
# L4-3 类 B（v2 新增；真实 Room 交错）
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest \
  --tests 'cn.vectory.syncplayer.settings.GainAnalysisConcurrencyTest'

# 最终门禁（AGENTS.md：Gradle 编译是类型验证唯一来源；即 L4-4 门禁：compileDebugKotlin + 全量单测）
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew compileDebugKotlin
JAVA_HOME=/home/mar/android-studio/jbr ./gradlew testDebugUnitTest
```

期望：所有列出测试类全绿（含 v2 新增 3 个 settings 测试类：`SyncRelationEditViewModelRemotePathTest`、`GainAnalysisViewModelUpdateTest`、`GainAnalysisConcurrencyTest`）；`compileDebugKotlin` BUILD SUCCESSFUL；全量单测无回归（重点关注 `PlayerViewModelSavedStateTest`、`PlayerViewModelToggleTest`、`PlayerViewModelErrorTest`、`BrowserViewModelErrorTest` 等相邻护栏）。**宣称边界（L4-4）**：BUG-013 的端到端修复（不复活/不回滚）在 L4-2+L4-3 全绿（含类 B 交错用例）后方可宣称。

---

## 附：与权威来源的差异 & 报告未提及的强相关风险

### 与 todo.md / 报告的差异（逐条，不隐瞒）

1. **BUG-002 未采用 "conflated" 字面方案**：todo.md 措辞"conflated Actor 串行保存（每视频只保留最新）"。本计划用 **UNLIMITED FIFO channel**（详见 BUG-002 节论证）：全 channel CONFLATED 会跨视频挤掉前一视频的最终保存（制造新丢保存）；keyed conflate（map+signal）存在迭代器 remove 与 put 替换的丢更新竞态。FIFO 满足 todo.md 的根本要求（VM 层串行化、调用序即落库序），且更简单。若 Oracle 坚持 conflate 语义，可接受的折中为"消费者同 key 去重"（保存前检查 map 中该 key 是否已有更新值），代价是复杂度——本计划默认不含。
2. **BUG-015 报告给出的两个修复方向（generation guard / cancel 前一个 load job），本计划选前者**：与 VM 内既有防护风格（`toggleTrashed` 的提交前校验）一致，无误取消面。报告未指定必选其一，此为设计选择而非冲突。
3. **BUG-016 auto-commit 失败时选择"上报后继续 toggle"（与既有 HardDelete 分支 :545 契约对称），而非"失败中止 toggle"**：报告只要求"复制 :219-244 的 auto-commit 逻辑"，未规定失败语义；对称选择可预测且不静默，理由已写入该节。若评审倾向 fail-stop（保留 pending delete、toggle 可重试），改动点仅一处（autoCommit 后判断返回值决定 return），已在设计上预留。
4. **BUG-019 队列耗尽不发 ErrorEvent（v3 oracle #2 后立场翻转）**：v2 曾选"耗尽停在当前视频、不加结构化终态"，论据为"VM 无 Context 无法生成资源文案"。oracle #2 指出该论据不成立——无 Context 只排除文案生成，不排除**结构化终态**；且 PlayerScreen.kt:163-174 实际行为是正常耗尽（`nextId==null`）即 `currentOnExit()` 退出，"停住"与既有终态不一致。v3 修订：`PlayerUiState.queueExhausted` 结构化标志 + UI 消费退出（详见 BUG-019 节）；仍**不发 ErrorEvent**（正常 EOF 非错误，该半句保留）。
5. **R2 报告称 `PlayerViewModel.kt:215` 为 `if (video != null)` 行，实读为 :216**（一行偏移，语义一致）；BUG-016 报告称 `:144` 无 guard 版 set 在 `:182`——报告指的 :144 是 guarded 版，两处均已核实并分别处理。

### v2 修订的偏差与核对记录（Lane2 交接项；语义以 L2 计划为准，以下为实读差异/选型）

6. **L2 的 import 指令为 no-op**：L2 L4-1 实现要点要求「import 增补 `cn.vectory.syncplayer.data.model.SyncProtocol`」——本轮实读 `SyncRelationEditViewModel.kt:11` **已存在**该 import，无需增补（语义不变，仅省一步）。
7. **规则②步骤 3 的 seam 选型（L2 明文授权 Lane4 定；v4 oracle #2 语法定稿）**：L2 原文「实现手段由 Lane4 定：暴露作业引用/注入可观测 seam 等」——本计划选定 `analysisJob` 改 **`internal var analysisJob: Job? = null` + `private set`**（`GainAnalysisViewModel.kt:70`），依据：`internal var` + `private set` 即"模块内可读、类内可写"（AGP test source set 为 friend module，单元测试可读）；~~v3 草案 `var + internal get` 语法非法（getter 可见性须与属性一致），已作废~~；项目零 `@VisibleForTesting` 使用（grep 核实），不引入新注解惯例；改动最小（一行）。
8. **L2 行号锚点复核结果（零漂移）**：`SyncRelationEditViewModel.kt`（:106/:176/:212/:249-265/:273-278/:280/:283/:290/:301/:306-307）与 `GainAnalysisViewModel.kt`（:70/:104/:110/:112/:118/:136/:148/:153-154/:158/:160-162/:169/:176-177/:201-202/:207/:214）全部精确命中，与 L2 计划引用一致；`updateAudioGain` 已在 `VideoItemDao.kt:245-246`、`VideoRepository.kt:132-133` 落地（L4-2/L4-3 依赖满足）；`relation_invalid_remote_path` 尚不存在于 `res/values/strings.xml`（grep 核实，L4-1 需 +1，与 L2 移出清单 :708 一致）。
9. **L4-1 红灯形态澄清**：L2 未细分工，本计划明确——纯函数期望表为新增 API 的表征测试（无红灯步骤）；行为性红灯由 save() 用例承担（现状 root/`..`/协议未知均原样落库，`coVerify(exactly = 0) { save(any()) }` 可编译且红）；SFTP root 保留为绿锁。与 L2 评审 #6「不得以编译失败当红灯」的纪律一致。

### 报告未提及的强相关风险（只列不展开）

1. `imageviewer/ImageViewerViewModel.kt:96-115 loadImage` 与 BUG-015 同构竞态（R2 报告已点名但不在本 Lane 写域）——本 Lane 修复后 ImageViewer 仍带病，建议另开任务。
2. **读-改-写快照竞争家族**（R2 报告族级提示；v3 oracle #8 修正条目）：~~`FileOperationsUseCase.updateStar`（:93-117）~~——实读该函数**已是单列 API**（`videoRepository.updateStar(videoItemId, star)` :108，无快照回写），非竞争点，移出；`PlaybackRepository.toggleTrashed` 的 `affected==0` upsert（:60-76）等同类 read-modify-write 点仍未在本计划范围。
3. `FileOperationsUseCaseTest:419-422` 既有注释声明 happy-path share 不可 JVM 单测——BUG-017 新用例 6/7 依赖"mock context 下 `FileProvider.getUriForFile` 抛异常"这一未在契约中固化的行为（v5 更正交叉引用：~~已在用例设计中给出 Robolectric/降级备选~~——该备选已于 v4 删除（helper 测试不能替代 UseCase 接线证据）；若该行为不稳定，修 stub 而非降级断言层，见 BUG-017 风险节）。
4. `progressSaveChannel` 消费者生命周期（v3 后；v4 #4 更正）：消费者协程宿主 `progressSaveScope`（AND-001 语义）——该 scope **不在 `onCleared` 取消**（与 AND-001 冲突的说法已删），消费者随 VM 实例被 GC / 进程终止而终结（终结时未落盘入队项的丢失与现状等价）；每次保存是消费者内的子 Job（子以 CE 完成不上浮取消父——见 BUG-002 方案要点），单次 CE 不再杀死消费者。空闲 channel/消费者是否被及时回收由 GC 决定，**不设"必然泄漏/必然回收"绝对断言**（oracle 残留风险收紧）；量级 = VM 实例数，观察点。
5. `formatFileSize` 的 `%.1f` 默认 Locale 行为（小数逗号等）——**测试侧已固定 `Locale.US` 并 `@After` 恢复**（BUG-021 节，v3 oracle #8 收紧）；被测函数随宿主 Locale 输出为**生产现状**，残余仅展示层小数符号差异，非本 BUG（窄聚焦，不触碰生产格式化行为）。
6. `undoDeleteErrorFlow` 的 value 拦截包装（`PlayerViewModel.kt:130-139`）与 BUG-016 新增的 onError 路径交互：auto-commit 失败会经该 flow 变成 `ErrorEvent`（seq 递增），UI 已有消费逻辑，无需改动——列为观察点。

### v3 返工记录（oracle 首审 FAIL 8.0：6 Major + 2 Minor，逐项响应）

> **历史记录声明（v5 加）**：本节与下节「v4 返工记录」均为**审计留痕，不可作为实施指令**——表中沿用的旧表述（`internal get`/`private set`、「受监督子 Job」、「有界 join」、`fileSize = plain.size`（Int）、「缺 42 键」等）已在后续轮次更正，**实施一律以正文当前方案为准**；两表的价值仅在于追溯每轮 oracle 意见与本计划的响应轨迹。

| # | oracle 问题 | 响应 | 改动落点 |
|---|---|---|---|
| 1 | BUG-002 单次保存 CE 杀死唯一消费者 | **采纳** | 方案 :88-113（受监督子 Job + 每次 join）；TDD 3 个新用例（首存 CE 后二次可执行 / 普通异常后继续 / viewModelScope 取消后最终保存）；删「必然清空/完全有界/与进程死亡等价」绝对表述（风险 :144-148） |
| 2 | BUG-019 耗尽策略 UI 判断错 | **采纳** | 触发链 :596-625 补 STATE_ENDED else 实况；方案 :626-690 `queueExhausted` + UI 消费退出；PlayerScreen.kt 入写域（:1037-1038）；prev 空洞边界声明（:668-676） |
| 3 | L4-1 缺「协议与 sourceId 同一快照」 | **采纳** | 新增小节 :862-905（快照捕获 + 门控测试第 5 项）；标注为对 L2 契约的**实现强化**，未回改 L2 引用原文 |
| 4 | BUG-017 接线测试 IV 不匹配 | **采纳** | TDD 测试 6/7 重写（独立密文+IV、`.copy(iv=)`、内容断言）+ 新增测试 8（解密准备期清理，红点=外层 catch 缺失时） |
| 5 | BUG-002 两绿锁不证性质 | **采纳** | TDD 重写为有状态 fake 断言最终持久值；跨视频先堵后放；seek 回退护栏 |
| 6 | BUG-016 草案未定义变量+不存在回调 | **采纳** | helper 签名 `autoCommitPendingHardDeleteLocked(incomingId, incomingIsHardDelete, onError)`（不依赖未读 previousTrashed）；NoGuard 显式 `{ errorFlow.value = it }`；测试 3 真实 flow + 计数 adapter |
| 7 | BUG-017 预创建新失败窗口+长名 | **采纳** | 方案 :503-508 外层 try/catch 清理责任+测试 8；uniqueShareFile 字节预算 MAX_LEAF_BYTES=240 + truncateUtf8 + 回退名；测试 4 哨兵内容验证不变 |
| 8 | Harness/锚点不实 | **采纳** | L4-1 harness 调度两条（:919-922）；类 B discover 等待+entered latch（:982-991）；seam 改 `internal get`/`private set`（:966-969）；写域表 8→9（:1033）；updateStar 条目划线移出（:1130）；updateAudioGain `VideoItemDao.kt:245-246`、MediaUris `provider/MediaUris.kt`（:1033-1046 区域） |

**说明性补充（非申诉）**：①问题 1 中「UNLIMITED≠有界」——v3 保留 channel 容量 UNLIMITED，"有界性"由**每次保存的有界 join**与消费者退出策略承担（单次调用时延上限 = 该次 save 的 join），非队列容量；②问题 2 的 UI 接线（LaunchedEffect 消费）在 JVM 单测层不可执行（项目无 compose-ui-test 基建），验收为 compileDebugKotlin + 代码走查，已在 BUG-019 节声明。

### v4 返工记录（oracle 复审 FAIL 8.3：4 Major + 1 Major 测试参数 + 1 Minor + 残留措辞，逐项响应）

| # | oracle 问题 | 响应 | 改动落点 |
|---|---|---|---|
| 1 | [Major] BUG-017 长名截断不收敛死循环（截断对象含后缀 → `createNewFile` 永假） | **采纳** | 方案重写（BUG-017 节）：仅截 stem、`-$videoId[-$n]` 后缀完整保留、ext 单独限长（`MAX_EXT_BYTES`/`FALLBACK_EXT=".bin"`）、全分支叶名 ≤ `MAX_LEAF_BYTES` 不变式、`check(n < 1_000)` 收敛 guard；正确性论证补收敛性证明；A 组新增用例 6-10（ASCII 长 stem / CJK+emoji codepoint 边界 / 超长 ext / 同长名 100 次有界重复 / 不同 id） |
| 2 | [Major] seam `var + internal get` 语法非法 | **采纳** | L4-3 规则②步骤 3 改 `internal var analysisJob: Job? = null` + `private set`（模块内可读、类内可写）；删「internal var 必然开放赋值权」错误论据；附录偏差 7、写域表 GainAnalysis 行同步；注明编译错误不计 TDD 红灯 |
| 3 | [Major] 测试 6/7/8 stub 与真实签名不符（`openCiphertextRead(Long id)` 非 URI） | **采纳** | 测试 6/7/8 stub 改按 id 分发（`openCiphertextRead(201L)`/`(202L)`）、`fileSize = plain.size.toLong()`；删除 P:580「允许退化为纯 helper 测试」备选（helper 测试不能替代 UseCase 接线证据） |
| 4 | [Major] BUG-002 join/取消文案矛盾 | **采纳** | 要点/风险统一「逐次 join 保证串行，不保证时间/容量有界」；队头阻塞=**接受的残留风险**（禁加超时丢保存）；Job 父子关系纠正（父=消费者普通 Job；子以 CE 完成不上浮取消父，无需监督层）；删「onCleared 取消 scope」（附录风险 4 同步）；门控用例 `gate.complete` 一律 `finally`（断言策略节+测试 1/3/用例 6）；验收计数更正「新增 6」 |
| 5 | [Major] 跨视频 fake `thirdArg`=duration | **采纳** | fake 完成点一律 `secondArg<Long>()`（position）；测试 3 门控改首笔（AtomicBoolean）；CONFLATED 失败形态更正说明（`{42→30_000, 43→1_000}`——旧视频保留首笔 30k，最终保存被顶掉；非"缺 42 键"） |
| 6 | [Minor] BUG-019 effect 前向引用+用例缺口 | **采纳** | effect 移至 `currentOnExit` 声明（:155）之后；effect 体首查 `vm.uiState.value.queueExhausted` 实时终态（防旧 composition 值误退出）；走查覆盖该交错（`isExiting` 只防重复退出）；用例 4 更名+断言 `queueExhausted == true`；新增用例 6（旧请求恢复不得发布耗尽，门控 getById）；验收/写域表 5→6 用例 |
| 7 | 残留措辞同步 | **采纳** | ①BUG-016 风险「日志只打 code」→ 实况（Throwable 含 cause 链，不新增 path/凭据输出）；②BUG-021「唯一变化严格限 ≥1PiB」→ 临界邻域实际旧/新对照；③BUG-017「无新 catch」→「新增 cleanup catch 后原样重抛」；④写域表头「9，含 strings.xml（8 Kotlin + 1 XML）」；⑤L4-1 快照收窄为「协议相关字段」（name/localBasePath 维持现读，声明不含） |
| 补充 | v3 说明性补充①「有界性由每次保存的有界 join 承担（单次调用时延上限=该次 join）」 | **更正作废** | `join()` 挂起等待**无时延上限**——v4 #4 的统一表述取代之；channel 容量仍 UNLIMITED，串行保证≠时间有界 |

**响应统计**：7/7 采纳（含 1 项自我更正），无申诉。

### v5 返工记录（ora-8 复审 FAIL 9.2：1 Major + 3 Minor，逐项响应）

| # | ora-8 问题 | 响应 | 改动落点 |
|---|---|---|---|
| 1 | [Major] BUG-019 用例 6 未建立旧/新请求交错（StandardTestDispatcher 下两次连调+放 gate，交错不必然；删 guard 可假绿） | **采纳** | 用例 6 重写为分步可执行：`entered` AtomicBoolean 侧信道（mock 进门控即置位）→ `runCurrent()` 后**断言 entered**（旧请求确已挂 gate）→ 新请求 `advanceUntilIdle()` 后**放 gate 前**断言 video=1 且 flag=false → `finally` 放 gate+推进 → 终态双断言；**变异验证注记**：删耗尽发布点 guard → flag 断言红；~~删提交点 guard → videoItem 断言红（两 guard 各有专属红灯锚点）~~（**v6 归属更正**：提交 guard 的 video 红证据由 BUG-015 既有用例 1/4 承担，用例 6 不作此声称） |
| 2 | [Minor] UI 变量名 `vm` | **采纳** | BUG-019 effect 代码块/交错正确性段/可验证性声明中 `vm.uiState.value` → `viewModel.uiState.value`（PlayerScreen 实参名；不加别名不扩写域；VM 层测试的 `vm.` 用法不在改动范围） |
| 3 | [Minor] BUG-002 生产接线片段误删 | **采纳** | 恢复完整 `saveProgress(position, duration)` 替换块（捕获 videoId、无视频 return、`trySend(PendingProgressSave(...))`）+ 明示"不得残留独立 repository-save launch"；代码块英文注释 `supervised child job` → `child job of the consumer`（与正文父子关系一致） |
| 4 | [Minor] 长名 guard/测试表述 | **采纳** | guard 注释与论证改「最多 1000 次候选尝试、耗尽显式失败（经 buildShareIntent 既有异常边界转为分享失败）」；用例 9 收窄（repeat(100) 只限调用次数；删「测试本身防单次调用挂死」声称——无独立墙钟不作此宣称）；**新增用例 11**（预占全部 1000 候选：断 IllegalStateException、预占文件零覆写、目录零额外产物；测试内复算候选序列的规则漂移自动转红；UseCase 层失败语义由既有边界承担）；验收/涉及文件/写域表 10→11 用例 |
| 5 | 交叉引用扫尾 | **采纳** | `.bin` 4 字节更正（代码注释）；附录残余风险 3 删除失效「Robolectric/降级备选」引用（备选 v4 已删）；v3/v4 返工记录前加「仅审计留痕、不可作实施指令」总注；L4-1 localBasePath 读取时机按 oracle 指示**不动**（维持 `normalizedBasePath` 前置计算现状） |

**响应统计**：5/5 采纳（4 项 ora-8 问题 + 1 项交叉引用扫尾），无申诉。

### v6 返工记录（ora-8 v5 复审 FAIL 9.4：2 Minor + 1 文字扫尾，最终小修）

| # | ora-8 v5 问题 | 响应 | 改动落点 |
|---|---|---|---|
| 1 | [Minor] 用例 6 finally 形态与调度 API | **采纳** | 步骤 1/2 整体包进真实 `try`（含 `runCurrent()`、`entered` 断言、放 gate 前双断言）；`finally` 同时 `gate.complete(Unit)` + `advanceUntilIdle()`；最终 video/flag 断言置于 `finally` 之后；`runCurrent()` 标注为 TestScope 扩展、明示禁 `testDispatcher.runCurrent()`；gate/entered 语义与变异说明保留（后者按 #2 归属修正） |
| 2 | [Minor] 两条 guard 变异正确归属 | **采纳** | 用例 6 变异注记只声称「删**耗尽发布 guard** → 最终 `queueExhausted` 断言红」；「删**成功提交 guard**」的 video 红证据归属 BUG-015 既有用例 1（旧请求迟到覆盖）与用例 4（第二挂起点门控），不新建重复用例；v5 返工记录行 1 的「两 guard 各有专属红灯锚点」表述划线更正（历史表内就地标注，不删除行） |
| 3 | [扫尾] localBasePath 精度 | **采纳** | L4-1 强化段与代码注释：「未捕获、仍按落库时刻现读」→「保存使用此前已计算的 `normalizedBasePath` 局部值（:279-286 前置计算产物），本计划不改变其前置计算/保存时机」；实际方案零改动（纯表述精度） |

**响应统计**：3/3 采纳，无申诉。全文扫描确认：无 `testDispatcher.runCurrent()` 残留、用例 6 无「提交 guard 红」残留表述（v5 历史表中的旧表述已划线标注归属更正）。
