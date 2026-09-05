# 车道门禁史（执行编排记录，增量落盘）

> 由执行编排会话维护。供最终执行报告（§车道门禁史）直接引用。

## L1 sync-engine（2026-09-05-plan-sync-engine.md）

评分史：7.2 → 7.0 → 8.0 → **v3.1 复审 9.6 PASS**（2026-09-05，独立评审代理，逐行对照生产源码 + javap 实证 API）。

评审结论原文要点：

```
VERDICT: PASS
SCORE: 9.6/10
```

- 无未决 Critical/Major；4 项 Minor（见下）。
- 上轮 7+2 项全部判定「已修复」（LAZY 启动窗口三语句拆分 + UnconfinedTestDispatcher 论证；停服 TOCTOU 入口作废序 + #22；Service 全量接线对照 SyncService.kt 逐行验证；existsStrict SAF/FileVolume 收窄 + S8/S9/S10；清理 CE 防护 cleanUpAttempt/13b/probePendingTmp 三处；测试确定性 S2/#17/#10/#13/#14；术语统一）。
- 5 项申诉全部「接受」（①分层措辞 ②#11/#12 降级回归锁 ③FORCED/ELIGIBLE 分流即失效协议 ④删 #13 重入模拟 ⑤探测 CE 边界如实标注）——评审者逐步推演/源码实证后认可，覆盖强度未降。
- ~60 处行级引用无一失实；sshj 0.39.0 SFTPException(statusCode,String)/NO_SUCH_FILE 与 DocumentsContract.EXTRA_LOADING 经 javap 实证；既有测试文件存在性与计划描述一致；BUG-009/010 编号以 todo.md 为准核对相符；AGENTS.md 7 条安全红线无冲突。

### 评审 4 项 Minor（非阻塞）——B-L1 实施简报附件（按此执行，不改计划文档）

1. BUG-007 §3 测试 4（计划 :445-447）标注「回归锁」实为红测试（现行实现 verify 失败路径会调一次 store.cancel，v3 契约收敛为 0）。**实施时按红测试对待**（TDD 先红后绿），勿按「回归锁」预期执行顺序。
2. 测试 #10 步骤注释（:1170）描述了不可能事件（t1 到期回报——实际已被 onSyncRequested(B) 入口 cancelStop 取消）。断言（相对增量）不受影响；实施时理解为准：「此步仅推进时间验证无停服发生」。
3. 测试 #13 用例名「callbacks never reenter coordinator」（:1173）超出实际断言内容（实断言恰一次停服 + stale 丢弃）。用例名可在实施时按评审建议改名（如 `eligible stop executes exactly once and stale re-fires are dropped`）或加注前提，二选一，记录偏差即可。
4. 测试 8/9 用例名小写「ambiguous finalize ...」（:457/:461）为术语残留；建议改「finalize-uncertain」保持一致，记录偏差即可。
5. （残留风险 5，信息性）继承行为：可重试失败删占位行后同 id 重试成功 → getById null → 元数据不写 → 下次 dryRun 自愈；现行与 v3 行为一致，非新增风险。

### PASS 残留风险清单（评审原文摘要，进最终报告 §残留风险）

1. BUG-018 残余①：stopService 调出与实际销毁之间到达的新请求依赖系统行为；生命周期级测试另立。
2. BUG-018 残余②：pendingTaskManager.finalize（SyncEngine.kt:131，try/catch 之外）抛非 CE 异常 → drainLoop 静默死亡、FGS 通知滞留至超时——现状保留，已明示。
3. BUG-007 残余④：外部写者在 rename 与探测之间删 tmp → ABSENT 误判——应用侧不可消除，已如实列为 §5 残余。
4. existsStrict 的 SAF 支持边界①②（provider 完整一致、无外部写者）不可普遍证明——以「不可信即 IOException→UNKNOWN→保守只删行」兜底。
5. 13b 探测 CE 场景生产不可达（NonCancellable+无挂起点），仅防御 mock 违约——纵深防御成本，可接受。
6. （信息性继承行为见上 Minor 5。）

## L2 smb-dao（2026-09-05-plan-smb-dao.md）

评分史：6.5 → 8.6 → 9.2 → **9.6 PASS**（原编排会话评审，实施前已定稿）。

## L3 settings-security（2026-09-05-plan-settings-security.md）

评分史：7.8 → 8.0；**待返工 2**（5 项问题清单见 execution-handoff.md §4.A2，返工后复审门槛 9.5）。

## L4 player-browser-util（2026-09-05-plan-player-browser-util.md）

**待修订**（吸收 L2 计划交接项 L4-1..L4-4）→ 首审门槛 9.5。
