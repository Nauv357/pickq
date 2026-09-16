import http from './http'

// 刷题记录接口（纯本地，错题/进度/复习由此派生）
//
// 说明（2026-09-16 审计）：`submitStudyAnswer` / `getBankRecords` / `getQuestionRecords` 三个包装
// 从未有调用方——作答统一走"会话制"（`POST /banks/{id}/sessions` + `finish` 一次性交卷），
// 所以这里只留界面真正在用的接口，避免后来者以为存在"逐题提交"这条路径。

// 主观题自评（自由给分 0~满分） → { recordId, selfGrade, earnedScore }
export const selfGradeRecord = (recordId, earnedScore) =>
  http.put(`/study-records/${recordId}/self-grade`, { earnedScore })

// 错题列表（含选项/最近作答/错误次数）
export const getWrongQuestions = (bankId, params) =>
  http.get(`/banks/${bankId}/wrong-questions`, { params })

// 学习进度（总题数/已做/正确率/完成度）
export const getBankProgress = (bankId) => http.get(`/banks/${bankId}/progress`)

// 今日待复习队列
export const getReviewDue = (bankId, params) => http.get(`/banks/${bankId}/review/due`, { params })

// 复习队列摘要 → { dueTotal, overdueTotal }（徽标计数/开启提示；开关关闭时为 0）
export const getReviewSummary = (bankId) => http.get(`/banks/${bankId}/review/summary`)

// 重置复习计划：清空该题库全部复习状态（含暂停标记）→ 清除条数
export const resetReviewStates = (bankId) => http.delete(`/banks/${bankId}/review-states`)

// 暂停/恢复复习（"不再复习此题"）
export const setReviewSuspended = (questionId, suspended) =>
  http.put(`/questions/${questionId}/review-suspend`, { suspended })

// 导出记录文件 → data = 记录文件 JSON（前端保存为 .json）
export const exportStudyRecords = () => http.post('/study-records/export')

// 导入记录文件（body = JSON 原文）→ data = { imported, missingBanks, missingQuestions }
export const importStudyRecords = (jsonText) =>
  http.post('/study-records/import', jsonText, { headers: { 'Content-Type': 'text/plain; charset=utf-8' } })
