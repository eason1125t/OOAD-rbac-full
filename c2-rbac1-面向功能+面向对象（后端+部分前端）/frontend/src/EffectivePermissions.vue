<script setup lang="ts">
// 按业务系统查看员工的有效权限，列表和授权共用后端的实时判定。
import { onMounted, ref } from 'vue'
import { errorMessage, list, type BusinessSystem, type NamedItem, type Page, type Permission } from './api'
import { businessSystems } from './referenceData'

const props = defineProps<{ employee: NamedItem }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
const systems = ref<BusinessSystem[]>([])
const code = ref('')
const results = ref<Page<Permission>>()
const error = ref('')
const busy = ref(false)
const size = 12

async function load(page = 1) {
  if (!code.value) return
  busy.value = true
  results.value = undefined
  error.value = ''
  try {
    const query = new URLSearchParams({ systemCode: code.value })
    results.value = await list<Permission>(`/api/employees/${props.employee.id}/effective-permissions?${query}`, page, size)
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  dialog.value?.showModal()
  busy.value = true
  try {
    systems.value = await businessSystems()
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
})
</script>

<template>
  <dialog ref="dialog" class="editor assignment-editor" aria-labelledby="effective-title"
      @cancel="busy && $event.preventDefault()" @close="emit('close')">
    <div class="assignment-heading">
      <div><h2 id="effective-title">员工有效权限</h2><p>{{ employee.name }}</p></div>
      <button :disabled="busy" @click="dialog?.close()">关闭</button>
    </div>
    <form class="assignment-add" @submit.prevent="load()">
      <label>业务系统
        <select v-model="code" :disabled="busy" @change="results = undefined; error = ''">
          <option value="" disabled>选择业务系统</option>
          <option v-for="system in systems" :key="system.id" :value="system.code">{{ system.name }}</option>
        </select>
      </label>
      <button class="primary" :disabled="busy || !code">查询权限</button>
    </form>
    <p v-if="busy" role="status">正在加载…</p>
    <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
    <template v-if="results">
      <ul v-if="results.items.length" class="relations">
        <li v-for="permission in results.items" :key="permission.id">
          <span>{{ permission.name }}</span><code>{{ permission.code }}</code>
        </li>
      </ul>
      <p v-else class="muted">员工在该系统暂无有效权限。</p>
      <div v-if="results.total > size" class="pager">
        <button :disabled="busy || results.page <= 1" @click="load(results.page - 1)">上一页</button>
        <span>第 {{ results.page }} 页</span>
        <button :disabled="busy || results.page * size >= results.total" @click="load(results.page + 1)">下一页</button>
      </div>
    </template>
  </dialog>
</template>
