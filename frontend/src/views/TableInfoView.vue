<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const emit = defineEmits(['changed'])
const rows = ref([])
const dialog = ref(false)
const editing = ref(false)
const form = reactive({ id: null, tableNo: '', seats: 2, status: '空闲' })
const openingId = ref(null)

const statusType = (s) => ({ '空闲': 'success', '占用': 'warning', '清洁中': 'info' }[s] || 'info')

async function load() {
  rows.value = await api.listTableInfos()
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
    const payload = { tableNo: form.tableNo, seats: Number(form.seats) }
    if (editing.value) {
      await api.updateTableInfo(form.id, payload)
    } else {
      await api.createTableInfo(payload)
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
    await api.openTable(r.id, r.status)
    await load()
    emit('changed')
    ElMessage.success('已开台')
  } catch (e) {
    await load()
    ElMessage.error(e.message)
  } finally {
    openingId.value = null
  }
}
function blockedReason(r) {
  if (r.status === '占用') return '已有账单占用，不能重复开台'
  if (r.activeCleaning?.status === '待接单') return '已结账等待清洁人员接单，不能绕过清洁'
  if (r.activeCleaning?.status === '清洁中') {
    return `清洁人员 ${r.activeCleaning.cleanerName || '—'} 处理中，预计 ${fmt(r.activeCleaning.expectedFinishAt)} 完成`
  }
  if (r.status === '清洁中') return '清洁尚未完成，不能开台'
  return ''
}
function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '—' }
onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>桌台</h2>
        <div class="sub">桌台状态只能按「占用（结账）→ 清洁中 → 空闲 → 开台占用」推进</div>
      </div>
      <el-button type="primary" @click="openCreate">新增桌台</el-button>
    </div>

    <el-table :data="rows" border stripe>
      <el-table-column prop="tableNo" label="桌号" width="90" align="center" />
      <el-table-column prop="seats" label="座位数" width="90" align="center" />
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="清洁责任人 / 时间" min-width="240">
        <template #default="{ row }">
          <template v-if="row.activeCleaning">
            <div>{{ row.activeCleaning.status }} · {{ row.activeCleaning.cleanerName || '等待接单' }}</div>
            <div class="muted">释放 {{ fmt(row.activeCleaning.releasedAt) }}</div>
            <div class="muted">开始 {{ fmt(row.activeCleaning.acceptedAt) }} · 预计 {{ fmt(row.activeCleaning.expectedFinishAt) }}</div>
            <el-alert v-if="row.activeCleaning.unfinishedReason" type="error" :closable="false"
              class="reason" :title="row.activeCleaning.unfinishedReason" />
          </template>
          <span v-else class="muted">{{ row.status === '占用' ? '账单进行中' : '无活动清洁单' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="收银员可执行动作 / 拦截原因" min-width="290">
        <template #default="{ row }">
          <el-button v-if="row.status === '空闲' && !row.activeCleaning" size="small" type="primary"
            :loading="openingId === row.id" @click="openTable(row)">开台</el-button>
          <el-button v-if="row.status === '空闲' && !row.activeCleaning" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" @click="openEdit(row)" v-if="row.status !== '空闲' || row.activeCleaning">编辑</el-button>
          <div class="block">
            {{ row.status === '空闲' && !row.activeCleaning ? '可开台；状态不能手工修改' : blockedReason(row) }}
          </div>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="editing ? '编辑桌台资料' : '新增桌台'" width="380px">
      <el-form label-width="80px">
        <el-form-item label="桌号"><el-input v-model="form.tableNo" placeholder="如 A1" /></el-form-item>
        <el-form-item label="座位数"><el-input v-model="form.seats" type="number" placeholder="大于 0" /></el-form-item>
        <el-alert type="info" :closable="false" show-icon
          title="状态由结账和清洁流程自动维护，不能在此手工设置。" />
      </el-form>
      <template #footer>
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
h2 { margin: 0; font-size: 18px; }
.sub { font-size: 12px; color: var(--muted); margin-top: 4px; }
.muted { color: var(--muted); font-size: 12px; }
.block { margin-top: 6px; color: var(--muted); font-size: 12px; line-height: 1.5; }
.reason { margin-top: 6px; }
</style>
