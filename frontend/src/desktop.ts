export interface DesktopState { phase: string; message: string; version?: string; percent?: number; schemaVersion?: number }
export interface DesktopInfo { version: string; platform: string; packaged: boolean; dataDirectory: string; updateUrl: string; service: DesktopState; update: DesktopState; demo: boolean }
export interface DesktopBridge {
  uploadProgressManaged?: boolean
  info(): Promise<DesktopInfo>
  minimize(): Promise<void>; maximize(): Promise<void>; close(): Promise<void>
  openData(): Promise<void>; importData(): Promise<{imported:boolean}>
  checkUpdates(): Promise<DesktopState>; downloadUpdate(): Promise<DesktopState>; applyUpdate(): Promise<void>
  configureUpdates(url: string): Promise<DesktopState>; setDirty(value: boolean): void
  setUploading(value: boolean): void
  onState(callback: (value:{service?:DesktopState;update?:DesktopState;upload?:import('./api').UploadProgress}) => void): () => void
}
declare global { interface Window { classnotesDesktop?: DesktopBridge } }
export const desktop = window.classnotesDesktop
