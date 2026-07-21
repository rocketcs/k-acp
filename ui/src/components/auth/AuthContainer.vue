<script setup lang="ts">
/**
 * 认证页面容器组件 — 左右双栏布局
 *
 * @author huxuehao
 */
import BackButton from './BackButton.vue'
import brandLogo from '@/assets/images/logo/logo.png'

interface Props {
  showBack?: boolean
  backTo?: string
}

withDefaults(defineProps<Props>(), {
  showBack: false,
  backTo: '/login',
})

/** 左侧品牌特性列表 */
</script>

<template>
  <div class="auth-container">
    <section class="auth-brand-panel" aria-label="金智维智能体平台">
      <div class="brand-glow brand-glow-blue" />
      <div class="brand-glow brand-glow-red" />
      <div class="brand-grid" />

      <div class="auth-brand-content">
        <div class="brand-mark-wrap">
          <img class="auth-brand-logo" :src="brandLogo" alt="金智维智能体平台" />
        </div>
        <p class="brand-kicker">KINGSWARE · AI AGENT PLATFORM</p>
        <p class="brand-caption">智能体管理与协作平台</p>
      </div>

      <div class="brand-corner brand-corner-top" />
      <div class="brand-corner brand-corner-bottom" />
    </section>

    <section class="auth-form-panel">
      <BackButton v-if="showBack" :to="backTo" />
      <ACard class="auth-card" :bordered="false" style="box-shadow: none">
        <slot />
      </ACard>
      <div class="powered-by">Powered by Kingsware</div>
    </section>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/modules/auth' as *;

.auth-container {
  position: fixed;
  inset: 0;
  display: grid;
  grid-template-columns: minmax(420px, 0.95fr) minmax(480px, 1.05fr);
  width: 100vw;
  height: 100vh;
  height: 100dvh;
  min-height: 0;
  max-width: none;
  margin: 0;
  overflow: hidden;
  background: #f7f9fc;
}

:global(html),
:global(body),
:global(#app) {
  width: 100%;
  height: 100%;
  min-height: 0;
  margin: 0;
  overflow: hidden;
}

.auth-brand-panel {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  isolation: isolate;
  background:
    radial-gradient(circle at 20% 20%, rgba(15, 116, 255, 0.12), transparent 32%),
    linear-gradient(145deg, #ffffff 0%, #f1f6fc 58%, #e7f0fa 100%);
}

.auth-brand-content {
  position: relative;
  z-index: 2;
  width: min(78%, 460px);
  animation: brand-rise 0.7s ease-out both;
}

.brand-mark-wrap {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  padding: 22px 26px;
  border: 1px solid rgba(255, 255, 255, 0.88);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.62);
  box-shadow: 0 24px 70px rgba(27, 69, 119, 0.13), inset 0 1px 0 rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(12px);
}

.auth-brand-logo {
  display: block;
  width: min(100%, 390px);
  height: auto;
}

.brand-kicker {
  margin: 28px 0 0;
  color: #50709a;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.22em;
}

.brand-caption {
  margin: 10px 0 0;
  color: #193557;
  font-size: clamp(20px, 2vw, 28px);
  font-weight: 600;
  letter-spacing: 0.08em;
}

.brand-grid {
  position: absolute;
  inset: 0;
  z-index: -1;
  opacity: 0.36;
  background-image: linear-gradient(rgba(44, 93, 148, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(44, 93, 148, 0.08) 1px, transparent 1px);
  background-size: 42px 42px;
  mask-image: linear-gradient(135deg, black 0%, transparent 72%);
}

.brand-glow {
  position: absolute;
  z-index: -1;
  border-radius: 50%;
  filter: blur(2px);
  pointer-events: none;
}

.brand-glow-blue {
  top: 12%;
  right: -13%;
  width: 340px;
  height: 340px;
  background: rgba(15, 116, 255, 0.11);
}

.brand-glow-red {
  bottom: -15%;
  left: -10%;
  width: 270px;
  height: 270px;
  background: rgba(229, 0, 28, 0.08);
}

.brand-corner {
  position: absolute;
  width: 84px;
  height: 84px;
  border-color: rgba(15, 116, 255, 0.35);
  border-style: solid;
}

.brand-corner-top {
  top: 52px;
  left: 52px;
  border-width: 1px 0 0 1px;
}

.brand-corner-bottom {
  right: 52px;
  bottom: 52px;
  border-width: 0 1px 1px 0;
  border-color: rgba(229, 0, 28, 0.3);
}

.auth-form-panel {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 0;
  padding: 56px 48px;
  overflow: hidden;
  background: #f8fafc;
}

.auth-form-panel :deep(.powered-by) {
  position: absolute;
  right: 48px;
  bottom: 18px;
  left: 48px;
  transform: none;
  padding: 0;
  border: 0;
  background: transparent;
  color: #98a2b3;
  font-size: 12px;
  letter-spacing: 0.04em;
  text-align: center;
}

.auth-card {
  width: 100%;
  max-width: 460px;
  padding: 42px 46px 38px;
  border: 1px solid rgba(224, 230, 238, 0.92);
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 24px 70px rgba(37, 56, 82, 0.1) !important;
  max-height: calc(100dvh - 112px);
  overflow-y: auto;
  animation: form-rise 0.7s 0.08s ease-out both;
}

@keyframes brand-rise {
  from {
    opacity: 0;
    transform: translateY(18px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes form-rise {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 900px) {
  .auth-container {
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  .auth-brand-panel {
    flex: 0 0 190px;
    min-height: 190px;
    height: 190px;
    padding: 36px 24px 30px;
  }

  .auth-brand-content {
    width: min(100%, 380px);
    text-align: center;
  }

  .brand-mark-wrap {
    padding: 14px 18px;
    border-radius: 14px;
  }

  .auth-brand-logo {
    width: min(100%, 310px);
  }

  .brand-kicker {
    margin-top: 15px;
    font-size: 9px;
  }

  .brand-caption {
    margin-top: 5px;
    font-size: 17px;
  }

  .brand-corner {
    width: 48px;
    height: 48px;
  }

  .brand-corner-top {
    top: 20px;
    left: 20px;
  }

  .brand-corner-bottom {
    right: 20px;
    bottom: 20px;
  }

  .auth-form-panel {
    flex: 1 1 auto;
    height: auto;
    min-height: 0;
    padding: 30px 20px 64px;
  }

  .auth-card {
    max-height: calc(100dvh - 284px);
  }

  .auth-form-panel :deep(.powered-by) {
    right: 20px;
    left: 20px;
  }
}

@media (max-width: 480px) {
  .auth-brand-panel {
    flex-basis: 154px;
    min-height: 154px;
    height: 154px;
    padding: 24px 18px 20px;
  }

  .auth-brand-logo {
    width: min(100%, 260px);
  }

  .brand-mark-wrap {
    padding: 10px 14px;
  }

  .brand-kicker {
    display: none;
  }

  .brand-caption {
    margin-top: 7px;
    font-size: 15px;
  }

  .auth-form-panel {
    min-height: 0;
    padding: 24px 16px 60px;
  }

  .auth-card {
    max-height: calc(100dvh - 238px);
  }

  .auth-form-panel :deep(.powered-by) {
    right: 16px;
    left: 16px;
  }

  .auth-card {
    padding: 30px 22px 28px;
    border-radius: 18px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .auth-brand-content,
  .auth-card {
    animation: none;
  }
}
</style>
