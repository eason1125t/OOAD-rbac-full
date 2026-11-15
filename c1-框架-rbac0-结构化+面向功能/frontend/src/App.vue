<script setup lang="ts">
// 登录及两个工作区共用服务端会话，切换只更改当前页面路径。
import { computed, onMounted, ref } from 'vue'
import { ApiError, errorMessage, session, write, type Session } from './api'
import AdminConsole from './AdminConsole.vue'
import BusinessPage from './BusinessPage.vue'

const identity = ref<Session>()
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const username = ref('')
const password = ref('')
const active = computed(() => identity.value?.workspaces.find(workspace => workspace.path === location.pathname))

function enter(current: Session) {
  const workspace = current.workspaces.find(item => item.path === location.pathname)
    ?? current.workspaces.find(item => item.key === current.defaultWorkspaceKey)
  if (workspace && workspace.path !== location.pathname) {
    location.replace(workspace.path)
    return
  }
  identity.value = current
}

async function loadSession() {
  error.value = ''
  loading.value = true
  try {
    enter(await session())
  } catch (failure) {
    if (!(failure instanceof ApiError && failure.status === 401)) error.value = errorMessage(failure)
    else if (location.pathname !== '/') history.replaceState(null, '', '/')
  } finally {
    loading.value = false
  }
}

async function signIn() {
  busy.value = true
  error.value = ''
  try {
    enter(await write<Session>('POST', '/api/auth/login', { username: username.value.trim(), password: password.value }))
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
    await write<void>('POST', '/api/auth/logout')
    location.replace('/')
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 401) location.replace('/')
    else error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

function switchWorkspace(event: Event) {
  const path = (event.target as HTMLSelectElement).value
  if (path !== location.pathname && identity.value?.workspaces.some(item => item.path === path)) location.assign(path)
}

onMounted(loadSession)
</script>

<template>
  <div v-if="loading" class="page-loading" role="status">正在读取登录状态…</div>
  <main v-else-if="!identity" class="login">
    <div class="login-brand">
      <span class="mark" aria-hidden="true"><i></i><i></i><i></i></span>
      <p>OOAD RBAC</p>
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
        <button class="quiet" :disabled="busy" @click="signOut">退出登录</button>
      </div>
    </header>
    <AdminConsole v-if="active?.type === 'ADMIN'" :notice="error" />
    <main v-else class="workspace">
      <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
      <BusinessPage v-if="active?.type === 'BUSINESS'" :key="active.key" :workspace="active" />
      <section v-else class="panel"><h1>暂无默认工作区</h1>
        <p>{{ identity.workspaces.length ? '请在顶部选择要进入的系统。' : '当前账号暂无可进入的系统。' }}</p>
      </section>
    </main>
  </template>
</template>
