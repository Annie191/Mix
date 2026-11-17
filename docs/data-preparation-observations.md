# 实际元数据与只读观测（第二环节）

新增 [TiDBMixedMetadata](../src/sqlancer/tidb/mixed/TiDBMixedMetadata.java) 和
[TiDBMixedObservations](../src/sqlancer/tidb/mixed/TiDBMixedObservations.java)。
现有 Oracle 在初始填充之后、导出数据和正式差分之前，采集实际元数据及数据事实，写入每个 attempt。
本环节没有调用新的 AI 决策，也没有实现 AI 补行执行或反馈循环。

## 实际元数据

不再只用 SQLancer 的简化类型推断约束，读取以下实际信息：

| 来源 | 保存内容 |
|---|---|
| `information_schema.columns` | 原始 `COLUMN_TYPE`、可空性、默认值、生成表达式、Extra，以及精度/长度/排序规则原始记录 |
| `information_schema.statistics` | 主键和唯一索引的有序列组合；每个 key_part 的列、表达式、前缀长度 |
| `information_schema.tidb_check_constraints` | 按当前数据库和表限定的 CHECK 名称与表达式 |
| `SHOW CREATE TABLE` | 完整 DDL；与 CHECK 名称核对，并识别 `NOT ENFORCED` |

`sql_type` 保留 `bigint unsigned`、`decimal(38,2)` 等原始声明。
协议兼容字段 `default_expression` 保存 COLUMN_DEFAULT 的原始文本，**不是可执行表达式**。
`DEFAULT NULL` 与未声明默认值可能在该字段中同为 null，区别保留在完整 DDL 中。
空字符串及空白默认值保留原值，不能按“缺失字段”拒绝。
不能仅据此将默认值拼成 SQL。生成列同时保存 generated 和 generation_expression；
`DEFAULT_GENERATED` 不当成虚拟/存储生成列。

复合唯一键 `UNIQUE(a,b)` 保留为一个有序组合，不能推导出 a、b 各自唯一。
前缀索引和表达式索引还须查看 `key_parts`，不能只使用 columns。
该 TiDB 提交的 TABLE_CONSTRAINTS 不提供 ENFORCED，因此不读取不存在的字段。

读取失败、CHECK 与 DDL 不一致、索引顺序无效、存在当前尚未结构化的外键等，
均保留原始证据及 errors，并将 `metadata_complete` / `schema.complete` 置为 false。
不能把采集失败解释成“没有约束”。这不是完整的约束求解器。

[TiDBSchema.TiDBColumn](../src/sqlancer/tidb/TiDBSchema.java) 同时保留原始类型、默认值和 Extra，
提供 isGenerated()/isAutoIncrement()；引导连接列选择排除虚拟及存储生成列。

## 观测语义

| 指标 | 精确语义与边界 |
|---|---|
| table_row_count | `COUNT(*)` 实际行数，包含重复行，不使用估算 TABLE_ROWS |
| null_count | 指定列为 NULL 的行数 |
| key_frequency | 单列值及其频次，包含 NULL；仅完整读完时是完整分布 |
| table_sample | 带类型的表样本；截断时 complete=false |
| join_pairs_before_where | 两张基础表在单个 `a.k=b.k` 下的匹配行对数，保留输入重复次数 |
| left/right_unmatched_input_rows | 每侧没有任何 ON 匹配的输入行数；普通等号下 NULL 键也未匹配 |
| left/right_input_rows_with_multiple_matches | 每侧能够匹配对侧至少两行的输入行数 |
| join_pairs_after_where | 真正匹配的行对经过原 WHERE 后的数量，排除外连接补出的 NULL 行 |
| duplicate_keys_after_where | 原 WHERE 后，按左侧连接列 GROUP BY，至少有两对匹配的非 NULL 键组数量 |
| left/right_unmatched_output_rows_after_where | 原外连接保留侧的未匹配输出经过 WHERE 后的行数；未保留侧为 0 |
| query_input_rows_before/after_where | 原 FROM/JOIN 在 WHERE 前/后、GROUP BY 前的输出行数，包含外连接补行 |
| query_result_empty | 冻结的原始 SELECT 是否输出至少一行，保留投影、聚合及 HAVING；不推断其输入行数 |
| group_sizes_before_having | 显式列 GROUP BY 的分组键及每组输入行数；列表可截断 |
| group_summary_before/after_having | 分组总数、这些组包含的输入行数、最小/最大组大小；HAVING 前后分别计算 |

简单 JOIN 指两个不同基础表之间、单个列等号；支持 INNER/STRAIGHT/LEFT/RIGHT 及带该 ON 的 CROSS。
NATURAL、`<=>`、复合/非等值条件、嵌套 JOIN 本身不猜测匹配情况，记录 unsupported。
嵌套结构中独立满足条件的基础表 JOIN 仍可给出局部 ON 统计。
局部统计不能声称是整个查询的覆盖。
键分组遵循该列的数据库 GROUP BY/排序规则语义，不推导跨类型隐式转换后的全局键等价类。

