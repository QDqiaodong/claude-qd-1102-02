<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from './router'
import api from './api'

import BeanBatchView from './views/BeanBatchView.vue'
import MenuItemView from './views/MenuItemView.vue'
import TableInfoView from './views/TableInfoView.vue'
import OrderTicketView from './views/OrderTicketView.vue'
import CleaningView from './views/CleaningView.vue'

// ------------------------------------------------------------------
// 导航形态：顶部横向导航（el-menu mode="horizontal"）。
// 品牌「咖啡馆」，四个业务模块一字排开，当前模块高亮。
// ------------------------------------------------------------------

const modules = [
  { path: '/bean-batch',  label: '咖啡豆批次', sub: '烘焙状态机', comp: BeanBatchView },
  { path: '/menu-item',   label: '饮品菜单',   sub: '在售 / 下架', comp: MenuItemView },
  { path: '/table-info',  label: '桌台',       sub: '开台 / 清洁', comp: TableInfoView },
  { path: '/cleaning',     label: '清洁交接',   sub: '接单 / 完成', comp: CleaningView },
  { path: '/order-ticket', label: '收银出单',  sub: 'POS 一条式',  comp: OrderTicketView }
]

const currentPath = ref(router.currentRoute.value.path)
const counts = ref({})

const currentView = computed(() => {
  const m = modules.find((mm) => mm.path === currentPath.value)
  return m ? m.comp : BeanBatchView
})

async function loadCounts() {
  try {
    const [b, m, t, q, o] = await Promise.all([
      api.listBeanBatches(), api.listMenuItems(),
      api.listTableInfos(), api.cleaningQueue(), api.listOrderTickets()
    ])
    counts.value = {
      '/bean-batch': b.length,
      '/menu-item': m.length,
      '/table-info': t.length,
      '/cleaning': q.length,
      '/order-ticket': o.length
    }
  } catch (e) { /* 计数失败不影响主流程 */ }
}

function go(path) {
  router.push(path)
}

watch(() => router.currentRoute.value.path, (p) => { currentPath.value = p })

onMounted(async () => {
  currentPath.value = router.currentRoute.value.path
  await loadCounts()
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">咖</span>
        <div class="brand-text">
          <div class="brand-title">咖啡馆 · 咖啡豆批次与饮品出单</div>
          <div class="brand-sub">顶部横向导航 · 棕色主题 · 收银台一条式出单</div>
        </div>
      </div>
      <el-menu
        class="nav"
        mode="horizontal"
        :ellipsis="false"
        :default-active="currentPath"
        @select="go"
      >
        <el-menu-item
          v-for="m in modules"
          :key="m.path"
          :index="m.path"
        >
          <span class="nav-label">{{ m.label }}</span>
          <span class="nav-count" v-if="counts[m.path] !== undefined">{{ counts[m.path] }}</span>
        </el-menu-item>
      </el-menu>
    </header>

    <main class="content">
      <component :is="currentView" @changed="loadCounts" />
    </main>
  </div>
</template>

<style scoped>
.app-shell { min-height: 100vh; display: flex; flex-direction: column; }

.topbar {
  display: flex; align-items: center; gap: 24px;
  padding: 0 22px; height: 64px;
  background: linear-gradient(100deg, var(--primary-deep) 0%, var(--primary) 60%, var(--primary-soft) 100%);
  color: #fff; box-shadow: 0 2px 12px rgba(87, 60, 51, 0.25);
}
.brand { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }
.brand-mark {
  width: 38px; height: 38px; border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  background: #fff; color: var(--primary-deep); font-weight: 700; font-size: 20px;
}
.brand-title { font-size: 17px; font-weight: 600; }
.brand-sub { font-size: 12px; opacity: 0.85; }

.nav { flex: 1; background: transparent; border-bottom: none; }
.nav :deep(.el-menu-item) {
  color: rgba(255, 255, 255, 0.82);
  height: 64px; line-height: 64px; font-size: 15px;
}
.nav :deep(.el-menu-item.is-active) {
  color: #fff; font-weight: 700;
  background: rgba(255, 255, 255, 0.14);
  border-bottom: 3px solid #fff;
}
.nav :deep(.el-menu-item:hover) { background: rgba(255, 255, 255, 0.1); color: #fff; }
.nav-label { margin-right: 6px; }
.nav-count {
  display: inline-flex; align-items: center; justify-content: center;
  min-width: 20px; height: 20px; padding: 0 5px; border-radius: 10px;
  background: rgba(255, 255, 255, 0.28); color: #fff; font-size: 12px;
}

.content { flex: 1; padding: 20px; }
</style>
