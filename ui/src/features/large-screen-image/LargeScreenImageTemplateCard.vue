<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { UploadedFileItem } from '@/types'
import type { LargeScreenImageTemplateV2, LargeScreenTemplateComponent, LargeScreenTemplateReplaceableField } from './template'

const props = defineProps<{
  template: LargeScreenImageTemplateV2
  referenceFile: UploadedFileItem | null
  editable: boolean
  busy: boolean
  validationError?: string
  onUpdateTemplate: (template: LargeScreenImageTemplateV2) => void
  onRetryAnalyze: () => void
  onGenerate: () => void
  onRemoveReference: () => void
  onReplaceReference: () => void
}>()

const components: LargeScreenTemplateComponent[] = [
  'title-status', 'metric-grid', 'line-chart', 'bar-chart', 'area-chart', 'pie-chart', 'gauge', 'map',
  'topology-cluster', 'core-topology', 'alert-feed', 'list', 'timeline', 'data-table', 'image-panel', 'footer-status',
]
const replaceableFields: LargeScreenTemplateReplaceableField[] = [
  'title', 'statusText', 'businessLabels', 'metricMeanings', 'chartData', 'icons', 'copy', 'visualAccent',
]
const selectedRegionId = ref(props.template.regions[0]?.id ?? '')

watch(() => props.template, (template) => {
  if (!template.regions.some((region) => region.id === selectedRegionId.value)) {
    selectedRegionId.value = template.regions[0]?.id ?? ''
  }
}, { deep: true })

const selectedRegion = computed(() => props.template.regions.find((region) => region.id === selectedRegionId.value) ?? null)
const canEditRegionGeometry = computed(() => props.editable && !selectedRegion.value?.locked)
const canvasStyle = computed(() => ({
  background: props.template.visualTokens.surface,
  borderColor: props.template.visualTokens.border,
  fontFamily: props.template.visualTokens.typography,
}))

function update(next: LargeScreenImageTemplateV2) {
  if (props.editable) props.onUpdateTemplate(next)
}

function updateTemplate<K extends keyof LargeScreenImageTemplateV2>(key: K, value: LargeScreenImageTemplateV2[K]) {
  update({ ...props.template, [key]: value })
}

function updateCanvasRatio(ratio: LargeScreenImageTemplateV2['canvas']['ratio']) {
  updateTemplate('canvas', { ...props.template.canvas, ratio })
}

function updateVisual<K extends keyof LargeScreenImageTemplateV2['visualTokens']>(key: K, value: LargeScreenImageTemplateV2['visualTokens'][K]) {
  updateTemplate('visualTokens', { ...props.template.visualTokens, [key]: value })
}

function updateRegion(change: Partial<LargeScreenImageTemplateV2['regions'][number]>) {
  const region = selectedRegion.value
  if (!region) return
  updateTemplate('regions', props.template.regions.map((item) => item.id === region.id ? { ...item, ...change } : item))
}

function updateBounds(key: keyof LargeScreenImageTemplateV2['regions'][number]['bounds'], value: number) {
  const region = selectedRegion.value
  if (!region || !canEditRegionGeometry.value) return
  updateRegion({ bounds: { ...region.bounds, [key]: value } })
}

function updateRelation(index: number, locked: boolean) {
  updateTemplate('relations', props.template.relations.map((relation, relationIndex) =>
    relationIndex === index ? { ...relation, locked } : relation,
  ))
}

function toggleReplaceable(field: LargeScreenTemplateReplaceableField, checked: boolean) {
  const region = selectedRegion.value
  if (!region) return
  const replaceable = checked
    ? [...new Set([...region.replaceable, field])]
    : region.replaceable.filter((item) => item !== field)
  updateRegion({ replaceable })
}
</script>

