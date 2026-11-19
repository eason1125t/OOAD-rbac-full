<script setup lang="ts">
// 五类目录共用的分页表，显示后端返回的范围和原表字段。
import { computed, onMounted, ref } from 'vue'
import { errorMessage, list, write, type NamedItem } from './api'
import type { DirectorySection } from './adminSections'
import AssignmentEditor from './AssignmentEditor.vue'
import RolePermissions from './RolePermissions.vue'
import EffectivePermissions from './EffectivePermissions.vue'
import DirectorySelect from './DirectorySelect.vue'
import PermissionPolicy from './PermissionPolicy.vue'
import type { Permission } from './api'
import { businessSystems, organizationOptions } from './referenceData'

type Row = { id: number } & Record<string, unknown>
const props = defineProps<{ directory: DirectorySection; nested?: boolean }>()
const assignment = ref<{ id: number; name: string }>()
const policy = ref<Permission>()
const inheritance = ref<{ id: number; name: string; ownerOrganizationId: number | null }>()
const effective = ref<NamedItem>()
const rows = ref<Row[]>([])
const page = ref(1)
const pageSize = 15
const total = ref(0)
const loading = ref(false)
const error = ref('')
const saveError = ref('')
const status = ref('')
const saving = ref(false)
const editable = computed(() => ['employees', 'roles', 'permissions'].includes(props.directory.id))
const dialog = ref<HTMLDialogElement>()
const target = ref<Row>()
const operation = ref<'create' | 'edit' | 'remove'>('create')
const name = ref('')
const username = ref('')
const code = ref('')
const organization = ref<number>()
const system = ref<number>()
const job = ref('')
const phone = ref('')
const organizations = ref<NamedItem[]>([])
const systems = ref<NamedItem[]>([])
const loadingOptions = ref(false)
const optionsReady = ref(true)
const displayOrganizations = ref<Map<number, string>>()
const displaySystems = ref<Map<number, string>>()
const label = computed(() => ({ employees: '员工', roles: '角色', permissions: '权限' }[props.directory.id] ?? '记录'))

/** 编辑时不回填已遮盖的电话，避免把遮盖结果保存到数据库。 */
async function open(mode: typeof operation.value, row?: Row) {
  operation.value = mode
  target.value = row
  name.value = String(row?.name ?? '')
  username.value = ''
  code.value = ''
  organization.value = typeof row?.organizationId === 'number' ? row.organizationId : undefined
  system.value = undefined
  job.value = String(row?.jobTitle ?? '')
  phone.value = ''
  saveError.value = ''
  status.value = ''
  optionsReady.value = true
  dialog.value?.showModal()
  if (mode === 'create' || (mode === 'edit' && props.directory.id === 'employees')) {
    loadingOptions.value = true
    try {
      const [org, apps] = await Promise.all([organizationOptions(),
        props.directory.id === 'permissions' ? businessSystems() : []])
      organizations.value = org
      systems.value = apps.filter(item => Number.isSafeInteger(item.id))
        .map(item => ({ id: item.id, name: `${item.name}（${item.ownerOrganizationName}）` }))
    } catch (failure) {
      saveError.value = errorMessage(failure)
      optionsReady.value = false
    } finally {
      loadingOptions.value = false
    }
  }
}

/** 只提交当前资源允许修改的字段，范围与引用由后端验证。 */
async function save() {
  if (saving.value || loadingOptions.value || !optionsReady.value) return
  saving.value = true
  saveError.value = ''
  const base = props.directory.path
  const isEmployee = props.directory.id === 'employees'
  const isPermission = props.directory.id === 'permissions'
  const organizationId = typeof organization.value === 'number' ? organization.value : undefined
  const systemId = typeof system.value === 'number' ? system.value : undefined
  const fields: Record<string, unknown> = { name: name.value.trim() }
  try {
    if (operation.value === 'create') {
      if (isEmployee) Object.assign(fields, { username: username.value.trim(), organizationId,
        jobTitle: job.value, phone: phone.value })
      else Object.assign(fields, { ownerOrganizationId: organizationId })
      if (isPermission) Object.assign(fields, { code: code.value.trim(), systemId })
      await write('POST', base, fields)
    } else if (target.value) {
      const path = `${base}/${target.value.id}`
      if (operation.value === 'remove') await write('DELETE', path)
      else {
        if (isEmployee) Object.assign(fields, { organizationId, jobTitle: job.value,
          phone: phone.value || undefined })
        await write('PATCH', path, fields)
      }
    }
    dialog.value?.close()
    status.value = operation.value === 'remove' ? (isEmployee ? '员工已停用' : `${label.value}已删除`) : '已保存'
    await load(operation.value === 'remove' && !isEmployee && rows.value.length === 1 && page.value > 1
      ? page.value - 1 : page.value)
  } catch (failure) {
    saveError.value = errorMessage(failure)
  } finally {
    saving.value = false
  }
}

