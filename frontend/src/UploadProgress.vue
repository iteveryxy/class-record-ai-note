<script setup lang="ts">
import { t, messageText } from './i18n'
import type { UploadProgress } from './api'
import { size } from './markdown'
defineProps<{ progress: UploadProgress }>()
</script>
<template>
  <section class="upload-progress" :aria-label="t('录音上传进度')" aria-live="polite">
    <div><span>{{ progress.waitingForServer ? t('文件已发送，正在保存录音…') : t('正在上传录音') }}</span><strong v-if="progress.percent!==null">{{ progress.percent }}%</strong></div>
    <progress :value="progress.percent ?? undefined" max="100" :aria-label="t('上传完成比例')"/>
    <small>{{ size(progress.loaded) }}<template v-if="progress.total"> / {{ size(progress.total) }}</template><span>{{ progress.waitingForServer ? t('保存完成后自动开始转录') : t('请保持窗口打开') }}</span></small>
  </section>
</template>
<style scoped>
.upload-progress{margin-top:18px;padding:15px 17px;background:#f2f8f5;border-radius:8px;color:#537360}.upload-progress>div{display:flex;justify-content:space-between;align-items:center;gap:10px;font-size:12px}.upload-progress strong{font-weight:600;font-variant-numeric:tabular-nums}.upload-progress progress{display:block;width:100%;height:7px;margin:11px 0 9px;border:0;appearance:none;accent-color:#268670;background:#dfeae3;border-radius:5px;overflow:hidden}.upload-progress progress::-webkit-progress-bar{background:#dfeae3;border-radius:5px}.upload-progress progress::-webkit-progress-value{background:#268670;transition:width .15s;border-radius:5px}.upload-progress progress::-moz-progress-bar{background:#268670;border-radius:5px}.upload-progress small{display:flex;justify-content:space-between;gap:10px;flex-wrap:wrap;font-size:11px;color:#819289;font-variant-numeric:tabular-nums}@media(prefers-reduced-motion:reduce){.upload-progress progress{transition:none}}
</style>