<template>
  <section class="large-screen-template-card" :aria-busy="busy">
    <header class="large-screen-template-header">
      <div>
        <p class="large-screen-template-eyebrow">可编辑大屏模板</p>
        <input
          class="large-screen-template-title"
          :value="template.title"
          :disabled="!editable || busy"
          aria-label="模板标题"
          @input="updateTemplate('title', ($event.target as HTMLInputElement).value)"
        >
      </div>
      <span class="large-screen-template-confidence">置信度 {{ template.confidence }}</span>
    </header>

    <div class="large-screen-template-reference">
      <span class="large-screen-template-reference-name">{{ referenceFile?.name ?? '未关联参考图' }}</span>
      <button class="large-screen-template-button" type="button" :disabled="!editable || busy" @click="onRemoveReference">移除参考图</button>
      <button class="large-screen-template-button" type="button" :disabled="!editable || busy" @click="onReplaceReference">更换图片</button>
      <button class="large-screen-template-button" type="button" :disabled="busy" @click="onRetryAnalyze">重新识图</button>
      <button class="large-screen-template-button large-screen-template-button-primary" type="button" :disabled="!editable || busy" @click="onGenerate">生成当前模板</button>
    </div>

    <p v-if="validationError" class="large-screen-template-validation-error">{{ validationError }}</p>
    <p v-if="!editable" class="large-screen-template-readonly">此历史模板为只读；当前激活模板可继续编辑。</p>

    <div class="large-screen-template-workspace">
      <div class="large-screen-template-canvas-wrap">
        <div class="large-screen-template-canvas" :style="canvasStyle" aria-label="1000 乘 1000 标准化画布">
          <button
            v-for="region in template.regions"
            :key="region.id"
            class="large-screen-template-region"
            :class="{ 'large-screen-template-region-selected': region.id === selectedRegionId, 'large-screen-template-region-locked': region.locked }"
            :style="{ left: `${region.bounds.x / 10}%`, top: `${region.bounds.y / 10}%`, width: `${region.bounds.width / 10}%`, height: `${region.bounds.height / 10}%`, zIndex: region.layer, borderColor: template.visualTokens.palette[region.layer % template.visualTokens.palette.length] }"
            type="button"
            @click="selectedRegionId = region.id"
          >{{ region.label }}</button>
        </div>
        <label class="large-screen-template-field">画布比例
          <select :value="template.canvas.ratio" :disabled="!editable || busy" @change="updateCanvasRatio(($event.target as HTMLSelectElement).value as LargeScreenImageTemplateV2['canvas']['ratio'])">
            <option value="16:9">16:9</option><option value="21:9">21:9</option><option value="9:16">9:16</option>
          </select>
        </label>
      </div>

      <div class="large-screen-template-editor">
        <fieldset class="large-screen-template-fieldset" :disabled="!editable || busy">
          <legend>视觉令牌</legend>
          <label class="large-screen-template-field">色板（逗号分隔）<input :value="template.visualTokens.palette.join(', ')" @input="updateVisual('palette', ($event.target as HTMLInputElement).value.split(',').map((value) => value.trim()).filter(Boolean))"></label>
          <label class="large-screen-template-field">表面<input :value="template.visualTokens.surface" @input="updateVisual('surface', ($event.target as HTMLInputElement).value)"></label>
          <label class="large-screen-template-field">边框<input :value="template.visualTokens.border" @input="updateVisual('border', ($event.target as HTMLInputElement).value)"></label>
          <label class="large-screen-template-field">字体<input :value="template.visualTokens.typography" @input="updateVisual('typography', ($event.target as HTMLInputElement).value)"></label>
        </fieldset>

        <fieldset v-if="selectedRegion" class="large-screen-template-fieldset" :disabled="!editable || busy">
          <legend>区域：{{ selectedRegion.label }}</legend>
          <label class="large-screen-template-field">标签<input :value="selectedRegion.label" @input="updateRegion({ label: ($event.target as HTMLInputElement).value })"></label>
          <label class="large-screen-template-field">组件<select :value="selectedRegion.component" :disabled="!canEditRegionGeometry" @change="updateRegion({ component: ($event.target as HTMLSelectElement).value as LargeScreenTemplateComponent })"><option v-for="component in components" :key="component" :value="component">{{ component }}</option></select></label>
          <label class="large-screen-template-field">用途<input :value="selectedRegion.purpose" @input="updateRegion({ purpose: ($event.target as HTMLInputElement).value })"></label>
          <label class="large-screen-template-check"><input type="checkbox" :checked="selectedRegion.locked" @change="updateRegion({ locked: ($event.target as HTMLInputElement).checked })">锁定区域</label>
          <div class="large-screen-template-bounds">
            <label v-for="key in ['x', 'y', 'width', 'height'] as const" :key="key" class="large-screen-template-field">{{ key }}<input type="number" :value="selectedRegion.bounds[key]" :disabled="!canEditRegionGeometry" @input="updateBounds(key, Number(($event.target as HTMLInputElement).value))"></label>
            <label class="large-screen-template-field">层级<input type="number" :value="selectedRegion.layer" :disabled="!canEditRegionGeometry" @input="updateRegion({ layer: Number(($event.target as HTMLInputElement).value) })"></label>
          </div>
          <div class="large-screen-template-replaceable"><span>可替换内容</span><label v-for="field in replaceableFields" :key="field" class="large-screen-template-check"><input type="checkbox" :checked="selectedRegion.replaceable.includes(field)" @change="toggleReplaceable(field, ($event.target as HTMLInputElement).checked)">{{ field === 'visualAccent' ? '视觉强调' : field }}</label></div>
        </fieldset>

        <fieldset class="large-screen-template-fieldset" :disabled="!editable || busy">
          <legend>关系锁定</legend>
          <label v-for="(relation, index) in template.relations" :key="`${relation.from}-${relation.to}-${index}`" class="large-screen-template-check"><input type="checkbox" :checked="relation.locked" @change="updateRelation(index, ($event.target as HTMLInputElement).checked)">{{ relation.from }} → {{ relation.to }}（{{ relation.kind }}）</label>
          <span v-if="template.relations.length === 0" class="large-screen-template-empty">没有已定义关系</span>
        </fieldset>

        <fieldset class="large-screen-template-fieldset" :disabled="!editable || busy">
          <legend>生成提示词</legend>
          <label class="large-screen-template-field">正向提示词<textarea :value="template.prompt" @input="updateTemplate('prompt', ($event.target as HTMLTextAreaElement).value)"></textarea></label>
          <label class="large-screen-template-field">负向提示词<textarea :value="template.negativePrompt" @input="updateTemplate('negativePrompt', ($event.target as HTMLTextAreaElement).value)"></textarea></label>
        </fieldset>
      </div>
    </div>
  </section>
