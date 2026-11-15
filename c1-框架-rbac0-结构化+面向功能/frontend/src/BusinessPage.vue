<script setup lang="ts">
// 将服务端鉴权结果和审批执行结果分开展示，审批始终使用当前登录会话。
import { onMounted, ref } from 'vue'
import { ApiError, errorMessage, list, write, type Approval, type RequestItem, type Workspace } from './api'

const props = defineProps<{ workspace: Workspace }>()
const items = ref<RequestItem[]>([])
const page = ref(1)
const total = ref(0)
const pageSize = 12
const loading = ref(false)
const approving = ref<number>()
const error = ref('')
const outcome = ref<{ id: number; authorization: string; execution: string; message: string; tone: string }>()

async function load(target = page.value) {
  loading.value = true
  error.value = ''
  try {
    const code = encodeURIComponent(props.workspace.key.slice('business:'.length))
    const result = await list<RequestItem>(`/api/business-systems/${code}/requests`, target, pageSize)
    items.value = result.items
    total.value = result.total
    page.value = result.page
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

async function approve(item: RequestItem) {
  approving.value = item.id
  outcome.value = undefined
  try {
    const response = await write<Approval>('POST', `/api/requests/${item.id}/approve`)
    item.status = response.status
    outcome.value = { id: item.id, authorization: '通过', execution: '成功', message: '申请已审批。', tone: 'success' }
  } catch (failure) {
    const code = failure instanceof ApiError ? failure.code : ''
    const denied = code === 'PERMISSION_DENIED' || code === 'SYSTEM_ACCESS_DENIED'
    const conflict = code === 'REQUEST_ALREADY_APPROVED'
    outcome.value = {
      id: item.id,
      authorization: denied ? '拒绝' : conflict ? '通过' : '未确认',
      execution: denied ? '未执行' : conflict ? '失败' : '未确认',
      message: errorMessage(failure),
      tone: 'danger',
    }
    if (conflict) await load()
  } finally {
    approving.value = undefined
  }
}

onMounted(() => load())
</script>

<template>
  <div class="title">
    <p class="subtitle">{{ workspace.name }}</p><h1>申请审批</h1>
  </div>
  <div class="business-layout">
    <section class="panel request-panel">
      <div class="panel-heading"><h2>申请列表</h2>
        <button :disabled="loading || approving !== undefined" @click="load()">刷新</button>
      </div>
      <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
      <p v-if="loading" role="status">正在加载申请…</p>
      <div v-if="items.length" class="table-scroll">
        <table>
          <thead><tr><th scope="col">申请</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead>
          <tbody><tr v-for="item in items" :key="item.id">
            <td><span class="item-id">#{{ item.id }}</span> {{ item.title }}</td>
            <td><span class="state" :class="{ approved: item.status === 'APPROVED' }">
              {{ item.status === 'PENDING' ? '待审批' : '已审批' }}
            </span></td>
            <td><button v-if="item.status === 'PENDING'" :disabled="loading || approving !== undefined"
                @click="approve(item)">{{ approving === item.id ? '正在审批…' : '审批' }}</button></td>
          </tr></tbody>
        </table>
      </div>
      <p v-else-if="!loading && !error" class="muted">当前系统暂无申请。</p>
      <div v-if="total > pageSize" class="pager">
        <button :disabled="loading || approving !== undefined || page <= 1" @click="load(page - 1)">上一页</button>
        <span>第 {{ page }} 页</span>
        <button :disabled="loading || approving !== undefined || page * pageSize >= total" @click="load(page + 1)">下一页</button>
      </div>
    </section>
    <aside class="receipt" aria-label="审批结果" aria-live="polite">
      <h2>审批结果</h2>
      <template v-if="outcome">
        <p class="item-id">申请 #{{ outcome.id }}</p>
        <dl><div><dt>鉴权结果</dt><dd :class="outcome.tone">{{ outcome.authorization }}</dd></div>
          <div><dt>执行结果</dt><dd>{{ outcome.execution }}</dd></div></dl>
        <p>{{ outcome.message }}</p>
      </template>
      <p v-else class="muted">提交审批后，在这里查看结果。</p>
    </aside>
  </div>
</template>