/** 请求分页成功后再更新页码，失败时可以重试。 */
async function load(target = page.value) {
  loading.value = true
  error.value = ''
  try {
    const [result] = await Promise.all([list<Row>(props.directory.path, target, pageSize), loadDisplayNames()])
    rows.value = result.items
    total.value = result.total
    page.value = result.page
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

/** 刷新也重新读取名称，按页切换时复用已读取的目录。 */
async function loadDisplayNames() {
  const { id } = props.directory
  await Promise.all([
    (id === 'roles' || id === 'permissions') && !displayOrganizations.value
      ? organizationOptions().then(items => {
        displayOrganizations.value = new Map(items.map(item => [item.id, item.name]))
      }) : undefined,
    id === 'permissions' && !displaySystems.value
      ? businessSystems().then(items => {
        displaySystems.value = new Map(items.map(item => [item.id, item.name]))
      }) : undefined,
  ])
}

function refresh() {
  displayOrganizations.value = undefined
  displaySystems.value = undefined
  void load()
}

/** 保留空值的可见位置，布尔状态使用管理员熟悉的说法。 */
function format(key: string, value: unknown) {
  if (key === 'ownerOrganizationId') {
    return typeof value === 'number' ? displayOrganizations.value?.get(value) ?? '组织不在可见范围内' : '全局共享'
  }
  if (key === 'systemId') {
    return typeof value === 'number' ? displaySystems.value?.get(value) ?? '系统不在可见范围内' : '管理权限'
  }
  if (typeof value === 'boolean') return value ? '启用' : '停用'
  return value == null || value === '' ? '—' : String(value)
}

onMounted(() => load())
</script>

<template>
  <header class="console-heading">
    <div><h1 v-if="!nested">{{ directory.label }}</h1><p v-if="!loading && !error">当前范围共 {{ total }} 条</p></div>
    <div class="directory-actions">
      <button :disabled="loading" @click="refresh">刷新</button>
      <button v-if="editable" class="primary" @click="open('create')">新增{{ label }}</button>
    </div>
  </header>
  <p v-if="status" class="notice success" role="status">{{ status }}</p>
  <section class="directory-table" :aria-label="directory.label">
    <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
    <p v-if="loading" class="directory-state" role="status">正在加载…</p>
    <div v-if="rows.length" class="table-scroll">
      <table>
        <thead><tr>
          <th v-for="column in directory.columns" :key="column.key" scope="col">{{ column.label }}</th>
          <th v-if="editable" scope="col">操作</th>
        </tr></thead>
        <tbody><tr v-for="row in rows" :key="row.id">
          <td v-for="column in directory.columns" :key="column.key">{{ format(column.key, row[column.key]) }}</td>
          <td v-if="editable"><div class="row-actions">
            <button v-if="directory.id === 'employees' || directory.id === 'roles'" :disabled="loading"
                @click="assignment = { id: row.id, name: String(row.name) }">{{ directory.id === 'employees' ? '管理角色' : '管理权限' }}</button>
            <button v-if="directory.id === 'roles'" :disabled="loading"
                @click="inheritance = { id: row.id, name: String(row.name), ownerOrganizationId: typeof row.ownerOrganizationId === 'number' ? row.ownerOrganizationId : null }">继承管理</button>
            <button v-if="directory.id === 'employees'" :disabled="loading"
                @click="effective = { id: row.id, name: String(row.name) }">有效权限</button>
            <button v-if="directory.id === 'permissions' && typeof row.systemId === 'number'" :disabled="loading"
                @click="policy = { id: row.id, name: String(row.name), code: String(row.code), systemId: Number(row.systemId) }">组织策略</button>
            <button :disabled="loading" @click="open('edit', row)">编辑</button>
            <button v-if="directory.id !== 'employees' || row.enabled" class="danger" :disabled="loading"
                @click="open('remove', row)">{{ directory.id === 'employees' ? '停用' : '删除' }}</button>
          </div></td>
        </tr></tbody>
      </table>
    </div>
    <p v-else-if="!loading && !error" class="directory-state">当前范围暂无记录。</p>
    <div v-if="total > pageSize" class="pager directory-pager">
      <button :disabled="loading || page <= 1" @click="load(page - 1)">上一页</button>
      <span>第 {{ page }} 页 / 共 {{ Math.ceil(total / pageSize) }} 页</span>
      <button :disabled="loading || page * pageSize >= total" @click="load(page + 1)">下一页</button>
    </div>
  </section>
  <dialog ref="dialog" class="editor" aria-labelledby="editor-title" @cancel="saving && $event.preventDefault()">
    <form @submit.prevent="save">
      <h2 id="editor-title">{{ operation === 'create' ? '新增' : operation === 'edit' ? '编辑' : '确认操作' }}{{ operation === 'remove' ? '' : label }}</h2>
      <template v-if="operation === 'remove'">
        <p>{{ directory.id === 'employees' ? '停用' : '删除' }}「{{ target?.name }}」？</p>
      </template>
      <template v-else>
        <label>名称<input v-model="name" required maxlength="128"></label>
        <label v-if="operation === 'create' && directory.id === 'employees'">用户名<input v-model="username" required maxlength="128"></label>
        <label v-if="operation === 'create' && directory.id === 'permissions'">权限码
          <input v-model="code" required maxlength="128" pattern="[A-Za-z0-9][A-Za-z0-9:_.\-]*">
        </label>
        <label v-if="operation === 'create' && directory.id === 'permissions'">业务系统
          <DirectorySelect v-model="system" :options="systems" empty="无业务系统（管理权限）" />
        </label>
        <label v-if="operation === 'create' || directory.id === 'employees'">所属组织
          <DirectorySelect v-model="organization" :options="organizations"
              :empty="directory.id === 'employees' ? (operation === 'edit' ? '保留当前归属' : '选择组织') : '全局共享'"
              :required="operation === 'create' && directory.id === 'employees'" />
        </label>
        <template v-if="directory.id === 'employees'">
          <label>岗位<input v-model="job" maxlength="128"></label>
          <label>电话<input v-model="phone" maxlength="32" :placeholder="operation === 'edit' ? '留空保留原号码' : ''"></label>
        </template>
      </template>
      <p v-if="saveError" class="notice danger" role="alert">{{ saveError }}</p>
      <p v-if="loadingOptions" role="status">正在加载可选范围…</p>
      <div class="directory-actions">
        <button type="button" :disabled="saving || loadingOptions" @click="dialog?.close()">取消</button>
        <button class="primary" :disabled="saving || loadingOptions || !optionsReady">{{ saving ? '正在提交…' : '确认' }}</button>
      </div>
    </form>
  </dialog>
  <RolePermissions v-if="assignment && directory.id === 'roles'" :key="assignment.id" :role="assignment"
      @close="assignment = undefined" />
  <AssignmentEditor v-if="assignment && directory.id === 'employees'" :key="assignment.id" :subject="assignment"
      kind="employee" @close="assignment = undefined" />
  <AssignmentEditor v-if="inheritance" :key="`inheritance-${inheritance.id}`" :subject="inheritance"
      kind="inheritance" @close="inheritance = undefined" />
  <EffectivePermissions v-if="effective" :key="effective.id" :employee="effective" @close="effective = undefined" />
  <PermissionPolicy v-if="policy" :key="policy.id" :permission="policy" @close="policy = undefined" />
</template>
