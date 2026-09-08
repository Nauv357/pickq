import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import 'katex/dist/katex.min.css'
// 标题衬线字体（与官网同源 Noto Serif SC；600 = 页面大标题字重）
import '@fontsource/noto-serif-sc/chinese-simplified-600.css'

import App from './App.vue'
import router from './router'
import { initTheme } from './utils/theme'
import './styles/main.css'

// 双主题初始化(存储偏好 > 系统偏好);dark class 由 utils/theme.js 控制,
// Element Plus 暗色变量随 html.dark 自动生效(设计系统见 styles/main.css)
initTheme()

const app = createApp(App)
app.use(ElementPlus, { locale: zhCn })
app.use(router)
app.mount('#app')
