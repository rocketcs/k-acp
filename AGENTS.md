## graphify

本项目的知识图谱位于 `graphify-out/`，其中包含核心节点、社区结构和跨文件关系。

当用户输入 `/graphify` 时，必须先使用已安装的 Graphify 技能或相关说明，再执行其他操作。

规则：

- 处理代码库相关问题时，如果 `graphify-out/graph.json` 已存在，应先运行 `graphify query "<问题>"`。使用 `graphify path "<A>" "<B>"` 查询关系，使用 `graphify explain "<概念>"` 聚焦解释某个概念。这些命令会返回限定范围的子图，通常比读取 `GRAPH_REPORT.md` 或直接使用 grep 搜索更精简。
- 钩子或增量更新后，`graphify-out/` 中出现未提交的变更属于正常现象；不能因此跳过 Graphify。只有当任务涉及过期或错误的图谱输出，或者用户明确要求不使用 Graphify 时，才可跳过。
- 如果 `graphify-out/wiki/index.md` 存在，进行整体浏览时应优先使用它，而不是直接遍历源代码。
- 只有在进行整体架构审查，或者 `query`、`path`、`explain` 无法提供足够上下文时，才读取 `graphify-out/GRAPH_REPORT.md`。
- 修改代码后，运行 `graphify update .` 以保持图谱为最新状态。该操作仅更新 AST，不产生 API 费用。
