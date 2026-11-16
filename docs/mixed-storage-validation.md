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
