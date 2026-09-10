# 医保问数快捷问题池 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提供基于真实医疗目录数据的 30 条快捷问题，并在空状态随机展示其中 8 条。

**Architecture:** 问题池继续驻留在 `GraphifyDataQueryChat.vue`，作为随前端包交付的静态数据，不引入接口或浏览器存储。按药品、耗材、医疗服务三组维护，每组 10 条；空状态沿用既有洗牌函数，仅将抽取数量调整为 8。每条问题仅使用 Wren MCP 已验证的目录实体、字段及口径；统一由引导文案说明返回结构化表格，避免问题文案重复。

**Tech Stack:** Vue 3、TypeScript、Node.js 内置测试、Docker Compose、Nginx。

## Global Constraints

- 问题池必须包含药品、耗材、医疗服务各 10 条，共 30 条。
- 每次空状态仅展示随机抽取的 8 条。
- 每条问题必须明确查询对象/范围和关键字段；由空状态统一说明会返回结构化表格数据，避免每条重复“以表格输出”。
- 不新增后端接口、localStorage/sessionStorage、依赖或页面布局改动。
- 仅重建 `apboa-frontend`，不得重启后端或数据容器。

---

### Task 1: 静态问题池与空状态抽取

**Files:**
- Modify: `ui/src/features/graphify-data-query/GraphifyDataQueryChat.vue:91-157`
- Modify: `ui/src/features/graphify-data-query/GraphifyDataQueryChatEmptyState.test.ts:5-35`

**Interfaces:**
- Consumes: `shuffle<T>(list: readonly T[]): T[]`，返回输入列表的随机排列。
- Produces: `QUICK_QUESTIONS`，一个包含 30 个中文查询文本的只读数组；`initializeQuickQuestions()` 将其随机抽取为 8 条并赋给 `quickQuestions`。

- [x] **Step 1: 写入失败的回归测试**

在 `GraphifyDataQueryChatEmptyState.test.ts` 添加以下测试，验证问题数量、三个目录示例和抽取数量：

```ts
test('quick questions provide 30 scoped table-output examples and show eight at a time', () => {
  const questionBlock = component.match(/const QUICK_QUESTIONS:[\\s\\S]*?\\n\\]/)?.[0] ?? ''
  assert.equal((questionBlock.match(/^  '/gm) ?? []).length, 30)
  assert.match(questionBlock, /查询“复方氯己定含漱液”的生产企业和规格/)
  assert.match(questionBlock, /查询“覆膜气管支架”的分类、材质和生产企业/)
  assert.match(questionBlock, /查询“互联网首诊（普通医师）”的支付类别和省级一档最高限额/)
  assert.doesNotMatch(questionBlock, /dosage_form|剂型/)
  assert.match(component, /quickQuestions\\.value = shuffle\\(QUICK_QUESTIONS\\)\\.slice\\(0, 8\\)/)
  assert.match(component, /选择一个快捷问题，快速查看结构化表格数据。/)
})
```

- [x] **Step 2: 运行测试并确认失败**

Run:

```bash
cd ui && node --experimental-strip-types --test src/features/graphify-data-query/GraphifyDataQueryChatEmptyState.test.ts
```

Expected: 新测试失败，因为当前问题池只有 24 条，且 `slice(0, 16)`。

- [x] **Step 3: 以 30 条静态问题替换问题池**

将 `QUICK_QUESTIONS` 替换为以下只读数组，并把初始化的抽取数量设为 8：

