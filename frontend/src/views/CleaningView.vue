<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const emit = defineEmits(['changed'])
const loading = ref(false)
const overview = ref({ queue: [], latestHandover: null, recentHandovers: [] })
const tables = ref([])
const tickets = ref([])
const role = ref(localStorage.getItem('cafe_cleaning_role') || '收银员')
const cleanerName = ref(localStorage.getItem('cafe_cleaner_name') || '')
const statusFilter = ref('')
const expanded = ref([])
const busyId = ref(null)

const acceptDialog = reactive({ visible: false, taskId: null, expectedAt: '', startAt: '' })
const reasonDialog = reactive({
  visible: false, mode: 'save', taskId: null, status: '', reason: ''
})
const CLEANER_PRESETS = ['小赵', '小钱', '小孙']

const filteredQueue = computed(() => {
  if (!statusFilter.value) return overview.value.queue || []
  return (overview.value.queue || []).filter((t) => t.status === statusFilter.value)
})
const pendingCount = computed(() => (overview.value.queue || []).filter((t) => t.status === '待接单').length)
const workingCount = computed(() => (overview.value.queue || []).filter((t) => t.status === '清洁中').length)
const tableNo = (id) => tables.value.find((t) => t.id === id)?.tableNo || `桌台ID ${id}`

function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '—' }
function tagType(s) {
  return { 待接单: 'warning', 清洁中: 'primary', 已完成: 'success', 空闲: 'success', 占用: 'danger' }[s] || 'info'
}
function ticketTagType(s) {
  return { 制作中: 'warning', 已完成: 'success', 已结账: 'info' }[s] || 'info'
}

function saveRole(v) {
  role.value = v
  localStorage.setItem('cafe_cleaning_role', v)
}
function saveCleaner(v) {
  cleanerName.value = v
  localStorage.setItem('cafe_cleaner_name', v)
}

