# 提示词与数据准备闭环验证（第四环节）

使用模拟模型响应和固定数据库，验证协议、执行、反馈与覆盖判定能否正确串联。
本环节不调用 DeepSeek、不评价真实模型的策略能力，也未接入 Oracle 自动补行路径。

代码：[TiDBMixedPreparationHarness](../src/sqlancer/tidb/mixed/TiDBMixedPreparationHarness.java)；
测试：[MixedPreparationTest](../test/sqlancer/tidb/mixed/MixedPreparationTest.java)。
每次模拟调用接收真实打包提示词和完整当前状态，返回经 v1 校验的 JSON。
写入复用第三环节追加执行器，观测复用第二环节生成的 SQL；H2 实际执行事务、约束、JOIN 与 WHERE，
仅 TiDB 专有会话/元数据由 fixture 模拟。t0.k 有主键，t1.k 无唯一约束。

## 判定原则

每轮分别保存：

```text
model_expectation → writes → actual_observations / actual_samples → evaluation
模型预期          → 写入结果 → 实际观测                         → 已达成／未达成／未验证
```

- 目标由固定用例通过 Requirement 提供可核对断言；框架不从模型自然语言解释推断真伪，
  不要求每个实验都补出相同场景，不自行修改目标。没有可测断言则为未验证。
- `achieved`（已达成）：所有断言均有同 query_id、schema_revision、data_revision 的 exact 观测并满足。
- `not_achieved`（未达成）：至少一项当前 exact 观测明确不满足；其他未测目标仍逐项标为未验证。
- `unverified`（未验证）：没有明确反证，但缺少精确事实；sampled、unknown、unsupported、error 或旧版本不能证明达成。

写入成功、模型声称达成、查询非空或模型 stop，都不会直接改变 evaluation。
补行后丢弃旧观测/样本，按 verification_requests 重新采集；未请求验证时保持未验证。
观测批次使用独立 ID 并保留 SQL、模拟快照和版本，失败后也不能把新 ID 当成新事实。

空结果不触发自动补行。固定用例可以将“保持为空”设为有意义目标，并精确核对它。
evaluation 是数据准备实验目标状态，不是差分 PASS/FAIL 或缺陷结论。

## 六个固定场景及实测结果

默认查询为 `t0 JOIN t1 ON t0.k=t1.k WHERE t0.k>10`。
除空结果用例外，实验目标为 WHERE 后至少 2 对匹配、至少 1 个重复匹配键。
所有用例最初包含 t0.k=1；其他初始数据见表。

| 场景 / 初始键 | 模型预期 → 写入结果 → 实际观测 → 判定 |
|---|---|
| [共享值被过滤](data-preparation-loop/filtered_shared.json)：t1=[1] | 先观测：WHERE 前 1 对、后 0 对 → 未达成；再补 t0=[12]、t1=[12,12]，3 行成功 → WHERE 后 2 对、1 个重复键 → 已达成，停止 |
| [唯一约束阻止重复](data-preparation-loop/unique_blocks_duplicates.json)：额外 t0=[12]、t1=[12] | 负向模拟误补 t0.k=12 → 主键失败，仍 1 对、0 重复键 → 未达成；改补 t1.k=12 → 成功，实测 2 对、1 个重复键 → 已达成 |
| [已经足够覆盖](data-preparation-loop/already_sufficient.json)：额外 t0=[12]、t1=[12,12] | 先实测已有 2 对、1 个重复键 → 已达成 → stop，0 次写入 |
| [只有有限样本](data-preparation-loop/samples_only.json)：额外 t0=[12]、t1=[1,12,12] | 每表只采 1 行，两个样本均截断 → 未验证 → stop，0 次写入；实际有覆盖也不能靠样本宣称已证明 |
| [连续约束失败](data-preparation-loop/consecutive_constraint_failures.json)：额外 t0=[12]，t1=[] | 依次试补 t0.k=1、12 → 两次主键失败，实测 WHERE 后均 0 对 → 未达成；程序因连续无进展终止，第三条模拟响应不会被调用 |
| [有意义的空结果](data-preparation-loop/meaningful_empty.json)：t1=[1]，WHERE FALSE | 目标为保持空结果；实测 WHERE 前 1 对、后 0 对且原查询为空 → 已达成 → stop，0 次写入 |

负向模拟用于验证程序拒绝/测量错误策略，并不表示提示词允许忽视约束。
模板由测试补齐请求/版本、空数组及当前 evidence_ids，形成完整响应后才进入协议校验；
模型可以选策略，程序不把其文字预期当作事实。

## 重试、预算与终止

框架记录失败的表和指定列值；同一失败行再次出现时整份响应在写入前拒绝。
改变请求 ID、观测 ID、reason 或 DECIMAL 尾随零不能解除拦截；确实改变表/值可提出不同计划。
这是受控实验的保守策略，不自动判断自由文本“新理由”是否充分，也不因插入失败而要求模型继续补行。
即使未指定列由 SQLancer 随机生成，相同失败提案也不重抽值重试。

另外设置连续无成功写入的 append 轮数上限，本组用例为 2；不同失败提案也不能无限循环。
各轮扣模型调用、探测请求、实际尝试行数和耗时；部分成功按执行器事实反馈。
模型 stop、预算耗尽、响应拒绝、连续无进展、提交不确定均会结束实验。
提交不确定时不再观测/重试，evaluation=unverified，final_state=null，要求重建。
结束后冻结执行器；不自动进入差分比较。

## 复现与记录

```bash
mvn -o -B -ntp -Djacoco.skip=true '-Dtest=sqlancer.tidb.mixed.MixedPreparationTest' test
python3 docs/data-preparation-loop/summarize.py
```

完整记录生成于 `target/mixed-preparation/*.json`：每轮请求前完整状态、模型响应、历史、实际写入、
观测、SQL 审计、版本、预算、每项目标判定及终止原因。
本次运行的稳定精简记录已提交为 [results.json](data-preparation-loop/results.json)，
它保留每轮模型预期、逐行写入、实际观测/样本及目标证据，去掉耗时、重复历史等字段。
摘要脚本仅提取实际运行结果，不根据模板伪造观测或判定。

额外反例覆盖：写入成功但没有验证、验证后明确未达成、模型自称达成、无理由重复失败、
过期/非精确观测、提交应答丢失、无断言及无模型调用预算。
提示词补充对应规则，并检查构建后实际加载的提示词包含这些约束。
这证明受控响应下的程序边界与记录行为；真实模型是否遵守提示词仍需后续实验。
