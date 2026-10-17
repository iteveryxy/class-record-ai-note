'use strict'
const { spawn } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { freePort } = require('./core.cjs')

class Services {
  constructor({ resources, userData, demo, version, notify }) {
    Object.assign(this, { resources, userData, demo, version, notify })
    this.children = []; this.stopping = false
    this.data = path.join(userData, 'data')
    this.token = crypto.randomBytes(32).toString('hex')
    this.workerToken = crypto.randomBytes(32).toString('hex')
  }
  launch(executable, args, name, env) {
    const logDir = path.join(this.userData, 'logs'); fs.mkdirSync(logDir, { recursive: true })
    const output = fs.createWriteStream(path.join(logDir, `${name}.log`), { flags: 'a' })
    const child = spawn(executable, args, { cwd: this.resources, env: { ...process.env, ...env }, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
    child.stdout.pipe(output, { end: false }); child.stderr.pipe(output, { end: false })
    child.on('error', error => { output.write(error.message + '\n'); this.notify({ phase: 'error', message: `${name} 启动失败，请查看诊断日志` }) })
    child.on('exit', (code) => { output.end(); if (!this.stopping) this.notify({ phase: 'error', message: `${name} 已停止（${code}），请重新启动课记` }) })
    this.children.push(child); return child
  }
  async wait(url, token, child, timeout = 90000) {
    const end = Date.now() + timeout
    while (Date.now() < end) {
      if (child.exitCode !== null) throw new Error('本地服务启动失败，请查看日志')
      try {
        const response = await fetch(url, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(1500) })
        if (response.ok) return await response.json()
      } catch { /* service booting */ }
      await new Promise(resolve => setTimeout(resolve, 250))
    }
    throw new Error('本地服务启动超时，请查看诊断日志')
  }
  async start() {
    this.stopping = false; fs.mkdirSync(this.data, { recursive: true })
    this.notify({ phase: 'starting', message: '正在准备本地录音与笔记服务…' })
    this.port = await freePort(); this.workerPort = await freePort()
    this.baseUrl = `http://127.0.0.1:${this.port}`
    const environment = { DATA_DIR: this.data, APP_DEMO: String(this.demo), APP_DESKTOP: 'true', APP_API_TOKEN: this.token,
      APP_VERSION: this.version, PORT: String(this.port), ASR_WORKER_URL: `http://127.0.0.1:${this.workerPort}`, ASR_WORKER_TOKEN: this.workerToken,
      MODELSCOPE_CACHE: path.join(this.userData, 'models', 'modelscope'), HF_HOME: path.join(this.userData, 'models', 'huggingface'), PYTHONIOENCODING: 'utf-8' }
    if (!this.demo) {
      const worker = this.launch(path.join(this.resources, 'worker', 'ClassNotesWorker.exe'), ['--port', String(this.workerPort)], 'worker', environment)
      await this.wait(`http://127.0.0.1:${this.workerPort}/health`, this.workerToken, worker)
    }
    const backend = this.launch(path.join(this.resources, 'java', 'bin', 'java.exe'), ['--enable-native-access=ALL-UNNAMED', '-jar', path.join(this.resources, 'backend', 'class-notes.jar')], 'backend', environment)
    this.health = await this.wait(`${this.baseUrl}/api/health`, this.token, backend)
    this.notify({ phase: 'ready', message: '本地服务已就绪', schemaVersion: this.health.schemaVersion })
    return this.health
  }
  async request(route) {
    const response = await fetch(this.baseUrl + route, { headers: { Authorization: `Bearer ${this.token}` }, signal: AbortSignal.timeout(3000) })
    if (!response.ok) throw new Error('本地服务暂不可用')
    return response.json()
  }
  async stop() {
    this.stopping = true
    await Promise.all(this.children.map(child => new Promise(resolve => {
      if (child.exitCode !== null) { resolve(); return }
      child.once('exit', resolve); child.kill()
      const timer = setTimeout(resolve, 4000); timer.unref()
    })))
    this.children = []
  }
}
module.exports = { Services }
