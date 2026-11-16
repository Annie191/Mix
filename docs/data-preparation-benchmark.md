# 真实 DeepSeek 联调与策略对照（第五环节）

新增真实 v1 [DeepSeek 客户端](../src/sqlancer/tidb/mixed/TiDBDeepSeekPreparationClient.java)、
[配对运行器](../src/sqlancer/tidb/mixed/TiDBMixedPreparationBenchmark.java)、
[数据场景统计](../src/sqlancer/tidb/mixed/TiDBMixedDataCoverage.java) 和
[指标汇总](../src/sqlancer/tidb/mixed/TiDBMixedBenchmarkMetrics.java)。
模型响应经协议校验后进入追加执行器；写后重新测量，再冻结数据并执行基础表预检和混合路径比较。
模型不能提供执行 SQL、更改查询或判定缺陷。

## 运行方法

真实模型凭据通过 `DEEPSEEK_API_KEY` 或 `DEEPSEEK_API_KEY_FILE` 提供，代码、请求审计和报告不记录凭据。
缺少凭据、HTTP 错误、超时或非法响应不会用模拟结果冒充 AI。
客户端不隐式重试，记录每次请求的实际模型、finish_reason、usage、时间及估算费用；
即使内容被截断或无效，已返回的 token 用量仍计入。用量未返回时成本未知，不能按 0 处理。

```bash
export TIDB_HOST=127.0.0.1 TIDB_PORT=22000 TIDB_USER=root
export DEEPSEEK_MODEL=deepseek-flash
# 在环境中设置 DEEPSEEK_API_KEY_FILE，或注入 DEEPSEEK_API_KEY。
java -cp target/sqlancer-2.0.0.jar \
  sqlancer.tidb.mixed.TiDBMixedPreparationBenchmark \
  logs/preparation-benchmark-NEW 0,1,2,3,4,5 RANDOM,SHARED,AI true
```

长程实验使用固定 JAR 副本，并显式保留依赖路径，避免构建替换运行中的资源：

```bash
cp target/sqlancer-2.0.0.jar /tmp/mix-benchmark-runner.jar
java -cp "/tmp/mix-benchmark-runner.jar:$PWD/target/lib/*" \
  sqlancer.tidb.mixed.TiDBMixedPreparationBenchmark \
  logs/preparation-benchmark-NEW 0,1,2,3,4,5 RANDOM,SHARED,AI true
```

要求目标提交及真实 TiDB/TiKV/TiFlash/PD，运行器创建并清理独立数据库。
最后的 true 表示执行引擎路径验证；false 仅采集数据指标，不能证明混合存储闭环通过。
使用新输出目录，保留旧实验；不能重复种子或策略造成重复计数。

现有 SQLancer Oracle 可通过 `MS_DATA_PREPARATION=DEEPSEEK` 开启真实补行：

```bash
MS_DATA_PREPARATION=DEEPSEEK MS_ARTIFACT_DIR=logs/mixed-live \
  java -jar target/sqlancer-2.0.0.jar \
  --host 127.0.0.1 --port 22000 --username root --password '' \
  --num-threads 1 --num-queries 1 --random-seed 42 --max-expression-depth 1 \
  --database-prefix ms_live_ --num-tries 1 --max-generated-databases 1 \
  tidb --oracle AI_MIXED_STORAGE --max-num-tables 2
```

该开关关闭旧的建议动作调用，以 RANDOM 生成初始数据，再执行新的协议补行。
每个查询 revision 最多 3 次调用、6 次追加行尝试、96 次探测和 120 秒准备预算；原有最多 3 个 revision。
结束后保存实际数据并沿用原快照、基础表预检、引擎计划校验、全部有效路径对比较及差异确认。
Oracle 未提供机器可测的自由文本目标解析，因此目标评价保守记未验证，另存实测数据覆盖变化；
benchmark 使用运行前明确的固定可测目标，不能把其成功率称为任意模型自然语言目标的成功率。

## 配对实验与预算

每个种子三组共享相同 Schema、冻结 SQL、初始数据和引擎配置，逐组恢复初始行，重新设置 SQLancer 种子。
每个种子轮换策略顺序；复用表 ID 和 TiFlash 副本，避免每组都重建副本。
副本等待和差分执行属于报告的总耗时；策略准备耗时单独统计，不将模型等待藏在数据库时间之外。

本轮是六种受控小表用例：INNER JOIN、LEFT JOIN、左侧唯一键、已有重复匹配及 NULL、GROUP BY、WHERE FALSE。
初始 t0/t1 各有 (k=5,v=5)，种子 3 另有 t0=(12,1),(NULL,1)、t1=(12,1),(12,2)。
一般查询为 k 等值连接且 WHERE t0.k>10；种子 5 使用 WHERE FALSE，种子 4 按 t0.k 分组。
目标预先定义：一般用例 WHERE 后至少 2 对匹配且至少 1 个重复匹配键；WHERE FALSE 用例保持原查询为空。
模型可以自行选择补行策略或停止，不强制把空查询补成非空。

