<script setup lang="ts">
import { t, messageText, locale } from './i18n'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { AudioLines, BookOpen, Upload, Search, SlidersHorizontal, Settings as SettingsIcon, ChevronRight,
  ArrowLeft, FileAudio, MoreHorizontal, Check, Clock3, CircleAlert, LoaderCircle, X, Plus,
  FileText, FolderOpen, Sparkles, CalendarDays, Download, Pencil, Save, RefreshCw, ExternalLink,
  History, ShieldCheck, ArrowUpRight, CheckCircle2, CloudUpload, ListFilter, Layers, HardDrive, Play, FolderInput } from 'lucide-vue-next'
import { api, post, put, upload, type UploadProgress } from './api'
import { renderMarkdown, time, size } from './markdown'
import type { Recording, Detail, Note, Stage, Settings, Artifact, Version } from './types'
import DesktopChrome from './DesktopChrome.vue'
import DesktopSettings from './DesktopSettings.vue'
import TranscriptionProgress from './TranscriptionProgress.vue'
import UploadProgressView from './UploadProgress.vue'
import AiNoteChat from './AiNoteChat.vue'
import SystemPromptEditor from './SystemPromptEditor.vue'
import MoveCourseDialog from './MoveCourseDialog.vue'
import LanguageSettings from './LanguageSettings.vue'
import { desktop } from './desktop'

