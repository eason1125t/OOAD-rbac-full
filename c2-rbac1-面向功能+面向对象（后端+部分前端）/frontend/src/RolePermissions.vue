<script setup lang="ts">
// 按系统选择授权动作，分别读取角色的直接授权和继承来源。
import { computed, onMounted, ref } from 'vue'
import { errorMessage, list, write, type BusinessSystem, type NamedItem, type Page, type Permission } from './api'
import { allPages, businessSystems } from './referenceData'

const props = defineProps<{ role: NamedItem }>()
const emit = defineEmits<{ close: [] }>()
const base = `/api/roles/${props.role.id}`
const sections = [
  { key: 'direct', title: '直接授予', path: `${base}/permissions` },
  { key: 'inherited', title: '继承权限', path: `${base}/inherited-permissions` },
] as const
type Section = typeof sections[number]
const dialog = ref<HTMLDialogElement>()
const systems = ref<BusinessSystem[]>([])
const catalog = ref<Permission[]>([])
const pages = ref<Partial<Record<Section['key'], Page<Permission>>>>({})
const selectedSystem = ref<number | 'management'>()
const selectedAction = ref<number>()
const busy = ref(false)
const error = ref('')
const message = ref('')
const pageSize = 12
const actions = computed(() => catalog.value.filter(item =>
  item.systemId === (selectedSystem.value === 'management' ? null : selectedSystem.value)))
const systemNames = computed(() => new Map(systems.value.map(system => [system.id, system.name])))

function systemName(id: number | null) {
  return id === null ? '管理权限' : systemNames.value.get(id) ?? '系统不在可见范围内'
}

async function run(task: () => Promise<void>) {
  busy.value = true
  error.value = ''
  try {
    await task()
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

async function load() {
  await run(async () => {
    const [apps, definitions, direct, inherited] = await Promise.all([
      businessSystems(), allPages<Permission>('/api/permissions'),
      list<Permission>(sections[0].path, 1, pageSize), list<Permission>(sections[1].path, 1, pageSize),
    ])
    systems.value = apps
    catalog.value = definitions
    pages.value = { direct, inherited }
  })
}

async function changePage(section: Section, page: number) {
  await run(async () => {
    pages.value[section.key] = await list<Permission>(section.path, page, pageSize)
  })
}

/** 只写当前角色的直接授权，撤销末页的最后一项后回到上一页。 */
async function change(method: 'PUT' | 'DELETE', id?: number) {
  if (id == null || busy.value) return
  message.value = ''
  await run(async () => {
    await write(method, `${sections[0].path}/${id}`)
    if (method === 'PUT') selectedAction.value = undefined
    const page = method === 'PUT' ? 1 : pages.value.direct?.page ?? 1
    const latest = await list<Permission>(sections[0].path, page, pageSize)
    const last = Math.max(1, Math.ceil(latest.total / pageSize))
    pages.value.direct = page > last ? await list<Permission>(sections[0].path, last, pageSize) : latest
    message.value = method === 'PUT' ? '已授予权限' : '已撤销直接授权'
  })
}

onMounted(() => {
  dialog.value?.showModal()
  void load()
})
</script>

<template>
  <dialog ref="dialog" class="editor assignment-editor" aria-labelledby="role-permissions-title"
      @cancel="busy && $event.preventDefault()" @close="emit('close')">
    <div class="assignment-heading">
      <div><h2 id="role-permissions-title">管理权限</h2><p>{{ role.name }}</p></div>
      <button :disabled="busy" @click="dialog?.close()">关闭</button>
    </div>
    <p v-if="error" class="notice danger" role="alert">{{ error }} <button :disabled="busy" @click="load()">重新读取</button></p>
    <p v-if="message" class="notice success" role="status">{{ message }}</p>
    <p v-if="busy && !pages.direct" class="muted" role="status">正在加载权限…</p>
    <div v-if="pages.direct" class="permission-controls">
      <label>业务系统
        <select v-model="selectedSystem" :disabled="busy" @change="selectedAction = undefined">
          <option :value="undefined" disabled>选择业务系统</option>
          <option v-for="system in systems" :key="system.id" :value="system.id">{{ system.name }}</option>
          <option v-if="catalog.some(item => item.systemId === null)" value="management">管理权限</option>
        </select>
      </label>
      <label>动作
        <select v-model="selectedAction" :disabled="busy || selectedSystem == null || !actions.length">
          <option :value="undefined" disabled>{{ selectedSystem != null && !actions.length ? '暂无可选动作' : '选择动作' }}</option>
          <option v-for="item in actions" :key="item.id" :value="item.id">
            {{ actions.some(other => other.id !== item.id && other.name === item.name) ? `${item.name}（${item.code}）` : item.name }}
          </option>
        </select>
      </label>
      <button class="primary" :disabled="busy || selectedAction == null" @click="change('PUT', selectedAction)">授予权限</button>
    </div>
    <section v-for="section in sections" :key="section.key" class="permission-group" :aria-label="section.title">
      <template v-if="pages[section.key]">
        <h3>{{ section.title }}</h3>
        <ul v-if="pages[section.key]?.items.length" class="relations">
          <li v-for="permission in pages[section.key]?.items" :key="permission.id">
            <div class="permission-name">
              <strong>{{ permission.name }}</strong>
              <small>{{ systemName(permission.systemId) }}</small>
            </div>
            <button v-if="section.key === 'direct'" :disabled="busy" @click="change('DELETE', permission.id)">撤销权限</button>
            <small v-else class="inherited-permission">继承权限，无法修改</small>
          </li>
        </ul>
        <p v-else class="muted">{{ section.key === 'direct' ? '暂无直接授予的权限' : '暂无继承权限' }}</p>
        <div v-if="(pages[section.key]?.total ?? 0) > pageSize" class="pager">
          <button :disabled="busy || pages[section.key]?.page === 1"
              @click="changePage(section, (pages[section.key]?.page ?? 1) - 1)">上一页</button>
          <span>第 {{ pages[section.key]?.page }} 页</span>
          <button :disabled="busy || (pages[section.key]?.page ?? 1) * pageSize >= (pages[section.key]?.total ?? 0)"
              @click="changePage(section, (pages[section.key]?.page ?? 1) + 1)">下一页</button>
        </div>
      </template>
    </section>
  </dialog>
</template>
