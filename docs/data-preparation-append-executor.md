# 可控追加行执行器（第三环节）

实现入口：[TiDBMixedAppendExecutor](../src/sqlancer/tidb/mixed/TiDBMixedAppendExecutor.java)。
输入是前两环节的状态包和人工构造的 v1 `append_rows` JSON；本环节不调用模型，也未接入 Oracle 自动补行循环。

## 执行规则

1. 校验整个计划、查询/Schema/数据版本、冻结状态、请求 ID 和预算。
   再读取实际元数据，拒绝不完整或已变化的 Schema、生成列赋值及未知列。
   要求连接位于预期数据库、autocommit=1、没有已有事务或历史快照。
2. 每行使用独立事务和普通的参数化 `INSERT INTO ... VALUES (?,...)`。
   [TiDBInsertGenerator.appendRow()](../src/sqlancer/tidb/gen/TiDBInsertGenerator.java)
   复用 SQLancer 常量生成器填充未指定的可写列；生成列不出现在 INSERT 中，未指定的自增列由数据库生成。
3. 在同一 TiKV 事务中读取目标表的写前、写后完整结果，按多重集合核对：
   原有行全部保留，恰好新增一行；核对新行中的模型指定值后才 COMMIT。
   不能完整读取、Warning、约束错误、值被舍入/截断或旧行变化，均回滚该行。
4. 成功行保留，失败行继续下一行；每行反馈实际结果。
   每次尝试消耗一行预算，失败也消耗；每次成功提交使 data_revision 加一。

普通随机 INSERT 生成路径保持原行为；追加模式没有 REPLACE、IGNORE、ON DUPLICATE KEY UPDATE、
SQL 表达式或执行模型 SQL 的入口。文本中的 SQL 字符只作为参数数据。

## 类型与精度

指定值沿用 v1 的带类型表示：

```json
{
  "k": {"type":"integer","value":"18446744073709551615"},
  "n": {"type":"decimal","value":"123456789012345678901234567890.01"},
  "s": {"type":"text","value":"普通文本"},
  "b": {"type":"binary","value":"AP8="}
}
```

整数经 BigInteger、小数经 BigDecimal 解析并绑定，避免经过 long 或 double 丢失精度；
二进制为 Base64，NULL 使用 `{"type":"null","value":null}`。
模型指定数值按精确数值相等核对，允许 DECIMAL 的尾随零差异；文本和二进制逐值相等。
超出列范围或不能精确表示的值不算成功；例如 DECIMAL(38,2) 的 `12.345` 不会悄悄变成 `12.35`。
数据库负责检查 NOT NULL、唯一键和已启用的 CHECK，执行器不预先求解约束。

## 调用及反馈

```java
TiDBMixedAppendExecutor executor = new TiDBMixedAppendExecutor(state, packet, 100);
JsonObject feedback = executor.execute(manualPlanJson, packet);
TiDBMixedSupport.save(outputPath, feedback);
```

`100` 是目标表精确核对的行数上限，必须容纳写后结果；超出即回滚，不能把截断样本当作完整证据。
同一用例复用同一个执行器，预算和版本保存在实例内，不能靠下一状态包补回预算或重放请求。
下一请求必须使用新 request_id、执行器返回的版本和剩余预算；补行后原观测/样本过期，需重新采集。
发现差异或进入正式比较前调用 freeze()；已冻结状态也会被协议拒绝。

每行 `writes[]` 包含：

| 字段 | 含义 |
|---|---|
| table、row_index | 操作中的表及行下标（从 0 开始） |
| specified_values、attempted_values | 人工/模型指定值、补齐后的实际尝试值 |
| actual_values | 已确认提交的新行完整实际值，含生成列和自增列；不可确认时为 null |
| sql | 程序生成的参数化 INSERT 模板 |
| status、affected_rows | success/1、failed/0、not_attempted/0 或 unknown/null |
| data_revision_after | 该行后的已知数据版本；提交结果不确定时为 null |
| sqlstate、error_code、error | SQL 错误或执行器拒绝原因 |

例如已有唯一键 k=1，依次追加 k=2、1、3，三行反馈为：

```text
success       affected_rows=1  data_revision_after=1
failed        affected_rows=0  data_revision_after=1  error_code=1062
success       affected_rows=1  data_revision_after=2
attempted_rows=3  remaining_insert_rows=0  requires_rebuild=false
```

计划整体校验、实际元数据或会话检查不通过时，执行前抛出异常，不消耗行预算。
执行期间的失败通过反馈返回；若 COMMIT 应答丢失或 ROLLBACK 失败，记 unknown，停止后续行，
requires_rebuild=true，版本返回 null，拒绝继续使用该执行器；不能假装未提交后自动重试。
结果中的 observation_ids 为空，verification_requests 仅保留为待执行需求，不宣称预期场景已形成。

## 边界与验证

执行器用于 SQLancer 独占的测试数据库；期间不允许其他连接写数据或改 Schema。
写前/写后核对完整目标表，适合当前小表测试，非大表执行方案。
时间预算在行间检查，并设置语句超时；单行的多个数据库操作、元数据检查和会话恢复不构成严格总耗时上界。
失败 INSERT 可能消耗自增 ID；回滚保证表中已有行不被修改，不保证 ID 连续。

[离线测试](../test/sqlancer/tidb/mixed/MixedAppendTest.java) 使用真实 H2 参数化写入/事务，
仅模拟 TiDB 会话与元数据；[真实 TiDB 测试](../test/sqlancer/tidb/mixed/MixedAppendIntegrationTest.java)
使用目标提交验证精确值、部分成功、CHECK 及拒绝路径。
人工计划的构造及完整执行入口见上述测试，运行命令和结果见 [验证记录](mixed-storage-validation.md#可控追加行执行器回归第三环节)。
