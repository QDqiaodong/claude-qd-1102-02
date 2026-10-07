<script setup>
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const emit = defineEmits(['changed'])

// 当前视角：清洁人员 / 收银员 / 管理员（同一页面让三种角色各看各的可执行动作）
const role = ref(localStorage.getItem('cafe_clean_role') || 'cleaner')
// 清洁责任人姓名（与 POS 收银员选择同款交互，刷新后保留）
const cleaner = ref(localStorage.getItem('cafe_cleaner') || '')
const CLEANER_PRESETS = ['老赵', '老钱', '老孙']

const queue = ref([])
const tables = ref([])
const recentLogs = ref([])
const historyTasks = ref([])
const loading = ref(false)
const busyId = ref(null) // 正在提交的清洁单 id：防重复点击，期间所有动作按钮禁用

const claimDialog = reactive({ open: false, taskId: null, tableNo: '', eta: defaultEta() })
const reasonDialog = reactive({ open: false, taskId: null, tableNo: '', reason: '' })
const logDialog = reactive({ open: false, title: '', logs: [] })

const taskTag = (s) => ({ 待接单: 'info', 清洁中: 'warning', 已完成: 'success' }[s] || 'info')
const tableTag = (s) => ({ 空闲: 'success', 占用: 'warning', 清洁中: 'info' }[s] || 'info')

const pendingCount = computed(() => queue.value.filter((t) => t.status === '待接单').length)
const cleaningCount = computed(() => queue.value.filter((t) => t.status === '清洁中').length)

function nowText() {
  return fmt(new Date().toISOString())
}

function defaultEta() {
  const d = new Date(Date.now() + 30 * 60 * 1000)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function onRole(v) {
  role.value = v
  localStorage.setItem('cafe_clean_role', v)
  loadAll()
}
function onCleaner(v) {
  cleaner.value = v
  localStorage.setItem('cafe_cleaner', v)
}

function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '—' }

function overdue(t) {
  return t.status === '清洁中' && t.etaAt && new Date(t.etaAt).getTime() < Date.now()
}

