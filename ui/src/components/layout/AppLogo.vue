<script setup lang="ts">
import brandLogo from '@/assets/images/logo/logo.png'

const props = withDefaults(defineProps<{
  compact?: boolean
}>(), {
  compact: false,
})
</script>

<template>
  <div class="app-logo flex items-center cursor-pointer" :class="{ compact: props.compact }">
    <div class="logo-icon flex items-center justify-center">
      <img :src="brandLogo" alt="Kingsware 金智维" class="logo-image">
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/components/logo' as *;
</style>
