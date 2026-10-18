<script setup lang="ts">
import { t, messageText } from './i18n'
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { AudioLines, Minus, Square, X, FolderInput, LoaderCircle } from 'lucide-vue-next'
import { desktop, type StorageState } from './desktop'
import { size } from './markdown'
const status=ref('本地工作空间')
const storage=ref<StorageState>()
const migrating=computed(()=>storage.value&&!['idle','complete','error'].includes(storage.value.phase))
let unsubscribe: (()=>void)|undefined
onMounted(async()=>{
  if(!desktop) return
  document.documentElement.classList.add('desktop-mode')
  const info=await desktop.info();status.value=info.service.message;storage.value=info.storage
  unsubscribe=desktop.onState(({service,storage:progress})=>{if(service) status.value=service.message;if(progress)storage.value=progress})
})
onUnmounted(()=>unsubscribe?.())
</script>
<template>
  <header v-if="desktop" class="desktop-titlebar"><div class="desktop-caption"><AudioLines :size="17"/><strong>{{ t('课记') }}</strong><span>{{ t('课堂录音 AI 笔记') }}</span></div><div class="desktop-service"><i/>{{ messageText(status) }}</div><div class="window-buttons"><button :aria-label="t('最小化')" @click="desktop.minimize()"><Minus :size="15"/></button><button :aria-label="t('最大化或还原')" @click="desktop.maximize()"><Square :size="12"/></button><button :aria-label="t('关闭课记')" class="window-close" @click="desktop.close()"><X :size="17"/></button></div></header>
  <Teleport to="body"><div v-if="migrating" class="storage-migration-overlay" role="dialog" aria-modal="true" :aria-label="t('正在迁移资料')"><section><FolderInput :size="32"/><h2>{{ t('正在迁移资料') }}</h2><p><LoaderCircle :size="15" class="spin"/>{{ messageText(storage?.message) }}</p><progress :value="storage?.phase==='copying'?storage.percent:undefined" max="100"/><small v-if="storage?.phase==='copying' && storage.total">{{ size(storage.loaded||0) }} / {{ size(storage.total) }} · {{ storage.percent }}%</small><p class="migration-hint">{{ t('正在校验并切换存储位置，请保持课记运行。原目录会保留为备份。') }}</p></section></div></Teleport>
</template>
<style scoped>
.storage-migration-overlay{position:fixed;inset:42px 0 0;z-index:150;display:flex;align-items:center;justify-content:center;background:#edf3efec;backdrop-filter:blur(4px)}.storage-migration-overlay section{width:460px;max-width:85vw;padding:36px;background:#fff;border:1px solid #dce7df;border-radius:16px;text-align:center;box-shadow:0 20px 70px #23493615;color:#51856d}.storage-migration-overlay h2{font-size:20px;margin:18px 0}.storage-migration-overlay p{display:flex;justify-content:center;gap:9px;font-size:13px;line-height:1.8}.storage-migration-overlay progress{width:100%;accent-color:#358f74;height:9px;margin:15px 0}.storage-migration-overlay small{font-size:12px;color:#829980}.storage-migration-overlay .migration-hint{font-size:12px;color:#829980;margin:20px 0 0}
</style>
