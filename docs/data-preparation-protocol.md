# AI 数据准备协议与提示词 v1.0

第一环节交付：协议、提示词、可执行的格式校验和完整交互示例。
当前已增加 [AI 结构化目标与程序验证](data-preparation-structured-goals.md)，生产闭环要求 structured_goals。
当前运行入口和真实对照结果见 [第五环节](data-preparation-benchmark.md)。以下按交付顺序列出各阶段：
第二环节已增加 [实际元数据与只读观测](data-preparation-observations.md)，并接入 Oracle 的初始数据填充后采集。
第三环节增加 [可控追加行执行器](data-preparation-append-executor.md)，当时使用人工 JSON 计划验证。
第四环节增加 [提示词与闭环验证](data-preparation-loop.md)，用模拟响应验证完整实验流程和独立目标判定。
第五环节已增加 [真实 DeepSeek 与配对实验](data-preparation-benchmark.md)；设置 MS_DATA_PREPARATION=DEEPSEEK 可接入 Oracle 补行，其数据目标评价与差分缺陷判定独立。
示例中的约束、观测和写入反馈是说明协议的人工 fixture，不是真实集群实验结果。

## 目的与职责

让 DeepSeek 根据实际状态决定是否值得补数据，而不是固定追求非空、匹配或一对多。
模型决定目标、表、列、值、行数及何时停止；程序检查权限、预算和结构，执行并测量；
SQLancer 生成初始随机数据及补行中未指定的可写列；最终差异由 Oracle 独立判断。

只允许三种决策：

| 决策 | 含义 | 操作要求 |
|---|---|---|
| `observe` | 请求当前状态包公布的只读观测 | 请求非空；补行及验证请求为空 |
| `append_rows` | 提出追加行候选计划 | 补行非空；说明预期和验证需求；不同时执行观测请求 |
| `stop` | 结束数据准备 | 所有操作为空；明确停止理由及尚未验证事项 |

模型不能输出执行 SQL、代码、查询或 Schema 修改、UPDATE/DELETE、引擎变更、PASS 或缺陷判定字段。
`reason`、`expected_effect` 等文字仅为建议，不是执行事实或 Oracle 判定。
列值中的 SQL 外观文本仍是普通数据，不能当作指令或 SQL 执行。

## 提示词与消息

完整提示词已随 JAR 打包，版本与协议同步：

- [总任务提示词](../src/sqlancer/tidb/mixed/protocol/overall-v1.txt)：测试目的、多样性、事实与预期、权限和冻结边界。
- [数据子任务提示词](../src/sqlancer/tidb/mixed/protocol/data-preparation-v1.txt)：决策方法、约束、反馈、三种输出结构。

每次请求包含三条消息，不依赖模型记忆：

```text
system = 总任务提示词
system = 数据准备子任务提示词
user   = 当前完整状态包 JSON
```

代码入口 [TiDBDataPreparationProtocol](../src/sqlancer/tidb/mixed/TiDBDataPreparationProtocol.java)：

```java
JsonObject state = TiDBDataPreparationProtocol.state(stateJson);
List<Map<String, String>> messages = TiDBDataPreparationProtocol.messages(stateJson);
JsonObject proposal = TiDBDataPreparationProtocol.decision(modelContentJson, state);
```

`modelContentJson` 指模型返回的决策内容对象，不含 HTTP/Chat Completions 外壳。
上述方法只构造消息、验证结构和已声明的权限，不发送网络请求、不执行提案。

## 每次请求的状态包

所有顶层字段必需，未知顶层字段拒绝。完整实例见 [01-state.json](data-preparation-v1/01-state.json)。

| 字段 | 内容 |
|---|---|
| `protocol_version` | 固定 `1.0` |
| `request_id`、`case_id`、`query_id` | 本次请求、用例、冻结的候选查询标识 |
| `schema_revision`、`data_revision` | 非负整数，限定为 Java long 范围 |
| `phase` | 固定 `DATA_PREPARATION` |
| `frozen`、`discrepancy_found` | 任一为 true 时，只允许 stop |
| `query` | 完整 SQL、带 ID 的 JOIN 关系、WHERE、GROUP BY、HAVING；条件文本不是执行授权 |
| `schema` | 证据 ID、完整性标志、查询涉及表的实际元数据 |
| `observations` | 带 ID、查询/Schema/数据版本、能力 ID、状态、值和错误的观测 |
| `samples` | 带 ID 和版本的表样本、列、带类型的行值，以及是否完整；有限样本不能证明全表事实 |
| `history` | 之前的提案、校验、部分写入结果、实际值、错误和观测 ID |
| `remaining_budget` | 剩余模型调用、探测请求、尝试追加行数和时间 |
| `available_observations` | 本次实际支持的探测目录，每项包含 id、metric、target、description |

`query` 固定包含 `sql, joins, where, group_by, having`。
JOIN 项包含 `id, type, left_tables, right_tables, on`；条件不存在时为 null，分组不存在时为 []。

