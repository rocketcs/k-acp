<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import MediaIcon from './MediaIcon.vue'
import { download } from '@/api/attach'
import { getMediaMimeType, isImageExtension } from '@/utils/chat/media'

const props = withDefaults(defineProps<{
  item: {
    id: string
    name: string
    extension?: string
    uploading?: boolean
  }
  size?: number
}>(), {
  size: 32,
})

const imageUrl = ref('')
const failed = ref(false)
let loadSequence = 0

function revokeImageUrl() {
  if (imageUrl.value.startsWith('blob:')) URL.revokeObjectURL(imageUrl.value)
  imageUrl.value = ''
}

async function loadThumbnail() {
  const sequence = ++loadSequence
  revokeImageUrl()
  failed.value = false

  if (props.item.uploading || props.item.id.startsWith('temp-') || !isImageExtension(props.item.extension)) return

  try {
    const response = await download(props.item.id)
    if (sequence !== loadSequence) return
    const serverMimeType = response.headers?.['content-type']?.split(';', 1)[0]
    const blob = new Blob([response.data], {
      type: getMediaMimeType(props.item.extension) || serverMimeType || 'application/octet-stream',
    })
    imageUrl.value = URL.createObjectURL(blob)
  } catch {
    if (sequence === loadSequence) failed.value = true
  }
}

watch(() => [props.item.id, props.item.extension, props.item.uploading], () => {
  void loadThumbnail()
}, { immediate: true })

onBeforeUnmount(() => {
  loadSequence++
  revokeImageUrl()
})
</script>

<template>
  <span class="media-thumbnail" :style="{ width: `${size}px`, height: `${size}px` }" :title="item.name">
    <img v-if="imageUrl && !failed" :src="imageUrl" :alt="item.name" class="media-thumbnail-image" @error="failed = true">
    <MediaIcon v-else :type="item.extension || 'FILE'" :size="Math.max(19, Math.round(size * 0.72))" />
  </span>
</template>

<style scoped lang="scss">
.media-thumbnail { display: inline-flex; flex: 0 0 auto; align-items: center; justify-content: center; overflow: hidden; border-radius: 6px; background: rgba(255, 255, 255, 0.72); }
.media-thumbnail-image { width: 100%; height: 100%; object-fit: cover; }
</style>
