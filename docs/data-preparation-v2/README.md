# 准备阶段动作接口 v2

状态包和落盘仍用 v1，模型默认通过 DeepSeek Beta 的三个 strict Tool Calls 提交动作。
接口由实际表列、生成列、类型、可空性、观测参数及目标字段动态生成；一次只允许一个动作。
入口：`TiDBMixedActionSchema.tools(state)`。Schema 是格式依据，程序继续校验版本、权限、预算、重复失败和数据库约束。

| 动作 | 参数 |
|---|---|
| observe | reason、evidence_ids、goal、observation_requests |
| append_rows | reason、evidence_ids、goal、operations、structured_goals、expected_effect、verification_requests、feasibility |
| stop | reason、evidence_ids、stop_reason |

strict 要求对象字段全部必填、禁止额外字段。三种动作不再互填无关字段或 null。
表行用列名对象；生成列不进入写入 Schema。integer/decimal 保留字符串精度，binary 使用完整字节的 Base64。
全部可写列必须出现；不指定值时使用 `{"type":"sqlancer"}`，程序移除此标记后复用 SQLancer 生成值。
每行至少明确指定一个列值。该标记不是写入值，也不能保证满足模型目标。模型不能提交 SQL。
参数枚举直接用字符串；例如 `"stage":"input"`，不是 `{"type":"enum","value":"input"}`。

完整交互示例（目录存在 `j0_pairs_after`，两表均只有可写整数列 k）：

```json
{"name":"observe","arguments":{"reason":"需要精确匹配数","evidence_ids":[],"goal":"判断是否值得补行","observation_requests":["j0_pairs_after"]}}
```

程序返回当前数据版本的实际观测。若匹配数为 0，模型可以选择以下计划，也可以继续观察或停止：

```json
{
  "name":"append_rows",
  "arguments":{
    "reason":"两侧都缺少通过过滤的完整键",
    "evidence_ids":[],
    "goal":"增加 WHERE 后的匹配",
    "operations":[
      {"table":"t0","rows":[{"k":{"type":"integer","value":"12"}}]},
      {"table":"t1","rows":[{"k":{"type":"integer","value":"12"}}]}
    ],
    "structured_goals":[{"capability_id":"j0_pairs_after","field":"count","comparison":"at_least","expected":"1"}],
    "expected_effect":"预期至少一对匹配，等待程序验证",
    "verification_requests":["j0_pairs_after"],
    "feasibility":{
      "join_witness":"t0.k=12 与 t1.k=12 满足完整等值连接",
      "where_witness":"12 满足 k>10",
      "group_witness":"本例没有 GROUP BY",
      "constraint_check":"先核对唯一键中不存在 12；仍以实际 INSERT 反馈为准",
      "measurement":"精确 j0_pairs_after.count >= 1"
    }
  }
}
```

程序逐行追加，再自动执行验证，不需要额外模型调用。插入失败或统计不达标不能判目标成功。
下一包带实际写入、当前版本事实、模型目标的 achieved/not_achieved/unverified 结果和剩余预算。

```json
{"name":"stop","arguments":{"reason":"程序实测目标已达成","evidence_ids":[],"stop_reason":"停止补行并继续差分"}}
```

这里展示函数名和参数；实际 API 的 `tool_calls[].function.arguments` 是 JSON 字符串。
请求 ID、查询和数据版本由程序绑定，不由模型填写。不能据模型文字宣告差异或缺陷。

字段错误反馈示例：

```json
{"path":"$.observation_requests[2].parameters.stage","code":"TYPE_MISMATCH","expected":"string","received_type":"object","allowed_values":["input"],"example":"input","execution_started":false,"attempted_rows":0,"retry_allowed":true}
```

格式修订额度与有效决策额度分开：默认有效决策 3、修订 3、HTTP 总调用 6。
全部调用仍扣时间、实际 token 和配置单价估算费用；默认 120 秒、500000 token、0.25 美元估算上限。
请求前按 UTF-8 字节数和最大输出预留 token/费用；返回后按 API usage 扣除。usage 缺失不能当零费用继续调用。
有效计划后程序观测不消耗模型调用。达到费用预留限制可能提前停止；费用是配置估算，不是账单承诺。
写入提交状态不确定仍要求重建；模型不可用冻结已有数据后继续差分。

补行必须给出完整连接、WHERE、全部分组键、约束及验证依据。
程序只在实际元数据完整、每表完整非 NULL 唯一键均进入 GROUP BY、且为内连接时证明单组最多 1 行；
此时拒绝要求组大小大于 1 的结构化目标。其余目标保持未知，依赖数据库实测，不解析任意自然语言。
NATURAL JOIN 列出全部同名键和实际类型，禁止把 BLOB 首字符视为完整键匹配证明。

`MS_PREPARATION_TRANSPORT=strict` 为默认；显式 `json` 保留旧接口。
`DEEPSEEK_THINKING=enabled|disabled`，默认 disabled；thinking 时使用 API 要求的 tool_choice=auto。
受控 Benchmark 新增 AI_THINKING 独立组，所有组核对初始 SQL/实际 Schema/数据指纹，再分别报告协议、目标、查询阶段覆盖、引擎覆盖和费用。

`real-responses-27.json` 固定了上一轮 27 份真实响应和当时状态：5 份原合规响应继续通过，22 份拒绝均定位到字段。
回归中的人工格式修订均通过校验，**不代表模型自行修订成功或数据库覆盖达成**。
三个真实目录的动态 Schema 已通过 Beta HTTP 200 兼容性探测；这仅验证 API 接受结构。

官方依据：[Tool Calls strict](https://api-docs.deepseek.com/guides/tool_calls/)、
[Chat Completion](https://api-docs.deepseek.com/api/create-chat-completion/)。
