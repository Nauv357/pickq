import http from './http'

/**
 * 学习路线（学习路径引擎阶段 2）：今天做什么 / 下一步 / 还缺什么。
 *
 * 这一层**不调模型**：掌握度、门控、外缘顺序、每日题量全部是后端确定性公式，
 * 所以随便刷新、零 token；接口失败也不会消耗任何额度。
 */

/** 整条路线：阶段 → 节点状态（掌握度/题量/状态）+ 外缘（nextBatch）+ 缺口（gaps） */
export const getRoadmap = (bankId, templateId) => http.get('/roadmap', { params: { bankId, templateId } })

/** 今天的任务：外缘主攻节点的新题 + 到期复习（当天冻结，完成度按今天实际作答算） */
export const getTodayTasks = (bankId, templateId) => http.get('/roadmap/today', { params: { bankId, templateId } })

/** 个体输入：目标模板 / 目标原话 / 每日题量 */
export const getRoadmapProfile = () => http.get('/roadmap/profile')

export const saveRoadmapProfile = (data) => http.put('/roadmap/profile', data)
