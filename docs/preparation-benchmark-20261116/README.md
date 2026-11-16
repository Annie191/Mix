# 2026-11-16 真实 DeepSeek 配对实验

目标为 TiDB `v9.0.0-beta.2.pre-1854-gb1d4142f99`，实际提交
`b1d4142f99a606aa3cd1ada6ee0caebaa84226dc`，本机 22000 端口的 TiDB/TiKV/TiFlash/PD 集群。
模型、组件完整版本、配置、UTC 请求时间和单价保存在逐用例记录中。

- [summary.json](summary.json)：仅正式配对实验的全部指标，含显式分子/分母。
- [manifest.json](manifest.json)：固定运行 JAR 和证据哈希、范围及中止原因。
- [records.json.gz](records.json.gz)：完整 JSON 压缩保存；没有用摘要替代原始观测或删除失败用例。
- [export.py](export.py)：核对配对 SQL、Schema、初始行、配置、预算和同一用例路径快照后导出。

解压与复算：

```bash
python3 - <<'PY'
import gzip, json
from pathlib import Path
root = Path('docs/preparation-benchmark-20261116')
records = json.loads(gzip.decompress((root / 'records.json.gz').read_bytes()))
Path('/tmp/mix-primary.json').write_text(json.dumps(records['primary']))
print({part: len(cases) for part, cases in records.items()})
PY
java -cp target/sqlancer-2.0.0.jar \
  sqlancer.tidb.mixed.TiDBMixedBenchmarkMetrics \
  /tmp/mix-primary.json /tmp/mix-summary.json
python3 - <<'PY'
import json
from pathlib import Path
assert json.loads(Path('docs/preparation-benchmark-20261116/summary.json').read_text()) == \
       json.loads(Path('/tmp/mix-summary.json').read_text())
print('Metrics reproduce exactly')
PY
```

`primary` 包含种子 0—5 × RANDOM/SHARED/AI，共 18 个用例，全部纳入汇总。
`exploratory_smoke` 是正式比较前的一次真实 AI 试跑，仅用于联调证据。
`aborted_build_overlap` 保留首次配对运行的完整及部分记录：运行中 Maven 替换 JAR，导致后续提示词资源读取失败。
发现后停止，用不可覆盖的 JAR 副本完整重跑六个种子；正式汇总不拼接两次实验。
探索和中止实验的调用费用不计入正式策略比较，各自 API 审计仍保留。

另存 `oracle_before_zerofill_fix` 与 `oracle_smoke`，用于 SQLancer 原 Oracle 入口的补充联调，
不加入六种子效果对照。前者记录 ZEROFILL 整数样本导致的调用前格式错误；
最终代码将整数事实规范化为十进制字符串并保持精度。
正式实验使用 BIGINT，没有触发该格式问题；manifest 区分正式运行 JAR 与后续修复后的 Oracle JAR。

这是六种受控小表的描述性比较：普通 INSERT 和相同行预算用于控制变量，
RANDOM/SHARED 复用原值策略，未比较整个旧随机建库/查询/DML 流程。
指标含义、资源边界与运行方法见 [联调说明](../data-preparation-benchmark.md)，
实际结果与局限见 [验证记录](../mixed-storage-validation.md#真实集群与策略对照第五环节)。
