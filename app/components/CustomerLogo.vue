<script setup lang="ts">
const props = defineProps<{
  src?: string | null
  alt: string
}>()

const hasLoaded = ref(false)
const hasFailed = ref(false)

watch(() => props.src, () => {
  hasLoaded.value = false
  hasFailed.value = false
}, { flush: 'sync' })
</script>

<template>
  <img
    v-if="src && !hasFailed"
    v-show="hasLoaded"
    :key="src"
    :src="src"
    :alt="alt"
    @load="hasLoaded = true"
    @error="hasFailed = true"
  >
</template>
