# 混合存储 Oracle 验证记录

验证日期：2026-11-16（UTC）。运行环境：Linux amd64，OpenJDK 17.0.15；项目编译目标为 Java 11。
SQLancer 基线与目标 TiDB 源码提交见 [使用说明](mixed-storage.md)。
下方集群与随机冒烟记录来自首次实现提交 `41ea000d`；后续比较逻辑修复的验证记录见文末。

## 实际集群

使用独立 TiUP playground，TiDB 连接端口为 `22000`，包含真实 TiKV、TiFlash 和 PD。
以下信息来自 `information_schema.cluster_info`，并非只根据启动参数推断：

| 组件 | 实际版本 | Git hash |
|---|---|---|
| TiDB | `9.0.0-latest-lwm` | `b1d4142f99` |
| TiKV | `9.0.0-beta.2` | `cb740b864e424185500faeb9641882a3796da79e` |
| TiFlash | `9.0.0-beta.2.pre-137-ga9010056f` | `a9010056f010954f349883a9b3d8716cc0e3ba86` |
| PD | `9.0.0-beta.2.pre-347-gdca466b` | `dca466b4f55337a562828e0401027164a227a14f` |

TiDB 二进制为本地源码构建，发布名称被覆盖，但 Git hash 对应要求的
`v9.0.0-beta.2.pre-1854-gb1d4142f99`。其他组件的 hash 单独记录，不宣称其源码提交与 TiDB 相同。

## 离线与集成测试

离线测试：`MixedUnitTest` 7 项、`MixedJdbcTest` 9 项全部通过。
覆盖 AST 与 INSERT 接入、逐表计划校验、版本门禁、配置去重、多重集及精确值、共同快照、
Warning/异常/资源超限、JSON 证据、DeepSeek 请求和动作约束，以及连接前的选项互斥校验。
默认构建中的真实集群测试跳过，不能将该跳过记作集成测试通过。

formatter、XML、Checkstyle、PMD 和 SpotBugs 检查通过；SpotBugs 报告 0 个 bug、0 个 error。
首次离线 verify 因 SpotBugs 插件依赖未缓存而停止；下载依赖后分析通过，属于构建环境问题。
随后执行完整 `mvn -o -B -ntp -Djacoco.skip=true '-Dtest=sqlancer.tidb.mixed.*Test' verify`
成功，耗时约 78 秒，测试汇总为 17 项、0 失败、0 错误、1 项预期跳过（真实集群测试）。

真实集群测试通过：

```bash
MS_INTEGRATION=true TIDB_HOST=127.0.0.1 TIDB_PORT=22000 TIDB_USER=root \
  mvn -o -B -ntp -Djacoco.skip=true \
  -Dtest=sqlancer.tidb.mixed.MixedIntegrationTest test
```

`MixedIntegrationTest` 的 1 项测试运行约 187 秒，验证两表 LEFT JOIN、重复行、NULL、
VARBINARY 和 DECIMAL(38,2)。四种分配使用共同快照，每条路径返回 6 行、3 种不同行值，
结果多重集一致。导出实际数据后通过独立 `TiDBMixedReplay` 重放，重放结果为 PASS。
测试清理自身创建的原始和重放数据库。

集群联调发现目标版本默认 ANALYZE 会返回自动采样 Note 1105。
实现改为 `ANALYZE TABLE ... WITH 1 SAMPLERATE` 后，完整集成测试通过；正式比较仍严格拒绝 Warning。

## 打包程序随机冒烟

```bash
MS_AI_ENABLED=false MS_ARTIFACT_DIR=/home/lwm/Mix-smoke-artifacts \
  java -jar target/sqlancer-2.0.0.jar \
  --host 127.0.0.1 --port 22000 --username root --password '' \
  --num-threads 1 --num-queries 3 --random-seed 42 --max-expression-depth 1 \
  --database-prefix mix_smoke_ --num-tries 1 --max-generated-databases 1 \
  tidb --oracle AI_MIXED_STORAGE --max-num-tables 2
```

