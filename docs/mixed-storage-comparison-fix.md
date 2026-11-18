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

## 追加：实际元数据与只读观测（第二环节）

新增 [TiDBMixedMetadata](../src/sqlancer/tidb/mixed/TiDBMixedMetadata.java)，读取实际列定义、
默认值、生成表达式、有序主键/唯一索引组合及 CHECK，并保留完整 DDL。
前缀长度和表达式索引另存 key_parts；CHECK 的启用状态与该版本 SHOW CREATE 核对。
读取失败或语义未完整表示时标记 metadata_complete=false，不当作“无约束”。
TiDBColumn 保留原始类型、默认值、Extra；引导连接列明确排除生成列。

新增 [TiDBMixedObservations](../src/sqlancer/tidb/mixed/TiDBMixedObservations.java)：

- 表行数、NULL 数、单列键频率和带类型样本。
- 简单列等值 JOIN 的匹配行对、两侧未匹配输入、重复匹配及 WHERE 后统计。
- 原 FROM/JOIN 在 WHERE 前后的输入行数、原查询是否为空、显式列 GROUP BY 的分组大小与 HAVING 前后摘要。

匹配数使用 INNER JOIN / EXISTS 语义独立计算，**外连接输出行数不当作匹配数**；
分组前输入、分组总数和 HAVING 后组数分别记录，**聚合结果行数不当作分组前行数**。
复杂 JOIN、跨多表的局部 WHERE 统计及未支持的分组语义明确记为 unsupported，不返回伪造的 0。

探测共用一个 TiKV 快照，结束后恢复会话；默认最多 64 次探测、每列表 64 行、10 秒探测预算。
结果区分 exact / sampled / unsupported / unknown / error；审计保留 SQL、TSO、目标、版本及错误。
statePacket() 将实际元数据与观测接回 v1 协议。JSON 落盘保留 null，避免遗漏必需字段。

Oracle 在初始填充后保存 actualMetadata、metadataEvidence、observations、samples、
observationEvidence、observationSnapshot 和 availableObservations。
本环节仍未执行新的 AI 补行决策或反馈循环。
详细语义、支持边界及核对方法见 [说明文档](data-preparation-observations.md)。

