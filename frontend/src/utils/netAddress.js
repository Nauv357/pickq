/* ============================================================
   本机 / 局域网（私网）地址判定 —— 与后端 src/main/java/com/tiku/util/NetAddress.java 同口径
   用途（两处必须一致，各写一份正则迟早走偏）：
   1. 设置页「获取可用模型」：无 Key 时只有公网地址才提示「请先填写 API Key」，
      本机/局域网（Ollama / vLLM / LM Studio 等）允许留空、请求不带鉴权头；
   2. 设置页地址提示：本机/局域网可提示"本地模型无需 API Key"。
   判定为"本机或私网"的形态：
   - 主机名 localhost（含 *.localhost）；
   - IPv6 回环 ::1；
   - IPv4 回环网段 127.0.0.0/8；
   - RFC 1918 私网：10.0.0.0/8、172.16.0.0/12（172.16~172.31）、192.168.0.0/16。
   一律<b>不认</b>（保守：宁可要求填 Key）：公网域名/公网 IP、0.0.0.0（"监听所有网卡"不是可访问地址）、
   169.254.x.x（链路本地）、IPv4 简写（127.1）、IPv4-mapped IPv6（::ffff:192.168.1.5）。
   ============================================================ */

/** IPv4 简写（如 127.1 / 127.0.1）：后端 URI.getHost() 会原样返回并判为非私网，这里保持一致 */
const IPV4_SHORTHAND = /^\d{1,3}(?:\.\d{1,3}){1,2}$/

/** host 归一化：去首尾空白、转小写、剥 IPv6 方括号；空 → 空串 */
function normalizeHost(host) {
  if (typeof host !== 'string') return ''
  let h = host.trim().toLowerCase()
  if (h.startsWith('[') && h.endsWith(']')) h = h.slice(1, -1)
  return h
}

/** 取 host（小写、去 IPv6 方括号）；URL 为空/不合法/无 host 时返回空串（不抛异常，调用方按"非私网"处理） */
export function hostOf(url) {
  const raw = typeof url === 'string' ? url.trim() : ''
  if (!raw) return ''
  try {
    return normalizeHost(new URL(raw).hostname)
  } catch (e) {
    return ''
  }
}

/** 是否本机或私网 host（规则见文件头注释） */
export function isLocalOrPrivateHost(host) {
  const h = normalizeHost(host)
  if (!h) return false
  if (h === 'localhost' || h.endsWith('.localhost')) return true
  if (h === '::1' || h === '0:0:0:0:0:0:0:1') return true
  const parts = h.split('.')
  if (parts.length !== 4) return false
  const octets = []
  for (const part of parts) {
    if (!/^\d{1,3}$/.test(part)) return false // 只接受 ASCII 数字且最多 3 位
    const n = Number(part)
    if (n > 255) return false
    octets.push(n)
  }
  if (octets[0] === 127) return true // 127.0.0.0/8
  if (octets[0] === 10) return true // 10.0.0.0/8
  if (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) return true // 172.16.0.0/12
  return octets[0] === 192 && octets[1] === 168 // 192.168.0.0/16
}

/** 原始串里的 authority（去 scheme/path/query/fragment/userinfo）；仅用于识别 IPv4 简写 */
function rawAuthority(url) {
  const m = /^[a-zA-Z][\w+.-]*:\/\/([^/?#]*)/.exec(typeof url === 'string' ? url.trim() : '')
  if (!m) return ''
  let auth = m[1]
  const at = auth.lastIndexOf('@')
  if (at >= 0) auth = auth.slice(at + 1)
  if (auth.startsWith('[')) return auth // IPv6 字面量
  const colon = auth.indexOf(':')
  return colon >= 0 ? auth.slice(0, colon) : auth
}

/**
 * 是否本机或私网地址（入参为完整 URL，形如 http://127.0.0.1:11434/v1）。
 * 注意：WHATWG URL 会把 http://127.1:11434 规范化成 127.0.0.1，而后端不会——
 * 这里按原始 authority 拦掉 IPv4 简写，与后端保持一致（宁可要求填 Key）。
 */
export function isLocalOrPrivate(url) {
  if (!isLocalOrPrivateHost(hostOf(url))) return false
  const auth = rawAuthority(url)
  if (auth && !auth.startsWith('[') && IPV4_SHORTHAND.test(auth)) return false
  return true
}
