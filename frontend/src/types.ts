export type Stage = 'TRANSCRIBE' | 'SUMMARIZE' | 'REFINE' | 'SYNC'
export interface Recording {
  id: string; title: string; course: string; recordedOn: string; category: string; filename: string
  size: number; durationMs?: number; transcriptStatus: string; summaryStatus: string; syncStatus: string
  lastError?: string; createdAt: string; updatedAt: string; noteId?: string; noteTitle?: string; preview?: string; confirmed: boolean
  transcriptionProgress?: TranscriptionProgress; audioAvailable: boolean
}
export interface TranscriptionProgress { phase: string; message: string; percent: number | null; processedMs: number; durationMs: number; completedChunks: number; totalChunks: number }
export interface Note {
  id: string; recordingId: string; title: string; markdown: string; category: string; revision: number
  confirmedRevision?: number; syncedRevision?: number; notionPageId?: string; notionUrl?: string; notionTarget?: string
  createdAt: string; updatedAt: string
}
export interface Segment { startMs: number; endMs: number; text: string; timestampKind: string }
export interface Transcript { id: string; provider: string; text: string; durationMs: number; segments: Segment[]; createdAt: string }
export interface Job { id: string; recordingId: string; stage: Stage; status: string; attempt: number; noteRevision: number; createdAt: string; startedAt?: string; finishedAt?: string; error?: string }
export interface Log { id: number; jobId?: string; level: string; message: string; createdAt: string }
export interface Version { id: number; revision: number; title: string; markdown: string; category: string; source: string; createdAt: string }
export interface Artifact { id: number; jobId: string; kind: string; content: string; createdAt: string }
export interface AiTurn { id:string;jobId:string;message:string;reply?:string;baseRevision:number;resultRevision?:number;changed:boolean;status:string;error?:string;createdAt:string }
export interface Detail { recording: Recording; note?: Note; transcript?: Transcript; jobs: Job[]; logs: Log[]; versions: Version[]; transcripts: Transcript[] }
export type Settings = Record<string, string | boolean>
