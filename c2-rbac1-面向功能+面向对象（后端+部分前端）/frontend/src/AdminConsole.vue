<script setup lang="ts">
// 管理工作区统一处理导航，目录与关系维护各自请求所需数据。
import { computed, onMounted, onUnmounted, ref } from 'vue'
import AdminDirectory from './AdminDirectory.vue'
import AdminOverview from './AdminOverview.vue'
import { directories } from './adminSections'
import { workspacePath } from './api'

defineProps<{ notice: string }>()
const entries = [
  { id: 'overview', label: '管理总览', glyph: '⌂' },
  ...directories.filter(item => item.id !== 'catalog').map((item, index) => ({
    ...item, glyph: ['◉', '▥', '◇', '◈'][index],
  })),
]
const selected = ref('overview')
const permissionTab = ref<'definitions' | 'catalog'>('definitions')
const directory = computed(() => directories.find(item =>
  item.id === (selected.value === 'permissions' && permissionTab.value === 'catalog' ? 'catalog' : selected.value)))

/** 从 URL 恢复导航，返回和刷新保留所选管理页面。 */
function syncLocation() {
  const params = new URLSearchParams(location.search)
  const original = params.get('view')
  const view = original === 'catalog' ? 'permissions'
    : original === 'assignments' ? (params.has('role') ? 'roles' : 'employees') : original
  selected.value = entries.find(item => item.id === view)?.id ?? 'overview'
  permissionTab.value = original === 'catalog' || params.get('tab') === 'catalog' ? 'catalog' : 'definitions'
}

/** 保留可复制的地址与浏览器默认的辅助导航操作。 */
function navigate(event: MouseEvent, id: string) {
  if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || event.button !== 0) return
  event.preventDefault()
  history.pushState(null, '', id === 'overview' ? workspacePath('/admin/') : `?view=${id}`)
  syncLocation()
}

syncLocation()
onMounted(() => addEventListener('popstate', syncLocation))
onUnmounted(() => removeEventListener('popstate', syncLocation))
</script>

<template>
  <div class="console">
    <aside class="console-sidebar">
      <div class="console-name"><b>权限管理平台</b><span>组织与访问控制</span></div>
      <nav aria-label="管理导航">
        <a v-for="entry in entries" :key="entry.id" :href="entry.id === 'overview' ? workspacePath('/admin/') : `?view=${entry.id}`"
            :aria-current="selected === entry.id ? 'page' : undefined" @click="navigate($event, entry.id)">
          <span class="nav-glyph" aria-hidden="true">{{ entry.glyph }}</span>{{ entry.label }}
        </a>
      </nav>
    </aside>
    <main class="console-content">
      <p v-if="notice" class="notice danger" role="alert">{{ notice }}</p>
      <AdminOverview v-if="selected === 'overview'" />
      <template v-else-if="directory">
        <template v-if="selected === 'permissions'">
          <header class="console-heading"><div><h1>权限管理</h1><p>{{ permissionTab === 'catalog'
            ? '查看 Excel 原表中的功能和角色动作。' : '维护实际参与鉴权的业务权限。' }}</p></div></header>
          <nav class="permission-tabs" aria-label="权限视图">
            <a href="?view=permissions" :aria-current="permissionTab === 'definitions' ? 'page' : undefined"
                @click="navigate($event, 'permissions')">生效权限配置</a>
            <a href="?view=permissions&tab=catalog" :aria-current="permissionTab === 'catalog' ? 'page' : undefined"
                @click="navigate($event, 'permissions&tab=catalog')">Excel 原表清单</a>
          </nav>
        </template>
        <AdminDirectory :key="directory.id" :directory="directory" :nested="selected === 'permissions'" />
      </template>
    </main>
  </div>
</template>
