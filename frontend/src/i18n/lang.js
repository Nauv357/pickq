/* ============================================================
   语言状态管理：切换语言 / 持久化 / Element Plus 组件语言联动
   ============================================================ */
import { computed, ref } from 'vue'
import i18n, { resolveLocale } from './index'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'

const LANG_KEY = 'tiku:lang'

/** 当前生效语言（ref，组件可 watch） */
export const currentLang = ref(resolveLocale())

/** Element Plus 组件语言（供 <el-config-provider :locale> 使用） */
export const elLocale = computed(() => (currentLang.value === 'en-US' ? en : zhCn))

/** 切换语言：'zh-CN' | 'en-US' | 'system'（跟随系统），持久化并即时生效 */
export function setLang(lang) {
  const pref = lang === 'en-US' ? 'en-US' : lang === 'system' ? 'system' : 'zh-CN'
  try {
    localStorage.setItem(LANG_KEY, pref)
  } catch {
    /* 忽略 */
  }
  applyLang(pref === 'system' ? resolveLocale() : pref)
}

/** 应用启动时初始化（main.js 或根组件调用一次） */
export function initLang() {
  applyLang(resolveLocale())
}

function applyLang(locale) {
  i18n.global.locale.value = locale
  currentLang.value = locale
  try {
    document.documentElement.lang = locale
  } catch {
    /* 忽略 */
  }
}

/** 读取用户偏好（含 'system'），供设置页回显 */
export function getLangPref() {
  try {
    return localStorage.getItem(LANG_KEY) || 'system'
  } catch {
    return 'system'
  }
}
