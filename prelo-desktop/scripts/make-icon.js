// Gera renderer/icon.png (512), assets/icon.ico (16–256) e o ícone do plugin a partir de assets/icon.svg.
// Uso: npm run icon
const { app, BrowserWindow } = require('electron');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');

async function render(svg, size) {
  const win = new BrowserWindow({ show: false, width: size, height: size, transparent: true, webPreferences: { offscreen: true } });
  const html = `<html><body style="margin:0;background:transparent;overflow:hidden">${svg.replace('<svg ', `<svg width="${size}" height="${size}" `)}</body></html>`;
  await win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(html));
  await new Promise((r) => setTimeout(r, 300));
  const img = await win.webContents.capturePage({ x: 0, y: 0, width: size, height: size });
  win.destroy();
  return img.resize({ width: size, height: size, quality: 'best' }).toPNG();
}

/** ICO com entradas PNG (aceito desde o Windows Vista). */
function ico(pngs) {
  const head = Buffer.alloc(6);
  head.writeUInt16LE(0, 0);
  head.writeUInt16LE(1, 2);
  head.writeUInt16LE(pngs.length, 4);
  const dir = Buffer.alloc(16 * pngs.length);
  let offset = 6 + dir.length;
  pngs.forEach(({ size, data }, i) => {
    const o = i * 16;
    dir.writeUInt8(size >= 256 ? 0 : size, o);
    dir.writeUInt8(size >= 256 ? 0 : size, o + 1);
    dir.writeUInt8(0, o + 2);
    dir.writeUInt8(0, o + 3);
    dir.writeUInt16LE(1, o + 4);
    dir.writeUInt16LE(32, o + 6);
    dir.writeUInt32LE(data.length, o + 8);
    dir.writeUInt32LE(offset, o + 12);
    offset += data.length;
  });
  return Buffer.concat([head, dir, ...pngs.map((p) => p.data)]);
}

app.whenReady().then(async () => {
  const svg = fs.readFileSync(path.join(ROOT, 'assets', 'icon.svg'), 'utf8');
  const big = await render(svg, 512);
  fs.writeFileSync(path.join(ROOT, 'renderer', 'icon.png'), big);
  const { nativeImage } = require('electron');
  const src = nativeImage.createFromBuffer(big);
  const pngs = [16, 24, 32, 48, 64, 128, 256].map((s) => ({ size: s, data: src.resize({ width: s, height: s, quality: 'best' }).toPNG() }));
  fs.writeFileSync(path.join(ROOT, 'assets', 'icon.ico'), ico(pngs));
  // plugin IntelliJ: META-INF/pluginIcon.svg (a IDE redimensiona)
  const meta = path.join(ROOT, '..', 'prelo-intellij', 'src', 'main', 'resources', 'META-INF');
  if (fs.existsSync(meta)) fs.writeFileSync(path.join(meta, 'pluginIcon.svg'), svg.replace('viewBox="0 0 512 512"', 'viewBox="0 0 512 512" width="40" height="40"'));
  console.log('icon.png, icon.ico e pluginIcon.svg gerados');
  app.quit();
});
