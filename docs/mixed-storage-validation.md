# 混合存储 Oracle 验证记录

验证日期：2026-11-16（UTC）。运行环境：Linux amd64，OpenJDK 17.0.15；项目编译目标为 Java 11。
SQLancer 基线与目标 TiDB 源码提交见 [使用说明](mixed-storage.md)。

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

DeepSeek 真实服务未调用；相关离线测试使用本地模拟 HTTP 服务，不产生 API 费用。
