/* ============================================================
   应用国际化：vue-i18n（legacy: false）
   - 语言：zh-CN（默认）/ en-US；存 localStorage 'tiku:lang'
   - 取值优先级：显式选择 > 跟随系统（navigator.language）
   - 文案字典按组件增量迁移：组件内 useI18n({ messages }) 提供局部字典，
     未迁移部分保持中文（zh 为 fallback，保证切换不出现空 key）
   - 例外：`common.*` 是全局共用词表（确定/取消/删除/更多…），供公共组件与
     useConfirm() 使用。这些词不属于任何单个页面，放全局才能保证
     "同一个动作在所有页面同一个说法"（见 docs/design-ui.md 规则 R6）。
   ============================================================ */
import { createI18n } from 'vue-i18n'

export const SUPPORTED_LANGS = ['zh-CN', 'en-US']

/** 解析应生效的语言（考虑 localStorage 显式值或系统语言） */
export function resolveLocale() {
  try {
    const saved = localStorage.getItem('tiku:lang')
    if (saved === 'zh-CN' || saved === 'en-US') return saved
    // 'system' 或未设置：跟随系统语言（zh 前缀 → 中文，否则英文）
    const nav = String(navigator.language || 'zh-CN').toLowerCase()
    return nav.startsWith('zh') ? 'zh-CN' : 'en-US'
  } catch {
    return 'zh-CN'
  }
}

const i18n = createI18n({
  legacy: false,
  globalInjection: true, // 模板可直接使用 $t
  locale: resolveLocale(),
  fallbackLocale: 'zh-CN', // 未翻译 key 回落中文，不显示裸 key
  messages: {
    'zh-CN': {
      common: {
        ok: '确定',
        cancel: '取消',
        close: '关闭',
        save: '保存',
        delete: '删除',
        edit: '编辑',
        more: '更多',
        moreOps: '更多操作',
        back: '返回',
        reset: '重置',
        retry: '重试',
        search: '搜索',
        loading: '加载中…',
        thinkAgain: '再想想',
        opFailed: '操作失败，请稍后重试'
      }
    },
    'en-US': {
      common: {
        ok: 'OK',
        cancel: 'Cancel',
        close: 'Close',
        save: 'Save',
        delete: 'Delete',
        edit: 'Edit',
        more: 'More',
        moreOps: 'More actions',
        back: 'Back',
        reset: 'Reset',
        retry: 'Retry',
        search: 'Search',
        loading: 'Loading…',
        thinkAgain: 'Keep it',
        opFailed: 'Operation failed, please try again later'
      }
    }
  }
})

export default i18n
