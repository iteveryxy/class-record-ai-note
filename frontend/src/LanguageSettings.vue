<script setup lang="ts">
import { ref } from 'vue'
import { Languages } from 'lucide-vue-next'
import { changeLanguage,languagePreference,locale,messageText,t,type LanguagePreference } from './i18n'
const saving=ref(false),error=ref('')
async function change(event:Event){
  const field=event.target as HTMLSelectElement
  saving.value=true;error.value=''
  try{await changeLanguage(field.value as LanguagePreference)}
  catch{field.value=languagePreference.value;error.value='无法保存语言设置，请重试'}
  finally{saving.value=false}
}
</script>
<template>
  <section class="settings-card language-settings">
    <div class="settings-section-title"><span class="stat-icon blue"><Languages :size="21"/></span><div><h3>{{ t('界面语言') }}</h3><p>{{ t('立即切换并自动保存，下次启动继续使用。') }}</p></div></div>
    <label for="interface-language">{{ t('语言') }}<select id="interface-language" :value="languagePreference" :disabled="saving" @change="change"><option value="system">{{ t('跟随系统') }}</option><option value="zh-CN">简体中文</option><option value="en">English</option></select></label>
    <p class="language-hint">{{ t('当前语言：{0}',{0:locale==='zh-CN'?'简体中文':'English'}) }} · {{ t('只改变界面语言，录音、转录、笔记和 System Prompt 保持原样。') }}</p>
    <p v-if="error" class="language-error" role="alert">{{ messageText(error) }}</p>
  </section>
</template>
<style scoped>
.language-settings label{display:flex;align-items:center;gap:18px;color:#58765b;font-size:12px}.language-settings select{min-width:200px;max-width:100%;padding:10px 13px;border:1px solid #dce6d4;border-radius:8px;background:#fcfdf9;color:#4b654a;font:inherit}.language-hint{font-size:11px;line-height:1.9;color:#889a7a;margin:13px 0 0}.language-error{color:#b35f4d;font-size:12px}
</style>
