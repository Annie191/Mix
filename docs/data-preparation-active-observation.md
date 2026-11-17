# 主动观测与参数化只读能力

模型仍只能 `observe / append_rows / stop`，不能输出执行 SQL 或修改查询。
现在由程序从当前 AST 和 Schema 生成参数化目录；模型选择目录项及受约束的值，程序校验、绑定 JDBC 参数后执行 SELECT。

## 初始信息与独立评价

开启 AI 数据准备后，默认初始只执行：

- 每表 `rows_<表>`。
- 每表 `sample_<表>`，最多两行；截断明确标记为样本。
- 原查询 `query_empty`。

两表共 5 个请求；初始探测从 96 次准备探测预算中扣除，模型收到剩余 91 次。初始观测总时间至多 10 秒且不超过剩余准备时间，实际耗时扣预算，不消耗模型调用额度。
不预先探测全部 JOIN、过滤或分组情况。已有足够信息时也允许直接补行或停止，不强制模型选择 observe。
未开启 AI 数据准备的旧 Oracle 观测行为保留。

Benchmark 仍保存独立完整前测 `initial_state`，但传给模型的是另行采集的 `model_initial_state`，不把完整前测泄露给模型。
各策略都执行相同的初始采集并扣费，准备耗时也包含这一步。完整前后评价仍在独立有界预算内。
最终评价重新携带模型已接受目标的参数，优先测量这些目标再测静态目录；预算不足的其他项目记录 unknown，不能据此声称完整覆盖。
原始六用例真实实验保持不变，本改动不能追溯证明原 9 次真实调用会选择 observe。

## 请求和能力目录

原静态请求继续使用字符串，例如 `"j0_pairs_after"`。参数化请求使用：

```json
{
  "capability_id": "key_rows_t0.k",
  "parameters": {
    "key": {"type":"integer","value":"12"},
    "stage": "where_input"
  }
}
```

`observation_requests` 和 `verification_requests` 可以混用两种形式。
只有目录中带 `parameters` 的项接受参数，参数名称必须完整匹配，其他字段和任意 target/SQL 均拒绝。
目录示例：

```json
{
  "id":"key_rows_t0.k",
  "metric":"key_scoped_rows",
  "target":{},
  "description":"指定键在基础表或原查询阶段中的精确行数；包含外连接补行",
  "parameters":{
    "key":{"kind":"integer","nullable":true},
    "stage":{"kind":"enum","values":["base","where_input","where_output"]}
  },
  "goal_fields":{"count":"non_negative_integer"}
}
```

目录 ID 和闭包绑定的 AST 决定查询对象；空 target 不授予模型自行补充 target 的权限。
`kind=enum` 使用给定字符串；其他 kind 使用已有带类型值。NULL 使用 `{"type":"null","value":null}`，整数和 DECIMAL 用字符串保证精度。
NULL 参数编译为 IS NULL，非空值作为 `?` 绑定；含 SQL 外观的文本仍是普通数据。

| 目录项 | 参数与精确语义 |
|---|---|
| `key_rows_<表>.<列>` | key、stage=base/where_input/where_output；分别计数基础表、完整原 FROM/JOIN 输出、完整原 WHERE 后该键的行数；不把外连接补行当成 ON 匹配 |
| `key_matches_j<N>_<表>.<列>` | key、stage=local_pairs/query_input/where_output；使用原 ON=TRUE，不限单个等号；local_pairs 为两侧子关系的匹配对，后两者为完整原查询阶段的行数，保留下游重复次数 |
| `predicate_where` 及 `_l/_r/_u` 路径 | stage=input/output；选择原 WHERE 或逻辑子树，返回 true_count/false_count/null_count；input 在原 FROM/JOIN 输出上求值，output 还要求通过完整原 WHERE |
| `predicate_j<N>_on` 及路径 | 仅 stage=input；输入为该 JOIN 的左右子关系组合，在应用该 ON 之前；嵌套子 JOIN 的条件仍保留。局部目录不接受 output，避免误当成 ON 后输出 |
| `predicate_j<N>_on_query` 及路径 | 指定 ON 在完整原 FROM/JOIN 输出或完整 WHERE 后的分布，用于区分局部匹配与整条查询的可达性 |
| `group_input_rows` | key0/key1/... 必须覆盖全部显式列 GROUP BY 键；计数完整原 WHERE 后、HAVING 前该分组的输入行数，包括 NULL 键，不拿聚合结果行数代替 |

