<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const emit = defineEmits(['changed'])
const rows = ref([])
const cleaningMap = ref({}) // tableId -> 当前清洁单
const openingId = ref(null)
const dialog = ref(false)
const editing = ref(false)
const form = reactive({ id: null, tableNo: '', seats: 2, status: '空闲' })

const statusType = (s) => ({ '空闲': 'success', '占用': 'warning', '清洁中': 'info' }[s] || 'info')
const fmt = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '')

async function load() {
  const [tables, queue] = await Promise.all([api.listTableInfos(), api.cleaningQueue()])
  rows.value = tables
  cleaningMap.value = Object.fromEntries(queue.map((q) => [q.tableId, q]))
}
function openCreate() {
  editing.value = false
  Object.assign(form, { id: null, tableNo: '', seats: 2, status: '空闲' })
  dialog.value = true
}
function openEdit(r) {
  editing.value = true
  Object.assign(form, { id: r.id, tableNo: r.tableNo, seats: r.seats, status: r.status })
  dialog.value = true
}
async function submit() {
  try {
    if (editing.value) {
      // 状态字段不下发：桌台状态只能走开台/结账/完成清洁流程，避免手工绕过清洁
      await api.updateTableInfo(form.id, { tableNo: form.tableNo, seats: Number(form.seats) })
    } else {
      await api.createTableInfo({ tableNo: form.tableNo, seats: Number(form.seats) })
    }
    dialog.value = false
    await load()
    emit('changed')
    ElMessage.success('已保存')
  } catch (e) { ElMessage.error(e.message) }
}
async function openTable(r) {
  if (openingId.value) return
  openingId.value = r.id
  try {
    await api.openTable(r.id)
    await load()
    emit('changed')
    ElMessage.success('已开台')
  } catch (e) {
    // 清洁中/重复开台等拦截原因直接展示，并刷新到最新状态
    await load()
    ElMessage({ type: 'error', message: e.message, duration: 5000, showClose: true })
  } finally {
    openingId.value = null
  }
}
onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>桌台</h2>
      <el-button type="primary" @click="openCreate">新增桌台</el-button>
    </div>

    <el-alert type="info" :closable="false" show-icon class="rule-tip"
      title="桌台状态只能走业务流程：空闲→占用由收银员「开台」；占用→清洁中由「结账」自动触发；清洁中→空闲必须由清洁人员在「清洁交接」页完成清洁。本页不能手工改状态、不能绕过清洁直接开台。" />

    <el-table :data="rows" border stripe>
      <el-table-column prop="tableNo" label="桌号" width="100" align="center" />
      <el-table-column prop="seats" label="座位数" width="90" align="center" />
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="当前清洁交接信息" min-width="320">
        <template #default="{ row }">
          <template v-if="cleaningMap[row.id]">
            <div class="clean-line">
              清洁单 <el-tag size="small" :type="cleaningMap[row.id].status === '待接单' ? 'info' : 'warning'">
                {{ cleaningMap[row.id].status }}
              </el-tag>
              <span v-if="cleaningMap[row.id].cleaner">
                责任人 <b>{{ cleaningMap[row.id].cleaner }}</b> · 预计 {{ fmt(cleaningMap[row.id].etaAt) }} 完成
              </span>
              <span v-else class="muted">等待清洁人员接单（{{ fmt(cleaningMap[row.id].queuedAt) }} 入队）</span>
            </div>
            <div v-if="cleaningMap[row.id].pendingReason" class="clean-reason">
              未完成原因：{{ cleaningMap[row.id].pendingReason }}
            </div>
            <div v-if="cleaningMap[row.id].blockedByNewTicket" class="clean-block">
              已有新账单 #{{ cleaningMap[row.id].activeTicketId }}，旧清洁单已被冻结拦截
            </div>
          </template>
          <span v-else-if="row.status === '占用'" class="muted">占用中，结账后自动进入清洁队列</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" align="center">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.status==='空闲'" size="small" type="primary"
            :loading="openingId === row.id" :disabled="openingId !== null"
            @click="openTable(row)">开台</el-button>
          <el-tooltip v-else-if="row.status==='清洁中'" content="清洁未完成，不能开台；请到「清洁交接」查看责任人与进度" placement="top">
            <el-button size="small" type="primary" disabled>开台</el-button>
          </el-tooltip>
          <el-button v-else size="small" type="primary" disabled>开台</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="editing ? '编辑桌台' : '新增桌台'" width="380px">
      <el-form label-width="80px">
        <el-form-item label="桌号"><el-input v-model="form.tableNo" placeholder="如 A1" /></el-form-item>
        <el-form-item label="座位数"><el-input v-model="form.seats" type="number" placeholder="大于 0" /></el-form-item>
        <el-form-item v-if="editing" label="当前状态">
          <el-tag :type="statusType(form.status)">{{ form.status }}</el-tag>
          <span class="muted status-note">状态由开台/结账/清洁流程驱动，不可手工修改</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
h2 { margin: 0; font-size: 18px; }
.rule-tip { margin-bottom: 12px; }
.muted { color: var(--muted); font-size: 12px; }
.clean-line { font-size: 13px; display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.clean-reason { font-size: 12px; color: #b88230; margin-top: 2px; }
.clean-block { font-size: 12px; color: #f56c6c; margin-top: 2px; }
.status-note { margin-left: 10px; }
</style>
