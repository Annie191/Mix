# 混合路径漏报修复说明

代码提交：[694778b1](https://github.com/Annie191/Mix/commit/694778b10a7010656bd7ab666191bcff4bd012a1)。

## 问题与修复结果

同一查询、数据和快照下，两表的四条执行路径如下：

| 路径 | t0 / t1 的读取引擎 | 状态 | 结果 |
|---|---|---|---|
| 全 TiKV | TiKV / TiKV | EXECUTION_ERROR | — |
| 全 TiFlash | TiFlash / TiFlash | EXECUTION_ERROR | — |
| 混合 A | TiKV / TiFlash | OK | `{1}` |
| 混合 B | TiFlash / TiKV | OK | `{2}` |

原实现跳过混合路径之间的比较，返回 EXECUTION_ERROR，漏掉 A、B 的结果差异。
修复后返回 MIXED_VS_MIXED_MISMATCH：比较 1 对、`mismatch()=true`、
`allPathsValid=false`，同时保留两个基线错误。

## 代码改动

**1. 比较所有有效路径对。** [TiDBMixedSupport.compare()](../src/sqlancer/tidb/mixed/TiDBMixedSupport.java)
只跳过非 OK 路径；四条有效路径共比较 6 对。比较仍包含列元数据和完整结果多重集：

```java
if (!a.status.equals("OK") || !b.status.equals("OK")) {
    continue;
}
boolean equal = a.columns.equals(b.columns) && a.bag.equals(b.bag);
```

差异分为三类，主状态优先级从上到下：

| 差异配置对 | 分类 |
|---|---|
| 全 TiKV ↔ 全 TiFlash | BASELINE_DISAGREEMENT |
| 单引擎 ↔ 混合 | MIXED_VS_SINGLE_MISMATCH |
| 混合 ↔ 混合 | MIXED_VS_MIXED_MISMATCH（新增） |

**2. 分别记录差异和路径完整性。** `differingPairs`、`mismatchTypes` 保存所有差异对及分类；
`failures` 保存失败路径；`allPathsValid` 表示四种要求的不同配置齐全且全部 OK：

```java
verdict.allPathsValid = completeConfigurations(runs) && verdict.failures.isEmpty();
```

`mismatch()` 根据 `differingPairs` 是否非空判断。只有没有差异且 `allPathsValid=true` 才为 PASS。
路径不完整时，已发现的差异仍会进入保存、确认和报错流程。重放复用同一比较逻辑。

**3. 按原始差异对确认。** [TiDBAiMixedOracle.confirmed()](../src/sqlancer/tidb/mixed/TiDBAiMixedOracle.java)
要求所有原始差异对连续两次有效且仍不一致，允许无关路径失败或恢复：

```java
return expected.mismatch() && confirmations.size() == 2 && confirmations.stream()
        .allMatch(actual -> actual.differingPairs.containsAll(expected.differingPairs));
```

证据新增 `confirmationTargets`、`confirmationOutcomes`，分别记录确认目标和每次确认结果。
确认失败仍保留原始差异；WHERE 约简继续要求两次保持相同差异签名，并保存 `reduction.outcomes`。

## 验证

新增 13 项回归测试，覆盖上述漏报、失败路径、6 对比较、分类优先级、严格 PASS 条件、
两次确认及 JSON 落盘。29 项离线测试和完整 `mvn verify`（含静态检查）通过。
本次未重跑真实集群；具体命令与结果见 [验证记录](mixed-storage-validation.md#比较与确认逻辑修复回归)。

## 追加：基础表预检分流与配置覆盖

**预检决定是否开始本轮比较。** 两个引擎在同一快照下读到相同基础表数据，才继续查询差分；
若不同，记录 DATA_PREFLIGHT_MISMATCH，保存证据，结束本次 `check()`，不尝试下一 revision，
也不进入混合查询差异确认和报错流程。普通 Warning/执行失败仍记为 PREFLIGHT_FAILED。

```java
if (!preflightReady(io, query.tables, record)) {
    preflightMismatch = true;
    report.put("status", "DATA_PREFLIGHT_MISMATCH");
    history.merge("DATA_PREFLIGHT_MISMATCH", 1L, Long::sum);
    break;
}
```

`case.json` 保存快照、两条基础表读取路径及其结果，主轮预检不同另存 `preflight-mismatch.json`。
新增 `preflight-counts.json` 和 `case.json.preflightCounts`，分别累计 PASS、
DATA_PREFLIGHT_MISMATCH、PREFLIGHT_FAILED；计数范围为同一 Oracle 实例，单位为一次预检批次，
包含确认/约简时的重建预检。主轮差异同时单列到 `history.json`，不再淹没在普通跳过中。

**先完整枚举，再按表数决定执行配置。** 配置表示各表的读取引擎，数据及 TiFlash 副本不随配置搬移。

| 查询表数 | 枚举配置数 | 本轮执行数 | 全部有效时比较对数 |
|---|---:|---:|---:|
| 2 | 4 | 全部 4 种 | 6 |
| 3 | 8 | 全部 8 种 | 28 |
| n ≥ 4 | 2ⁿ | 两条单引擎基线 + 随机 4 种不同混合配置 | 15 |

所有选定配置的执行顺序随机。当前 Schema 生成器最多创建 5 张表。
三表的 8 种组合为（K=TiKV，F=TiFlash，依次对应 t0/t1/t2）：
`KKK、KKF、KFK、KFF、FKK、FKF、FFK、FFF`。

[TiDBMixedSupport](../src/sqlancer/tidb/mixed/TiDBMixedSupport.java) 新增 `enumerateConfigurations()`，
`configurations()` 从完整集合选取本轮配置；路径完整性检查同步改为按表数要求 4、8、6 条，
继续拒绝重复配置、缺失基线、不同表集合及失败路径。确认和重放沿用保存的配置集合。
`allPathsValid` 表示本轮选定配置完整有效；更多表的 PASS 不代表未抽中的配置也已通过。

验证新增 10 项回归测试：完整枚举与抽样、4/8/6 条路径的完整性及 JDBC 执行、
预检一致后继续比较、预检不同后停止并单独计数、普通失败分流和证据落盘。
39 项离线测试通过，完整 `mvn verify` 及静态检查通过；本次未重跑真实集群。