type Page = 'recordings' | 'notes' | 'history' | 'settings'
const page = ref<Page>('recordings')
const rows = ref<Recording[]>([])
const searchIds = ref<Set<string>>()
const settings = ref<Settings>({})
const config = ref<Record<string, string>>({})
const selected = ref<Detail>()
const selectedId = ref('')
const tab = ref('note')
const editing = ref(false)
const chatOpen=ref(false),chatDirty=ref(false),promptResetting=ref(false)
const draft = ref({ title: '', markdown: '', category: '课堂笔记', revision: 0 })
const q = ref(''), course = ref(''), category = ref(''), date = ref('')
const loading = ref(true), busy = ref(false), uploading = ref(false)
const uploadProgress = ref<UploadProgress>({loaded:0,total:null,percent:0,waitingForServer:false})
const error = ref(''), notice = ref('')
const uploadOpen = ref(false), resetOpen = ref(false), filtersOpen = ref(false)
const moveTarget = ref<Recording>()
const file = ref<File>(), fileInput = ref<HTMLInputElement>(), audio = ref<HTMLAudioElement>()
const uploadForm = ref({ title: '', course: '', recordedOn: localDate(), category: '课堂笔记' })
const artifacts = ref<Artifact[]>([]), version = ref<Version>(), transcriptVersion = ref('')
const serviceOnline = ref(true)
let poll: ReturnType<typeof setInterval>, noticeTimer: ReturnType<typeof setTimeout>
let refreshing = false
function localDate() { const now = new Date(); return `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')}` }
const pageNames: Record<Page, string> = { recordings: '录音库', notes: '笔记库', history: '处理记录', settings: '系统设置' }
const courses = computed(() => [...new Set(rows.value.map(r => r.course))].sort())
const categories = computed(() => [...new Set(['课堂笔记', '重点复习', '习题整理', ...rows.value.map(r => r.category)])])
const filtered = computed(() => rows.value.filter(r => {
  return (page.value !== 'notes' || r.noteId) && (!course.value || r.course === course.value) && (!category.value || r.category === category.value)
    && (!date.value || r.recordedOn === date.value) && (!searchIds.value || searchIds.value.has(r.id))
}))
const groupDates = computed(() => [...new Set(filtered.value.map(r => r.recordedOn))])
const pending = computed(() => rows.value.filter(r => ['QUEUED','RUNNING'].includes(r.transcriptStatus) || ['QUEUED','RUNNING'].includes(r.summaryStatus)).length)
const failed = computed(() => rows.value.filter(r => [r.transcriptStatus, r.summaryStatus, r.syncStatus].includes('FAILED')).length)
const notesCount = computed(() => rows.value.filter(r => r.noteId).length)
const totalDuration = computed(() => Math.round(rows.value.reduce((n,r) => n+(r.durationMs || 0),0)/60000))
const active = computed(() => !!selected.value?.jobs.some(j => ['QUEUED','RUNNING'].includes(j.status)))
const currentNote = computed(() => selected.value?.note)
const confirmed = computed(() => !!currentNote.value && currentNote.value.confirmedRevision === currentNote.value.revision)
const dirty = computed(() => !!currentNote.value && (draft.value.markdown !== currentNote.value.markdown || draft.value.title !== currentNote.value.title || draft.value.category !== currentNote.value.category))
const shownTranscript = computed(() => selected.value?.transcripts.find(t => t.id === transcriptVersion.value) || selected.value?.transcript)
const markdown = computed(() => renderMarkdown(editing.value ? draft.value.markdown : version.value?.markdown || currentNote.value?.markdown || ''))
const stageName: Record<Stage, string> = { TRANSCRIBE: '本地转录', SUMMARIZE: '知识整理', REFINE:'AI 对话修改', SYNC: 'Notion 同步' }
const statusName: Record<string, string> = { UPLOADED: '已上传', PENDING: '待处理', QUEUED: '排队中', RUNNING: '处理中', SUCCEEDED: '已完成', FAILED: '处理失败' }
function overall(r: Recording) {
  if ([r.transcriptStatus,r.summaryStatus,r.syncStatus].includes('FAILED')) return { label: '处理失败', class: 'danger' }
  if (r.transcriptStatus === 'RUNNING') return { label: '转录中', class: 'working' }
  if (r.summaryStatus === 'RUNNING') return { label: '整理中', class: 'working' }
  if (r.syncStatus === 'RUNNING') return { label: '同步中', class: 'working' }
  if ([r.transcriptStatus,r.summaryStatus,r.syncStatus].includes('QUEUED')) return { label: '排队中', class: 'waiting' }
  if (r.noteId && r.summaryStatus === 'PENDING' && !r.confirmed) return { label: '待重新核对', class: 'waiting' }
  if (r.noteId) return { label: r.syncStatus === 'SUCCEEDED' ? '已同步' : r.confirmed ? '已确认' : '笔记已生成', class: 'success' }
  return { label: r.transcriptStatus === 'SUCCEEDED' ? '待整理' : '已上传', class: 'waiting' }
}
function toast(text: string) { notice.value = text; clearTimeout(noticeTimer); noticeTimer = setTimeout(() => notice.value = '', 4500) }
function canMove(r:Recording){return ![r.transcriptStatus,r.summaryStatus,r.syncStatus].some(s=>['QUEUED','RUNNING'].includes(s))}
function openMove(r:Recording){moveTarget.value={...r}}
async function movedCourse(r:Recording){
  const source=moveTarget.value?.course
  moveTarget.value=undefined
  rows.value=rows.value.map(row=>row.id===r.id?r:row)
  if(selected.value?.recording.id===r.id)selected.value.recording=r
  if(course.value===source)course.value=r.course
  toast(`已移至「${r.course}」，录音与笔记已保留`)
  while(refreshing)await new Promise(resolve=>setTimeout(resolve,50))
  await refresh()
}
async function attempt(action: () => Promise<void>) { busy.value = true; error.value = ''; try { await action() } catch (e) { error.value = e instanceof Error ? e.message : '操作失败' } finally { busy.value = false } }
async function refresh() {
  if (refreshing) return
  refreshing = true
  try {
    // Full-text search uses SQLite, including Markdown beyond the card excerpt.
    const queryText = q.value
    const query = new URLSearchParams(queryText ? { q: queryText } : {})
    const [all, matches] = await Promise.all([api<Recording[]>('/recordings'), queryText ? api<Recording[]>(`/recordings?${query}`) : Promise.resolve(null)])
    rows.value = all
    if (q.value === queryText) searchIds.value = matches ? new Set(matches.map(r => r.id)) : undefined
    serviceOnline.value = true
    if (selectedId.value) {
      const id = selectedId.value
      const detail = await api<Detail>(`/recordings/${id}`)
      if (selectedId.value === id) {
        const wasDirty = dirty.value
        selected.value = detail
        if (!editing.value && !wasDirty) setDraft(detail.note)
      }
    }
  } catch (e) { serviceOnline.value = false; if (loading.value) error.value = e instanceof Error ? e.message : '无法连接后端' }
  finally { refreshing = false; loading.value = false }
}
function setDraft(note?: Note) { if (note) draft.value = { title: note.title, markdown: note.markdown, category: note.category, revision: note.revision } }
function localDraftKey(id: string) { return `classnotes:draft:${id}` }
function persistDraft() { if (currentNote.value && editing.value) { try { localStorage.setItem(localDraftKey(currentNote.value.id), JSON.stringify(draft.value)) } catch { /* keep editor text even if local storage is full */ } } }
watch(draft, persistDraft, { deep: true })
watch([dirty,editing,chatDirty],()=>desktop?.setDirty((dirty.value && editing.value)||chatDirty.value))
watch(uploading,value=>desktop?.setUploading?.(value))
async function openRecording(id: string) {
  chatOpen.value=false
  selectedId.value = id; tab.value = page.value === 'history' ? 'logs' : 'note'; editing.value = false; version.value = undefined; artifacts.value = []; transcriptVersion.value = ''
  await attempt(async () => { selected.value = await api<Detail>(`/recordings/${id}`); setDraft(selected.value.note) })
}
function beginEdit() {
  version.value = undefined
  if (!currentNote.value) return
  setDraft(currentNote.value)
  try { const saved = localStorage.getItem(localDraftKey(currentNote.value.id)); if (saved) { draft.value = JSON.parse(saved); toast('已恢复此笔记的本地草稿；保存时会检查版本') } } catch { /* malformed local draft does not block editing */ }
  editing.value = true
}
function leaveDetail() { persistDraft(); selected.value = undefined; selectedId.value = ''; editing.value = false; chatOpen.value=false; error.value = '' }
function navigate(next: Page) { leaveDetail(); page.value = next; course.value = ''; category.value = ''; date.value = ''; q.value = ''; if (next === 'settings') loadSettings() }
async function saveNote() {
  if (!currentNote.value) return
  await attempt(async () => {
    const note = await put<Note>(`/notes/${currentNote.value!.id}`, draft.value)
    selected.value!.note = note; localStorage.removeItem(localDraftKey(note.id)); editing.value = false; setDraft(note)
    toast('笔记已保存，需要确认新版本后同步'); await refresh()
  })
}
async function confirmNote() {
  if (!currentNote.value) return
  await attempt(async () => { selected.value!.note = await post<Note>(`/notes/${currentNote.value!.id}/confirm`, { revision: currentNote.value!.revision }); toast('已确认当前笔记'); await refresh() })
}
async function runStage(stage: Stage) {
  await attempt(async () => { await post(`/recordings/${selectedId.value}/jobs`, { stage }); toast(t('{0}已加入队列',{0:t(stageName[stage])})); await refresh() })
}
function pickFile(event: Event) { const input = event.target as HTMLInputElement; if (input.files?.[0]) chooseFile(input.files[0]) }
function chooseFile(chosen: File) {
  if (uploading.value) return
  error.value = ''
  if (!/\.(wav|m4a)$/i.test(chosen.name)) { error.value = '请选择 M4A 或 WAV 音频'; return }
  if (chosen.size > 1024**3 || !chosen.size) { error.value = '文件须非空且不超过 1GB'; return }
  file.value = chosen; if (!uploadForm.value.title) uploadForm.value.title = chosen.name.replace(/\.[^.]+$/, '')
}
function dropFile(event: DragEvent) { if (event.dataTransfer?.files[0]) chooseFile(event.dataTransfer.files[0]) }
async function uploadFile() {
  if (!file.value) return
  uploading.value = true
  uploadProgress.value = {loaded:0,total:null,percent:0,waitingForServer:false}
  await attempt(async () => {
    const form = new FormData(); form.append('file', file.value!); Object.entries(uploadForm.value).forEach(([k,v]) => form.append(k,v))
    const rec = await upload<Recording>('/recordings',form,value=>uploadProgress.value=value)
    uploadOpen.value = false; file.value = undefined; uploadForm.value = { title: '', course: '', recordedOn: localDate(), category: '课堂笔记' }
    toast('录音已上传，本地转录已加入队列'); await refresh(); await openRecording(rec.id)
  })
  uploading.value = false
}
function openUpload() { uploadOpen.value = true; error.value = ''; file.value = undefined }
async function loadSettings() {
  await attempt(async () => { settings.value = await api<Settings>('/settings'); config.value = Object.fromEntries(Object.entries(settings.value).filter(([k,v]) => !k.endsWith('Configured') && typeof v === 'string').map(([k,v]) => [k, String(v)]))
    config.value.llmApiKey = ''; config.value.notionToken = ''; config.value.workerToken = '' })
}
async function saveSettings() { await attempt(async () => { settings.value = await put<Settings>('/settings', config.value); config.value.llmApiKey = ''; config.value.notionToken = ''; config.value.workerToken = ''; toast('配置已保存，后续任务将使用新配置') }) }
async function showArtifacts() { tab.value = 'raw'; await attempt(async () => { artifacts.value = await api<Artifact[]>(`/recordings/${selectedId.value}/artifacts`) }) }
function seek(ms: number) { if (audio.value) { audio.value.currentTime = ms / 1000; audio.value.play().catch(() => toast('音频暂时无法播放，请检查原文件')) } }
function formatDate(value: string) { return new Date(value).toLocaleString(locale.value, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }
function versionPreview(v: Version) { version.value = v; editing.value = false; tab.value = 'note' }
async function restoreVersion() { if (!version.value || !currentNote.value) return; setDraft(currentNote.value); draft.value = { ...draft.value, title: version.value.title, markdown: version.value.markdown, category: version.value.category }; version.value = undefined; editing.value = true; toast('历史内容已载入编辑器，保存将生成新版本') }
async function resetCreation() { await attempt(async () => { await post(`/notes/${currentNote.value!.id}/reset-notion-creation`, { revision: currentNote.value!.revision }); resetOpen.value = false; toast('创建状态已重置，可重新发起同步') }) }
watch(q, () => { refresh() })
onMounted(async () => { await refresh(); try { settings.value = await api<Settings>('/settings') } catch { /* status banner handles offline */ } poll = setInterval(refresh, 2500) })
onUnmounted(() => { clearInterval(poll); clearTimeout(noticeTimer) })
</script>

<template>
  <DesktopChrome/>
  <div class="app-shell">
    <aside class="sidebar">
      <button class="brand" @click="navigate('recordings')"><span class="brand-icon"><AudioLines :size="23"/></span><span>{{ t('课记') }}<span class="brand-sub">CLASS NOTES</span></span></button>
      <div class="workspace-label">{{ t('我的学习空间') }} <span>MVP</span></div>
      <nav :aria-label="t('主导航')">
        <button v-for="item in ([['recordings', t('录音库'), AudioLines], ['notes', t('笔记库'), BookOpen], ['history', t('处理记录'), History]] as const)" :key="item[0]" :class="{ active: page === item[0] }" @click="navigate(item[0])"><component :is="item[2]" :size="18"/><span>{{ item[1] }}</span><span v-if="item[0] === 'recordings'" class="nav-count">{{ rows.length }}</span><span v-if="item[0] === 'notes'" class="nav-count">{{ notesCount }}</span></button>
      </nav>
      <div class="course-label">{{ t('我的课程') }} <FolderOpen :size="14"/></div>
      <div class="course-list"><button v-for="(c,index) in courses" :key="c" :class="{ selected: course === c }" @click="navigate('recordings'); course = c"><i :class="`course-dot dot-${index % 4}`"/><span>{{ c }}</span><ChevronRight :size="14"/></button><p v-if="!courses.length" class="muted small">{{ t('上传录音后，课程会显示在这里') }}</p></div>
      <div class="sidebar-bottom"><div class="local-card"><ShieldCheck :size="20"/><div><strong>{{ t('录音留在本地') }}</strong><small>{{ t('转录结果由你掌握') }}</small></div><span class="online-dot"/></div><button class="settings-nav" :class="{ active: page === 'settings' }" @click="navigate('settings')"><SettingsIcon :size="18"/>{{ t('系统设置') }}</button><div class="profile"><span class="avatar">{{ t('我') }}</span><div><strong>{{ t('个人工作空间') }}</strong><small>{{ t('专注每一堂课') }}</small></div><MoreHorizontal :size="18"/></div></div>
    </aside>
    <div class="main-shell">
      <header class="topbar"><div class="breadcrumb"><span>{{ t('学习空间') }}</span><ChevronRight :size="14"/><button @click="leaveDetail()">{{ t(pageNames[page]) }}</button><template v-if="selected"><ChevronRight :size="14"/><span>{{ t('录音详情') }}</span></template></div><div class="top-right"><span v-if="settings.demo" class="demo-pill">{{ t('演示模式') }}</span><span class="local-badge"><HardDrive :size="14"/> {{ t('本地工作空间') }}</span><span class="avatar small-avatar">{{ t('我') }}</span></div></header>
      <div v-if="!serviceOnline" class="offline-banner"><CircleAlert :size="16"/>{{ t('后端连接中断，正在尝试恢复。编辑中的内容仍保留在页面和本地草稿中。') }}<button @click="refresh">{{ t('重新连接') }}</button></div>
      <div v-if="error && !uploadOpen && !resetOpen" class="error-banner" role="alert"><CircleAlert :size="17"/>{{ messageText(error) }}<button :aria-label="t('关闭错误')" @click="error = ''"><X :size="16"/></button></div>
      <main v-if="!selectedId && page !== 'settings'" class="workspace">
        <div class="page-heading"><div><div class="eyebrow">{{ t('让每一堂课，都有迹可循') }}</div><h1>{{ page === 'notes' ? t('我的课堂笔记') : page === 'history' ? t('处理记录') : t('我的课堂录音') }}</h1><p>{{ page === 'notes' ? t('整理知识，留下思考。按课程与日期回顾每一次学习。') : page === 'history' ? t('转录、知识整理与同步分别记录，失败后可独立重试。') : t('从课堂声音到清晰笔记，把时间留给理解。') }}</p></div><button class="btn primary" @click="openUpload"><Plus :size="18"/>{{ t('上传录音') }}</button></div>
        <section class="stats" :aria-label="t('录音统计')"><div><span class="stat-icon mint"><FileAudio :size="20"/></span><span><small>{{ t('累计录音') }}</small><strong>{{ rows.length }}<em>{{ t('份') }}</em></strong></span></div><div><span class="stat-icon lavender"><BookOpen :size="20"/></span><span><small>{{ t('已生成笔记') }}</small><strong>{{ notesCount }}<em>{{ t('篇') }}</em></strong></span></div><div><span class="stat-icon blue"><Clock3 :size="20"/></span><span><small>{{ t('累计课堂时长') }}</small><strong>{{ totalDuration }}<em>{{ t('分钟') }}</em></strong></span></div><div><span class="stat-icon sand"><Layers :size="20"/></span><span><small>{{ t('处理中 / 失败') }}</small><strong>{{ pending }}<em>/ {{ failed }}</em></strong></span></div></section>
        <section class="workflow-card"><div class="sparkle-tile"><Sparkles :size="23"/></div><div class="workflow-copy"><strong>{{ t('听见课堂，读懂知识') }}</strong><span>{{ t('自动提取概念、公式与解题思路，每个知识点都能回到原始录音。') }}</span></div><div class="workflow-steps"><span>{{ t('上传录音') }}</span><ChevronRight :size="13"/><span>{{ t('本地转录') }}</span><ChevronRight :size="13"/><span>{{ t('知识整理') }}</span><ChevronRight :size="13"/><span>{{ t('确认 & 同步') }}</span></div></section>
        <div v-if="settings.demo" class="demo-caption"><CircleAlert :size="14"/>{{ t('当前为演示数据，上传后返回固定示例；正式模式才会分析实际音频。') }}</div>
        <section class="library-panel"><div class="library-toolbar"><div class="library-title">{{ t(pageNames[page]) }}<span>{{ filtered.length }}</span></div><div class="search-tools"><label class="search"><Search :size="16"/><input v-model="q" :placeholder="t('搜索录音、课程或笔记内容…')" :aria-label="t('搜索')"/><kbd>/</kbd></label><select v-model="course" :aria-label="t('按课程筛选')"><option value="">{{ t('全部课程') }}</option><option v-for="c in courses" :key="c">{{ c }}</option></select><button class="icon-btn filter-btn" :class="{ active: filtersOpen }" :aria-label="t('更多筛选')" @click="filtersOpen = !filtersOpen"><SlidersHorizontal :size="17"/></button></div></div>
          <div v-if="filtersOpen" class="filter-row"><ListFilter :size="16"/><label>{{ t('分类') }} <select v-model="category"><option value="">{{ t('全部分类') }}</option><option v-for="c in categories" :key="c">{{ c }}</option></select></label><label>{{ t('上课日期') }} <input v-model="date" type="date"/></label><button class="text-btn" @click="course = ''; category = ''; date = ''; q = ''">{{ t('清除筛选') }}</button></div>
          <div v-if="loading" class="empty-state"><LoaderCircle class="spin" :size="30"/><h3>{{ t('正在加载录音…') }}</h3></div>
          <div v-else-if="!filtered.length" class="empty-state"><span class="empty-icon"><AudioLines :size="34"/></span><h3>{{ rows.length ? t('没有找到匹配的内容') : t('你的第一份课堂笔记，从这里开始') }}</h3><p>{{ rows.length ? t('试试其他关键词，或清除筛选条件。') : t('上传 M4A 或 WAV 录音，课记会为你整理知识。') }}</p><button v-if="!rows.length" class="btn primary" @click="openUpload"><Upload :size="16"/>{{ t('上传课堂录音') }}</button></div>
          <template v-else-if="page === 'notes'"><div class="notes-grid"><button v-for="r in filtered" :key="r.id" class="note-card" @click="openRecording(r.id)"><div><span class="course-chip">{{ r.course }}</span><ArrowUpRight :size="18"/></div><h3>{{ r.noteTitle || r.title }}</h3><p>{{ (r.preview || '').replace(/[#*$>]/g, '').slice(0,130) }}</p><footer><span><CalendarDays :size="13"/>{{ r.recordedOn }}</span><span class="tag">{{ r.category }}</span></footer></button></div></template>
          <template v-else><div class="table-scroll"><table><thead><tr><th class="title-column">{{ page === 'history' ? t('录音 / 最近状态') : t('录音名称') }}</th><th>{{ t('课程') }}</th><th>{{ t('上课日期') }}</th><th>{{ t('时长') }}</th><th>{{ t('状态') }}</th><th class="action-column"/></tr></thead><tbody><tr v-for="r in filtered" :key="r.id" @click="openRecording(r.id)" @keydown.enter="openRecording(r.id)" tabindex="0"><td><div class="recording-title"><span class="audio-file-icon"><AudioLines :size="22"/></span><div><strong>{{ r.title }}</strong><small>{{ r.filename }} <span>·</span> {{ size(r.size) }}</small></div></div></td><td><span class="course-chip">{{ r.course }}</span></td><td class="table-date">{{ r.recordedOn }}</td><td class="table-date">{{ r.durationMs ? time(r.durationMs) : '—' }}</td><td><span :class="['status-pill', overall(r).class]"><LoaderCircle v-if="overall(r).class === 'working'" :size="12" class="spin"/><i v-else/>{{ t(overall(r).label) }}</span><TranscriptionProgress v-if="r.transcriptionProgress && ['QUEUED','RUNNING','FAILED'].includes(r.transcriptStatus)" :progress="r.transcriptionProgress" compact/></td><td><div class="recording-row-actions"><button class="text-btn row-move" :aria-label="t('移动{0}的课程', {0: r.title})" :title="canMove(r) ? t('移动课程') : t('请等待当前任务完成后移动')" :disabled="busy || !canMove(r)" @click.stop="openMove(r)" @keydown.enter.stop><FolderInput :size="15"/>{{ t('移动') }}</button><button class="row-arrow" :aria-label="t('查看{0}', {0: r.title})" @click.stop="openRecording(r.id)" @keydown.enter.stop><ChevronRight :size="17"/></button></div></td></tr></tbody></table></div></template>
          <footer v-if="filtered.length" class="table-footer"><span>{{ t(page==='notes'?'共 {0} 份笔记 · 按上课日期归档':'共 {0} 份录音 · 按上课日期归档',{0:filtered.length}) }}</span><span><ShieldCheck :size="13"/>{{ t('原始音频与处理结果保存在本地') }}</span></footer>
        </section>
        <section v-if="page === 'notes' && groupDates.length" class="archive-index"><CalendarDays :size="17"/><span>{{ t('日期归档') }}</span><button v-for="d in groupDates" :key="d" @click="date = date === d ? '' : d">{{ d }}</button></section>
        <div class="workspace-footnote">{{ t('好笔记的起点，是认真听过的每一堂课。') }}</div>
      </main>

      <main v-else-if="selected" class="workspace detail-workspace">
        <button class="back-link" @click="leaveDetail"><ArrowLeft :size="15"/>{{ t('返回{0}',{0:t(pageNames[page])}) }}</button>
        <div class="page-heading detail-heading"><div><div class="detail-meta"><span class="course-chip">{{ selected.recording.course }}</span><span><CalendarDays :size="14"/>{{ selected.recording.recordedOn }}</span><span>{{ selected.recording.category }}</span></div><h1>{{ selected.recording.title }}</h1><p>{{ selected.recording.filename }} · {{ size(selected.recording.size) }} <span v-if="selected.recording.durationMs">· {{ time(selected.recording.durationMs) }}</span></p></div><div class="detail-actions"><button class="btn secondary" :disabled="busy || active" :title="active ? t('请等待当前任务完成后移动') : t('移动课程')" @click="openMove(selected.recording)"><FolderInput :size="16"/>{{ t('移动课程') }}</button><a v-if="currentNote" class="btn secondary" :href="`/api/notes/${currentNote.id}/markdown`" download><Download :size="16"/>{{ t('导出 Markdown') }}</a></div></div>
        <div v-if="settings.demo" class="demo-caption"><CircleAlert :size="14"/>{{ t('演示内容为固定示例，示例音频为静音。正式模式会处理实际音频。') }}</div>
        <section class="audio-player"><span class="player-icon"><AudioLines :size="22"/></span><div><strong>{{ t('原始课堂录音') }}</strong><small>{{ t('点击转录时间，可跳转到对应位置') }}</small></div><audio ref="audio" :key="selected.recording.id" :src="`/api/recordings/${selected.recording.id}/audio`" controls preload="metadata"/></section>
        <TranscriptionProgress v-if="selected.recording.transcriptionProgress" :progress="selected.recording.transcriptionProgress"/>
        <div class="detail-grid"><section class="document-panel"><div class="document-tabs"><button :class="{ active: tab === 'note' }" @click="tab = 'note'"><BookOpen :size="16"/>{{ t('课堂笔记') }}</button><button :class="{ active: tab === 'transcript' }" @click="tab = 'transcript'"><FileText :size="16"/>{{ t('原始转录') }}</button><button :class="{ active: tab === 'logs' }" @click="tab = 'logs'"><History :size="16"/>{{ t('处理记录') }}</button><button :class="{ active: tab === 'versions' }" @click="tab = 'versions'"><Layers :size="16"/>{{ t('版本') }}</button><button :class="{ active: tab === 'raw' }" @click="showArtifacts">{{ t('原始结果') }}</button></div>
          <div v-if="tab === 'note' && currentNote"><div class="note-toolbar"><span><i class="tiny-dot"/>{{ version ? t('历史版本 v{0}', {0: version.revision}) : t('当前版本 v{0}', {0: currentNote.revision}) }}<em v-if="!version">{{ confirmed ? t('已确认') : t('待确认') }}</em></span><div v-if="version"><button class="text-btn" @click="restoreVersion">{{ t('恢复到编辑器') }}</button><button class="text-btn" @click="version = undefined">{{ t('返回当前版本') }}</button></div><div v-else-if="editing"><button class="btn compact secondary" @click="persistDraft(); editing = false">{{ t('稍后继续') }}</button><button class="btn compact primary" :disabled="busy || !dirty" @click="saveNote"><Save :size="14"/>{{ t('保存笔记') }}</button></div><div v-else style="display:flex;gap:8px"><button class="btn compact secondary" :aria-expanded="chatOpen" @click="chatOpen=!chatOpen"><Sparkles :size="14"/>{{ chatOpen?t('收起 AI 对话'):t('AI 对话') }}</button><button class="btn compact secondary" @click="beginEdit"><Pencil :size="14"/>{{ t('编辑笔记') }}</button></div></div>
            <AiNoteChat v-if="chatOpen" :key="currentNote.id" :note="currentNote" :disabled="active||editing||!!version" :reason="editing?t('请先保存或退出手动编辑，再发送 AI 要求'):version?t('请先返回当前版本'):t('请等待当前任务完成')" @queued="refresh" @complete="refresh" @dirty="chatDirty=$event"/>
            <div v-if="editing" class="editor-meta"><label>{{ t('标题') }}<input v-model="draft.title" maxlength="200"/></label><label>{{ t('分类') }}<input v-model="draft.category" list="category-options" maxlength="80"/></label></div><div :class="['note-content', { 'editing-grid': editing }]"><div v-if="editing" class="editor-pane"><div class="editor-caption">{{ t('Markdown · 本地草稿自动保留') }}</div><textarea v-model="draft.markdown" :aria-label="t('Markdown 编辑器')" spellcheck="false"/></div><article class="markdown-body" v-html="markdown"/></div></div>
          <div v-else-if="tab === 'note'" class="empty-state"><span class="empty-icon"><Sparkles :size="32"/></span><h3>{{ active ? t('正在把课堂声音整理成知识') : t('等待生成课堂笔记') }}</h3><p>{{ t('完成转录后，使用已配置的大模型提取重点。') }}</p><button v-if="selected.transcript && !active" class="btn primary" @click="runStage('SUMMARIZE')"><Sparkles :size="16"/>{{ t('整理笔记') }}</button></div>
          <div v-else-if="tab === 'transcript'" class="transcript-content"><div v-if="shownTranscript" class="transcript-info"><span>{{ shownTranscript.provider }} · {{ shownTranscript.segments.length }} {{ t('个分段') }}</span><select v-model="transcriptVersion" :aria-label="t('转录历史')"><option value="">{{ t('当前转录') }}</option><option v-for="t in selected.transcripts" :key="t.id" :value="t.id">{{ formatDate(t.createdAt) }} · {{ t.provider }}</option></select></div><p v-if="shownTranscript" class="timestamp-hint">{{ t('时间戳为音频分段边界，可能包含重叠；用于回听定位，不代表逐字对齐。') }}</p><div v-for="(s,i) in shownTranscript?.segments" :key="i" class="transcript-segment"><button @click="seek(s.startMs)"><Play :size="11"/>{{ time(s.startMs) }}</button><p>{{ s.text }}</p><small>{{ time(s.endMs) }}</small></div><div v-if="!shownTranscript" class="empty-state"><FileText :size="30"/><h3>{{ t('转录结果将在这里显示') }}</h3></div></div>
          <div v-else-if="tab === 'logs'" class="logs-content"><h3>{{ t('任务历史') }}</h3><div v-for="job in selected.jobs" :key="job.id" class="job-row"><span :class="['job-status', job.status.toLowerCase()]"><Check v-if="job.status === 'SUCCEEDED'" :size="16"/><CircleAlert v-else-if="job.status === 'FAILED'" :size="16"/><Clock3 v-else :size="16"/></span><div><strong>{{ t(stageName[job.stage]) }} <em>{{ t('第 {0} 次',{0:job.attempt}) }}</em></strong><p v-if="job.error" class="danger-text">{{ messageText(job.error) }}</p><small>{{ formatDate(job.createdAt) }}</small></div><span class="muted small">{{ t(statusName[job.status]) }}</span><button v-if="job.status === 'FAILED'" class="text-btn" :disabled="active || busy" @click="runStage(job.stage)"><RefreshCw :size="13"/>{{ t('重试') }}</button></div><h3 class="log-title">{{ t('处理日志') }}</h3><div v-for="log in selected.logs" :key="log.id" class="log-line"><time>{{ formatDate(log.createdAt) }}</time><span :class="log.level.toLowerCase()">{{ log.level }}</span><p>{{ messageText(log.message) }}</p></div></div>
          <div v-else-if="tab === 'versions'" class="logs-content"><h3>{{ t('每次保存，都是一份可回溯的记录') }}</h3><div v-for="v in selected.versions" :key="v.id" class="version-row"><span class="version-icon"><FileText :size="18"/></span><div><strong>v{{ v.revision }} · {{ v.title }}</strong><small>{{ v.source === 'AI' ? t('AI 整理') : v.source === 'AI_CHAT' ? t('AI 对话修改') : t('手动编辑') }} · {{ formatDate(v.createdAt) }}</small></div><button class="text-btn" @click="versionPreview(v)">{{ t('查看版本') }}<ChevronRight :size="13"/></button></div><p v-if="!selected.versions.length" class="muted">{{ t('尚无笔记版本') }}</p></div>
          <div v-else-if="tab === 'raw'" class="raw-content"><p class="muted small">{{ t('保留每次模型与 Notion 的原始响应。Worker 的分段日志在 data/asr/任务ID/ 下。') }}</p><details v-for="a in artifacts" :key="a.id"><summary><code>{{ a.kind }}</code><span>{{ formatDate(a.createdAt) }}</span></summary><pre>{{ a.content }}</pre></details><p v-if="!artifacts.length" class="muted">{{ t('暂无原始结果') }}</p></div>
        </section>
        <aside class="detail-rail"><section class="rail-card"><h3>{{ t('处理进度') }}</h3><div v-for="(stage,i) in (['TRANSCRIBE', 'SUMMARIZE', 'SYNC'] as const)" :key="stage" class="pipeline-step"><span :class="['step-number', { done: selected.recording[stage === 'TRANSCRIBE' ? 'transcriptStatus' : stage === 'SUMMARIZE' ? 'summaryStatus' : 'syncStatus'] === 'SUCCEEDED' }]"><Check v-if="selected.recording[stage === 'TRANSCRIBE' ? 'transcriptStatus' : stage === 'SUMMARIZE' ? 'summaryStatus' : 'syncStatus'] === 'SUCCEEDED'" :size="14"/><template v-else>{{ i+1 }}</template></span><div><strong>{{ t(stageName[stage]) }}</strong><small>{{ t(statusName[selected.recording[stage === 'TRANSCRIBE' ? 'transcriptStatus' : stage === 'SUMMARIZE' ? 'summaryStatus' : 'syncStatus']]) }}</small></div></div><div class="rail-actions"><button class="btn secondary" :disabled="active || busy" @click="runStage('TRANSCRIBE')"><RefreshCw :size="14"/>{{ t('重新转录') }}</button><button class="btn secondary" :disabled="active || busy || !selected.transcript" @click="runStage('SUMMARIZE')"><Sparkles :size="14"/>{{ currentNote ? t('重新整理笔记') : t('整理笔记') }}</button></div></section>
          <section class="rail-card sync-card"><span class="notion-logo">N</span><h3>{{ t('同步到 Notion') }}</h3><p>{{ t('核对课堂知识后，确认当前版本，再同步到你的课程数据库。') }}</p><button v-if="currentNote && !confirmed" class="btn primary full" :disabled="busy || editing || !!version" @click="confirmNote"><CheckCircle2 :size="16"/>{{ t('确认当前笔记') }}</button><span v-else-if="confirmed" class="confirmed-label"><CheckCircle2 :size="15"/>v{{ currentNote?.revision }} {{ t('已确认') }}</span><button class="btn secondary full" :disabled="busy || active || !confirmed || editing || settings.demo === true" @click="runStage('SYNC')"><CloudUpload :size="16"/>{{ selected.recording.syncStatus === 'SUCCEEDED' && currentNote?.syncedRevision === currentNote?.revision && currentNote ? t('再次同步当前版本') : t('同步到 Notion') }}</button><p v-if="currentNote?.notionPageId && selected.recording.syncStatus === 'PENDING' && currentNote.syncedRevision === currentNote.revision" class="course-sync-hint">{{ t('课程归档已更改，再次同步将更新原来的 Notion 页面。') }}</p><a v-if="currentNote?.notionUrl" :href="currentNote.notionUrl" target="_blank" rel="noopener noreferrer" class="text-btn">{{ t('打开 Notion 页面') }}<ExternalLink :size="13"/></a><small v-if="settings.demo" class="muted">{{ t('演示模式不写入 Notion') }}</small><button v-if="selected.recording.syncStatus === 'FAILED' && selected.recording.lastError?.includes('创建结果不确定')" class="text-btn small" @click="resetOpen = true">{{ t('核对后重置创建状态') }}</button></section>
          <section class="rail-note"><ShieldCheck :size="17"/><div><strong>{{ t('每一步都有记录') }}</strong><p>{{ t('转录与总结独立保存。失败只重试对应阶段，原始结果随时可查。') }}</p></div></section>
        </aside></div>
      </main>
      <main v-else-if="page === 'settings' && !selectedId" class="workspace settings-workspace"><div class="page-heading"><div><div class="eyebrow">{{ t('按你的方式，整理课堂') }}</div><h1>{{ t('系统设置') }}</h1><p>{{ t('配置本地转录、大模型与 Notion。凭证在本机加密保存，留空时保留已配置值。') }}</p></div><button class="btn primary" :disabled="busy||promptResetting" @click="saveSettings"><Save :size="16"/>{{ t('保存配置') }}</button></div><div v-if="settings.demo" class="demo-caption"><CircleAlert :size="14"/>{{ t('演示模式使用固定内容；配置会保存，正式模式启动后生效。') }}</div>
        <LanguageSettings/>
        <DesktopSettings/>
        <section class="settings-card"><div class="settings-section-title"><span class="stat-icon mint"><AudioLines :size="21"/></span><div><h3>{{ t('本地语音转录') }}</h3><p>{{ t('音频在 Python Worker 中转换为 16kHz 单声道，并分段识别。') }}</p></div><span class="tag">SenseVoice-Small</span></div><div class="settings-fields"><label>{{ t('Worker 地址') }}<input v-model="config.workerUrl" placeholder="http://127.0.0.1:8090"/><small>{{ t('与 Java 后端共享同一个 DATA_DIR') }}</small></label><label>Worker Token<input v-model="config.workerToken" type="password" autocomplete="new-password" :placeholder="settings.workerTokenConfigured ? t('已配置，留空保留') : t('可选，与 Worker 环境变量保持一致')"/></label></div></section>
        <section class="settings-card"><div class="settings-section-title"><span class="stat-icon lavender"><Sparkles :size="21"/></span><div><h3>{{ t('AI 知识提取') }}</h3><p>{{ t('支持兼容 Chat Completions 的模型 API，按段提取后合并知识。') }}</p></div></div><div class="settings-fields"><label>{{ t('API 基础地址') }}<input v-model="config.llmBaseUrl" placeholder="https://your-provider.example/v1"/><small>{{ t('包含 /v1；本地模型允许使用 HTTP') }}</small></label><label>{{ t('模型名称') }}<input v-model="config.llmModel" :placeholder="t('填写服务商提供的模型 ID')"/></label><label>API Key<input v-model="config.llmApiKey" type="password" autocomplete="new-password" :placeholder="settings.llmApiKeyConfigured ? t('已配置，留空保留') : t('填写 API Key，本地免密模型可留空')"/></label><label>{{ t('每段最大字符数') }}<input v-model="config.llmChunkChars" type="number" min="2000" max="12000"/><small>{{ t('建议 6000，模型上下文较小时可调小') }}</small></label><label class="checkbox-label"><input v-model="config.autoSummarize" type="checkbox" true-value="true" false-value="false"/>{{ t('转录完成后自动整理笔记') }}</label></div><SystemPromptEditor v-model="config.llmSystemPrompt" :disabled="busy" @busy="promptResetting=$event"/></section>
        <section class="settings-card"><div class="settings-section-title"><span class="notion-logo">N</span><div><h3>{{ t('Notion 集成') }}</h3><p>{{ t('创建官方 Integration，并在目标数据库的连接菜单中授权。') }}</p></div><span class="tag">{{ t('确认后同步') }}</span></div><div class="settings-fields"><label>Integration Token<input v-model="config.notionToken" type="password" autocomplete="new-password" :placeholder="settings.notionTokenConfigured ? t('已配置，留空保留') : 'ntn_…'"/></label><label>{{ t('数据库 ID') }}<input v-model="config.notionDatabaseId" :placeholder="t('32 位或带连字符的 UUID')"/></label><label>{{ t('数据源 ID') }}<input v-model="config.notionDataSourceId" :placeholder="t('单数据源数据库可留空')"/><small>{{ t('数据库有多个数据源时必须指定') }}</small></label><div class="field-placeholder"/></div><div class="schema-help"><h4>{{ t('数据库字段映射') }}</h4><p>{{ t('在 Notion 建立以下字段，类型需一致。Source ID 是查重依据，请保留。') }}</p><div class="schema-fields"><label v-for="[key,label,type] in ([['notionTitleProperty',t('标题'),'Title'],['notionSourceProperty',t('来源 ID'),'Text'],['notionCourseProperty',t('课程'),'Text'],['notionDateProperty',t('日期'),'Date'],['notionCategoryProperty',t('分类'),'Select']] as const)" :key="key">{{ label }} <em>{{ type }}</em><input v-model="config[key]"/></label></div></div></section>
        <p class="settings-footnote"><ShieldCheck :size="15"/>{{ t('仅笔记正文与归档字段发送到 Notion。总结时转录文本发送到你配置的模型 API。') }}</p>
      </main>
      <div v-else class="empty-state"><LoaderCircle :size="30" class="spin"/><p>{{ t('正在加载录音详情…') }}</p></div>
    </div>
    <div v-if="notice" class="toast" role="status"><CheckCircle2 :size="18"/>{{ messageText(notice) }}</div>
    <datalist id="category-options"><option v-for="c in categories" :key="c" :value="c"/></datalist>
    <div v-if="uploadOpen" class="modal-overlay" @click.self="!uploading && (uploadOpen = false)"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="upload-title"><header><div><h2 id="upload-title">{{ t('上传课堂录音') }}</h2><p>{{ t('一份录音，一次值得回顾的学习。') }}</p></div><button class="icon-btn" :disabled="uploading" :aria-label="t('关闭上传')" @click="uploadOpen = false"><X :size="20"/></button></header><form @submit.prevent="uploadFile"><div v-if="error" class="error-banner" role="alert">{{ messageText(error) }}</div><button type="button" :class="['dropzone', { 'has-file': file }]" @dragover.prevent @drop.prevent="dropFile" :disabled="uploading" @click="fileInput?.click()"><span><FileAudio v-if="file" :size="28"/><Upload v-else :size="28"/></span><strong>{{ file ? file.name : t('点击选择，或将录音拖到这里') }}</strong><small>{{ file ? size(file.size) : t('支持 M4A、WAV · 单文件最大 1GB') }}</small></button><input ref="fileInput" type="file" accept=".m4a,.wav" class="visually-hidden" :disabled="uploading" @change="pickFile"/><div class="upload-fields"><label>{{ t('录音标题') }}<input v-model="uploadForm.title" :readonly="uploading" required maxlength="200" :placeholder="t('例如：导数的定义与可导性')"/></label><div class="two-fields"><label>{{ t('课程') }}<input v-model="uploadForm.course" :readonly="uploading" required maxlength="120" list="course-options" :placeholder="t('例如：高等数学')"/></label><label>{{ t('上课日期') }}<input v-model="uploadForm.recordedOn" :readonly="uploading" required type="date"/></label></div><label>{{ t('笔记分类') }}<input v-model="uploadForm.category" :readonly="uploading" required maxlength="80" list="category-options"/></label></div><datalist id="course-options"><option v-for="c in courses" :key="c" :value="c"/></datalist><UploadProgressView v-if="uploading" :progress="uploadProgress"/><div class="upload-hint"><ShieldCheck :size="16"/>{{ t('原始录音保存在本机，上传后自动加入转录队列。') }}</div><footer><button type="button" class="btn secondary" :disabled="uploading" @click="uploadOpen = false">{{ t('取消') }}</button><button class="btn primary" type="submit" :disabled="!file || uploading"><LoaderCircle v-if="uploading" class="spin" :size="16"/><Upload v-else :size="16"/>{{ uploading ? (uploadProgress.waitingForServer ? t('正在保存…') : t('正在上传…')) : t('上传并开始转录') }}</button></footer></form></section></div>
    <MoveCourseDialog v-if="moveTarget" :recording="moveTarget" :courses="courses" @close="moveTarget=undefined" @moved="movedCourse"/>
    <div v-if="resetOpen" class="modal-overlay"><section class="modal small-modal" role="dialog" aria-modal="true"><h2>{{ t('已核对 Notion 未创建页面？') }}</h2><p>{{ t('先按 Source ID') }} <code>classnotes:{{ currentNote?.id }}</code> {{ t('查询目标数据库。若已有页面，稍后重试会复用；仅在确认没有页面时重置，避免重复。') }}</p><div v-if="error" class="error-banner">{{ messageText(error) }}</div><footer><button class="btn secondary" @click="resetOpen = false">{{ t('返回核对') }}</button><button class="btn primary" :disabled="busy" @click="resetCreation">{{ t('确认未创建，重置状态') }}</button></footer></section></div>
  </div>
</template>

<style scoped>
.recording-row-actions{display:flex;align-items:center;gap:14px;white-space:nowrap}.row-move{font-size:11px}.detail-actions{display:flex;align-items:center;justify-content:flex-end;gap:10px;flex-wrap:wrap;flex-shrink:0}.sync-card .course-sync-hint{color:#5f896b;font-size:11px;line-height:1.8}table .action-column{width:105px}@media(max-width:1000px){.detail-actions{justify-content:flex-start;margin-top:16px}.recording-row-actions{gap:8px}}
</style>
