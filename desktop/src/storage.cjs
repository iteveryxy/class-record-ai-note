'use strict'
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { spawn } = require('node:child_process')
const { isInside } = require('./core.cjs')

const overlaps = (a, b) => a === b || isInside(a, b) || isInside(b, a)
function storageRoot(config, userData) {
  if (!config.storageRoot) {
    let previous
    try { previous=JSON.parse(fs.readFileSync(path.join(userData,'storage-migration.json'),'utf8')) } catch { /* No completed migration. */ }
    if(previous?.phase==='complete')throw new Error('已有迁移记录但缺少存储配置，请从备份恢复 desktop.json，避免打开旧资料')
    return userData
  }
  if (typeof config.storageRoot !== 'string' || !path.isAbsolute(config.storageRoot)) throw new Error('存储目录配置无效，请恢复 desktop.json 中的存储设置')
  const directory = path.resolve(config.storageRoot)
  try {
    const marker = JSON.parse(fs.readFileSync(path.join(directory, 'classnotes-storage.json'), 'utf8'))
    if (marker.format !== 1 || !config.storageId || marker.storageId !== config.storageId || !fs.statSync(path.join(directory, 'data', 'classnotes.db')).isFile()) throw new Error('identity')
    return fs.realpathSync(directory)
  } catch { throw new Error('存储目录不可用或资料标识不匹配，请连接原磁盘后重试：' + directory) }
}
async function assertStorageIdle({ services, uploading, apiWrites, dirty }) {
  if (uploading || apiWrites) throw new Error('请先等待上传和保存操作完成')
  if (dirty) throw new Error('请先保存笔记和设置中的修改')
  if ((await services.request('/api/activity')).active) throw new Error('请等待所有转录、整理和同步任务完成后再迁移')
}
function validateTarget(source, target, protectedRoots = []) {
  source = fs.realpathSync(source); target = fs.realpathSync(target)
  if (!fs.statSync(target).isDirectory() || path.dirname(target) === target) throw new Error('请选择一个空文件夹，不能使用磁盘根目录')
  if (overlaps(source, target)) throw new Error('源目录和目标目录不能包含彼此')
  for (const root of protectedRoots) {
    if (fs.existsSync(root) && overlaps(fs.realpathSync(root), target)) throw new Error('不能将资料放入程序或桌面配置目录，请选择其他空文件夹')
  }
  if (fs.readdirSync(target).length) throw new Error('目标文件夹必须为空，避免覆盖已有文件')
  return target
}
function copyWithWorker(executable, source, target, notify) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, ['--migrate-source', source, '--migrate-target', target], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, PYTHONIOENCODING: 'utf-8' } })
    let pending = '', error = '', result
    child.stdout.setEncoding('utf8'); child.stderr.setEncoding('utf8')
    child.stderr.on('data', value => { error = (error + value).slice(-2000) })
    child.stdout.on('data', value => {
      pending += value
      let line
      while ((line = pending.indexOf('\n')) >= 0) {
        const raw = pending.slice(0, line); pending = pending.slice(line + 1)
        try {
          const state = JSON.parse(raw)
          if (state.phase === 'verified') result = state.result
          else if (state.phase === 'error') error = state.message
          else notify(state)
        } catch { /* Worker diagnostics are not commands. */ }
      }
    })
    child.on('error', reject)
    child.on('close', code => code === 0 && result ? resolve(result) : reject(new Error(error || '复制资料失败，原目录保留')))
  })
}

async function migrateStorage({ services, target, protectedRoots, persist, journal, notify, copy = copyWithWorker }) {
  const source = services.storageRoot
  target = validateTarget(source, target, protectedRoots)
  const staging = path.join(target, '.classnotes-migration-' + crypto.randomUUID())
  const state = { source, target, staging, phase: 'copying' }
  journal(state)
  let switched = false, committed = false
  try {
    notify({ phase: 'stopping' })
    await services.stop()
    const marker = await copy(path.join(services.resources, 'worker', 'ClassNotesWorker.exe'), source, staging, notify)
    if (fs.readdirSync(target).some(name => name !== path.basename(staging))) throw new Error('目标文件夹出现了其他文件，已停止切换')
    const diskMarker = JSON.parse(fs.readFileSync(path.join(staging, 'classnotes-storage.json'), 'utf8'))
    if (diskMarker.format !== 1 || diskMarker.storageId !== marker.storageId || !fs.statSync(path.join(staging, 'data', 'classnotes.db')).isFile()) throw new Error('迁移副本校验失败')
    for (const name of ['data', 'models', 'classnotes-storage.json']) {
      if (fs.existsSync(path.join(staging, name))) fs.renameSync(path.join(staging, name), path.join(target, name))
    }
    fs.rmdirSync(staging) // Only the now-empty application-owned staging folder.
    journal({ ...state, phase: 'starting' })
    services.setStorageRoot(target); switched = true
    notify({ phase: 'starting' }); await services.start()
    persist({ storageRoot: target, storageId: marker.storageId })
    committed = true
    try { journal({ ...state, phase: 'complete', storageId:marker.storageId }) } catch { /* The committed pointer remains authoritative. */ }
    notify({ phase: 'complete', source, target })
    return { migrated: true, source, target }
  } catch (error) {
    if (!committed) {
      if (switched) await services.stop()
      services.setStorageRoot(source)
      if (!services.children.length) await services.start()
      try { journal({ ...state, phase: 'failed' }) } catch { /* Source and preferences are still preserved. */ }
    }
    throw new Error('迁移失败，原资料目录保留：' + error.message.slice(0, 500), {cause:error})
  }
}
module.exports = { storageRoot, validateTarget, migrateStorage, copyWithWorker, assertStorageIdle }
