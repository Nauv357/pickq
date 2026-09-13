import { ElLoading } from 'element-plus'

/**
 * 长任务提示（导入题库 / 从广场下载这类"可能几十秒没有任何响应"的操作）。
 *
 * 背景（用户实测）：导入一个含图题库后界面毫无反应，刷新页面才发现其实已经导入成功。
 * 根因是两件事叠在一起——
 *  1. 导入请求走全局 axios（timeout 15s），超过 15s 客户端就中止，而后端仍在继续跑；
 *  2. 界面上没有任何"正在导入"的状态（按钮既不变灰也不转圈），用户只能看到"点了没反应"。
 * 这里提供全屏遮罩 + 已用秒数，让"在后端确实干活"这件事对用户可见；调用方还需把对应
 * 请求的 timeout 设为 0（见 `api/banks.js`、`DiscoverView`）。
 */
let instance = null
let timer = null
let startedAt = 0

/** 开始提示：3 秒后开始显示已用秒数（短任务不闪字） */
export function startBusy(text) {
  stopBusy()
  instance = ElLoading.service({ lock: true, text, background: 'rgba(0, 0, 0, 0.35)' })
  startedAt = Date.now()
  timer = setInterval(() => {
    const seconds = Math.round((Date.now() - startedAt) / 1000)
    if (seconds >= 3 && instance) {
      instance.setText(`${text}（已用 ${seconds} 秒，请不要关闭窗口）`)
    }
  }, 1000)
  return instance
}

/** 结束提示（必须放在 finally 里，任何分支都不能漏） */
export function stopBusy() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  if (instance) {
    instance.close()
    instance = null
  }
}