</template>

<style scoped>
.large-screen-template-card { display: grid; gap: 14px; max-width: 1080px; padding: 18px; border: 1px solid #d8e4f2; border-radius: 12px; background: #fff; color: #1f2937; }
.large-screen-template-header, .large-screen-template-reference, .large-screen-template-workspace, .large-screen-template-bounds, .large-screen-template-replaceable { display: flex; gap: 10px; }
.large-screen-template-header { align-items: start; justify-content: space-between; }.large-screen-template-eyebrow { margin: 0 0 4px; color: #64748b; font-size: 12px; }.large-screen-template-title { width: min(100%, 500px); border: 0; font-size: 20px; font-weight: 700; }.large-screen-template-confidence { padding: 4px 8px; border-radius: 999px; background: #e0f2fe; color: #075985; font-size: 12px; }
.large-screen-template-reference { flex-wrap: wrap; align-items: center; padding: 10px; border-radius: 8px; background: #f8fafc; }.large-screen-template-reference-name { flex: 1 1 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.large-screen-template-button { padding: 6px 10px; border: 1px solid #cbd5e1; border-radius: 6px; background: #fff; cursor: pointer; }.large-screen-template-button-primary { border-color: #0369a1; background: #0369a1; color: #fff; }.large-screen-template-button:disabled { cursor: not-allowed; opacity: .55; }
.large-screen-template-validation-error { margin: 0; color: #b42318; }.large-screen-template-readonly { margin: 0; color: #64748b; font-size: 13px; }.large-screen-template-workspace { align-items: start; }.large-screen-template-canvas-wrap { width: min(45%, 460px); min-width: 280px; }.large-screen-template-canvas { position: relative; width: 100%; aspect-ratio: 1; overflow: hidden; border: 2px solid; border-radius: 8px; background-image: linear-gradient(rgba(255,255,255,.08) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.08) 1px, transparent 1px); background-size: 8.333% 8.333%; }
.large-screen-template-region { position: absolute; overflow: hidden; padding: 4px; border: 2px solid; background: rgba(7, 27, 58, .72); color: #fff; font: inherit; font-size: clamp(8px, 1.6vw, 12px); text-align: left; cursor: pointer; }.large-screen-template-region-selected { outline: 3px solid #facc15; outline-offset: -3px; }.large-screen-template-region-locked::after { content: '锁定'; display: block; font-size: 9px; opacity: .8; }
.large-screen-template-editor { display: grid; flex: 1; gap: 10px; min-width: 0; }.large-screen-template-fieldset { display: grid; gap: 8px; min-width: 0; padding: 10px; border: 1px solid #d8e4f2; border-radius: 8px; }.large-screen-template-fieldset legend { padding: 0 4px; font-weight: 600; }.large-screen-template-field { display: grid; gap: 4px; font-size: 12px; }.large-screen-template-field input, .large-screen-template-field select, .large-screen-template-field textarea { width: 100%; box-sizing: border-box; padding: 6px; border: 1px solid #cbd5e1; border-radius: 5px; font: inherit; }.large-screen-template-field textarea { min-height: 58px; resize: vertical; }.large-screen-template-check { display: flex; gap: 5px; align-items: center; font-size: 12px; }.large-screen-template-bounds { flex-wrap: wrap; }.large-screen-template-bounds .large-screen-template-field { width: 76px; }.large-screen-template-replaceable { flex-wrap: wrap; align-items: center; }.large-screen-template-empty { color: #64748b; font-size: 12px; }
@media (max-width: 760px) { .large-screen-template-workspace { display: grid; }.large-screen-template-canvas-wrap { width: 100%; }.large-screen-template-header { display: grid; }.large-screen-template-title { width: 100%; } }
</style>