三组使用相同普通 INSERT 执行器、6 行尝试上限、96 次探测上限、120 秒准备上限。
固定的前/后测量每次最多 64 次探测、每列表 64 行、30 秒，三组相同，独立于策略内预算。
追加后的审计观测设定最低集合，占用上述 96 次预算，防止只请求有利指标。
模型最多 3 次真实请求，基线无 API 请求；两组固定基线通常一轮各表补 3 行，然后停止。
实际消耗允许不同，报告展示实际行数、探测、时间和费用，不要求人为烧完预算。

RANDOM/SHARED **复用现有 fill() 的值策略**，SHARED 仍是五个 [0,100) 整数的共享池、
约一半的引导连接列取共享值；其他值由 SQLancer 常量生成器产生。
为控制只追加语义及相同行预算，三组统一使用普通 INSERT，未运行旧生成器的 REPLACE/IGNORE/UPDATE 变体。
这是现有值策略的受控对照，不等同于整个旧随机建库/查询/多行 DML 流程的吞吐比较。
固定小表的结果不能推广为任意 Schema、长程随机测试或实际缺陷发现率提升。

## 指标定义

| 指标 | 分子 / 分母或计算方式 |
|---|---|
| 计划执行率 | 至少尝试一行的 append 计划 / 可识别的 append 提案；协议拒绝也在分母，无法识别的响应另计 rejected |
| 可验证追加目标达成率 | append 轮中 achieved / (achieved + not_achieved)；unverified 单列，stop 不混入 |
| 最终用例目标达成率 | 前述固定目标最终 achieved / 所有尝试用例，异常用例仍在分母 |
| 新增观测数据场景 | 同一种子写前/写后都精确测量、由不存在变存在的场景桶 |
| 新增有效场景 | 上述新增场景且本用例全部选定引擎路径有效；不要求结果一致才算执行有效 |
| 场景丢失 | 写前存在、写后不存在的可比较场景桶，单独列出 |
| 无新增场景的补行 | 某 append 轮写入成功，完整可比较测量没有新增桶时，该轮成功行数；不细分单行因果 |
| 补行收益未验证 | 成功写入但写前/后测量不能完整比较的行数，不能计入“无收益” |
| 重复失败 | 重复失败提案被程序拒绝的次数；首次不同约束失败另按 rows_failed 统计 |
| 时间 | 准备耗时与总耗时分别汇总；副本初始化/等待会影响总耗时，不能当成策略开销差异 |
| 调用成本 | 实际 prompt/cache-hit/cache-miss/completion token × 记录的单价；缺失用量保持未知 |

数据场景桶包括 JOIN 匹配、两侧未匹配、两侧重复匹配、WHERE 后重复键、过滤掉的匹配、
各列 NULL、GROUP BY 的多组/多行组、输入非空但查询为空。
“查询非空”只作为诊断值，不能单独贡献新增场景或证明目标。
计数为种子×场景桶的出现次数，同时保存去重桶名；不把 2 对变成 20 对算作 18 个新增场景。

引擎统计独立保存全部配置、路径状态、分配比例、JOIN 两侧组合及 allPathsValid。
计划不符、Warning、基础表预检失败不能贡献有效引擎覆盖。
WHERE FALSE 可能被优化为空计划，仍保留其数据语义价值，但不把消除表访问的路径冒充引擎覆盖。

## 证据与费用

每个 seed/strategy 生成完整 JSON：初始/最终状态、每轮模型内容和写入事实、观测 SQL、实际数据恢复语句、
组件版本、快照、EXPLAIN/ANALYZE、结果多重集、API usage、失败和清理情况。
all.json 保留全部用例，summary.json 提供指标及显式分子/分母；不会删除不利或失败结果。

客户端按 [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/) 的
JSON Output、禁用 thinking 及 usage 字段接入。
默认费用采用 [官方价格页](https://api-docs.deepseek.com/quick_start/pricing/) 于本次运行核对的
deepseek-flash 非高峰 USD/百万 token：命中 0.003、未命中 0.15、输出 0.60。
每次请求记录该配置与 UTC 时间；可用 DEEPSEEK_HIT_USD_PER_M、DEEPSEEK_MISS_USD_PER_M、
DEEPSEEK_OUTPUT_USD_PER_M 覆盖。它是估算费用，非账户账单；价格、模型或时间段改变后应重新配置。

本轮实际结果见 [验证记录](mixed-storage-validation.md#真实集群与策略对照第五环节)。
