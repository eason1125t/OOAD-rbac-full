<script setup lang="ts">
// 员工和角色目录共用的关系维护弹窗，保留当前对象和两份独立分页。
import { computed, onMounted, ref } from 'vue'
import { errorMessage, list, write, type NamedItem, type Page, type Permission } from './api'

const props = defineProps<{ kind: 'employee' | 'role'; subject: NamedItem }>()
const emit = defineEmits<{ close: [] }>()
const isEmployee = props.kind === 'employee'
const noun = isEmployee ? '角色' : '权限'
const base = isEmployee ? `/api/employees/${props.subject.id}/roles` : `/api/roles/${props.subject.id}/permissions`
const optionsPath = isEmployee ? '/api/roles' : '/api/permissions'
type Choice = NamedItem | Permission
const dialog = ref<HTMLDialogElement>()
const assigned = ref<Page<Choice>>()
const choices = ref<Page<Choice>>()
const selected = ref<number>()
const busy = ref(false)
const failed = ref(false)
const error = ref('')
const message = ref('')
const size = 15
const ready = computed(() => Boolean(assigned.value && choices.value) && !failed.value)

/** 刷新现有关系与可选值，失败时保留重试入口。 */
async function run(operation: () => Promise<void>) {
  busy.value = true
  error.value = ''
  failed.value = false
  try {
    await operation()
  } catch (failure) {
    error.value = errorMessage(failure)
    failed.value = true
  } finally {
    busy.value = false
  }
}

async function load() {
  await run(async () => {
    const [current, options] = await Promise.all([list<Choice>(base, assigned.value?.page, size),
      list<Choice>(optionsPath, choices.value?.page, size)])
    assigned.value = current
    choices.value = options
  })
}

async function changePage(kind: 'assigned' | 'choices', page: number) {
  await run(async () => {
    if (kind === 'assigned') assigned.value = await list<Choice>(base, page, size)
    else {
      choices.value = await list<Choice>(optionsPath, page, size)
      selected.value = undefined
    }
  })
}

/** 提交后回读最新关系。删除末页最后一项时回到仍有记录的页。 */
async function change(method: 'PUT' | 'DELETE', id?: number) {
  if (!ready.value || id == null) return
  message.value = ''
  await run(async () => {
    await write(method, `${base}/${id}`)
    message.value = method === 'PUT' ? `已授予${noun}` : `已撤销${noun}`
    if (method === 'PUT') selected.value = undefined
    const target = assigned.value?.page ?? 1
    const latest = await list<Choice>(base, target, size)
    const lastPage = Math.max(1, Math.ceil(latest.total / size))
    assigned.value = target > lastPage ? await list<Choice>(base, lastPage, size) : latest
  })
}

function optionLabel(item: Choice) {
  return 'code' in item ? `${item.name}（${item.code}${item.systemId ? ` / 系统 ${item.systemId}` : ''}）` : item.name
}

onMounted(() => {
  dialog.value?.showModal()
  void load()
})
</script>

<template>
  <dialog ref="dialog" class="editor assignment-editor" aria-labelledby="assignment-title"
      @cancel="busy && $event.preventDefault()" @close="emit('close')">
    <div class="assignment-heading">
      <div><h2 id="assignment-title">管理{{ noun }}</h2><p>{{ subject.name }}</p></div>
      <button :disabled="busy" @click="dialog?.close()">关闭</button>
    </div>
    <p v-if="message" class="notice success" role="status">{{ message }}</p>
    <p v-if="error" class="notice danger" role="alert">{{ error }} <button @click="load()">重试</button></p>
    <p v-if="busy && !ready" class="muted" role="status">正在加载…</p>
    <div v-if="choices" class="assignment-add">
      <label>待授予{{ noun }}
        <select v-model="selected" :disabled="busy || !ready">
          <option :value="undefined" disabled>选择{{ noun }}</option>
          <option v-for="item in choices.items" :key="item.id" :value="item.id">{{ optionLabel(item) }}</option>
        </select>
      </label>
      <button class="primary" :disabled="busy || !ready || selected == null" @click="change('PUT', selected)">授予{{ noun }}</button>
    </div>
    <div v-if="choices && choices.total > size" class="pager">
      <button :disabled="busy || choices.page <= 1" @click="changePage('choices', choices.page - 1)">上一页</button>
      <span>可选{{ noun }}第 {{ choices.page }} 页</span>
      <button :disabled="busy || choices.page * size >= choices.total" @click="changePage('choices', choices.page + 1)">下一页</button>
    </div>
    <h3>已分配{{ noun }}</h3>
    <ul v-if="assigned?.items.length" class="relations">
      <li v-for="item in assigned.items" :key="item.id">
        <span>{{ optionLabel(item) }}</span>
        <button :disabled="busy || !ready" @click="change('DELETE', item.id)">撤销{{ noun }}</button>
      </li>
    </ul>
    <p v-else-if="assigned" class="muted">暂无{{ noun }}分配</p>
    <div v-if="assigned && assigned.total > size" class="pager">
      <button :disabled="busy || assigned.page <= 1" @click="changePage('assigned', assigned.page - 1)">上一页</button>
      <span>已分配{{ noun }}第 {{ assigned.page }} 页</span>
      <button :disabled="busy || assigned.page * size >= assigned.total" @click="changePage('assigned', assigned.page + 1)">下一页</button>
    </div>
  </dialog>
</template>
