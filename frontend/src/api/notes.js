import http from './http'

/**
 * 笔记（我的，不是题库的）。
 *
 * 与"解析"的边界：解析写进 `question.analysis`，**会随题库文件导出、会给别人看**；
 * 笔记只存本机（不进内容包），是"我自己的记忆钩子与体会"——做题时随手记，
 * 也可以把 AI 讲解一键存进来（source=ai，界面上标出来）。
 */

/** 题库内的笔记（给 questionId 就只看这道题） */
export const listNotes = (bankId, params) => http.get(`/banks/${bankId}/notes`, { params })

/** 某道题的笔记（做题页/回顾页就地显示，不分页） */
export const listQuestionNotes = (questionId) => http.get(`/questions/${questionId}/notes`)

/** 新建笔记：{questionId?, content, source?}（questionId 省略 = 题库级随手记） */
export const createNote = (bankId, data) => http.post(`/banks/${bankId}/notes`, data)

export const updateNote = (id, content) => http.put(`/notes/${id}`, { content })

export const deleteNote = (id) => http.delete(`/notes/${id}`)
