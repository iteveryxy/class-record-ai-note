<script setup lang="ts">
import { t, messageText } from './i18n'
import { ref,onMounted,onUnmounted } from 'vue'
import { FolderOpen, Download, RefreshCw, Package, FileInput, CheckCircle2 } from 'lucide-vue-next'
import { desktop,type DesktopInfo,type DesktopState } from './desktop'
const info=ref<DesktopInfo>(),state=ref<DesktopState>(),url=ref(''),error=ref(''),busy=ref(false),notice=ref('')
let unsubscribe:(()=>void)|undefined
onMounted(async()=>{if(!desktop)return;info.value=await desktop.info();url.value=info.value.updateUrl;state.value=info.value.update;unsubscribe=desktop.onState(({update})=>{if(update)state.value=update})})
onUnmounted(()=>unsubscribe?.())
async function act(action:()=>Promise<unknown>){busy.value=true;error.value='';try{await action()}catch(e){error.value=e instanceof Error?e.message:String(e)}finally{busy.value=false}}
async function importOld(){await act(async()=>{const result=await desktop!.importData();if(result.imported)notice.value='旧版录音、笔记与配置已复制，源文件仍保留'})}
async function saveUpdateUrl(){await act(async()=>{state.value=await desktop!.configureUpdates(url.value)})}
async function checkUpdate(){await act(async()=>{state.value=await desktop!.checkUpdates()})}
async function downloadUpdate(){await act(async()=>{state.value=await desktop!.downloadUpdate()})}
async function applyUpdate(){await act(()=>desktop!.applyUpdate())}
</script>
<template>
  <section v-if="desktop && info" class="settings-card desktop-settings"><div class="settings-section-title"><span class="stat-icon blue"><Package :size="21"/></span><div><h3>{{ t('桌面版与更新') }}</h3><p>{{ t('课记') }} {{ info.version }} {{ t('· Windows 64 位 · 数据格式 v') }}{{ info.service.schemaVersion }}</p></div><span class="tag">{{ info.packaged?t('已打包'):t('开发模式') }}</span></div>
    <div v-if="error" role="alert" class="desktop-error">{{ messageText(error) }}</div><div v-if="notice" class="desktop-notice"><CheckCircle2 :size="14"/>{{ messageText(notice) }}</div>
    <div class="desktop-data-row"><div><strong>{{ t('独立的本地资料目录') }}</strong><p>{{ info.dataDirectory }}</p><small>{{ t('安装、升级与卸载默认保留录音、笔记、配置及模型缓存。') }}</small></div><button class="btn secondary" @click="desktop.openData()"><FolderOpen :size="15"/>{{ t('打开资料目录') }}</button></div>
    <div class="desktop-data-row"><div><strong>{{ t('导入网页 MVP 数据') }}</strong><p>{{ t('选择旧版 data/local 文件夹。仅允许导入到空工作空间，源数据不会删除。') }}</p></div><button class="btn secondary" :disabled="busy" @click="importOld"><FileInput :size="15"/>{{ t('导入旧版数据') }}</button></div>
    <div class="desktop-update-settings"><label>{{ t('更新发布目录') }}<input v-model="url" :placeholder="t('https://你的更新站点/classnotes/windows/')"/><small>{{ t('填写发布者提供的 HTTPS 地址，目录需包含 latest.yml、安装包和 blockmap。') }}</small></label><button class="btn secondary" :disabled="busy" @click="saveUpdateUrl">{{ t('保存更新地址') }}</button></div>
    <div class="desktop-update-status"><span>{{ messageText(state?.message) }}</span><progress v-if="state?.phase==='downloading'" :value="state.percent||0" max="100"/><span v-if="state?.phase==='downloading'">{{ state.percent||0 }}%</span><button v-if="state?.phase==='available'" class="btn primary" :disabled="busy" @click="downloadUpdate"><Download :size="15"/>{{ t('下载') }} {{ state.version }}</button><button v-else-if="state?.phase==='downloaded'" class="btn primary" :disabled="busy" @click="applyUpdate">{{ t('重启并安装') }}</button><button v-else class="btn secondary" :disabled="busy||state?.phase==='checking'||state?.phase==='downloading'" @click="checkUpdate"><RefreshCw :size="15"/>{{ t('检查更新') }}</button></div>
  </section>
</template>
