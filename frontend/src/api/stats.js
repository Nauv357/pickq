import http from './http'

// 学习统计聚合：今日/连胜/累计 KPI + 365 天日粒度 + 30 天到期预测 + 熟练度分布
export const getStatsSummary = () => http.get('/stats/summary')

// 学习统计二期：题库×掌握度 / 错题治愈 / 最近练习
export const getStatsDetail = () => http.get('/stats/detail')