进程正常退出（exit 0），保存 3 个 case：

| 最终分类 | 数量 | 实际行为 |
|---|---|---|
| PASS | 2 | 四种分配计划有效，结果多重集一致 |
| INCONCLUSIVE | 1 | 类型转换及 TiFlash 不支持的下推产生 Warning，保存证据并跳过 |

其中一个 PASS 在两次预检拒绝后，第三个 revision 完成有效比较。
没有将预检失败或 INCONCLUSIVE 算作通过，没有发现查询结果差异。
这只是固定种子的短程验证，不能代表长时间随机覆盖率或证明 TiDB 不存在缺陷。

DeepSeek 真实服务未调用；相关离线测试使用模拟 HTTP 响应，不产生 API 费用。

## 比较与确认逻辑修复回归

新增 `MixedComparisonTest` 12 项，以及 `MixedJdbcTest` 中 1 项 JDBC 执行与 JSON 证据回归。
先在修复前运行用户报告的场景，测试失败：期望 MIXED_VS_MIXED_MISMATCH，实际 EXECUTION_ERROR。
修复后所有 29 项离线测试通过，真实集群测试默认跳过，本次未重启集群或重跑随机冒烟。

重点覆盖：

- 两条基线失败、混合路径不同：1 个有效比较对，MIXED_VS_MIXED_MISMATCH，
  `allPathsValid=false`，保留两个基线错误。
- 两条基线失败、混合路径相同，或只有一条有效路径：保留失败状态，不报 PASS 或差异。
- 四条有效路径：6 个比较对；只有全部一致才为 PASS。
- 多类差异同时存在：保留所有差异对与分类，主状态按既定优先级选择；
  `allPathsValid=true` 和发现差异可以同时成立。
- 配置缺失或重复：即使其余结果一致，也不能通过。
- 元数据和重复次数不同、执行顺序打乱、Warning 或无效路径与有效差异并存。
- 原始差异对连续两次复现可以确认，无关基线恢复不会阻止确认；
  原始差异对失败或变为一致时，其他差异不能替代它；只完成一次复现不能确认。
- JDBC fixture 模拟基线 SQL 失败、混合路径计划有效且结果不同，验证共同快照及新字段的 JSON 落盘。

完整验证命令：

```bash
mvn -o -B -ntp -Djacoco.skip=true '-Dtest=sqlancer.tidb.mixed.*Test' verify
```

本次完整 verify 成功（约 137 秒）：30 项测试、29 项通过、1 项真实集群测试预期跳过，
0 失败、0 错误。打包、formatter、XML、Checkstyle、PMD 和 SpotBugs 全部通过，
SpotBugs 报告 0 个 bug、0 个 error。

## 基础表预检分流与配置枚举回归

