<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { UploadedFileItem } from '@/types'
import { compileLargeScreenTemplatePrompt, type LargeScreenImageTemplate } from './message'
import MediaPreview from '@/components/common/MediaPreview.vue'
import MediaThumbnail from '@/components/common/MediaThumbnail.vue'

type LargeScreenTemplateComponent = string
type LargeScreenTemplateReplaceableField = string

const componentLabels: Record<string, string> = {
  'title-status': '标题与状态', 'metric-grid': '指标网格', 'line-chart': '折线图', 'bar-chart': '柱状图',
  'area-chart': '面积图', 'pie-chart': '饼图', gauge: '仪表盘', map: '地图', 'topology-cluster': '拓扑集群',
  'core-topology': '核心拓扑', 'alert-feed': '告警动态', list: '列表', timeline: '时间线',
  'data-table': '数据表格', 'image-panel': '图片面板', 'footer-status': '底部状态',
  'kpi-card': '指标卡片', statistic: '统计数值', 'tab-bar': '页签导航', legend: '图例',
  'filter-bar': '筛选栏', logo: '标识', badge: '状态徽标', progress: '进度指示',
  'icon-button': '图标按钮', divider: '分隔线', 'text-block': '文本说明',
}
const replaceableLabels: Record<string, string> = {
  title: '标题', statusText: '状态文字', businessLabels: '业务标签', metricMeanings: '指标含义',
  chartData: '图表数据', icons: '图标', copy: '说明文案', visualAccent: '视觉强调',
}
const relationLabels: Record<string, string> = {
  'topology-link': '拓扑关联', 'flow-link': '流程关系', 'dependency-link': '依赖关系',
  'hierarchy-link': '层级关系', 'data-link': '数据关联',
}

const props = defineProps<{
  template: LargeScreenImageTemplate
  referenceFile: UploadedFileItem | null
  editable: boolean
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
  mode?: 'message' | 'workbench' | 'placeholder'
}>()

const components: LargeScreenTemplateComponent[] = [
  'title-status', 'metric-grid', 'line-chart', 'bar-chart', 'area-chart', 'pie-chart', 'gauge', 'map',
  'topology-cluster', 'core-topology', 'alert-feed', 'list', 'timeline', 'data-table', 'image-panel', 'footer-status',
  'kpi-card', 'statistic', 'tab-bar', 'legend', 'filter-bar', 'logo', 'badge', 'progress', 'icon-button', 'divider', 'text-block',
]
const replaceableFields: LargeScreenTemplateReplaceableField[] = [
  'title', 'statusText', 'businessLabels', 'metricMeanings', 'chartData', 'icons', 'copy', 'visualAccent',
]
const selectedRegionId = ref(props.template.regions[0]?.id ?? '')
const referencePreviewVisible = ref(false)
const cardRef = ref<HTMLElement | null>(null)
let promptResizeObserver: ResizeObserver | null = null

watch(() => props.template, (template) => {
  if (!template.regions.some((region) => region.id === selectedRegionId.value)) {
    selectedRegionId.value = template.regions[0]?.id ?? ''
  }
  void nextTick(resizePromptAreas)
}, { deep: true })

const selectedRegion = computed(() => props.template.regions.find((region) => region.id === selectedRegionId.value) ?? null)
const paletteCards = computed(() => props.template.visualTokens.palette.map((value) => ({
  value: value.trim(), valid: /^#[0-9a-fA-F]{6}$/.test(value.trim()),
})))
const canMoveSelectedRegion = computed(() => props.editable && !selectedRegion.value?.locked)
const canvasStyle = computed(() => ({
  background: props.template.visualTokens.surface,
  borderColor: props.template.visualTokens.border,
  fontFamily: props.template.visualTokens.typography,
  aspectRatio: props.template.canvas.ratio.replace(':', ' / '),
}))

function update(next: LargeScreenImageTemplate, syncPrompt = true) {
  if (props.editable) props.onUpdateTemplate(syncPrompt ? { ...next, prompt: compileLargeScreenTemplatePrompt(next) } : next)
}

function updateTemplate<K extends keyof LargeScreenImageTemplate>(key: K, value: LargeScreenImageTemplate[K]) {
  update({ ...props.template, [key]: value }, key !== 'prompt' && key !== 'negativePrompt')
}

function updateCanvasRatio(ratio: LargeScreenImageTemplate['canvas']['ratio']) {
  updateTemplate('canvas', { ...props.template.canvas, ratio })
}

function updateVisual<K extends keyof LargeScreenImageTemplate['visualTokens']>(key: K, value: LargeScreenImageTemplate['visualTokens'][K]) {
  updateTemplate('visualTokens', { ...props.template.visualTokens, [key]: value })
}

