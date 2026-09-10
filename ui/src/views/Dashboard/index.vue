<script setup lang="ts">
/**
 * 工作台页面：保留本地快捷入口，并渲染上游可配置数据看板。
 *
 * @author huxuehao
 */
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { EditOutlined } from '@ant-design/icons-vue'
import { useAccountStore, useDashboardStore } from '@/stores'
import { registerBuiltinPanels } from '@/components/dashboard/panels'
import DashboardGrid from '@/components/dashboard/DashboardGrid.vue'
import DashboardEmpty from '@/components/dashboard/DashboardEmpty.vue'
import { RouteNames } from '@/router/constants'
import agentAvatar from '@/assets/avatar/agent.png'
import workflowAvatar from '@/assets/avatar/workflow.png'
import knowledgeAvatar from '@/assets/avatar/knowledgebase.png'
import modelProviderAvatar from '@/assets/avatar/model-provider.png'
import skillAvatar from '@/assets/avatar/skill.png'
import mcpAvatar from '@/assets/avatar/mcp.png'
import toolAvatar from '@/assets/avatar/tool.png'
import hookAvatar from '@/assets/avatar/hook.png'
import promptAvatar from '@/assets/avatar/prompt.png'
import sensitiveAvatar from '@/assets/avatar/sensitive.png'

interface QuickEntry {
  label: string
  description: string
  avatar: string
  path: string
}

interface EntryGroup {
  title: string
  description: string
  items: QuickEntry[]
}

registerBuiltinPanels()

const router = useRouter()
const accountStore = useAccountStore()
const dashboardStore = useDashboardStore()

const dsl = computed(() => dashboardStore.portal?.config || null)
const hasPanels = computed(() => (dsl.value?.panels?.length || 0) > 0)

const entryGroups: EntryGroup[] = [
  {
    title: '开发',
    description: '构建和编排您的 AI 应用',
    items: [
      { label: '智能体', description: '创建、配置和发布智能体', avatar: agentAvatar, path: '/agent' },
      { label: '工作流', description: '编排可复用的业务流程', avatar: workflowAvatar, path: '/workflow' },
      { label: '知识库', description: '管理智能体使用的知识内容', avatar: knowledgeAvatar, path: '/knowledge' },
    ],
  },
  {
    title: '资源',
    description: '统一管理模型、能力和安全配置',
    items: [
      { label: '模型', description: '配置模型供应商和模型参数', avatar: modelProviderAvatar, path: '/model' },
      { label: '技能', description: '维护智能体可调用的技能包', avatar: skillAvatar, path: '/skill' },
      { label: 'MCP', description: '接入和治理 MCP 服务', avatar: mcpAvatar, path: '/mcp' },
      { label: '工具', description: '配置智能体可执行的工具', avatar: toolAvatar, path: '/tool' },
      { label: '扩展', description: '管理运行过程中的扩展能力', avatar: hookAvatar, path: '/hook' },
      { label: '提示词', description: '沉淀可复用的系统提示词', avatar: promptAvatar, path: '/prompt' },
      { label: '敏感词', description: '维护内容安全过滤规则', avatar: sensitiveAvatar, path: '/sensitive' },
    ],
  },
]

function openEntry(item: QuickEntry) {
  router.push(item.path)
}

function goDesigner() {
  router.push({ name: RouteNames.DASHBOARD_DESIGN })
}

onMounted(() => {
  dashboardStore.loadPortal()
})
</script>

<script lang="ts">
export default {
  name: 'DashboardView',
}
</script>

<template>
  <main class="dashboard-container">
    <section class="welcome-section">
      <p class="welcome-eyebrow">KINGSWARE 工作台</p>
      <h1 class="welcome-title">
        欢迎回来，{{ accountStore.userInfo?.nickname || '用户' }}
      </h1>
      <p class="welcome-desc">
        从常用模块开始，创建并管理您的 AI 应用。
      </p>
    </section>

    <section class="dashboard-portal">
      <div class="portal-header">
        <div class="portal-intro">
          <h2 class="portal-title">数据看板</h2>
          <p class="portal-desc text-secondary">
            工作台是你的专属数据门户，可自由编排图表、指标与表格面板，基于数据集实时呈现关键业务视图。
          </p>
        </div>
        <div class="portal-actions">
          <a-button type="text" @click="goDesigner">
            <template #icon><EditOutlined /></template>
            设计器
          </a-button>
        </div>
      </div>

      <div class="portal-body">
        <a-spin v-if="dashboardStore.loading" />
        <template v-else-if="dsl && hasPanels">
          <DashboardGrid :dsl="dsl" />
        </template>
        <DashboardEmpty v-else @create="goDesigner" />
      </div>
    </section>

    <section
      v-for="group in entryGroups"
      :key="group.title"
      class="entry-section"
    >
      <header class="section-header">
        <div>
          <h2>{{ group.title }}</h2>
          <p>{{ group.description }}</p>
        </div>
      </header>

      <div class="entry-grid">
        <button
          v-for="item in group.items"
          :key="item.path"
          type="button"
          class="entry-card"
          @click="openEntry(item)"
        >
          <span class="entry-icon">
            <img :src="item.avatar" :alt="`${item.label}图标`">
          </span>
          <span class="entry-content">
            <strong>{{ item.label }}</strong>
            <span>{{ item.description }}</span>
          </span>
          <span class="entry-arrow" aria-hidden="true">→</span>
        </button>
      </div>
    </section>
  </main>
