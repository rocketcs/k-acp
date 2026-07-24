# 商业标书高召回检索应用包

本目录把“商业标书智能体”的普通标讯检索升级为应用层高召回工作流。它不修改 Java 后端源码、数据库结构、共享 `tender-search`、旧版 `resolve_tender_source_urls`、外部标讯 API 或模型配置。

## 实现边界

- `TenderHighRecallSearchTool.java`：工作流内部执行器。根据 QueryPlan 完成最多三轮基础检索和一次按需纠偏，每轮只取 50 条；返回结果在本地完成硬条件复核、同公告去重、项目生命周期归并、A/B/C 分层和稳定排序。计费型搜索不自动重试，原始响应和全文不进入模型上下文。
- `TenderSourceUrlResolverV2Tool.java`：工作流内部链接解析器。最多批量处理 20 条，只读取搜索结果显式原文字段或本地解析知了聚合页，不调用逐条计费详情接口；按 `record_key` 返回并执行原文身份/可访问性校验。
- `CommercialTenderHighRecallWorkflowTool.java`：`default-tender` 唯一可见入口。当前内置 WorkflowTool 会丢弃调用方的 START 参数，因此用应用层入口把真实 `question`、`priorState`、`companyProfile` 交给已发布工作流；未改后端源码。
- `workflow.json` 与 `workflow-code/`：查询计划、确定性检索、当前批次切片、强制链接解析、回答上下文隔离、状态封装的单链路工作流。
- `.codex/skills/tender-high-recall-search/`：QueryPlan、检索分层、连续状态和输出契约。共享 `tender-search` 仍是接口字段契约，不被覆盖。
- `.codex/skills/commercial-tender-followup-curator/`：根据当前项目、采购方、地区、企业画像和已完成动作，精选 3 至 4 个可点击的业务追问。它不执行检索、不暴露工具，也不补造事实。

## 固定应用资源

| 资源 | 编号/ID | 可见范围 |
|---|---|---|
| 高召回执行器 | `execute_tender_high_recall_v1` / `2079122200000000101` | 仅工作流内部 |
| 源链接解析 v2 | `resolve_tender_source_urls_v2` / `2079122200000000102` | 仅工作流内部 |
| 智能体入口 | `commercial_tender_high_recall_search` / `2079122200000000103` | 仅 `default-tender` |
| 高召回 Skill | `tender-high-recall-search` / `2079122200000000201` | 仅 `default-tender` |
| 追问精选 Skill | `commercial-tender-followup-curator` / `2079122200000000202` | 仅 `default-tender` |
| 专用提示词 | `2079122200000000301` | 仅 `default-tender` |
| 高召回工作流 | `commercial-tender-high-recall-v1` / `2079122200000000401` | 由入口工具调用 |

内部执行器、解析器和高召回规划 Skill 不会写入 `agent_tools`、`agent_workflows` 或共享 `skill_tools`。只有入口工具和追问精选 Skill 绑定 `default-tender`。

## 安装与验证

在仓库根目录执行：

```bash
rtk proxy bash docs/operations/commercial-tender-high-recall/verify-local.sh --static
rtk proxy bash docs/operations/commercial-tender-high-recall/apply-local.sh
rtk proxy bash docs/operations/commercial-tender-high-recall/verify-local.sh --live
rtk proxy bash docs/operations/commercial-tender-high-recall/verify-local.sh --functional
```

安装脚本会：

1. 核对 `default-tender`、共享 Skill、公共 HTTP 工具和当前模型恰好存在一份。
2. 把原提示词 ID、模型 ID、共享 Skill 内容摘要和旧解析器代码摘要保存到 `~/.k-acp-backups/commercial-tender-high-recall-before.json`。
3. 幂等写入新的应用资源和专用绑定，不创建或修改数据库表。
4. 通过 K-ACP API 校验并发布工作流，使运行时缓存拿到真实已验证定义。

`--static` 会编译三个动态 Java 工具和全部工作流 CODE 节点，检查受限包、工作流强制链路、上下文隔离、凭据字面量，并执行 200 条 `record_key`/私网拒绝回归以及 Unicode `sourceUrl` 提取测试。

`--functional` 会真实执行“广东最近一个月服务器采购”工作流，验证 QueryPlan、积分受控检索、当前批次不超过 20、本地链接解析、回答和 continuationState。

## 回滚

```bash
rtk proxy bash docs/operations/commercial-tender-high-recall/rollback-local.sh
```

回滚只删除上述固定 ID 的应用资源和运行记录，并把 `default-tender` 恢复到安装前提示词。共享 `tender-search`、旧解析器、模型配置和其他智能体不受影响。

## 验收口径

- 所有查询轮次完整继承地区、日期、金额、阶段、主体角色和截止状态。
- 三轮基础检索每轮最多调用一次搜索接口并读取声明总数；声明总数超过已加载数量或任一轮失败时明确 `is_complete=false`。
- 去重优先级为 `bid_id → uniq_key → aggregate_url → fingerprint`；生命周期阶段关联但不互相删除。
- A/B 为主结果，C 独立待核实；当前批次最多 20，剩余结果通过 continuationState 继续。
- 回答模型只看到当前投影批次，不看到原始 API 页、完整候选、公告全文或 HTML。
- 源链接按 `record_key` 合并；逐条计费详情调用为 0，错误原文链接和键映射错误目标均为 0。
- 每轮结果末尾提供 3 至 4 个可点击的业务追问；当前核心项目深化、横向机会或采购方洞察优先，存在剩余结果时才可把继续下一批作为补充项，且不触发从第一页重新搜索。
- 本地部署将高召回工具时限设为 240 秒；这是运行配置，不改变后端业务代码。它避免正常的“计划 + 受控检索 + 批量原文解析”在默认 60 秒时限内被错误中断。