JOIN 的 WHERE 后统计仅在该 JOIN 就是整个两表 FROM 时支持，避免把引用第三张表的条件
强行套到局部 JOIN。原查询整体的 WHERE 前后输入计数仍单独尝试执行。
分组统计先支持显式列 GROUP BY；表达式分组、无 GROUP BY 的隐式聚合标为 unsupported/unverified。
无 GROUP BY 的聚合查询仍可测量原查询是否为空及聚合前输入行数。

例如 `SELECT COUNT(*) ... WHERE FALSE` 可以输出一行，但聚合前输入行数是 0。
LEFT JOIN 输出 6 行也可以只有 4 对真正匹配。两个值分别保存，不相互代替。

## 查询阶段能力补充

当前已增加 [基础数据与查询阶段覆盖](data-preparation-query-stage-coverage.md)：
整数过滤边界分别计数基础表、原 FROM/JOIN 输出和完整 WHERE 输出；
NULL 分别测量查询输入/输出、WHERE 三值结果、聚合参数及分组输入。
表中的值或局部 ON 匹配不直接算整条查询的覆盖。程序生成 SQL 并沿用下述快照、状态与预算。

## 状态、快照与预算

每条观测包含 `id, capability_id, query_id, schema_revision, data_revision, status, value, error`。
状态沿用 [v1 协议](data-preparation-protocol.md)：

- `exact`：完整标量/摘要，或完整读完的列表；空结果判定也可精确，不声称完整结果行数。
- `sampled`：返回行数受到限制的样本或频率/分组列表，complete=false。
- `unsupported`：当前算法不支持该语义；value=null，说明原因，不返回伪造的 0。
- `unknown`：探测次数/时间预算不足，尚未测量；value=null。
- `error`：SQL 错误、Warning 或不能解码的结果；value=null，错误码及 SQLSTATE 保存在 audit。

所有 SQL 由程序从 Schema/AST 构造；模型只能选择 available() 中的能力 ID。
available() 排除静态不支持的能力；可执行能力仍可能因运行错误或预算不足而没有有效值。
allIds() 同时包含支持和不支持的项目，便于完整记录已知边界。

一次 collect() 在同一 TiKV TSO 下只读执行，已有快照时复用；完成或失败后恢复原快照和引擎设置。
审计记录保留指标含义、目标、实际 SQL、TSO 和观测 ID，可在相同快照/数据上核对。
探测使用流式读取，最多保存 rows 行，多读一行判断是否截断；设置数据库执行超时。
SQL 数值和计数使用字符串，二进制使用 Base64，NULL 明确编码。

默认 Oracle 每轮最多 64 次探测、每个列表 64 行、10 秒总探测时间，单次至多 30 秒且不超过
本轮剩余整秒。采集顺序优先查询/关系统计，再补充分布；预算不足的项目记录 unknown。
预算仅限制观测，不替模型决定补行策略；元数据读取沿用现有读取超时。

## 接口与保存位置

```java
TiDBMixedMetadata metadata = TiDBMixedMetadata.collect(io, database, candidate.tables, schemaRevision);
TiDBMixedObservations facts = new TiDBMixedObservations(candidate, queryId, schemaRevision, dataRevision);
facts.collect(io, facts.allIds(), new TiDBMixedObservations.Budget(64, 64, 10_000));
JsonObject packet = facts.statePacket(requestId, caseId, metadata.schema, history, remainingBudget);
```

statePacket() 生成并校验第一环节的完整状态包，保留查询描述、实际约束、带版本观测、样本和历史。
remainingBudget 是下一次模型决策的预算，由后续调度器维护；collect() 不扣减这个外部预算。
生成的状态包超过协议大小限制时拒绝，后续调度须缩小输入，不能默默截断后声称完整。

`case.json.attempts[]` 新增：

- `actualMetadata`、`metadataEvidence`：结构化实际元数据和原始证据/失败。
- `observations`、`samples`：带版本事实。
- `observationEvidence`、`observationSnapshot`：核对 SQL、含义与快照。
- `availableObservations`：当前支持的能力目录。
- `observationStatus` / `observationError`：采集批次状态；COLLECTED 不代表每项均精确或查询 PASS。

初始填充后的数据在本 revision 内记为 data_revision=0；未来追加成功后由调度器递增。
本次采集快照与后续正式差分快照用途不同，采集完成即恢复；正式比较仍等待副本、获取共同快照、
执行基础表预检。观测失败不当成查询差异；会话初始化/恢复失败则终止本轮，避免污染后续执行。
JSON 落盘保留 null，避免丢失协议必需的空字段。

测试及真实目标 TiDB 验证见 [验证记录](mixed-storage-validation.md#实际元数据与只读观测回归第二环节)。

字段参考：[COLUMNS](https://docs.pingcap.com/tidb/stable/information-schema-columns/)、
[STATISTICS](https://docs.pingcap.com/tidb/stable/information-schema-statistics/)、
[CHECK](https://docs.pingcap.com/tidb/stable/information-schema-check-constraints/)。
具体版本兼容性同时按本机目标提交源码和真实 TiDB 测试核对。
