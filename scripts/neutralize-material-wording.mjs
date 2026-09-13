/**
 * 一次性迁移脚本（2026-09-13 已执行）：把后端提示词/注释里"资料分析"这类公考专用说法
 * 改成主题中立的表述——材料是给各类题型公用的，不该暗示只有公务员考试的数据分析题才用。
 *
 * 用 Node 做字面替换（比 PowerShell 的数组/字符串语义可靠：PowerShell 把 @(@('a','b')) 展平成字符串后
 * 会拿"首字符"去做替换，曾把整份文件里的空格替换成 * —— 见提交说明）。
 * 幂等：再跑一次不会改动任何东西；`--check` 只报告不写盘。
 * 用法：node scripts/neutralize-material-wording.mjs [--check]
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { globSync } from 'node:fs'

const CHECK = process.argv.includes('--check')

const PAIRS = [
  ['（多题共用大题干，如"材料一/资料分析"）', '（多题共用的大题干/图表，如"材料一""阅读材料"）'],
  ['（如"材料一"、资料分析材料）', '（如"材料一"、阅读材料、图表资料）'],
  ['（材料题（多题共用大题干，如资料分析/阅读材料）处理：', '（材料题（多题共用的大题干/图表，如阅读材料、材料一）处理：'],
  ['共享材料规则（资料分析/阅读材料题）：', '共享材料规则（多题共用的大题干/图表/阅读材料）：'],
  ['//任务材料素材列表（预览页"材料素材区"：资料分析/阅读材料题的共享材料，用户拖入/点击关联到题目材料区）',
    '//任务材料素材列表（预览页"材料素材区"：多题共用的材料，用户拖入/点击关联到题目材料区）'],
  [' * 共享材料（资料分析大题干）管理。', ' * 共享材料（多题共用的大题干/图表）管理。'],
  ['//材料组识别启用（资料分析扫描件/图片常见）', '//材料组识别启用（扫描件/图片里的共用材料常见）'],
  ['// ==================== 材料素材（资料分析/阅读材料题） ====================',
    '// ==================== 材料素材（多题共用的材料） ===================='],
  ['//资料分析的长材料段落保持在题号行之前不动。', '//共用材料的长段落保持在题号行之前不动。'],
  [' * 题目图片存储（资料分析图表、主观题参考答案图等）。', ' * 题目图片存储（材料里的图表、主观题参考答案图等）。'],
  [' * 共享材料（资料分析大题干）管理：随题库生命周期，删除时组内题解除引用。',
    ' * 共享材料（多题共用的大题干/图表）管理：随题库生命周期，删除时组内题解除引用。'],
  ['（组 = 同一 material_id 的所有题，资料分析大题干共用）', '（组 = 同一 material_id 的所有题，多题共用大题干）'],
  ['//共享材料引用（资料分析组内题，可空）', '//共享材料引用（多题共用材料，可空）'],
  [' * 组内题（资料分析）：materialId + materialContent 返回共享大题干。',
    ' * 带共享材料的题：materialId + materialContent 返回共用的大题干。'],
  ['//共享材料引用（资料分析组内题，null=不修改）', '//共享材料引用（多题共用材料，null=不修改）'],
  ['/** 共享材料数组（资料分析大题干，v1.1 可选扩展） */', '/** 共享材料数组（多题共用的大题干/图表，v1.1 可选扩展） */'],
  [' * 内容包中的共享材料（资料分析大题干）。', ' * 内容包中的共享材料（多题共用的大题干/图表）。'],
  [' * 共享材料（资料分析大题干：文字 + 图片标记 [图片:文件名]）。',
    ' * 共享材料（多题共用的大题干/图表：文字 + 图片标记 [图片:文件名]）。'],
  ['//共享材料引用（资料分析组内题；见 material 表）', '//共享材料引用（多题共用材料；见 material 表）']
]

const files = globSync('src/main/java/**/*.java', { cwd: process.cwd() })
let changedFiles = 0
let replaced = 0
const unmatched = []

for (const rel of files) {
  let text = readFileSync(rel, 'utf8')
  const before = text
  for (const [from, to] of PAIRS) {
    if (text.includes(from)) {
      text = text.split(from).join(to)
      replaced++
    }
  }
  if (text !== before) {
    changedFiles++
    if (!CHECK) writeFileSync(rel, text, 'utf8')
  }
}

// 收尾检查：不该再有任何"资料分析"
const leftovers = []
for (const rel of files) {
  const text = readFileSync(rel, 'utf8')
  text.split('\n').forEach((line, i) => {
    if (line.includes('资料分析')) leftovers.push(`${rel}:${i + 1}: ${line.trim().slice(0, 100)}`)
  })
}

console.log(`${CHECK ? '[check] ' : ''}替换词组命中 ${replaced} 处，涉及 ${changedFiles} 个文件`)
if (unmatched.length) console.log('未命中的模式：\n' + unmatched.join('\n'))
if (leftovers.length) {
  console.log(`\n仍有 ${leftovers.length} 行含「资料分析」：`)
  console.log(leftovers.join('\n'))
} else {
  console.log('\n✓ 后端已无「资料分析」表述')
}
