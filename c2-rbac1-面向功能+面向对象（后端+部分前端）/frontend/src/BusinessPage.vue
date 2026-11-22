<script setup lang="ts">
// 管理当前系统的申请列表、详情与操作反馈，权限最终由后端校验。
import { computed, onMounted, ref } from 'vue'
import CurrentAccess from './CurrentAccess.vue'
import { ApiError, errorMessage, read, session, write, type CurrentAuthorization, type RequestAction, type RequestDetail,
  type RequestItem, type RequestPage, type RequestStatus, type Workspace } from './api'

const props = defineProps<{ workspace: Workspace; employeeId: number }>()
const systemCode = props.workspace.key.slice('business:'.length)
const collection = `/api/business-systems/${encodeURIComponent(systemCode)}/requests`
const labels: Record<RequestStatus, string> = {
  PENDING: '待审批', APPROVED: '已审批', WITHDRAWN: '已撤回', REJECTED: '已驳回',
}
const successText: Record<RequestAction, string> = {
  create: '申请已提交。', update: '申请已修改。', withdraw: '申请已撤回。',
  approve: '申请已审批。', reject: '申请已驳回。', delete: '申请已删除。',
}
const items = ref<RequestItem[]>([])
const authorization = ref<CurrentAuthorization>()
const page = ref(1)
const total = ref(0)
const pageSize = 12
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const selected = ref<RequestDetail>()
const dialog = ref<HTMLDialogElement>()
const editorAction = ref<'create' | 'update' | 'delete'>('create')
const title = ref('')
const editorError = ref('')
const outcome = ref<{ id: number; authorization: string; execution: string; message: string; tone: string }>()
const editorHeading = computed(() => ({ create: '发起申请', update: '修改申请', delete: '删除已驳回申请' })[editorAction.value])
const ownsSelection = computed(() => selected.value?.applicantId === props.employeeId)
const hasPermission = (code: string) => authorization.value?.effectivePermissions.some(item => item.code === code) ?? false
const can = (action: RequestAction) => hasPermission(`request:${action}`)

async function load(target = page.value) {
  loading.value = true
  error.value = ''
  authorization.value = undefined
  try {
    const current = await session(systemCode)
    if (!current.authorization) throw new Error('未能读取当前系统权限，请刷新重试。')
    authorization.value = current.authorization
    if (!hasPermission('request:read')) {
      items.value = []
      total.value = 0
      selected.value = undefined
      return
    }
    const result = await read<RequestPage>(`${collection}?page=${target}&pageSize=${pageSize}`)
    if (target > 1 && result.items.length === 0) {
      await load(Math.max(1, Math.ceil(result.total / pageSize)))
      return
    }
    items.value = result.items
    total.value = result.total
    page.value = result.page
  } catch (failure) {
    error.value = errorMessage(failure)
    // 无法确认当前权限时清除旧列表、详情及按钮，避免沿用过期展示。
    items.value = []
    total.value = 0
    selected.value = undefined
    authorization.value = undefined
  } finally {
    loading.value = false
  }
}

async function details(item: RequestItem) {
  busy.value = true
  error.value = ''
  try {
    selected.value = await read<RequestDetail>(`/api/requests/${item.id}`)
  } catch (failure) {
    selected.value = undefined
    error.value = errorMessage(failure)
    if (failure instanceof ApiError && (failure.status === 401 || failure.status === 403)) {
      items.value = []
      total.value = 0
      authorization.value = undefined
    }
    if (failure instanceof ApiError && failure.status === 404) await load()
  } finally {
    busy.value = false
  }
}

function openEditor(action: typeof editorAction.value) {
  editorAction.value = action
  title.value = action === 'update' ? selected.value?.title ?? '' : ''
  editorError.value = ''
  dialog.value?.showModal()
}

function failureReceipt(failure: unknown, id: number) {
  const code = failure instanceof ApiError ? failure.code : ''
  const denied = code === 'PERMISSION_DENIED' || code === 'SYSTEM_ACCESS_DENIED'
  const conflict = code === 'REQUEST_ALREADY_APPROVED' || code === 'REQUEST_STATE_CONFLICT'
  outcome.value = {
    id,
    authorization: denied ? '拒绝' : conflict ? '通过' : '未确认',
    execution: denied ? '未执行' : conflict ? '失败' : '未确认',
    message: errorMessage(failure),
    tone: 'danger',
  }
}

async function submitEditor() {
  const action = editorAction.value
  const id = selected.value?.id
  if (action !== 'create' && id == null) return
  busy.value = true
  editorError.value = ''
  outcome.value = undefined
  try {
    if (action === 'delete') {
      await write('DELETE', `/api/requests/${id}`)
      selected.value = undefined
      outcome.value = { id: id!, authorization: '通过', execution: '成功', message: successText.delete, tone: 'success' }
      await load()
    } else {
      const payload = { title: title.value.trim() }
      if (!payload.title) { editorError.value = '请输入申请标题。'; return }
      const result = await write<RequestDetail>(action === 'create' ? 'POST' : 'PATCH',
        action === 'create' ? collection : `/api/requests/${id}`, payload)
      selected.value = result
      outcome.value = { id: result.id, authorization: '通过', execution: '成功', message: successText[action], tone: 'success' }
      await load(action === 'create' ? Math.max(1, Math.ceil((total.value + 1) / pageSize)) : page.value)
    }
    dialog.value?.close()
  } catch (failure) {
    editorError.value = errorMessage(failure)
    if (id != null) failureReceipt(failure, id)
  } finally {
    busy.value = false
  }
}

