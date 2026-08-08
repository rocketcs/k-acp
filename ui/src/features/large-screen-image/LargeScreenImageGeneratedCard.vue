<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{
  imageUrl: string
  messageId: string
  busy?: boolean
  onAnalyze: () => void
  mode?: 'card' | 'placeholder'
}>()

const previewVisible = ref(false)

function fileNameFromUrl(url: string) {
  try {
    const pathname = new URL(url).pathname
    const name = decodeURIComponent(pathname.split('/').filter(Boolean).pop() || '')
    return name && /\.[a-z0-9]+$/i.test(name) ? name : `large-screen-image-${Date.now()}.png`
  } catch {
    return `large-screen-image-${Date.now()}.png`
  }
}

function objectStorageDownloadUrl(url: string) {
  const filename = fileNameFromUrl(url)
  try {
    const downloadUrl = new URL(url)
    downloadUrl.searchParams.set('response-content-disposition', `attachment; filename="${filename}"; filename*=UTF-8''${encodeURIComponent(filename)}`)
    downloadUrl.searchParams.set('response-content-type', 'application/octet-stream')
    return downloadUrl.href
  } catch {
    return url
  }
}

function clickDownloadLink(href: string, filename: string, target = '_self') {
  const link = document.createElement('a')
  link.href = href
  link.download = filename
  link.target = target
  link.rel = 'noopener noreferrer'
  document.body.appendChild(link)
  link.click()
  link.remove()
}

async function downloadImage() {
  const filename = fileNameFromUrl(props.imageUrl)
  try {
    const response = await fetch(props.imageUrl)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const contentType = response.headers.get('content-type') || ''
    if (!contentType.startsWith('image/')) throw new Error(`invalid content-type: ${contentType}`)
    const blob = await response.blob()
    const objectUrl = URL.createObjectURL(blob)
    clickDownloadLink(objectUrl, filename)
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 5000)
  } catch {
    clickDownloadLink(objectStorageDownloadUrl(props.imageUrl), filename, '_blank')
  }
}
</script>

<template>
  <section v-if="mode === 'placeholder'" class="large-screen-generated-card-placeholder" :data-large-screen-generated-message-id="messageId" aria-hidden="true" />
  <section v-else class="large-screen-generated-card" :data-large-screen-generated-message-id="messageId">
    <button class="large-screen-generated-card-preview-trigger" type="button" title="点击预览图片" @click="previewVisible = true">
      <img class="large-screen-generated-card-image" :src="imageUrl" alt="当前生成的大屏图片">
    </button>
    <div class="large-screen-generated-card-actions">
      <span>可将此结果作为新的参考图重新识别</span>
      <button type="button" @click="previewVisible = true">预览图片</button>
      <button type="button" @click="downloadImage">下载图片</button>
      <button type="button" :disabled="busy" @click="onAnalyze">识别此生成图</button>
    </div>
    <Teleport to="body">
      <div v-if="previewVisible" class="large-screen-generated-preview" role="dialog" aria-modal="true" aria-label="生成图片预览" @click.self="previewVisible = false">
        <button class="large-screen-generated-preview-close" type="button" aria-label="关闭预览" @click="previewVisible = false">×</button>
        <img :src="imageUrl" alt="生成图片预览">
        <button class="large-screen-generated-preview-download" type="button" @click="downloadImage">下载图片</button>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
.large-screen-generated-card { display: grid; gap: 10px; width: 100%; }.large-screen-generated-card:global(.large-screen-generated-card-highlighted) { animation: large-screen-generated-card-flash 1.8s ease-out; }.large-screen-generated-card-preview-trigger { display: block; width: 100%; padding: 0; border: 0; border-radius: 10px; background: transparent; cursor: zoom-in; }.large-screen-generated-card-preview-trigger:focus-visible { outline: 2px solid #0ea5e9; outline-offset: 3px; }.large-screen-generated-card-image { display: block; width: 100%; max-height: 720px; object-fit: contain; border-radius: 10px; background: #f1f5f9; }.large-screen-generated-card-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; color: #64748b; font-size: 12px; }.large-screen-generated-card-actions button { padding: 6px 10px; border: 1px solid #0369a1; border-radius: 6px; background: #fff; color: #075985; cursor: pointer; font: inherit; }.large-screen-generated-card-actions button:disabled { cursor: not-allowed; opacity: .55; }.large-screen-generated-preview { position: fixed; inset: 0; z-index: 3000; display: grid; place-items: center; padding: 48px; background: rgb(2 6 23 / 86%); backdrop-filter: blur(12px); }.large-screen-generated-preview img { max-width: min(96vw, 1600px); max-height: 88vh; object-fit: contain; border-radius: 12px; box-shadow: 0 24px 80px rgb(0 0 0 / 45%); }.large-screen-generated-preview-close, .large-screen-generated-preview-download { position: fixed; border: 1px solid rgb(255 255 255 / 28%); border-radius: 999px; background: rgb(15 23 42 / 74%); color: #fff; cursor: pointer; }.large-screen-generated-preview-close { top: 22px; right: 24px; width: 42px; height: 42px; font-size: 28px; line-height: 36px; }.large-screen-generated-preview-download { right: 24px; bottom: 24px; padding: 10px 16px; font: inherit; }@keyframes large-screen-generated-card-flash { 0%, 55% { box-shadow: 0 0 0 4px rgb(14 165 233 / 80%); } 100% { box-shadow: 0 0 0 0 transparent; } }
</style>