`schema` 包含 `id, complete, tables`，每表包含 `name, metadata_complete, columns, constraints`。
每列包含 `name, sql_type, value_kind, nullable, generated, generation_expression, auto_increment, default_expression`。
生成表达式、默认表达式不存在时为 null。约束记录主键、复合唯一键、CHECK 等，统一结构为：

```json
{"name":"uk_k","kind":"unique","columns":["k"],"expression":null,"enforced":true}
```

`kind` 为 `primary_key`、`unique` 或 `check`；CHECK 的条件放在 expression，其他情况为 null。
联合键的 columns 保留列顺序。不能完整表达的约束应保留说明并将 metadata_complete 标为 false，
不能默默遗漏后仍声称元数据完整。

缺少元数据时标注 complete=false，不能把“未知约束”当作“没有约束”。实际采集已在第二环节实现。
主键/唯一索引还可带 key_parts，保存表达式及前缀长度；不能把它们当成全列唯一。
协议校验器检查元数据容器、列字段及可写性声明；追加执行器写入前重新核对实际元数据，数据库检查实际约束，不预先求解 CHECK。

观测状态只能为 `exact`、`sampled`、`unknown`、`unsupported`、`error`。
没有结果用 null 表示，不能用 0 伪装；例如精确计数的 value 可为 `{"count":"2"}`。
`available_observations` 的条目绑定具体目标，模型通过 ID 请求，例如 `join_after` 绑定 JOIN j0。
模型不能提供任意查询或更改该 target。实际能力及 ID 见第二环节的 available()；本文人工示例的目录不代表当前实例必然支持。

`history` 每项记录 `decision_id, decision, validation_status, data_revision_before,
data_revision_after, writes, observation_ids, failure_reason, validation_feedback`。
decision 保存通过校验的提案；被拒绝时为 null，原始响应仍保留在 raw_response，失败原因包含在 failure_reason。
validation_status 为 `accepted`、`rejected` 或 `parse_error`；校验通过不等于执行成功。
writes 中每行包含 `table, row_index, status, affected_rows, specified_values, actual_values,
data_revision_after, sqlstate, error_code, error`：status 为 `success`、`failed`、`not_attempted` 或 `unknown`，
实际值不可获取时为 null，失败不能伪装为已写入。指定值和实际值使用同一种带类型表示。
第三环节还返回 attempted_values、SQL 模板、剩余预算、审计事件和 requires_rebuild。
提交不确定时 affected_rows、data_revision_after 为 null，停止补行并要求重建，不能当作普通失败重试。
校验器仅检查 history 是对象数组；真实执行反馈及错误采集由追加执行器提供。

可恢复的计划校验错误不再立即结束循环。`history[].validation_feedback` 保存：

```json
{
  "stage": "plan_validation",
  "code": "APPEND_ROW_BUDGET_EXCEEDED",
  "message": "append exceeds remaining row budget; total across all tables must be <= 6",
  "recoverable": true,
  "retry_allowed": true,
  "request_id": "prepare_round_0",
  "data_revision": 0,
  "execution_started": false,
  "attempted_rows": 0,
  "remaining_budget": {"model_calls": 2, "probe_requests": 96, "insert_rows": 6, "time_ms": 118000},
  "correction": "Use the latest state and remaining budget to submit a new observe, smaller/different append_rows, or stop; rejected operations were not executed"
}
```

这是结构示例，time_ms 按实耗扣除。
字段错误、未知观测/证据 ID、格式错误、标识/版本回显错误和超预算可在剩余调用/时间内重新规划。
所有表的行数合计受 insert_rows 限制；拒绝发生在执行前，不消耗行/探测预算或修改数据版本，但该次调用和耗时照扣。
下一次请求使用新 request_id、当前版本及完整拒绝历史；没有预算则终止，不自动增加额度。
传输错误、重复已失败行的提案、冻结、已发现差异、提交不确定和执行上下文异常不进入这条纠错路径。

## 模型输出契约

三种决策共用以下完整字段，缺失、未知字段、重复 JSON 键、非严格 JSON、过深或超长内容均拒绝：

```json
{
  "protocol_version":"1.0",
  "request_id":"req_2",
  "case_id":"example_case",
  "query_id":"query_1",
  "schema_revision":0,
  "data_revision":0,
  "decision":"append_rows",
  "reason":"obs_17 显示已有匹配，obs_18 显示原 WHERE 后无匹配。",
  "evidence_ids":["schema_0","obs_17","obs_18"],
  "goal":"补充过滤后的重复匹配场景。",
  "structured_goals":[],
  "observation_requests":[],
  "operations":[
    {"table":"t0","rows":[{"k":{"type":"integer","value":"12"}}]},
    {"table":"t1","rows":[{"k":{"type":"integer","value":"12"}},{"k":{"type":"integer","value":"12"}}]}
  ],
  "expected_effect":"预期形成两对过滤后匹配，需重新测量。",
  "verification_requests":["join_after","duplicate_after"],
  "verification_limit":null,
  "stop_reason":null
}
```

