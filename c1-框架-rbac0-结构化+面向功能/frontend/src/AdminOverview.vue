<script setup lang="ts">
// 首页从现有分页接口读取当前管理员可见的对象数量。
import { onMounted, ref } from 'vue'
import { list } from './api'

const stages = [
  { id: 'employees', label: '员工', detail: '按组织查找员工，查看已分配角色。' },
  { id: 'roles', label: '角色', detail: '汇总同一岗位所需的操作权限。' },
  { id: 'permissions', label: '权限', detail: '按业务系统区分可执行的操作。' },
]
const totals = ref<Record<string, number>>({})
const error = ref(false)
onMounted(async () => {
  const results = await Promise.allSettled(stages.map(stage => list(`/api/${stage.id}`, 1, 1)))
  results.forEach((result, index) => {
    if (result.status === 'fulfilled') totals.value[stages[index].id] = result.value.total
    else error.value = true
  })
})
</script>

<template>
  <header class="console-heading">
    <div><h1>管理总览</h1><p>查看当前可见范围，管理员工的访问权限。</p></div>
    <a class="console-action" href="?view=employees">管理员工角色 <span aria-hidden="true">↗</span></a>
  </header>
  <section class="access-map" aria-labelledby="access-map-title">
    <h2 id="access-map-title">授权关系</h2>
    <div class="access-chain">
      <a v-for="stage in stages" :key="stage.id" :href="`?view=${stage.id}`" class="access-step">
        <strong>{{ stage.label }}</strong>
        <span class="access-count">{{ totals[stage.id] ?? '—' }}</span>
        <span class="access-detail">{{ stage.detail }}</span>
      </a>
    </div>
    <p v-if="error" class="map-error" role="status">部分目录数量暂不可用。可进入目录重试。</p>
  </section>
  <div class="console-links">
    <a href="?view=departments"><b>部门目录</b><span>核对部门名称、经理和原表上级</span><i aria-hidden="true">↗</i></a>
    <a href="?view=permissions&tab=catalog"><b>Excel 原表清单</b><span>查看原表中的功能和角色动作</span><i aria-hidden="true">↗</i></a>
  </div>
</template>
