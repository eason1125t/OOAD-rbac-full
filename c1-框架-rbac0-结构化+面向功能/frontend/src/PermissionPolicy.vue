<script setup lang="ts">
// 以选中的业务权限为上下文，读取并维护指定组织的直接策略。
import { computed, onMounted, ref } from 'vue'
import DirectorySelect from './DirectorySelect.vue'
import { ApiError, errorMessage, list, read, write, type BusinessSystem, type NamedItem, type Page, type Permission } from './api'
import { businessSystems, organizationOptions } from './referenceData'

const props = defineProps<{ permission: Permission }>()
const emit = defineEmits<{ close: [] }>()
type Policy = { status: 'OPEN' | 'DISABLED' | 'REOPENED'; roleIds: number[] }
const statusNames = { OPEN: '开放', DISABLED: '已停用', REOPENED: '已重新开放' }
const dialog = ref<HTMLDialogElement>()
const org = ref<number>()
const role = ref<number>()
const roles = ref<Page<NamedItem>>()
const organizations = ref<NamedItem[]>([])
const system = ref<BusinessSystem>()
const current = ref<Policy>()
const busy = ref(false)
const denied = ref(false)
const error = ref('')
const confirmation = ref(false)
const notice = ref('')
const size = 20
const path = computed(() => org.value && system.value
  ? `/api/organizations/${org.value}/business-systems/${encodeURIComponent(system.value.code)}/permissions/${encodeURIComponent(props.permission.code)}`
  : '')

/** 从服务器读取当前组织的状态及重授记录。 */
async function reload() {
  current.value = undefined
  if (path.value) current.value = await read<Policy>(path.value)
}

/** 执行请求并展示后端的范围、状态冲突或鉴权提示。 */
async function perform(task: () => Promise<void>, checkAccess = false) {
  busy.value = true
  error.value = ''
  try {
    await task()
  } catch (failure) {
    if (checkAccess && failure instanceof ApiError && failure.status === 403) denied.value = true
    error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

async function selectOrganization() {
  confirmation.value = false
  notice.value = ''
  role.value = undefined
  await perform(reload, true)
}

async function rolePage(page = 1) {
  await perform(async () => {
    roles.value = await list<NamedItem>('/api/roles', page, size)
    role.value = undefined
  })
}

/** 停用需显式确认，其他写入提交后重新读取策略。 */
async function update(action: 'disable' | 'reopen' | 'regrant') {
  if (!current.value || !path.value || busy.value || denied.value) return
  if (action === 'disable' && !confirmation.value) {
    confirmation.value = true
    return
  }
  confirmation.value = false
  notice.value = ''
  await perform(async () => {
    if (action === 'regrant') {
      if (role.value == null) return
      await write('PUT', `${path.value}/regrants/${role.value}`)
    } else await write(action === 'disable' ? 'PUT' : 'DELETE', `${path.value}/disabled`)
    notice.value = { disable: '已停用', reopen: '已重新开放', regrant: '已重授' }[action]
    await reload()
  })
}

onMounted(() => {
  dialog.value?.showModal()
  void perform(async () => {
    const [options, systems, availableRoles] = await Promise.all([
      organizationOptions(), businessSystems(), list<NamedItem>('/api/roles', 1, size),
    ])
    system.value = systems.find(item => item.id === props.permission.systemId)
    if (!system.value) throw new Error('当前业务系统不可用，请刷新权限目录。')
    organizations.value = options
    roles.value = availableRoles
  })
})
</script>

<template>
  <dialog ref="dialog" class="editor assignment-editor" aria-labelledby="policy-title"
      @cancel="busy && $event.preventDefault()" @close="emit('close')">
    <div class="assignment-heading">
      <div><h2 id="policy-title">组织策略</h2><p>{{ permission.name }}{{ system ? ` · ${system.name}` : '' }}</p></div>
      <button :disabled="busy" @click="dialog?.close()">关闭</button>
    </div>
    <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
    <p v-if="notice" class="notice success" role="status">{{ notice }}</p>
    <label class="policy-field">目标组织
      <DirectorySelect v-model="org" :options="organizations" empty="选择公司或部门" :disabled="busy || denied"
          @update:model-value="selectOrganization" />
    </label>
    <p v-if="busy" role="status">正在读取策略…</p>
    <div v-if="current" class="policy-body">
      <p>本级策略 <strong>{{ statusNames[current.status] }}</strong></p>
      <p class="muted">最终鉴权同时检查系统及适用上级的停用配置。</p>
      <div v-if="confirmation" class="notice danger">
        停用后，该组织范围内的审批将被拒绝，已有范围重授会清除。
      </div>
      <div class="directory-actions">
        <button v-if="current.status === 'DISABLED'" class="primary" :disabled="busy || denied" @click="update('reopen')">重新开放</button>
        <button v-else class="danger" :disabled="busy || denied" @click="update('disable')">{{ confirmation ? '确认停用' : '停用' }}</button>
        <button v-if="confirmation" @click="confirmation = false">取消停用</button>
      </div>
      <template v-if="current.status === 'REOPENED'">
        <label>待重授角色
          <DirectorySelect v-model="role" :options="roles?.items ?? []" empty="选择角色" :disabled="busy || denied" />
        </label>
        <div v-if="roles && roles.total > size" class="pager">
          <button :disabled="busy || roles.page <= 1" @click="rolePage(roles.page - 1)">上一页</button>
          <span>第 {{ roles.page }} 页</span>
          <button :disabled="busy || roles.page * size >= roles.total" @click="rolePage(roles.page + 1)">下一页</button>
        </div>
        <button class="primary" :disabled="busy || denied || !role || current.roleIds.includes(role)" @click="update('regrant')">重授角色</button>
        <p>已重授 {{ current.roleIds.length }} 个角色</p>
      </template>
    </div>
  </dialog>
</template>
