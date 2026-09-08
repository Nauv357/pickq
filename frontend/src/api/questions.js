import http from './http'

// 题目接口
export const getQuestion = (id) => http.get(`/questions/${id}`)

// 创建（body 需含 bankId），data = questionId
export const createQuestion = (data) => http.post('/questions', data)

// 更新（不修改 bankId）
export const updateQuestion = (id, data) => http.put(`/questions/${id}`, data)

export const deleteQuestion = (id) => http.delete(`/questions/${id}`)

// 判题（不写记录，预览用；做题请用 study-records）
export const submitAnswer = (id, selectedKeys) => http.post(`/questions/${id}/answer`, { selectedKeys })

// 收藏/取消收藏
export const setFavorite = (id, favorite) => http.put(`/questions/${id}/favorite`, { favorite })

// 单题 AI 辅助解析（按需生成，不落库）→ 解析文本。
// 思考模式单题生成常需 20-90 秒，必须覆盖全局 15s 超时
export const aiAnalysisQuestion = (id) => http.post(`/questions/${id}/ai-analysis`, null, { timeout: 180000 })

// 草稿 AI 解析（编辑/录入面板：基于表单当前内容，题目可未保存）
export const aiAnalysisDraft = (data) => http.post('/questions/ai-analysis-draft', data, { timeout: 180000 })

// 保存解析为题目正式解析
export const saveQuestionAnalysis = (id, analysis) => http.put(`/questions/${id}/analysis`, { analysis })
