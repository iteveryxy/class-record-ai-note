<script setup lang="ts">
import { t, messageText } from './i18n'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { Send, Sparkles, LoaderCircle, RefreshCw } from 'lucide-vue-next'
import { api, post } from './api'
import { renderMarkdown } from './markdown'
import type { AiTurn, Note } from './types'

const props=defineProps<{note:Note;disabled?:boolean;reason?:string}>()
const emit=defineEmits<{queued:[];complete:[];dirty:[boolean]}>()
const turns=ref<AiTurn[]>([]),message=ref(''),error=ref(''),sending=ref(false),loading=ref(true)
const scroller=ref<HTMLElement>()
const draftKey=`classnotes:ai-draft:${props.note.id}`
let poll:ReturnType<typeof setInterval>,refreshing=false,disposed=false
const pending=computed(()=>turns.value.some(turn=>['QUEUED','RUNNING'].includes(turn.status)))
const blocked=computed(()=>props.disabled||pending.value||sending.value||loading.value)
watch(message,value=>{
  emit('dirty',!!value.trim())
  try { if(value) localStorage.setItem(draftKey,value); else localStorage.removeItem(draftKey) }
  catch { error.value='对话草稿暂时无法保存在本地，请保留当前窗口或复制修改要求。' }
})
async function refresh() {
  if(refreshing||disposed)return
  refreshing=true
  try {
    const latest=await api<AiTurn[]>(`/notes/${props.note.id}/chat`)
    if(disposed)return
    const completed=latest.some(turn=>['SUCCEEDED','FAILED'].includes(turn.status)&&turns.value.some(old=>old.id===turn.id&&['QUEUED','RUNNING'].includes(old.status)))
    if(JSON.stringify(latest)!==JSON.stringify(turns.value)) {
      turns.value=latest;await nextTick()
      scroller.value?.scrollTo({top:scroller.value.scrollHeight})
    }
    if(completed)emit('complete')
  } catch(e) { if(!disposed)error.value=e instanceof Error?e.message:'暂时无法读取对话' }
  finally {refreshing=false;loading.value=false}
}
async function send(retryMessage?:string) {
  if(blocked.value)return
  const text=(retryMessage??message.value).trim()
  if(!text)return
  sending.value=true;error.value=''
  try {
    const turn=await post<AiTurn>(`/notes/${props.note.id}/chat`,{message:text,revision:props.note.revision})
    if(disposed)return
    turns.value.push(turn)
    if(retryMessage===undefined)message.value=''
    emit('queued');await nextTick();scroller.value?.scrollTo({top:scroller.value.scrollHeight})
  } catch(e) {if(!disposed)error.value=e instanceof Error?e.message:'发送失败，修改要求仍在输入框中'}
  finally {sending.value=false}
}
function shortcut(event:KeyboardEvent) {if((event.ctrlKey||event.metaKey)&&event.key==='Enter'&&!event.isComposing){event.preventDefault();send()}}
onMounted(async()=>{
  try {message.value=localStorage.getItem(draftKey)||''}catch{/* Keep the in-memory composer available. */}
  await refresh();if(!disposed)poll=setInterval(refresh,2000)
})
onUnmounted(()=>{disposed=true;clearInterval(poll);emit('dirty',false)})
</script>

