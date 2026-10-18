<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { HardDrive, FolderOpen, FolderInput, CheckCircle2 } from 'lucide-vue-next'
import { desktop, type DesktopInfo } from './desktop'
import { t, messageText } from './i18n'
const retainRecordings=defineModel<string>('retainRecordings',{default:'true'})
defineProps<{disabled?:boolean}>()
const info=ref<DesktopInfo>(),busy=ref(false),error=ref(''),notice=ref(''),backup=ref('')
onMounted(async()=>{if(desktop)info.value=await desktop.info()})
async function migrate(){
  busy.value=true;error.value='';notice.value=''
  try{
    const result=await desktop!.migrateStorage()
    if(result.migrated){info.value=await desktop!.info();backup.value=result.source||'';notice.value='资料迁移完成，原目录作为备份保留'}
  }catch(e){error.value=e instanceof Error?e.message:String(e)}
  finally{busy.value=false}
}
</script>
<template>
  <section class="settings-card storage-settings">
    <div class="settings-section-title"><span class="stat-icon mint"><HardDrive :size="21"/></span><div><h3>{{ t('存储与录音') }}</h3><p>{{ t('选择资料保存位置，并控制转录后的音频保留方式。') }}</p></div></div>
    <p v-if="error" class="desktop-error" role="alert">{{ messageText(error) }}</p>
    <div v-if="notice" class="desktop-notice" role="status"><CheckCircle2 :size="15"/><div>{{ t(notice) }}<p class="storage-path">{{ backup }}</p><small>{{ t('确认新目录使用正常后，可自行删除旧目录中的 data 和 models 文件夹。请保留桌面配置目录。') }}</small></div></div>
    <template v-if="desktop && info">
      <div class="desktop-data-row"><div><strong>{{ t('当前存储位置') }}</strong><p class="storage-path">{{ info.storageRoot }}</p><small>{{ t('包含 data（录音、转录、笔记、历史和密钥）及 models（模型缓存）。') }}</small></div><button class="btn secondary" :disabled="busy" @click="desktop.openData()"><FolderOpen :size="15"/>{{ t('打开资料目录') }}</button></div>
      <div class="desktop-data-row"><div><strong>{{ t('迁移到其他文件夹') }}</strong><p>{{ t('请选择空文件夹。复制并校验成功后才切换，原目录保留为备份。') }}</p><small>{{ t('迁移前请保存配置与笔记，并等待所有任务完成。') }}</small></div><button class="btn secondary migrate-storage" :disabled="busy || disabled" @click="migrate"><FolderInput :size="15"/>{{ t('更换并迁移') }}</button></div>
      <p class="storage-hint">{{ t('桌面偏好、草稿和诊断日志继续保存在：') }}<span class="storage-path">{{ info.configDirectory }}</span></p>
    </template>
    <label class="storage-retention checkbox-label"><input v-model="retainRecordings" type="checkbox" true-value="true" false-value="false"/>{{ t('成功转录后保留本地录音') }}</label>
    <p class="storage-hint">{{ t('保存配置后生效。仅影响之后成功完成的转录，已有录音不会自动删除。处理失败时保留音频以便重试。') }}</p>
    <p v-if="retainRecordings==='false'" class="storage-warning">{{ t('成功保存转录后，将清理原始录音和转录音频缓存。文字、时间戳、原始文字结果和笔记保留，但无法回听或重新转录。') }}</p>
    <p class="storage-hint">{{ t('已有录音可在录音详情中单独确认清理。迁移留下的旧目录备份不会自动清理。') }}</p>
  </section>
</template>
<style scoped>
.storage-path{overflow-wrap:anywhere}.storage-hint{font-size:11px;line-height:1.9;color:#889a7a;margin:12px 0}.storage-hint span{display:block}.storage-retention{display:flex;align-items:center;gap:9px;margin-top:23px;font-size:13px;color:#4c6d55}.storage-retention input{accent-color:#25866e}.storage-warning{padding:13px 15px;background:#fbf5e8;border:1px solid #ede2c6;border-radius:8px;color:#9a7541;font-size:12px;line-height:1.9}.desktop-notice small{display:block;margin-top:6px;line-height:1.8}.desktop-notice p{margin:6px 0}
</style>
