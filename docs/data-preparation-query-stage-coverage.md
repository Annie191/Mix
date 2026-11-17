# 基础数据与查询阶段覆盖分开报告

表中存在某个值，只能证明数据存在。只有该值形成原 FROM/JOIN 的输出，才能证明它到达 WHERE 输入；通过完整 WHERE 后，才能证明它进入分组或聚合输入。
这里验证逻辑关系阶段，不声称优化器实际按某种顺序求值或某个物理算子已经执行；物理引擎路径仍单独核验。

## 报告结构

`data_coverage_before/after` 和 `data_coverage_change` 新增 `scope_version=2`，分别保存：

| 字段 | 含义 |
|---|---|
| `base_data` | 表行数、表中 NULL、边界值存在性、局部 ON 的匹配/未匹配/重复匹配事实 |
| `query_stages` | 原查询 WHERE 输入/输出、过滤边界、谓词真假/UNKNOWN、外连接未匹配输出、分组/聚合输入及现有分组场景 |
| 顶层 `present/measured/added/lost/complete` | 兼容入口，仅映射查询阶段；基础统计不再贡献查询场景分数 |

每个范围独立保存已存在、已精确测量、预期可测但未验证的桶；前后均有同版本精确统计，才比较该桶的新增/丢失。
“同版本”指每个观测与所属写前或写后状态的 query/schema/data 版本一致；写前、写后数据版本可因追加而不同。
预算不足、错误或样本在前后两轮都缺测时，也不能得到“完整且无收益”的结论。
不支持的探测保留 `unsupported` 原因，不以 0 代替。`complete` 只表示本查询已支持的桶都可比较，不代表支持任意表达式场景。

Benchmark 汇总新增独立的 `base_data`、`query_stages`：`new_hits`、`lost_hits`、`new_buckets`、
`complete_changes`、`unverified_changes`、`missing_scoped_reports`；只有 `query_stages` 计算 `new_effective_scene_hits`，且仍要求全部选定引擎路径有效。
补行只有基础数据新增、查询阶段完整测量却没有新增时，记录 `base_data_only_append_rounds`，不会算作有效查询场景改善。
旧记录的原始字段保持不变；新版汇总把旧未分范围的命中保存为 `legacy_unscoped_scene_hits`，不再加到查询阶段分数。
缺少新版范围的报告单列统计，无法判断阶段收益的旧补行也不计为“已证明无收益”，不改写历史真实实验。

## 新增探测

所有探测 SQL 由程序从原 AST 构造，在现有只读快照中计费执行，AI 只能选择目录 ID。
新增能力的 `goal_fields={"count":"non_negative_integer"}`，可以直接作为 AI 的结构化目标，追加后按已有闭环重新验证。

| 能力 ID | 精确含义 |
|---|---|
| `base_where_boundary_{below,at,above}` | 相关基础表中值为边界 -1 / 边界 / 边界 +1 的行数，仅数据存在性 |
| `where_boundary_{below,at,above}_before` | 原 FROM/JOIN 输出中，相关列值为上述值的行数，已真正形成 WHERE 输入 |
| `where_boundary_{below,at,above}_after` | 上述行通过完整原 WHERE 后的数量，不忽略其他过滤条件 |
| `where_input_nulls_<表>.<列>` | 原 FROM/JOIN 输出中，查询相关列为 NULL 的行数，包含外连接补出的 NULL |
| `where_output_nulls_<表>.<列>` | 原 WHERE 后该列仍为 NULL 的行数，位于分组/聚合之前 |
| `where_predicate_true/false/unknown` | 完整原 WHERE 在原 FROM/JOIN 行上的 SQL 三值结果，不等同于某个输入列为 NULL |
| `aggregate_input_nulls_<表>.<列>` | 实际聚合表达式引用的列，在 WHERE 后为 NULL 的输入行数 |
| `aggregate_arg<N>_nulls` | 原聚合参数表达式本身在 WHERE 后为 NULL 的行数，不从列 NULL 推测表达式结果 |
| `group_input_nulls_<表>.<列>` | 原分组表达式引用的列，在 WHERE 后为 NULL 的输入行数；不推测任意分组表达式的结果 |

