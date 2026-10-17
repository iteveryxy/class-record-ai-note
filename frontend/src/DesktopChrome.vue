<script setup lang="ts">
import { t, messageText } from './i18n'
import { ref, onMounted, onUnmounted } from 'vue'
import { AudioLines, Minus, Square, X } from 'lucide-vue-next'
import { desktop } from './desktop'
const status=ref('本地工作空间')
let unsubscribe: (()=>void)|undefined
onMounted(async()=>{
  if(!desktop) return
  document.documentElement.classList.add('desktop-mode')
  status.value=(await desktop.info()).service.message
  unsubscribe=desktop.onState(({service})=>{if(service) status.value=service.message})
})
onUnmounted(()=>unsubscribe?.())
</script>
<template>
  <header v-if="desktop" class="desktop-titlebar"><div class="desktop-caption"><AudioLines :size="17"/><strong>{{ t('课记') }}</strong><span>{{ t('课堂录音 AI 笔记') }}</span></div><div class="desktop-service"><i/>{{ status }}</div><div class="window-buttons"><button :aria-label="t('最小化')" @click="desktop.minimize()"><Minus :size="15"/></button><button :aria-label="t('最大化或还原')" @click="desktop.maximize()"><Square :size="12"/></button><button :aria-label="t('关闭课记')" class="window-close" @click="desktop.close()"><X :size="17"/></button></div></header>
</template>
