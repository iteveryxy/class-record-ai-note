<script setup lang="ts">
import { t, messageText } from './i18n'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ArrowRight, FolderInput, LoaderCircle, X } from 'lucide-vue-next'
import { put } from './api'
import type { Recording } from './types'

const props=defineProps<{recording:Recording;courses:string[]}>()
const emit=defineEmits<{close:[];moved:[Recording]}>()
const target=ref(''),creating=ref(!props.courses.some(c=>c!==props.recording.course)),saving=ref(false),error=ref('')
const dialog=ref<HTMLElement>(),field=ref<HTMLElement>()
const choices=computed(()=>props.courses.filter(c=>c!==props.recording.course))
const valid=computed(()=>!!target.value.trim()&&target.value.trim()!==props.recording.course&&target.value.trim().length<=120)
const previousFocus=document.activeElement as HTMLElement|null
async function move(){
  if(!valid.value||saving.value)return
  saving.value=true;error.value=''
  try{
    const moved=await put<Recording>(`/recordings/${props.recording.id}/course`,{course:target.value.trim(),expectedCourse:props.recording.course})
    emit('moved',moved)
  }catch(e){error.value=e instanceof Error?e.message:'移动失败，请重试'}
  finally{saving.value=false}
}
function keydown(event:KeyboardEvent){
  if(event.key==='Escape'){event.preventDefault();if(!saving.value)emit('close')}
  if(event.key!=='Tab')return
  const fields=Array.from(dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled),input,select')||[])
  const first=fields[0],last=fields[fields.length-1]
  if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus()}
  else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus()}
}
onMounted(()=>field.value?.focus())
onUnmounted(()=>previousFocus?.isConnected&&previousFocus.focus())
</script>

<template>
  <div class="modal-overlay" @click.self="!saving&&emit('close')" @keydown="keydown">
    <section ref="dialog" class="modal move-course-dialog" role="dialog" aria-modal="true" aria-labelledby="move-course-title" :aria-busy="saving">
      <header><div><h2 id="move-course-title">{{ t('移动课程') }}</h2><p class="move-recording-title">{{ recording.title }}</p></div><button type="button" class="icon-btn" :aria-label="t('关闭移动课程')" :disabled="saving" @click="emit('close')"><X :size="20"/></button></header>
      <form @submit.prevent="move">
        <div class="move-current"><span>{{ t('当前课程') }}</span><strong>{{ recording.course }}</strong><ArrowRight :size="17"/></div>
        <label for="move-course-target">{{ t('目标课程') }}</label>
        <input v-if="creating" id="move-course-target" ref="field" v-model="target" required maxlength="120" :disabled="saving" :placeholder="t('输入新课程名称')"/>
        <select v-else id="move-course-target" ref="field" v-model="target" required :disabled="saving"><option value="" disabled>{{ t('请选择目标课程') }}</option><option v-for="c in choices" :key="c" :value="c">{{ c }}</option></select>
        <button v-if="choices.length" type="button" class="text-btn move-mode" :disabled="saving" @click="creating=!creating;target='';error=''">{{ creating?t('选择已有课程'):t('输入新课程') }}</button>
        <p class="move-course-hint">{{ t('录音、转录、笔记和历史记录会一起归档。无需重新处理录音。') }}</p>
        <p v-if="recording.noteId" class="move-course-hint">{{ t('已同步到 Notion 的笔记，移动后请再点一次同步，将更新原来的页面。') }}</p>
        <p v-if="error" class="move-course-error" role="alert">{{ error }}</p>
        <footer><button type="button" class="btn secondary" :disabled="saving" @click="emit('close')">{{ t('取消') }}</button><button type="submit" class="btn primary" :disabled="saving||!valid"><LoaderCircle v-if="saving" class="spin" :size="16"/><FolderInput v-else :size="16"/>{{ saving?t('正在移动…'):t('确认移动') }}</button></footer>
      </form>
    </section>
  </div>
</template>

<style scoped>
.move-course-dialog{max-width:470px}.move-recording-title{overflow-wrap:anywhere}.move-current{display:flex;align-items:center;gap:14px;padding:14px 16px;margin:4px 0 23px;background:#f3f7ef;border-radius:9px;font-size:12px;color:#829475}.move-current strong{color:#4e745b;flex:1;overflow-wrap:anywhere}.move-course-dialog label{display:block;color:#58765b;font-size:12px;font-weight:600;margin-bottom:9px}.move-course-dialog input,.move-course-dialog select{box-sizing:border-box;width:100%;border:1px solid #dce6d4;border-radius:8px;padding:12px;color:#4b654a;background:#fcfdf9;font:inherit;font-size:13px}.move-course-dialog input:focus,.move-course-dialog select:focus{outline:2px solid #c5dfc7;outline-offset:1px}.move-mode{margin-top:10px}.move-course-hint{color:#869777;font-size:12px;line-height:1.9;margin:17px 0 0}.move-course-error{color:#b35f4d;font-size:12px;line-height:1.8}.move-course-dialog footer{display:flex;justify-content:flex-end;gap:10px;margin-top:25px}
</style>
