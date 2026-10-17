'use strict'
const fs = require('node:fs')
const path = require('node:path')
const { NsisUpdater } = require('electron-updater')
const { updateUrl, compatibleUpdate } = require('./core.cjs')

class Updates {
  constructor({ app, config, services, notify, install, createUpdater }) {
    Object.assign(this, { app, config, services, notify, install, createUpdater })
    this.state = { phase: config.updateUrl ? 'idle' : 'unconfigured', message: config.updateUrl ? '点击检查新版本' : '尚未配置更新发布地址' }
    if (config.updateUrl) this.configure(config.updateUrl)
  }
  emit(state) { this.state = { ...this.state, ...state }; this.notify(this.state); return this.state }
  configure(url) {
    if (['checking','downloading','downloaded'].includes(this.state.phase)) throw new Error('请先完成当前更新操作，再修改发布地址')
    const validated = updateUrl(url)
    this.config.updateUrl = validated
    const options={ provider: 'generic', url: validated, channel: 'latest' }
    this.updater = validated ? (this.createUpdater ? this.createUpdater(options) : new NsisUpdater(options)) : null
    if (!this.updater) return this.emit({ phase: 'unconfigured', message: '尚未配置更新发布地址' })
    this.updater.autoDownload = false; this.updater.autoInstallOnAppQuit = false; this.updater.allowDowngrade = false
    this.updater.disableWebInstaller = true
    this.updater.logger = { info() {}, warn() {}, error: message => fs.appendFileSync(path.join(this.app.getPath('userData'), 'logs', 'updater.log'), String(message) + '\n') }
    this.updater.on('error', error => this.emit({ phase: 'error', message: `更新失败：${error.message.slice(0,300)}` }))
    this.updater.on('download-progress', progress => this.emit({ phase: 'downloading', percent: Math.round(progress.percent), message: '正在下载更新…' }))
    this.updater.on('update-downloaded', info => this.emit({ phase: 'downloaded', version: info.version, message: '更新已下载，完成当前工作后可重启安装' }))
    return this.emit({ phase: 'idle', message: '更新地址已配置' })
  }
  async check() {
    if (['checking','downloading','downloaded'].includes(this.state.phase)) throw new Error('更新操作正在进行，请等待完成')
    if (!this.updater) return this.emit({ phase: 'unconfigured', message: '请先配置实际的 HTTPS 更新发布目录' })
    if (!this.app.isPackaged) return this.emit({ phase: 'development', message: '开发模式不执行更新，请使用已安装的桌面版' })
    this.emit({ phase: 'checking', message: '正在检查更新…' })
    try {
      const result = await this.updater.checkForUpdates()
      const semver=require('semver')
      if (!result || !semver.gt(result.updateInfo.version, this.app.getVersion())) return this.emit({ phase: 'current', message: '当前已是最新版本' })
      compatibleUpdate(result.updateInfo, this.services.health.schemaVersion)
      return this.emit({ phase: 'available', version: result.updateInfo.version, message: `发现新版本 ${result.updateInfo.version}` })
    } catch (error) { return this.emit({ phase: 'error', message: error.message.slice(0,300) }) }
  }
  async download() {
    if (this.state.phase !== 'available') throw new Error('请先检查并确认可用更新')
    compatibleUpdate(this.updater.updateInfoAndProvider.info, this.services.health.schemaVersion)
    this.emit({ phase: 'downloading', percent: 0, message: '正在下载更新…' })
    try { await this.updater.downloadUpdate(); return this.state }
    catch (error) { return this.emit({ phase: 'error', message: error.message.slice(0,300) }) }
  }
  async apply() {
    if (this.state.phase !== 'downloaded') throw new Error('更新尚未下载完成')
    if ((await this.services.request('/api/activity')).active) throw new Error('还有录音任务在运行，请完成后再重启安装')
    await this.install(() => this.updater.quitAndInstall(false, true))
  }
}
module.exports = { Updates }
