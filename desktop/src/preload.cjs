const { contextBridge, ipcRenderer } = require('electron')
contextBridge.exposeInMainWorld('classnotesDesktop', {
  uploadProgressManaged: true,
  info: () => ipcRenderer.invoke('desktop:info'),
  setLanguage: language => ipcRenderer.invoke('desktop:language',language),
  minimize: () => ipcRenderer.invoke('desktop:minimize'),
  maximize: () => ipcRenderer.invoke('desktop:maximize'),
  close: () => ipcRenderer.invoke('desktop:close'),
  openData: () => ipcRenderer.invoke('desktop:open-data'),
  importData: () => ipcRenderer.invoke('desktop:import-data'),
  migrateStorage: () => ipcRenderer.invoke('desktop:migrate-storage').catch(error => { throw new Error(error.message.replace(/^Error invoking remote method 'desktop:migrate-storage': Error: /, '')) }),
  checkUpdates: () => ipcRenderer.invoke('desktop:check-updates'),
  downloadUpdate: () => ipcRenderer.invoke('desktop:download-update'),
  applyUpdate: () => ipcRenderer.invoke('desktop:apply-update'),
  configureUpdates: url => ipcRenderer.invoke('desktop:configure-updates', url),
  setDirty: dirty => ipcRenderer.send('desktop:dirty', Boolean(dirty)),
  setUploading: uploading => ipcRenderer.send('desktop:uploading', Boolean(uploading)),
  onState: callback => { const listener = (_event, state) => callback(state); ipcRenderer.on('desktop:state', listener); return () => ipcRenderer.removeListener('desktop:state', listener) },
})
