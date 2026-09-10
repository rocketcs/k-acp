---
name: markdown-document-export
description: 将 Markdown 内容排版并导出为可下载的 Word、Excel 或 PDF 文件。
---

# 中文文档排版与导出

当用户要求生成、下载或重新排版 Word、Excel、PDF 文档时使用本技能。模型负责组织内容和版式意图，`export_document` 工具负责生成二进制文件。

## 必须遵守

1. 先将内容整理为标题、段落、列表和表格；表格只用于可比较的行列数据。
2. 调用 `export_document`，参数使用：`format`（`docx`/`xlsx`/`pdf`）、`title`、Markdown `content`，以及可选的 `file_name`。
3. 不得把 Markdown 标记原样写入成品：`##`、`|---|`、反引号和链接语法必须转换或清理。
4. Word 使用原生标题、项目符号和表格；Excel 使用真实单元格、表头样式、合适列宽；PDF 使用固定版式、边框表格和可嵌入中文 TrueType 字体。
5. 工具返回 `download_url` 后，最终回复必须包含真实链接，例如：`[文件名](/api/attach/download/{attachment_id})`。不能只写“可点击下载”。
6. 工具失败时如实报告错误，不伪造链接；旧文件不会自动更新，必须重新生成。

详细格式建议见 [references/format-guidelines.md](references/format-guidelines.md)。
