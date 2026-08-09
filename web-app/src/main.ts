 import { createApp } from 'vue'
 import App from './App.vue'
 import router from './router'
 import { pinia } from '@/stores'
 import './style.css'

 // 创建 Vue 应用实例
 const app = createApp(App)

 // 安装 Pinia 状态管理
 app.use(pinia)

 // 安装 Vue Router
 app.use(router)

 // 挂载到 #app
 app.mount('#app')
