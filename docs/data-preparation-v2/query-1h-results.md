# 一小时真实 DeepSeek 随机查询测试

2026-11-18 UTC 15:02:33–16:02:33 持续启动用例，最后一例于 16:05:28 完成。
启动窗口 3600 秒，收尾 175.206 秒；没有强制终止进程。
被测提交 `d3d87eba`，目标 TiDB `b1d4142f99`，本机端口 22000。
使用真实 `deepseek-flash`、Beta strict tools、thinking disabled，最大输出 4000 token。
每次准备最多追加 12 行、3 次有效决策、3 次格式修订、6 次 HTTP 调用、120 秒；token/费用继续受限。

| 项目 | 结果 |
|---|---:|
| 随机种子/完整 case.json | 27 / 26 |
| 真实 API 调用 / HTTP 200 | 72 / 72 |
| 通过校验 / 拒绝 | 70 / 2 |
| 合规动作 observe / append_rows / stop | 61 / 6 / 3 |
| 补行计划提出 / 执行 | 6 / 6 |
| 成功追加 / 失败行 | 25 / 0 |
| 完整补行目标最终达成 / 未达成 | 4 / 2 |
| 逐项目标达成 / 未达成 | 57 / 8 |
| 有效路径对比较 / 发现不一致 | 106 / 0 |
| 配置单价估算费用 | $0.137860908 |

两份拒绝均为 `$.evidence_ids` 引用不存在的证据，返回 `UNKNOWN_EVIDENCE_ID` 及合法 ID。
seed 50、68 随后的响应均修订通过，没有因拒绝立即退出。
本轮没有复现行数组、stage 对象或 observe 无关字段的格式错误；这不保证以后没有。

26 个用例最终状态：PASS 7、INCONCLUSIVE 13、EXECUTION_ERROR 3、INVALID_CONFIGURATION 2、GENERATION_REJECTED 1。
PASS 中 6 个非空、1 个空结果。INCONCLUSIVE 涉及 SQL Warning（11 例）和精确 Oracle 不处理的浮点结果（3 例，原因可重叠）。
无有效扫描的配置不能算通过。23 个进入差分的 revision 均取得正 TSO，各路径记录同一 TSO；106 对有效结果全部相同。
三次执行错误分别是两次 DDL 超时、一次读取 TiFlash 副本状态时 PD 报无 leader。

查询阶段新增 **11 次场景命中，来自 4 个 revision**，按用例重复计数：

| seed | 新增场景 | 差分状态 |
|---|---|---|
| 54、56 | 各新增 WHERE 后匹配、重复匹配、WHERE TRUE | PASS |
| 57 | WHERE 后匹配、重复匹配 | INCONCLUSIVE，存在 Warning |
| 66 | WHERE 输入 NULL、WHERE TRUE、WHERE UNKNOWN | PASS；只有这些新增项有精确前后证据，整体覆盖评估不完整 |

其中引擎路径全部有效的新增命中为 9 次。基础数据存在性和查询场景分别统计。
seed 66 的其他未测项仍为未知，不能把局部增量解释为完整覆盖。
25 行写入成功也不代表全部有效：seed 63、64 追加共 8 行，没有产生已测查询场景增量。
21 次准备以预算耗尽结束，observe 占多数；接口改善后，观察与补行机会的分配仍有改进空间。

seed 58 在建表阶段遇到“分区唯一索引需要 GLOBAL”的新错误提示，未进入 Oracle，因此没有 case.json，完整进程日志仍保留。
已将该提示加入建表预期错误；修复后用相同 seed 单独重跑建表，退出码为 0。
一小时期间没有替换被测 jar，这项补修不追记为一小时测试成功。
26 个证据目录均有 case.json，没有日志保存失败或此前代理项/空标签异常。

本轮证明真实调用、拒绝修订、追加和自动验证能在随机查询中运行，并测到部分收益；没有发现结果不一致。
它没有同输入的 RANDOM/SHARED 对照，不能单凭这轮证明 AI 更好。
受控对照单独见 [六用例结果](benchmark-results.md)；完整计数、种子和 jar 摘要见 [机器可读记录](query-1h-results.json)。
原始日志位于项目 `logs/query-1h-deepseek-20261118T150233Z/`，没有放到用户主目录的新实验文件夹。
