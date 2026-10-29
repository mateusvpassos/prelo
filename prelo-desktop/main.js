const { app, BrowserWindow, ipcMain, dialog, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const { Engine } = require('./engine');

let win;
let dirty = false;
let forceClose = false;
const engine = new Engine(__dirname, process.resourcesPath, app.isPackaged);

const isReport = (a) => /\.report\.json$/i.test(a || '');
let pendingFile = process.argv.slice(1).find(isReport) || null;

if (!app.requestSingleInstanceLock()) app.quit();
app.on('second-instance', (_, argv) => {
  if (!win) return;
  if (win.isMinimized()) win.restore();
  win.focus();
  const f = argv.slice(1).find(isReport);
  if (f) win.webContents.send('open-file', f);
});

function createWindow() {
  win = new BrowserWindow({
    width: 1480,
    height: 920,
    minWidth: 1100,
    minHeight: 680,
    backgroundColor: '#0c0f12',
    title: 'Prelo',
    icon: path.join(__dirname, 'renderer', 'icon.png'),
    show: !process.env.PRELO_SHOTS,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), backgroundThrottling: !process.env.PRELO_SHOTS },
  });
  win.removeMenu();
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.on('close', (e) => {
    if (!dirty || forceClose) return;
    e.preventDefault();
    win.webContents.send('confirm-close');
  });
  if (process.env.PRELO_DEVTOOLS) win.webContents.openDevTools({ mode: 'detach' });
  if (process.env.PRELO_SHOTS) win.webContents.once('did-finish-load', () => screenshots(process.env.PRELO_SHOTS));
}

/** Capturas para documentação/verificação: PRELO_SHOTS=pasta npm start */
async function screenshots(dir) {
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const js = (code) => win.webContents.executeJavaScript(code, true);
  const shot = async (name) => {
    const img = await win.webContents.capturePage();
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, name + '.png'), img.toPNG());
  };
  try {
    await wait(2500);
    await win.webContents.insertCSS('*, *::before, *::after { animation: none !important; transition: none !important; }');
    await shot('inicio');
    const file = path.join(examplesDir(), 'painel.report.json');
    await js(`preloApp.openExample(${JSON.stringify(file)})`);
    await wait(2500);
    await js(`document.querySelector('.blk[data-type="chart"]')?.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, button: 0 })); document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));`);
    await wait(800);
    await shot('designer');
    await js(`document.getElementById('splitPreview').click()`);
    await wait(9000);
    await shot('lado-a-lado');
    await js(`document.getElementById('splitPreview').click(); document.getElementById('btn-sample').click()`);
    await wait(800);
    await shot('dados');
    await js(`document.querySelector('#codeModal [data-close]').click(); document.getElementById('btn-export').click()`);
    await wait(500);
    await shot('exportar');
  } catch (e) {
    console.error(e);
  }
  forceClose = true;
  app.quit();
}

app.whenReady().then(() => {
  createWindow();
  engine.start(); // aquece o Java enquanto a janela abre
});
app.on('window-all-closed', () => app.quit());
app.on('will-quit', () => engine.stop());

ipcMain.on('close-now', () => { forceClose = true; win.close(); });
ipcMain.on('set-dirty', (_, v) => { dirty = !!v; });
ipcMain.on('set-title', (_, t) => win?.setTitle(t));
ipcMain.handle('pending-file', () => { const f = pendingFile; pendingFile = null; return f; });

// ---------------------------------------------------------------- motor

ipcMain.handle('engine', (_, cmd, args) => engine.call(cmd, args));
ipcMain.handle('engine-restart', () => { engine.stop(); engine.start(); return true; });

// ---------------------------------------------------------------- arquivos

function sampleFor(file) {
  return file.replace(/\.report\.json$/i, '.sample.json');
}

function readReport(file) {
  const text = fs.readFileSync(file, 'utf8');
  const sp = sampleFor(file);
  const sample = fs.existsSync(sp) ? fs.readFileSync(sp, 'utf8') : null;
  addRecent(file);
  return { path: file, name: path.basename(file), dir: path.dirname(file), text, sample, samplePath: fs.existsSync(sp) ? sp : null };
}

ipcMain.handle('open-dialog', async () => {
  const r = await dialog.showOpenDialog(win, {
    title: 'Abrir relatório',
    properties: ['openFile'],
    filters: [{ name: 'Relatório Prelo', extensions: ['json'] }],
  });
  if (r.canceled || !r.filePaths.length) return null;
  try { return readReport(r.filePaths[0]); } catch (e) { return { error: e.message }; }
});

ipcMain.handle('read-report', (_, file) => {
  try { return readReport(file); } catch (e) { return { error: e.message }; }
});

/** Salva o template (e o sample ao lado, se houver). Sem caminho pergunta onde. */
ipcMain.handle('save-report', async (_, { path: file, name, text, sample, saveAs }) => {
  if (!file || saveAs) {
    const r = await dialog.showSaveDialog(win, {
      title: 'Salvar relatório',
      defaultPath: (file ? path.dirname(file) + path.sep : '') + (name || 'relatorio').replace(/(\.report)?\.json$/i, '') + '.report.json',
      filters: [{ name: 'Relatório Prelo', extensions: ['json'] }],
    });
    if (r.canceled) return null;
    file = r.filePath;
    if (!isReport(file)) file = file.replace(/(\.json)?$/i, '.report.json');
  }
  try {
    fs.writeFileSync(file, text);
    if (sample != null && sample.trim()) fs.writeFileSync(sampleFor(file), sample);
    addRecent(file);
    return { path: file, name: path.basename(file), dir: path.dirname(file) };
  } catch (e) {
    return { error: e.message };
  }
});

