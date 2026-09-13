import http from './http'

// 共用材料（多题共用的大题干/图表）管理
export const listMaterials = (bankId) => http.get(`/banks/${bankId}/materials`)

export const createMaterial = (bankId, content) => http.post(`/banks/${bankId}/materials`, { content })

export const updateMaterial = (bankId, id, content) =>
  http.put(`/banks/${bankId}/materials/${id}`, { content })

export const deleteMaterial = (bankId, id) => http.delete(`/banks/${bankId}/materials/${id}`)

// 图片上传 → data = { name }（含日期目录，如 260829/ab12.png，直接拼进 [图片:name] 标记）
export const uploadImage = (bankId, file) => {
  const fd = new FormData()
  fd.append('file', file)
  return http.post(`/banks/${bankId}/images`, fd)
}

/** 图片标记：拼接 [图片:文件名] */
export const imageMarker = (name) => `[图片:${name}]`
