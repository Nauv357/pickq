import http from './http'

/**
 * 笔记（我的，不是题库的）。
 *
 * 与"解析"的边界：解析写进 `question.analysis`，**会随题库文件导出、会给别人看**；
 * 笔记只存本机（不进内容包），是"我自己的记忆钩子与体会"——做题时随手记，
 * 也可以把 AI 讲解一键存进来（source=ai，界面上标出来）。
 *
 * 笔记不隶属于任何题库/题目：挂在哪里由**关联**决定（0..N 个题库、0..N 道题，未归类也合法）。
 */

/** 笔记列表：params = { bankId?, questionId?, unlinked?, keyword?, page, size }（都不给 = 全部） */
export const listNotes = (params) => http.get('/notes', { params })

/** 某道题的笔记（做题页/回顾页就地显示，不分页） */
export const listQuestionNotes = (questionId) => http.get(`/questions/${questionId}/notes`)

/** 新建笔记：{bankId?, questionId?, content, source?, color?}（关联都可省略 = 未归类随手记） */
export const createNote = (data) => http.post('/notes', data)

export const updateNote = (id, content) => http.put(`/notes/${id}`, { content })

/** 只改标记色（不动正文）；color 传 null = 取消标色 */
export const setNoteColor = (id, color) => http.put(`/notes/${id}/color`, { color })

export const deleteNote = (id) => http.delete(`/notes/${id}`)

/** 加一条关联（幂等）：type = bank / question */
export const addNoteLink = (id, type, targetId) => http.post(`/notes/${id}/links`, { type, targetId })

/** 去掉一条关联（笔记内容保留） */
export const removeNoteLink = (id, type, targetId) =>
  http.delete(`/notes/${id}/links`, { params: { type, targetId } })
