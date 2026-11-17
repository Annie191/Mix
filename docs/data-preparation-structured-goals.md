# AI 选择目标，程序验证目标

原问题：Oracle 没有固定 `Requirement`，自由文本目标无法求值；Benchmark 的固定断言也不能代表 AI 这一轮的全部目标。

现在保留文字 `goal`、`expected_effect`，新增 `structured_goals`。AI 从本次能力目录选择目标，程序在执行前校验，执行后用当前版本的精确观测判定。
`achieved` 只证明列出的结构化目标，不证明自然语言中的额外承诺，更不表示差分测试通过。

## 模型看到什么

状态包的 `available_observations` 每项增加程序生成的 `goal_fields`：

```json
{
  "id": "j0_left_unmatched_after",
  "metric": "left_unmatched_output_rows_after_where",
  "target": {"join_id": "j0"},
  "description": "原 JOIN 保留侧经 WHERE 后的未匹配输出行数",
  "goal_fields": {"count": "non_negative_integer"}
}
```

| 能力 | 可选字段 | 比较 | expected |
|---|---|---|---|
| 行数、NULL、匹配、未匹配、重复键等精确计数 | `count` | `equals` / `at_least` / `at_most` | 非负整数字符串 |
| `query_empty` | `empty` | `equals` | `"true"` / `"false"` |
| `groups_before_having`、`groups_after_having` | `group_count` / `input_rows` / `min_size` / `max_size` | 同精确计数 | 非负整数字符串 |
| 表样本、频率列表、分组明细 | 无，`goal_fields={}` | 不支持目标判定 | 不适用 |

仅公布当前查询实际支持的能力。未知能力、字段、运算符、负数、小数、JSON 数字及重复目标均拒绝，按已有结构化反馈流程允许模型修订。
最多累计 64 个不同目标；大整数使用字符串和 BigInteger 比较，不经过浮点数。
程序重新构造并核对能力目录，模型不能添加能力或输出执行 SQL。

新增 [查询阶段能力](data-preparation-query-stage-coverage.md) 可直接用于结构化目标：整数比较边界分别验证到达 WHERE 输入和通过完整 WHERE，
NULL 分别验证查询输入、WHERE 输出、聚合参数值。其他没有专用能力的过滤边界仍不可验证，必须在 `verification_limit` 写明。
精确 NULL 计数也不能自动证明 NULL 经 JOIN/WHERE 后仍存在。不能用近似指标替代缺失能力。

## 完整响应示例

对于 `t0 LEFT JOIN t1 ON t0.k=t1.k WHERE t0.k>10`，假设当前 t0=[1]、t1=[]，t0.k 唯一、t1.k 可空且不唯一，预算允许追加四行：

```json
{
  "protocol_version": "1.0",
  "request_id": "prepare_round_0",
  "case_id": "structured_example",
  "query_id": "query_example",
  "schema_revision": 0,
  "data_revision": 0,
  "decision": "append_rows",
  "reason": "当前无过滤后的匹配，允许在 t1 重复键并保留 t0 未匹配行",
  "evidence_ids": ["schema_example"],
  "goal": "形成过滤后匹配、重复匹配和一条外连接未匹配输出",
  "structured_goals": [
    {"capability_id":"j0_pairs_after","field":"count","comparison":"at_least","expected":"2"},
    {"capability_id":"j0_duplicate_after","field":"count","comparison":"at_least","expected":"1"},
    {"capability_id":"j0_left_unmatched_after","field":"count","comparison":"at_least","expected":"1"}
  ],
  "observation_requests": [],
  "operations": [
    {"table":"t0","rows":[{"k":{"type":"integer","value":"12"}},{"k":{"type":"integer","value":"13"}}]},
    {"table":"t1","rows":[{"k":{"type":"integer","value":"12"}},{"k":{"type":"integer","value":"12"}}]}
  ],
  "expected_effect": "预期两对匹配、一个重复键、一条未匹配输出；等待实测",
  "verification_requests": [],
  "verification_limit": "程序自动测量结构化目标；不声称覆盖特定过滤边界",
  "stop_reason": null
}
```

身份和证据 ID 必须来自实际状态，此处为说明性值。程序不是执行模型 SQL，而是检查并绑定普通 INSERT 值。
追加后的目标能力与显式验证请求、最低审计观测去重合并；合计超探测预算，写入前拒绝。
每个追加后的验证轮重新测量所有已接受目标，防止只验证新目标而遗忘此前承诺。

如果四行确实写入且实测匹配数=2、重复键=1、未匹配输出=1，三个目标才都为 `achieved`。
若唯一约束或生成值导致部分失败，逐行写入结果照实保留，目标按真实观测判断，不能按 INSERT 成功数推断。

## 评价及历史

- 每轮 `model_goal_evaluation`：本轮已接受目标的逐项 `expected → actual → evidence → outcome`。
- 每轮 `cumulative_model_goal_evaluation`：本查询全部已接受目标在当前数据上的状态。
- 最终 `model_goals`、`model_goal_evaluation`：累计目标及最终状态。后续 `stop` 或空目标数组不会删除先前目标。
- Oracle `postPreparationModelGoalEvaluation`、Benchmark `final_model_goal_evaluation`：准备结束后独立复测所得状态。
- 原 `evaluation`、`final_goal_evaluation`：继续表示程序固定断言；不混作模型目标。

只有相同 query/schema/data 版本、`status=exact` 且字段值合法的观测能证明结果。
至少一个目标明确不满足则整体 `not_achieved`；没有反证但缺少精确证据，或目标数组为空，则为 `unverified`；全部有精确证据且满足才为 `achieved`。
提交结果不确定时，所有模型目标均 `unverified`。冲突的精确证据也不能证明达成。
分组汇总从完整单行中的具名字段取值；外连接未匹配数使用专用指标，均不拿查询结果行数冒充。

当前提示词和运行闭环要求 `structured_goals` 字段，无法表达时使用 `[]` 并说明限制。
底层 v1.0 校验器保留旧 JSON 的回放兼容性；生产闭环会拒绝缺少新字段的响应并反馈纠错，不会静默忽略。
旧六场景、旧真实 DeepSeek 实验及归档数据不改写，不把历史固定断言重新解释为模型目标成功。

实现见 [目标校验与评价](../src/sqlancer/tidb/mixed/TiDBMixedPreparationGoals.java)、
[闭环](../src/sqlancer/tidb/mixed/TiDBMixedPreparationHarness.java)、
[Oracle](../src/sqlancer/tidb/mixed/TiDBAiMixedOracle.java)。

## 回归验证

新增 [MixedPreparationGoalsTest](../test/sqlancer/tidb/mixed/MixedPreparationGoalsTest.java) 的 7 项回归：
无固定断言的生产用法、目标探测超预算后修订、旧版本/样本/错误/冲突证据、非法目标及大整数、
外连接与分组字段、提交结果不确定、有意义的空结果停止。
实际 H2 回归得到匹配数 2、重复键 1，但 NULL 与未匹配输出均为 0，
固定断言通过而模型总体目标失败；见 [逐项记录](data-preparation-loop/structured-goals-results.json)。
该记录使用模拟模型响应和模拟 TiDB 元数据/会话；不是新的真实 DeepSeek 或 TiDB/TiFlash 实验。