上例沿用早期人工能力 ID，`structured_goals=[]` 不宣称目标已达成；当前实际能力的完整响应见 [结构化目标示例](data-preparation-structured-goals.md#完整响应示例)。
底层校验器可回放原 v1.0 响应；生产闭环要求新增 structured_goals 字段并校验目标。

`reason`、`goal`、`expected_effect` 均为非空字符串。`evidence_ids` 只能引用状态包中的
query_id、schema.id、观测或样本 ID；不能凭空造证据。解释是否符合事实仍需后续验证。
`observation_requests`、`verification_requests` 是静态能力 ID 或参数化请求对象的数组；
参数化对象包含 capability_id、parameters，按能力和规范化参数去重，不允许超过探测预算。
参数化目标也须携带相同 parameters；具体类型、范围和完整交互见 [主动观测](data-preparation-active-observation.md)。
observe 必须有请求，verification_limit 为 null；append_rows 不能同时 observe。
append_rows 的验证请求为空时，必须在 verification_limit 中明确说明未验证目标及能力缺口。
stop 的所有操作数组必须为空，stop_reason 非空；其他决策的 stop_reason 必须为 null。

每个补行操作只有 `table, rows`；同一张表只列一次，每行至少指定一列。
表必须在本状态包的查询 Schema 中；列必须存在且非生成列。
值必须与列声明的 value_kind 一致，NULL 只能用于声明可空的列。

| type | value 格式 | 示例 |
|---|---|---|
| integer | 无指数的十进制字符串 | `"9223372036854775807"` |
| decimal | 无指数的十进制字符串 | `"12345678901234567890.01"` |
| text | 字符串 | `"hello"` |
| boolean | JSON 布尔值 | `true` |
| binary | 标准规范 Base64 字符串 | `"AP8="` |
| null | JSON null | `null` |

请求整数和小数值不能使用 JSON number，避免精度丢失；也不接受 SQL 表达式。
空字符串与 NULL 分开。具体范围、精度、长度、唯一键及 CHECK 的数据库校验留到后续执行器。

## 版本、预算和结束语义

响应必须原样回显请求/用例/查询标识及两个版本。旧版本响应一律拒绝。
补行提案不能引用旧版本观测/样本；observe 可以引用旧观测说明为何需要刷新。
每次实际提交且改变数据的写入批次推进 data_revision；部分成功也推进，失败且无改变则不推进。
历史保留旧观测，但后续决策必须使用新版本事实。示例逐行提交，因此三行成功后版本从 0 变为 3。

模型调用预算包括当前请求及重试；探测预算包括失败请求；补行预算按尝试行数计，失败也消耗。
校验器检查当前提案是否超出声明预算，但不扣减预算、不负责真实计时；调度由第四环节实现。
资源不足、模型不可用或解析失败时，后续调度应结束补充，保留实际数据及原因，不自动套固定规则补行。
stop 仅结束数据准备；即使解释声称“已覆盖”，也不能据此将 Oracle 判为 PASS。

进入正式差分前，后续程序须导出最终实际数据、等待 TiFlash 副本、获取共同快照并执行基础表预检。
冻结或发现差异后不允许追加数据。当前协议无删除、更新、改查询或改 Schema 的操作入口。

## 完整交互示例

查询为 `t0.k=t1.k AND t0.k>10`。以下六个文件是完整状态与响应，不是省略式对话：

| 步骤 | 文件 | 发生的事情 |
|---|---|---|
| 1 | [01-state](data-preparation-v1/01-state.json) | 两边样本都含 5，但样本有限；尚无全表计数 |
| 2 | [02-observe](data-preparation-v1/02-observe.json) | 模型请求过滤前/后匹配数及重复键数 |
| 3 | [03-state-after-observe](data-preparation-v1/03-state-after-observe.json) | 返回精确计数 1、0、0，并附上历史和剩余预算 |
| 4 | [04-append-rows](data-preparation-v1/04-append-rows.json) | 模型决定 t0 补一行 12、t1 补两行 12，并请求验证 |
| 5 | [05-state-after-append](data-preparation-v1/05-state-after-append.json) | 三行成功；数据版本更新为 3，精确观测为两对匹配、一个重复键 |
| 6 | [06-stop](data-preparation-v1/06-stop.json) | 模型根据新观测停止，没有宣称差分通过或缺陷成立 |

若部分写入失败，下一状态包必须包含每行的实际写入、失败 SQLSTATE/错误码、实际数据版本和新观测；
由模型决定换方案、请求观测或停止。执行成功、自然语言解释和预期本身都不能替代验证。

本环节测试验证协议格式、权限和示例往返，不证明提示词能让真实模型稳定决策。
真实模型效果、计划执行率、目标达成率及空结果场景分类留待后续环节验证。
