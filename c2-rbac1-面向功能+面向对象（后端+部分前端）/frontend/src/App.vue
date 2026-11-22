<script setup lang="ts">
// 文件职责：组织登录、身份恢复与工作区页面；账户变化时先卸载旧数据。
// 协作关系：auth.ts协调同版标签页认证，api.ts保持真实后端授权。
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ApiError, edition, editionHome, editionName, errorMessage, session, workspacePath, type Session } from './api'
import { login, logout, onAuthChange } from './auth'
import AdminConsole from './AdminConsole.vue'
import BusinessPage from './BusinessPage.vue'
import DepartmentEmployees from './DepartmentEmployees.vue'

const identity = ref<Session>()
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const username = ref('')
const password = ref('')
const directoryOpen = ref(false)
let loadOwner = {}
const active = computed(() => identity.value?.workspaces.find(workspace => workspacePath(workspace.path) === location.pathname))

function enter(current: Session) {
  const workspace = current.workspaces.find(item => workspacePath(item.path) === location.pathname)
    ?? current.workspaces.find(item => item.key === current.defaultWorkspaceKey)
  if (workspace && workspacePath(workspace.path) !== location.pathname) {
    location.replace(workspacePath(workspace.path))
    return
  }
  identity.value = current
}

async function loadSession() {
  const owner = loadOwner = {}
  error.value = ''
  loading.value = true
  try {
    const current = await session()
    if (owner === loadOwner) enter(current)
  } catch (failure) {
    if (owner !== loadOwner) return
    if (!(failure instanceof ApiError && failure.status === 401)) error.value = errorMessage(failure)
    else if (location.pathname !== editionHome) history.replaceState(null, '', editionHome)
  } finally {
    if (owner === loadOwner) loading.value = false
  }
}

async function signIn() {
  busy.value = true
  error.value = ''
  try {
    enter(await login(username.value.trim(), password.value))
    password.value = ''
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

async function signOut() {
  busy.value = true
  error.value = ''
  try {
    await logout()
    // 退出事件已清空身份并把URL归位；再次整页导航会打断紧接着的新登录。
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 401) location.replace(editionHome)
    else error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

function switchWorkspace(event: Event) {
  const path = (event.target as HTMLSelectElement).value
  if (workspacePath(path) !== location.pathname && identity.value?.workspaces.some(item => item.path === path)) {
    location.assign(workspacePath(path))
  }
}

const stopAuthListener = onAuthChange(change => {
  loadOwner = {}
  identity.value = undefined
  directoryOpen.value = false
  if (change.kind === 'login') void loadSession()
  else {
    loading.value = false
    error.value = change.kind === 'invalid' ? '登录已失效，请重新登录。' : ''
    if (location.pathname !== editionHome) history.replaceState(null, '', editionHome)
  }
})
onUnmounted(stopAuthListener)
onMounted(() => { if (edition) void loadSession() })
</script>

<template>
  <main v-if="!edition" class="workspace">
    <section class="panel edition-entry">
      <h1>OOAD RBAC 工作台</h1>
      <div class="row-actions">
        <a class="edition-link" href="/oo/">进入面向对象版</a>
        <a class="edition-link" href="/func/">进入面向功能版</a>
      </div>
    </section>
  </main>
  <div v-else-if="loading" class="page-loading" role="status">正在读取登录状态…</div>
  <main v-else-if="!identity" class="login">
    <div class="login-brand">
      <span class="mark" aria-hidden="true"><i></i><i></i><i></i></span>
      <p>OOAD RBAC · {{ editionName }}</p>
    </div>
    <form class="login-form" @submit.prevent="signIn">
      <h1>登录工作台</h1>
      <label>用户名<input v-model="username" autocomplete="username" required maxlength="128"></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" required maxlength="128"></label>
      <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
      <button class="primary" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</button>
    </form>
  </main>
  <template v-else>
    <header class="topbar">
      <div class="brand"><span class="mark" aria-hidden="true"><i></i><i></i><i></i></span> OOAD RBAC</div>
      <div class="account">
        <a class="edition-label" href="/" aria-label="切换版本">{{ editionName }}</a>
        <label v-if="identity.workspaces.length" class="switch">
          <span>当前系统</span>
          <select :value="active?.path ?? ''" @change="switchWorkspace">
            <option v-if="!active" value="" disabled>选择系统</option>
            <option v-for="workspace in identity.workspaces" :key="workspace.key" :value="workspace.path">
              {{ workspace.name }}
            </option>
          </select>
        </label>
        <span class="identity">{{ identity.employee.username }}</span>
        <button class="quiet" @click="directoryOpen = true">部门与员工</button>
        <button class="quiet" :disabled="busy" @click="signOut">退出登录</button>
      </div>
    </header>
    <AdminConsole v-if="active?.type === 'ADMIN'" :notice="error" />
    <main v-else class="workspace">
      <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
      <BusinessPage v-if="active?.type === 'BUSINESS'" :key="active.key" :workspace="active" :employee-id="identity.employee.id" />
      <section v-else class="panel"><h1>暂无默认工作区</h1>
        <p>{{ identity.workspaces.length ? '请在顶部选择要进入的系统。' : '当前账号暂无可进入的系统。' }}</p>
      </section>
    </main>
    <DepartmentEmployees v-if="directoryOpen" :administrator="identity.workspaces.some(item => item.type === 'ADMIN')"
      @close="directoryOpen = false" />
  </template>
</template>
