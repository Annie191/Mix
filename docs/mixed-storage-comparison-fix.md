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

## 追加：消除分配盲区与覆盖统计

[eed1ae32](https://github.com/Annie191/Mix/commit/eed1ae32eace0b4a73dc08d381d242f703bc3e23)
已消除旧 pivot 算法的永久盲区。四表完整枚举得到 16 种配置，其中 14 种为混合配置，
包含全部 6 种 2∶2 分配；每轮固定两条基线，从 14 种混合配置中随机抽取 4 种。
因此 2∶2 有被选中的概率，不再永远缺失，回归测试已验证完整组合及平衡分配可以被抽到。

新增 [TiDBMixedCoverage](../src/sqlancer/tidb/mixed/TiDBMixedCoverage.java)，统计两类覆盖：

| 维度 | 示例键 | 含义 |
|---|---|---|
| 引擎分配比例 | `tables=4;tikv=2;tiflash=2` | 四张表中两张读 TiKV、两张读 TiFlash |
| JOIN 两侧引擎 | `tables=4;type=LEFT;left=tikv;right=tiflash` | 四表查询中，一个 LEFT JOIN 的左表读 TiKV、右表读 TiFlash |

每个桶记录 `attempted`、`valid`、`failed` 及各执行状态次数。
只有 `run.status=OK` 才计入有效覆盖：计划有效、结果完整读取，但不代表查询结果彼此一致。
失败路径仅计入尝试和失败；已涉及表数的所有比例桶、已涉及 JOIN 类型的左右引擎组合桶预置零值，
便于发现未尝试或尚无有效结果的组合。

JOIN 左右侧按 SQL AST 统计，区分 INNER/LEFT/RIGHT 等类型和 NATURAL 子类型，保留方向，
不把它解释为优化器的物理 JOIN 输入顺序。嵌套 JOIN 一侧涉及两种引擎时记为 `mixed`。
一次路径按比例计 1 次，按显式 JOIN 节点分别计数；多个 JOIN 可贡献多次。
逗号 FROM 的隐式连接单列为 `pathsWithoutExplicitJoin`，不虚构显式 JOIN 覆盖。

证据保存位置：

- `attempts[].coverage`：本轮主执行的统计；`alternativeCoverage`：替代配置执行的统计。
- `case.json.coverage` 和 `coverage.json`：同一 Oracle 实例跨 check 的累计快照，换数据库后重新计数。

统计在基础表预检通过后的主执行和替代配置执行中更新，预检、重复确认、WHERE 约简及独立重放不计入，
避免重复复现放大覆盖率。累计快照不能直接相加；跨 case 汇总应累加各 attempt 的本轮统计。
本次仅增加观测统计，保持 4/8/6 路径预算和随机抽样，不根据计数自动改变选择或差异判定。

新增 10 项回归测试，覆盖比例与 2∶2、JOIN 方向和类型、零计数桶、失败分流、
多 JOIN/嵌套 JOIN、批次与累计快照、JSON 落盘及统计不影响随机抽样。
49 项离线测试、完整构建和静态检查通过；本次未重跑真实集群。

## 追加：AI 数据准备协议与提示词（第一环节）

新增 [协议文档](data-preparation-protocol.md)、[总任务提示词](../src/sqlancer/tidb/mixed/protocol/overall-v1.txt)
和 [数据准备提示词](../src/sqlancer/tidb/mixed/protocol/data-preparation-v1.txt)。
模型可以决定是否补行、补哪些表列、值和行数，但决策仅限 `observe`、`append_rows`、`stop`。
不提供执行 SQL、修改查询/Schema、更新/删除数据或宣布缺陷成立的操作入口。

新增 [TiDBDataPreparationProtocol](../src/sqlancer/tidb/mixed/TiDBDataPreparationProtocol.java)，提供三个独立入口：

```java
state(stateJson);                 // 校验完整状态包
messages(stateJson);              // 总提示词 + 子任务提示词 + 本轮完整状态
decision(modelContentJson, state); // 校验结构、引用、版本、权限和预算
```

每次状态包含完整 SQL、实际约束、带版本的观测和样本、历史提案/失败及剩余预算。
模型输出必须包含依据、目标、操作、预期和验证需求；预期不当作已验证事实。
只允许请求状态包公布的观测能力；无法验证时明确记录原因。
请求/查询/Schema/数据版本不匹配、过期补行依据、未知字段、生成列赋值及超预算计划均拒绝。
补行值采用带类型表示，整数和小数用字符串保存精度；SQL 外观文本只能作为普通列值。

[六份完整 JSON](data-preparation-protocol.md#完整交互示例) 展示：观测发现原匹配被 WHERE 过滤，
模型决定补入 12，程序反馈三行写入及新版本计数，模型再停止。
示例是人工 fixture，不是真实 DeepSeek 或集群实验；提示词随 JAR 打包。

本环节尚未接入模型调用、元数据/观测采集、补行执行及反馈调度，不改变现有 Oracle 运行路径。
新增 22 项协议回归测试；完整验证结果见 [验证记录](mixed-storage-validation.md#ai-数据准备协议与提示词回归第一环节)。
