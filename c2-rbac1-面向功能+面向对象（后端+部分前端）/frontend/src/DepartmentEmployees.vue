<script setup lang="ts">
// 共用部门逐层浏览、范围内员工搜索分页；管理员详情单独调用受保护接口。
// 与App共用Session，目录范围和普通员工字段由后端决定，不在前端拼接全量数据。
import { onMounted, onUnmounted, ref } from 'vue'
import { errorMessage, list, read, type DepartmentNode, type DirectoryEmployee, type EmployeeProfile, type Page } from './api'

defineProps<{ administrator: boolean }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
const organizations = ref<Page<DepartmentNode>>()
const employees = ref<Page<DirectoryEmployee>>()
const parent = ref<DepartmentNode>()
const department = ref<DepartmentNode>()
const departmentQuery = ref('')
const employeeQuery = ref('')
const organizationError = ref('')
const employeeError = ref('')
const profileError = ref('')
const organizationBusy = ref(false)
const employeeBusy = ref(false)
const profileBusy = ref(false)
const profile = ref<EmployeeProfile>()
const pageSize = 10
let organizationRevision = 0
let employeeRevision = 0
let profileRevision = 0
let organizationFilter = ''
let employeeFilter = ''

/** 根/直接子级及搜索分别构造查询，页码从1开始，不递归下载整棵树。 */
async function loadOrganizations(target = 1) {
  const current = ++organizationRevision
  organizationBusy.value = true
  organizationError.value = ''
  organizations.value = undefined
  const query = new URLSearchParams({ view: 'tree' })
  if (departmentQuery.value.trim()) {
    query.set('q', departmentQuery.value.trim())
    query.set('rootsOnly', 'false')
  } else if (parent.value) query.set('parentId', String(parent.value.id))
  else query.set('rootsOnly', 'true')
  if (query.toString() !== organizationFilter) target = 1
  organizationFilter = query.toString()
  try {
    const result = await list<DepartmentNode>('/api/departments?' + query, target, pageSize)
    if (current === organizationRevision) organizations.value = result
  } catch (failure) {
    if (current === organizationRevision) organizationError.value = errorMessage(failure)
  } finally {
    if (current === organizationRevision) organizationBusy.value = false
  }
}

async function loadEmployees(target = 1) {
  const current = ++employeeRevision
  employeeBusy.value = true
  employeeError.value = ''
  employees.value = undefined
  profile.value = undefined
  profileError.value = ''
  profileRevision++
  profileBusy.value = false
  const query = new URLSearchParams()
  if (department.value) query.set('departmentId', String(department.value.id))
  if (employeeQuery.value.trim()) query.set('q', employeeQuery.value.trim())
  if (query.toString() !== employeeFilter) target = 1
  employeeFilter = query.toString()
  try {
    const result = await list<DirectoryEmployee>('/api/employees?' + query, target, pageSize)
    if (current !== employeeRevision) return
    if (target > 1 && !result.items.length && result.total) {
      await loadEmployees(Math.max(1, Math.ceil(result.total / pageSize)))
      return
    }
    employees.value = result
  } catch (failure) {
    if (current === employeeRevision) employeeError.value = errorMessage(failure)
  } finally {
    if (current === employeeRevision) employeeBusy.value = false
  }
}

function roots() {
  parent.value = undefined
  departmentQuery.value = ''
  void loadOrganizations()
}

function expand(node: DepartmentNode) {
  parent.value = node
  departmentQuery.value = ''
  void loadOrganizations()
}

function selectDepartment(node?: DepartmentNode) {
  department.value = node
  void loadEmployees()
}

async function details(employee: DirectoryEmployee) {
  const current = ++profileRevision
  profile.value = undefined
  profileError.value = ''
  profileBusy.value = true
  try {
    const result = await read<EmployeeProfile>('/api/employees/' + employee.id)
    if (current === profileRevision) profile.value = result
  } catch (failure) {
    if (current === profileRevision) profileError.value = errorMessage(failure)
  } finally {
    if (current === profileRevision) profileBusy.value = false
  }
}

onMounted(() => {
  dialog.value?.showModal()
  void loadOrganizations()
  void loadEmployees()
})
onUnmounted(() => {
  organizationRevision++
  employeeRevision++
  profileRevision++
})
</script>

