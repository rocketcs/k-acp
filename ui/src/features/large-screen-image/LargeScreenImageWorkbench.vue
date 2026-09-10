<script setup lang="ts">
import { ref } from 'vue'
import type { UploadedFileItem } from '@/types'
import LargeScreenImageTemplateCard from './LargeScreenImageTemplateCard.vue'
import type { LargeScreenImageTemplate } from './message'

defineProps<{
  template: LargeScreenImageTemplate
  referenceFile: UploadedFileItem | null
  busy: boolean
  validationError?: string
  onUpdateTemplate: (template: LargeScreenImageTemplate) => void
  onRetryAnalyze: () => void
  onGenerate: () => void
  onRemoveReference: () => void
  onReplaceReference: () => void
  versions?: Array<{ id: string; label: string }>
  activeVersionId?: string
  onSelectVersion?: (id: string) => void
}>()

const expanded = ref(false)
</script>

<template>
  <section class="large-screen-image-workbench" :class="{ 'is-collapsed': !expanded }" aria-label="大屏创作工作台">
    <header class="large-screen-image-workbench-bar">
      <div class="large-screen-image-workbench-title">
        <span class="large-screen-image-workbench-kicker">创作工作台</span>
        <strong>{{ template.title || '当前大屏模板' }}</strong>
        <span>{{ template.regions.length }} 个模块 · {{ template.canvas.ratio }}</span>
      </div>
      <button type="button" class="large-screen-image-workbench-toggle" :aria-expanded="expanded" @click="expanded = !expanded">
        {{ expanded ? '收起工作台' : '展开工作台' }}
      </button>
    </header>
    <div v-show="expanded" class="large-screen-image-workbench-body">
      <LargeScreenImageTemplateCard
        mode="workbench"
        :template="template"
        :reference-file="referenceFile"
        :editable="true"
        :busy="busy"
        :validation-error="validationError"
        :on-update-template="onUpdateTemplate"
        :on-retry-analyze="onRetryAnalyze"
        :on-generate="onGenerate"
        :on-remove-reference="onRemoveReference"
        :on-replace-reference="onReplaceReference"
        :versions="versions"
        :active-version-id="activeVersionId"
        :on-select-version="onSelectVersion"
      />
    </div>
  </section>
</template>

<style scoped>
.large-screen-image-workbench { display: flex; flex-direction: column; overflow: hidden; border: 1px solid #b8d4ec; border-radius: 0 18px 18px 0; background: rgb(248 252 255 / 98%); box-shadow: 16px 20px 46px rgb(15 23 42 / 18%); backdrop-filter: blur(18px); transition: width .24s ease, box-shadow .24s ease; }
.large-screen-image-workbench-bar { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 52px; padding: 9px 14px; border-bottom: 1px solid #d9e8f4; background: linear-gradient(90deg, #e8f5ff, #f9fcff); }
.large-screen-image-workbench-title { display: flex; align-items: baseline; gap: 9px; min-width: 0; color: #526273; font-size: 12px; }.large-screen-image-workbench-title strong { overflow: hidden; color: #102a43; font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }.large-screen-image-workbench-kicker { color: #0878be; font-weight: 700; letter-spacing: .08em; }.large-screen-image-workbench-toggle { flex: none; padding: 7px 10px; border: 1px solid #9fc7e5; border-radius: 7px; background: #fff; color: #075985; cursor: pointer; font: inherit; font-size: 12px; }.large-screen-image-workbench-toggle:hover { border-color: #0284c7; background: #f0f9ff; }
.large-screen-image-workbench-body { display: flex; flex: 1; min-height: 0; padding: 12px; overflow: auto; }.large-screen-image-workbench-body :deep(.large-screen-template-card) { flex: 1; min-height: 100%; }
.large-screen-image-workbench.is-collapsed { width: 46px; border-radius: 0 12px 12px 0; box-shadow: 8px 12px 24px rgb(15 23 42 / 14%); }.large-screen-image-workbench.is-collapsed .large-screen-image-workbench-bar { height: 100%; min-height: 0; padding: 8px; border: 0; background: #eff8ff; }.large-screen-image-workbench.is-collapsed .large-screen-image-workbench-title { display: none; }.large-screen-image-workbench.is-collapsed .large-screen-image-workbench-toggle { width: 30px; min-height: 118px; padding: 8px 4px; border-color: #8ec5e9; color: #075985; line-height: 1.25; writing-mode: vertical-rl; }
@media (max-width: 760px) { .large-screen-image-workbench-title span:last-child { display: none; }.large-screen-image-workbench-body { max-height: 74dvh; padding: 7px; } }
</style>
