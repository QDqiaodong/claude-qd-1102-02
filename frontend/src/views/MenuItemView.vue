<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const emit = defineEmits(['changed'])
const rows = ref([])
const beanBatches = ref([])        // 全部批次（供选豆种 & 展示库存）
const dialog = ref(false)
const editing = ref(false)
const form = reactive({ id: null, name: '', price: '', category: '意式', status: '在售' })
const CATEGORIES = ['意式', '手冲', '非咖啡']

// 配方弹窗
const recipeDialog = ref(false)
const recipeMenu = ref(null)
const recipeLines = ref([])
const stockCheck = ref(null)
const savingRecipe = ref(false)

const statusType = (s) => (s === '在售' ? 'success' : 'info')

async function load() {
  const [menus, batches] = await Promise.all([api.listMenuItems(), api.listBeanBatches()])
  rows.value = menus
  beanBatches.value = batches
}

// 豆种 -> 已入库批次（按入库时间 FIFO 排序，与后端一致）
const inStockBatches = computed(() =>
  beanBatches.value
    .filter((b) => b.status === '已入库' && b.remainingG > 0)
    .sort((a, b) => new Date(a.storedAt || 0) - new Date(b.storedAt || 0) || a.id - b.id)
)
const beanOrigins = computed(() => [...new Set(beanBatches.value.map((b) => b.origin))])
function stockOf(origin) {
  return inStockBatches.value.filter((b) => b.origin === origin).reduce((s, b) => s + b.remainingG, 0)
}

function openCreate() {
  editing.value = false
  Object.assign(form, { id: null, name: '', price: '', category: '意式', status: '在售' })
  dialog.value = true
}
function openEdit(r) {
  editing.value = true
  Object.assign(form, { id: r.id, name: r.name, price: r.price, category: r.category, status: r.status })
  dialog.value = true
}
async function submit() {
  try {
    if (editing.value) {
      await api.updateMenuItem(form.id, { ...form, price: Number(form.price) })
    } else {
      await api.createMenuItem({ ...form, price: Number(form.price) })
    }
    dialog.value = false
    await load()
    emit('changed')
    ElMessage.success('已保存')
  } catch (e) { ElMessage.error(e.message) }
}

async function openRecipe(r) {
  recipeMenu.value = r
  recipeLines.value = (await api.getRecipe(r.id)).map((x) => ({
    beanOrigin: x.beanOrigin, grams: x.grams
  }))
  await refreshStockCheck()
  recipeDialog.value = true
}
function addRecipeLine() {
  recipeLines.value.push({ beanOrigin: beanOrigins.value[0] || '', grams: 15 })
}
function removeRecipeLine(i) { recipeLines.value.splice(i, 1) }

