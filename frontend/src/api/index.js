import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.message || error?.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

async function call(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body.data
}

const params = (o) => ({ params: o })

export default {
  // ---------------- 咖啡豆批次 ----------------
  listBeanBatches: (status) => call(http.get('/bean-batches', params(status ? { status } : {}))),
  createBeanBatch: (d) => call(http.post('/bean-batches', d)),
  updateBeanBatch: (id, d) => call(http.put(`/bean-batches/${id}`, d)),
  roastBeanBatch: (id) => call(http.post(`/bean-batches/${id}/roast`)),
  finishRoastBeanBatch: (id) => call(http.post(`/bean-batches/${id}/finish-roast`)),
  depleteBeanBatch: (id) => call(http.post(`/bean-batches/${id}/deplete`)),

  // ---------------- 饮品菜单 + 配方 ----------------
  listMenuItems: (status) => call(http.get('/menu-items', params(status ? { status } : {}))),
  createMenuItem: (d) => call(http.post('/menu-items', d)),
  updateMenuItem: (id, d) => call(http.put(`/menu-items/${id}`, d)),
  getRecipe: (id) => call(http.get(`/menu-items/${id}/recipe`)),
  saveRecipe: (id, lines) => call(http.put(`/menu-items/${id}/recipe`, lines)),

  // ---------------- 桌台 ----------------
  listTableInfos: (status) => call(http.get('/table-infos', params(status ? { status } : {}))),
  createTableInfo: (d) => call(http.post('/table-infos', d)),
  updateTableInfo: (id, d) => call(http.put(`/table-infos/${id}`, d)),
  openTable: (id) => call(http.post(`/table-infos/${id}/open`)),

  // ---------------- 清洁交接 ----------------
  cleaningQueue: () => call(http.get('/cleanings/queue')),
  cleaningDashboard: () => call(http.get('/cleanings/dashboard')),
  listCleanings: () => call(http.get('/cleanings')),
  latestCleaning: (tableId) => call(http.get(`/cleanings/table/${tableId}/latest`)),
  cleaningLogs: (id) => call(http.get(`/cleanings/${id}/logs`)),
  tableCleaningLogs: (tableId) => call(http.get(`/cleanings/table/${tableId}/logs`)),
  claimCleaning: (id, cleaner, etaAt) => call(http.post(`/cleanings/${id}/claim`, { cleaner, etaAt })),
  saveCleaningReason: (id, cleaner, reason) => call(http.post(`/cleanings/${id}/save-reason`, { cleaner, reason })),
  completeCleaning: (id, cleaner) => call(http.post(`/cleanings/${id}/complete`, { cleaner })),
  withdrawCleaning: (id, cleaner) => call(http.post(`/cleanings/${id}/withdraw`, { cleaner })),

  // ---------------- 点单出单 ----------------
  listOrderTickets: (status) => call(http.get('/order-tickets', params(status ? { status } : {}))),
  createOrderTicket: (d) => call(http.post('/order-tickets', d)),
  getOrderTicket: (id) => call(http.get(`/order-tickets/${id}`)),
  addOrderItem: (id, d) => call(http.post(`/order-tickets/${id}/add-item`, d)),
  finishPreview: (id) => call(http.get(`/order-tickets/${id}/finish-preview`)),
  finishOrderTicket: (id, operator) => call(http.post(`/order-tickets/${id}/finish`, { operator })),
  settleOrderTicket: (id, operator) => call(http.post(`/order-tickets/${id}/settle`, { operator })),

  // ---------------- 库存视图 / 批次追溯 ----------------
  onSaleStock: () => call(http.get('/stock/on-sale')),
  menuStock: (id) => call(http.get(`/stock/menu-items/${id}`)),
  batchTrace: (id) => call(http.get(`/stock/bean-batches/${id}/trace`))
}
