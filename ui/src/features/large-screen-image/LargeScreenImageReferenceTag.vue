<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import * as attachApi from '@/api/attach'
import MediaPreview from '@/components/common/MediaPreview.vue'
import MediaThumbnail from '@/components/common/MediaThumbnail.vue'
import type { UploadedFileItem } from '@/types'

const props = defineProps<{ content: string }>()
const referenceFile = ref<UploadedFileItem | null>(null)
const previewVisible = ref(false)

const fileId = computed(() => props.content.trim())

async function loadReference() {
  if (!/^\d+$/.test(fileId.value)) return
  try {
    const attachment = (await attachApi.selectOne(fileId.value)).data?.data
    if (!attachment) return
    referenceFile.value = {
      id: fileId.value,
      name: attachment.originalName || attachment.name || '',
      extension: (attachment.extension || '').replace(/^\./, '').toLowerCase(),
      size: attachment.attachSize ? String(attachment.attachSize) : '',
    }
  } catch {
    referenceFile.value = null
  }
}

watch(fileId, () => { void loadReference() })
onMounted(() => { void loadReference() })
</script>

<template>
  <button
    v-if="referenceFile"
    class="large-screen-reference-tag"
    type="button"
    title="查看参考图"
    @click="previewVisible = true"
  ><MediaThumbnail :item="referenceFile" :size="64" /></button>
  <span v-else class="large-screen-reference-tag-loading">参考图加载中…</span>
  <MediaPreview
    v-if="referenceFile"
    v-model:visible="previewVisible"
    :items="[referenceFile]"
    :current-index="0"
  />
</template>

<style scoped>
.large-screen-reference-tag { display: inline-flex; padding: 0; overflow: hidden; border: 0; border-radius: 8px; background: transparent; cursor: zoom-in; vertical-align: middle; }
.large-screen-reference-tag:focus-visible { outline: 2px solid #0f74ff; outline-offset: 2px; }
.large-screen-reference-tag-loading { color: #64748b; font-size: 13px; }
</style>
