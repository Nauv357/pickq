import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '../layouts/AppLayout.vue'

const routes = [
  {
    path: '/',
    component: AppLayout,
    children: [
      {
        path: '',
        name: 'bank-list',
        component: () => import('../views/BankListView.vue'),
        meta: { title: '题库' }
      },
      {
        path: 'banks/:id',
        name: 'bank-detail',
        component: () => import('../views/BankDetailView.vue'),
        meta: { title: '题库详情' }
      },
      {
        path: 'banks/:id/practice',
        name: 'practice',
        component: () => import('../views/PracticeView.vue'),
        meta: { title: '做题' }
      },
      {
        path: 'banks/:id/sessions',
        name: 'session-history',
        component: () => import('../views/SessionHistoryView.vue'),
        meta: { title: '练习历史' }
      },
      {
        path: 'ai-import/jobs',
        name: 'ai-import-jobs',
        component: () => import('../views/AiImportJobsView.vue'),
        meta: { title: 'AI 导入记录' }
      },
      {
        path: 'ai-import/:jobId',
        name: 'ai-import-preview',
        component: () => import('../views/AiImportPreviewView.vue'),
        meta: { title: 'AI 导入预览' }
      },
      {
        path: 'stats',
        name: 'stats',
        component: () => import('../views/StatsView.vue'),
        meta: { title: '学习统计' }
      },
      {
        path: 'discover',
        name: 'discover',
        component: () => import('../views/DiscoverView.vue'),
        meta: { title: '发现题库' }
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('../views/SettingsView.vue'),
        meta: { title: '设置' }
      },
      { path: ':pathMatch(.*)*', redirect: { name: 'bank-list' } }
    ]
  },
  {
    // 打印试卷页：独立页面（无侧栏），固定布局 + 浏览器打印/另存 PDF
    path: '/banks/:id/print',
    name: 'print-paper',
    component: () => import('../views/PrintPaperView.vue'),
    meta: { title: '打印试卷' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.afterEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} · 拾题` : '拾题 · 自建题库'
})

export default router