async function action(item: RequestItem, change: 'approve' | 'reject' | 'withdraw') {
  busy.value = true
  outcome.value = undefined
  try {
    const result = await write<{ status: RequestStatus }>('POST', `/api/requests/${item.id}/${change}`)
    const listed = items.value.find(row => row.id === item.id)
    if (listed) listed.status = result.status
    if (selected.value?.id === item.id) selected.value.status = result.status
    outcome.value = { id: item.id, authorization: '通过', execution: '成功', message: successText[change], tone: 'success' }
  } catch (failure) {
    failureReceipt(failure, item.id)
    if (failure instanceof ApiError && [403, 404, 409].includes(failure.status)) {
      await load()
      if (selected.value?.id === item.id) await details(item)
    }
  } finally {
    busy.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <div class="title">
    <p class="subtitle">{{ workspace.name }}</p><h1>业务申请</h1>
  </div>
  <CurrentAccess v-if="authorization" :authorization="authorization" />
  <div class="business-layout">
    <section class="panel request-panel">
      <div class="panel-heading">
        <h2>申请列表</h2>
        <div class="row-actions">
          <button :disabled="loading || busy" @click="load()">刷新</button>
          <button v-if="can('create')" class="primary" :disabled="loading || busy" @click="openEditor('create')">发起申请</button>
        </div>
      </div>
      <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
      <p v-if="authorization && !hasPermission('request:read')" class="notice" role="status">
        当前没有申请查看权限。{{ can('create') ? '你仍可发起申请。' : '可展开“我的角色与权限”查看授权情况。' }}
      </p>
      <p v-if="loading" role="status">正在加载申请…</p>
      <div v-if="items.length" class="table-scroll">
        <table>
          <thead><tr><th scope="col">申请</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead>
          <tbody><tr v-for="item in items" :key="item.id">
            <td><span class="item-id">#{{ item.id }}</span> {{ item.title }}</td>
            <td><span class="state" :class="{ approved: item.status === 'APPROVED', rejected: item.status === 'REJECTED' }">
              {{ labels[item.status] }}
            </span></td>
            <td><div class="row-actions">
              <button :disabled="loading || busy" @click="details(item)">查看详情</button>
              <button v-if="item.status === 'PENDING' && can('approve')" :disabled="loading || busy"
                  @click="action(item, 'approve')">审批</button>
            </div></td>
          </tr></tbody>
        </table>
      </div>
      <p v-else-if="!loading && !error && hasPermission('request:read')" class="muted">当前系统暂无申请。</p>
      <div v-if="total > pageSize" class="pager">
        <button :disabled="loading || busy || page <= 1" @click="load(page - 1)">上一页</button>
        <span>第 {{ page }} 页</span>
        <button :disabled="loading || busy || page * pageSize >= total" @click="load(page + 1)">下一页</button>
      </div>
    </section>
    <aside class="request-sidebar">
      <section v-if="selected" class="panel" aria-label="申请详情">
        <div class="panel-heading"><h2>申请详情</h2><button :disabled="busy" @click="selected = undefined">关闭</button></div>
        <p class="item-id">#{{ selected.id }} · {{ labels[selected.status] }}</p>
        <h3>{{ selected.title }}</h3>
        <p v-if="ownsSelection" class="muted">我的申请</p>
        <div class="row-actions">
          <template v-if="selected.status === 'PENDING'">
            <button v-if="ownsSelection && can('update')" :disabled="busy" @click="openEditor('update')">修改</button>
            <button v-if="ownsSelection && can('withdraw')" :disabled="busy" @click="action(selected, 'withdraw')">撤回</button>
            <button v-if="can('approve')" :disabled="busy" @click="action(selected, 'approve')">审批</button>
            <button v-if="can('reject')" :disabled="busy" @click="action(selected, 'reject')">驳回</button>
          </template>
          <button v-if="selected.status === 'REJECTED' && ownsSelection && can('delete')" class="danger"
              :disabled="busy" @click="openEditor('delete')">删除</button>
        </div>
      </section>
      <section class="receipt" aria-label="操作结果" aria-live="polite">
        <h2>操作结果</h2>
        <template v-if="outcome">
          <p class="item-id">申请 #{{ outcome.id }}</p>
          <dl><div><dt>鉴权结果</dt><dd :class="outcome.tone">{{ outcome.authorization }}</dd></div>
            <div><dt>执行结果</dt><dd>{{ outcome.execution }}</dd></div></dl>
          <p>{{ outcome.message }}</p>
        </template>
        <p v-else class="muted">完成操作后，在这里查看结果。</p>
      </section>
    </aside>
  </div>
  <dialog ref="dialog" class="editor" aria-labelledby="request-editor-title" @cancel="busy && $event.preventDefault()">
    <form @submit.prevent="submitEditor">
      <h2 id="request-editor-title">{{ editorHeading }}</h2>
      <p v-if="editorAction === 'delete'">删除「{{ selected?.title }}」？</p>
      <label v-else>申请标题<input v-model="title" required maxlength="128"></label>
      <p v-if="editorError" class="notice danger" role="alert">{{ editorError }}</p>
      <div class="directory-actions">
        <button type="button" :disabled="busy" @click="dialog?.close()">取消</button>
        <button class="primary" :disabled="busy">{{ editorAction === 'delete' ? '确认删除' : '保存申请' }}</button>
      </div>
    </form>
  </dialog>
</template>
