<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const emit = defineEmits(['changed'])
const rows = ref([])
const dialog = ref(false)
const editing = ref(false)
const form = reactive({ id: null, batchNo: '', origin: '', roastLevel: '中', weightKg: '' })
const ROAST_LEVELS = ['浅', '中', '深']

// 消耗追溯
const traceDialog = ref(false)
const trace = ref(null)

const statusType = (s) => ({ '待烘焙': 'info', '烘焙中': 'warning', '已入库': 'success', '已用完': 'default' }[s] || 'info')

async function load() {
  rows.value = await api.listBeanBatches()
}
function openCreate() {
  editing.value = false
  Object.assign(form, { id: null, batchNo: '', origin: '', roastLevel: '中', weightKg: '' })
  dialog.value = true
}
function openEdit(r) {
  editing.value = true
  Object.assign(form, { id: r.id, batchNo: r.batchNo, origin: r.origin, roastLevel: r.roastLevel, weightKg: r.weightKg })
  dialog.value = true
}
async function submit() {
  try {
    if (editing.value) {
      await api.updateBeanBatch(form.id, { ...form })
    } else {
      await api.createBeanBatch({ ...form, weightKg: Number(form.weightKg) })
    }
    dialog.value = false
    await load()
    emit('changed')
    ElMessage.success('已保存')
  } catch (e) { ElMessage.error(e.message) }
}
async function action(r, fn, label) {
  try { await fn(r.id); await load(); emit('changed'); ElMessage.success(label) }
  catch (e) { ElMessage.error(e.message) }
}
async function openTrace(r) {
  trace.value = await api.batchTrace(r.id)
  traceDialog.value = true
}

function fmtTime(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 16)
}
function kg(g) { return (g / 1000).toFixed(3) }

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>咖啡豆批次 · 余量与消耗追溯</h2>
      <el-button type="primary" @click="openCreate">新增批次</el-button>
    </div>

    <el-alert type="info" :closable="false" class="rule-tip"
      title="批次标记入库后按重量生成余量，只有「已入库且有余量」的批次会被配方扣减；系统按入库时间从早到晚自动分配，可跨批次。入库后批次信息锁定不可编辑。" />

    <el-table :data="rows" border stripe>
      <el-table-column prop="batchNo" label="批次号" width="130" />
      <el-table-column prop="origin" label="豆种/产地" min-width="160" />
      <el-table-column prop="roastLevel" label="烘焙度" width="80" align="center" />
      <el-table-column label="入库重量" width="100" align="right">
        <template #default="{ row }">{{ Number(row.weightKg).toFixed(2) }} kg</template>
      </el-table-column>
      <el-table-column label="批次余量" width="130" align="right">
        <template #default="{ row }">
          <span :class="{ zero: row.remainingG === 0, low: row.remainingG > 0 && row.remainingG < 500 }">
            {{ row.remainingG }} 克
          </span>
          <span class="kg-sub">/ {{ kg(row.remainingG) }} kg</span>
        </template>
      </el-table-column>
      <el-table-column label="入库时间" width="150" align="center">
        <template #default="{ row }">{{ fmtTime(row.storedAt) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" min-width="330">
        <template #default="{ row }">
          <el-button size="small"
            :disabled="row.status==='已入库' || row.status==='已用完'"
            @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.status==='待烘焙'" size="small" type="warning" @click="action(row, api.roastBeanBatch, '已送入烘焙')">送入烘焙</el-button>
          <el-button v-if="row.status==='烘焙中'" size="small" type="success" @click="action(row, api.finishRoastBeanBatch, '已入库，余量已生成')">标记入库</el-button>
          <el-button v-if="row.status==='已入库'" size="small" @click="action(row, api.depleteBeanBatch, '已用完（余量清零）')">标记用完</el-button>
          <el-button size="small" type="primary" plain @click="openTrace(row)">消耗追溯</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="editing ? '编辑批次（仅未入库）' : '新增批次'" width="420px">
      <el-form label-width="80px">
        <el-form-item label="批次号"><el-input v-model="form.batchNo" placeholder="如 BB-20260901" /></el-form-item>
        <el-form-item label="产地"><el-input v-model="form.origin" placeholder="如 埃塞俄比亚 耶加雪菲（配方按此名称匹配）" /></el-form-item>
        <el-form-item label="烘焙度">
          <el-select v-model="form.roastLevel" style="width:100%">
            <el-option v-for="l in ROAST_LEVELS" :key="l" :label="l" :value="l" />
          </el-select>
        </el-form-item>
        <el-form-item label="重量(kg)"><el-input v-model="form.weightKg" type="number" placeholder="大于 0，标记入库时换算为克余量" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 批次消耗追溯 -->
    <el-dialog v-model="traceDialog"
      :title="trace ? `消耗追溯 · ${trace.batchNo}（${trace.origin}）` : '消耗追溯'"
      width="820px">
      <template v-if="trace">
        <div class="trace-summary">
          <el-tag :type="statusType(trace.status)">{{ trace.status }}</el-tag>
          <span>入库时间：<b>{{ fmtTime(trace.storedAt) }}</b></span>
          <span>入库重量：<b>{{ Number(trace.weightKg).toFixed(2) }} kg</b></span>
          <span>当前余量：<b :class="{ zero: trace.remainingGrams === 0 }">{{ trace.remainingGrams }} 克</b></span>
          <span>账单累计消耗：<b>{{ trace.consumedGrams }} 克</b>（{{ trace.consumptionCount }} 条流水）</span>
        </div>
        <el-table :data="trace.consumptions" border size="small" max-height="420">
          <el-table-column label="账单" width="90" align="center">
            <template #default="{ row }">#{{ row.ticketId }}<div class="table-no" v-if="row.tableNo">{{ row.tableNo }} 桌</div></template>
          </el-table-column>
          <el-table-column prop="menuName" label="饮品" min-width="130" />
          <el-table-column label="扣减(克)" width="90" align="right">
            <template #default="{ row }"><b>{{ row.grams }}</b></template>
          </el-table-column>
          <el-table-column prop="operator" label="操作者" width="100" align="center" />
          <el-table-column label="时间" width="150" align="center">
            <template #default="{ row }">{{ fmtTime(row.consumedAt) }}</template>
          </el-table-column>
          <el-table-column label="账单状态" width="90" align="center">
            <template #default="{ row }"><el-tag size="small" :type="row.ticketStatus === '已结账' ? 'info' : 'success'">{{ row.ticketStatus }}</el-tag></template>
          </el-table-column>
        </el-table>
        <div v-if="!trace.consumptions.length" class="trace-empty">
          该批次还没有被任何账单消耗。若已被「标记用完」（人工封存/报损），也不会产生账单流水。
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
h2 { margin: 0; font-size: 18px; }
.rule-tip { margin-bottom: 12px; }
.zero { color: #909399; font-weight: 700; }
.low { color: #e6a23c; font-weight: 700; }
.kg-sub { color: var(--muted); font-size: 12px; margin-left: 4px; }
.trace-summary { display: flex; flex-wrap: wrap; gap: 18px; align-items: center; margin-bottom: 12px; font-size: 13px; background: #faf6f2; padding: 10px 12px; border-radius: 8px; }
.table-no { font-size: 11px; color: var(--muted); }
.trace-empty { color: var(--muted); text-align: center; padding: 24px 0; }
</style>
