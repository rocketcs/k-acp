<script setup lang="ts">
/**
 * 租户设置组件 — 租户管理员管理租户
 *
 * @author huxuehao
 */
import { ref, computed, onMounted, watch } from 'vue'
import { message } from 'ant-design-vue'
import { UserOutlined } from '@ant-design/icons-vue'
import { useAccountStore } from '@/stores'
import * as tenantApi from '@/api/tenant'
import type {
  Tenant,
  TenantMemberVO,
  TenantJoinRequestVO,
  TenantSettingsRequest,
  TenantMemberAddDTO,
} from '@/types'
import { TenantRole, TenantJoinRequestStatus } from '@/types'

const accountStore = useAccountStore()

const tagColor: Record<string, string> = {
  TENANT_OWNER: 'purple',
  TENANT_ADMIN: 'blue',
  TENANT_EDITOR: 'cyan',
  TENANT_VIEWER: 'default',
}

/**
 * 获取头像文本
 */
function getAvatarText(text: string) {
  return text.charAt(0).toUpperCase()
}

/** 当前租户ID */
const tenantId = computed(() => accountStore.currentTenant?.tenantId)
/** 是否为租户管理员 */
const isAdmin = computed(() => accountStore.isAdmin)

/** 当前用户是否为租户拥有者 */
const isTenantOwner = computed(() => accountStore.tenantRole === 'TENANT_OWNER')

/** 当前用户是否仅为租户管理员（非拥有者） */
const isOnlyTenantAdmin = computed(() => accountStore.tenantRole === 'TENANT_ADMIN')

/** 当前激活的Tab */
const activeTab = ref('basic')

/** 租户详情 */
const tenantDetail = ref<Tenant | null>(null)
const detailLoading = ref(false)

/** 成员列表 */
const members = ref<TenantMemberVO[]>([])
const membersLoading = ref(false)

/** 成员搜索（前端过滤，支持昵称+账号） */
const searchKeyword = ref('')
const debouncedKeyword = ref('')
let searchTimer: ReturnType<typeof setTimeout> | null = null

watch(searchKeyword, (val) => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    debouncedKeyword.value = val
  }, 300)
})

/** 前端搜索过滤后的成员列表 */
const filteredMembers = computed(() => {
  const keyword = debouncedKeyword.value.trim().toLowerCase()
  if (!keyword) return members.value
  return members.value.filter(
    m =>
      (m.nickname && m.nickname.toLowerCase().includes(keyword)) ||
      (m.username && m.username.toLowerCase().includes(keyword))
  )
})

/** 加入申请列表 */
const joinRequests = ref<TenantJoinRequestVO[]>([])
const joinRequestsLoading = ref(false)

// ========== 基本信息 ==========

const basicForm = ref({
  name: '',
  code: '',
  description: '',
  contactName: '',
  contactEmail: '',
})
const basicLoading = ref(false)

async function fetchTenantDetail() {
  if (!tenantId.value) return
  detailLoading.value = true
  try {
    const res = await tenantApi.detail(tenantId.value)
    tenantDetail.value = res.data.data
    if (tenantDetail.value) {
      basicForm.value = {
        name: tenantDetail.value.name || '',
        code: tenantDetail.value.code || '',
        description: tenantDetail.value.description || '',
        contactName: tenantDetail.value.contactName || '',
        contactEmail: tenantDetail.value.contactEmail || '',
      }
    }
  } catch {
    // ignore
  } finally {
    detailLoading.value = false
  }
}

async function handleSaveBasic() {
  if (!tenantId.value) return
  basicLoading.value = true
  try {
    await tenantApi.update(tenantId.value, {
      ...tenantDetail.value!,
      ...basicForm.value,
    } as Tenant)
    message.success('基本信息已更新')
    await fetchTenantDetail()
  } catch {
    // handled by interceptor
  } finally {
    basicLoading.value = false
  }
}

// ========== 治理设置 ==========

const governanceForm = ref<TenantSettingsRequest>({
  discoverable: false,
  joinable: false,
  joinApprovalRequired: true,
})
const governanceLoading = ref(false)

function syncGovernanceForm() {
  if (tenantDetail.value) {
    governanceForm.value = {
      discoverable: tenantDetail.value.discoverable || false,
      joinable: tenantDetail.value.joinable || false,
      joinApprovalRequired: tenantDetail.value.joinApprovalRequired ?? true,
    }
  }
}

watch(() => tenantDetail.value, () => {
  syncGovernanceForm()
})

