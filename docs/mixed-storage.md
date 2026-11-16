# TiDB 混合存储差分 Oracle

## 版本与入口

SQLancer 基线：`a513c87781ed879e5aae6bc78f8b6c6056bd7a99`。
TiDB 目标：`v9.0.0-beta.2.pre-1854-gb1d4142f99`，完整提交
`b1d4142f99a606aa3cd1ada6ee0caebaa84226dc`。

入口是 `tidb --oracle AI_MIXED_STORAGE`，实现位于 `src/sqlancer/tidb/mixed/`。
原始设计见 [设计归档](mixed_storage_differential_testing_oracle_design.md)。
本次离线、真实集群与随机运行结果见 [验证记录](mixed-storage-validation.md)。
混合路径比较和确认逻辑的修复见 [简要改动说明](mixed-storage-comparison-fix.md)。
使用独立 SQLancer 基线是为了保留原工作目录的未提交改动，并使交付可重复构建。

`TiDBMixedTarget` 校验 `TIDB_VERSION()` 的 Git Commit Hash，而不是仅相信发布名称。
接受完整 hash 和本地构建使用的精确 10 位 hash `b1d4142f99`，因此兼容
`Release Version: v9.0.0-latest-lwm`。集群中每个 TiDB 节点都必须匹配该提交。
TiKV、TiFlash、PD 必须存在，其实际版本与 hash 记录到每个 case，不能用 unistore 代替。

## 构建与离线测试

Java 11 或更高版本、Maven：

```bash
mvn -B -Djacoco.skip=true -Dtest='sqlancer.tidb.mixed.*Test' verify
```

这会编译整个 SQLancer、运行该 Oracle 的离线测试、打包 JAR 和 `target/lib/`，并执行上游
formatter、XML、Checkstyle、PMD、SpotBugs 检查。真实集群测试默认跳过。

## 随机测试

在专用测试集群执行；SQLancer 会删除并重建它分配的测试数据库。
Oracle 单独运行，不和其他 Oracle、QPG 或全局非预编译计划缓存变异组合。
该入口自行管理副本，不需要 `--tiflash`。

```bash
export MS_AI_ENABLED=false
export MS_ARTIFACT_DIR=logs/mixed
java -jar target/sqlancer-2.0.0.jar \
  --host 127.0.0.1 --port 4000 --username root --password '' \
  --num-threads 1 --num-queries 10 --random-seed 42 \
  --database-prefix mix_test_ --num-tries 1 --max-generated-databases 1 \
  tidb --oracle AI_MIXED_STORAGE --max-num-tables 5
```

示例连接空密码测试账号；如需要，使用 SQLancer 的 `--password` 参数设置密码。
SQLancer 自身的默认密码是 `sqlancer`，空密码必须显式传入。密码不写入 case 的环境记录。
每个 `check()` 最多生成三个 revision：

1. Provider 仅复用 TiDB 建表和索引生成器生成空 Schema。
2. 复用 SQLancer AST、表达式和 JOIN，按需补充整数连接列；只渲染一次并冻结 SQL。
3. 复用 INSERT 生成器，通过可选回调应用 RANDOM、SHARED 或 NULL_HEAVY 数据偏好。
4. 导出实际数据，全量采样 ANALYZE，建立并等待 TiFlash 副本，取得共同 TSO，执行单表双引擎预检。
5. 随机执行 TiKV-only、TiFlash-only、两组互补混合分配。两表时覆盖全部四种分配。
6. 每条路径验证 EXPLAIN 和 EXPLAIN ANALYZE 的逐表引擎，然后比较完整结果多重集。

修改 S/Q/D 会创建新 revision；仅改变引擎分配保留原 SQL、数据和 TSO。
多表默认采样四条路径，不穷举全部分配。无 ORDER BY、LIMIT、别名、多查询块、UNION 或 CTE。
计划消除了表、PointGet 没有明确引擎或出现未知扫描格式时，保守拒绝配置。
目标 TiDB 的默认 ANALYZE 会产生自动采样 Note 1105；这里显式使用 `WITH 1 SAMPLERATE`，
使生成的小数据集全量采样，并继续严格检查所有 SQL Warning。
EXPLAIN ANALYZE 是独立执行，所以证据级别明确标为 `PLAN_ONLY`。

## 比较与分类

按整行计数，保留重复行、NULL、二进制、字符串和高精度数值；不依赖返回顺序。
整数/DECIMAL 通过 `BigDecimal` 精确归一化。正式查询结果含浮点类型时（包括空结果）
为 INCONCLUSIVE；基础表预检允许对存储浮点数的精确表示比较。
结果包含 JDBC 列标签和类型信息，每次读取最多 10,000 行、16 MiB 字符表示预算；
超限不比较截断结果。Connector/J 使用流式结果读取。

比较所有状态为 `OK` 的路径对，包括两条混合路径之间的比较；四条有效路径共比较 6 对。
`outcome.allPathsValid` 独立记录是否具有四种要求的不同配置且全部有效。
`outcome.differingPairs` 和 `outcome.mismatchTypes` 保留所有已发现的差异对及分类，
`failures` 保留失败路径；差异判定不要求 `allPathsValid=true`。