function updatePaletteColor(index: number, value: string) {
  updateVisual('palette', props.template.visualTokens.palette.map((color, colorIndex) => colorIndex === index ? value : color))
}

function updateRegion(change: Partial<LargeScreenImageTemplate['regions'][number]>) {
  const region = selectedRegion.value
  if (!region) return
  updateTemplate('regions', props.template.regions.map((item) => item.id === region.id ? { ...item, ...change } : item))
}

function removeSelectedRegion() {
  const region = selectedRegion.value
  if (!region || !props.editable || props.busy || props.template.regions.length <= 1) return
  const regions = props.template.regions.filter((item) => item.id !== region.id)
  selectedRegionId.value = regions[0]?.id ?? ''
  update({
    ...props.template,
    regions,
    relations: props.template.relations.filter((relation) => relation.from !== region.id && relation.to !== region.id),
  })
}

function moveSelectedRegion(deltaX: number, deltaY: number): boolean {
  const region = selectedRegion.value
  if (!region || !canMoveSelectedRegion.value || props.busy) return false
  const x = Math.max(0, Math.min(1000 - region.bounds.width, region.bounds.x + deltaX))
  const y = Math.max(0, Math.min(1000 - region.bounds.height, region.bounds.y + deltaY))
  if (x === region.bounds.x && y === region.bounds.y) return false
  updateRegion({ bounds: { ...region.bounds, x, y } })
  return true
}

function handleTemplateKeydown(event: KeyboardEvent) {
  if (!(event.target instanceof Node) || !cardRef.value?.contains(event.target)) return
  const target = event.target
  if (target instanceof HTMLTextAreaElement || target instanceof HTMLSelectElement
    || (target instanceof HTMLInputElement && ['text', 'color'].includes(target.type))) return
  const step = event.shiftKey ? 10 : 1
  const offsets: Record<string, [number, number]> = {
    ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step],
  }
  const offset = offsets[event.key]
  if (!offset) return
  if (moveSelectedRegion(...offset)) event.preventDefault()
}

function resizePromptArea(element: HTMLTextAreaElement) {
  // Reset before measuring so a previously stretched grid track never becomes the next baseline.
  element.style.height = '0px'
  element.style.height = `${element.scrollHeight}px`
}

function resizePromptAreas() {
  cardRef.value?.querySelectorAll<HTMLTextAreaElement>('.large-screen-template-prompt').forEach(resizePromptArea)
}

onMounted(() => {
  window.addEventListener('keydown', handleTemplateKeydown, true)
  void nextTick(resizePromptAreas)
  if (cardRef.value && typeof ResizeObserver !== 'undefined') {
    promptResizeObserver = new ResizeObserver(() => { void nextTick(resizePromptAreas) })
    promptResizeObserver.observe(cardRef.value)
  }
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleTemplateKeydown, true)
  promptResizeObserver?.disconnect()
})

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

function regionLabel(id: string) {
  return props.template.regions.find((region) => region.id === id)?.label || id
}

function componentLabel(component: string) { return componentLabels[component] || component }
function replaceableLabel(field: string) { return replaceableLabels[field] || field }
function relationLabel(kind: string) { return relationLabels[kind] || kind }
</script>

