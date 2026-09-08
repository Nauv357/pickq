import http from './http'

// 一键完整备份：H2 一致性快照 + 题目图片 + AI 配置 → zip（打包较慢，勿设短超时）
export const downloadBackup = () => http.get('/backup', { responseType: 'blob', timeout: 0 })

// 一键恢复·准备：上传备份 zip，后端校验并解压到暂存 → 返回 { dataDir }
export const prepareRestore = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return http.post('/backup/restore-prepare', fd, { timeout: 0 })
}