const FORMATS = {
  pdf: { name: 'PDF', ext: 'pdf' },
  xlsx: { name: 'Planilha Excel', ext: 'xlsx' },
  html: { name: 'Página HTML', ext: 'html' },
  csv: { name: 'CSV (Excel)', ext: 'csv' },
};

ipcMain.handle('export', async (_, { format, name, dir, json, sample }) => {
  const f = FORMATS[format] || FORMATS.pdf;
  const base = (name || 'relatorio').replace(/(\.report)?\.json$/i, '');
  const r = await dialog.showSaveDialog(win, {
    title: 'Exportar ' + f.name,
    defaultPath: (dir ? dir + path.sep : '') + base + '.' + f.ext,
    filters: [{ name: f.name, extensions: [f.ext] }],
  });
  if (r.canceled) return null;
  const res = await engine.call('export', { json, sample, baseDir: dir || null, format, out: r.filePath });
  if (res.error) return { error: res.error };
  return { path: r.filePath, bytes: res.bytes };
});

ipcMain.on('open-path', (_, p) => shell.openPath(p));
ipcMain.on('reveal', (_, p) => shell.showItemInFolder(p));
ipcMain.on('open-external', (_, url) => { if (/^https:\/\//.test(url)) shell.openExternal(url); });

// ---------------------------------------------------------------- exemplos e recentes

function examplesDir() {
  const packaged = path.join(process.resourcesPath, 'examples');
  if (app.isPackaged && fs.existsSync(packaged)) return packaged;
  const local = path.join(__dirname, 'build-resources', 'examples');
  return fs.existsSync(local) ? local : path.join(__dirname, '..', 'examples');
}

ipcMain.handle('examples', () => {
  const dir = examplesDir();
  const meta = {
    painel: ['Painel de vendas', 'Indicadores, gráficos e tabela cruzada'],
    cobranca: ['Cobrança com Pix', 'QR Pix, boleto e itens com total'],
    etiquetas: ['Etiquetas de produto', 'Grade 3×7 com preço, EAN e QR'],
    crachas: ['Crachás', 'Cartões de evento com cor por função'],
    contrato: ['Contrato', 'Texto formatado com campos e assinaturas'],
    vendas: ['Relatório de vendas', 'Tabela agrupada com subtotais'],
    pedidos: ['Pedidos', 'Mestre-detalhe: pedido e itens'],
  };
  const out = [];
  for (const [id, [title, desc]] of Object.entries(meta)) {
    const file = path.join(dir, id + '.report.json');
    if (!fs.existsSync(file)) continue;
    const png = path.join(dir, 'galeria', id + '.png');
    out.push({
      id, title, desc, file,
      thumb: fs.existsSync(png) ? 'data:image/png;base64,' + fs.readFileSync(png).toString('base64') : null,
    });
  }
  return out;
});

ipcMain.handle('read-example', (_, file) => {
  try {
    const r = readReport(file);
    removeRecent(file); // exemplo abre como cópia sem título, não entra nos recentes
    return { ...r, path: null, name: r.name, samplePath: null };
  } catch (e) {
    return { error: e.message };
  }
});

const recentFile = () => path.join(app.getPath('userData'), 'recent.json');
function loadRecent() {
  try { return JSON.parse(fs.readFileSync(recentFile(), 'utf8')); } catch { return []; }
}
function storeRecent(list) {
  try { fs.mkdirSync(path.dirname(recentFile()), { recursive: true }); fs.writeFileSync(recentFile(), JSON.stringify(list.slice(0, 12))); } catch {}
}
function addRecent(file) {
  if (file.startsWith(examplesDir())) return;
  storeRecent([{ path: file, at: Date.now() }, ...loadRecent().filter((r) => r.path !== file)]);
}
function removeRecent(file) {
  storeRecent(loadRecent().filter((r) => r.path !== file));
}
ipcMain.handle('recent', () => loadRecent().filter((r) => fs.existsSync(r.path)).map((r) => ({ ...r, name: path.basename(r.path), dir: path.dirname(r.path) })));
ipcMain.handle('forget-recent', (_, file) => { removeRecent(file); return true; });

// ---------- sobre (renderer/about.js) ----------
{
  const { ipcMain: ipc, shell: sh } = require('electron');
  const REPO = 'https://github.com/mateusvpassos/prelo';
  // instalado, ficam em resources/ (extraResources); rodando do código, LICENSE e NOTICE estão na raiz do repositório
  const res = (packaged, dev) => require('path').join(app.isPackaged ? process.resourcesPath : __dirname, app.isPackaged ? packaged : dev);
  ipc.handle('about:info', () => ({ version: app.getVersion() }));
  ipc.handle('about:notices', () => [res('LICENSE.txt', '../LICENSE'), res('NOTICE.txt', '../NOTICE'), res('THIRD-PARTY-NOTICES.md', 'THIRD-PARTY-NOTICES.md')]
    .map(f => { try { return require('fs').readFileSync(f, 'utf8'); } catch { return ''; } })
    .join('\n\n' + '─'.repeat(60) + '\n\n'));
  ipc.handle('about:open', (_, which) => {
    if (which === 'licenses') return sh.openPath(res('engine/runtime/legal', 'build-resources/engine/runtime/legal'));
    const url = { repo: REPO, issues: REPO + '/issues/new/choose' }[which];
    if (url) sh.openExternal(url);
  });
}