async function handleSaveGovernance() {
  if (!tenantId.value) return
  governanceLoading.value = true
  try {
    const res = await tenantApi.updateSettings(tenantId.value, governanceForm.value)
    tenantDetail.value = res.data.data
    syncGovernanceForm()
    message.success('治理设置已更新')
  } catch {
    // handled by interceptor
  } finally {
    governanceLoading.value = false
  }
}

// ========== 成员管理 ==========

async function fetchMembers() {
  if (!tenantId.value) return
  membersLoading.value = true
  try {
    const res = await tenantApi.listMembers(tenantId.value)
    members.value = res.data.data || []
  } catch {
    // ignore
  } finally {
    membersLoading.value = false
  }
}

/** 添加成员弹窗 */
const addMemberVisible = ref(false)
const addMemberForm = ref<TenantMemberAddDTO>({
  username: '',
  role: TenantRole.TENANT_VIEWER,
})
const addMemberLoading = ref(false)

function openAddMember() {
  addMemberForm.value = {
    username: '',
    role: TenantRole.TENANT_VIEWER,
  }
  addMemberVisible.value = true
}

async function handleAddMember() {
  if (!tenantId.value || !addMemberForm.value.username) {
    message.warning('请输入账号')
    return
  }
  addMemberLoading.value = true
  try {
    await tenantApi.addMember(tenantId.value, addMemberForm.value)
    message.success('成员添加成功')
    addMemberVisible.value = false
    await fetchMembers()
  } catch {
    // handled by interceptor
  } finally {
    addMemberLoading.value = false
  }
}

async function handleUpdateMemberRole(member: TenantMemberVO, role: string) {
  if (!tenantId.value) return
  try {
    await tenantApi.updateMemberRole(tenantId.value, String(member.accountId), role)
    message.success('角色已更新')
    await fetchMembers()
  } catch {
    // handled by interceptor
  }
}

async function handleRemoveMember(member: TenantMemberVO) {
  if (!tenantId.value) return
  try {
    await tenantApi.removeMember(tenantId.value, String(member.accountId))
    message.success('成员已移除')
    await fetchMembers()
  } catch {
    // handled by interceptor
  }
}

/** 租户角色选项（根据当前用户角色过滤） */
const tenantRoleOptions = computed(() => {
  if (isTenantOwner.value) {
    return [
      { label: '管理员', value: TenantRole.TENANT_ADMIN },
      { label: '编辑者', value: TenantRole.TENANT_EDITOR },
      { label: '查看者', value: TenantRole.TENANT_VIEWER },
    ]
  }
  // TENANT_ADMIN 只能设置编辑者和查看者
  return [
    { label: '编辑者', value: TenantRole.TENANT_EDITOR },
    { label: '查看者', value: TenantRole.TENANT_VIEWER },
  ]
})

/** 角色中文映射 */
function roleLabel(role: string): string {
  const map: Record<string, string> = {
    TENANT_OWNER: '拥有者',
    TENANT_ADMIN: '管理员',
    TENANT_EDITOR: '编辑者',
    TENANT_VIEWER: '查看者',
  }
  return map[role] || role
}

/** 申请状态标签颜色 */
function statusColor(status: string): string {
  const map: Record<string, string> = {
    PENDING: 'orange',
    APPROVED: 'green',
    REJECTED: 'red',
    CANCELLED: 'default',
  }
  return map[status] || 'default'
}
function statusLabel(status: string): string {
  const map: Record<string, string> = {
    PENDING: '待审批',
    APPROVED: '已通过',
    REJECTED: '已拒绝',
    CANCELLED: '已撤销',
  }
  return map[status] || status
}

// ========== 加入申请管理 ==========

/** 申请搜索（前端过滤，支持昵称+账号） */
const requestSearchKeyword = ref('')
const requestDebouncedKeyword = ref('')
let requestSearchTimer: ReturnType<typeof setTimeout> | null = null

watch(requestSearchKeyword, (val) => {
  if (requestSearchTimer) clearTimeout(requestSearchTimer)
  requestSearchTimer = setTimeout(() => {
    requestDebouncedKeyword.value = val
  }, 300)
})

/** 前端搜索过滤后的申请列表 */
const filteredJoinRequests = computed(() => {
  const keyword = requestDebouncedKeyword.value.trim().toLowerCase()
  if (!keyword) return joinRequests.value
  return joinRequests.value.filter(
    r =>
      (r.applicantName && r.applicantName.toLowerCase().includes(keyword)) ||
      (r.applicantUsername && r.applicantUsername.toLowerCase().includes(keyword))
  )
})

const pendingCount = computed(() =>
  joinRequests.value.filter((r) => r.status === TenantJoinRequestStatus.PENDING).length
)