</template>

<style scoped lang="scss">
.dashboard-container {
  min-height: 100%;
  padding: 32px;
  background: #f7f8fa;
}

.welcome-section {
  padding: 32px;
  margin-bottom: 24px;
  overflow: hidden;
  background:
    radial-gradient(circle at 90% 20%, rgba(71, 114, 255, 0.16), transparent 34%),
    linear-gradient(135deg, #ffffff 0%, #f5f7ff 100%);
  border: 1px solid #e8ebf2;
  border-radius: 16px;
}

.welcome-eyebrow {
  margin: 0 0 10px;
  color: #4772ff;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
}

.welcome-title {
  margin: 0 0 8px;
  color: #17191c;
  font-size: 28px;
  font-weight: 600;
  line-height: 1.3;
}

.welcome-desc {
  margin: 0;
  color: #69707d;
  font-size: 14px;
}

.dashboard-portal {
  display: flex;
  flex-direction: column;
  min-height: 260px;
  margin-bottom: 24px;
  overflow: hidden;
  background: #fff;
  border: 1px solid #eceef2;
  border-radius: 16px;
}

.portal-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  padding: 24px 24px 8px;
}

.portal-intro {
  flex: 1;
  min-width: 0;
}

.portal-title {
  margin: 0 0 var(--spacing-sm) 0;
  color: var(--color-text-primary);
  font-size: var(--font-size-2xl);
  font-weight: 600;
}

.portal-desc {
  max-width: 800px;
  margin: 0;
  font-size: var(--font-size-base);
  line-height: 1.6;
}

.portal-actions {
  flex-shrink: 0;
}

.portal-body {
  flex: 1;
  padding: 16px 10px;
}

.entry-section {
  padding: 24px;
  margin-bottom: 24px;
  background: #fff;
  border: 1px solid #eceef2;
  border-radius: 16px;
}

.section-header {
  margin-bottom: 20px;

  h2 {
    margin: 0 0 6px;
    color: #202328;
    font-size: 18px;
    font-weight: 600;
  }

  p {
    margin: 0;
    color: #8a909b;
    font-size: 13px;
  }
}

.entry-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 14px;
}

.entry-card {
  display: flex;
  align-items: center;
  width: 100%;
  min-height: 92px;
  padding: 16px;
  color: inherit;
  text-align: left;
  background: #fff;
  border: 1px solid #e9ebef;
  border-radius: 12px;
  cursor: pointer;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;

  &:hover {
    border-color: #b9c7ff;
    box-shadow: 0 8px 24px rgba(38, 59, 122, 0.08);
    transform: translateY(-2px);

    .entry-arrow {
      color: #4772ff;
      transform: translateX(3px);
    }
  }

  &:focus-visible {
    outline: 3px solid rgba(71, 114, 255, 0.24);
    outline-offset: 2px;
  }
}

.entry-icon {
  display: grid;
  flex: 0 0 48px;
  width: 48px;
  height: 48px;
  margin-right: 14px;
  background: #f4f6fb;
  border-radius: 12px;
  place-items: center;

  img {
    width: 30px;
    height: 30px;
    object-fit: contain;
  }
}

.entry-content {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 6px;

  strong {
    color: #25282e;
    font-size: 15px;
    font-weight: 600;
  }

  span {
    overflow: hidden;
    color: #7b818d;
    font-size: 12px;
    line-height: 1.4;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.entry-arrow {
  margin-left: 12px;
  color: #b1b6c0;
  font-size: 18px;
  transition: color 0.2s ease, transform 0.2s ease;
}

@media (max-width: 768px) {
  .dashboard-container {
    padding: 16px;
  }

  .welcome-section,
  .entry-section {
    padding: 20px;
  }

  .portal-header {
    flex-direction: column;
    gap: 12px;
    padding: 20px 20px 8px;
  }

  .entry-grid {
    grid-template-columns: 1fr;
  }
}
</style>
