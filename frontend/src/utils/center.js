/* ============================================================
   题库广场（内容包中心）地址
   官方固定地址 = https://pickq.cn（不提供用户自定义；
   早期 localStorage 覆盖值一律忽略，防止残留旧地址生效）
   ============================================================ */

const CENTER_URL = 'https://pickq.cn'

/** 读取广场地址（始终返回官方地址） */
export function getCenterUrl() {
  return CENTER_URL
}

/** 兼容旧调用：不再支持自定义，返回官方地址 */
export function setCenterUrl() {
  return CENTER_URL
}

/** 广场根地址 + 作品路径拼装（保证单斜杠） */
export function centerPacksUrl() {
  return `${CENTER_URL}/packs`
}