新增 24 项离线回归及 2 项真实 TiDB 验证；共 98 项测试，97 项通过、1 项原 TiFlash 集群测试预期跳过。
完整构建和静态检查通过。真实探测验证使用目标提交的独立 unistore 实例，未宣称 TiFlash 差分验证通过；
命令及可核对的计数结果见 [验证记录](mixed-storage-validation.md#实际元数据与只读观测回归第二环节)。

## 追加：可控追加行执行器（第三环节）

新增 [TiDBMixedAppendExecutor](../src/sqlancer/tidb/mixed/TiDBMixedAppendExecutor.java)，
接受人工构造的 v1 append_rows JSON，暂不调用模型或自动调度补行。

- TiDBInsertGenerator 新增独立普通 INSERT 模式，复用 SQLancer 常量补齐未指定列；
  参数化写入，不生成 REPLACE、IGNORE 或 ON DUPLICATE KEY UPDATE，不写生成列，未指定自增列交给数据库。
- 写前复核实际元数据、版本和会话。实例保存剩余行预算，每次尝试扣一行，失败也扣；
  超预算计划整份拒绝，不能用新状态包补回预算或重放请求。
- 整数/DECIMAL 使用字符串表示并经 BigInteger/BigDecimal 绑定；二进制、文本和 NULL 保留各自类型。
  每行独立事务，核对写前/写后完整行多重集合及指定值，确认只追加一行后才提交。
  Warning、舍入、约束错误或旧行变化均回滚该行，其他成功行保留。
- 逐行记录指定值、补齐值、实际值、影响行数、版本及错误，区分成功、失败、未执行、提交结果不确定。
  提交不确定时停止，要求重建，不自动重试；观测验证需求仍需后续环节执行。

使用方式、反馈和边界见 [执行器说明](data-preparation-append-executor.md)。
新增 18 项离线及 5 项真实 TiDB 测试；共 121 项测试，120 项通过、1 项原 TiFlash 测试预期跳过。
完整构建及静态检查通过。
真实验证使用目标提交的 unistore 实例，非分布式 TiFlash 验证；详细结果见
[验证记录](mixed-storage-validation.md#可控追加行执行器回归第三环节)。

## 追加：提示词与闭环行为验证（第四环节）

新增 [TiDBMixedPreparationHarness](../src/sqlancer/tidb/mixed/TiDBMixedPreparationHarness.java)，
将模拟响应、v1 协议、追加执行器和只读观测串联。每轮独立记录：
**模型预期 → 逐行写入结果 → 当前实际观测 → 已达成／未达成／未验证**。

目标由固定用例提供可测断言，只有相同查询/Schema/数据版本的 exact 事实能证明达成。
补行后刷新观测，写入成功、模型解释、有限样本、非空结果或 stop 都不能直接算覆盖成功。
空结果不触发自动补行；“WHERE 前有匹配、WHERE FALSE 后为空”作为保留目标通过实测验证。

已失败的相同行提案在再次写入前拒绝，改 reason 或观测 ID 不算新依据；
不同方案连续两轮无成功写入也会停止。提交不确定时终止并保持未验证，不自动重试。
提示词同步明确上述边界。

新增六份模拟脚本和 11 项离线测试，覆盖共享值被过滤、唯一键阻止重复并换侧修补、
覆盖已足够、仅有限样本、连续约束失败及有意义的空结果，并加入误判和重试反例。
使用实际 H2 写入/事务/探测，TiDB 元数据和会话由 fixture 模拟；未调用真实模型或接入 Oracle 自动补行。
场景结果和逐轮证据见 [闭环验证说明](data-preparation-loop.md) 与 [运行记录](data-preparation-loop/results.json)。
共 132 项测试，124 项通过、8 项真实集群测试预期跳过；完整构建及静态检查通过。
本轮未重跑真实 TiDB/TiFlash，命令和反例断言见
[验证记录](mixed-storage-validation.md#提示词与闭环行为回归第四环节)。

## 追加：真实 DeepSeek 与配对效果比较（第五环节）

新增真实 DeepSeek 客户端和配对实验入口；Oracle 通过 `MS_DATA_PREPARATION=DEEPSEEK` 开启补行闭环。
模型仍只能 observe / append_rows / stop，追加后必须重新观测，再进入基础表预检和混合差分比较。
模型解释、写入成功和查询非空均不直接算覆盖成功。

- 客户端限定调用数和超时，不隐式重试或回退模拟响应；记录真实模型、token、耗时和估算费用，不保存密钥。
- RANDOM/SHARED 复用现有值策略，三组统一用普通 INSERT，限制为 6 次行尝试、96 次探测、120 秒准备预算；AI 最多 3 次调用。
- 每个种子恢复相同初始行，使用相同 Schema、SQL 和全部四种引擎配置，轮换策略顺序。
  追加后的最低审计观测也扣探测预算，防止只测有利指标。
- 数据场景和引擎分配分别统计；只有当前版本的精确观测可以证明场景变化。
  保存计划执行率、可验证目标达成率、新增/丢失场景、无新增场景补行、未验证收益、重复失败、时间和费用。
  引擎路径无效时，不将新增数据场景计作新增有效场景；失败用例仍保留在分母。
- Oracle 自由文本目标没有机器判定规则，保守记为未验证；配对实验另用预先定义的可测目标，二者不混淆。
- 真实 Oracle 联调发现 ZEROFILL 整数样本会返回 `"00000"`；观测层改用 BigInteger 规范化为 `"0"`，
  同时保留无符号 BIGINT 精度，避免合法数据库事实在模型调用前被协议拒绝。

六种受控小表用例用于真实集群比较，不代表长程随机测试或缺陷发现率。
完整用法、指标定义及值策略对照边界见 [联调说明](data-preparation-benchmark.md)。
实际结果、证据及局限见 [验证记录](mixed-storage-validation.md#真实集群与策略对照第五环节)。

## 追加：拒绝计划反馈与预算配置（2026-11-17）

修复“提案被拒绝就结束，模型无法修订”的缺口：

- 超预算、字段/观测/证据 ID、格式或版本错误：执行前拒绝，把结构化 `validation_feedback` 和剩余预算放入下一次请求的 history，允许重新规划、观察或停止。
- 每次拒绝仍扣调用和时间；没有写入就不扣行数、探测预算，不改数据版本或丢掉当前观测。额度耗尽后终止，不增加重试额度。
- 冻结、已发现差异、提交不确定、执行上下文异常、传输错误和重复已失败行提案仍立即停止。
- `MS_PREPARATION_ROWS` 可配置所有表合计的追加尝试行数，默认 6，范围 0—1000。
  Oracle 和配对实验共用配置，RANDOM/SHARED 按同一总额分配，防止只给 AI 放宽预算。
- 提示词明确先计算所有表合计行数，收到拒绝后应修订实际计划，不能只改解释或请求 ID。

6 行是原小表对照实验的统一上限；两表的一次一对多只需追加 3 行，多表及更多边界场景可提高上限。
原实验的指标保留不变；核对原始记录后，更正 seed 4 为 7 行提案（seed 2 为 8 行）。
模拟回归覆盖两种超额提案被拒绝后改补 3 行、实测获得 2 对匹配和 1 个重复键，以及预算耗尽和必须停止的边界。
未重跑真实 DeepSeek，不把模拟纠错成功当成真实模型成功率提升。
对应记录见 [拒绝后修订实测](data-preparation-loop/rejection-recovery-results.json)。
153 项测试中 144 项通过、9 项按环境开关跳过，完整构建及静态检查通过。

## 追加：AI 结构化目标与生产验证（2026-11-17）

修复“AI 提出目标，生产流程始终无法验证”的缺口：

- 响应新增 `structured_goals`，由 AI 选择能力 ID、字段、比较方式和期望值；程序校验合法性，文字解释保留。
  能力目录新增 `goal_fields`，支持精确计数、空结果和分组汇总。没有相应能力的边界意图仍记未验证，不用相近指标替代证明。
- 补行后自动测量所有已接受目标，与显式验证请求、最低审计观测去重并统一扣预算；超预算先拒绝并反馈修订。
- 每项目标记录 `expected → actual → evidence → achieved/not_achieved/unverified`。
  只有当前 query/schema/data 版本的精确事实能证明达成；旧版本、有限样本、错误、缺测和提交不确定均不能证明。
- Oracle 保存 `model_goal_evaluation` 和最终独立复测结果；Benchmark 的固定断言及其成功率继续保留，另加 AI 目标评价和指标，互不替代。
- 已接受目标累计保留，后续 `stop` 或空目标数组不能删除未达成目标；缺少新字段的生产响应会被拒绝并反馈纠错。

核心反例已实测：补行后匹配数=2、重复键=1，固定断言通过；但 NULL 数、外连接未匹配输出均实测为 0（模型目标均为至少 1），
这两项目标未达成，AI 整体目标正确记为 `not_achieved`。停止后仍保留失败项。
本轮使用模拟响应和实际 H2 写入/探测，未重跑真实 DeepSeek 或 TiDB/TiFlash，不更新旧实验成功率。
协议、完整响应和字段语义见 [结构化目标说明](data-preparation-structured-goals.md)，
逐项证据见 [回归实测记录](data-preparation-loop/structured-goals-results.json)。

本轮共 160 项测试：151 项通过、9 项真实集群测试按环境开关跳过；格式、XML、Checkstyle、PMD、SpotBugs、命名及差异空白检查通过。

## 追加：基础数据与查询阶段覆盖分离（2026-11-17）

修复“表里有值就算查询覆盖”的统计缺口：

- `scope_version=2` 分别报告 `base_data` 与 `query_stages`。表内 NULL、边界值、局部 JOIN 事实仍保留，但不再直接贡献查询阶段分数。
- 增加整数边界值及相邻值在原 FROM/JOIN 输出、完整 WHERE 后的精确计数。seed 3 同形反例中，仅有 `t0.k=10` 时 WHERE 边界输入为 0；补齐 `t1.k=10` 后才变为 1。
- 新增 WHERE 输入/输出 NULL、完整谓词 TRUE/FALSE/UNKNOWN、聚合参数实际 NULL、分组输入 NULL；不从源列 NULL 推断表达式结果。
- 已采集的 `left_unmatched_after`、`right_unmatched_after` 纳入查询阶段覆盖；被 WHERE 去掉的外连接输出不计分。普通过滤仍与具体边界分开记录。
- 新能力可作为 AI 结构化目标，由现有闭环自动重新观测；所有探测继续扣预算。写入成功和模型解释不能证明阶段目标达成。
- 基础数据与查询阶段分别统计新增/丢失、缺测和收益。两轮都没测到的能力也不能冒充“完整且无收益”。旧未分范围指标单列 `legacy_unscoped_scene_hits`，不计入新版查询阶段分数。

实测闭环第一轮只补左表，基础数据新增但边界目标未达成；第二轮补齐右表，目标经实测达成。
另覆盖三表局部匹配被后续 JOIN 阻断、聚合参数 NULL 与输入列 NULL 相反、外连接输出被过滤等反例。
上述为逻辑关系阶段证据，物理执行路径仍由原引擎覆盖单独核验；不支持的谓词边界明确保留未验证。
本轮使用模拟响应和实际 H2 SQL，没有重跑真实 DeepSeek 或 TiDB/TiFlash，不改写旧实验覆盖率。
能力、边界及报告结构见 [查询阶段覆盖说明](data-preparation-query-stage-coverage.md)，
逐项证据见 [实测记录](data-preparation-loop/query-stage-coverage-results.json)。

本轮新增 10 项回归；共 170 项测试中 161 项通过、9 项真实集群测试按开关跳过。完整构建、formatter/XML/Checkstyle/PMD/SpotBugs、命名和差异空白检查通过。

## 追加：主动参数化观测与同版本证据保留（2026-11-17）

- 开启 AI 准备后，初始只采每表行数、最多两行样本、query_empty，并扣探测和时间预算，不再把全量诊断预先交给模型。
  Benchmark 的完整前测仍作独立评价；模型接收另行采集的 `model_initial_state`，RANDOM/SHARED/AI 共用同样的初始采集与预算。
- 增加程序从 AST 编译的参数化目录：指定键的基础/查询阶段行数、原 ON 的指定键匹配、指定谓词及逻辑子树的 TRUE/FALSE/NULL 分布、指定完整分组键的输入行数。
  非等值、复合和嵌套 JOIN 可用局部诊断；局部匹配与完整查询输出分别说明，不让模型输出 SQL 或修改 target。
- 参数使用带类型的值及限定枚举；整数/DECIMAL 保留精度，非空值使用 JDBC 占位符，SQL 外观文本也只是普通数据。非法类型、参数、重复请求和超预算在读取前拒绝。
- 参数化结构化目标携带原参数，补行后自动重测，最终独立评价也重测同一键和阶段；不同键、阶段及旧数据版本的证据不能串用。
- 同版本 observe 按“能力＋规范化参数”更新证据，保留未刷新的当前观测/样本；刷新失败也替换该请求的旧成功。
  全部 INSERT 确定失败且版本未变时保留其他证据，成功写入或提交不确定时仍使旧证据失效。
- 独立记录主动 observe 提案/执行、探测请求和参数化探测数；参数化诊断不直接通过枚举更多键增加查询阶段覆盖分数。

受控实测跑通 `observe → append_rows → stop`：先测得原 WHERE 排除了 1 条连接结果、指定键输出为 0，随后两表各补一行，重新测得指定键输出为 1，目标才达成。
准备探测预算依次为 96 → 初始后 91 → 主动观测后 89 → 补行验证后 88。
新增 13 项回归还覆盖复合/非等值 ON、三表局部匹配被后续 JOIN 阻断、NULL/复合分组、精确 DECIMAL、非法输入、证据保留/替换及最终复测。
本轮使用模拟响应和实际 H2 SELECT/INSERT，没有调用真实 DeepSeek 或运行真实 TiDB/TiFlash；不改写旧真实实验，也不声称真实模型的 observe 使用率或覆盖收益已提高。
请求协议、能力边界及交互见 [主动观测说明](data-preparation-active-observation.md)，
逐轮事实见 [实测记录](data-preparation-loop/active-observation-results.json)。

本轮共 183 项测试：174 项通过、9 项真实集群测试按开关跳过；完整构建、formatter/XML/Checkstyle/PMD/SpotBugs、命名、JSON、本地文档链接及差异空白检查通过。


## 追加：模型不可用时继续差分（2026-11-17）

修复“缺少凭据导致整个用例跳过”的流程缺陷：

- 客户端初始化纳入准备阶段。缺少凭据、凭据文件不可读、配置错误或模型调用失败，均记录 `termination=model_unavailable` 及脱敏原因，冻结补行，用已有数据继续导出、等待副本、共同快照、基础表预检和差分比较。
- `model_failure.stage` 区分 `initialization` / `request`；错误码区分缺少凭据、凭据不可读、配置无效和调用失败。不保存密钥、原始异常消息或失败 HTTP 响应正文。
- 初始化失败不消耗调用/行/探测额度，只扣耗时；实际调用失败扣本次调用和耗时，不自动重试。此前成功提交的数据、版本和有效证据继续保留，不自动套用固定补行规则。
- 写入提交状态不确定仍为 `requires_rebuild=true`、`final_state=null`；先保存准备证据，再以 `PREPARATION_UNCERTAIN` 阻止后续测量及差分。数据库执行/会话恢复异常不被包装成模型不可用。
- Oracle 和 Benchmark 使用相同处理边界。模型可用性与最终差分结果分别记录；继续测试不代表 AI 准备成功，也不直接代表差分 PASS。

回归覆盖无凭据/空凭据、不可读文件、无效配置、HTTP/传输故障、成功写入后的后续调用故障、提交回执丢失及数据库观测异常。
JDBC 流程回归验证：初始化或调用失败后，仍完成导出、快照、预检及四条路径的六对比较；提交不确定则不能继续。
测试使用实际 H2 读写和模拟模型/引擎接口，未调用真实 DeepSeek 或运行真实 TiDB/TiFlash。

本轮新增 7 项回归；共 190 项测试中 181 项通过、9 项真实集群测试按开关跳过。完整 Maven verify、formatter/XML/Checkstyle/PMD/SpotBugs（0 bugs）、命名、本地文档链接及差异空白检查通过。

## 追加：共同快照获取与核验（2026-11-18）

修复 `beginSnapshot()` 在事务外取得 `0` 并继续比较的问题：

- 先检查会话空闲，再执行 `BEGIN → SELECT TIDB_CURRENT_TSO() → ROLLBACK`，在短事务内取得 TSO，结束事务后才设置 `tidb_snapshot`。
- 拒绝 `0`、负数、空值、非数字及 uint64 溢出；设置后读回确认值一致、无活动事务且 autocommit 开启。全部成功才公布 `snapshotTSO`。
- 基础表预检及每条引擎路径，在 EXPLAIN、EXPLAIN ANALYZE、结果读取前后核验同一个正 TSO。获取、设置、读回或路径中校验失败记为 `SNAPSHOT_ERROR`，不能 PASS。
- 比较器另行拒绝成功路径的无效/不同 TSO；没有共同快照的结果不计算差异对。其他有效同快照路径发现的差异仍保留，路径完成情况独立记录。
- 获取失败尝试回滚自身事务、清空已尝试设置的快照；不覆盖调用前已有事务。日志增加 `SNAPSHOT_ACQUIRE`、`SNAPSHOT_CHECK`，保存请求值、读回值、验证状态及失败原因。
- 只读数据观测也采用相同流程；已有 `tidb_snapshot=0` 不能复用为有效快照，已有正快照须先核验。

重新核对旧 20 分钟日志：32 个完整用例中 18 个记录为 PASS，33 个进入差分的 revision 全部为 TSO `0`。
这些旧 PASS **不能作为严格共同快照差分通过的证据**；原始日志和压缩包保留，不改写历史输出。

官方语义及事务内示例见 [TIDB_CURRENT_TSO 文档](https://docs.pingcap.com/tidb/stable/tidb-functions/#tidb_current_tso)。

验证：新增 7 项回归；完整 verify 共 197 项，188 项通过、9 项真实集群测试默认跳过，构建及静态检查通过。
另在目标集群开启 4 项真实测试，全部通过。共同快照 `470548885236613124` 建立后，由独立连接把 t0 从 4 行增至 5 行；
双引擎预检仍读到 4 行，四条查询路径均返回原来的 6 行，34 次会话核验全部通过。独立重放取得另一个正 TSO，也通过。
生产 Oracle 两用例冒烟正常退出：1 PASS、1 INCONCLUSIVE（Warning），两个 revision 均为正 TSO，未调用模型。
这些是本轮受控验证及两用例短测，不替代旧 20 分钟实验；逐项数据见 [快照验证记录](mixed-snapshot-validation.json)。

## 追加：外连接观测目录确定化（2026-11-18）

根因：同一 AST 重建关系 SQL 时，随机出现 `LEFT JOIN` / `LEFT OUTER JOIN`（RIGHT 同理），使目录 `description` 变化，完整 JSON 校验误拒合法候选。

- 增加 `TiDBVisitor.asStringDeterministic()`：固定输出 `LEFT JOIN` / `RIGHT JOIN`，嵌套 JOIN 同样处理，渲染不消耗随机数。
- 混合 Oracle 的冻结候选、状态描述、静态/参数化探测、查询阶段统计统一使用该入口，目录描述与实际编译 SQL 使用一致的关系文本。
- `PreparationHarness` 仍完整比较 query 和 available_observations；SQL、能力描述、参数签名真正变化时仍拒绝。能力身份和签名继续参与校验。
- 默认 visitor 继续保留 SQLancer 的随机可选语法渲染。

固定种子 42 的受控复现中，LEFT/RIGHT 各重建 100 次：修复前目录各 54 次不一致，Harness 内部另行重建时各 40 次误拒；修复后两项均为 0。
这些是此复现实验的计数，不是所有查询的失败率。旧日志不改写。
新增回归还覆盖三表嵌套 LEFT/RIGHT 的目录与绑定探测 SQL 重建 100 次、渲染不推进随机流、真实目录篡改拒绝，
以及两种外连接的 `observe → append_rows → stop` 闭环：实测匹配数从 0 变为 1 后才判定目标达成。

完整 verify 共 203 项：193 项通过、10 项真实集群测试按开关跳过；formatter/XML/Checkstyle/PMD/SpotBugs（0 bugs）、命名及差异空白检查通过。
另开启新增的 1 项真实 TiDB 测试，LEFT/RIGHT 两种闭环均通过，实际匹配数均为 `0 → 1 → 1`，完整目录始终一致。
模型响应为模拟数据，本轮没有调用真实 DeepSeek，也未重跑 20 分钟实验。
逐项计数及闭环结果见 [目录稳定性验证](mixed-catalog-validation.json)，完整证据和构建日志保存在本机 `/home/lwm/Mix-catalog-validation-20261118/`。

## 追加：生产入口独立写前覆盖基线（2026-11-18）

修复精简 bootstrap 被当作 `dataCoverageBefore`、缺少查询阶段写前证据的问题：

- 在第一次模型调用及补行前，独立采集写前评估，立即保存 `dataCoverageBefore`；准备结束后按最终数据版本独立复测，再计算 `dataCoverageChange`。
- 模型仍只接收行数、两行样本和 query_empty，不接收独立评估结果；后续主动观测继续由模型决定。
- 新增 `prePreparationAssessment` / `postPreparationAssessment`：分别保存请求、事实、SQL 证据、快照、版本、探测状态及预算/耗时。各阶段限额为 64 次探测、单次结果 64 行、10 秒，不扣准备预算。
- 前后优先测量同一组静态覆盖能力；查询阶段和基础数据仍分开报告。未知、样本、失败或仅写后才测到的场景不能冒充新增覆盖。
- 模型不可用时仍前后测；写入提交不确定则保留写前证据并要求重建，不产生写后覆盖或收益结论。会话异常继续向外抛出。

旧实验缺少的写前事实不能事后补造，本次不改写旧统计，也不据此宣称 AI 覆盖率已提高。

新增 5 项回归，完整 verify 共 209 项：198 项通过、11 项真实集群测试按开关跳过；构建、formatter/XML/Checkstyle/PMD/SpotBugs（0 bugs）、命名和差异空白检查通过。
另开启新增的 1 项真实 TiDB 测试并通过：前后各精确测量 19 个查询阶段覆盖项，WHERE 后匹配数 `0 → 1`，新增 `j0_pairs_after` 和 `where_predicate_true`，`complete=true`。
模型首轮仍只有 5 项 bootstrap 观测，独立评估前后各执行 42 次探测，耗时 784 / 711 毫秒；两者独立记录，不扣准备预算。
缺凭据、无写入的回归中前后也可完整比较，新增/丢失集合均为空；前测不足及提交不确定的反例不能冒充有效收益。
模型响应为模拟数据，未调用真实 DeepSeek，未重跑长测。逐项证据见 [写前基线验证](mixed-assessment-validation.json)。
完整测试数据位于项目 `target/mixed-assessment/`，运行日志位于 `logs/coverage-baseline/`，不再散放用户主目录。

## 追加：证据 JSON 无损保存及失败兜底（2026-11-18）

修复前已用孤立 UTF-16 代理项复现 `UnmappableCharacterException`。UTF-8 编码保持不变，调整序列化及保存边界：

- `TiDBMixedSupport.save()` 统一转交 `TiDBMixedEvidence`：JSON 中的孤立代理项转为 `\uXXXX`；正常中文、成对代理项（如 emoji）保留。读取 JSON 后，SQL、列名、结果、异常及复杂 Map 键中的原始 UTF-16 码元逐一相同，不替换成 `?` 或 `�`。
- 先写临时文件，再替换目标；失败清理临时文件，不用错误记录覆盖原有 `case.json`。
- 保存失败尽力写入 `<文件名>.save-error.json`，记录 `EVIDENCE_SAVE_ERROR`、失败阶段、原状态、SQL、快照及异常。若完整 JSON 已生成，则先尝试另存 `<文件名>.recovery-<UUID>.json`，错误记录给出其文件名；目录完全不可写时，把最小记录输出到进程 stderr。
- 生产 Oracle 分别尝试保存各份证据，最后保存 `case.json`；保存异常不再从 `finally` 逃出终止工作线程。非差异用例保存失败时不能 PASS，记录 `evidenceSaveErrors` 并结束本轮；已经发现的差异保留原分类，仍走缺陷报告出口。
- 确认/重放前必须成功保存原始证据，失败则停止确认；在保存前标记已发现的差异，避免保存错误覆盖它。

新增 8 项回归：全部 65,536 个 UTF-16 码元往返、中文/emoji/字面转义、重复替换、序列化失败、目标不可替换、目录不可写，以及生产保存边界的状态、后续证据和下一用例保存。测试不调用模型或真实数据库，也未重跑查询长测。
完整混合 Oracle verify 共 217 项：206 项通过、11 项真实集群测试按开关跳过；构建、formatter/XML/Checkstyle/PMD/SpotBugs（0 bugs）、命名、本地文档链接及差异空白检查通过。
验证日志保存在项目 `logs/evidence-save/`，其中 `before.log` 保留修复前失败证据。

## 追加：只读观测兼容空列标签（2026-11-18）

修复前已复现 `getColumnLabel()` 返回 null 时的 NPE，现作两处调整：

- 普通结果的标签为 null、空字符串或纯空白时，按列位置使用 `column_1`、`column_2` 等标识；其他标签继续按 `Locale.ROOT` 转小写。行数据仍按位置读取，精度、类型及样本截断标记保留。
- `query_empty` 使用专用 `isEmpty()`：对原查询结果只调用一次 `next()`，不读取列元数据、类型或单元格。查询有一行即判非空，聚合及 HAVING 仍按原查询的实际输出判断。
- 两种路径共用 SELECT 校验、参数绑定、流式读取、超时、行数上限、Warning 检查及资源关闭；SQL 失败或 Warning 仍记录观测错误，不能判为空或精确成功。观测结束后继续恢复原快照和引擎设置。

新增 7 项回归，使用实际 H2 SELECT 与 JDBC 元数据代理：覆盖 null/空/空白标签、标识稳定、精确值及截断、空与非空查询完全不解码、NaN、聚合/HAVING、错误及 Warning。未调用真实模型或真实 TiDB，也未重跑长测。
完整混合 Oracle verify 共 224 项：213 项通过、11 项真实集群测试按开关跳过；构建、formatter/XML/Checkstyle/PMD/SpotBugs（0 bugs）、命名、本地文档链接及差异空白检查通过。
日志位于项目 `logs/observation-labels/`，`before.log` 保留修复前复现。

## 追加：动作接口、修订预算和计划语义（2026-11-18）

1. **接口分成三个函数。** 默认向 DeepSeek Beta 提交动态 strict Schema，`observe`、`append_rows`、`stop` 各有自己的字段。根据真实可写列和观测目录限定类型、枚举及目标字段；生成列不能写，SQL 不能提交。程序继续做预算、版本、权限和数据库约束校验。
2. **反馈定位错误字段。** 新增 `path/code/expected/received_type/allowed_values/example`。固定上一轮 27 份真实响应作回归：5 份保持合规，22 份均能定位；人工格式修订后 27 份均通过。此结论不等于模型修订或补行成功。
3. **修订和决策分开计数。** 默认有效决策 3、格式修订 3、总 HTTP 调用 6；120 秒、token 和估算费用仍共同限额。失败反馈不再轻易耗尽后续观察、补行和再决策机会。程序补行后自动验证，不消耗额外模型调用。
4. **计划说明可达性。** 补行要求五项简短依据：完整连接键、WHERE、全部 GROUP BY 键、实际约束、验证观测。文字不能当覆盖。程序保守推导完整非 NULL 唯一键的分组上界，只拒绝能够证明不可达的结构化组大小目标；其他场景仍实测。
5. **对照保持同一输入。** Benchmark 在准备前核对 SQL、实际元数据及数据指纹；新增独立 AI_THINKING 组，分别汇总协议通过率、目标达成率、基础数据/查询阶段覆盖、引擎覆盖、耗时及配置单价估算费用。

接口、完整交互示例和限制见 [动作接口 v2](data-preparation-v2/README.md)。
三组真实目录的 Schema 已通过 Beta API HTTP 200 兼容性检查；这只证明接口接受结构，效果需看后续数据库测量。
验证日志统一放在项目 `logs/action-interface/`，不会新建用户主目录下的实验目录。

完整 verify 共 238 项：225 项通过、13 项真实集群测试按开关跳过；formatter、XML、Checkstyle、PMD、SpotBugs（0 bugs）全部通过。新增回归还验证两次格式错误后仍能执行 observe → append_rows → stop、最后一次有效决策后的自动验证、请求前 token 预留、实际 usage 扣费及缺失 usage 后停止调用。

真实 TiDB `b1d4142f99` 的两项回归均通过：seed 49 的最小用例有 4 对匹配，但分为 4 个单行组，HAVING COUNT=4 输出 0 行；seed 51 的首字符猜测仍为 0 匹配，两侧分别追加完整文本 `"1"` 和二进制 `MQ==` 后，两行写入成功、实测匹配为 1。这里是人工固定计划，不计作真实模型的策略成功。计数见 [验证数据](data-preparation-v2/validation.json)。

### 实测发现后的补修

- 对照预跑发现多轮证据反复进入历史，状态包超过 128000 字符并中断汇总。现只向模型发送计划、失败、写入及目标评价摘要，完整观测、SQL 审计和覆盖快照仍保留在 rounds；新增六轮大样本回归，完整证据超过上限时，每轮请求仍在上限内。
- v2 的所有观测请求统一为 capability_id/parameters 对象；静态能力使用 null parameters（Beta 不接受无属性对象），程序再转为原内部 ID。旧 v1 JSON 接口及 27 份回归样例保持兼容。错误参数仍明确拒绝，不做含糊的静默转换。
- 按实际反馈补充未知枚举的字段路径和合法值；分组上界检查允许合法的 at_most 目标。新增可配置输出 token 上限，审计记录 max_tokens。
- Beta HTTP 200 不等于返回参数一定合规：预跑实际出现过不合规返回，继续由本地校验拒绝。预跑中的状态包错误和 4000 token thinking 截断不计作最终效果数据；修复后重新对照。

补修后的完整 verify 共 241 项：228 项通过、13 项真实测试按开关跳过；静态检查全部通过。新增实际 API 兼容性检查确认 Beta 拒绝无属性对象（400），静态观测 parameters 改用 null 后返回 200。协议与示例均已同步，原始不合规返回继续作为回归证据保存。

### 真实模型对照与一小时随机查询测试

- 六个固定用例分别运行 RANDOM、SHARED、AI、AI_THINKING，24 组初始 SQL、实际元数据及数据指纹全部一致，每组追加上限 12 行。查询场景新增命中分别为 **3、16、24、15**；成功追加行分别为 72、72、33、29。按用例重复计数，不是不同场景种类。AI 四份实际执行的补行计划中只有一份全部目标达成，不能用写入成功替代目标成功。
- 两个 AI 组使用相同的 8000 输出 token 上限。关闭 thinking 为 18/19 次调用获得合规响应；开启为 7/12，包含四次截断。本次小样本未显示 thinking 优势，不能推广为普遍结论。费用、耗时、逐项目标及引擎路径另列，见 [完整对照](data-preparation-v2/benchmark-results.md)。
- 新增独立写前/写后评估的用例级追加行分类，以及“合规响应 / 全部 API 调用”指标。评估不足仍标未知；有增量用例中的每一行不能据此都算有效。保留原逐轮统计，并增加回归。
- 最后完成 **3600 秒启动窗口 + 175.206 秒自然收尾** 的真实 DeepSeek 随机查询测试：27 个种子、26 份完整用例、72 次 API 调用、70 份合规响应；两份未知证据 ID 拒绝均得到字段反馈并在下一次修订通过。六份补行计划执行，25 行成功。测得 11 次查询场景新增，其中引擎路径全部有效的为 9 次；一例只有局部精确统计，整体评估仍不完整。
- 一小时测试中 7 个 PASS、13 个 INCONCLUSIVE、3 个执行错误、2 个无效配置、1 个生成拒绝。23 个差分 revision 均为正 TSO 且各路径一致，106 对有效结果全部相同；没有发现差异。不能把其余状态算作通过，也不能用随机测试替代同输入效果对照。21 次准备耗尽预算、观察占多数，仍是实际限制。
- seed 58 在 Oracle 之前因分区唯一索引要求 GLOBAL 的新提示退出，保留完整控制台日志。已加入建表预期错误，并用相同 seed 在真实 TiDB 上单独验证建表退出码为 0；没有中途替换一小时被测 jar。完整用例没有保存失败。

逐项结果见 [一小时记录](data-preparation-v2/query-1h-results.md)，实际模型的拒绝、修订、写入及未达目标见 [交互证据](data-preparation-v2/live-loop-example.json)。
最终混合 Oracle 测试及 verify 检查共 **242 项：229 项通过、13 项真实测试按开关跳过，0 失败/错误**；formatter、XML、Checkstyle、PMD、SpotBugs 全部通过。
日志保存在项目 `logs/action-interface/`、`logs/paired-v2-20261118T150234Z/` 和 `logs/query-1h-deepseek-20261118T150233Z/`。