<template>
  <dialog ref="dialog" class="editor people-dialog" aria-labelledby="people-title" @close="emit('close')">
    <header class="panel-heading"><div><h2 id="people-title">部门与员工</h2>
      <p class="muted">仅显示当前账号可见的数据。</p></div>
      <button autofocus @click="dialog?.close()">关闭目录</button>
    </header>
    <div class="people-layout">
      <section aria-label="部门树">
        <h3>部门逐层查询</h3>
        <form class="directory-search" @submit.prevent="parent = undefined; loadOrganizations()">
          <label>部门名称或编码<input v-model="departmentQuery" maxlength="128" placeholder="搜索可见组织"></label>
          <button :disabled="organizationBusy">查找部门</button>
        </form>
        <div class="directory-actions">
          <button :disabled="organizationBusy" @click="roots">返回根部门</button>
          <button :disabled="employeeBusy" @click="selectDepartment()">全部可见员工</button>
        </div>
        <p v-if="parent" class="muted">当前层级：{{ parent.name }} 的直接子部门</p>
        <p v-if="organizationError" class="notice danger" role="alert">{{ organizationError }}</p>
        <p v-if="organizationBusy" role="status">正在加载部门…</p>
        <ul v-else class="department-nodes">
          <li v-for="node in organizations?.items" :key="node.id">
            <button v-if="node.organizationType === 'DEPARTMENT'" :aria-pressed="department?.id === node.id"
              @click="selectDepartment(node)">{{ node.name }}</button>
            <span v-else>{{ node.name }}</span>
            <button v-if="node.hasChildren" :aria-label="'展开' + node.name" @click="expand(node)">展开</button>
          </li>
        </ul>
        <p v-if="organizations && !organizations.items.length" class="muted">没有匹配的部门。</p>
        <div v-if="organizations && organizations.total > pageSize" class="pager">
          <button :disabled="organizationBusy || organizations.page <= 1" @click="loadOrganizations(organizations.page - 1)">上一层级页</button>
          <span>部门第 {{ organizations.page }} 页</span>
          <button :disabled="organizationBusy || organizations.page * pageSize >= organizations.total"
            @click="loadOrganizations(organizations.page + 1)">下一层级页</button>
        </div>
      </section>
      <section aria-label="部门员工列表">
        <h3>{{ department ? department.name + '的员工' : '全部可见员工' }}</h3>
        <form class="directory-search" @submit.prevent="loadEmployees()">
          <label>姓名或账号<input v-model="employeeQuery" maxlength="128" placeholder="搜索员工"></label>
          <button :disabled="employeeBusy">查找员工</button>
          <button type="button" :disabled="employeeBusy" @click="loadEmployees(employees?.page ?? 1)">刷新员工</button>
        </form>
        <p v-if="employeeError" class="notice danger" role="alert">{{ employeeError }}</p>
        <p v-if="employeeBusy" role="status">正在加载员工…</p>
        <div v-if="employees?.items.length" class="table-scroll">
          <table><thead><tr><th>姓名</th><th>账号</th><th v-if="administrator">操作</th></tr></thead>
            <tbody><tr v-for="employee in employees.items" :key="employee.id">
              <td>{{ employee.name }}</td><td>{{ employee.username }}</td>
              <td v-if="administrator"><button :disabled="profileBusy" @click="details(employee)">查看资料</button></td>
            </tr></tbody>
          </table>
        </div>
        <p v-else-if="employees" class="muted">没有匹配的员工。</p>
        <div v-if="employees" class="pager">
          <button :disabled="employeeBusy || employees.page <= 1" @click="loadEmployees(employees.page - 1)">上一员工页</button>
          <span>员工第 {{ employees.page }} 页 / 共 {{ employees.total }} 人</span>
          <button :disabled="employeeBusy || employees.page * pageSize >= employees.total"
            @click="loadEmployees(employees.page + 1)">下一员工页</button>
        </div>
        <p v-if="profileError" class="notice danger" role="alert">{{ profileError }}</p>
        <p v-if="profileBusy" role="status">正在加载资料…</p>
        <section v-if="profile" class="employee-profile" aria-label="员工资料">
          <h3>{{ profile.name }}</h3>
          <dl><div><dt>账号</dt><dd>{{ profile.username }}</dd></div>
            <div><dt>岗位</dt><dd>{{ profile.jobTitle || '—' }}</dd></div>
            <div><dt>电话</dt><dd>{{ profile.phone || '—' }}</dd></div>
            <div><dt>状态</dt><dd>{{ profile.enabled ? '启用' : '停用' }}</dd></div></dl>
        </section>
      </section>
    </div>
  </dialog>
</template>
