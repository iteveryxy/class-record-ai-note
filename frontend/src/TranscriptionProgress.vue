<script setup lang="ts">
import { t, messageText } from './i18n'
import type { TranscriptionProgress } from './types'
import { time } from './markdown'
defineProps<{ progress: TranscriptionProgress; compact?: boolean }>()
</script>
<template>
  <section :class="['transcription-progress', { compact, failed: progress.phase==='failed', complete: progress.phase==='completed' }]" :aria-label="t('本地转录进度')">
    <div class="progress-caption"><span>{{ compact ? (progress.phase==='queued'?t('等待转录'):progress.phase==='failed'?t('转录中断'):progress.phase==='converting'?t('转换音频'):progress.phase==='loading_model'?t('准备语音模型'):progress.phase==='finalizing'?t('保存转录结果'):t('本地转录')) : progress.message }}</span><strong v-if="progress.percent!=null">{{ progress.percent }}%</strong></div>
    <progress :value="progress.percent ?? undefined" max="100" :aria-label="t('转录完成比例')"/>
    <div v-if="!compact" class="progress-detail"><span v-if="progress.durationMs">{{ t('已识别') }} {{ time(progress.processedMs) }} / {{ time(progress.durationMs) }}</span><span v-if="progress.totalChunks">{{ progress.completedChunks }} / {{ progress.totalChunks }} {{ t('段') }}</span><span v-if="progress.phase==='queued'">{{ t('开始识别后将显示实际进度') }}</span><span v-if="progress.phase==='starting'">{{ t('本地服务准备中') }}</span></div>
  </section>
</template>
<style scoped>
.transcription-progress{padding:18px 22px;border:1px solid #e1eae4;background:#f7faf8;border-radius:10px;margin:0 0 22px;color:#537360}.progress-caption{display:flex;align-items:center;justify-content:space-between;gap:12px;font-size:12px}.progress-caption strong{font-size:13px;font-weight:600;flex-shrink:0;font-variant-numeric:tabular-nums}.transcription-progress progress{display:block;width:100%;height:7px;margin-top:10px;border:0;appearance:none;accent-color:#268670;overflow:hidden;border-radius:5px;background:#e4ece7}.transcription-progress progress::-webkit-progress-bar{background:#e4ece7;border-radius:5px}.transcription-progress progress::-webkit-progress-value{background:#268670;border-radius:5px;transition:width .3s}.transcription-progress progress::-moz-progress-bar{background:#268670;border-radius:5px}.transcription-progress progress:indeterminate{background:linear-gradient(90deg,#e4ece7 0 30%,#8abda7 48%,#e4ece7 68%);background-size:200% 100%;animation:transcription-preparing 1.8s ease-in-out infinite}.progress-detail{display:flex;gap:18px;flex-wrap:wrap;margin-top:8px;font-size:11px;color:#819289;font-variant-numeric:tabular-nums}.compact{min-width:145px;max-width:210px;padding:0;margin:9px 0 0;background:transparent;border:0;border-radius:0}.compact .progress-caption{font-size:10px;gap:7px}.compact .progress-caption strong{font-size:11px}.compact progress{height:4px;margin-top:5px}.failed{color:#ad6a59}.failed progress{accent-color:#bc7b68}.failed progress::-webkit-progress-value{background:#bc7b68}.failed progress::-moz-progress-bar{background:#bc7b68}.failed progress:indeterminate{animation:none;background:#e9ddd7}@keyframes transcription-preparing{to{background-position:-200% 0}}@media(prefers-reduced-motion:reduce){.transcription-progress progress{animation:none!important;transition:none!important}}
</style>
