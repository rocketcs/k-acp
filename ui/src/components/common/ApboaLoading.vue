<template>
  <div class="apboa-loading" :class="sizeClass">
    <div class="logo-container">
      <svg viewBox="0 0 300 300" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <!-- 波光渐变 -->
          <linearGradient :id="shimmerGradId" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%"   stop-color="white" stop-opacity="0"/>
            <stop offset="25%"  stop-color="white" stop-opacity="0.06"/>
            <stop offset="45%"  stop-color="white" stop-opacity="0.38"/>
            <stop offset="55%"  stop-color="white" stop-opacity="0.38"/>
            <stop offset="75%"  stop-color="white" stop-opacity="0.06"/>
            <stop offset="100%" stop-color="white" stop-opacity="0"/>
          </linearGradient>

          <!-- 遮罩 -->
          <mask :id="maskId">
            <rect width="300" height="300" fill="black"/>
            <path d="M 152 212 A 85 85 0 1 1 212 152"
                  fill="none" stroke="white" stroke-width="42" stroke-linecap="round"/>
            <circle cx="210" cy="210" r="24" fill="white"/>
          </mask>
        </defs>

        <!-- 圆环 -->
        <path d="M 152 212 A 85 85 0 1 1 212 152"
              fill="none"
              stroke="#DEDEDE"
              stroke-width="42"
              stroke-linecap="round"/>

        <!-- 圆点 -->
        <circle cx="210" cy="210" r="24" fill="#DEDEDE"/>

        <!-- 波光 -->
        <g :mask="`url(#${maskId})`">
          <g class="shimmer-sweep">
            <rect x="0" y="-60" width="620" height="500"
                  :fill="`url(#${shimmerGradId})`"
                  transform="rotate(-28 60 190)"/>
          </g>
        </g>
      </svg>
    </div>
    <div v-if="tip" class="loading-tip">{{ tip }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

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
  fullscreen: false
})

// 生成唯一的ID，避免多个实例冲突
const uniqueId = ref(Math.random().toString(36).substring(2, 9))
const shimmerGradId = computed(() => `shimmerGrad-${uniqueId.value}`)
const maskId = computed(() => `logoMask-${uniqueId.value}`)

const sizeClass = computed(() => ({
  [`size-${props.size}`]: true,
  'fullscreen': props.fullscreen
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
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 9999;
  background-color: rgba(255, 255, 255, 0.9);
}

.logo-container {
  width: 64px;
  height: 64px;
}

.logo-container svg {
  width: 100%;
  height: 100%;
  overflow: visible;
}

.apboa-loading.size-small .logo-container {
  width: 32px;
  height: 32px;
}

.apboa-loading.size-large .logo-container {
  width: 96px;
  height: 96px;
}

.loading-tip {
  font-size: 14px;
  color: rgba(0, 0, 0, 0.65);
  text-align: center;
}

.apboa-loading.size-small .loading-tip {
  font-size: 12px;
}

.apboa-loading.size-large .loading-tip {
  font-size: 16px;
}

/* ── 波光扫描动画 ── */
.shimmer-sweep {
  animation: shimmerMove 1.2s ease-in-out 0.8s infinite;
}

@keyframes shimmerMove {
  0%   { transform: translateX(-380px); }
  50%  { transform: translateX(560px); }
  100% { transform: translateX(560px); }
}
</style>
