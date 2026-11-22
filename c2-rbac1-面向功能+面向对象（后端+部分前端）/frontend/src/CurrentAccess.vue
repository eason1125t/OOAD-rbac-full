<script setup lang="ts">
// 展示BusinessPage读取的当前系统角色与有效权限，调用自身检查接口，不查询他人身份。
import { computed, ref, watch } from 'vue'
import { checkApi, checkPermission, errorMessage, type CurrentAuthorization } from './api'

const props = defineProps<{ authorization: CurrentAuthorization }>()
const permissionCode = ref('request:approve')
const operationId = ref('approveRequest')
const checks = ref<{ permission?: boolean; api?: boolean }>({})
const error = ref('')
const busy = ref(false)
const roleNames = computed(() => new Map(props.authorization.roles.map(role => [role.id, role.name])))
const operations = [
  ['listSystemRequests', '查看申请列表'], ['getRequest', '查看申请详情'], ['createRequest', '发起申请'],
  ['updateRequest', '修改申请'], ['withdrawRequest', '撤回申请'], ['approveRequest', '审批申请'],
  ['rejectRequest', '驳回申请'], ['deleteRejectedRequest', '删除已驳回申请'],
]
let revision = 0
// 输入或授权快照改变后清除旧结论，异步返回不能写入另一次检查。
watch([() => props.authorization, permissionCode, operationId], () => {
  revision++
  checks.value = {}
  error.value = ''
  busy.value = false
})
async function check(kind: 'permission' | 'api') {
  const current = ++revision
  busy.value = true
  checks.value = {}
  error.value = ''
  try {
    const result = kind === 'permission'
      ? await checkPermission(props.authorization.systemCode, permissionCode.value.trim())
      : await checkApi(props.authorization.systemCode, operationId.value)
    if (current === revision) checks.value = { [kind]: result.allowed }
  } catch (failure) {
    if (current === revision) error.value = errorMessage(failure)
  } finally {
    if (current === revision) busy.value = false
  }
}
</script>

<template>
  <section class="panel access-panel" aria-label="我的角色与权限">
    <details>
      <summary>我的角色与权限</summary>
      <p class="muted">当前系统：{{ authorization.systemCode }}</p>
      <p v-if="authorization.globalAdministrator">当前为全局管理员，仍受组织权限停用约束。</p>
      <h3>角色来源</h3>
      <ul v-if="authorization.roles.length" class="role-sources">
        <li v-for="role in authorization.roles" :key="role.id">
          <b>{{ role.name }}</b>
          <span v-if="role.direct" class="state">直接分配</span>
          <span v-if="role.inherited" class="state">继承自 {{ role.inheritedFromRoleIds.map(id => roleNames.get(id) ?? '角色#' + id).join('、') }}</span>
          <span class="muted">{{ role.appliesToSystem ? '涉及当前系统' : '未配置当前系统权限' }}</span>
        </li>
      </ul>
      <p v-else class="muted">暂无直接或继承角色。</p>
      <h3>有效权限</h3>
      <ul v-if="authorization.effectivePermissions.length" class="permission-chips">
        <li v-for="permission in authorization.effectivePermissions" :key="permission.id">
          {{ permission.name }} <code>{{ permission.code }}</code>
        </li>
      </ul>
      <p v-else class="muted">当前系统暂无有效权限。</p>
      <div class="access-checks">
        <form @submit.prevent="check('permission')">
          <label>前端权限码<input v-model="permissionCode" required maxlength="128" placeholder="例如 request:approve"></label>
          <button :disabled="busy || !permissionCode.trim()">检查前端权限</button>
          <p v-if="checks.permission !== undefined" role="status">前端权限：{{ checks.permission ? '允许' : '不允许' }}</p>
        </form>
        <form @submit.prevent="check('api')">
          <label>业务操作<select v-model="operationId">
            <option v-for="[id, label] in operations" :key="id" :value="id">{{ label }}</option>
          </select></label>
          <button :disabled="busy">检查 API 权限</button>
          <p v-if="checks.api !== undefined" role="status">API 权限：{{ checks.api ? '允许' : '不允许' }}</p>
        </form>
      </div>
      <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
      <p class="muted">检查结果仅反映当前系统权限；实际操作还会重新检查权限、申请归属和状态。</p>
    </details>
  </section>
</template>
