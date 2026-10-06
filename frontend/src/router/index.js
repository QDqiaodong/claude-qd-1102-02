import { createRouter, createWebHashHistory } from 'vue-router'

// 注意：main.js 里没有 app.use(router)，路由实例由 App.vue 直接驱动，
// 所以这里全部用静态 import —— <component :is> 拿到的是组件对象本身。
import BeanBatchView from '../views/BeanBatchView.vue'
import MenuItemView from '../views/MenuItemView.vue'
import TableInfoView from '../views/TableInfoView.vue'
import OrderTicketView from '../views/OrderTicketView.vue'

const routes = [
  { path: '/', redirect: '/bean-batch' },
  { path: '/bean-batch', name: 'bean-batch', component: BeanBatchView },
  { path: '/menu-item', name: 'menu-item', component: MenuItemView },
  { path: '/table-info', name: 'table-info', component: TableInfoView },
  { path: '/order-ticket', name: 'order-ticket', component: OrderTicketView },
  { path: '/:pathMatch(.*)*', redirect: '/bean-batch' }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