```ts
const QUICK_QUESTIONS: readonly string[] = [
  '查询“复方氯己定含漱液”的生产企业和规格',
  '查询“复方氯己定含漱液”的支付类别和最高价格',
  '查询“聚维酮碘含漱液”的生产企业与规格',
  '查询“西吡氯铵含漱液”的支付类别与最高价格',
  '查询“氯己定苯佐卡因含片”的规格和生产企业',
  '查询“西地碘含片”的支付类别和最高价格',
  '查询“复方氢氧化铝片”的生产企业与规格',
  '查询甲类药品的名称、生产企业和最高价格',
  '查询乙类药品的名称、规格和生产企业',
  '查询药品目录中的价格语义和最高价格',
  '查询“覆膜气管支架”的分类、材质和生产企业',
  '查询“覆膜气管支架”的支付类别和二级分类',
  '查询“一次性使用支气管定位支架”的材质、企业和支付类别',
  '查询“镍钛记忆合金自扩张式医用内支架(气道支架)”的分类和企业',
  '查询“气管支架”的材质、企业和支付类别',
  '查询呼吸介入材料的目录名称、材质和生产企业',
  '查询非血管介入治疗类材料的二级分类和支付类别',
  '查询材质为不锈钢的耗材名称、分类和生产企业',
  '查询材质为合金的耗材名称、分类和生产企业',
  '查询乙类耗材的名称、二级分类和生产企业',
  '查询“互联网首诊（普通医师）”的支付类别和省级一档最高限额',
  '查询“互联网首诊(副主任医师)”的支付类别和省级一档最高限额',
  '查询“互联网首诊(主任医师)”的支付类别和省级一档最高限额',
  '查询不同医师级别的互联网首诊项目与最高限额',
  '查询“门诊诊查费（普通门诊）”的支付类别和自付比例',
  '查询甲类医疗服务项目的名称、自付比例和最高限额',
  '查询丙类医疗服务项目的名称、自付比例和最高限额',
  '查询政策号为“豫医保办〔2025〕51号”的医疗服务项目',
  '查询名称含“诊查”的医疗服务项目及支付类别',
  '查询医疗服务项目的自付比例和省级一档最高限额',
]

function initializeQuickQuestions() {
  quickQuestions.value = shuffle(QUICK_QUESTIONS).slice(0, 8)
}
```

同时将空状态引导文案替换为：

```vue
<p>选择一个快捷问题，快速查看结构化表格数据。</p>
```

- [x] **Step 4: 运行定向测试并确认通过**

Run:

```bash
cd ui && node --experimental-strip-types --test src/features/graphify-data-query/GraphifyDataQueryChatEmptyState.test.ts
```

Expected: 退出码为 0，所有空状态与图谱入口测试通过。

- [ ] **Step 5: 提交代码与测试（本次不提交，避免包含工作区已有改动）**

```bash
git add ui/src/features/graphify-data-query/GraphifyDataQueryChat.vue ui/src/features/graphify-data-query/GraphifyDataQueryChatEmptyState.test.ts
git commit -m "feat: refine graphify quick questions"
```

### Task 2: 前端验证与容器更新

**Files:**
- Modify: `docker/frontend/Dockerfile` 无修改。
- Modify: `docker/docker-compose-simple.yml` 无修改。

**Interfaces:**
- Consumes: `k-acp-local-apboa-frontend` Docker 镜像与 `apboa-frontend` Compose 服务。
- Produces: 运行且健康的 `k-acp-frontend` 容器，在 `http://127.0.0.1:23080/web/` 提供更新后的前端。

- [ ] **Step 1: 运行前端类型检查**

Run:

```bash
cd ui && npm run type-check
```

Expected: `vue-tsc --build` 退出码为 0。

- [ ] **Step 2: 更新代码图谱并检查差异**

Run:

```bash
graphify update .
git diff --check
```

Expected: Graphify 完成增量 AST 更新，差异检查没有空白错误。

- [ ] **Step 3: 仅重建前端服务**

Run:

```bash
docker compose --project-name k-acp-local --env-file docker/.env.kacp -f docker/docker-compose-simple.yml -f docker/docker-compose-kacp-local.yml build apboa-frontend
docker compose --project-name k-acp-local --env-file docker/.env.kacp -f docker/docker-compose-simple.yml -f docker/docker-compose-kacp-local.yml up -d --no-deps --force-recreate apboa-frontend
```

Expected: `k-acp-frontend` 被重新创建；其他服务和数据卷保持运行。

- [ ] **Step 4: 确认健康状态与页面连通性**

Run:

```bash
docker inspect --format '{{.State.Status}} {{.State.Health.Status}}' k-acp-frontend
curl --fail --silent --show-error --output /dev/null http://127.0.0.1:23080/web/
```

Expected: 首个命令输出 `running healthy`，第二个命令退出码为 0。