| 状态 | 含义 |
|---|---|
| PASS | 两条独立单引擎基线和两条不同混合配置全部有效、结果一致 |
| BASELINE_DISAGREEMENT | TiKV 与 TiFlash 单引擎基线不同 |
| MIXED_VS_SINGLE_MISMATCH | 有效混合路径与有效单引擎路径不同 |
| MIXED_VS_MIXED_MISMATCH | 两条有效混合路径不同，即使单引擎基线失败也报告 |
| INVALID_CONFIGURATION | 表访问/引擎分配不符、配置缺失或重复 |
| INCONCLUSIVE | Warning、浮点结果或资源预算超限 |
| EXECUTION_ERROR | SQL、超时、快照/GC 或连接异常 |
| DATA_PREFLIGHT_MISMATCH | 同一快照下基础表双引擎读取不同，需独立调查 |
| VERSION_MISMATCH / UNSUPPORTED | TiDB 提交不符或缺少必要组件 |

部分路径失败时仍保留其他有效路径已经发现的差异，并记录失败，不报告完整通过。
同时发现多类差异时，主状态按 BASELINE_DISAGREEMENT、MIXED_VS_SINGLE_MISMATCH、
MIXED_VS_MIXED_MISMATCH 的顺序选择，完整分类和配置对均保留。
只有未发现差异且 `allPathsValid=true` 才为 PASS；未发现差异但路径不完整时保留失败分类。
Oracle 仅将 PASS 计入 SQLancer 成功比较数；跳过的候选保存证据并抛出 `IgnoreMeException`。

## DeepSeek 建议（可选）

```bash
export MS_AI_ENABLED=true
export DEEPSEEK_MODEL=deepseek-flash
# 由运行环境注入 DEEPSEEK_API_KEY，然后运行上述 SQLancer 命令。
```

使用官方 [Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)
与 [JSON Output](https://api-docs.deepseek.com/guides/json_mode/)；每次 check 最多四个请求，重试计入预算。
动作限制为保持、引导 JOIN、数据偏好、重新生成、换分配或移除 WHERE。
没有模型生成 SQL、修改 ExpectedErrors 或改变判定的入口。
模型关闭、缺少 key、认证失败、限流、网络异常或无效 JSON 时回退到确定性控制流程。
开启后会向 API 发送生成的测试 Schema、SQL 和执行证据，并产生 API 费用；审计不保存认证头。

## 证据、确认和重放

每次 check 写入 `MS_ARTIFACT_DIR/<database>_<uuid>/case.json` 和 `history.json`。
case 包含目标提交、随机种子、组件与 Session、SQL/DDL/DML、实际恢复语句、各轮 TSO、
配置、两种计划、原始结果与多重集、失败分类及 AI 审计。JSON 通过临时文件替换写入。

发现查询差异先保存原始证据，再独立重建实际数据确认两次。
`confirmationTargets` 记录所有原始差异对；这批配置对必须在两次确认中都有效且继续返回不同结果，
才标为 STABLE_CANDIDATE。无关路径失败或恢复不会阻止确认，新增差异也不能替代消失的原始差异对。
`confirmationOutcomes` 分别保留两次确认的比较结果、路径完整性与失败信息。
确认失败不抹去原差异；移除 WHERE 的约简仍仅在两次重复保持相同差异签名时接受，
约简的比较结果另存为 `reduction.outcomes`。
替代分配发现的差异同样先保存、确认，记录 `mismatchRunSet=alternativeRuns`。

```bash
export TIDB_HOST=127.0.0.1
export TIDB_PORT=4000
export TIDB_USER=root
# 如需要，由环境注入 TIDB_PASSWORD。
java -cp 'target/sqlancer-2.0.0.jar:target/lib/*' \
  sqlancer.tidb.mixed.TiDBMixedReplay logs/mixed/<case>/case.json 0
```

重放自动选择发现差异的 run set，也可添加第三个参数 `runs` 或 `alternativeRuns`。
仅使用本工具生成且可信的 case：重放会执行其中的恢复 SQL。
重放在新的 `msreplay_*` 数据库恢复实际数据、重新等待副本并获取新 TSO，不调用模型，
输出独立 replay JSON。数据库保留便于检查；结果非 PASS 时进程非零退出。
它不还原全局集群配置，也不保证任意人工数据库的完整备份。

## 真实集群集成测试

```bash
MS_INTEGRATION=true TIDB_HOST=127.0.0.1 TIDB_PORT=4000 TIDB_USER=root \
  mvn -B -Djacoco.skip=true -Dtest=sqlancer.tidb.mixed.MixedIntegrationTest test
```

该测试在随机命名的独立数据库中验证：重复行、NULL、VARBINARY、高精度 DECIMAL、
四种实际引擎分配、共同快照、数据导出和无模型重放，并清理自己创建的数据库。
测试必须连接符合目标提交的真实 TiDB/TiKV/TiFlash/PD，不能用 mock 或 unistore 报告通过。

## 实现边界

遵循原设计的表级 V1 范围。尚无任意谓词求解、全量 ddmin、实际结果执行 Trace 或
分区级/查询块级引擎分配。TiFlash 不支持的表和表达式保留失败证据，不由 AI 宣称兼容。
DeepSeek 的真实服务联调与随机测试覆盖率独立于离线测试结果。