配置策略改为两表全部 4 种、三表全部 8 种、更多表完整枚举后随机选择 6 种，
其中固定包含全 TiKV、全 TiFlash。基础表预检不同则停止本轮，独立保存证据及计数。
改动细节见 [追加说明](mixed-storage-comparison-fix.md#追加基础表预检分流与配置覆盖)。

新增 `MixedConfigurationTest` 7 项和 `MixedJdbcTest` 3 项：

- 2 至 5 表完整枚举得到 4、8、16、32 种无重复组合。
- 两表和三表全选；4、5 表抽取 6 种，保留两条单引擎基线及四条不同混合配置，
  多种随机种子可选择不同集合，并覆盖两表/两表的平衡分配。
- 正式比较的完整路径预算为 4、8、6，比较对数为 6、28、15；缺失、重复和失败不能 PASS。
- 混合路径差异在基线失败时仍报告，确认逻辑适用于扩展配置集。
- JDBC fixture 执行 3、4、5 表所选配置，验证计划、共同快照和实际比较对数。
- 预检差异保留两个有效读取结果，首次发现即结束预检，返回停止信号，计数和 JSON 证据独立保留；
  预检一致后可进入正式比较，普通 Warning 归入 PREFLIGHT_FAILED。

执行同一完整 verify 命令成功（约 81 秒）：40 项测试、39 项通过、1 项真实集群测试预期跳过，
0 失败、0 错误。打包、formatter、XML、Checkstyle、PMD 和 SpotBugs 全部通过。
本次未重启真实集群或重新运行随机冒烟，JDBC fixture 不代表真实 TiFlash 集成测试。

## 引擎比例与 JOIN 组合覆盖统计回归

新增 `MixedCoverageTest` 9 项和 `MixedJdbcTest` 1 项，验证：

- 四表全枚举时，TiFlash 表数 0/1/2/3/4 对应 1/4/6/4/1 条有效路径，包含全部六种 2∶2。
- 固定 LEFT JOIN 的四种左右引擎组合各出现四次，JOIN 方向、类型和 NATURAL 子类型分别计数。
- 无效计划、执行错误仅增加尝试和失败，不算有效覆盖；未观察到的比例/组合保留零计数。
- 多个 JOIN 按节点计数，路径数保持一次；嵌套 JOIN 侧的混合引擎记为 mixed。
- 逗号 FROM 不产生显式 JOIN 计数，另有 pathsWithoutExplicitJoin。
- 本轮、累计和历史快照彼此独立，保存/读取 JSON 后计数完整。
- JDBC fixture 验证主执行与替代配置执行各自保存统计，无效替代配置不会增加有效覆盖。
- 相同随机种子下，增加统计前后抽样配置完全一致，判定不受统计影响。

完整 verify 成功（约 77 秒）：50 项测试、49 项通过、1 项真实集群测试预期跳过，
0 失败、0 错误。打包、formatter、XML、Checkstyle、PMD、SpotBugs 和命名检查通过。
本次未重跑真实集群，统计测试及 JDBC fixture 不代表真实 TiFlash 集成测试。

## AI 数据准备协议与提示词回归（第一环节）

新增 `DataPreparationProtocolTest` 22 项，验证：

- 六份完整 JSON 按 observe → append_rows → stop 与对应状态包逐轮匹配，校验不修改输入。
- 两份提示词可从打包资源读取，每次消息携带完整状态，不依赖会话记忆。
- 缺失/未知字段、重复 JSON 键、非严格 JSON、类型错误及超长/过深输入被拒绝。
- 任意执行 SQL、更新/删除、查询/Schema/引擎修改和 verdict 无操作字段，其他决策名称被拒绝。
- 请求/用例/查询/版本不匹配、虚构证据、过期补行依据、未公布观测能力和重复请求被拒绝。
- 冻结或已有差异时只能 stop；观测、行数、模型调用和时间预算限制有效。
- 未知表列、生成列赋值、非法 NULL、类型不匹配、空行和重复表操作被拒绝。
- 大整数/小数以字符串保存精度；SQL 外观文本仍是数据，二进制/布尔/NULL 分别表示。
- 缺少验证能力必须说明未验证原因；部分成功和写入失败可保留为历史，停止不触发固定重试。

验证命令：

```bash
mvn -o -B -ntp -Djacoco.skip=true '-Dtest=sqlancer.tidb.mixed.*Test' verify
python3 src/check_names.py
```

72 项测试：71 项通过，1 项真实集群测试预期跳过，0 失败、0 错误。
完整构建、formatter、XML、Checkstyle、PMD、SpotBugs 和命名检查通过；
另确认两份提示词在构建的 JAR 中，文档相对路径有效。

本次没有调用真实 DeepSeek、运行真实 TiDB/TiFlash 或执行任何补行。
fixture 仅验证协议交互，不证明提示词效果、数据库约束满足或场景已在真实集群形成。

## 实际元数据与只读观测回归（第二环节）

新增 24 项离线回归：`MixedMetadataTest` 7 项、`MixedObservationTest` 16 项，
以及 `DataPreparationProtocolTest` 中 1 项实际空字符串/空白默认值回归。

- 元数据：原始精确类型、默认值、生成表达式、生成列排除、占位列兼容；
  联合唯一键顺序、前缀和表达式索引、CHECK 启用状态、采集失败及不完整约束声明。
- 实际探测 SQL 在 H2 固定数据上执行，TiDB 会话语句使用 JDBC fixture：
  LEFT/RIGHT JOIN、重复输入、NULL、WHERE、GROUP BY/HAVING、全局聚合空输入、精确频率与截断样本。
- 预算不足、Warning 和执行错误不能伪装成 0；未支持的 JOIN/WHERE 范围明确标记；
  复用/恢复快照及引擎设置、拒绝未知能力、优先测量查询事实、精确数值和二进制表示。
- 空默认值用例先运行旧校验器，失败为 expected nonempty text；修正后保留实际空字符串。

新增 `MixedFactsIntegrationTest` 2 项，在独立临时实例上真实执行：

```bash
MS_FACTS_INTEGRATION=true TIDB_HOST=127.0.0.1 TIDB_PORT=24900 TIDB_USER=root \
  mvn -o -B -ntp -Djacoco.skip=true '-Dtest=sqlancer.tidb.mixed.*Test' verify
python3 src/check_names.py
```

实例使用本机 `/home/lwm/tidb/bin/tidb-server-latest-lwm`，`TIDB_VERSION()` 校验 Git Commit
为 `b1d4142f99`，存储为 unistore。测试创建并清理独立数据库，临时启用 CHECK 后恢复原全局设置。
这证明该 TiDB 的实际元数据和 SQL 探测行为，不代表 TiKV/TiFlash 分布式集成测试。

真实测试核对：生成列、BIGINT UNSIGNED、DECIMAL(38,2)、普通/空默认值、复合/前缀唯一键、
启用与未启用 CHECK；实际状态包可以经 v1 校验并 JSON 保存/读取，null 字段保留。

计数数据固定为 `t0.k=[1,1,NULL,2]`、`t1.k=[1,1,3]`：

| 实际观测 | 结果 |
|---|---:|
| LEFT JOIN 匹配行对 | 4 |
| 左侧 / 右侧未匹配输入行 | 2 / 1 |
| LEFT JOIN 输出行（分组前） | 6 |
| 左侧可重复匹配输入行 | 2 |
| 至少有两对匹配的键组 | 1 |
| GROUP BY t0.k 的组数 | 3 |
| HAVING COUNT(*) > 1 后的组数 / 这些组的输入行数 | 1 / 4 |
| WHERE FALSE 后、聚合前输入行数 | 0 |
| 原 COUNT(*) 查询是否为空 | false |

完整 verify：98 项测试，97 项通过（95 项离线、2 项真实 TiDB），
1 项原 TiFlash 集群测试预期跳过，0 失败、0 错误。
构建、formatter、XML、Checkstyle、PMD、SpotBugs 及命名检查通过。
本次没有调用 DeepSeek、执行 AI 补行或重新运行 TiFlash 四路径差分集成测试。

## 可控追加行执行器回归（第三环节）

新增 23 项人工 JSON 计划测试：MixedAppendTest 18 项离线测试、MixedAppendIntegrationTest 5 项真实 TiDB 测试。
离线测试实际执行 H2 的参数化 INSERT 和事务，仅模拟 TiDB 会话/元数据。

- 普通 INSERT 模式重复生成 200 次，排除 REPLACE、IGNORE、UPDATE 及生成列。
- 跨表计划、无主键表的重复行、SQLancer 补齐列、NULL、空文本和空二进制。
- 超预算整单拒绝，失败消耗尝试预算；新状态包不能重置内部预算，过期版本及重复请求不能重放。
- 写前复核真实生成列定义，拒绝伪造元数据、冻结状态、已有事务及历史快照。
- Warning、静默舍入、旧行意外变化及完整读取上限导致回滚，不虚报成功。
- 会话设置失败逐行返回 not_attempted；模拟 COMMIT 已生效但应答丢失，返回 unknown，
  后续行停止且要求重建，不能把已提交的数据报告为失败未写入。

真实测试使用目标 Git Commit `b1d4142f99` 的独立 unistore 临时实例，创建并清理独立数据库：

```bash
MS_APPEND_INTEGRATION=true MS_FACTS_INTEGRATION=true \
  TIDB_HOST=127.0.0.1 TIDB_PORT=24900 TIDB_USER=root \
  mvn -o -B -ntp -Djacoco.skip=true '-Dtest=sqlancer.tidb.mixed.*Test' verify
python3 src/check_names.py
```

实际核对结果：

| 输入/情形 | 结果 |
|---|---|
| BIGINT UNSIGNED 最大值 18446744073709551615 | 精确提交，生成列得到 18446744073709551616 |
| DECIMAL(38,2) 的 123456789012345678901234567890.01 | 原值精确提交 |
| 二进制 AP8=、含 SQL 外观/中文/反斜线文本 | 原值保留，文本不作为 SQL 执行 |
| 依次追加 k=2、1、3，已有唯一键 k=1 | success、failed/1062、success；原行保留，版本 0→2，扣 3 行预算 |
| DECIMAL(38,2) 输入 12.345；UNSIGNED 超界值 | 均失败，表行数及版本不变 |
| 已启用 CHECK(n>0)，依次追加 n=-1 和合法行 | failed、success，仅合法行提交 |
| 生成列赋值、超预算计划、调用方已有事务 | 写前拒绝；调用方事务仍能自行回滚 |

测试临时启用 CHECK 后恢复原全局设置。人工计划及反馈通过同一 v1 协议入口执行，无模型网络调用；
完整用法与反馈边界见 [执行器说明](data-preparation-append-executor.md)。

共 121 项测试，120 项通过（113 项离线、7 项真实 TiDB），
1 项原 TiFlash 集群测试预期跳过，0 失败、0 错误。
完整构建、formatter、XML、Checkstyle、PMD、SpotBugs、命名检查及 git diff --check 通过。
本次未接入真实模型或自动补行循环，也未重新运行 TiFlash 差分集成测试。

## 提示词与闭环行为回归（第四环节）

新增 MixedPreparationTest 的 11 项离线测试；六类固定场景使用提交的模拟响应脚本，
其他用例验证误判、重复失败拦截和预算/不确定状态边界。
每次模拟调用都读取实际打包提示词和最新完整状态，响应经 v1 校验；
H2 实际执行第三环节的参数化 INSERT、逐行事务、主键约束及第二环节的探测 SQL。
TiDB 专有会话和元数据由 fixture 模拟，不调用模型网络 API。

```bash
mvn -o -B -ntp -Djacoco.skip=true '-Dtest=sqlancer.tidb.mixed.*Test' verify
python3 src/check_names.py
python3 docs/data-preparation-loop/summarize.py
git diff --check
```

| 固定场景 | 实测闭环结果 |
|---|---|
| 共享值被 WHERE 过滤 | 初始 1→0 对；追加 3 行后测得 2 对、1 个重复键，达成 |
| 唯一约束阻止重复 | t0 主键冲突，未达成；改补 t1 后测得 2 对、1 个重复键，达成 |
| 已有足够覆盖 | 精确观测达成后 stop，无写入 |
| 仅有截断样本 | 未验证后 stop，无写入，不虚报全表事实 |
| 连续两次约束失败 | 两次均未达成，程序在第三次模型调用前终止 |
| 有意义的空结果 | WHERE 前有匹配、WHERE FALSE 后为 0，原查询为空；保持空结果目标达成，无写入 |

额外反例断言：写入成功且模型声称达成，无验证仍为 unverified；实测不足时为 not_achieved。
重写 reason、提供新观测 ID、改变 DECIMAL 尾随零均不能重复已失败提案；
旧查询/Schema/数据版本、sampled/unknown/unsupported/error 不能证明目标。
COMMIT 应答丢失后不继续观测或请求模型，目标为未验证、最终状态为 null；
空目标列表不因逻辑上的“全称为空”被误判为达成，零调用预算不调用模型。

132 项测试，124 项通过，8 项真实集群测试按环境开关预期跳过，0 失败、0 错误。
完整构建、formatter、XML、Checkstyle、PMD、SpotBugs、命名检查及 git diff --check 通过。
完整逐轮证据生成在 target/mixed-preparation；
[精简运行记录](data-preparation-loop/results.json) 保留模型预期、真实写入、实际观测和每项目标判定。
详细语义见 [闭环验证说明](data-preparation-loop.md)。

本轮未重跑真实 TiDB/TiFlash，未测试真实 DeepSeek 的策略效果；
上述结果证明固定响应下的程序语义及提示词约束已被打包，不代表真实模型一定遵循。

## 真实集群与策略对照（第五环节）

2026-11-16 在本机 22000 端口的真实 TiDB/TiKV/TiFlash/PD 集群运行，
TiDB Git Commit 为 `b1d4142f99a606aa3cd1ada6ee0caebaa84226dc`，对应目标
`v9.0.0-beta.2.pre-1854-gb1d4142f99`。模型请求和返回均为 `deepseek-flash`。
每用例保存组件完整版本、会话、快照、观测 SQL、EXPLAIN/ANALYZE、结果多重集及 API usage。

正式实验为种子 0—5 × RANDOM/SHARED/AI，共 18 个用例，全部完成且保留，没有筛掉失败提案。
导出程序已核对每个种子的 SQL、实际 Schema、完整初始行、全部四种配置和预算一致；
每用例四条引擎路径使用同一快照，三组之间各取写后快照。
三组均限制 6 行尝试、96 次探测、120 秒准备，AI 至多 3 次调用；
基线复用现有 RANDOM/SHARED 值策略，统一普通 INSERT，不等同于旧 DML 全流程吞吐比较。

| 正式汇总（6 个用例/组） | RANDOM | SHARED | AI |
|---|---:|---:|---:|
| append 计划执行 / 提案 | 6/6 | 6/6 | 3/5 |
| 可验证追加目标达成 / 可验证追加目标 | 2/6 | 2/6 | 3/5 |
| 最终固定目标达成 / 全部用例 | 2/6 | 2/6 | 4/6 |
| 追加目标未验证 | 0 | 0 | 0 |
| 写入成功行 / 失败行 | 36/0 | 36/0 | 15/0 |
| 新增观测场景次数 | 15 | 16 | 12 |
| 新增有效场景次数 | 12 | 13 | 12 |
| 丢失场景次数 | 1 | 2 | 2 |
| 成功补行但无新增场景 / 收益未验证行 | 0/0 | 0/0 | 0/0 |
| 重复失败提案拦截 | 0 | 0 | 0 |
| 有效 / 尝试引擎路径 | 20/24 | 20/24 | 20/24 |
| 准备耗时合计（秒） | 14.688 | 14.895 | 43.509 |
| 总耗时合计（秒，含副本等待） | 159.377 | 207.595 | 218.641 |
| 真实模型调用 | 0 | 0 | 9 |
| 估算 USD | 0 | 0 | 0.011258490 |

“场景次数”是种子×场景桶的新增次数，不能等同于独立查询数或缺陷数；
“有效”另要求本用例全部选定引擎路径有效。
全部用量已返回：AI prompt 67,123 tokens、completion 5,433 tokens，按每次记录的缓存命中/未命中单价估算，非账户账单。
准备耗时反映策略成本；总耗时受首组副本建立影响，已轮换组顺序，不能据此直接归因于策略。

逐种子的固定目标判定为：

| 种子及场景 | RANDOM | SHARED | AI |
|---|---|---|---|
| 0 INNER JOIN、共享值被过滤 | 未达成 | 未达成 | 达成 |
| 1 LEFT JOIN | 未达成 | 未达成 | 达成 |
| 2 t0 唯一键 | 未达成 | 未达成 | 未达成；8 行提案超预算，整单拒绝 |
| 3 已有重复匹配和 NULL | 达成 | 达成 | 达成；仍选择补行，增加其他场景 |
| 4 GROUP BY | 未达成 | 未达成 | 未达成；7 行提案超预算，整单拒绝 |
| 5 WHERE FALSE | 达成 | 达成 | 达成；直接 stop，未补行 |

一般用例目标为 WHERE 后至少 2 对匹配、至少 1 个重复匹配键；种子 5 为保持空查询及 0 对匹配。
种子 3、5 初始已达成，最终成功率包含它们，不能声称全部成功都是 AI 新创造的覆盖。
AI 两次超预算提案均占执行率与目标判定分母，未写入、不自动缩减、不额外重试。
本轮没有实际约束写入失败或重复失败提案，故相关 0 只能表示本轮未触发；
连续失败拦截仍由第四环节固定脚本回归证明。

15 个非 WHERE FALSE 用例的四路径均有效、基础表预检一致、六对比较一致。
三个 WHERE FALSE 用例保持有意义的空结果，但优化器消除了表访问，四路径均为 INVALID_CONFIGURATION，
不算 PASS 或有效引擎覆盖；三组由此各少 4 条有效路径。
数据场景和引擎分配比例/JOIN 两侧组合在汇总中独立保存。

结论限于这六种受控小表：**AI 改善了过滤后重复匹配目标的达成情况，但新增有效场景总次数未超过 SHARED，
且准备耗时和调用成本更高。** AI 在种子 0、1 新增了重复匹配类场景，基线也新增了未匹配、NULL 等其他场景；
不能仅以非空结果增加、总目标成功率或单一桶数宣布 AI 全面提升测试覆盖。
本轮没有证明长程随机测试、复杂约束/多表查询或真实缺陷发现率提升。

证据与复算方法见 [实验记录](preparation-benchmark-20261116/README.md)，
指标定义和执行命令见 [联调说明](data-preparation-benchmark.md)。
首次试验期间构建替换了运行 JAR，导致后续资源读取失败；已停止并从固定 JAR 完整重跑，
未将两次结果拼接。压缩证据额外保留探索试跑及中止记录，正式汇总仅取 primary。
两部分已知额外模型费用分别为 USD 0.003145206 和 0.002525562，不计入上表策略对照。

回归测试阶段共 144 项，138 项通过（135 项离线、3 项真实 TiDB），6 项按环境开关跳过，
0 失败、0 错误。新增 11 项客户端/指标/最低审计集合回归及 1 项真实整数格式回归。
真实测试核对 ZEROFILL `0/12/65535` 和 BIGINT UNSIGNED 最大值的规范字符串及协议状态包。
格式化后完成 `verify -DskipTests`，formatter、XML、Checkstyle、PMD、SpotBugs、命名检查及 diff 检查通过。
可复现命令：

```bash
MS_FACTS_INTEGRATION=true TIDB_HOST=127.0.0.1 TIDB_PORT=22000 TIDB_USER=root \
  mvn -o -B -ntp -Djacoco.skip=true '-Dtest=sqlancer.tidb.mixed.*Test' test
mvn -o -B -ntp -Djacoco.skip=true -DskipTests verify
python3 src/check_names.py
git diff --check
```

额外用 `MS_DATA_PREPARATION=DEEPSEEK`、随机种子 42、1 个查询、至多 2 张表验证原 SQLancer Oracle 入口。
首次运行暴露 ZEROFILL 的 `"00000"` 样本违反规范整数格式；最终观测层改用 BigInteger 规范化，
上述真实整数回归同时确保无符号 BIGINT 精度。
修复后该入口完成 3 次真实调用，两个追加计划分别成功写入 2、1 行，再 stop，写后重新观测、基础表预检 PASS。
自由文本目标仍为 unverified；该随机查询涉及 TiFlash 不支持的 `bit_and`/`nulleq` 和文本转整数 Warning，
仅 1/4 路径有效，最终 INCONCLUSIVE，未宣称差分 PASS 或发现缺陷。
这部分费用 USD 0.004989918 单独保存，不计入正式六种子对照。
入口修复前后证据均在压缩记录中；manifest 分别记录正式配对和修复后 Oracle 的固定 JAR 哈希。

## 计划拒绝反馈回归（2026-11-17）

新增 9 项离线回归：7/8 行提案超过 6 行预算后修订、字段/观测/证据 ID/标识/版本/格式纠错、
最低审计观测超额后修订、可选择 observe 后 stop、调用/时间耗尽，以及传输错误/冻结/已发现差异时立即停止。
原有未知提交结果和重复失败行提案终止测试继续通过。

两份超额用例的真实 H2 执行结果为：

```text
7 行或 8 行提案 → 写前拒绝（0 次写入，行预算仍 6，调用剩 2）
→ 模拟模型读到结构化拒绝反馈，改为 t0 补 1 行、t1 补 2 行
→ 3 行提交成功 → 实测 WHERE 后 2 对匹配、1 个重复键 → stop
```

另核对 0/1/6/7/12 行配置：RANDOM/SHARED 的所有表合计行数与同一个配置一致，零行不生成空 INSERT。
精简逐轮证据见 [记录](data-preparation-loop/rejection-recovery-results.json)，完整测试输出位于 target/mixed-preparation。
没有调用真实模型、没有重跑 TiDB/TiFlash 或改写前一日的真实实验指标。

本轮 153 项测试，144 项通过、9 项真实集群测试按开关跳过，0 失败、0 错误。
完整 verify、formatter、XML、Checkstyle、PMD、SpotBugs、命名及 diff 检查通过。

当前远程构建依赖已升级，使用 JDK 17 与 Maven 3.9.11 验证。
配套清理新 ECJ 检出的 20 处旧多余 import，固定仓库原格式配置，并处理 5 处新格式化器的注释空白差异；
保留已升级的依赖版本，不改变其他数据库的运行逻辑。xml-maven-plugin 1.4.0 要求 Maven 至少 3.9。
新 ECJ/formatter 的 class 文件版本为 61，CI 构建 JDK 同步改为 17；项目编译目标仍为 Java 11。

```bash
mvn -B -ntp -Djacoco.skip=true '-Dtest=sqlancer.tidb.mixed.*Test' verify
python3 src/check_names.py
git diff --check
```

## AI 结构化目标验证（2026-11-17）

- 新增 7 项回归，实际执行 H2 INSERT/事务/只读探测，模型响应与 TiDB 会话/元数据由 fixture 模拟。
- Oracle 相同的空固定 Requirement 用法，现在可验证模型所选的结构化目标；固定断言仍独立保存。
- 匹配数 2、重复键 1 使固定断言通过；模型额外要求 NULL 和外连接未匹配输出至少各 1，实测均为 0，整体正确记为 not_achieved，stop 不清除失败目标。
- 覆盖自动验证探测计费、超预算执行前拒绝及修订、非法字段/比较/类型、超过 64 位整数、旧版本/样本/错误/冲突证据、外连接与分组字段、提交不确定及空结果停止。
- 160 项测试中 151 项通过、9 项真实集群测试按环境开关跳过；formatter/XML/Checkstyle/PMD/SpotBugs 完整检查通过，SpotBugs 0 bugs；命名和 git diff --check 通过。
- 本轮没有调用真实 DeepSeek 或运行真实 TiDB/TiFlash，不改写第五环节真实实验数据，不声称真实模型覆盖率已改善。

验证命令（Maven 3.9.11 / JDK 17，缓存依赖离线执行）：

```bash
/tmp/mix-retry-maven/bin/mvn -o -B -ntp -Djacoco.skip=true '-Dtest=sqlancer.tidb.mixed.*Test' verify
python3 src/check_names.py
git diff --check
```

协议与完整响应见 [结构化目标说明](data-preparation-structured-goals.md)，
实际逐项反馈见 [回归记录](data-preparation-loop/structured-goals-results.json)。
