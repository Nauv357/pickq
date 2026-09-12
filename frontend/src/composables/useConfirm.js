import { ElMessageBox } from 'element-plus'

/**
 * 统一的确认对话框（docs/design-ui.md 规则 R3：破坏性操作必须二次确认）。
 *
 * 为什么要有这个 composable：
 * 1. 现状是 19 处手写 ElMessageBox.confirm，按钮文案大量硬编码中文
 *    （英文界面上"确认"按钮仍写着"删除/取消"），危险操作是否红色各行其是；
 * 2. 确认按钮文案必须等于动作本身（"删除"而不是"确定"），否则用户是在盲确认；
 * 3. 点遮罩关闭不算确认（避免误触执行破坏性操作）。
 *
 * 文案取自全局词典 common.*（见 src/i18n/index.js），页面无需自带这些键。
 * 第一个参数传页面的 t（各页面自带局部词典，这里不重复创建 i18n 作用域）。
 *
 * 用法：
 *   const { confirm, confirmDanger } = useConfirm(t)
 *   if (await confirmDanger(t('delAsk'), t('delTitle'))) doDelete()
 *
 * @param {(key: string, named?: object) => string} t 页面的翻译函数
 */
export function useConfirm(t) {
  if (typeof t !== 'function') {
    throw new Error('useConfirm(t) 需要页面的 t 函数（见 src/composables/useConfirm.js 注释）')
  }

  /**
   * @param {string} message 正文：说清后果（能不能恢复）
   * @param {string} title 标题：动作名
   * @param {object} [opts] { confirmText, cancelText, danger }
   * @returns {Promise<boolean>} true = 用户确认执行
   */
  function ask(message, title, opts = {}) {
    const confirmText = opts.confirmText || title || t('common.ok')
    const cancelText = opts.cancelText || t('common.cancel')
    return ElMessageBox.confirm(message, title, {
      type: opts.danger ? 'warning' : 'info',
      confirmButtonText: confirmText,
      cancelButtonText: cancelText,
      confirmButtonClass: opts.danger ? 'el-button--danger' : undefined,
      closeOnClickModal: false
    }).then(
      () => true,
      () => false // 取消 / 点关闭 / 点遮罩都算"不执行"
    )
  }

  /** 危险操作（删除 / 覆盖 / 重置）：标题即确认按钮文案，红色确认按钮 */
  function confirmDanger(message, title, opts = {}) {
    return ask(message, title, { ...opts, danger: true })
  }

  /** 普通确认：默认「确定 / 取消」 */
  function confirm(message, title, opts = {}) {
    return ask(message, title, opts)
  }

  return { confirm, confirmDanger }
}
