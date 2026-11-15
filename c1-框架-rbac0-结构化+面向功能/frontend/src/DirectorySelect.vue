<script setup lang="ts">
// 按名称展示后端可见选项，绑定的值仍是资源 ID。
import type { NamedItem } from './api'

defineProps<{ options: NamedItem[]; empty: string; required?: boolean }>()
const model = defineModel<number>()
</script>

<template>
  <select v-model="model" :required="required">
    <option :value="undefined">{{ empty }}</option>
    <option v-for="item in options" :key="item.id" :value="item.id">{{ item.name }}</option>
  </select>
</template>
