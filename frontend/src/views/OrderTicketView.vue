<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const emit = defineEmits(['changed'])

const tables = ref([])
const menuItems = ref([])       // 在售饮品（基础信息）
const stockMap = ref({})        // menuItemId -> 库存视图
const selected = ref(null)
const current = ref(null)       // 账单详情 { ticket, items, consumptions }
const preview = ref(null)       // 完成前消耗预览
const operator = ref(localStorage.getItem('cafe_operator') || '')
const OPERATOR_PRESETS = ['小张', '小李', '小王']
const finishing = ref(false)
const settling = ref(false)
const opening = ref(false)

const tableType = (s) => ({ '空闲': 'success', '占用': 'warning', '清洁中': 'info' }[s] || 'info')
const ticketType = (s) => ({ '制作中': 'warning', '已完成': 'success', '已结账': 'info' }[s] || 'info')

const total = computed(() => (current.value ? Number(current.value.ticket.total).toFixed(2) : '0.00'))
const stockOf = (id) => stockMap.value[id] || null
// 同一明细出现在多个批次 = 该饮品发生了跨批次拆分
const crossItemNos = computed(() => {
  if (!preview.value) return []
  const cnt = {}
  for (const b of preview.value.batches) {
    for (const sp of b.splits) cnt[sp.ticketItemId] = (cnt[sp.ticketItemId] || 0) + 1
  }
  return Object.keys(cnt).filter((k) => cnt[k] > 1).map(Number)
})

async function loadTables() { tables.value = await api.listTableInfos() }
async function loadMenu() {
  const [menus, stock] = await Promise.all([api.listMenuItems('在售'), api.onSaleStock()])
  menuItems.value = menus
  stockMap.value = Object.fromEntries(stock.map((s) => [s.menuItemId, s]))
}

async function selectTable(t) {
  selected.value = t
  current.value = null
  preview.value = null
  if (t.status === '空闲') return
  if (t.status === '清洁中' || t.activeCleaning) {
    const cleaning = t.activeCleaning
    if (cleaning?.status === '清洁中') {
      ElMessage.info(`该桌台由「${cleaning.cleanerName}」清洁中，预计 ${fmtTime(cleaning.expectedFinishAt)} 完成，暂不可开台`)
    } else {
      ElMessage.info('该桌台已结账，正在等待清洁人员接单；清洁完成前不能开台')
    }
    return
  }
  const all = await api.listOrderTickets()
  const active = all.find((x) => x.tableId === t.id && (x.status === '制作中' || x.status === '已完成'))
  if (active) {
    current.value = await api.getOrderTicket(active.id)
  } else {
    current.value = await api.createOrderTicket({ tableId: t.id, items: [] })
    emit('changed')
  }
  await loadPreview()
}

async function openSelected() {
  if (!selected.value) { ElMessage.warning('请先选择桌台'); return }
  if (opening.value) return
  opening.value = true
  try {
    const expectedStatus = selected.value.status
    const updated = await api.openTable(selected.value.id, expectedStatus)
    await loadTables()
    const opened = tables.value.find((t) => t.id === updated.id) || updated
    await selectTable(opened)
    ElMessage.success('已开台')
  } catch (e) {
    await loadTables()
    ElMessage.error(e.message)
  } finally {
    opening.value = false
  }
}

function onOperator(v) {
  operator.value = v
  localStorage.setItem('cafe_operator', v)
}

async function addItem(mi) {
  if (!current.value) { ElMessage.warning('请先开台'); return }
  if (current.value.ticket.status !== '制作中') { ElMessage.warning('当前账单不可加单（需为制作中）'); return }
  const stock = stockOf(mi.id)
  try {
    if (stock && !stock.enough) {
      // 库存不足不禁止点单，但必须让收银员看清楚后果
      await ElMessageBox.confirm(
        `${mi.name}：${stock.reason}。\n仍可先点单，但完成制作时若库存不足，整张账单都会保持「制作中」、不扣任何豆。是否继续加入？`,
        '库存不足提示', { confirmButtonText: '仍然加入', cancelButtonText: '取消', type: 'warning' })
    }
    current.value = await api.addOrderItem(current.value.ticket.id, { menuItemId: mi.id, qty: 1 })
    await loadPreview()
    ElMessage.success(`已加入 ${mi.name}`)
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  }
}

async function loadPreview() {
  if (!current.value || current.value.ticket.status !== '制作中') { preview.value = null; return }
  try { preview.value = await api.finishPreview(current.value.ticket.id) }
  catch (e) { preview.value = null }
}

async function refresh() {
  if (current.value) {
    current.value = await api.getOrderTicket(current.value.ticket.id)
    await loadPreview()
  }
}

