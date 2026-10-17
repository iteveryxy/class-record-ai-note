<script setup lang="ts">
import { t, messageText } from './i18n'
import { ref } from 'vue'
import { RotateCcw } from 'lucide-vue-next'
import { post } from './api'
import type { Settings } from './types'
defineProps<{modelValue?:string;disabled?:boolean}>()
const emit=defineEmits<{'update:modelValue':[string];busy:[boolean]}>()
const resetting=ref(false),error=ref(''),notice=ref('')
async function reset(){
  resetting.value=true;emit('busy',true);error.value='';notice.value=''
  try {
    const settings=await post<Settings>('/settings/system-prompt/reset',{})
    emit('update:modelValue',String(settings.llmSystemPrompt));notice.value='已恢复并保存默认 System Prompt，其他配置未改动。'
  }catch(e){error.value=e instanceof Error?e.message:'恢复默认失败，现有提示词仍保留'}
  finally{resetting.value=false;emit('busy',false)}
}
</script>
<template>
  <div class="system-prompt-editor"><div class="prompt-heading"><label for="llm-system-prompt">{{ t('System Prompt · 系统提示词') }}</label><button type="button" class="text-btn" :disabled="disabled||resetting" @click="reset"><RotateCcw :size="13"/>{{ t('恢复默认') }}</button></div>
    <p>{{ t('控制知识提取与 AI 对话的整理要求、表达风格和保留规则。编辑后点击「保存配置」，后续开始的任务使用新提示词。') }}</p>
    <textarea id="llm-system-prompt" :value="modelValue||''" :disabled="disabled||resetting" maxlength="20000" rows="10" spellcheck="false" @input="emit('update:modelValue',($event.target as HTMLTextAreaElement).value)"/>
    <div class="prompt-hint"><small>{{ (modelValue||'').length }} {{ t('/ 20000 字符') }}</small><small>{{ t('恢复默认会立即保存此项；AI 对话的精确修改格式由程序补充。') }}</small></div>
    <p v-if="notice" class="prompt-notice" role="status">{{ notice }}</p><p v-if="error" class="prompt-error" role="alert">{{ error }}</p>
  </div>
</template>
<style scoped>
.system-prompt-editor{margin-top:24px;padding-top:21px;border-top:1px solid #e5ebe1}.prompt-heading{display:flex;align-items:center;justify-content:space-between;gap:15px}.prompt-heading label{color:#59775b;font-size:12px;font-weight:600}.system-prompt-editor>p{font-size:11px;line-height:1.9;color:#889a7a;margin:10px 0 12px}.system-prompt-editor textarea{display:block;box-sizing:border-box;width:100%;padding:13px 15px;resize:vertical;min-height:180px;max-height:700px;border:1px solid #dce6d4;border-radius:9px;background:#fcfdf9;color:#4b654a;font-family:inherit;font-size:12px;line-height:1.9}.system-prompt-editor textarea:focus{outline:2px solid #c5dfc7;outline-offset:1px}.prompt-hint{display:flex;justify-content:space-between;gap:15px;margin-top:8px;flex-wrap:wrap}.prompt-hint small{font-size:10px;color:#91a080}.system-prompt-editor .prompt-notice{color:#368367}.system-prompt-editor .prompt-error{color:#b35f4d}
</style>
