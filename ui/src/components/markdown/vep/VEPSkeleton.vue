<template>
  <div class="vep-skeleton">
    <div class="vep-skeleton-inner">
      <!-- 卡片骨架区 -->
      <div class="vep-skeleton-header">
        <span class="vep-skeleton-dot"></span>
        <span class="vep-skeleton-title">正在生成数据卡片...</span>
      </div>
      <div class="vep-skeleton-cards">
        <div
          v-for="n in 3"
          :key="'card-' + n"
          class="vep-skeleton-card"
          :style="{ animationDelay: `${0.2 + n * 0.15}s` }"
        >
          <div class="vep-skeleton-card-label"></div>
          <div class="vep-skeleton-card-value"></div>
        </div>
      </div>
      <!-- 图表骨架区 -->
      <div class="vep-skeleton-chart">
        <div class="vep-skeleton-chart-bars">
          <div
            v-for="n in 5"
            :key="'bar-' + n"
            class="vep-skeleton-chart-bar"
            :style="{
              height: `${30 + n * 12}%`,
              animationDelay: `${0.5 + n * 0.1}s`
            }"
          ></div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * VEP 流式加载占位卡片
 *
 * JSON 不完整时展示优雅的骨架屏动画，
 * 包含卡片指标占位和图表柱状占位。
 */
</script>

<style scoped>
.vep-skeleton {
  margin: 12px 0;
  border-radius: 12px;
  overflow: hidden;
  position: relative;
  background: #fafbfc;
  border: 1px solid #e0e3e8;
}

/* 旋转渐变边框 */
.vep-skeleton::before {
  content: '';
  position: absolute;
  inset: -1px;
  border-radius: 12px;
  padding: 1px;
  background: linear-gradient(
    120deg,
    #e8ecf1 0%,
    #c4cad4 25%,
    #e8ecf1 40%,
    #fafbfc 60%,
    #e8ecf1 75%,
    #c4cad4 100%
  );
  background-size: 300% 100%;
  -webkit-mask: linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0);
  -webkit-mask-composite: xor;
  mask-composite: exclude;
  opacity: 0.5;
  animation: vepBorderShimmer 3s ease-in-out infinite;
}

@keyframes vepBorderShimmer {
  0% { background-position: 100% 0; }
  50% { background-position: 0% 0; }
  100% { background-position: 100% 0; }
}

.vep-skeleton-inner {
  position: relative;
  padding: 18px 20px;
  z-index: 1;
}

/* 标题区域 */
.vep-skeleton-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.vep-skeleton-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c4cad4;
  animation: vepPulse 1.5s ease-in-out infinite;
}

.vep-skeleton-title {
  font-size: 13px;
  color: #a0a4ab;
  animation: vepPulseText 1.5s ease-in-out infinite;
}

@keyframes vepPulse {
  0%, 100% { opacity: 0.35; }
  50% { opacity: 0.75; }
}

@keyframes vepPulseText {
  0%, 100% { opacity: 0.5; }
  50% { opacity: 0.9; }
}

/* 卡片骨架网格 */
.vep-skeleton-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 20px;
}

.vep-skeleton-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  background: #F5F6F8;
  border-radius: 8px;
  animation: vepFadeSlideIn 0.5s ease both;
}

@keyframes vepFadeSlideIn {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.vep-skeleton-card-label {
  height: 10px;
  width: 60%;
  border-radius: 5px;
  background: linear-gradient(90deg, #eef0f4 25%, #e4e7ed 50%, #eef0f4 75%);
  background-size: 200% 100%;
  animation: vepShimmer 1.8s ease-in-out infinite;
}

.vep-skeleton-card-value {
  height: 18px;
  width: 80%;
  border-radius: 5px;
  background: linear-gradient(90deg, #f2f4f7 25%, #e8eaef 50%, #f2f4f7 75%);
  background-size: 200% 100%;
  animation: vepShimmer 1.8s ease-in-out infinite;
  animation-delay: 0.1s;
}

/* 图表骨架区 */
.vep-skeleton-chart {
  height: 120px;
  padding: 12px;
  background: #F5F6F8;
  border-radius: 8px;
  animation: vepFadeSlideIn 0.5s ease both;
  animation-delay: 0.6s;
}

.vep-skeleton-chart-bars {
  display: flex;
  align-items: flex-end;
  justify-content: space-around;
  height: 100%;
  gap: 8px;
}

.vep-skeleton-chart-bar {
  flex: 1;
  border-radius: 4px 4px 0 0;
  background: linear-gradient(90deg, #e4e7ed 25%, #d8dce5 50%, #e4e7ed 75%);
  background-size: 200% 100%;
  animation: vepShimmer 1.8s ease-in-out infinite;
}

@keyframes vepShimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
</style>