路径由程序按 AST 编译：二元逻辑节点左右为 `_l/_r`，前缀节点子表达式为 `_u`。
表达式分组等尚不能可靠编译的模板不出现在目录中；不会允许模型提供 SQL 绕过限制。
所有模板仍受探测次数、时间、超时和结果行数限制。局部笛卡尔诊断可能开销较大；超时/警告仍为 error，不回退估算。
这些能力描述逻辑关系，不证明物理求值顺序。参数化键诊断也不会按“多试几个值”直接增加 [查询阶段覆盖分数](data-preparation-query-stage-coverage.md)。

## 参数化目标

结构化目标沿用原四字段，参数化目录项另外必须带相同的 parameters：

```json
{
  "capability_id":"key_rows_t0.k",
  "parameters":{"key":{"type":"integer","value":"12"},"stage":"where_output"},
  "field":"count",
  "comparison":"at_least",
  "expected":"1"
}
```

谓词分布的 field 可选 true_count、false_count、null_count。
目标是否合法和是否达成仍由程序判断。只使用相同能力、规范化参数、query/schema/data 版本的 exact 证据；其他键或阶段不能替代。
补行后的自动验证合并已接受目标、显式请求和静态审计，按能力＋参数去重并扣预算。
`allIds()` 不枚举参数空间，静态最低审计也不会把没有参数的模板 ID 当成可执行请求。

## 证据保留与失效

同一 query/schema/data 版本没有写入时，局部 observe 只替换被请求的“能力＋参数”证据，其他当前观测和样本保留，原证据 ID 不改。
参数名顺序和 DECIMAL 尾零不产生不同请求；不同键、阶段各自保留。刷新失败或超时也替换该请求的旧成功，不能靠旧 exact 继续证明目标。
请求表样本时，即使新请求失败也撤销该表的旧样本，其他表的样本保留。
全部 INSERT 确定失败且数据版本未变时同样保留未刷新的事实；发生成功写入或提交不确定时，旧事实全部失效，提交不确定仍立即终止。
此处依赖 SQLancer 对测试数据的版本管理，不把另一个进程未经记录的写入视为同一逻辑数据版本。

## 受控闭环实测

用例：`t0 INNER JOIN t1 ON t0.k=t1.k WHERE t0.k>10`，初始两表各有 k=1。
以下为模拟模型响应和实际 H2 SQL，不是真实 DeepSeek 的策略表现。

1. 初始收到行数、少量样本和查询为空，探测预算从 96 变为 91。
2. `observe` 请求完整 WHERE 的输入分布，以及 key=12 的 WHERE 输出行数。实测 false_count=1，指定键输出=0；预算变为 89，原行数与样本仍保留。
3. `append_rows` 给两侧各补 k=12，目标为上面的参数化 count>=1。两行实际写入，旧事实失效；自动重新测量指定键输出，实测 count=1、目标 achieved；预算变为 88。
4. `stop` 不写入，不把解释或写入成功当证明。累计目标与实测证据保留。

第一次 observe 的完整响应形状如下，ID 必须按真实状态回显：

```json
{
  "protocol_version":"1.0",
  "request_id":"active_round_0",
  "case_id":"active_observation",
  "query_id":"active_query",
  "schema_revision":0,
  "data_revision":0,
  "decision":"observe",
  "reason":"初始查询为空，需确认连接输出是否被 WHERE 排除",
  "evidence_ids":["schema_0"],
  "goal":"先定位阻塞条件再决定补行",
  "structured_goals":[],
  "observation_requests":[
    {"capability_id":"predicate_where","parameters":{"stage":"input"}},
    {"capability_id":"key_rows_t0.k","parameters":{"key":{"type":"integer","value":"12"},"stage":"where_output"}}
  ],
  "operations":[],
  "expected_effect":"观测结果待实测，暂不宣称覆盖成功",
  "verification_requests":[],
  "verification_limit":null,
  "stop_reason":null
}
```

逐轮实际计划、写入、观测与判定见 [实测记录](data-preparation-loop/active-observation-results.json)。
新增回归还覆盖非等值/复合 ON、三表局部匹配被后续 JOIN 阻断、NULL/复合分组、精确 DECIMAL、SQL 外观文本、非法参数/重复请求/超预算、缓存保留/替换/版本失效及最终独立复测。
汇总增加 observe_plans_proposed/executed、observe_probes、parameterized_probes；probe 数按请求审计计数，unknown/error 仍保留，不能理解为成功观测数。

实现见 [请求与参数](../src/sqlancer/tidb/mixed/TiDBMixedObservationRequest.java)、
[AST 模板](../src/sqlancer/tidb/mixed/TiDBMixedParameterizedObservations.java)、
[闭环](../src/sqlancer/tidb/mixed/TiDBMixedPreparationHarness.java)、
[回归](../test/sqlancer/tidb/mixed/MixedActiveObservationTest.java)。