async function refreshStockCheck() {
  if (!recipeMenu.value) return
  stockCheck.value = await api.menuStock(recipeMenu.value.id)
}
async function submitRecipe() {
  if (savingRecipe.value) return
  savingRecipe.value = true
  try {
    await api.saveRecipe(recipeMenu.value.id, recipeLines.value.map((l) => ({
      beanOrigin: l.beanOrigin, grams: Number(l.grams)
    })))
    await refreshStockCheck()
    await load()
    emit('changed')
    ElMessage.success('配方已保存（不影响已完成的历史账单）')
  } catch (e) { ElMessage.error(e.message) } finally { savingRecipe.value = false }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>饮品菜单 · 配方维护</h2>
      <el-button type="primary" @click="openCreate">新增饮品</el-button>
    </div>

    <el-alert type="info" :closable="false" class="rule-tip"
      title="配方决定「每杯用哪个豆种、多少克」；完成制作时系统只从已入库且有余量的批次中按入库先后自动分配，可跨批次，但必须整单满足。改配方不会重算已完成账单。" />

    <el-table :data="rows" border stripe>
      <el-table-column prop="name" label="饮品名" min-width="140" />
      <el-table-column prop="category" label="类别" width="90" align="center" />
      <el-table-column prop="price" label="价格(元)" width="100" align="right">
        <template #default="{ row }">{{ Number(row.price).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" width="200" align="center">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="warning" plain @click="openRecipe(row)">维护配方</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增 / 编辑饮品 -->
    <el-dialog v-model="dialog" :title="editing ? '编辑饮品' : '新增饮品'" width="420px">
      <el-form label-width="80px">
        <el-form-item label="饮品名"><el-input v-model="form.name" placeholder="如 拿铁" /></el-form-item>
        <el-form-item label="类别">
          <el-select v-model="form.category" style="width:100%">
            <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="价格(元)"><el-input v-model="form.price" type="number" placeholder="大于 0" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width:100%">
            <el-option label="在售" value="在售" />
            <el-option label="下架" value="下架" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 配方维护 -->
    <el-dialog v-model="recipeDialog" :title="`配方维护 · ${recipeMenu?.name || ''}`" width="640px">
      <div v-if="recipeMenu" class="recipe-head">
        <el-tag size="small">{{ recipeMenu.category }}</el-tag>
        <span v-if="recipeMenu.category !== '非咖啡'" class="recipe-hint">咖啡类饮品必须维护豆种配方，否则收银员无法完成制作</span>
        <span v-else class="recipe-hint">非咖啡类饮品不消耗咖啡豆，配方可留空</span>
      </div>

      <el-table :data="recipeLines" border size="small" class="recipe-table">
        <el-table-column label="豆种（匹配批次产地）" min-width="240">
          <template #default="{ row }">
            <el-select v-model="row.beanOrigin" filterable style="width:100%">
              <el-option
                v-for="o in beanOrigins" :key="o" :label="o" :value="o"
                :disabled="stockOf(o) <= 0"
              >
                <span>{{ o }}</span>
                <span style="float:right;color:var(--muted);font-size:12px">
                  已入库余量 {{ stockOf(o) }} 克{{ stockOf(o) <= 0 ? '（暂无可用批次）' : '' }}
                </span>
              </el-option>
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="每杯(克)" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.grams" :min="1" :max="200" :step="1" controls-position="right" style="width:120px" />
          </template>
        </el-table-column>
        <el-table-column width="70" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" @click="removeRecipeLine($index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button size="small" plain class="add-line" @click="addRecipeLine">+ 添加豆种（拼配）</el-button>

      <!-- 保存后即时核对：按当前库存一杯够不够 -->
      <div v-if="stockCheck" class="stock-check">
        <template v-if="!stockCheck.coffee">
          <el-alert type="success" :closable="false" title="非咖啡饮品，不占用咖啡豆库存" />
        </template>
        <template v-else-if="!stockCheck.hasRecipe">
          <el-alert type="warning" :closable="false" title="咖啡类饮品尚无配方，请至少添加一条豆种" />
        </template>
        <template v-else>
          <el-alert
            :type="stockCheck.enough ? 'success' : 'error'" :closable="false"
            :title="stockCheck.enough ? '按当前库存，单杯可制作' : stockCheck.reason"
          />
          <div v-if="stockCheck.needs.length" class="need-lines">
            <div v-for="n in stockCheck.needs" :key="n.beanOrigin" class="need-line">
              <span>{{ n.beanOrigin }}</span>
              <span>每杯 {{ n.requiredGrams }} 克 / 已入库余量 {{ n.availableGrams }} 克</span>
              <el-tag size="small" :type="n.enough ? 'success' : 'danger'">{{ n.enough ? '够' : '不足' }}</el-tag>
            </div>
          </div>
        </template>
      </div>

      <template #footer>
        <el-button @click="recipeDialog=false">关闭</el-button>
        <el-button type="primary" :loading="savingRecipe" @click="submitRecipe">保存配方</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
h2 { margin: 0; font-size: 18px; }
.rule-tip { margin-bottom: 12px; }
.recipe-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.recipe-hint { font-size: 13px; color: var(--muted); }
.add-line { margin-top: 10px; width: 100%; }
.stock-check { margin-top: 14px; }
.need-lines { margin-top: 8px; display: flex; flex-direction: column; gap: 6px; }
.need-line { display: flex; align-items: center; justify-content: space-between; font-size: 13px; padding: 4px 8px; background: #faf6f2; border-radius: 6px; }
</style>