<template>
  <section v-if="mode === 'placeholder'" class="large-screen-template-card-placeholder" aria-hidden="true" />
  <section v-else ref="cardRef" class="large-screen-template-card" :class="{ 'large-screen-template-card-workbench': mode === 'workbench' }" :aria-busy="busy">
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
    <label v-if="versions && versions.length > 1" class="large-screen-template-version">模板版本
      <select :value="activeVersionId" @change="onSelectVersion?.(($event.target as HTMLSelectElement).value)">
        <option v-for="version in versions" :key="version.id" :value="version.id">{{ version.label }}</option>
      </select>
    </label>

    <MediaPreview
      v-if="referenceFile"
      v-model:visible="referencePreviewVisible"
      :items="[referenceFile]"
      :current-index="0"
    />

    <p v-if="validationError" class="large-screen-template-validation-error">{{ validationError }}</p>
    <p v-if="!editable" class="large-screen-template-readonly">此历史模板为只读；当前激活模板可继续编辑。</p>

    <div class="large-screen-template-workspace">
      <div class="large-screen-template-canvas-wrap">
        <div
          class="large-screen-template-canvas"
          :style="canvasStyle"
          aria-label="模板画布：选中未锁定区域后，可使用方向键移动；按住 Shift 加速移动"
        >
          <button
            v-for="region in template.regions"
            :key="region.id"
            class="large-screen-template-region"
            :class="{ 'large-screen-template-region-selected': region.id === selectedRegionId, 'large-screen-template-region-locked': region.locked }"
            :style="{ left: `${region.bounds.x / 10}%`, top: `${region.bounds.y / 10}%`, width: `${region.bounds.width / 10}%`, height: `${region.bounds.height / 10}%`, zIndex: region.layer, borderColor: template.visualTokens.palette[region.layer % template.visualTokens.palette.length] }"
            type="button"
            :title="region.locked ? '该区域已锁定' : '使用方向键移动；按住 Shift 可快速移动'"
            @click="selectedRegionId = region.id"
          >{{ region.label }}</button>
        </div>
        <div v-if="selectedRegion" class="large-screen-template-canvas-controls">
          <span>已选中：{{ selectedRegion.label }}</span>
          <button
            class="large-screen-template-lock-button"
            :class="{ 'large-screen-template-lock-button-active': selectedRegion.locked }"
            type="button"
            :disabled="!editable || busy"
            @click="updateRegion({ locked: !selectedRegion.locked })"
          >{{ selectedRegion.locked ? '解锁区域' : '锁定区域' }}</button>
          <button
            class="large-screen-template-remove-button"
            type="button"
            :disabled="!editable || busy || template.regions.length <= 1"
            @click="removeSelectedRegion"
          >删除选中模块</button>
          <span class="large-screen-template-canvas-hint">{{ selectedRegion.locked ? '已锁定，无法移动' : '方向键移动 · Shift 加速' }}</span>
        </div>
        <label class="large-screen-template-field">画布比例
          <select :value="template.canvas.ratio" :disabled="!editable || busy" @change="updateCanvasRatio(($event.target as HTMLSelectElement).value as LargeScreenImageTemplate['canvas']['ratio'])">
            <option value="16:9">16:9</option><option value="21:9">21:9</option><option value="9:16">9:16</option>
          </select>
        </label>
      </div>

      <div class="large-screen-template-editor">
        <fieldset v-if="selectedRegion" class="large-screen-template-fieldset" :disabled="!editable || busy">
          <legend>区域：{{ selectedRegion.label }}</legend>
          <label class="large-screen-template-field">标签<input :value="selectedRegion.label" @input="updateRegion({ label: ($event.target as HTMLInputElement).value })"></label>
          <label class="large-screen-template-field">组件<select :value="selectedRegion.component" :disabled="!canMoveSelectedRegion" @change="updateRegion({ component: ($event.target as HTMLSelectElement).value as LargeScreenTemplateComponent })"><option v-for="component in components" :key="component" :value="component">{{ componentLabel(component) }}</option></select></label>
          <label class="large-screen-template-field">展示内容<input :value="selectedRegion.purpose" @input="updateRegion({ purpose: ($event.target as HTMLInputElement).value })"></label>
          <label class="large-screen-template-field">层级<input type="number" :value="selectedRegion.layer" :disabled="!canMoveSelectedRegion" @input="updateRegion({ layer: Number(($event.target as HTMLInputElement).value) })"></label>
          <div class="large-screen-template-replaceable"><span>可替换内容</span><label v-for="field in replaceableFields" :key="field" class="large-screen-template-check"><input type="checkbox" :checked="selectedRegion.replaceable.includes(field)" @change="toggleReplaceable(field, ($event.target as HTMLInputElement).checked)">{{ replaceableLabel(field) }}</label></div>
        </fieldset>

        <fieldset class="large-screen-template-fieldset" :disabled="!editable || busy">
          <legend>关系锁定</legend>
          <label v-for="(relation, index) in template.relations" :key="`${relation.from}-${relation.to}-${index}`" class="large-screen-template-check"><input type="checkbox" :checked="relation.locked" @change="updateRelation(index, ($event.target as HTMLInputElement).checked)">{{ regionLabel(relation.from) }} → {{ regionLabel(relation.to) }}（{{ relationLabel(relation.kind) }}）</label>
          <span v-if="template.relations.length === 0" class="large-screen-template-empty">没有已定义关系</span>
        </fieldset>

        <fieldset class="large-screen-template-fieldset" :disabled="!editable || busy">
          <legend>视觉令牌</legend>
          <div class="large-screen-template-field">色板
            <div class="large-screen-template-palette" aria-label="色板，可点击色卡修改颜色">
              <input
                v-for="(color, index) in paletteCards"
                :key="`${color.value}-${index}`"
                class="large-screen-template-swatch"
                :class="{ 'large-screen-template-swatch-invalid': !color.valid }"
                type="color"
                :value="color.valid ? color.value : '#000000'"
                :aria-label="`色板 ${index + 1}`"
                :disabled="!editable || busy"
                @input="updatePaletteColor(index, ($event.target as HTMLInputElement).value)"
              >
            </div>
          </div>
          <label class="large-screen-template-field">表面<input :value="template.visualTokens.surface" @input="updateVisual('surface', ($event.target as HTMLInputElement).value)"></label>
          <label class="large-screen-template-field">边框<input :value="template.visualTokens.border" @input="updateVisual('border', ($event.target as HTMLInputElement).value)"></label>
          <label class="large-screen-template-field">字体<input :value="template.visualTokens.typography" @input="updateVisual('typography', ($event.target as HTMLInputElement).value)"></label>
        </fieldset>

        <fieldset class="large-screen-template-fieldset" :disabled="!editable || busy">
          <legend>生成提示词</legend>
          <label class="large-screen-template-field">正向提示词<textarea class="large-screen-template-prompt" :value="template.prompt" @input="updateTemplate('prompt', ($event.target as HTMLTextAreaElement).value); resizePromptArea($event.target as HTMLTextAreaElement)"></textarea></label>
          <label class="large-screen-template-field">负向提示词<textarea class="large-screen-template-prompt" :value="template.negativePrompt" @input="updateTemplate('negativePrompt', ($event.target as HTMLTextAreaElement).value); resizePromptArea($event.target as HTMLTextAreaElement)"></textarea></label>
        </fieldset>

        <footer class="large-screen-template-reference">
          <button
            v-if="referenceFile"
            class="large-screen-template-reference-preview"
            type="button"
            title="查看参考图"
            @click="referencePreviewVisible = true"
          ><MediaThumbnail :item="referenceFile" :size="44" /></button>
          <span v-else class="large-screen-template-reference-name">未关联参考图</span>
          <button class="large-screen-template-button" type="button" :disabled="!editable || busy" @click="onRemoveReference">移除参考图</button>
          <button class="large-screen-template-button" type="button" :disabled="!editable || busy" @click="onReplaceReference">更换图片</button>
          <button class="large-screen-template-button" type="button" :disabled="!editable || busy" @click="onRetryAnalyze">重新识图</button>
          <button class="large-screen-template-button large-screen-template-button-primary" type="button" :disabled="!editable || busy" @click="onGenerate">立即生图</button>
        </footer>
      </div>
    </div>
  </section>
