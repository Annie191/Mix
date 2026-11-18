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
