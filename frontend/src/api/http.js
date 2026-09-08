import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一 axios 实例：baseURL 相对路径 /api
 * - 开发：Vite 代理到 http://localhost:8080
 * - 生产：由后端同源托管 dist
 * 响应统一包装 { code, data, message }，code === 200 才算成功。
 */
const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    // 后端统一包装
    if (body && typeof body.code === 'number') {
      if (body.code === 200) {
        return body.data
      }
      const msg = body.message || `请求失败（code=${body.code}）`
      ElMessage.error(msg)
      return Promise.reject(new Error(msg))
    }
    return body
  },
  (error) => {
    // 调用方自行处理错误时（如发现页离线空态），跳过全局弹窗
    if (error.config?.skipErrorMessage) {
      return Promise.reject(error)
    }
    const msg = error.response?.data?.message || error.message || '网络错误，请确认后端服务已启动'
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

export default http
