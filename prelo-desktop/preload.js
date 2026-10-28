const { contextBridge, ipcRenderer, webUtils } = require('electron');

contextBridge.exposeInMainWorld('api', {
  engine: (cmd, args) => ipcRenderer.invoke('engine', cmd, args),
  restartEngine: () => ipcRenderer.invoke('engine-restart'),
  openDialog: () => ipcRenderer.invoke('open-dialog'),
  readReport: (f) => ipcRenderer.invoke('read-report', f),
  saveReport: (doc) => ipcRenderer.invoke('save-report', doc),
  exportReport: (req) => ipcRenderer.invoke('export', req),
  examples: () => ipcRenderer.invoke('examples'),
  readExample: (f) => ipcRenderer.invoke('read-example', f),
  recent: () => ipcRenderer.invoke('recent'),
  forgetRecent: (f) => ipcRenderer.invoke('forget-recent', f),
  pathForFile: (file) => webUtils.getPathForFile(file),
  pendingFile: () => ipcRenderer.invoke('pending-file'),
  onOpenFile: (cb) => ipcRenderer.on('open-file', (_, f) => cb(f)),
  openPath: (p) => ipcRenderer.send('open-path', p),
  reveal: (p) => ipcRenderer.send('reveal', p),
  setTitle: (t) => ipcRenderer.send('set-title', t),
  setDirty: (v) => ipcRenderer.send('set-dirty', v),
  onConfirmClose: (cb) => ipcRenderer.on('confirm-close', () => cb()),
  closeNow: () => ipcRenderer.send('close-now'),
});
