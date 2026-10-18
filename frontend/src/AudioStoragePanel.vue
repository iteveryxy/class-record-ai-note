<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { Trash2, X, HardDrive } from 'lucide-vue-next'
import { api, post } from './api'
import { t, messageText } from './i18n'
import { size } from './markdown'
interface Info { audioAvailable:boolean;files:number;bytes:number;canDelete:boolean;transcriptId?:string;deleteBlockedReason?:string }
const props=defineProps<{recordingId:string;title:string;active:boolean}>()
const emit=defineEmits<{deleted:[]}>()
const info=ref<Info>(),open=ref(false),busy=ref(false),error=ref('')
const dialog=ref<HTMLElement>(),cancelButton=ref<HTMLButtonElement>()
let previousFocus:HTMLElement|null=null
const audioSize=(bytes:number)=>bytes<1024?`${bytes} B`:bytes<1024*1024?`${(bytes/1024).toFixed(1)} KB`:size(bytes)
watch(open,async value=>{if(value){previousFocus=document.activeElement as HTMLElement;await nextTick();cancelButton.value?.focus()}else if(previousFocus?.isConnected)previousFocus.focus()})
function keydown(event:KeyboardEvent){
  if(event.key==='Escape'){event.preventDefault();if(!busy.value)open.value=false}
  if(event.key!=='Tab')return
  const fields=Array.from(dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled)')||[]),first=fields[0],last=fields[fields.length-1]
  if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus()}
  else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus()}
}
watch(()=>[props.recordingId,props.active],async()=>{try{info.value=await api<Info>(`/recordings/${props.recordingId}/audio-storage`)}catch{/* The detail page reports connectivity failures. */}},{immediate:true})
async function preview(){error.value='';busy.value=true;try{info.value=await api<Info>(`/recordings/${props.recordingId}/audio-storage`);open.value=true}catch(e){error.value=String(e)}finally{busy.value=false}}
async function remove(){
  busy.value=true;error.value=''
  try{info.value=await post<Info>(`/recordings/${props.recordingId}/audio/delete`,{confirmed:true,transcriptId:info.value?.transcriptId});open.value=false;emit('deleted')}
  catch(e){error.value=e instanceof Error?e.message:String(e)}finally{busy.value=false}
}
</script>
<template>
  <div v-if="info?.files" class="audio-storage-actions"><span><HardDrive :size="13"/>{{ t('本地音频') }} · {{ audioSize(info.bytes) }}</span><button class="text-btn" :disabled="busy || active || !info.canDelete" :title="messageText(info.deleteBlockedReason)||t('仅可清理已成功转录且无任务运行的录音')" @click="preview"><Trash2 :size="13"/>{{ t('清理本地录音') }}</button><span v-if="error && !open" role="alert">{{ messageText(error) }}</span></div>
  <Teleport to="body"><div v-if="open" class="modal-overlay" @click.self="!busy && (open=false)"><section ref="dialog" class="modal audio-cleanup-modal" @keydown="keydown" role="dialog" aria-modal="true" :aria-label="t('清理本地录音')"><header class="modal-header"><h2>{{ t('清理本地录音') }}</h2><button :disabled="busy" :aria-label="t('关闭')" @click="open=false"><X :size="20"/></button></header><div class="cleanup-content"><h3>{{ title }}</h3><p>{{ t('将删除 {0} 个本地音频文件，释放约 {1}。',{0:info?.files,1:audioSize(info?.bytes||0)}) }}</p><p>{{ t('转录文字、时间戳、原始文字结果、笔记和历史版本会保留。删除后无法在课记中回听或重新转录，此操作无法撤销。') }}</p><p>{{ t('电脑上的原始上传文件及迁移备份不受影响。') }}</p><p v-if="error" class="desktop-error" role="alert">{{ messageText(error) }}</p></div><footer class="modal-footer"><button ref="cancelButton" class="btn secondary" :disabled="busy" @click="open=false">{{ t('取消') }}</button><button class="btn danger confirm-audio-delete" :disabled="busy || !info?.canDelete" @click="remove"><Trash2 :size="14"/>{{ busy?t('正在清理…'):t('确认删除音频') }}</button></footer></section></div></Teleport>
</template>
<style scoped>
.audio-storage-actions{display:flex;align-items:center;justify-content:space-between;gap:15px;margin:-7px 0 20px;padding:0 18px;font-size:11px;color:#839882}.audio-storage-actions>span{display:flex;align-items:center;gap:6px}.audio-storage-actions .text-btn{font-size:11px}.audio-cleanup-modal{max-width:520px}.cleanup-content{padding:0}.cleanup-content h3{margin:0 0 12px}.cleanup-content h3{font-size:16px;color:#4c6654;overflow-wrap:anywhere}.cleanup-content p{font-size:13px;line-height:1.9;color:#82917c}.btn.danger{color:#fff;background:#b7684f}.btn.danger:hover{background:#a65b45}
</style>