<template>
  <section class="ai-chat" :aria-label="t('AI 笔记对话')">
    <header><span class="chat-icon"><Sparkles :size="18"/></span><div><h3>{{ t('和 AI 一起修改笔记') }}</h3><p>{{ t('告诉它改哪里、保留什么。修改自动保存新版本，可在「版本」中恢复。') }}</p></div></header>
    <div ref="scroller" class="chat-history" role="log" :aria-label="t('笔记对话记录')" aria-live="polite">
      <p v-if="loading" class="chat-empty">{{ t('正在读取对话…') }}</p>
      <div v-else-if="!turns.length" class="chat-empty"><strong>{{ t('可以这样说') }}</strong><p>{{ t('「保留所有公式和来源时间，精简重复的概念说明。」') }}</p><p>{{ t('「把解题步骤改成编号列表，易错点完整保留。」') }}</p></div>
      <div v-for="turn in turns" :key="turn.id" class="chat-turn">
        <div class="chat-user"><small>{{ t('你 · 基于 v') }}{{ turn.baseRevision }}</small><p>{{ turn.message }}</p></div>
        <div class="chat-assistant"><small><Sparkles :size="12"/>{{ t('AI 助手') }} <span v-if="turn.status==='SUCCEEDED'">{{ turn.changed?t('已保存 v{0}', {0: turn.resultRevision}):t('笔记未改动') }}</span></small>
          <p v-if="['QUEUED','RUNNING'].includes(turn.status)" class="chat-working"><LoaderCircle :size="14"/>{{ turn.status==='QUEUED'?t('正在排队…'):t('正在按你的要求检查并修改笔记…') }}</p>
          <div v-else-if="turn.status==='FAILED'" class="chat-failed"><p>{{ turn.error || t('本次修改未完成，当前笔记保留。') }}</p><button class="text-btn" :disabled="blocked" @click="send(turn.message)"><RefreshCw :size="12"/>{{ t('重试这条要求') }}</button></div>
          <div v-else class="chat-reply" v-html="renderMarkdown(turn.reply||t('已完成'))"/>
        </div>
      </div>
    </div>
    <div v-if="error" class="chat-error" role="alert">{{ error }}</div>
    <footer><label for="note-ai-message">{{ t('修改要求或问题') }}</label><textarea id="note-ai-message" v-model="message" maxlength="5000" rows="3" :placeholder="t('例如：保留公式和易错点，只修改概念说明…')" :disabled="sending" @keydown="shortcut"/>
      <div class="chat-send-row"><small>{{ props.disabled ? (reason||t('请等待当前任务完成')) : pending ? t('AI 正在处理，完成后可继续对话') : t('Ctrl + Enter 发送 · 未提及的内容保留原文') }}</small><button class="btn primary compact" :disabled="blocked||!message.trim()" @click="send()"><LoaderCircle v-if="sending" :size="14"/><Send v-else :size="14"/>{{ t('发送') }}</button></div>
    </footer>
  </section>
</template>

<style scoped>
.ai-chat{margin:0 20px 20px;border:1px solid #d9e7df;border-radius:13px;overflow:hidden;background:#fcfdfb}.ai-chat header{display:flex;gap:11px;padding:17px 18px;border-bottom:1px solid #e5ece5;background:#f2f7f1}.chat-icon{display:grid;place-items:center;color:#27816b}.ai-chat h3{margin:0 0 5px;color:#365a45;font-size:14px}.ai-chat header p{margin:0;color:#7b8f7c;font-size:11px;line-height:1.7}.chat-history{max-height:340px;min-height:125px;overflow:auto;padding:16px 18px}.chat-empty{color:#809180;font-size:12px;line-height:1.9}.chat-empty strong{color:#58725b}.chat-empty p{margin:5px 0}.chat-turn+.chat-turn{margin-top:20px}.chat-user{margin-left:35px;border-radius:12px 12px 3px 12px;background:#eaf4ed;padding:11px 13px}.chat-user small{font-size:10px;color:#77927d}.chat-user p{white-space:pre-wrap;overflow-wrap:anywhere;margin:6px 0 0;color:#3f6050;font-size:12px;line-height:1.8}.chat-assistant{margin-top:11px;margin-right:15px;font-size:12px;line-height:1.8;color:#536c55;overflow-wrap:anywhere}.chat-assistant>small{display:flex;align-items:center;gap:5px;color:#719079;font-size:10px}.chat-assistant>small span{margin-left:auto}.chat-working{display:flex;align-items:center;gap:7px;color:#8a9b7b}.chat-failed,.chat-error{color:#b25e4d}.chat-failed p{margin:6px 0}.chat-error{padding:0 18px 12px;font-size:12px}.chat-reply :deep(p){margin:7px 0}.chat-reply :deep(pre){max-width:100%;overflow:auto}.ai-chat footer{padding:15px 18px;border-top:1px solid #e2ebe2}.ai-chat label{display:block;color:#67816b;font-size:11px;margin-bottom:7px}.ai-chat textarea{box-sizing:border-box;width:100%;resize:vertical;min-height:78px;max-height:240px;padding:11px 12px;border:1px solid #d5e2d7;border-radius:9px;font-family:inherit;font-size:12px;line-height:1.8;color:#3d5c48;background:#fff}.ai-chat textarea:focus{outline:2px solid #bfddcb;outline-offset:1px}.chat-send-row{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:9px}.chat-send-row small{color:#8a9c82;font-size:10px;line-height:1.6}.chat-send-row button{flex-shrink:0}@media(max-width:650px){.ai-chat{margin-left:12px;margin-right:12px}.chat-send-row{align-items:flex-end}.chat-send-row small{max-width:65%}}
</style>
