'use strict'
const path = require('node:path')
const net = require('node:net')

function safeAsset(root, pathname) {
  let decoded
  try { decoded = decodeURIComponent(pathname) } catch { throw new Error('资源路径无效') }
  if (decoded.includes('\0') || decoded.includes('\\')) throw new Error('资源路径无效')
  const target = path.resolve(root, '.' + (decoded === '/' ? '/index.html' : decoded))
  const relative = path.relative(root, target)
  if (relative.startsWith('..') || path.isAbsolute(relative)) throw new Error('资源路径超出应用目录')
  return target
}
function updateUrl(value) {
  if (!value || !value.trim()) return ''
  const url = new URL(value.trim())
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash) throw new Error('更新地址必须为不含凭证和查询参数的 HTTPS 目录')
  return url.href.endsWith('/') ? url.href : url.href + '/'
}
function compatibleUpdate(info, currentSchema) {
  const minimum = Number(info.minimumSchemaVersion), target = Number(info.dataSchemaVersion)
  if (!Number.isInteger(minimum) || !Number.isInteger(target)) throw new Error('更新缺少数据格式兼容说明，请使用安装包手动升级')
  if (minimum > currentSchema || target < currentSchema) throw new Error('此更新与当前数据格式不兼容，已停止安装；请先核对发行说明')
  return true
}
function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => { const port = server.address().port; server.close(() => resolve(port)) })
  })
}
function isInside(root, candidate) {
  const relative = path.relative(path.resolve(root), path.resolve(candidate))
  return relative !== '' && !relative.startsWith('..') && !path.isAbsolute(relative)
}
module.exports = { safeAsset, updateUrl, compatibleUpdate, freePort, isInside }
