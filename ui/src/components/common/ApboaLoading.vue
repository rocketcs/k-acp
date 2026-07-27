<template>
  <div class="apboa-loading" :class="sizeClass">
    <div class="logo-container">
      <img :src="brandLogo" alt="Kingsware 金智维">
    </div>
    <div v-if="tip" class="loading-tip">{{ tip }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import brandLogo from '@/assets/images/logo/logo.png'

interface Props {
  /** 加载提示文本 */
  tip?: string
  /** 加载动画尺寸 */
  size?: 'small' | 'default' | 'large'
  /** 是否全屏显示 */
  fullscreen?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  size: 'default',
  fullscreen: false,
})

const sizeClass = computed(() => ({
  [`size-${props.size}`]: true,
  fullscreen: props.fullscreen,
}))
</script>

<style scoped lang="scss">
.apboa-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 24px;
}

.apboa-loading.fullscreen {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background-color: rgba(255, 255, 255, 0.9);
}

.logo-container {
  width: 160px;
  height: 42px;

  img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: contain;
    animation: brand-pulse 1.4s ease-in-out infinite;
  }
}

.apboa-loading.size-small .logo-container {
  width: 100px;
  height: 28px;
}

.apboa-loading.size-large .logo-container {
  width: 220px;
  height: 58px;
}

.loading-tip {
  color: rgba(0, 0, 0, 0.65);
  font-size: 14px;
  text-align: center;
}

.apboa-loading.size-small .loading-tip {
  font-size: 12px;
}

.apboa-loading.size-large .loading-tip {
  font-size: 16px;
}

@keyframes brand-pulse {
  0%, 100% {
    opacity: 0.55;
    transform: scale(0.98);
  }

  50% {
    opacity: 1;
    transform: scale(1);
  }
}
</style>
