<script setup lang="ts">
/**
 * 发现组织组件 — 浏览已加入和可发现的租户
 *
 * @author huxuehao
 */
import { ref, computed, onMounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import * as tenantApi from '@/api/tenant'
import { useAccountStore } from '@/stores'
import type { TenantDiscoveryVO } from '@/types'

const accountStore = useAccountStore()

/** 所有租户发现数据 */
const tenants = ref<TenantDiscoveryVO[]>([])
const loading = ref(false)

/** 切换租户加载状态 */
const switchingTenantId = ref<string | null>(null)

/** 申请加入弹窗 */
const joinModalVisible = ref(false)
const joinTarget = ref<TenantDiscoveryVO | null>(null)
const joinMessage = ref('')
const joinLoading = ref(false)

/** 已加入的组织 */
const joinedTenants = computed(() =>
  tenants.value.filter((t) => t.isJoined)
)

/** 可发现但未加入的组织 */
const discoverableTenants = computed(() =>
  tenants.value.filter((t) => !t.isJoined)
)

/** 租户角色中文映射 */
const roleLabelMap: Record<string, string> = {
  TENANT_OWNER: '拥有者',
  TENANT_ADMIN: '管理员',
  TENANT_EDITOR: '编辑者',
  TENANT_VIEWER: '查看者',
}

async function fetchDiscoverable() {
  loading.value = true
  try {
    const res = await tenantApi.listDiscoverable()
    tenants.value = res.data.data || []
  } catch {
    // 统一由 request.ts 响应拦截器处理错误提示
  } finally {
    loading.value = false
  }
}

/**
 * 切换到指定组织
 */
async function handleSwitchTenant(tenant: TenantDiscoveryVO) {
  Modal.confirm({
    title: '确认切换组织',
    content: `确定要切换到组织「${tenant.name}」吗？`,
    icon: null,
    onOk: async () => {
      switchingTenantId.value = String(tenant.id)
      try {
        await accountStore.switchTenant({ tenantId: String(tenant.id) })
        message.success(`已切换到组织「${tenant.name}」`)
        location.reload()
      } catch {
        // 统一由 request.ts 响应拦截器处理错误提示
      } finally {
        switchingTenantId.value = null
      }
    },
  })
}

/**
 * 打开申请加入弹窗
 */
function openJoinModal(tenant: TenantDiscoveryVO) {
  joinTarget.value = tenant
  joinMessage.value = ''
  joinModalVisible.value = true
}

/**
 * 提交加入申请
 */
async function handleJoinSubmit() {
  if (!joinTarget.value) return
  joinLoading.value = true
  try {
    const res = await tenantApi.joinRequest(String(joinTarget.value.id), {
      message: joinMessage.value,
    })
    // 判断是否直接加入成功（无需审批场景）
    if (res.data.msg && res.data.msg.includes('加入成功')) {
      message.success('加入成功')
    } else {
      message.success('申请已提交，请等待管理员审批')
    }
    joinModalVisible.value = false
    // 刷新列表
    await fetchDiscoverable()
  } catch {
    // 统一由 request.ts 响应拦截器处理错误提示
  } finally {
    joinLoading.value = false
  }
}

onMounted(() => {
  fetchDiscoverable()
})
</script>

<template>
  <div>
    <h2 class="settings-page-title">发现组织</h2>

    <div class="discover-desc mb-lg">
      浏览已加入的组织，或发现并申请加入感兴趣的团队
    </div>

    <ApboaSpin :spinning="loading">
      <div v-if="tenants.length === 0 && !loading" class="empty-state">
        <AEmpty description="暂无可发现的租户" />
      </div>

      <div class="tenant-list" v-else>
        <!-- 已加入的组织 -->
        <section v-if="joinedTenants.length > 0" class="tenant-section">
          <h3 class="section-title">已加入的组织</h3>
          <div class="tenant-card-grid">
            <div
              v-for="tenant in joinedTenants"
              :key="tenant.id"
              class="tenant-card"
              :class="{ 'is-current': tenant.isCurrent }"
            >
              <div class="card-avatar">
                {{ (tenant.name || '?').charAt(0) }}
              </div>
              <div class="card-content">
                <div class="card-header">
                  <span class="card-name" :title="tenant.name">{{ tenant.name }}</span>
                </div>
                <div v-if="tenant.description" class="card-desc" :title="tenant.description">
                  {{ tenant.description }}
                </div>
                <div class="card-meta">
                  <span class="meta-code" :title="tenant.code">{{ tenant.code }}</span>
                </div>
                <div class="card-action">
                  <ATag v-if="tenant.isCurrent" color="green" :bordered="false">当前</ATag>
                  <ATag v-if="tenant.role" :bordered="false">
                    {{ roleLabelMap[tenant.role] || tenant.role }}
                  </ATag>
                  <AButton
                    size="small"
                    v-if="!tenant.isCurrent"
                    :loading="switchingTenantId === String(tenant.id)"
                    @click="handleSwitchTenant(tenant)"
                  >
                    切换到此组织
                  </AButton>
                </div>
              </div>
            </div>
          </div>
        </section>

        <!-- 可发现的组织（未加入） -->
        <section v-if="discoverableTenants.length > 0" class="tenant-section">
          <h3 class="section-title">可发现的组织</h3>
          <div class="tenant-card-grid">
            <div
              v-for="tenant in discoverableTenants"
              :key="tenant.id"
              class="tenant-card"
            >
              <div class="card-avatar">
                {{ (tenant.name || '?').charAt(0) }}
              </div>
              <div class="card-content">
                <div class="card-header">
                  <span class="card-name" :title="tenant.name">{{ tenant.name }}</span>
                </div>
                <div v-if="tenant.description" class="card-desc" :title="tenant.description">
                  {{ tenant.description }}
                </div>
                <div class="card-meta">
                  <span class="meta-code" :title="tenant.code">{{ tenant.code }}</span>
                </div>
                <div class="card-action">
                  <AButton
                    type="primary"
                    size="small"
                    :disabled="!tenant.joinable"
                    @click="openJoinModal(tenant)"
                  >
                    {{ tenant.joinApprovalRequired ? '申请加入' : '立即加入' }}
                  </AButton>
                </div>
              </div>
            </div>
          </div>
        </section>
      </div>
    </ApboaSpin>

    <!-- 申请加入弹窗 -->
    <AModal
      v-model:open="joinModalVisible"
      :title="`申请加入 - ${joinTarget?.name}`"
      :confirm-loading="joinLoading"
      @ok="handleJoinSubmit"
    >
      <div class="mb-sm">
        <template v-if="joinTarget?.joinApprovalRequired">
          <p class="mb-sm">该组织加入需要管理员审批，请填写申请理由：</p>
        </template>
        <template v-else>
          <p class="mb-sm">点击确认后将直接加入该组织。</p>
        </template>
      </div>

      <ATextarea
        v-if="joinTarget?.joinApprovalRequired"
        v-model:value="joinMessage"
        :rows="3"
        placeholder="请输入申请理由（可选）"
      />
    </AModal>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/modules/_settings.scss' as *;

// ========== 页面描述 ==========
.discover-desc {
  font-size: 14px;
  color: var(--text-secondary, #666);
}

.tenant-list {
  max-height: calc(100vh - 200px);
  overflow-y: auto;
}

// ========== 分区 ==========
.tenant-section {
  margin-bottom: 36px;

  &:last-child {
    margin-bottom: 0;
  }
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 14px;
  color: var(--text-primary, #1a1a1a);
}

// ========== 卡片网格 ==========
.tenant-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 14px;
}

// ========== 卡片主体 ==========
.tenant-card {
  display: flex;
  gap: 14px;
  padding: 18px 20px;
  background: var(--color-bg-primary, #fff);
  border: 1px solid var(--color-border-light, #eee);
  border-radius: 10px;
  transition: box-shadow 0.2s ease, transform 0.2s ease, border-color 0.2s ease;

  &:hover {
    border-color: var(--color-border, #ddd);
    box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
    transform: translateY(-1px);
  }

  &.is-current {
    border-color: rgba(82, 196, 26, 0.25);
    background: rgba(82, 196, 26, 0.02);
  }
}

// ========== 卡片头像 ==========
.card-avatar {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  font-size: 18px;
  font-weight: 600;
  color: #6d6afe;
  background: linear-gradient(135deg, #ede9fe 0%, #e0e7ff 100%);
}

// ========== 卡片内容区 ==========
.card-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

// ========== 标题行 ==========
.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.card-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary, #1a1a1a);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  flex-shrink: 1;
}

// ========== 标签 ==========

// ========== 描述 ==========
.card-desc {
  font-size: 13px;
  line-height: 1.55;
  color: var(--text-secondary, #777);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  line-clamp: 2;
  overflow: hidden;
  word-break: break-word;
}

// ========== 元信息 ==========
.card-meta {
  display: flex;
  align-items: center;
}

.meta-code {
  font-size: 12px;
  font-family: "SF Mono", "Cascadia Code", "Fira Code", monospace;
  color: var(--text-tertiary, #aaa);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

// ========== 操作按钮 ==========
.card-action {
  display: flex;
  justify-content: flex-end;
  padding-top: 4px;
}

// ========== 空状态 ==========
.empty-state {
  padding-top: 60px;
}
</style>
