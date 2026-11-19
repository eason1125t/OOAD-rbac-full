<script setup lang="ts">
// 员工和角色目录共用的关系维护弹窗，保留当前对象和两份独立分页。
import { computed, onMounted, ref } from 'vue'
import { ApiError, errorMessage, list, write, type NamedItem, type Page, type Role } from './api'

const props = defineProps<{ kind: 'employee' | 'inheritance'; subject: NamedItem & { ownerOrganizationId?: number | null } }>()
const emit = defineEmits<{ close: [] }>()
const isEmployee = props.kind === 'employee'
const isInheritance = props.kind === 'inheritance'
const base = isEmployee ? `/api/employees/${props.subject.id}/roles`
  : `/api/roles/${props.subject.id}/parents`
const optionsPath = '/api/roles'
const dialog = ref<HTMLDialogElement>()
const assigned = ref<Page<Role>>()
const choices = ref<Page<Role>>()
const selected = ref<number>()
const busy = ref(false)
const failed = ref(false)
const error = ref('')
const message = ref('')
const size = 15
const ready = computed(() => Boolean(assigned.value && choices.value) && !failed.value)
const available = computed(() => choices.value?.items.filter(item => !isInheritance ||
  (item.ownerOrganizationId === props.subject.ownerOrganizationId && item.id !== props.subject.id)) ?? [])
const heading = isInheritance ? '继承管理' : '管理角色'
const addLabel = isInheritance ? '添加继承' : '授予角色'
const removeLabel = isInheritance ? '移除继承' : '撤销角色'

/** 刷新现有关系与可选值，失败时保留重试入口。 */
async function run(operation: () => Promise<void>, retainRelationsOnConflict = false) {
  busy.value = true
  error.value = ''
  failed.value = false
  try {
    await operation()
  } catch (failure) {
    error.value = errorMessage(failure)
    failed.value = !(retainRelationsOnConflict && failure instanceof ApiError && failure.status === 409)
  } finally {
    busy.value = false
  }
}

async function load() {
  await run(async () => {
    const [current, options] = await Promise.all([list<Role>(base, assigned.value?.page, size),
      list<Role>(optionsPath, choices.value?.page, size)])
    assigned.value = current
    choices.value = options
  })
}

async function changePage(kind: 'assigned' | 'choices', page: number) {
  await run(async () => {
    if (kind === 'assigned') assigned.value = await list<Role>(base, page, size)
    else {
      choices.value = await list<Role>(optionsPath, page, size)
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
    message.value = isInheritance ? (method === 'PUT' ? '已添加继承' : '已移除继承')
      : (method === 'PUT' ? '已授予角色' : '已撤销角色')
    if (method === 'PUT') selected.value = undefined
    const target = assigned.value?.page ?? 1
    const latest = await list<Role>(base, target, size)
    const lastPage = Math.max(1, Math.ceil(latest.total / size))
    assigned.value = target > lastPage ? await list<Role>(base, lastPage, size) : latest
  }, isInheritance)
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
      <div><h2 id="assignment-title">{{ heading }}</h2><p>{{ subject.name }}</p></div>
      <button :disabled="busy" @click="dialog?.close()">关闭</button>
    </div>
    <p v-if="message" class="notice success" role="status">{{ message }}</p>
    <p v-if="error" class="notice danger" role="alert">{{ error }} <button v-if="failed" @click="load()">重试</button></p>
    <p v-if="busy && !ready" class="muted" role="status">正在加载…</p>
    <div v-if="choices" class="assignment-add">
      <label>{{ isInheritance ? '继承自' : '待授予角色' }}
        <select v-model="selected" :disabled="busy || !ready">
          <option :value="undefined" disabled>选择角色</option>
          <option v-for="item in available" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
      </label>
      <button class="primary" :disabled="busy || !ready || selected == null" @click="change('PUT', selected)">{{ addLabel }}</button>
    </div>
    <div v-if="choices && choices.total > size" class="pager">
      <button :disabled="busy || choices.page <= 1" @click="changePage('choices', choices.page - 1)">上一页</button>
      <span>可选角色第 {{ choices.page }} 页</span>
      <button :disabled="busy || choices.page * size >= choices.total" @click="changePage('choices', choices.page + 1)">下一页</button>
    </div>
    <h3>{{ isInheritance ? '直接继承的角色' : '已分配角色' }}</h3>
    <ul v-if="assigned?.items.length" class="relations">
      <li v-for="item in assigned.items" :key="item.id">
        <span>{{ item.name }}</span>
        <button :disabled="busy || !ready" @click="change('DELETE', item.id)">{{ removeLabel }}</button>
      </li>
    </ul>
    <p v-else-if="assigned" class="muted">{{ isInheritance ? '暂无继承关系' : '暂无角色分配' }}</p>
    <div v-if="assigned && assigned.total > size" class="pager">
      <button :disabled="busy || assigned.page <= 1" @click="changePage('assigned', assigned.page - 1)">上一页</button>
      <span>已分配角色第 {{ assigned.page }} 页</span>
      <button :disabled="busy || assigned.page * size >= assigned.total" @click="changePage('assigned', assigned.page + 1)">下一页</button>
    </div>
  </dialog>
</template>