async function loadAll() {
  loading.value = true
  try {
    const [q, ts] = await Promise.all([api.cleaningQueue(), api.listTableInfos()])
    queue.value = q
    tables.value = ts
    if (role.value === 'admin') {
      const [dash, all] = await Promise.all([api.cleaningDashboard(), api.listCleanings()])
      recentLogs.value = dash.recentLogs
      historyTasks.value = all
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

/** 统一动作入口：串行化按钮点击 + 失败后强制拉取最新状态（责任人/原因以后端为准） */
async function runAction(id, fn, okText) {
  if (busyId.value) {
    ElMessage.warning('上一个操作还在提交中，请勿重复点击')
    return
  }
  busyId.value = id
  try {
    await fn()
    ElMessage.success(okText)
    await loadAll()
    emit('changed')
  } catch (e) {
    // 并发失败/状态拦截：什么都不覆盖，刷新后展示最新责任人、时间、原因与拦截原因
    await loadAll()
    ElMessage({ type: 'error', message: e.message, duration: 6000, showClose: true })
  } finally {
    busyId.value = null
  }
}

function requireCleaner() {
  if (!cleaner.value || !cleaner.value.trim()) {
    ElMessage.warning('请先在顶部选择或填写清洁责任人姓名')
    return false
  }
  return true
}

function openClaim(t) {
  if (!requireCleaner()) return
  claimDialog.taskId = t.id
  claimDialog.tableNo = t.tableNo
  claimDialog.eta = defaultEta()
  claimDialog.open = true
}

async function submitClaim() {
  if (!claimDialog.eta) { ElMessage.warning('请选择预计完成时间'); return }
  const id = claimDialog.taskId
  const eta = String(claimDialog.eta).replace(' ', 'T')
  claimDialog.open = false
  await runAction(id, () => api.claimCleaning(id, cleaner.value.trim(), eta), '已接单：责任人、开始时间、预计完成时间已记录')
}

function openReason(t) {
  if (!requireCleaner()) return
  reasonDialog.taskId = t.id
  reasonDialog.tableNo = t.tableNo
  reasonDialog.reason = t.pendingReason || ''
  reasonDialog.open = true
}

async function submitReason() {
  if (!reasonDialog.reason.trim()) { ElMessage.warning('请填写未完成原因'); return }
  const id = reasonDialog.taskId
  const reason = reasonDialog.reason.trim()
  reasonDialog.open = false
  await runAction(id, () => api.saveCleaningReason(id, cleaner.value.trim(), reason), '未完成原因已暂存，刷新页面后仍保留')
}

async function complete(t) {
  if (!requireCleaner()) return
  try {
    await ElMessageBox.confirm(
      `确认桌台「${t.tableNo}」已清洁完成？完成后桌台将立即显示为「空闲」，收银员可为下一桌开台。`,
      '完成清洁', { confirmButtonText: '确认完成', cancelButtonText: '取消', type: 'success' })
  } catch { return }
  await runAction(t.id, () => api.completeCleaning(t.id, cleaner.value.trim()),
    '清洁完成，桌台已释放为空闲')
}

async function withdraw(t) {
  if (!requireCleaner()) return
  try {
    await ElMessageBox.confirm(
      `确认撤回桌台「${t.tableNo}」的清洁？清洁单将回到「待接单」，当前责任人清空，已暂存的未完成原因会保留。`,
      '撤回清洁', { confirmButtonText: '确认撤回', cancelButtonText: '取消', type: 'warning' })
  } catch { return }
  await runAction(t.id, () => api.withdrawCleaning(t.id, cleaner.value.trim()),
    '已撤回：清洁单回到待接单队列，未完成原因已保留')
}

/** 收银员视角开台：清洁中会被后端拦截并提示原因 */
async function cashierOpen(t) {
  await runAction(`open-${t.id}`, async () => {
    await api.openTable(t.id)
  }, '已开台')
}

async function showLogs(t) {
  try {
    logDialog.logs = await api.cleaningLogs(t.id)
    logDialog.title = `桌台 ${t.tableNo} · 清洁单 #${t.id} 交接记录`
    logDialog.open = true
  } catch (e) { ElMessage.error(e.message) }
}

function taskOfTable(tableId) {
  return queue.value.find((t) => t.tableId === tableId) || null
}

// 5 秒轮询：清洁人员/收银员在别的终端推进后，本页面也能看到最新状态
let timer = null
onMounted(() => {
  loadAll()
  timer = setInterval(loadAll, 5000)
})
onBeforeUnmount(() => timer && clearInterval(timer))
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <h2>桌台清洁交接</h2>
      <div class="head-right">
        <el-radio-group :model-value="role" @change="onRole">
          <el-radio-button value="cleaner">清洁人员</el-radio-button>
          <el-radio-button value="cashier">收银员</el-radio-button>
          <el-radio-button value="admin">管理员</el-radio-button>
        </el-radio-group>
        <el-button @click="loadAll">刷新</el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" show-icon class="flow-tip"
      title="交接主线：收银员结账 → 桌台进入「清洁中」并生成待接单清洁单 → 清洁人员接单（记录责任人/开始时间/预计完成时间）→ 完成清洁后桌台才回到「空闲」可开台。清洁中可暂存未完成原因、可撤回，历史交接记录只追加不改写。" />

    <!-- ================= 清洁人员视角 ================= -->
    <template v-if="role === 'cleaner'">
      <div class="identity-bar">
        <span class="id-label">我是清洁责任人</span>
        <el-select v-model="cleaner" filterable allow-create default-first-option
          placeholder="选择或输入姓名" style="width:200px" @change="onCleaner">
          <el-option v-for="p in CLEANER_PRESETS" :key="p" :label="p" :value="p" />
        </el-select>
        <span class="queue-stat">
          当前队列：<b>{{ pendingCount }}</b> 张待接单 · <b>{{ cleaningCount }}</b> 张清洁中
        </span>
      </div>

      <el-empty v-if="!queue.length" description="当前没有待清洁桌台" />

      <div class="task-grid">
        <div v-for="t in queue" :key="t.id" class="task-card" :class="{ mine: t.cleaner === cleaner && t.status === '清洁中' }">
          <div class="tc-top">
            <span class="tc-no">{{ t.tableNo }}</span>
            <el-tag :type="taskTag(t.status)">{{ t.status }}</el-tag>
            <el-tag v-if="t.blockedByNewTicket" type="danger" size="small" effect="dark">被新账单拦截</el-tag>
          </div>

          <!-- 待接单 -->
          <div class="tc-body">
            <template v-if="t.status === '待接单'">
              <div class="meta">入队时间：{{ fmt(t.queuedAt) }}</div>
              <div class="meta">交接收银员：{{ t.cashier || '—' }}</div>
              <el-alert v-if="t.blockedByNewTicket" type="error" :closable="false" show-icon class="block-alert"
                :title="`桌台已有新账单（#${t.activeTicketId}），旧清洁单不能再操作，请联系管理员核对`" />
              <el-button type="primary" class="full-btn" :disabled="!!busyId || t.blockedByNewTicket"
                @click="openClaim(t)">接单（记录责任人 / 开始 / 预计完成）</el-button>
            </template>

            <!-- 清洁中 -->
            <template v-else>
              <div class="meta">责任人：<b>{{ t.cleaner }}</b></div>
              <div class="meta">开始时间：{{ fmt(t.startedAt) }}</div>
              <div class="meta">
                预计完成：{{ fmt(t.etaAt) }}
                <el-tag v-if="overdue(t)" type="danger" size="small">已超时</el-tag>
              </div>
              <div class="reason-box" v-if="t.pendingReason">
                <div class="reason-head">未完成原因（{{ fmt(t.reasonSavedAt) }} 暂存）</div>
                <div class="reason-text">{{ t.pendingReason }}</div>
              </div>
              <el-alert v-if="t.blockedByNewTicket" type="error" :closable="false" show-icon class="block-alert"
                :title="`桌台已有新账单（#${t.activeTicketId}），不能完成/撤回这张旧清洁单，请联系管理员核对`" />
              <template v-if="t.cleaner === cleaner && !t.blockedByNewTicket">
                <el-button class="full-btn" :disabled="!!busyId" @click="openReason(t)">
                  {{ t.pendingReason ? '修改未完成原因并暂存' : '填写未完成原因并暂存' }}
                </el-button>
                <div class="btn-row">
                  <el-button type="success" :disabled="!!busyId" @click="complete(t)">完成清洁</el-button>
                  <el-button type="warning" plain :disabled="!!busyId" @click="withdraw(t)">撤回清洁</el-button>
                </div>
              </template>
              <el-alert v-else-if="!t.blockedByNewTicket" type="info" :closable="false"
                title="该清洁单由其他责任人负责，你不能代为完成或撤回" />
            </template>
          </div>

          <el-button text size="small" class="log-link" @click="showLogs(t)">查看交接记录</el-button>
        </div>
      </div>
    </template>

    <!-- ================= 收银员视角 ================= -->
    <template v-if="role === 'cashier'">
      <el-alert type="warning" :closable="false" show-icon class="flow-tip"
        title="收银员只能在桌台「空闲」时开台；结账后桌台自动进入「清洁中」，必须等清洁人员完成清洁才会重新空闲，不能绕过清洁直接开台。" />
      <div class="cashier-grid">
        <div v-for="t in tables" :key="t.id" class="cashier-card">
          <div class="tc-top">
            <span class="tc-no">{{ t.tableNo }}</span>
            <el-tag :type="tableTag(t.status)">{{ t.status }}</el-tag>
          </div>
          <div class="meta">{{ t.seats }} 座</div>

          <template v-if="t.status === '空闲'">
            <el-button type="primary" class="full-btn" :disabled="!!busyId" @click="cashierOpen(t)">开台</el-button>
            <div class="ok-note">可开台：上一桌已结账且清洁已完成</div>
          </template>

          <template v-else-if="t.status === '清洁中'">
            <el-button type="primary" class="full-btn" disabled>开台</el-button>
            <template v-if="taskOfTable(t.id)">
              <el-alert type="error" :closable="false" show-icon class="block-alert"
                :title="`被拦截：桌台清洁中（${taskOfTable(t.id).status}），不能开台`" />
              <div class="meta" v-if="taskOfTable(t.id).cleaner">
                清洁责任人：<b>{{ taskOfTable(t.id).cleaner }}</b> · 预计 {{ fmt(taskOfTable(t.id).etaAt) }} 完成
              </div>
              <div class="meta" v-else>清洁单尚待清洁人员接单（{{ fmt(taskOfTable(t.id).queuedAt) }} 入队）</div>
              <el-button text size="small" @click="showLogs(taskOfTable(t.id))">查看清洁进度 / 交接记录</el-button>
            </template>
            <div class="meta" v-else>桌台清洁中（历史遗留清洁单），请联系清洁人员处理</div>
          </template>

          <template v-else>
            <el-button type="primary" class="full-btn" disabled>开台</el-button>
            <div class="meta">桌台已开台，请在「收银出单」页点单结算</div>
          </template>
        </div>
      </div>
    </template>

    <!-- ================= 管理员视角 ================= -->
    <template v-if="role === 'admin'">
      <div class="admin-stats">
        <el-statistic title="待接单" :value="pendingCount" />
        <el-statistic title="清洁中" :value="cleaningCount" />
        <el-statistic title="清洁队列合计" :value="queue.length" />
      </div>

      <h3 class="block-title">当前清洁队列</h3>
      <el-table :data="queue" border size="small">
        <el-table-column prop="tableNo" label="桌号" width="72" align="center" />
        <el-table-column label="清洁单" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="taskTag(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="cashier" label="结账收银员" width="100" align="center">
          <template #default="{ row }">{{ row.cashier || '—' }}</template>
        </el-table-column>
        <el-table-column prop="cleaner" label="清洁责任人" width="100" align="center">
          <template #default="{ row }">{{ row.cleaner || '未接单' }}</template>
        </el-table-column>
        <el-table-column label="入队" width="140">
          <template #default="{ row }">{{ fmt(row.queuedAt) }}</template>
        </el-table-column>
        <el-table-column label="开始 / 预计完成" width="270">
          <template #default="{ row }">
            <span v-if="row.startedAt">{{ fmt(row.startedAt) }} → <b :class="{ overdue: overdue(row) }">{{ fmt(row.etaAt) }}</b></span>
            <span v-else class="muted">尚未接单</span>
          </template>
        </el-table-column>
        <el-table-column label="未完成原因（暂存）" min-width="200">
          <template #default="{ row }">
            <span v-if="row.pendingReason">{{ row.pendingReason }}
              <span class="muted">（{{ fmt(row.reasonSavedAt) }}）</span>
            </span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="拦截状态" width="170">
          <template #default="{ row }">
            <el-tag v-if="row.blockedByNewTicket" type="danger" size="small">
              已有新账单 #{{ row.activeTicketId }}，旧单冻结
            </el-tag>
            <span v-else class="muted">正常</span>
          </template>
        </el-table-column>
        <el-table-column label="记录" width="90" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="showLogs(row)">交接记录</el-button>
          </template>
        </el-table-column>
      </el-table>

      <h3 class="block-title">最近交接记录（只追加，历史不改写）</h3>
      <el-table :data="recentLogs" border size="small">
        <el-table-column label="时间" width="140">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="tableNo" label="桌号" width="72" align="center" />
        <el-table-column prop="action" label="事件" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="{ '结账转清洁': 'info', '接单': 'warning', '暂存原因': '', '完成清洁': 'success', '撤回清洁': 'danger' }[row.action] || ''">
              {{ row.action }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="actor" label="操作人" width="100" align="center">
          <template #default="{ row }">{{ row.actor || '—' }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注 / 未完成原因" min-width="240" show-overflow-tooltip />
        <el-table-column label="清洁单" width="80" align="center">
          <template #default="{ row }">#{{ row.taskId }}</template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 接单弹窗 -->
    <el-dialog v-model="claimDialog.open" :title="`桌台 ${claimDialog.tableNo} · 清洁接单`" width="420px">
      <el-form label-width="110px">
        <el-form-item label="清洁责任人">
          <el-input :model-value="cleaner" disabled />
        </el-form-item>
        <el-form-item label="开始时间">
          <el-input :model-value="nowText()" disabled />
        </el-form-item>
        <el-form-item label="预计完成时间" required>
          <el-date-picker v-model="claimDialog.eta" type="datetime" format="YYYY-MM-DD HH:mm"
            value-format="YYYY-MM-DD HH:mm" placeholder="选择预计完成时间" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="claimDialog.open = false">取消</el-button>
        <el-button type="primary" @click="submitClaim">确认接单</el-button>
      </template>
    </el-dialog>

    <!-- 暂存原因弹窗 -->
    <el-dialog v-model="reasonDialog.open" :title="`桌台 ${reasonDialog.tableNo} · 暂存未完成原因`" width="460px">
      <el-input v-model="reasonDialog.reason" type="textarea" :rows="4" maxlength="255" show-word-limit
        placeholder="如：沙发缝有咖啡渍需二次擦拭、清洁设备故障等待处理……暂存后页面刷新仍保留" />
      <template #footer>
        <el-button @click="reasonDialog.open = false">取消</el-button>
        <el-button type="primary" @click="submitReason">暂存（不改变清洁状态）</el-button>
      </template>
    </el-dialog>

    <!-- 交接记录弹窗 -->
    <el-dialog v-model="logDialog.open" :title="logDialog.title" width="640px">
      <el-timeline v-if="logDialog.logs.length">
        <el-timeline-item v-for="l in logDialog.logs" :key="l.id"
          :type="{ '结账转清洁': 'primary', '接单': 'warning', '暂存原因': 'info', '完成清洁': 'success', '撤回清洁': 'danger' }[l.action]"
          :timestamp="fmt(l.createdAt)">
          <b>{{ l.action }}</b> · {{ l.actor || '系统' }}
          <div class="muted" v-if="l.remark">{{ l.remark }}</div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无交接记录" :image-size="60" />
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.head-right { display: flex; gap: 10px; align-items: center; }
h2 { margin: 0; font-size: 18px; }
h3.block-title { margin: 18px 0 8px; font-size: 15px; color: var(--primary-deep); }
.flow-tip { margin-bottom: 14px; }

.identity-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 14px;
  background: #fff; border: 1px solid var(--line); border-radius: 10px; padding: 10px 14px; }
.id-label { font-weight: 700; color: var(--primary-deep); }
.queue-stat { margin-left: auto; color: var(--muted); font-size: 13px; }
.queue-stat b { color: var(--primary-deep); font-size: 16px; }

.task-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 14px; }
.task-card, .cashier-card {
  background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 14px;
  display: flex; flex-direction: column; gap: 8px;
}
.task-card.mine { border-color: var(--primary); box-shadow: 0 4px 14px rgba(109,76,65,.12); }
.tc-top { display: flex; align-items: center; gap: 8px; }
.tc-no { font-size: 20px; font-weight: 800; color: var(--primary-deep); margin-right: auto; }
.tc-body { display: flex; flex-direction: column; gap: 6px; }
.meta { font-size: 13px; color: var(--ink); }
.muted { color: var(--muted); font-size: 12px; }
.full-btn { width: 100%; margin-top: 2px; }
.btn-row { display: flex; gap: 8px; }
.btn-row .el-button { flex: 1; }
.reason-box { background: #fdf6ec; border: 1px dashed #e6a23c; border-radius: 8px; padding: 8px 10px; }
.reason-head { font-size: 12px; color: #b88230; margin-bottom: 2px; }
.reason-text { font-size: 13px; white-space: pre-wrap; }
.block-alert { margin: 2px 0; }
.log-link { align-self: flex-end; }
.overdue { color: #f56c6c; }
.ok-note { font-size: 12px; color: #67c23a; }

.cashier-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 14px; }

.admin-stats { display: flex; gap: 40px; background: #fff; border: 1px solid var(--line);
  border-radius: 10px; padding: 14px 22px; margin-bottom: 6px; }
</style>