async function fetchJoinRequests() {
  if (!tenantId.value) return
  joinRequestsLoading.value = true
  try {
    const res = await tenantApi.listJoinRequests(tenantId.value)
    joinRequests.value = res.data.data || []
  } catch {
    // ignore
  } finally {
    joinRequestsLoading.value = false
  }
}

async function handleApproveRequest(requestId: string) {
  try {
    await tenantApi.approveJoinRequest(requestId)
    message.success('已通过申请')
    await fetchJoinRequests()
    await fetchMembers()
  } catch {
    // handled by interceptor
  }
}

async function handleRejectRequest(requestId: string) {
  try {
    await tenantApi.rejectJoinRequest(requestId)
    message.success('已拒绝申请')
    await fetchJoinRequests()
  } catch {
    // handled by interceptor
  }
}

// ========== 生命周期 ==========

onMounted(() => {
  if (isAdmin.value) {
    fetchTenantDetail()
    fetchMembers()
    fetchJoinRequests()
  }
})

// 监听Tab切换
watch(activeTab, (tab) => {
  if (!isAdmin.value) return
  if (tab === 'members') fetchMembers()
  if (tab === 'requests') fetchJoinRequests()
  if (tab === 'basic' && !tenantDetail.value) fetchTenantDetail()
})
</script>

