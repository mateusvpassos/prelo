// Casca do app desktop: documento aberto, arquivos, motor Java e a ponte com o designer (designer/designer.js).
(function () {
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const icon = (n, s) => (window.rlIcon ? window.rlIcon(n, s) : '');

  const BLANK = {
    title: 'Novo relatório',
    page: { size: 'A4', orientation: 'portrait', margin: '20mm 15mm' },
    fields: [],
    pageHeader: [{ type: 'text', value: 'Novo relatório', style: { bold: true, size: 14 } }, { type: 'line' }],
    body: [],
    pageFooter: [{ type: 'text', value: 'Página ${page} de ${pages}', align: 'right', style: { size: 8, color: '#777' } }],
  };

  /** Documento aberto: path null = ainda não salvo. */
  let doc = null;
  let designerReady = false;
  let checkTimer = null;
  let previewSeq = 0;
  let schema = null;

  // ---------------------------------------------------------------- ponte com o designer

  const toDesigner = (msg) => window.relato && window.relato.receive(msg);

  window.relatoHost = (raw) => {
    const msg = JSON.parse(raw);
    switch (msg.type) {
      case 'ready':
        designerReady = true;
        toDesigner({ type: 'theme', dark: true });
        toDesigner({ type: 'caps', classes: false });
        if (doc) loadIntoDesigner(true);
        return;
      case 'change':
        if (!doc || msg.json === doc.text) return;
        doc.text = msg.json;
        setDirty(true);
        scheduleCheck();
        return;
      case 'preview': return preview();
      case 'copy': navigator.clipboard.writeText(msg.text || '').catch(() => {}); return;
      case 'paste':
        navigator.clipboard.readText().then((t) => toDesigner({ type: 'clipboard', text: t })).catch(() => toDesigner({ type: 'clipboard', text: '' }));
        return;
      case 'findClasses': toDesigner({ type: 'classes', query: msg.query, items: [] }); return;
      case 'importFields': toDesigner({ type: 'fields', className: msg.className, fields: [], error: 'Disponível só no plugin do IntelliJ.' }); return;
      default:
    }
  };

  async function loadIntoDesigner(reset) {
    if (!designerReady || !doc) return;
    if (!schema) {
      const r = await window.api.engine('schema');
      if (r.error) return engineError(r.error);
      schema = r.schema;
    }
    let normalized;
    try {
      normalized = JSON.stringify(JSON.parse(stripComments(doc.text)));
    } catch (e) {
      toDesigner({ type: 'invalid', message: e.message });
      return;
    }
    toDesigner({ type: 'load', json: normalized, schema, fileName: doc.name, reset: !!reset });
    scheduleCheck(0);
  }

  function stripComments(s) {
    return s.replace(/^\s*\/\/.*$/gm, '');
  }

  function scheduleCheck(delay = 400) {
    clearTimeout(checkTimer);
    checkTimer = setTimeout(async () => {
      if (!doc) return;
      const r = await window.api.engine('check', { json: doc.text });
      if (!r.error) toDesigner({ type: 'problems', items: r.items || [] });
    }, delay);
  }

  async function preview() {
    if (!doc) return;
    const seq = ++previewSeq;
    busy(true);
    const r = await window.api.engine('preview', { json: doc.text, sample: doc.sample, baseDir: doc.dir, dpi: 80 });
    busy(false);
    if (seq !== previewSeq) return; // chegou uma prévia mais nova
    if (r.error && !(r.pages || []).length && /motor|Java/.test(r.error) && !r.total) engineError(r.error);
    toDesigner({ type: 'preview', pages: r.pages || [], total: r.total || 0, notes: r.notes || [], error: r.error || null });
  }

  // ---------------------------------------------------------------- estado do documento

  function setDoc(d) {
    doc = d;
    document.body.classList.toggle('no-doc', !doc);
    updateTitle();
    if (doc) loadIntoDesigner(true);
    else refreshWelcome();
  }

  function setDirty(v) {
    if (!doc) return;
    doc.dirty = v;
    window.api.setDirty(v);
    updateTitle();
  }

  function updateTitle() {
    const dn = $('docname');
    dn.classList.toggle('dirty', !!doc?.dirty);
    $('docTitle').textContent = doc ? doc.name : 'Início';
    dn.title = doc ? (doc.path || 'Ainda não salvo') : '';
    window.api.setTitle(doc ? `${doc.dirty ? '● ' : ''}${doc.name} — Relato` : 'Relato');
  }

  function fromFile(r, asCopy) {
    return {
      path: asCopy ? null : r.path,
      name: asCopy ? r.name.replace(/\.report\.json$/i, '') + ' (cópia).report.json' : r.name,
      dir: r.dir,
      text: r.text,
      sample: r.sample,
      dirty: false,
    };
  }

  // ---------------------------------------------------------------- ações

  /** Pergunta antes de descartar alterações. Resolve true se pode seguir. */
  async function confirmDiscard() {
    if (!doc || !doc.dirty) return true;
    const choice = await ask('Salvar as alterações?', `"${doc.name}" tem alterações que não foram salvas.`, [
      { id: 'cancel', label: 'Cancelar' },
      { id: 'discard', label: 'Descartar', cls: 'danger' },
      { id: 'save', label: 'Salvar', cls: 'primary' },
    ]);
    if (choice === 'save') return save(false);
    return choice === 'discard';
  }

  async function newDoc() {
    if (!(await confirmDiscard())) return;
    setDoc({ path: null, name: 'novo-relatorio.report.json', dir: null, text: JSON.stringify(BLANK, null, 2) + '\n', sample: null, dirty: false });
    toast('Relatório novo. Comece pelo botão Relatório: declare os campos dos dados e arraste para o layout.');
  }

  async function openDialog() {
    if (!(await confirmDiscard())) return;
    const r = await window.api.openDialog();
    if (!r) return;
    if (r.error) return toast(r.error, true);
    setDoc(fromFile(r));
  }

  async function openPath(file) {
    if (!(await confirmDiscard())) return;
    const r = await window.api.readReport(file);
    if (r.error) return toast('Não foi possível abrir: ' + r.error, true);
    setDoc(fromFile(r));
  }

  async function openExample(file) {
    if (!(await confirmDiscard())) return;
    const r = await window.api.readExample(file);
    if (r.error) return toast(r.error, true);
    setDoc(fromFile(r, true));
    toast('Exemplo aberto como cópia. Salve para guardar suas alterações.');
  }

  async function save(saveAs) {
    if (!doc) return false;
    const r = await window.api.saveReport({ path: doc.path, name: doc.name, text: doc.text, sample: doc.sample, saveAs });
    if (!r) return false;
    if (r.error) { toast('Erro ao salvar: ' + r.error, true); return false; }
    const renamed = r.name !== doc.name;
    Object.assign(doc, { path: r.path, name: r.name, dir: r.dir });
    setDirty(false);
    if (renamed) toDesigner({ type: 'load', json: JSON.stringify(JSON.parse(stripComments(doc.text))), fileName: doc.name });
    flashSaved();
    return true;
  }

  function flashSaved() {
    const dn = $('docname');
    dn.classList.add('saved');
    setTimeout(() => dn.classList.remove('saved'), 1200);
  }

  async function closeDoc() {
    if (!(await confirmDiscard())) return;
    window.api.setDirty(false);
    setDoc(null);
  }

  async function exportAs(format) {
    if (!doc) return;
    closeMenu();
    busy(true, 'exportando…');
    const r = await window.api.exportReport({ format, name: doc.name, dir: doc.dir, json: doc.text, sample: doc.sample });
    busy(false);
    if (!r) return;
    if (r.error) return toast('Não foi possível exportar:\n' + r.error, true);
    const kb = Math.max(1, Math.round(r.bytes / 1024));
    toast(`Exportado: ${r.path.split(/[\\/]/).pop()} (${kb} KB)`, false, [
      { label: 'Abrir', primary: true, run: () => window.api.openPath(r.path) },
      { label: 'Mostrar na pasta', run: () => window.api.reveal(r.path) },
    ]);
  }

  // ---------------------------------------------------------------- dados de exemplo e JSON

  let codeMode = null;

  function openCode(mode) {
    if (!doc) return;
    codeMode = mode;
    const sample = mode === 'sample';
    $('codeIcon').innerHTML = icon(sample ? 'database' : 'code', 18);
    $('codeTitle').textContent = sample ? 'Dados de exemplo' : 'JSON do relatório';
    $('codeSub').textContent = sample
      ? 'Usados no preview e na exportação. Formato: {"params": {...}, "data": [...]}. Ao salvar, vão para '
        + (doc.path ? doc.path.split(/[\\/]/).pop().replace(/\.report\.json$/i, '.sample.json') : 'um .sample.json ao lado do relatório') + '.'
      : 'O mesmo conteúdo do arquivo .report.json. Alterações aqui voltam para o layout.';
    $('codeGen').classList.toggle('hidden', !sample);
    $('codeText').value = sample ? (doc.sample || '') : doc.text;
    $('codeErr').textContent = '';
    if (sample && !doc.sample) $('codeText').placeholder = 'Vazio: o preview inventa dados a partir dos campos. Use "Gerar a partir dos campos" para começar.';
    $('codeModal').classList.add('on');
    setTimeout(() => { const t = $('codeText'); t.focus(); t.setSelectionRange(0, 0); t.scrollTop = 0; }, 30);
  }

  async function generateSample() {
    const r = await window.api.engine('sample', { json: doc.text, rows: 12 });
    if (r.error) { $('codeErr').textContent = r.error; return; }
    $('codeText').value = r.text;
    $('codeErr').textContent = '';
  }

  function applyCode() {
    const text = $('codeText').value;
    if (codeMode === 'sample' && !text.trim()) {
      doc.sample = null;
    } else {
      try {
        JSON.parse(stripComments(text));
      } catch (e) {
        $('codeErr').textContent = 'JSON inválido: ' + e.message;
        return;
      }
      if (codeMode === 'sample') doc.sample = text;
      else {
        doc.text = text.endsWith('\n') ? text : text + '\n';
        loadIntoDesigner(false);
      }
    }
    setDirty(true);
    closeModal('codeModal');
    if (codeMode === 'sample' && document.body.matches('.previewing, .splitting')) preview();
  }

  // ---------------------------------------------------------------- tela inicial

  async function refreshWelcome() {
    const [ex, rec] = await Promise.all([window.api.examples(), window.api.recent()]);
    $('examples').innerHTML = ex.map((e) => `
      <button class="ex" data-file="${esc(e.file)}">
        <div class="th ${e.thumb ? '' : 'none'}">${e.thumb ? `<img src="${e.thumb}" alt="">` : icon('file', 32)}</div>
        <div class="tx"><b>${esc(e.title)}</b><small>${esc(e.desc)}</small></div>
      </button>`).join('');
    $('recent').innerHTML = rec.length
      ? rec.map((r) => `<div class="rc" data-file="${esc(r.path)}" title="${esc(r.path)}">${icon('file', 16)}<div><b>${esc(r.name.replace(/\.report\.json$/i, ''))}</b><small>${esc(r.dir)}</small></div><span class="x" data-forget="1" title="Tirar da lista">${icon('x', 13)}</span></div>`).join('')
      : '<div class="empty-note">Os relatórios que você abrir aparecem aqui.</div>';
  }

  // ---------------------------------------------------------------- motor

  let busyCount = 0;
  function busy(on, label) {
    busyCount = Math.max(0, busyCount + (on ? 1 : -1));
    const el = $('engineState');
    el.classList.toggle('busy', busyCount > 0);
    if (busyCount > 0) el.querySelector('.lbl').textContent = label || 'gerando…';
  }

  function engineError(msg) {
    const el = $('engineState');
    el.className = 'engine err';
    el.querySelector('.lbl').textContent = 'motor parado · clique para reiniciar';
    el.title = msg;
    toast('O motor de relatórios (Java) não respondeu:\n' + msg, true);
  }

  async function warmEngine() {
    const el = $('engineState');
    const t0 = performance.now();
    const r = await window.api.engine('schema');
    if (r.error) return engineError(r.error);
    schema = r.schema;
    el.className = 'engine ok';
    el.title = `Motor pronto (${Math.round(performance.now() - t0)} ms)`;
  }

  $('engineState').addEventListener('click', async () => {
    const el = $('engineState');
    if (!el.classList.contains('err')) return;
    el.className = 'engine';
    el.querySelector('.lbl').textContent = 'reiniciando motor…';
    await window.api.restartEngine();
    await warmEngine();
  });

  // ---------------------------------------------------------------- modais, menu, avisos

  function closeModal(id) { $(id).classList.remove('on'); }

  function ask(title, text, buttons) {
    return new Promise((resolve) => {
      $('askTitle').textContent = title;
      $('askText').textContent = text;
      const box = $('askButtons');
      box.innerHTML = '<div class="spacer"></div>' + buttons.map((b) => `<button data-id="${b.id}" class="${b.cls || ''}">${esc(b.label)}</button>`).join('');
      const done = (id) => { closeModal('askModal'); box.onclick = null; resolve(id); };
      box.onclick = (e) => { const b = e.target.closest('button'); if (b) done(b.dataset.id); };
      $('askModal').dataset.cancel = 'cancel';
      $('askModal')._done = done;
      $('askModal').classList.add('on');
      setTimeout(() => box.querySelector('.primary')?.focus(), 30);
    });
  }

  function toast(msg, err, actions = []) {
    const t = document.createElement('div');
    t.className = 'toast' + (err ? ' err' : '');
    t.innerHTML = `<div class="msg">${esc(msg)}</div>` + actions.map((a, i) => `<button data-i="${i}" class="${a.primary ? 'primary' : ''}">${esc(a.label)}</button>`).join('')
      + `<button class="icon-btn ghost" data-x="1">${icon('x', 13)}</button>`;
    t.addEventListener('click', (e) => {
      const b = e.target.closest('button');
      if (!b) return;
      if (b.dataset.i) actions[+b.dataset.i].run();
      t.remove();
    });
    $('toasts').append(t);
    setTimeout(() => t.remove(), err ? 12000 : actions.length ? 9000 : 5000);
  }

  const closeMenu = () => $('exportMenu').classList.remove('on');

  // ---------------------------------------------------------------- eventos

  function wire() {
    $('btn-new').onclick = newDoc;
    $('w-new').onclick = newDoc;
    $('btn-open').onclick = openDialog;
    $('w-open').onclick = openDialog;
    $('btn-save').onclick = (e) => save(e.shiftKey);
    $('btn-sample').onclick = () => openCode('sample');
    $('btn-json').onclick = () => openCode('json');
    $('btn-help').onclick = () => $('helpModal').classList.add('on');
    $('btn-export').onclick = (e) => { e.stopPropagation(); $('exportMenu').classList.toggle('on'); };
    $('exportMenu').addEventListener('click', (e) => { const b = e.target.closest('button[data-fmt]'); if (b) exportAs(b.dataset.fmt); });
    document.addEventListener('click', (e) => { if (!e.target.closest('.menu-wrap')) closeMenu(); });
    $('codeGen').onclick = generateSample;
    $('codeApply').onclick = applyCode;
    $('codeText').addEventListener('keydown', (e) => {
      if (e.key === 'Tab') { e.preventDefault(); document.execCommand('insertText', false, '  '); }
      if (e.key === 'Enter' && e.ctrlKey) applyCode();
      e.stopPropagation();
    });
    document.querySelectorAll('.modal-back').forEach((m) => {
      m.addEventListener('mousedown', (e) => { if (e.target === m) closeAny(m); });
      m.querySelectorAll('[data-close]').forEach((b) => (b.onclick = () => closeAny(m)));
    });

    $('examples').addEventListener('click', (e) => { const b = e.target.closest('.ex'); if (b) openExample(b.dataset.file); });
    $('recent').addEventListener('click', async (e) => {
      const rc = e.target.closest('.rc');
      if (!rc) return;
      if (e.target.closest('[data-forget]')) { await window.api.forgetRecent(rc.dataset.file); refreshWelcome(); return; }
      openPath(rc.dataset.file);
    });

    // atalhos do app (antes do designer)
    window.addEventListener('keydown', (e) => {
      const k = e.key.toLowerCase();
      const open = document.querySelector('.modal-back.on');
      if (e.key === 'Escape') {
        if (open) { closeAny(open); e.preventDefault(); e.stopPropagation(); }
        closeMenu();
        return;
      }
      if (open) return;
      let run = null;
      if (e.ctrlKey && !e.altKey) {
        if (k === 'n') run = newDoc;
        else if (k === 'o') run = openDialog;
        else if (k === 's' && doc) run = () => save(e.shiftKey);
        else if (k === 'e' && doc) run = () => exportAs('pdf');
        else if (k === 'w' && doc) run = closeDoc;
      }
      if (e.key === 'F1') run = () => $('helpModal').classList.add('on');
      if (run) { e.preventDefault(); e.stopPropagation(); run(); }
    }, true);

    // arrastar arquivo do Explorer
    let dragDepth = 0;
    const isFileDrag = (e) => [...(e.dataTransfer?.types || [])].includes('Files');
    window.addEventListener('dragenter', (e) => { if (!isFileDrag(e)) return; dragDepth++; $('dropOverlay').classList.add('on'); });
    window.addEventListener('dragleave', (e) => { if (!isFileDrag(e)) return; if (--dragDepth <= 0) { dragDepth = 0; $('dropOverlay').classList.remove('on'); } });
    window.addEventListener('dragover', (e) => { if (isFileDrag(e)) e.preventDefault(); });
    window.addEventListener('drop', (e) => {
      if (!isFileDrag(e)) return;
      e.preventDefault();
      dragDepth = 0;
      $('dropOverlay').classList.remove('on');
      const f = e.dataTransfer.files[0];
      if (!f) return;
      const p = window.api.pathForFile(f);
      if (!/\.json$/i.test(p)) return toast('Solte um arquivo .report.json', true);
      openPath(p);
    });

    window.api.onOpenFile((f) => openPath(f));
    window.api.onConfirmClose(async () => {
      if (await confirmDiscard()) window.api.closeNow();
    });
  }

  function closeAny(m) {
    if (m.id === 'askModal' && m._done) m._done('cancel');
    else m.classList.remove('on');
  }

  // ---------------------------------------------------------------- início

  document.body.classList.add('no-doc');
  document.addEventListener('DOMContentLoaded', async () => {
    wire();
    refreshWelcome();
    warmEngine();
    const pending = await window.api.pendingFile();
    if (pending) openPath(pending);
  });

  window.relatoApp = { get doc() { return doc; }, openPath, openExample, newDoc, exportAs, preview };
})();
