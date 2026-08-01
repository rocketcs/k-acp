<script setup lang="ts">
defineProps<{
  items: Array<{ id: string; imageUrl: string }>
}>()

const emit = defineEmits<{
  select: [id: string]
}>()
</script>

<template>
  <aside v-if="items.length" class="large-screen-image-timeline" aria-label="生成图片时间轴">
    <span class="large-screen-image-timeline-title">生图记录</span>
    <button
      v-for="(item, index) in items"
      :key="item.id"
      class="large-screen-image-timeline-item"
      type="button"
      :title="`定位第 ${index + 1} 张生成图`"
      @click="emit('select', item.id)"
    >
      <img :src="item.imageUrl" :alt="`第 ${index + 1} 张生成图`">
      <span>{{ index + 1 }}</span>
    </button>
  </aside>
</template>

<style scoped>
.large-screen-image-timeline { position: fixed; z-index: 20; top: 50%; right: 20px; display: grid; gap: 8px; max-height: 64dvh; padding: 10px 7px; overflow-y: auto; border: 1px solid rgb(148 163 184 / 45%); border-radius: 12px; background: rgb(255 255 255 / 88%); box-shadow: 0 12px 32px rgb(15 23 42 / 12%); backdrop-filter: blur(12px); transform: translateY(-50%); }.large-screen-image-timeline-title { color: #475569; font-size: 11px; font-weight: 600; text-align: center; writing-mode: vertical-rl; justify-self: center; }.large-screen-image-timeline-item { position: relative; width: 54px; height: 34px; padding: 0; overflow: hidden; border: 2px solid transparent; border-radius: 5px; background: #0f172a; cursor: pointer; }.large-screen-image-timeline-item:hover, .large-screen-image-timeline-item:focus-visible { border-color: #0ea5e9; outline: none; }.large-screen-image-timeline-item img { width: 100%; height: 100%; object-fit: cover; }.large-screen-image-timeline-item span { position: absolute; right: 2px; bottom: 2px; min-width: 14px; border-radius: 999px; background: rgb(15 23 42 / 80%); color: #fff; font-size: 10px; line-height: 14px; }
@media (max-width: 900px) { .large-screen-image-timeline { right: 8px; }.large-screen-image-timeline-title { display: none; } }
</style>