async function finish() {
  if (finishing.value) return           // 防重复提交
  if (!operator.value || !operator.value.trim()) {
    ElMessage.warning('请先选择或填写本次操作者（收银员）'); return
  }
  if (preview.value && !preview.value.satisfiable) {
    ElMessage.error('库存无法整单满足，请先看下方「本次豆量核算」的失败原因'); return
  }
  finishing.value = true
  try {
    current.value = await api.finishOrderTicket(current.value.ticket.id, operator.value.trim())
    preview.value = null
    await loadMenu()                    // 库存已变，刷新菜单卡片
    emit('changed')
    ElMessage.success('制作完成：已按配方扣减咖啡豆并记录流水')
  } catch (e) {
    // 并发失败/库存失败：账单保持制作中，桌台照常，收银员可改单或稍后重试
    await refresh()
    ElMessage({ type: 'error', message: e.message, duration: 6000, showClose: true })
  } finally {
    finishing.value = false
  }
}

async function settle() {
  if (settling.value) return
  if (!operator.value || !operator.value.trim()) {
    ElMessage.warning('请先选择或填写结账收银员'); return
  }
  settling.value = true
  try {
    await api.settleOrderTicket(current.value.ticket.id, operator.value.trim())
    await loadTables()
    selected.value = tables.value.find((t) => t.id === current.value.ticket.tableId) || selected.value
    current.value = null
    preview.value = null
    emit('changed'); ElMessage.success('已结账：桌台已进入清洁队列，清洁完成后才能再次开台')
  } catch (e) {
    await Promise.all([refresh().catch(() => {}), loadTables()])
    await loadMenu()
    ElMessage({ type: 'error', message: e.message, duration: 6000, showClose: true })
  } finally {
    settling.value = false
  }
}

function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '' }
function gramsText(g) { return `${g} 克` }

onMounted(async () => { await loadTables(); await loadMenu() })
</script>