async function load() {
  loading.value = true
  try {
    const [ov, ts, os] = await Promise.all([
      api.cleaningOverview(), api.listTableInfos(), api.listOrderTickets()
    ])
    overview.value = ov
    tables.value = ts
    tickets.value = os
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function blockReason(row) {
  if (role.value === '收银员') {
    return row.status === '待接单'
      ? '等待清洁人员接单，收银员不能绕过清洁开台'
      : `清洁责任人 ${row.cleanerName || '—'} 处理中，清洁完成后才可开台`
  }
  if (role.value === '管理员') return '管理员负责监督与查看，不代接、不改写责任人'
  if (!cleanerName.value.trim()) return '请先在上方填写清洁人员姓名'
  if (row.status === '待接单') return ''
  if (row.cleanerName !== cleanerName.value.trim()) {
    return `当前责任人是「${row.cleanerName}」，只有责任人可继续暂存/撤回/完成`
  }
  if (row.unfinishedReason) return '仍有未完成原因；处理后清空原因，才能完成清洁'
  return ''
}
function canAct(row) {
  return role.value === '清洁人员' && cleanerName.value.trim() && !blockReason(row)
}

function openAccept(row) {
  if (!canAct(row) || row.status !== '待接单') return
  acceptDialog.expectedAt = toLocal(new Date(Date.now() + 20 * 60 * 1000))
  acceptDialog.startAt = toLocal(new Date())
  acceptDialog.taskId = row.id
  acceptDialog.visible = true
}

function toIso(v) {
  if (!v) return ''
  return v instanceof Date ? toLocal(v) : String(v).replace(' ', 'T')
}
function toLocal(d) {
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function submitAccept() {
  if (!acceptDialog.expectedAt) { ElMessage.warning('请选择预计完成时间'); return }
  try {
    await api.acceptCleaningTask(acceptDialog.taskId, {
      cleanerName: cleanerName.value.trim(),
      expectedFinishAt: toIso(acceptDialog.expectedAt)
    })
    acceptDialog.visible = false
    ElMessage.success('已接单：责任人、开始时间和预计完成时间已记录')
    await load()
  } catch (e) {
    acceptDialog.visible = false
    await load()
    ElMessage.error(e.message)
  }
}

function openReason(row, mode) {
  if (!canAct(row) || row.status !== '清洁中') return
  reasonDialog.mode = mode
  reasonDialog.taskId = row.id
  reasonDialog.status = row.status
  reasonDialog.reason = mode === 'withdraw' ? (row.unfinishedReason || '') : (row.unfinishedReason || '')
  reasonDialog.visible = true
}

async function submitReason() {
  const id = reasonDialog.taskId
  const reason = reasonDialog.reason.trim()
  reasonDialog.visible = false
  busyId.value = id
  try {
    if (reasonDialog.mode === 'withdraw') {
      await api.withdrawCleaningTask(id, {
        cleanerName: cleanerName.value.trim(),
        expectedStatus: reasonDialog.status,
        unfinishedReason: reason
      })
      ElMessage.success('已撤回为待接单；桌台仍保持清洁中，原责任人与原因已保留')
    } else {
      await api.saveCleaningReason(id, {
        cleanerName: cleanerName.value.trim(),
        unfinishedReason: reason
      })
      ElMessage.success(reason ? '未完成原因已暂存，刷新后仍会保留' : '未完成原因已清空')
    }
    await load()
  } catch (e) {
    await load()
    ElMessage.error(e.message)
  } finally {
    busyId.value = null
  }
}

async function complete(row) {
  if (!canAct(row)) return
  if (row.unfinishedReason) {
    ElMessage.error('仍有未完成原因，请先处理并清空后再完成清洁')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认由「${cleanerName.value.trim()}」完成 ${row.tableNo} 的清洁？完成后桌台才会重新显示为可开台。`,
      '完成清洁确认', { confirmButtonText: '完成清洁', cancelButtonText: '取消', type: 'success' })
  } catch { return }
  busyId.value = row.id
  try {
    await api.completeCleaningTask(row.id, {
      cleanerName: cleanerName.value.trim(),
      expectedStatus: row.status
    })
    ElMessage.success('清洁完成，桌台已释放为空闲')
    await load()
  } catch (e) {
    await load()
    ElMessage.error(e.message)
  } finally {
    busyId.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="clean-page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2>桌台清洁交接</h2>
        <div class="sub">结账后必须清洁；历史交接只追加不改写，所有并发动作以后端行锁状态为准</div>
      </div>
      <el-radio-group :model-value="role" @change="saveRole">
        <el-radio-button label="收银员">收银员</el-radio-button>
        <el-radio-button label="清洁人员">清洁人员</el-radio-button>
        <el-radio-button label="管理员">管理员</el-radio-button>
      </el-radio-group>
    </div>

    <div class="role-line">
      <template v-if="role === '清洁人员'">
        <span>当前清洁人员</span>
        <el-select v-model="cleanerName" filterable allow-create default-first-option
          placeholder="选择或输入姓名" style="width:180px" @change="saveCleaner">
          <el-option v-for="n in CLEANER_PRESETS" :key="n" :label="n" :value="n" />
        </el-select>
        <el-alert type="info" :closable="false" show-icon
          title="只有当前责任人可以继续暂存原因、撤回或完成；刷新页面会从服务端恢复责任人、时间和原因。" />
      </template>
      <el-alert v-else-if="role === '收银员'" type="warning" :closable="false" show-icon
        title="收银视角：清洁中的桌台会在 POS 中拦截开台；请等待队列变为空闲，不能手工绕过。" />
      <el-alert v-else type="success" :closable="false" show-icon
        title="管理员视角：可查看当前清洁队列、最近交接流水、桌台与账单最终状态；不代执行清洁动作。" />
    </div>

    <div class="stat-row">
      <div class="stat"><b>{{ pendingCount }}</b><span>待接单</span></div>
      <div class="stat"><b>{{ workingCount }}</b><span>清洁中</span></div>
      <div class="stat"><b>{{ overview.recentHandovers?.length || 0 }}</b><span>最近交接记录</span></div>
      <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width:130px"
        @change="statusFilter = $event || ''">
        <el-option label="待接单" value="待接单" />
        <el-option label="清洁中" value="清洁中" />
      </el-select>
      <el-button @click="load" style="margin-left:auto">刷新队列</el-button>
    </div>

    <el-alert v-if="overview.latestHandover" class="latest" type="success" :closable="false" show-icon
      :title="`最近一次交接：${tableNo(overview.latestHandover.tableId)} · ${overview.latestHandover.event} · ${overview.latestHandover.operatorName || '—'} · ${fmt(overview.latestHandover.createdAt)}`" />

    <el-table :data="filteredQueue" border stripe row-key="id" :expand-row-keys="expanded"
      @expand-change="(row, rows) => expanded = rows.map(r => r.id)">
      <el-table-column type="expand" width="42">
        <template #default="{ row }">
          <div class="events">
            <el-timeline>
              <el-timeline-item v-for="e in row.events || []" :key="e.id"
                :timestamp="`${fmt(e.createdAt)} · ${e.event} · ${e.operatorName || '—'}`">
                <b>{{ row.tableNo }}</b>
                <el-tag size="small" effect="plain">{{ e.fromStatus || '—' }} → {{ e.toStatus || '—' }}</el-tag>
                <div v-if="e.expectedFinishAt">预计完成：{{ fmt(e.expectedFinishAt) }}</div>
                <div v-if="e.unfinishedReason" class="reason-text">原因：{{ e.unfinishedReason }}</div>
              </el-timeline-item>
            </el-timeline>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="tableNo" label="桌台" width="80" align="center" />
      <el-table-column label="清洁单" width="100" align="center">
        <template #default="{ row }"><el-tag :type="tagType(row.status)">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="桌台当前状态" width="120" align="center">
        <template #default="{ row }"><el-tag size="small" :type="tagType(row.tableStatus)">{{ row.tableStatus }}</el-tag></template>
      </el-table-column>
      <el-table-column label="结账释放" min-width="150">
        <template #default="{ row }">
          <div>{{ fmt(row.releasedAt) }}</div>
          <div class="muted">账单 #{{ row.ticketId }} · {{ row.ticketStatus }} · 收银 {{ row.releasedBy || '—' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="责任人 / 开始" min-width="160">
        <template #default="{ row }">
          <div>{{ row.cleanerName || '尚未接单' }}</div>
          <div class="muted">{{ fmt(row.acceptedAt) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="预计完成" width="145">
        <template #default="{ row }">{{ fmt(row.expectedFinishAt) }}</template>
      </el-table-column>
      <el-table-column label="未完成原因" min-width="220">
        <template #default="{ row }">
          <el-alert v-if="row.unfinishedReason" type="error" :title="row.unfinishedReason" :closable="false" show-icon />
          <span v-else class="muted">无</span>
          <div v-if="row.reasonUpdatedAt" class="muted">暂存于 {{ fmt(row.reasonUpdatedAt) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="可执行动作 / 拦截原因" width="330" fixed="right">
        <template #default="{ row }">
          <template v-if="role === '清洁人员' && cleanerName.trim()">
            <el-button size="small" type="primary" :disabled="row.status !== '待接单' || !!blockReason(row)"
              @click="openAccept(row)">接单</el-button>
            <el-button size="small" :disabled="row.status !== '清洁中' || !!blockReason(row)"
              :loading="busyId === row.id" @click="openReason(row, 'save')">暂存原因</el-button>
            <el-button size="small" type="warning" :disabled="row.status !== '清洁中' || !!blockReason(row)"
              :loading="busyId === row.id" @click="openReason(row, 'withdraw')">撤回清洁</el-button>
            <el-button size="small" type="success"
              :disabled="row.status !== '清洁中' || !!blockReason(row) || !!row.unfinishedReason"
              :loading="busyId === row.id" @click="complete(row)">完成清洁</el-button>
          </template>
          <div class="block">{{ blockReason(row) || '可执行当前状态允许的动作' }}</div>
        </template>
      </el-table-column>
    </el-table>

    <div class="bottom-grid">
      <section class="panel">
        <h3>最近交接记录（不可改写）</h3>
        <el-table :data="overview.recentHandovers || []" border size="small" max-height="360">
          <el-table-column prop="tableId" label="桌台ID" width="70" align="center" />
          <el-table-column prop="event" label="事件" width="110" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ row.fromStatus || '—' }} → {{ row.toStatus || '—' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="operatorName" label="操作者" width="90" />
          <el-table-column label="原因 / 预计完成" min-width="220">
            <template #default="{ row }">
              <div v-if="row.unfinishedReason" class="reason-text">{{ row.unfinishedReason }}</div>
              <div class="muted">预计 {{ fmt(row.expectedFinishAt) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="145">
            <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
      </section>

      <section class="panel" v-if="role === '管理员'">
        <h3>桌台与账单最终状态</h3>
        <el-table :data="tables" border size="small" max-height="230">
          <el-table-column prop="tableNo" label="桌台" width="70" align="center" />
          <el-table-column label="桌台" width="85">
            <template #default="{ row }"><el-tag size="small" :type="tagType(row.status)">{{ row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column label="清洁队列" min-width="140">
            <template #default="{ row }">
              <span v-if="row.activeCleaning">{{ row.activeCleaning.status }} · {{ row.activeCleaning.cleanerName || '待接单' }}</span>
              <span v-else class="muted">无活动清洁单</span>
            </template>
          </el-table-column>
        </el-table>
        <el-table :data="tickets" border size="small" max-height="230" class="ticket-table">
          <el-table-column prop="id" label="#" width="55" align="center" />
          <el-table-column prop="tableId" label="桌台ID" width="70" align="center" />
          <el-table-column label="账单状态" width="90">
            <template #default="{ row }"><el-tag size="small" :type="ticketTagType(row.status)">{{ row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column label="结账" min-width="145">
            <template #default="{ row }">
              <span v-if="row.status === '已结账'">{{ fmt(row.settledAt) }} · {{ row.settledBy || '—' }}</span>
              <span v-else class="muted">未结账</span>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>

    <el-dialog v-model="acceptDialog.visible" title="清洁人员接单" width="420px">
      <el-form label-width="110px">
        <el-form-item label="清洁责任人">
          <el-input :model-value="cleanerName" disabled />
        </el-form-item>
        <el-form-item label="开始时间">
          <el-input :model-value="acceptDialog.startAt" disabled />
        </el-form-item>
        <el-form-item label="预计完成时间" required>
          <el-date-picker v-model="acceptDialog.expectedAt" type="datetime" format="YYYY-MM-DD HH:mm"
            value-format="YYYY-MM-DD HH:mm" placeholder="选择预计完成时间" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="acceptDialog.visible=false">取消</el-button>
        <el-button type="primary" @click="submitAccept">确认接单</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reasonDialog.visible"
      :title="reasonDialog.mode === 'withdraw' ? '撤回清洁并保留交接信息' : '暂存未完成原因'" width="460px">
      <el-alert type="warning" :closable="false" show-icon class="dialog-alert"
        :title="reasonDialog.mode === 'withdraw'
          ? '撤回后清洁单回到待接单，但桌台仍为清洁中；责任人、开始时间、预计时间保留。'
          : '原因保存到服务端，页面刷新或异常中断后仍可继续；清空输入并保存表示问题已处理。'" />
      <el-input v-model="reasonDialog.reason" type="textarea" :rows="4" maxlength="500" show-word-limit
        placeholder="如：等待清洁剂 / 桌面破损需报修；没有未完成事项时可留空后保存" />
      <template #footer>
        <el-button @click="reasonDialog.visible=false">取消</el-button>
        <el-button :type="reasonDialog.mode === 'withdraw' ? 'warning' : 'primary'" @click="submitReason">
          {{ reasonDialog.mode === 'withdraw' ? '确认撤回' : '暂存原因' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
h2 { margin: 0 0 4px; font-size: 18px; }
h3 { margin: 0 0 10px; font-size: 15px; color: var(--primary-deep); }
.sub { font-size: 12px; color: var(--muted); }
.role-line { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.role-line .el-alert { flex: 1; }
.stat-row { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.latest { margin-bottom: 12px; }
.stat { background: #fff; border: 1px solid var(--line); border-radius: 10px; padding: 10px 16px; display: flex; flex-direction: column; align-items: center; min-width: 110px; }
.stat b { font-size: 22px; color: var(--primary-deep); }
.stat span { font-size: 12px; color: var(--muted); }
.block { margin-top: 6px; font-size: 12px; color: var(--muted); line-height: 1.5; }
.events { padding: 10px 20px; background: #fdfaf7; }
.reason-text { color: #c45656; font-size: 12px; }
.muted { color: var(--muted); font-size: 12px; }
.bottom-grid { display: grid; grid-template-columns: 1.4fr 1fr; gap: 14px; margin-top: 16px; }
.panel { background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 12px; }
.ticket-table { margin-top: 10px; }
.dialog-alert { margin-bottom: 10px; }
</style>
