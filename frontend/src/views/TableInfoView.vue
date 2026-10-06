<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const emit = defineEmits(['changed'])
const rows = ref([])
const dialog = ref(false)
const editing = ref(false)
const form = reactive({ id: null, tableNo: '', seats: 2, status: '空闲' })

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
    if (editing.value) {
      await api.updateTableInfo(form.id, { ...form, seats: Number(form.seats) })
    } else {
      await api.createTableInfo({ ...form, seats: Number(form.seats) })
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
onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>桌台</h2>
      <el-button type="primary" @click="openCreate">新增桌台</el-button>
    </div>

    <el-table :data="rows" border stripe>
      <el-table-column prop="tableNo" label="桌号" width="100" align="center" />
      <el-table-column prop="seats" label="座位数" width="100" align="center" />
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" min-width="240">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.status==='空闲'" size="small" type="primary" @click="action(row, api.openTable, '已开台')">开台</el-button>
          <el-button v-if="row.status==='占用'" size="small" @click="action(row, api.cleanTable, '开始清洁')">收台清洁</el-button>
          <el-button v-if="row.status==='清洁中'" size="small" type="success" @click="action(row, api.cleanTable, '清洁完成')">清洁完成</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="editing ? '编辑桌台' : '新增桌台'" width="380px">
      <el-form label-width="80px">
        <el-form-item label="桌号"><el-input v-model="form.tableNo" placeholder="如 A1" /></el-form-item>
        <el-form-item label="座位数"><el-input v-model="form.seats" type="number" placeholder="大于 0" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width:100%">
            <el-option label="空闲" value="空闲" />
            <el-option label="占用" value="占用" />
            <el-option label="清洁中" value="清洁中" />
          </el-select>
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
.page-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
h2 { margin: 0; font-size: 18px; }
</style>