<template>
  <div>
    <h2 class="settings-page-title">组织管理</h2>

    <div v-if="!isAdmin" class="no-permission">
      <AResult
        status="403"
        title="暂无权限"
        sub-title="仅组织管理员可访问此页面"
      />
    </div>

    <template v-else>
      <ATabs v-model:activeKey="activeTab">
        <!-- 基本信息 -->
        <ATabPane key="basic" tab="基本信息">
          <div class="tab-content" v-if="tenantDetail">
            <AForm layout="vertical" :model="basicForm">
              <AFormItem label="组织名称">
                <AInput v-model:value="basicForm.name" />
              </AFormItem>
              <AFormItem label="组织编码">
                <AInput v-model:value="basicForm.code" disabled />
              </AFormItem>
              <AFormItem label="描述">
                <ATextarea v-model:value="basicForm.description" :rows="3" />
              </AFormItem>
              <AFormItem>
                <AButton type="primary" :loading="basicLoading" @click="handleSaveBasic">
                  保存
                </AButton>
              </AFormItem>
            </AForm>
          </div>
        </ATabPane>

        <!-- 治理设置 -->
        <ATabPane key="governance" tab="治理设置">
          <div class="tab-content">
            <AForm layout="vertical" :model="governanceForm">
              <AFormItem label="允许被发现">
                <ASwitch v-model:checked="governanceForm.discoverable" />
                <span class="form-hint ml-sm">开启后，其他用户可在「发现组织」中找到此组织</span>
              </AFormItem>
              <AFormItem label="允许申请加入">
                <ASwitch v-model:checked="governanceForm.joinable" />
                <span class="form-hint ml-sm">开启后，用户可主动申请加入此组织</span>
              </AFormItem>
              <AFormItem label="加入需要审批" v-if="governanceForm.joinable">
                <ASwitch v-model:checked="governanceForm.joinApprovalRequired" />
                <span class="form-hint ml-sm">开启后，用户申请加入需管理员审批；关闭则自动加入</span>
              </AFormItem>
              <AFormItem>
                <AButton type="primary" :loading="governanceLoading" @click="handleSaveGovernance">
                  保存设置
                </AButton>
              </AFormItem>
            </AForm>
          </div>
        </ATabPane>

        <!-- 成员管理 -->
        <ATabPane key="members" tab="成员管理">
          <div class="tab-content">
            <div class="members-toolbar">
              <span>共 {{ filteredMembers.length }} 位成员</span>
              <div class="members-toolbar-right">
                <AInput
                  v-model:value="searchKeyword"
                  placeholder="搜索昵称或账号"
                  style="width: 220px"
                  allow-clear
                />
                <AButton type="primary" size="small" @click="openAddMember">
                  添加成员
                </AButton>
              </div>
            </div>

            <ApboaSpin :spinning="membersLoading">
              <div class="account-list">
                <div
                  v-for="member in filteredMembers"
                  :key="member.accountId"
                  class="account-list-item"
                >
                  <AAvatar :size="36" class="account-list-avatar">
                    {{ getAvatarText(member.nickname || String(member.accountId)) }}
                  </AAvatar>
                  <div class="account-list-info">
                    <div class="account-list-name-row">
                      <span class="account-list-name">{{ member.nickname }}</span>
                    </div>
                    <div class="account-list-email">@{{ member.username }}</div>
                  </div>
                  <div class="account-list-meta">
                    <ATag
                      :color="tagColor[member.tenantRole]"
                      :bordered="false"
                    >
                      <UserOutlined />{{ roleLabel(member.tenantRole) }}
                    </ATag>
                  </div>
                  <div class="account-list-actions">
                    <template v-if="member.tenantRole === 'TENANT_OWNER'">
                      <span class="text-tertiary">-</span>
                    </template>
                    <template v-else-if="isOnlyTenantAdmin && member.tenantRole === 'TENANT_ADMIN'">
                      <span class="text-tertiary">-</span>
                    </template>
                    <template v-else>
                      <a-divider type="vertical" />
                      <ASelect
                        :value="member.tenantRole"
                        :options="tenantRoleOptions"
                        size="small"
                        style="width: 90px"
                        @change="(val: string) => handleUpdateMemberRole(member, val)"
                      />
                      <APopconfirm
                        title="确认移除该成员？"
                        @confirm="handleRemoveMember(member)"
                      >
                        <AButton type="link" danger size="small">移除</AButton>
                      </APopconfirm>
                    </template>
                  </div>
                </div>
              </div>
              <AEmpty v-if="!membersLoading && filteredMembers.length === 0" description="暂无成员" />
            </ApboaSpin>

            <!-- 添加成员弹窗 -->
            <AModal
              v-model:open="addMemberVisible"
              title="添加成员"
              :confirm-loading="addMemberLoading"
              @ok="handleAddMember"
            >
              <AForm layout="vertical" :model="addMemberForm">
                <AFormItem label="账号">
                  <AInput v-model:value="addMemberForm.username" placeholder="请输入要添加的账号" />
                </AFormItem>
                <AFormItem label="角色">
                  <ASelect
                    v-model:value="addMemberForm.role"
                    :options="tenantRoleOptions"
                  />
                </AFormItem>
              </AForm>
            </AModal>
          </div>
        </ATabPane>

        <!-- 加入申请管理 -->
        <ATabPane key="requests">
          <template #tab>
            <span>
              加入申请
              <ABadge v-if="pendingCount > 0" :count="pendingCount" :overflow-count="99" size="small" class="ml-xs" />
            </span>
          </template>
          <div class="tab-content">
            <div class="members-toolbar">
              <span>共 {{ filteredJoinRequests.length }} 条申请</span>
              <div class="members-toolbar-right">
                <AInput
                  v-model:value="requestSearchKeyword"
                  placeholder="搜索昵称或账号"
                  style="width: 220px"
                  allow-clear
                />
              </div>
            </div>
            <ApboaSpin :spinning="joinRequestsLoading">
              <div class="account-list">
                <div
                  v-for="request in filteredJoinRequests"
                  :key="request.id"
                  class="account-list-item"
                >
                  <AAvatar :size="36" class="account-list-avatar">
                    {{ getAvatarText(request.applicantName || String(request.accountId)) }}
                  </AAvatar>
                  <div class="account-list-info">
                    <div class="account-list-name-row">
                      <span class="account-list-name">{{ request.applicantName }}</span>
                    </div>
                    <div class="account-list-email">@{{ request.applicantUsername }}</div>
                  </div>
                  <div class="account-list-meta">
                    <ATag :color="statusColor(request.status)" :bordered="false">
                      {{ statusLabel(request.status) }}
                    </ATag>
                  </div>
                  <div class="account-list-actions">
                    <template v-if="request.status === TenantJoinRequestStatus.PENDING">
                      <a-divider type="vertical" />
                      <AButton type="link" size="small" @click="handleApproveRequest(String(request.id))">
                        通过
                      </AButton>
                      <AButton type="link" danger size="small" @click="handleRejectRequest(String(request.id))">
                        拒绝
                      </AButton>
                    </template>
                    <span v-else class="text-tertiary">-</span>
                  </div>
                </div>
              </div>
              <AEmpty v-if="!joinRequestsLoading && filteredJoinRequests.length === 0" description="暂无申请" />
            </ApboaSpin>
          </div>
        </ATabPane>
      </ATabs>
    </template>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/modules/_settings.scss' as *;

.tab-content {
  padding-top: 16px;
  max-width: 720px;
}

.no-permission {
  padding-top: 60px;
}

.form-hint {
  font-size: 13px;
  color: var(--text-tertiary, #999);
}

.members-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--spacing-base);
  flex-wrap: wrap;
  gap: var(--spacing-md);
}

.members-toolbar-right {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}
</style>