<template>
  <div class="pos">
    <div class="pos-title-row">
      <h2 class="pos-title">收银出单 · POS 一条式</h2>
      <div class="operator-box">
        <span class="op-label">本次操作者</span>
        <el-select v-model="operator" filterable allow-create default-first-option
          placeholder="选择或输入收银员" style="width:180px" @change="onOperator">
          <el-option v-for="p in OPERATOR_PRESETS" :key="p" :label="p" :value="p" />
        </el-select>
      </div>
    </div>
    <div class="pos-grid">
      <!-- 左：饮品菜单（带库存标记） -->
      <section class="col col-menu">
        <div class="col-head">饮品菜单（在售）<span class="col-sub">灰条=库存不足，仍可点单</span></div>
        <div class="menu-grid">
          <button v-for="mi in menuItems" :key="mi.id" class="menu-card"
            :class="{ lack: stockOf(mi.id) && !stockOf(mi.id).enough }" @click="addItem(mi)">
            <span class="mc-name">{{ mi.name }}</span>
            <span class="mc-cat">{{ mi.category }} · ¥{{ Number(mi.price).toFixed(2) }}</span>
            <span v-if="stockOf(mi.id) && stockOf(mi.id).enough" class="mc-stock ok">
              库存可做（{{ stockOf(mi.id).needs.map(n => n.beanOrigin).join('、') }}）
            </span>
            <span v-else-if="stockOf(mi.id)" class="mc-stock no">{{ stockOf(mi.id).reason }}</span>
          </button>
          <div v-if="!menuItems.length" class="empty">暂无在售饮品</div>
        </div>
      </section>

      <!-- 中：点单流水 + 豆量核算 -->
      <section class="col col-flow">
        <div class="col-head">点单流水</div>
        <el-table :data="current ? current.items : []" border size="small">
          <el-table-column prop="menuName" label="饮品" min-width="110" />
          <el-table-column label="单价" width="72" align="right">
            <template #default="{ row }">¥{{ Number(row.unitPrice).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column prop="qty" label="数量" width="56" align="center" />
          <el-table-column label="小计" width="72" align="right">
            <template #default="{ row }">¥{{ Number(row.subtotal).toFixed(2) }}</template>
          </el-table-column>
        </el-table>
        <div v-if="!current" class="flow-empty">请先在右侧选择并开台</div>

        <!-- 制作中：完成前豆量核算（按当前库存预演 FIFO） -->
        <template v-if="current && current.ticket.status === '制作中'">
          <div class="plan-box">
            <div class="plan-title">本次豆量核算（完成时按当时配方执行）</div>
            <template v-if="preview">
              <el-alert v-if="preview.satisfiable" type="success" :closable="false" show-icon
                title="库存充足，可整单完成：将按入库先后从以下批次扣减" />
              <div v-else class="plan-fail">
                <el-alert type="error" :closable="false" show-icon title="现在无法完成制作（完成时账单将保持制作中，不扣任何批次）" />
                <ul class="fail-list">
                  <li v-for="m in preview.missingRecipes" :key="m.ticketItemId">
                    饮品「{{ m.menuName }}」×{{ m.qty }} 尚未维护配方，请联系管理员补配方
                  </li>
                  <li v-for="s in preview.shortages" :key="s.beanOrigin">
                    豆种「{{ s.beanOrigin }}」需 {{ s.requiredGrams }} 克，可用仅 {{ s.availableGrams }} 克，
                    <b>还差 {{ s.lackGrams }} 克</b>（{{ s.menuNames.join('、') }}）
                  </li>
                </ul>
              </div>
              <div v-if="preview.batches.length" class="plan-batches">
                <div v-for="b in preview.batches" :key="b.batchId" class="plan-batch">
                  <div class="pb-head">
                    <el-tag size="small" type="warning">{{ b.batchNo }}</el-tag>
                    <span>{{ b.beanOrigin }}（{{ b.roastLevel }}烘）</span>
                    <b>本次扣 {{ b.useGrams }} 克</b>
                  </div>
                  <div class="pb-sub">
                    入库 {{ fmtTime(b.storedAt) }} · 扣减前余量 {{ b.remainingGrams }} 克
                    <span v-if="b.splits.length > 1" class="cross-flag">本批次为 {{ b.splits.length }} 项明细供豆</span>
                  </div>
                  <div class="pb-splits">
                    <span v-for="sp in b.splits" :key="sp.ticketItemId" class="pb-split">
                      {{ sp.menuName }}：{{ sp.grams }} 克
                      <em v-if="crossItemNos.includes(sp.ticketItemId)" class="cross-item">↘ 跨批次</em>
                    </span>
                  </div>
                </div>
              </div>
              <div v-if="preview.satisfiable && !preview.batches.length" class="plan-note">本单均为非咖啡饮品，不消耗咖啡豆</div>
            </template>
          </div>
        </template>

        <!-- 已完成/已结账：展示已固化的消耗流水（历史快照） -->
        <template v-if="current && current.ticket.status !== '制作中'">
          <div class="done-box">
            <div class="plan-title">咖啡豆消耗记录
              <span class="done-meta">操作者 {{ current.ticket.finishedBy || '—' }} · {{ fmtTime(current.ticket.finishedAt) }}</span>
            </div>
            <el-table :data="current.consumptions" border size="small">
              <el-table-column prop="batchNo" label="批次" width="120" />
              <el-table-column prop="menuName" label="饮品" min-width="90" />
              <el-table-column prop="beanOrigin" label="豆种" min-width="120" show-overflow-tooltip />
              <el-table-column label="克数" width="70" align="right">
                <template #default="{ row }"><b>{{ row.grams }}</b></template>
              </el-table-column>
              <el-table-column prop="operator" label="操作者" width="80" align="center" />
            </el-table>
            <div v-if="!current.consumptions.length" class="plan-note">
              本账单完成于配方/库存功能上线前或均为非咖啡饮品，无消耗流水；金额仍按点单时快照保留
            </div>
          </div>
        </template>
      </section>

      <!-- 右：桌台与结算 -->
      <section class="col col-pay">
        <div class="col-head">桌台与结算</div>
        <div class="table-grid">
          <button v-for="t in tables" :key="t.id" class="table-card"
            :class="{ on: selected && selected.id === t.id, blocked: t.status === '清洁中' || t.activeCleaning }"
            @click="selectTable(t)">
            <span class="tc-no">{{ t.tableNo }}</span>
            <span class="tc-seats">{{ t.seats }} 座</span>
            <el-tag size="small" :type="tableType(t.status)">{{ t.status }}</el-tag>
            <span v-if="t.activeCleaning" class="tc-clean">
              {{ t.activeCleaning.status === '待接单' ? '等待清洁接单' : `清洁：${t.activeCleaning.cleanerName}` }}
            </span>
          </button>
        </div>

        <div class="pay-box" v-if="selected">
          <div class="pay-row">
            <span>当前桌台</span><b>{{ selected.tableNo }}（{{ selected.status }}）</b>
          </div>
          <el-button v-if="selected.status === '空闲' && !selected.activeCleaning" type="primary"
            :loading="opening" :disabled="opening" @click="openSelected">
            {{ opening ? '正在开台…' : '开台' }}
          </el-button>
          <el-alert v-if="selected.status === '清洁中' || selected.activeCleaning" type="warning"
            :closable="false" show-icon class="clean-alert"
            :title="selected.activeCleaning?.status === '清洁中'
              ? `清洁责任人 ${selected.activeCleaning.cleanerName || '—'}，预计 ${fmtTime(selected.activeCleaning.expectedFinishAt)} 完成；收银员不能绕过清洁开台`
              : '桌台已结账释放，正在等待清洁人员接单；不能直接开台'" />

          <template v-if="current">
            <div class="pay-row">
              <span>账单状态</span>
              <el-tag size="small" :type="ticketType(current.ticket.status)">{{ current.ticket.status }}</el-tag>
            </div>
            <div class="pay-total"><span>合计</span><b>¥{{ total }}</b></div>
            <el-button v-if="current.ticket.status === '制作中'" type="success"
              :loading="finishing" :disabled="finishing || !operator"
              @click="finish">
              {{ finishing ? '正在完成…' : '完成制作（按配方扣豆）' }}
            </el-button>
            <el-button v-if="current.ticket.status === '已完成'" type="primary"
              :loading="settling" :disabled="settling || !operator" @click="settle">
              {{ settling ? '正在结账…' : '结账并移交清洁' }}
            </el-button>
            <el-button size="small" text @click="refresh">刷新账单/库存</el-button>
          </template>
        </div>
        <div v-else class="pay-box hint">请选择上方桌台</div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.pos-title-row { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.pos-title { margin: 0; font-size: 18px; }
.operator-box { display: flex; align-items: center; gap: 8px; }
.op-label { font-size: 13px; color: var(--muted); }

.pos-grid { display: grid; grid-template-columns: 1.2fr 1.3fr 1fr; gap: 14px; align-items: start; }
.col { background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 12px; min-height: 420px; }
.col-head { font-weight: 700; margin-bottom: 10px; color: var(--primary-deep); }
.col-sub { font-weight: 400; font-size: 12px; color: var(--muted); margin-left: 8px; }

.menu-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
.menu-card {
  display: flex; flex-direction: column; gap: 4px; align-items: flex-start;
  border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px;
  background: var(--primary-wash); cursor: pointer; transition: all .15s; text-align: left;
}
.menu-card:hover { border-color: var(--primary); transform: translateY(-2px); box-shadow: 0 4px 12px rgba(109,76,65,.18); }
.menu-card.lack { background: #f4f4f5; border-style: dashed; }
.mc-name { font-weight: 700; color: var(--ink); }
.mc-cat { font-size: 12px; color: var(--muted); }
.mc-stock { font-size: 11px; line-height: 1.4; }
.mc-stock.ok { color: #67c23a; }
.mc-stock.no { color: #f56c6c; }

.empty { grid-column: 1 / -1; color: var(--muted); text-align: center; padding: 30px 0; }
.flow-empty { color: var(--muted); text-align: center; padding: 40px 0; }

.plan-box, .done-box { margin-top: 12px; border-top: 1px dashed var(--line); padding-top: 10px; }
.plan-title { font-weight: 700; font-size: 13px; margin-bottom: 8px; color: var(--primary-deep); }
.done-meta { font-weight: 400; color: var(--muted); font-size: 12px; margin-left: 8px; }
.plan-fail { margin-top: 8px; }
.fail-list { margin: 8px 0 0; padding-left: 18px; font-size: 12.5px; color: #c45656; line-height: 1.8; }
.plan-batches { margin-top: 10px; display: flex; flex-direction: column; gap: 8px; }
.plan-batch { border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px; background: #fdfaf7; }
.pb-head { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.pb-head b { margin-left: auto; color: var(--primary-deep); }
.pb-sub { font-size: 11.5px; color: var(--muted); margin: 4px 0 6px; }
.cross-flag { color: #e6a23c; margin-left: 8px; }
.pb-splits { display: flex; flex-wrap: wrap; gap: 6px; }
.pb-split { font-size: 11.5px; background: #fff; border: 1px solid var(--line); border-radius: 10px; padding: 2px 8px; }
.cross-item { font-style: normal; color: #e6a23c; margin-left: 4px; }
.plan-note { margin-top: 8px; font-size: 12px; color: var(--muted); }

.table-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-bottom: 12px; }
.table-card {
  display: flex; flex-direction: column; gap: 4px; align-items: center;
  border: 1px solid var(--line); border-radius: 10px; padding: 10px 6px;
  background: #fff; cursor: pointer; transition: all .15s;
}
.table-card.on { border-color: var(--primary); background: var(--primary-wash); }
.table-card.blocked { border-style: dashed; background: #fdf6ec; }
.tc-no { font-weight: 700; font-size: 16px; }
.tc-seats { font-size: 12px; color: var(--muted); }
.tc-clean { font-size: 11px; color: #b25e09; line-height: 1.3; }
.clean-alert { margin-top: 2px; }

.pay-box { border-top: 1px dashed var(--line); padding-top: 12px; display: flex; flex-direction: column; gap: 10px; }
.pay-box.hint { color: var(--muted); }
.pay-row { display: flex; align-items: center; justify-content: space-between; }
.pay-total { display: flex; align-items: center; justify-content: space-between; font-size: 16px; }
.pay-total b { color: var(--primary-deep); font-size: 22px; }
</style>
