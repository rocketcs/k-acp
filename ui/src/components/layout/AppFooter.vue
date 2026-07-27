<script setup lang="ts">
/**
 * 应用底部组件
 *
 * @author huxuehao
 */
const currentYear = new Date().getFullYear()
</script>

<template>
  <div class="app-footer flex items-center justify-center">
    <div class="footer-content flex flex-col items-center gap-sm">
      <div class="footer-description">
        <p>金智维智能体平台</p>
      </div>

      <div class="footer-copyright">
        <p>&copy; {{ currentYear }} Kingsware. All rights reserved.</p>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/components/footer' as *;
</style>