已有的 `j0_left_unmatched_after`、`j0_right_unmatched_after` 现在纳入查询阶段计分；
未保留侧、INNER JOIN、或被 WHERE 排除的外连接行均不能获得“未匹配输出”覆盖。
原 `j0_filtered_matches` 继续表示存在匹配被过滤，不再代表命中过滤边界。
表中 `nulls_present_*` 仅保存在 `base_data`，不能替代这些阶段事实。

边界识别先支持整数列与整数字面量之间的 `= != > >= < <=`，支持左右反写及 AND 中的多个比较。
AND 左右以 `_l`、`_r` 递归编号，例如 `where_boundary_l_at_before`。
`before` 表示到达完整 WHERE 的输入，**不证明其他 AND 子句也满足**；`after` 必须通过完整 WHERE。
边界及相邻值用 BigInteger 构造，避免 Long 最大/最小值附近溢出；不可存储的相邻值不会因此自动算覆盖。
OR、NOT、转换/函数比较、非整数边界等明确标为不支持；不会从自然语言推断边界达成。
无 WHERE 时不创建边界或谓词真假指标。查询相关列按结构化 AST 收集；不解析原始 SQL 文本片段猜测列或表达式。

## seed 3 同形反例

查询为 `t0 INNER JOIN t1 ON t0.k=t1.k WHERE t0.k>10`：

| 数据 | 表中 k=10 | 到达 WHERE 的 k=10 | WHERE 后 k=10 | 结论 |
|---|---:|---:|---:|---|
| t0 有 10，t1 没有 10 | 1 | 0 | 0 | 只有基础数据存在，边界尚未触达 |
| t0、t1 都有 10 | 1 | 1 | 0 | 边界形成连接对并被原谓词排除 |
| 另外两侧都有 11 | 1 | 1 | 0 | `above_before=1, above_after=1`，同时验证相邻通过值 |

另加匹配键 5，只能增加普通过滤，不能冒充边界 10。
若希望覆盖“边界被排除”和“相邻值通过”，AI 应明确选择两个目标：

```json
[
  {"capability_id":"where_boundary_at_before","field":"count","comparison":"at_least","expected":"1"},
  {"capability_id":"where_boundary_at_after","field":"count","comparison":"equals","expected":"0"},
  {"capability_id":"where_boundary_above_after","field":"count","comparison":"at_least","expected":"1"}
]
```

单独声明 `at_after=0` 不能证明边界曾到达 WHERE，必须结合 `at_before>=1`。
模型仍决定目标和补行策略，程序仅检查和测量；不强制把空查询补成非空。

实现见 [阶段探测](../src/sqlancer/tidb/mixed/TiDBMixedQueryStages.java)、
[范围与差值](../src/sqlancer/tidb/mixed/TiDBMixedDataCoverage.java)、
[回归测试](../test/sqlancer/tidb/mixed/MixedQueryStageCoverageTest.java)。

## 回归结果

10 项新增回归使用实际 H2 INSERT、事务和只读 SQL，TiDB 会话/元数据由 fixture 模拟：

- seed 3 同形输入：孤立边界、普通过滤、补齐匹配、边界相邻值分开核对。
- NULL 表中存在但被 INNER JOIN 排除；LEFT/RIGHT 未匹配输出保留或被 WHERE 去掉。
- 聚合引用列为 NULL、参数表达式返回 0，以及列非 NULL、参数表达式返回 NULL，两种相反情形分别核对。
- AND 与反写比较保留完整 WHERE；OR 明确不支持；边界整数不溢出。
- 三表查询中局部 JOIN 已匹配，但第三表阻断输出时，WHERE 边界计数仍为 0。
- 样本、旧版本、两轮都缺测不能冒充已验证；基础新增和旧未分范围指标不获得查询阶段分数。
- 真实执行器闭环中，第一轮只补左表，模型边界目标未达成且无查询场景新增；第二轮补齐右表，目标经自动实测后达成。

可核对的计数、两个范围的变化及逐轮目标评价见 [实测记录](data-preparation-loop/query-stage-coverage-results.json)。
本轮未调用真实 DeepSeek，也未运行真实 TiDB/TiFlash；原真实对照实验的覆盖率保持原样。
