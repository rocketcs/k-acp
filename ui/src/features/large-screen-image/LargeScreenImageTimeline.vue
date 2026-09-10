<script setup lang="ts">
import { ref } from 'vue'

defineProps<{
  items: Array<{ id: string; imageUrl: string }>
}>()

const emit = defineEmits<{
  select: [id: string]
}>()

const previewUrl = ref('')

function preview(item: { id: string; imageUrl: string }) {
  previewUrl.value = item.imageUrl
  emit('select', item.id)
}
</script>

<template>
  <aside v-if="items.length" class="large-screen-image-timeline" aria-label="生成图片时间轴">
    <span class="large-screen-image-timeline-title">生图记录</span>
    <button
      v-for="(item, index) in items"
      :key="item.id"
      class="large-screen-image-timeline-item"
      type="button"
      :title="`预览第 ${index + 1} 张生成图`"
      @click="preview(item)"
    >
      <img :src="item.imageUrl" :alt="`第 ${index + 1} 张生成图`">
      <span>{{ index + 1 }}</span>
    </button>
    <Teleport to="body">
      <div v-if="previewUrl" class="large-screen-image-timeline-preview" role="dialog" aria-modal="true" aria-label="生图记录预览" @click.self="previewUrl = ''">
        <button type="button" class="large-screen-image-timeline-preview-close" aria-label="关闭预览" @click="previewUrl = ''">×</button>
        <img :src="previewUrl" alt="生图记录预览">
      </div>
    </Teleport>
  </aside>
</template>

<style scoped>
.large-screen-image-timeline { position: fixed; z-index: 20; top: 50%; right: 20px; display: grid; gap: 8px; max-height: 64dvh; padding: 10px 7px; overflow-y: auto; border: 1px solid rgb(148 163 184 / 45%); border-radius: 12px; background: rgb(255 255 255 / 88%); box-shadow: 0 12px 32px rgb(15 23 42 / 12%); backdrop-filter: blur(12px); transform: translateY(-50%); }.large-screen-image-timeline-title { color: #475569; font-size: 11px; font-weight: 600; text-align: center; writing-mode: vertical-rl; justify-self: center; }.large-screen-image-timeline-item { position: relative; width: 54px; height: 34px; padding: 0; overflow: hidden; border: 2px solid transparent; border-radius: 5px; background: #0f172a; cursor: zoom-in; }.large-screen-image-timeline-item:hover, .large-screen-image-timeline-item:focus-visible { border-color: #0ea5e9; outline: none; }.large-screen-image-timeline-item img { width: 100%; height: 100%; object-fit: cover; }.large-screen-image-timeline-item span { position: absolute; right: 2px; bottom: 2px; min-width: 14px; border-radius: 999px; background: rgb(15 23 42 / 80%); color: #fff; font-size: 10px; line-height: 14px; }.large-screen-image-timeline-preview { position: fixed; inset: 0; z-index: 3000; display: grid; place-items: center; padding: 48px; background: rgb(2 6 23 / 86%); backdrop-filter: blur(12px); }.large-screen-image-timeline-preview img { max-width: min(96vw, 1600px); max-height: 88vh; object-fit: contain; border-radius: 12px; box-shadow: 0 24px 80px rgb(0 0 0 / 45%); }.large-screen-image-timeline-preview-close { position: fixed; top: 22px; right: 24px; width: 42px; height: 42px; border: 1px solid rgb(255 255 255 / 28%); border-radius: 999px; background: rgb(15 23 42 / 74%); color: #fff; cursor: pointer; font-size: 28px; line-height: 36px; }
@media (max-width: 900px) { .large-screen-image-timeline { right: 8px; }.large-screen-image-timeline-title { display: none; } }
</style>