</template>

<style scoped>
.large-screen-template-card { display: grid; width: 100%; gap: 14px; box-sizing: border-box; padding: 18px; border: 1px solid #d8e4f2; border-radius: 12px; background: #fff; color: #1f2937; }
.large-screen-template-header, .large-screen-template-reference, .large-screen-template-workspace, .large-screen-template-replaceable { display: flex; gap: 10px; }
.large-screen-template-header { align-items: start; justify-content: space-between; }.large-screen-template-eyebrow { margin: 0 0 4px; color: #64748b; font-size: 12px; }.large-screen-template-title { width: min(100%, 500px); border: 0; font-size: 20px; font-weight: 700; }.large-screen-template-confidence { padding: 4px 8px; border-radius: 999px; background: #e0f2fe; color: #075985; font-size: 12px; }.large-screen-template-version { display: flex; align-items: center; gap: 8px; color: #475569; font-size: 12px; }.large-screen-template-version select { min-width: 180px; padding: 5px 8px; border: 1px solid #cbd5e1; border-radius: 6px; background: #fff; color: #1e293b; font: inherit; }
.large-screen-template-reference { flex-wrap: wrap; align-items: center; padding: 10px; border-radius: 8px; background: #f8fafc; }.large-screen-template-reference-name { flex: 1 1 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.large-screen-template-reference-preview { display: inline-flex; padding: 0; overflow: hidden; border: 0; border-radius: 7px; background: transparent; cursor: zoom-in; }.large-screen-template-reference-preview:focus-visible { outline: 2px solid #0369a1; outline-offset: 2px; }.large-screen-template-button { padding: 6px 10px; border: 1px solid #cbd5e1; border-radius: 6px; background: #fff; cursor: pointer; }.large-screen-template-button-primary { border-color: #0369a1; background: #0369a1; color: #fff; }.large-screen-template-button:disabled { cursor: not-allowed; opacity: .55; }
.large-screen-template-validation-error { margin: 0; color: #b42318; }.large-screen-template-readonly { margin: 0; color: #64748b; font-size: 13px; }.large-screen-template-workspace { display: grid; min-width: 0; }.large-screen-template-canvas-wrap { width: 100%; min-width: 0; }.large-screen-template-canvas { position: relative; width: 100%; overflow: hidden; border: 2px solid; border-radius: 8px; background-image: linear-gradient(rgba(255,255,255,.08) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.08) 1px, transparent 1px); background-size: 8.333% 8.333%; }
.large-screen-template-region { position: absolute; overflow: hidden; padding: 4px; border: 2px solid; background: rgba(7, 27, 58, .72); color: #fff; font: inherit; font-size: clamp(8px, 1.6vw, 12px); text-align: left; cursor: pointer; }.large-screen-template-region-selected { outline: 3px solid #facc15; outline-offset: -3px; }.large-screen-template-region-locked::after { content: '锁定'; display: block; font-size: 9px; opacity: .8; }
.large-screen-template-card-workbench { container-type: inline-size; min-height: 100%; align-content: start; gap: 12px; padding: 14px; }.large-screen-template-card-workbench .large-screen-template-workspace { grid-template-columns: minmax(0, 1.45fr) minmax(290px, .85fr); align-items: start; min-height: 0; gap: 14px; }.large-screen-template-card-workbench .large-screen-template-editor { align-content: start; align-items: start; padding-right: 4px; overflow: visible; }.large-screen-template-card-workbench .large-screen-template-fieldset { align-content: start; }.large-screen-template-card-workbench .large-screen-template-canvas { min-height: 330px; }.large-screen-template-card-workbench .large-screen-template-region { font-size: clamp(9px, 1vw, 12px); }
@container (max-width: 720px) { .large-screen-template-card-workbench .large-screen-template-workspace { grid-template-columns: 1fr; }.large-screen-template-card-workbench .large-screen-template-editor { max-height: none; overflow: visible; } }
.large-screen-template-canvas-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 8px 2px 2px; color: #475569; font-size: 12px; }.large-screen-template-lock-button, .large-screen-template-remove-button { padding: 5px 9px; border: 1px solid #f59e0b; border-radius: 6px; background: #fffbeb; color: #92400e; cursor: pointer; font: inherit; }.large-screen-template-lock-button-active { border-color: #0369a1; background: #e0f2fe; color: #075985; }.large-screen-template-remove-button { border-color: #fecaca; background: #fff7f7; color: #b42318; }.large-screen-template-lock-button:disabled, .large-screen-template-remove-button:disabled { cursor: not-allowed; opacity: .55; }.large-screen-template-canvas-hint { color: #64748b; }.large-screen-template-editor { display: grid; flex: 1; gap: 10px; min-width: 0; }.large-screen-template-fieldset { display: grid; gap: 8px; min-width: 0; padding: 10px; border: 1px solid #d8e4f2; border-radius: 8px; }.large-screen-template-fieldset legend { padding: 0 4px; font-weight: 600; }.large-screen-template-field { display: grid; gap: 4px; font-size: 12px; }.large-screen-template-field input, .large-screen-template-field select, .large-screen-template-field textarea { width: 100%; box-sizing: border-box; padding: 6px; border: 1px solid #cbd5e1; border-radius: 5px; font: inherit; }.large-screen-template-field textarea { min-height: 58px; resize: vertical; }.large-screen-template-prompt { overflow: hidden; resize: none !important; }.large-screen-template-check { display: flex; gap: 5px; align-items: center; font-size: 12px; }.large-screen-template-replaceable { flex-wrap: wrap; align-items: center; }.large-screen-template-empty { color: #64748b; font-size: 12px; }.large-screen-template-palette { display: flex; flex-wrap: wrap; gap: 8px; min-height: 38px; padding: 6px; border: 1px solid #cbd5e1; border-radius: 5px; background: #fff; }.large-screen-template-swatch { width: 32px !important; height: 24px; padding: 0 !important; border: 1px solid rgb(15 23 42 / 26%) !important; border-radius: 5px !important; background: transparent; cursor: pointer; }.large-screen-template-swatch::-webkit-color-swatch-wrapper { padding: 0; }.large-screen-template-swatch::-webkit-color-swatch { border: 0; border-radius: 4px; }.large-screen-template-swatch:disabled { cursor: not-allowed; opacity: .55; }.large-screen-template-swatch-invalid { border-color: #b42318 !important; }
@media (max-width: 760px) { .large-screen-template-workspace, .large-screen-template-card-workbench .large-screen-template-workspace { display: grid; }.large-screen-template-canvas-wrap { width: 100%; }.large-screen-template-header { display: grid; }.large-screen-template-title { width: 100%; }.large-screen-template-card-workbench .large-screen-template-editor { max-height: none; overflow: visible; } }
</style>
