/* Relato Designer — editor visual de *.report.json.
 * Fala com o host (plugin IntelliJ) por mensagens JSON:
 *   host → designer: window.relato.receive({type:'load'|'invalid'|'preview'|'theme', ...})
 *   designer → host: window.relatoHost(JSON.stringify({type:'ready'|'change'|'preview', ...}))
 */
(() => {
  'use strict';

  const BANDS = [
    { key: 'firstPageHeader', label: 'Cabeçalho da 1ª página (substitui o de baixo nela)', cls: 'hdr first', optional: true },
    { key: 'pageHeader', label: 'Cabeçalho da página (todas as páginas)', cls: 'hdr' },
    { key: 'body', label: 'Conteúdo', cls: 'body' },
    { key: 'pageFooter', label: 'Rodapé da página (todas as páginas)', cls: 'ftr' },
  ];

  const TYPES = {
    text: { label: 'Texto', icon: 'T', make: () => ({ type: 'text', value: 'Texto' }) },
    table: { label: 'Tabela', icon: '▦', make: () => ({ type: 'table', zebra: true, columns: defaultColumns() }) },
    list: { label: 'Lista (repetir)', icon: '≡', make: () => ({ type: 'list', source: firstListField() || 'rows', content: [] }) },
    fields: { label: 'Ficha (rótulo: valor)', icon: '⊞', make: () => ({ type: 'fields', columns: 2, items: defaultFieldItems() }) },
    row: { label: 'Colunas', icon: '▥', make: () => ({ type: 'row', columns: [{ width: '50%', content: [] }, { width: '50%', content: [] }] }) },
    box: { label: 'Caixa', icon: '▢', make: () => ({ type: 'box', style: { border: true, padding: 4 }, content: [] }) },
    image: { label: 'Imagem', icon: '▣', make: () => ({ type: 'image', src: 'classpath:img/logo.png', width: '30mm' }) },
    line: { label: 'Linha', icon: '—', make: () => ({ type: 'line' }) },
    spacer: { label: 'Espaço', icon: '↕', make: () => ({ type: 'spacer', height: '5mm' }) },
    pageBreak: { label: 'Quebra de página', icon: '⤓', make: () => ({ type: 'pageBreak' }) },
    html: { label: 'HTML', icon: '<>', make: () => ({ type: 'html', value: '<b>HTML</b>' }) },
    barcode: { label: 'Código de barras / QR', icon: '▤', make: () => ({ type: 'barcode', value: '${codigo}', format: 'qr', width: '25mm' }) },
    include: { label: 'Sub-relatório', icon: '⎘', make: () => ({ type: 'include', template: 'outro-relatorio' }) },
  };

  const SNIPPETS = [
    { label: 'Título', icon: 'H', el: () => ({ type: 'text', value: 'Título do relatório', style: { bold: true, size: 14 } }) },
    { label: 'Página X de Y', icon: '#', el: () => ({ type: 'text', value: 'Página ${page} de ${pages}', align: 'right', style: { size: 8 } }) },
    { label: 'Data de emissão', icon: '◷', el: () => ({ type: 'text', value: 'Emitido em ${now()}', style: { size: 8, color: '#777' } }) },
  ];

  const LABELS = {
    value: 'Valor', format: 'Formato', align: 'Alinhamento', style: 'Estilo', visible: 'Visível se (expressão)', src: 'Imagem (src)',
    width: 'Largura', height: 'Altura', gap: 'Espaço entre colunas', columns: 'Colunas', keepTogether: 'Não quebrar entre páginas',
    source: 'Fonte de dados', sortBy: 'Ordenar por', showHeader: 'Mostrar cabeçalho', zebra: 'Linhas alternadas', grid: 'Grade',
    totalLabel: 'Rótulo do total geral', emptyText: 'Texto quando vazio', header: 'Cabeçalho', total: 'Total', headerStyle: 'Estilo do cabeçalho',
    groups: 'Grupos', by: 'Agrupar por', footer: 'Subtotais no rodapé do grupo', footerLabel: 'Rótulo do subtotal', pageBreak: 'Nova página por grupo',
    as: 'Nome do item', separator: 'Separador entre itens', pageBreakBetween: 'Nova página por item', labelWidth: 'Largura do rótulo',
    items: 'Itens', label: 'Rótulo', span: 'Ocupa (colunas)', color: 'Cor', thickness: 'Espessura', title: 'Título', locale: 'Idioma',
    page: 'Página', size: 'Tamanho', orientation: 'Orientação', margin: 'Margens', css: 'CSS extra', parameters: 'Parâmetros',
    fields: 'Campos dos dados', dataClass: 'Classe dos dados', name: 'Nome', type: 'Tipo', default: 'Valor padrão', sample: 'Valor de exemplo',
  };

  const S = {
    model: null, schema: null, sel: null, colSel: null, fileName: '', history: [], future: [], mergeKey: null, mergeAt: 0,
    drag: null, preview: false, zoom: 'fit', problems: [], multi: [], clip: null, open: new Set(), hasHost: false, invalid: null,
  };

  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const clone = (x) => JSON.parse(JSON.stringify(x));
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

  // ---------------------------------------------------------------- host

  const host = {
    send(msg) {
      if (typeof window.relatoHost === 'function') window.relatoHost(JSON.stringify(msg));
      else if (window.relatoMockHost) window.relatoMockHost(msg);
    },
  };

  window.relato = {
    hostReady() {
      S.hasHost = true;
      host.send({ type: 'ready' });
    },
    receive(msg) {
      if (typeof msg === 'string') msg = JSON.parse(msg);
      switch (msg.type) {
        case 'load': return onLoad(msg);
        case 'invalid': return onInvalid(msg.message);
        case 'preview': return onPreview(msg);
        case 'theme': document.body.classList.toggle('dark', !!msg.dark); return;
        case 'classes': return onClasses(msg);
        case 'clipboard': return pasteText(msg.text);
        case 'fields': return onFields(msg);
        case 'problems':
          S.problems = msg.items || [];
          decorateProblems();
          if (!S.sel || S.problems.length) renderProblemsBox();
          return;
        default: return;
      }
    },
    /** Para testes/depuração. */
    state: S,
  };

  function onLoad(msg) {
    if (msg.schema) S.schema = typeof msg.schema === 'string' ? JSON.parse(msg.schema) : msg.schema;
    if (msg.fileName) {
      S.fileName = msg.fileName;
      $('title').textContent = msg.fileName;
    }
    const model = typeof msg.json === 'string' ? JSON.parse(msg.json) : msg.json;
    S.invalid = null;
    $('banner').classList.remove('on');
    if (S.model && same(model, S.model)) return;
    const first = !S.model;
    S.model = normalize(model);
    if (first) {
      S.history = [];
      S.future = [];
    }
    if (S.sel && !isElement(getAt(S.sel))) S.sel = null;
    renderAll();
    if (S.preview) requestPreview();
  }

  function onInvalid(message) {
    S.invalid = message;
    $('bannerText').innerHTML = '<b>O JSON está inválido</b>\n\n' + esc(message) + '\n\nCorrija na aba de texto; o designer volta sozinho.';
    $('banner').classList.add('on');
  }

  function normalize(m) {
    if (!m || typeof m !== 'object' || Array.isArray(m)) m = {};
    for (const b of BANDS) if (!Array.isArray(m[b.key])) m[b.key] = [];
    return m;
  }

  /** JSON para o arquivo: bandas vazias somem e objetos curtos ficam numa linha. */
  function serialize() {
    const m = clone(S.model);
    for (const b of BANDS) if (Array.isArray(m[b.key]) && m[b.key].length === 0 && b.key !== 'body') delete m[b.key];
    return pretty(m, '') + '\n';
  }

  function pretty(v, ind) {
    if (v === null || typeof v !== 'object') return JSON.stringify(v);
    if (ind !== '') {
      const one = inline(v);
      if (one.length + ind.length <= 110) return one;
    }
    const inner = ind + '  ';
    if (Array.isArray(v)) {
      if (!v.length) return '[]';
      return '[\n' + v.map((x) => inner + pretty(x, inner)).join(',\n') + '\n' + ind + ']';
    }
    const keys = Object.keys(v).filter((k) => v[k] !== undefined);
    if (!keys.length) return '{}';
    return '{\n' + keys.map((k) => inner + JSON.stringify(k) + ': ' + pretty(v[k], inner)).join(',\n') + '\n' + ind + '}';
  }

  function inline(v) {
    if (v === null || typeof v !== 'object') return JSON.stringify(v);
    if (Array.isArray(v)) return v.length ? '[ ' + v.map(inline).join(', ') + ' ]' : '[]';
    const keys = Object.keys(v).filter((k) => v[k] !== undefined);
    return keys.length ? '{ ' + keys.map((k) => JSON.stringify(k) + ': ' + inline(v[k])).join(', ') + ' }' : '{}';
  }

  // ---------------------------------------------------------------- modelo

  function getAt(path) {
    let o = S.model;
    for (const k of path || []) {
      if (o == null) return undefined;
      o = o[k];
    }
    return o;
  }

  const isElement = (x) => x && typeof x === 'object' && !Array.isArray(x) && typeof x.type === 'string';

  /** Listas de filhos (zonas de soltar) de um elemento. */
  function childZones(el) {
    switch (el.type) {
      case 'row': return (el.columns || []).map((c, i) => ({ path: ['columns', i, 'content'] }));
      case 'box': return [{ path: ['content'] }];
      case 'list': return [{ path: ['header'] }, { path: ['content'] }, { path: ['footer'] }];
      default: return [];
    }
  }

  function findPath(target) {
    function walk(arr, path) {
      for (let i = 0; i < arr.length; i++) {
        const el = arr[i];
        const p = path.concat(i);
        if (el === target) return p;
        if (!isElement(el)) continue;
        for (const z of childZones(el)) {
          const sub = z.path.reduce((o, k) => (o == null ? o : o[k]), el);
          if (Array.isArray(sub)) {
            const r = walk(sub, p.concat(z.path));
            if (r) return r;
          }
        }
      }
      return null;
    }
    for (const b of BANDS) {
      const r = walk(S.model[b.key] || [], [b.key]);
      if (r) return r;
    }
    return null;
  }

  function ensureArray(path) {
    let o = S.model;
    for (let i = 0; i < path.length; i++) {
      const k = path[i];
      if (o[k] == null) o[k] = i === path.length - 1 ? [] : typeof path[i + 1] === 'number' ? [] : {};
      o = o[k];
    }
    return o;
  }

  function countBlocks() {
    let n = 0;
    (function walk(arr) {
      for (const el of arr || []) {
        if (!isElement(el)) continue;
        n++;
        for (const z of childZones(el)) walk(z.path.reduce((o, k) => (o == null ? o : o[k]), el));
      }
    })([].concat(...BANDS.map((b) => S.model[b.key] || [])));
    return n;
  }

  // ---------------------------------------------------------------- histórico

  /**
   * Aplica uma alteração: guarda o estado anterior, avisa o host e redesenha.
   * mergeKey junta edições seguidas do mesmo campo num único passo de desfazer.
   */
  function commit(fn, opts = {}) {
    if (S.invalid) return;
    const before = JSON.stringify({ m: S.model, s: S.sel });
    const now = Date.now();
    const merge = opts.merge && opts.merge === S.mergeKey && now - S.mergeAt < 1500;
    fn();
    if (!merge) {
      S.history.push(before);
      if (S.history.length > 200) S.history.shift();
    }
    S.mergeKey = opts.merge || null;
    S.mergeAt = now;
    S.future = [];
    changed(opts.props !== false);
  }

  function changed(redrawProps) {
    host.send({ type: 'change', json: serialize() });
    renderCanvas();
    renderPalette();
    if (redrawProps) renderProps();
    updateButtons();
    if (S.preview) requestPreview();
  }

  function undo() {
    if (!S.history.length || S.invalid) return;
    S.future.push(JSON.stringify({ m: S.model, s: S.sel }));
    restore(S.history.pop());
  }

  function redo() {
    if (!S.future.length || S.invalid) return;
    S.history.push(JSON.stringify({ m: S.model, s: S.sel }));
    restore(S.future.pop());
  }

  function restore(snap) {
    const o = JSON.parse(snap);
    S.model = o.m;
    S.sel = o.s && isElement(getAt(o.s)) ? o.s : null;
    S.mergeKey = null;
    changed(true);
  }

  // ---------------------------------------------------------------- operações

  function insertAt(containerPath, index, el) {
    commit(() => {
      const arr = ensureArray(containerPath);
      arr.splice(Math.max(0, Math.min(index, arr.length)), 0, el);
      S.sel = findPath(el);
      S.colSel = null;
    });
  }

  function moveTo(fromPath, containerPath, index) {
    const el = getAt(fromPath);
    if (!el) return;
    // não mover um bloco para dentro dele mesmo
    if (containerPath.length > fromPath.length && same(containerPath.slice(0, fromPath.length), fromPath)) return;
    commit(() => {
      const target = ensureArray(containerPath);
      const src = getAt(fromPath.slice(0, -1));
      const i = fromPath[fromPath.length - 1];
      src.splice(i, 1);
      if (src === target && i < index) index--;
      target.splice(Math.max(0, Math.min(index, target.length)), 0, el);
      S.sel = findPath(el);
    });
  }

  function removeMany(paths) {
    if (!paths.length) return;
    commit(() => {
      for (const p of [...paths].sort(cmpPath).reverse()) {
        const arr = getAt(p.slice(0, -1));
        if (Array.isArray(arr)) arr.splice(p[p.length - 1], 1);
      }
      S.sel = null;
      S.multi = [];
      S.colSel = null;
    });
  }

  function duplicateMany(paths) {
    if (!paths.length) return;
    commit(() => {
      const copies = [];
      for (const p of [...paths].sort(cmpPath).reverse()) {
        const arr = getAt(p.slice(0, -1));
        const c = clone(getAt(p));
        arr.splice(p[p.length - 1] + 1, 0, c);
        copies.push(c);
      }
      S.multi = copies.map(findPath).filter(Boolean);
      S.sel = S.multi[S.multi.length - 1] || null;
    });
  }

  // ---------- copiar / colar (clipboard da IDE: funciona entre relatórios)

  function copyPaths(paths) {
    if (!paths.length) return;
    const els = [...paths].sort(cmpPath).map((p) => clone(getAt(p)));
    const text = JSON.stringify(els.length === 1 ? els[0] : els, null, 2);
    S.clip = text;
    host.send({ type: 'copy', text });
    $('status').textContent = els.length + ' bloco(s) copiado(s)';
  }

  function cutPaths(paths) {
    copyPaths(paths);
    removeMany(paths);
  }

  function requestPaste() {
    if (S.hasHost) host.send({ type: 'paste' });
    else pasteText(S.clip);
  }

  /** Cola blocos (JSON de um elemento ou lista) depois do selecionado, ou no fim do conteúdo. */
  function pasteText(text) {
    if (!text) return;
    let v;
    try {
      v = JSON.parse(text);
    } catch (e) {
      // texto simples vira um bloco de texto
      v = { type: 'text', value: String(text).slice(0, 2000) };
    }
    const els = (Array.isArray(v) ? v : [v]).filter(isElement);
    if (!els.length) { $('status').textContent = 'nada para colar'; return; }
    let container = ['body'];
    let index = (S.model.body || []).length;
    if (S.sel) {
      container = S.sel.slice(0, -1);
      index = S.sel[S.sel.length - 1] + 1;
    }
    commit(() => {
      const arr = ensureArray(container);
      arr.splice(index, 0, ...els);
      S.multi = els.map(findPath).filter(Boolean);
      S.sel = S.multi[S.multi.length - 1] || null;
    });
    $('status').textContent = els.length + ' bloco(s) colado(s)';
  }

  function removeAt(path) {
    commit(() => {
      getAt(path.slice(0, -1)).splice(path[path.length - 1], 1);
      S.sel = null;
      S.colSel = null;
    });
  }

  function duplicate(path) {
    const el = clone(getAt(path));
    const parent = path.slice(0, -1);
    insertAt(parent, path[path.length - 1] + 1, el);
  }

  function shift(path, delta) {
    const arr = getAt(path.slice(0, -1));
    const i = path[path.length - 1];
    const j = i + delta;
    if (!arr || j < 0 || j >= arr.length) return;
    commit(() => {
      const [el] = arr.splice(i, 1);
      arr.splice(j, 0, el);
      S.sel = path.slice(0, -1).concat(j);
    });
  }

  // ---------------------------------------------------------------- campos (fields)

  function flatFields(fields, prefix, out, inList) {
    for (const f of fields || []) {
      if (!f || !f.name) continue;
      const path = prefix ? prefix + '.' + f.name : f.name;
      out.push({ path, field: f, inList });
      if (f.type === 'object') flatFields(f.fields, path, out, inList);
    }
    return out;
  }

  function simpleFields() {
    return flatFields(S.model?.fields, '', [], false).filter((x) => x.field.type !== 'list' && x.field.type !== 'object');
  }

  function firstListField() {
    const f = (S.model?.fields || []).find((x) => x.type === 'list');
    return f ? f.name : null;
  }

  function formatFor(type) {
    return { money: 'currency', date: 'date', datetime: 'datetime', decimal: 'decimal', number: 'number', boolean: 'Sim|Não' }[type];
  }

  function nice(name) {
    const last = String(name).split('.').pop();
    return last.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());
  }

  function columnFor(path, field) {
    const c = { header: field.label || nice(path), value: '${' + path + '}' };
    const f = formatFor(field.type);
    if (f) c.format = f;
    if (field.type === 'money' || field.type === 'decimal' || field.type === 'number') c.total = 'sum';
    return c;
  }

  function defaultColumns() {
    const fs = simpleFields().slice(0, 4);
    if (!fs.length) return [{ header: 'Coluna', value: '${campo}' }];
    return fs.map((x) => columnFor(x.path, x.field));
  }

  function defaultFieldItems() {
    const fs = simpleFields().slice(0, 4);
    if (!fs.length) return [{ label: 'Rótulo', value: '${campo}' }];
    return fs.map((x) => ({ label: x.field.label || nice(x.path), value: '${' + x.path + '}', ...(formatFor(x.field.type) ? { format: formatFor(x.field.type) } : {}) }));
  }

  /** Bloco criado ao soltar um campo numa área vazia. */
  function elementForField(p) {
    if (p.field.type === 'list') {
      const cols = flatFields(p.field.fields, '', [], true).filter((x) => x.field.type !== 'list' && x.field.type !== 'object');
      return { type: 'table', source: p.path, grid: true, columns: cols.length ? cols.map((x) => columnFor(x.path, x.field)) : [{ header: 'Coluna', value: '${campo}' }] };
    }
    const t = { type: 'text', value: (p.field.label || nice(p.path)) + ': ${' + p.path + '}' };
    const f = formatFor(p.field.type);
    if (f) t.format = f;
    return t;
  }

  // ---------------------------------------------------------------- estilo

  const UNITLESS = new Set(['font-weight', 'line-height', 'opacity', 'z-index']);

  function styleCss(style) {
    if (!style) return '';
    if (typeof style === 'string') return style;
    let css = '';
    for (const [k, v] of Object.entries(style)) {
      if (v == null || v === '') continue;
      switch (k) {
        case 'bold': css += 'font-weight:' + (v ? 'bold' : 'normal') + ';'; break;
        case 'italic': css += 'font-style:' + (v ? 'italic' : 'normal') + ';'; break;
        case 'underline': css += 'text-decoration:' + (v ? 'underline' : 'none') + ';'; break;
        case 'uppercase': css += 'text-transform:' + (v ? 'uppercase' : 'none') + ';'; break;
        case 'size': css += 'font-size:' + len('font-size', v) + ';'; break;
        case 'align': css += 'text-align:' + v + ';'; break;
        case 'valign': css += 'vertical-align:' + v + ';'; break;
        case 'font': css += 'font-family:' + v + ';'; break;
        case 'background': css += 'background-color:' + v + ';'; break;
        case 'border': css += 'border:' + (v === true ? '0.5pt solid #999' : v) + ';'; break;
        default: {
          const prop = k.includes('-') ? k : k.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase());
          css += prop + ':' + len(prop, v) + ';';
        }
      }
    }
    return css;
  }

  function len(prop, v) {
    return typeof v === 'number' && !UNITLESS.has(prop) ? v + 'pt' : String(v);
  }

  // ---------------------------------------------------------------- render: paleta

  function renderPalette() {
    const p = $('palette');
    let h = '<div class="sec">Blocos</div>';
    for (const [type, t] of Object.entries(TYPES)) {
      h += `<div class="pitem" data-new="${type}"><span class="ic">${esc(t.icon)}</span>${esc(t.label)}</div>`;
    }
    h += '<div class="sec">Prontos</div>';
    SNIPPETS.forEach((s, i) => {
      h += `<div class="pitem" data-snip="${i}"><span class="ic">${esc(s.icon)}</span>${esc(s.label)}</div>`;
    });
    h += '<div class="sec">Campos dos dados</div>';
    const fields = S.model?.fields || [];
    if (!fields.length) h += '<div class="hint">Declare os campos em ⚙ Relatório para arrastá-los para o layout.</div>';
    h += fieldItems(fields, '', false);
    const params = S.model?.parameters || [];
    if (params.length) {
      h += '<div class="sec">Parâmetros</div>';
      for (const prm of params) {
        if (!prm?.name) continue;
        h += `<div class="pitem field" data-param="${esc(prm.name)}"><span class="ic">$</span>${esc(prm.name)}<span class="ft">${esc(prm.type || '')}</span></div>`;
      }
    }
    p.innerHTML = h;
  }

  function fieldItems(fields, prefix, inList) {
    let h = '';
    for (const f of fields) {
      if (!f?.name) continue;
      const path = prefix ? prefix + '.' + f.name : f.name;
      const icon = f.type === 'list' ? '≡' : f.type === 'object' ? '{}' : '•';
      h += `<div class="pitem field" data-field="${esc(path)}" data-inlist="${inList ? 1 : 0}"><span class="ic">${icon}</span>${esc(f.label || f.name)}<span class="ft">${esc(f.type || 'string')}</span></div>`;
      if ((f.type === 'list' || f.type === 'object') && f.fields?.length) {
        // campos de uma lista são relativos ao item (usados dentro da tabela/lista dessa fonte)
        h += '<div class="fnest">' + fieldItems(f.fields, f.type === 'list' ? '' : path, inList || f.type === 'list') + '</div>';
      }
    }
    return h;
  }

  function fieldByPath(path, inList) {
    const all = [];
    (function walk(fields, prefix, inL) {
      for (const f of fields || []) {
        if (!f?.name) continue;
        const p = prefix ? prefix + '.' + f.name : f.name;
        all.push({ path: p, field: f, inList: inL });
        if (f.type === 'list') walk(f.fields, '', true);
        else if (f.type === 'object') walk(f.fields, p, inL);
      }
    })(S.model?.fields, '', false);
    return all.find((x) => x.path === path && x.inList === inList) || all.find((x) => x.path === path);
  }

  // ---------------------------------------------------------------- render: canvas

  function pageBox() {
    const pg = S.model.page || {};
    const sizes = { a3: [297, 420], a4: [210, 297], a5: [148, 210], letter: [216, 279], legal: [216, 356] };
    let [w, h] = sizes[String(pg.size || 'A4').toLowerCase()] || [210, 297];
    const m = String(pg.size || '').match(/([\d.]+)mm\s+([\d.]+)mm/);
    if (m) [w, h] = [parseFloat(m[1]), parseFloat(m[2])];
    if (pg.orientation === 'landscape') [w, h] = [h, w];
    const margins = String(pg.margin || '20mm 15mm').trim().split(/\s+/);
    const [top, right = top, bottom = top, left = right] = margins;
    return { w, top, right, bottom, left };
  }

  function renderCanvas() {
    if (!S.model) return;
    const pb = pageBox();
    let h = `<div class="page" style="width:${pb.w}mm;padding:0 ${esc(pb.right)} 0 ${esc(pb.left)};${esc(styleCss(S.model.style))}">`;
    const firstEmpty = !(S.model.firstPageHeader || []).length;
    if (firstEmpty && !S.showFirst) h += '<div class="band-add"><a href="#" data-add-first="1">+ cabeçalho diferente na 1ª página</a></div>';
    for (const b of BANDS) {
      if (b.optional && firstEmpty && !S.showFirst) continue;
      h += `<div class="band ${b.cls}"><span class="bname">${esc(b.label)}</span>${zone([b.key], S.model[b.key] || [], 'Arraste blocos aqui')}</div>`;
    }
    h += '</div>';
    $('canvas').innerHTML = h;
    $('status').textContent = countBlocks() + ' bloco(s)';
    applyZoom();
    decorateProblems();
  }

  /** 'fit' ajusta a página à largura disponível; senão, fator fixo. */
  function applyZoom() {
    const page = document.querySelector('#canvas .page');
    if (!page) return;
    page.style.zoom = '';
    let z = S.zoom;
    if (z === 'fit') {
      const avail = (S.preview === 'split' ? $('canvas').clientWidth : $('center').clientWidth) - 48;
      z = Math.max(0.35, Math.min(1.25, avail / page.offsetWidth));
    }
    page.style.zoom = String(z);
    $('zoomLabel').textContent = Math.round(z * 100) + '%';
  }

  function zoomBy(f) {
    const cur = parseFloat(document.querySelector('#canvas .page')?.style.zoom || '1');
    S.zoom = Math.max(0.35, Math.min(2, cur * f));
    applyZoom();
  }

  function zone(path, arr, emptyText) {
    const key = esc(JSON.stringify(path));
    if (!arr.length) return `<div class="dz empty" data-zone="${key}">${esc(emptyText)}</div>`;
    let h = `<div class="dz" data-zone="${key}">`;
    arr.forEach((el, i) => { h += block(el, path.concat(i)); });
    return h + '</div>';
  }

  function block(el, path) {
    if (!isElement(el)) return '';
    const sel = isSelected(path);
    const t = TYPES[el.type];
    const cls = 'blk' + (sel ? ' sel' : '') + (el.visible ? ' hidden-cond' : '');
    let h = `<div class="${cls}" data-path="${esc(JSON.stringify(path))}" data-type="${esc(el.type)}">`;
    h += `<div class="tag">${esc(t ? t.label : el.type)}</div>`;
    h += '<div class="acts"><button data-a="up" title="Subir">↑</button><button data-a="down" title="Descer">↓</button>'
      + '<button data-a="copy" title="Copiar (Ctrl+C)">⎘</button><button data-a="dup" title="Duplicar (Ctrl+D)">⧉</button><button data-a="del" title="Remover (Del)">✕</button></div>';
    h += body(el, path);
    return h + '</div>';
  }

  function chips(str) {
    return esc(str).replace(/\$\{([^}]*)\}/g, (_, x) => `<span class="chip">${x}</span>`);
  }

  function body(el, path) {
    const css = esc(styleCss(el.style));
    switch (el.type) {
      case 'text': {
        const align = el.align ? 'text-align:' + esc(el.align) + ';' : '';
        return `<div class="r-text" style="${align}${css}">${chips(el.value || '') || '&nbsp;'}</div>`;
      }
      case 'html':
        return `<div class="r-html" style="${css}">${esc(el.value || '')}</div>`;
      case 'image': {
        const wrap = el.align ? `text-align:${esc(el.align)}` : '';
        const size = (el.width ? `width:${esc(el.width)};` : '') + (el.height ? `height:${esc(el.height)};` : '');
        const src = String(el.src || '');
        const inner = /^(data:|https?:)/.test(src)
          ? `<img src="${esc(src)}" style="${size}${css}" alt="">`
          : `<span class="r-image" style="${size}${css}">▣ ${chips(src || 'sem src')}</span>`;
        return `<div style="${wrap}">${inner}</div>`;
      }
      case 'line': {
        const c = `border-top:${esc(el.thickness || '0.5pt')} solid ${esc(el.color || '#999')};`;
        return `<div class="r-line" style="${c}${css}"></div>`;
      }
      case 'spacer':
        return `<div class="r-spacer" style="height:${esc(el.height || '5mm')};${css}"></div>`;
      case 'pageBreak':
        return '<div class="r-break"><span>quebra de página</span></div>';
      case 'row': {
        const gap = el.gap || '4mm';
        let h = `<div class="r-row" style="${css}">`;
        (el.columns || []).forEach((c, i) => {
          const w = c.width ? `flex:0 0 ${esc(c.width)};` : 'flex:1 1 0;';
          const pad = i > 0 ? `padding-left:${esc(gap)};` : '';
          const al = c.align ? `text-align:${esc(c.align)};` : '';
          h += `<div class="col" style="${w}${pad}${al}${esc(styleCss(c.style))}">${zone(path.concat('columns', i, 'content'), c.content || [], 'coluna ' + (i + 1))}</div>`;
        });
        return h + '</div>';
      }
      case 'box':
        return `<div class="r-box" style="${css}">${zone(path.concat('content'), el.content || [], 'Arraste blocos para dentro da caixa')}</div>`;
      case 'list': {
        let h = `<div class="r-list" style="${css}">`;
        if ((el.header || []).length) h += '<div class="zl">cabeçalho da lista</div>' + zone(path.concat('header'), el.header, '');
        h += `<div class="zl">para cada item de <span class="src chip">${esc(el.source || '?')}</span>${el.as ? ' como ' + esc(el.as) : ''}</div>`;
        h += zone(path.concat('content'), el.content || [], 'Arraste o conteúdo de cada item aqui');
        h += '<div class="zl">rodapé da lista</div>' + zone(path.concat('footer'), el.footer || [], 'totais da lista (opcional)');
        return h + '</div>';
      }
      case 'fields': {
        const n = Math.max(1, el.columns || 2);
        let h = `<div class="r-fields" style="grid-template-columns:repeat(${n}, auto 1fr);${el.labelWidth ? '' : ''}${css}">`;
        let used = 0;
        for (const it of el.items || []) {
          const span = Math.min(Math.max(1, it.span || 1), n);
          if (used + span > n) used = 0;
          h += `<div class="lb">${chips(it.label || '')}</div><div style="grid-column:span ${span * 2 - 1}">${chips(it.value || '')}</div>`;
          used += span;
        }
        return h + '</div>';
      }
      case 'table':
        return table(el, path, css);
      case 'barcode': {
        const two = !el.format || el.format === 'qr' || el.format === 'datamatrix';
        const w = el.width || (two ? '25mm' : '60mm');
        const hh = el.height || (two ? w : '15mm');
        const wrap = el.align ? `text-align:${esc(el.align)}` : '';
        return `<div style="${wrap}"><span class="r-barcode ${two ? 'qr' : ''}" style="width:${esc(w)};height:${esc(hh)};${css}">${esc((el.format || 'qr').toUpperCase())}</span>`
          + (el.showText && !two ? `<div style="font-family:monospace;font-size:8pt;${wrap}">${chips(el.value || '')}</div>` : '')
          + `<div class="r-barcode-v">${chips(el.value || '')}</div></div>`;
      }
      case 'include':
        return `<div class="r-include" style="${css}">⎘ sub-relatório <b>${esc(el.template || '?')}</b>${el.data ? ' · dados: <span class="chip">' + esc(el.data) + '</span>' : ''}</div>`;
      default:
        return `<div class="r-html">tipo desconhecido: ${esc(el.type)}</div>`;
    }
  }

  function table(el, path, css) {
    const cols = el.columns || [];
    const n = Math.max(1, cols.length);
    const isSel = S.sel && same(S.sel, path);
    let h = el.source && el.source !== 'rows' ? `<div class="r-table-src">fonte: <span class="chip">${esc(el.source)}</span>${el.sortBy ? ' · ordenado por ' + esc(el.sortBy) : ''}</div>`
      : el.sortBy ? `<div class="r-table-src">ordenado por ${esc(el.sortBy)}</div>` : '';
    h += `<table class="r-table${el.grid ? ' grid' : ''}" style="${css}">`;
    const align = (c) => (c.align ? `text-align:${esc(c.align)};` : c.total && c.total !== 'count' ? 'text-align:right;' : '');
    if (el.showHeader !== false) {
      h += '<tr>' + cols.map((c, i) => {
        const cs = isSel && S.colSel === i ? ' class="csel"' : '';
        return `<th data-col="${i}"${cs} style="${c.width ? 'width:' + esc(c.width) + ';' : ''}${align(c)}${esc(styleCss(c.headerStyle))}">${chips(c.header || '')}</th>`;
      }).join('') + '</tr>';
    }
    (el.groups || []).forEach((g, lv) => {
      h += `<tr class="gh"><td colspan="${n}" style="padding-left:${4 + lv * 8}pt;${esc(styleCss(g.style))}">${chips(g.header || '${' + (g.by || '?') + '}')}</td></tr>`;
    });
    const row = (ghost) => '<tr' + (ghost ? ' class="ghost"' : '') + '>' + cols.map((c) => `<td style="${align(c)}${esc(styleCss(c.style))}">${chips(c.value || '')}</td>`).join('') + '</tr>';
    h += row(false) + row(true);
    const totals = (label, cls) => {
      const first = cols.findIndex((c) => c.total);
      let r = `<tr class="${cls}">`;
      if (first > 0) r += `<td colspan="${first}">${chips(label)}</td>`;
      cols.forEach((c, i) => {
        if (i < first) return;
        const v = !c.total ? '' : /^(sum|avg|min|max)$/.test(c.total) ? c.total + '(' + String(c.value || '').replace(/^\$\{|\}$/g, '') + ')' : c.total;
        r += `<td style="${align(c)}">${v ? '<span class="chip">' + esc(v.replace(/^\$\{|\}$/g, '')) + '</span>' : ''}</td>`;
      });
      return r + '</tr>';
    };
    const hasTotals = cols.some((c) => c.total);
    if (el.pageTotals) h += totals(el.carryLabel || 'A transportar', 'sub pt') + totals(el.broughtLabel || 'Transporte', 'sub pt');
    if (hasTotals) {
      [...(el.groups || [])].reverse().forEach((g) => { if (g.footer) h += totals(g.footerLabel || 'Subtotal', 'sub'); });
      h += totals(el.totalLabel || 'Total', 'tot');
    }
    return h + '</table>';
  }

  // ---------------------------------------------------------------- interação no canvas

  function pathOf(node) {
    return node ? JSON.parse(node.getAttribute('data-path')) : null;
  }

  function isSelected(path) {
    if (S.sel && same(S.sel, path)) return true;
    return S.multi.some((m) => same(m, path));
  }

  /** Caminhos selecionados (sem os que estão dentro de outro selecionado). */
  function selection() {
    const all = S.multi.length ? S.multi : S.sel ? [S.sel] : [];
    return all.filter((p) => !all.some((q) => q !== p && q.length < p.length && same(p.slice(0, q.length), q)));
  }

  function cmpPath(a, b) {
    for (let i = 0; i < Math.min(a.length, b.length); i++) {
      if (a[i] === b[i]) continue;
      if (typeof a[i] === 'number' && typeof b[i] === 'number') return a[i] - b[i];
      return String(a[i]) < String(b[i]) ? -1 : 1;
    }
    return a.length - b.length;
  }

  function select(path, col = null, add = false) {
    if (add && path) {
      const i = S.multi.findIndex((m) => same(m, path));
      if (!S.multi.length && S.sel) S.multi = [S.sel];
      if (i >= 0) S.multi.splice(i, 1); else S.multi.push(path);
      S.sel = S.multi.length ? S.multi[S.multi.length - 1] : null;
    } else {
      S.multi = path ? [path] : [];
      S.sel = path;
    }
    S.colSel = col;
    if (col != null && path) S.open.add(openKey(path, 'columns', col));
    renderCanvas();
    renderProps();
  }

  function onCanvasClick(e) {
    if (suppressClick) return;
    if (e.target.closest('[data-add-first]')) {
      e.preventDefault();
      S.showFirst = true;
      renderCanvas();
      return;
    }
    const act = e.target.closest('.acts button');
    const blk = e.target.closest('.blk');
    if (act && blk) {
      e.stopPropagation();
      const p = pathOf(blk);
      ({ up: () => shift(p, -1), down: () => shift(p, 1), dup: () => duplicate(p), del: () => removeAt(p), copy: () => copyPaths([p]) })[act.dataset.a]();
      return;
    }
    if (blk && (e.ctrlKey || e.metaKey || e.shiftKey)) {
      select(pathOf(blk), null, true);
      return;
    }
    if (blk) {
      const th = e.target.closest('th[data-col]');
      select(pathOf(blk), th ? Number(th.dataset.col) : null);
    } else if (e.target.closest('.page') || e.target.id === 'canvas') {
      select(null);
    }
  }

  function onCanvasDblClick(e) {
    const txt = e.target.closest('.r-text');
    const blk = e.target.closest('.blk');
    if (!txt || !blk || blk.dataset.type !== 'text') return;
    const path = pathOf(blk);
    const el = getAt(path);
    txt.setAttribute('contenteditable', 'true');
    txt.textContent = el.value || '';
    txt.focus();
    document.getSelection().selectAllChildren(txt);
    let done = false;
    const finish = (save) => {
      if (done) return;
      done = true;
      const v = txt.textContent;
      if (save && v !== (el.value || '')) commit(() => { getAt(path).value = v; });
      else renderCanvas();
    };
    txt.addEventListener('blur', () => finish(true), { once: true });
    txt.addEventListener('keydown', (k) => {
      if (k.key === 'Enter' && !k.shiftKey) { k.preventDefault(); txt.blur(); }
      if (k.key === 'Escape') { k.preventDefault(); finish(false); }
      k.stopPropagation();
    });
  }

  // ---------------------------------------------------------------- arrastar e soltar
  // Arrasto com eventos de mouse (não HTML5 DnD): funciona também no JCEF off-screen da IDE.

  let dropLine = null;
  let pending = null; // mousedown aguardando mover alguns pixels
  let ghost = null;
  let suppressClick = false;

  function clearDrop() {
    dropLine?.remove();
    dropLine = null;
    document.querySelectorAll('.dz.over').forEach((z) => z.classList.remove('over'));
    document.querySelectorAll('.blk.drop-into').forEach((b) => { b.classList.remove('drop-into'); b.style.outline = ''; });
  }

  function payloadFor(node) {
    const pi = node.closest?.('.pitem');
    if (pi) {
      if (pi.dataset.new) return { kind: 'new', type: pi.dataset.new, label: TYPES[pi.dataset.new].label };
      if (pi.dataset.snip) return { kind: 'snip', i: Number(pi.dataset.snip), label: SNIPPETS[Number(pi.dataset.snip)].label };
      if (pi.dataset.field) return { kind: 'field', path: pi.dataset.field, inList: pi.dataset.inlist === '1', label: pi.dataset.field };
      if (pi.dataset.param) return { kind: 'param', name: pi.dataset.param, label: pi.dataset.param };
      return null;
    }
    const blk = node.closest?.('.blk');
    if (blk && !node.closest('.acts') && !node.isContentEditable) {
      const t = TYPES[blk.dataset.type];
      return { kind: 'move', path: pathOf(blk), label: t ? t.label : blk.dataset.type, node: blk };
    }
    return null;
  }

  // ---------- redimensionar colunas (tabela e bloco de colunas)

  let resizing = null;

  function resizeHandle(e) {
    const th = e.target.closest?.('table.r-table th');
    if (th && th.nextElementSibling) {
      const r = th.getBoundingClientRect();
      if (e.clientX > r.right - 6) return { kind: 'table', blk: th.closest('.blk'), i: Number(th.dataset.col), cells: [...th.parentElement.children] };
    }
    const col = e.target.closest?.('.r-row > .col');
    if (col && col.nextElementSibling) {
      const r = col.getBoundingClientRect();
      if (e.clientX > r.right - 6) {
        const cells = [...col.parentElement.children];
        return { kind: 'row', blk: col.closest('.blk'), i: cells.indexOf(col), cells };
      }
    }
    return null;
  }

  function startResize(h, e) {
    const widths = h.cells.map((c) => c.getBoundingClientRect().width);
    resizing = { ...h, x: e.clientX, widths, start: widths.slice() };
    document.body.classList.add('resizing');
    e.preventDefault();
  }

  function moveResize(e) {
    const r = resizing;
    const dx = e.clientX - r.x;
    const min = 12;
    const a = Math.max(min, r.start[r.i] + dx);
    const b = Math.max(min, r.start[r.i] + r.start[r.i + 1] - a);
    r.widths[r.i] = r.start[r.i] + r.start[r.i + 1] - b;
    r.widths[r.i + 1] = b;
    const total = r.widths.reduce((x, y) => x + y, 0);
    r.cells.forEach((c, k) => {
      const pct = (r.widths[k] / total * 100).toFixed(1) + '%';
      if (r.kind === 'table') c.style.width = pct;
      else c.style.flex = '0 0 ' + pct;
    });
    $('status').textContent = r.cells.map((_, k) => Math.round(r.widths[k] / total * 100) + '%').join(' · ');
  }

  function endResize() {
    const r = resizing;
    resizing = null;
    document.body.classList.remove('resizing');
    suppressClick = true;
    setTimeout(() => { suppressClick = false; }, 0);
    const total = r.widths.reduce((x, y) => x + y, 0);
    const path = pathOf(r.blk);
    commit(() => {
      const el = getAt(path);
      el.columns.forEach((c, k) => { if (k < r.widths.length) c.width = Math.round(r.widths[k] / total * 1000) / 10 + '%'; });
      S.sel = path;
    });
  }

  function onMouseDown(e) {
    if (e.button !== 0 || S.invalid) return;
    const rh = resizeHandle(e);
    if (rh) return startResize(rh, e);
    const p = payloadFor(e.target);
    if (!p) return;
    pending = { p, x: e.clientX, y: e.clientY };
    if (p.kind !== 'move') e.preventDefault(); // evita selecionar texto da paleta
  }

  function onMouseMove(e) {
    if (resizing) return moveResize(e);
    if (!pending && !S.drag) {
      document.body.classList.toggle('can-resize', !!resizeHandle(e));
      return;
    }
    if (pending && !S.drag) {
      if (Math.abs(e.clientX - pending.x) + Math.abs(e.clientY - pending.y) < 5) return;
      S.drag = pending.p;
      pending = null;
      document.getSelection()?.removeAllRanges();
      document.body.classList.add('dragging-now');
      ghost = document.createElement('div');
      ghost.className = 'drag-ghost';
      ghost.textContent = S.drag.label;
      document.body.append(ghost);
      S.drag.node?.classList.add('dragging');
    }
    if (!S.drag) return;
    e.preventDefault();
    ghost.style.left = e.clientX + 12 + 'px';
    ghost.style.top = e.clientY + 8 + 'px';
    clearDrop();
    const t = dropTarget(hit(e));
    if (!t) return;
    if (t.into) {
      t.into.classList.add('drop-into');
      t.into.style.outline = '2px dashed var(--drop)';
      return;
    }
    t.zone.classList.add('over');
    if (!t.kids.length) return;
    dropLine = document.createElement('div');
    dropLine.className = 'dropline';
    const zr = t.zone.getBoundingClientRect();
    const y = t.index < t.kids.length ? t.kids[t.index].getBoundingClientRect().top - 1 : t.kids[t.kids.length - 1].getBoundingClientRect().bottom + 1;
    const zoom = parseFloat(document.querySelector('#canvas .page')?.style.zoom || '1') || 1;
    dropLine.style.top = (y - zr.top) / zoom + 'px';
    t.zone.appendChild(dropLine);
  }

  function onMouseUp(e) {
    if (resizing) return endResize();
    pending = null;
    if (!S.drag) return;
    const p = S.drag;
    const t = dropTarget(hit(e));
    endDrag();
    suppressClick = true;
    setTimeout(() => { suppressClick = false; }, 0);
    if (t) drop(t, p);
  }

  function endDrag() {
    clearDrop();
    ghost?.remove();
    ghost = null;
    S.drag?.node?.classList.remove('dragging');
    S.drag = null;
    document.body.classList.remove('dragging-now');
  }

  /** Elemento sob o cursor (o fantasma não recebe eventos). */
  function hit(e) {
    const el = document.elementFromPoint(e.clientX, e.clientY);
    return { target: el || document.body, clientX: e.clientX, clientY: e.clientY };
  }

  /** Alvo do drop: soltar campo "dentro" de tabela/ficha, ou posição numa zona. */
  function dropTarget(e) {
    const zoneEl = e.target.closest?.('.dz');
    if (!zoneEl || !S.drag) return null;
    if (S.drag.kind === 'field' || S.drag.kind === 'param') {
      const blk = e.target.closest('.blk');
      if (blk && blk.parentElement === zoneEl && (blk.dataset.type === 'table' || blk.dataset.type === 'fields')) {
        return { into: blk };
      }
    }
    const kids = [...zoneEl.children].filter((c) => c.classList.contains('blk'));
    let index = kids.length;
    for (let i = 0; i < kids.length; i++) {
      const r = kids[i].getBoundingClientRect();
      if (e.clientY < r.top + r.height / 2) { index = i; break; }
    }
    return { zone: zoneEl, path: JSON.parse(zoneEl.dataset.zone), index, kids };
  }

  function drop(t, p) {
    if (t.into) return dropInto(pathOf(t.into), p);
    switch (p.kind) {
      case 'move': return moveTo(p.path, t.path, t.index);
      case 'new': return insertAt(t.path, t.index, TYPES[p.type].make());
      case 'snip': return insertAt(t.path, t.index, SNIPPETS[p.i].el());
      case 'param': return insertAt(t.path, t.index, { type: 'text', value: '${' + p.name + '}' });
      case 'field': {
        const f = fieldByPath(p.path, p.inList);
        if (f) insertAt(t.path, t.index, elementForField(f));
        return;
      }
      default:
    }
  }

  /** Campo solto sobre tabela → nova coluna; sobre ficha → novo item. */
  function dropInto(path, p) {
    const el = getAt(path);
    const f = p.kind === 'field' ? fieldByPath(p.path, p.inList) : { path: p.name, field: { name: p.name } };
    if (!f) return;
    commit(() => {
      if (el.type === 'table') {
        el.columns = el.columns || [];
        el.columns.push(columnFor(f.path, f.field));
        S.colSel = el.columns.length - 1;
        S.open.add(openKey(path, 'columns', S.colSel));
      } else {
        el.items = el.items || [];
        const it = { label: f.field.label || nice(f.path), value: '${' + f.path + '}' };
        const fmt = formatFor(f.field.type);
        if (fmt) it.format = fmt;
        el.items.push(it);
      }
      S.sel = path;
    });
  }

  // ---------------------------------------------------------------- propriedades

  function def(name) {
    return S.schema?.definitions?.[name];
  }

  function resolve(node) {
    let n = node;
    for (let i = 0; n && n.$ref && i < 10; i++) n = def(n.$ref.split('/').pop());
    return n || {};
  }

  const refName = (node) => (node && node.$ref ? node.$ref.split('/').pop() : null);
  const openKey = (path, key, i) => JSON.stringify(path) + '|' + key + '|' + i;

  function renderProps() {
    const box = $('props');
    if (!S.model) { box.innerHTML = ''; return; }
    box.innerHTML = '';
    const head = document.createElement('div');
    head.className = 'phead';
    const bodyEl = document.createElement('div');
    bodyEl.className = 'pbody';
    const el = S.sel ? getAt(S.sel) : null;
    const many = selection();
    if (many.length > 1) {
      head.innerHTML = `<span class="pt">${many.length} blocos selecionados</span>`
        + '<button data-a="copy" title="Copiar">⎘</button><button data-a="dup" title="Duplicar">⧉</button><button data-a="del" class="danger" title="Remover">✕</button>';
      head.addEventListener('click', (e) => {
        const a = e.target.closest('button')?.dataset.a;
        if (a) ({ copy: () => copyPaths(many), dup: () => duplicateMany(many), del: () => removeMany(many) })[a]();
      });
      box.append(head, bodyEl);
      bodyEl.innerHTML = '<div class="hint">Ctrl/Shift+clique adiciona ou tira blocos da seleção. O estilo abaixo vale para todos.</div>';
      const els = many.map(getAt).filter(isElement);
      const first = els[0];
      const proxy = new Proxy({}, {
        get: (_, k) => (k === 'style' ? first.style : undefined),
        set: (_, k, v) => { for (const x of els) x[k] = typeof v === 'object' ? clone(v) : v; return true; },
        deleteProperty: (_, k) => { for (const x of els) delete x[k]; return true; },
      });
      styleEditor(bodyEl, 'Estilo (todos)', proxy, 'style', 'multi-style');
      return;
    }
    if (!isElement(el)) {
      head.innerHTML = '<span class="pt">⚙ Relatório</span>';
      box.append(head, bodyEl);
      if (!S.schema) { bodyEl.innerHTML = '<div class="hint">Schema não carregado.</div>'; return; }
      const props = { ...S.schema.properties };
      delete props.$schema;
      objectForm(bodyEl, props, S.model, ['report'], new Set(['pageHeader', 'body', 'pageFooter']));
      return;
    }
    const t = TYPES[el.type];
    head.innerHTML = `<span class="pt">${esc(t ? t.label : el.type)}</span>`
      + '<button data-a="up" title="Subir">↑</button><button data-a="down" title="Descer">↓</button>'
      + '<button data-a="dup" title="Duplicar">⧉</button><button data-a="del" class="danger" title="Remover">✕</button>';
    head.addEventListener('click', (e) => {
      const a = e.target.closest('button')?.dataset.a;
      const p = S.sel;
      if (a) ({ up: () => shift(p, -1), down: () => shift(p, 1), dup: () => duplicate(p), del: () => removeAt(p) })[a]();
    });
    box.append(head, bodyEl);
    renderProblemsBox();
    const d = resolve(def(el.type));
    if (!d.properties) { bodyEl.innerHTML = '<div class="hint">Sem propriedades conhecidas.</div>'; return; }
    // estilo e visibilidade por último
    const props = {};
    for (const [k, v] of Object.entries(d.properties)) if (k !== 'type' && k !== 'style' && k !== 'visible') props[k] = v;
    props.style = d.properties.style;
    props.visible = d.properties.visible;
    objectForm(bodyEl, props, el, S.sel, new Set());
  }

  /** Formulário gerado do schema. Alterações vão direto no objeto (via commit). */
  function objectForm(container, props, obj, ownerPath, skip) {
    for (const [key, raw] of Object.entries(props)) {
      if (skip.has(key) || !raw) continue;
      const rn = refName(raw);
      if (rn === 'elements') continue;
      const node = resolve(raw);
      const label = LABELS[key] || key;
      const mk = JSON.stringify(ownerPath) + '.' + key;
      if (rn === 'style') { styleEditor(container, label, obj, key, mk); continue; }
      if (key === 'dataClass') { classInput(container, obj, mk); continue; }
      if (node.type === 'array' && resolve(node.items).type === 'object') { arrayEditor(container, key, label, node, obj, ownerPath); continue; }
      if (node.type === 'object' && node.properties) {
        const fs = document.createElement('fieldset');
        fs.innerHTML = `<legend>${esc(label)}</legend>`;
        container.append(fs);
        const sub = obj[key] && typeof obj[key] === 'object' ? obj[key] : {};
        objectForm(fs, node.properties, new Proxy(sub, {
          set(target, k, v) {
            target[k] = v;
            if (!obj[key]) obj[key] = target;
            return true;
          },
          deleteProperty(target, k) {
            delete target[k];
            if (obj[key] && !Object.keys(target).length) delete obj[key];
            return true;
          },
        }), ownerPath.concat(key), new Set());
        continue;
      }
      scalarInput(container, key, label, node, raw, obj, mk);
    }
  }

  function setProp(obj, key, v) {
    if (v === '' || v === undefined || v === null || v === false && key !== 'showHeader') delete obj[key];
    else obj[key] = v;
  }

  function scalarInput(container, key, label, node, raw, obj, mk) {
    const row = document.createElement('div');
    row.className = 'frow';
    const desc = node.description || raw.description;
    const cur = obj[key];
    const types = [].concat(node.type || []);
    if (types.includes('boolean') && types.length === 1) {
      row.classList.add('check');
      const id = 'cb' + Math.random().toString(36).slice(2);
      const on = key === 'showHeader' ? cur !== false : !!cur;
      row.innerHTML = `<input type="checkbox" id="${id}" ${on ? 'checked' : ''}><label for="${id}">${esc(label)}</label>`;
      row.querySelector('input').addEventListener('change', (e) => commit(() => {
        if (key === 'showHeader') { if (e.target.checked) delete obj[key]; else obj[key] = false; }
        else setProp(obj, key, e.target.checked);
      }, { props: false }));
      if (desc) row.title = desc;
      container.append(row);
      return;
    }
    row.innerHTML = `<label>${esc(label)}<span class="k">${esc(key)}</span></label>`;
    let input;
    if (node.enum) {
      input = document.createElement('select');
      input.innerHTML = '<option value=""></option>' + node.enum.map((v) => `<option ${v === cur ? 'selected' : ''}>${esc(v)}</option>`).join('');
      input.addEventListener('change', () => commit(() => setProp(obj, key, input.value), { props: false }));
    } else if ((key === 'value' && (obj.type === 'text' || obj.type === 'html')) || key === 'css') {
      input = document.createElement('textarea');
      input.value = cur ?? '';
      input.addEventListener('input', () => commit(() => setProp(obj, key, input.value), { props: false, merge: mk }));
    } else {
      input = document.createElement('input');
      const numeric = types.length === 1 && (types[0] === 'integer' || types[0] === 'number');
      input.type = numeric ? 'number' : 'text';
      input.value = cur == null ? '' : typeof cur === 'object' ? JSON.stringify(cur) : cur;
      const list = datalistFor(key, node, raw);
      if (list) input.setAttribute('list', list);
      input.addEventListener('input', () => commit(() => {
        let v = input.value;
        if (numeric) v = v === '' ? '' : Number(v);
        else if (key === 'default' || key === 'sample') v = parseLoose(v);
        setProp(obj, key, v);
      }, { props: false, merge: mk }));
    }
    row.append(input);
    if (desc) {
      const d = document.createElement('div');
      d.className = 'desc';
      d.textContent = desc;
      row.append(d);
    }
    container.append(row);
  }

  function parseLoose(v) {
    if (v === 'true') return true;
    if (v === 'false') return false;
    if (v !== '' && !isNaN(Number(v)) && !/^0\d/.test(v)) return Number(v);
    return v;
  }

  /** Sugestões: expressões ${campo}, caminhos, formatos e exemplos do schema. */
  function datalistFor(key, node, raw) {
    let items = null;
    const all = [];
    (function walk(fields, prefix) {
      for (const f of fields || []) {
        if (!f?.name) continue;
        const p = prefix ? prefix + '.' + f.name : f.name;
        all.push(p);
        if (f.type === 'object') walk(f.fields, p);
        if (f.type === 'list') walk(f.fields, '');
      }
    })(S.model?.fields, '');
    for (const prm of S.model?.parameters || []) if (prm?.name) all.push(prm.name);
    if (key === 'value' || key === 'header' || key === 'label' || key === 'src') items = all.map((p) => '${' + p + '}').concat(['${page}', '${pages}', '${now()}', '${today()}', '${count()}', '${index}']);
    else if (key === 'source') items = ['rows'].concat((S.model?.fields || []).filter((f) => f.type === 'list').map((f) => f.name));
    else if (key === 'by' || key === 'sortBy' || key === 'visible') items = all;
    else if (node.examples || raw.examples) items = node.examples || raw.examples;
    if (!items || !items.length) return null;
    const id = 'dl-' + key;
    let dl = document.getElementById(id);
    if (!dl) {
      dl = document.createElement('datalist');
      dl.id = id;
      document.body.append(dl);
    }
    dl.innerHTML = [...new Set(items)].map((v) => `<option value="${esc(v)}">`).join('');
    return id;
  }

  function summary(key, item) {
    if (!item || typeof item !== 'object') return String(item);
    switch (key) {
      case 'columns': return item.header != null || item.value != null ? `${item.header || ''} ${item.value ? '· ' + item.value : ''}` : `coluna ${item.width || ''}`;
      case 'groups': return 'por ' + (item.by || '?');
      case 'items': return `${item.label || ''}: ${item.value || ''}`;
      case 'parameters':
      case 'fields': return `${item.name || '?'} (${item.type || 'string'})` + (item.fields?.length ? ` · ${item.fields.length} subcampos` : '');
      default: return Object.values(item).filter((v) => typeof v !== 'object').join(' · ');
    }
  }

  function newItem(key, obj) {
    switch (key) {
      case 'columns':
        return obj.type === 'row' ? { width: '', content: [] } : { header: 'Nova', value: '${campo}' };
      case 'groups': return { by: simpleFields()[0]?.path || 'campo', footer: true };
      case 'items': return { label: 'Rótulo', value: '${campo}' };
      case 'parameters': return { name: 'parametro', type: 'string' };
      case 'fields': return { name: 'campo', type: 'string' };
      default: return {};
    }
  }

  function arrayEditor(container, key, label, node, obj, ownerPath) {
    const wrap = document.createElement('div');
    wrap.className = 'arr';
    const arr = Array.isArray(obj[key]) ? obj[key] : [];
    wrap.innerHTML = `<div class="ah"><span>${esc(label)} (${arr.length})</span><button title="Adicionar">+ adicionar</button></div>`;
    wrap.querySelector('.ah button').addEventListener('click', () => commit(() => {
      if (!Array.isArray(obj[key])) obj[key] = [];
      obj[key].push(newItem(key, obj));
      S.open.add(openKey(ownerPath, key, obj[key].length - 1));
    }));
    const itemProps = resolve(node.items).properties || {};
    arr.forEach((item, i) => {
      const ok = openKey(ownerPath, key, i);
      const card = document.createElement('div');
      card.className = 'card' + (S.open.has(ok) ? ' open' : '');
      card.innerHTML = `<div class="ch"><span class="sum">${esc(summary(key, item))}</span>`
        + '<button data-a="up" title="Subir">↑</button><button data-a="down" title="Descer">↓</button><button data-a="del" title="Remover">✕</button></div><div class="cb"></div>';
      card.querySelector('.ch').addEventListener('click', (e) => {
        const a = e.target.closest('button')?.dataset.a;
        if (!a) {
          if (S.open.has(ok)) S.open.delete(ok); else S.open.add(ok);
          card.classList.toggle('open');
          return;
        }
        commit(() => {
          const j = a === 'up' ? i - 1 : a === 'down' ? i + 1 : -1;
          if (a === 'del') arr.splice(i, 1);
          else if (j >= 0 && j < arr.length) [arr[i], arr[j]] = [arr[j], arr[i]];
          if (!arr.length) delete obj[key];
          S.open.clear();
        });
      });
      const skip = new Set(key === 'columns' && obj.type === 'row' ? ['content'] : []);
      const refresh = () => { card.querySelector('.sum').textContent = summary(key, item); };
      card.addEventListener('input', refresh);
      card.addEventListener('change', refresh);
      objectForm(card.querySelector('.cb'), itemProps, item, ownerPath.concat(key, i), skip);
      wrap.append(card);
    });
    container.append(wrap);
  }

  function styleEditor(container, label, obj, key, mk) {
    const row = document.createElement('div');
    row.className = 'frow style-ed';
    const st = obj[key];
    if (typeof st === 'string') {
      row.innerHTML = `<label>${esc(label)} (CSS)</label><textarea></textarea>`;
      const ta = row.querySelector('textarea');
      ta.value = st;
      ta.addEventListener('input', () => commit(() => setProp(obj, key, ta.value), { props: false, merge: mk }));
      container.append(row);
      return;
    }
    const s = st && typeof st === 'object' ? st : {};
    const known = new Set(['bold', 'italic', 'underline', 'uppercase', 'size', 'color', 'background', 'align', 'border', 'padding']);
    const extra = Object.entries(s).filter(([k]) => !known.has(k)).map(([k, v]) => `${k}: ${v}`).join('; ');
    row.innerHTML = `<label>${esc(label)}</label>
      <div class="tg">
        <button data-t="bold" title="Negrito"><b>B</b></button>
        <button data-t="italic" title="Itálico"><i>I</i></button>
        <button data-t="underline" title="Sublinhado"><u>U</u></button>
        <button data-t="uppercase" title="Maiúsculas">Aa</button>
        <button data-t="border" title="Borda">▢</button>
      </div>
      <div class="grid2">
        <div><label>Tamanho (pt)</label><input type="number" step="0.5" data-k="size"></div>
        <div><label>Alinhamento</label><select data-k="align"><option></option><option>left</option><option>center</option><option>right</option><option>justify</option></select></div>
        <div><label>Cor</label><div class="colorin"><input type="color" data-c="color"><input type="text" data-k="color" placeholder="#333"></div></div>
        <div><label>Fundo</label><div class="colorin"><input type="color" data-c="background"><input type="text" data-k="background" placeholder="#f0f0f0"></div></div>
        <div><label>Espaçamento interno</label><input type="text" data-k="padding" placeholder="4"></div>
        <div><label>Outros (CSS)</label><input type="text" data-extra placeholder="marginTop: 4"></div>
      </div>`;
    const write = (fn) => commit(() => {
      const cur = obj[key] && typeof obj[key] === 'object' ? obj[key] : {};
      fn(cur);
      if (Object.keys(cur).length) obj[key] = cur; else delete obj[key];
    }, { props: false, merge: mk });
    row.querySelectorAll('[data-t]').forEach((b) => {
      const k = b.dataset.t;
      b.classList.toggle('on', !!s[k]);
      b.addEventListener('click', () => {
        const on = !b.classList.contains('on');
        b.classList.toggle('on', on);
        commit(() => {
          const cur = obj[key] && typeof obj[key] === 'object' ? obj[key] : {};
          if (on) cur[k] = true; else delete cur[k];
          if (Object.keys(cur).length) obj[key] = cur; else delete obj[key];
        }, { props: false });
      });
    });
    row.querySelectorAll('[data-k]').forEach((inp) => {
      const k = inp.dataset.k;
      inp.value = s[k] ?? '';
      inp.addEventListener(inp.tagName === 'SELECT' ? 'change' : 'input', () => write((cur) => {
        const v = inp.type === 'number' ? (inp.value === '' ? '' : Number(inp.value)) : k === 'padding' ? parseLoose(inp.value) : inp.value;
        if (v === '') delete cur[k]; else cur[k] = v;
        const picker = row.querySelector(`[data-c="${k}"]`);
        if (picker && /^#[0-9a-f]{6}$/i.test(inp.value)) picker.value = inp.value;
      }));
    });
    row.querySelectorAll('[data-c]').forEach((pick) => {
      const k = pick.dataset.c;
      if (/^#[0-9a-f]{6}$/i.test(s[k] || '')) pick.value = s[k];
      pick.addEventListener('input', () => {
        row.querySelector(`[data-k="${k}"]`).value = pick.value;
        write((cur) => { cur[k] = pick.value; });
      });
    });
    const ex = row.querySelector('[data-extra]');
    ex.value = extra;
    ex.addEventListener('input', () => write((cur) => {
      for (const k of Object.keys(cur)) if (!known.has(k)) delete cur[k];
      for (const part of ex.value.split(';')) {
        const m = part.match(/^\s*([\w-]+)\s*:\s*(.+?)\s*$/);
        if (m) cur[m[1]] = parseLoose(m[2]);
      }
    }));
    container.append(row);
  }


  // ---------------------------------------------------------------- classe dos dados (dataClass)

  let classTimer = null;

  function classInput(container, obj, mk) {
    const row = document.createElement('div');
    row.className = 'frow';
    row.innerHTML = `<label>${esc(LABELS.dataClass)}<span class="k">dataClass</span></label>
      <input type="text" list="dl-dataClass" placeholder="com.empresa.VendaDTO">
      <div class="classbar"><button class="btn primary" type="button">⇩ Importar campos da classe</button><span class="cmsg"></span></div>
      <div class="desc">Lê os atributos da classe (record, POJO, Lombok, Kotlin) e preenche os campos abaixo. Listas e objetos viram campos aninhados.</div>`;
    const input = row.querySelector('input');
    const btn = row.querySelector('button');
    const cmsg = row.querySelector('.cmsg');
    input.value = obj.dataClass || '';
    if (!document.getElementById('dl-dataClass')) {
      const dl = document.createElement('datalist');
      dl.id = 'dl-dataClass';
      document.body.append(dl);
    }
    input.addEventListener('input', () => {
      commit(() => setProp(obj, 'dataClass', input.value.trim()), { props: false, merge: mk });
      clearTimeout(classTimer);
      const q = input.value.trim();
      if (q.length >= 2) classTimer = setTimeout(() => host.send({ type: 'findClasses', query: q }), 250);
    });
    btn.addEventListener('click', () => {
      const cls = input.value.trim();
      if (!cls) { cmsg.textContent = 'informe a classe'; return; }
      if (!S.hasHost && !window.relatoMockHost) { cmsg.textContent = 'disponível no plugin'; return; }
      cmsg.textContent = 'lendo…';
      host.send({ type: 'importFields', className: cls });
    });
    container.append(row);
  }

  function onClasses(msg) {
    const dl = document.getElementById('dl-dataClass');
    if (dl) dl.innerHTML = (msg.items || []).map((v) => `<option value="${esc(v)}">`).join('');
  }

  /** Junta com os campos existentes: vale a estrutura da classe, preserva label/sample. */
  function mergeFields(fromClass, existing) {
    const old = new Map((existing || []).filter((f) => f && f.name).map((f) => [f.name, f]));
    return fromClass.map((f) => {
      const prev = old.get(f.name);
      if (!prev) return f;
      const out = { name: f.name, type: f.type };
      if (prev.label) out.label = prev.label;
      if (prev.sample !== undefined) out.sample = prev.sample;
      if (f.fields) out.fields = mergeFields(f.fields, prev.fields);
      return out;
    });
  }

  function onFields(msg) {
    const cmsg = document.querySelector('.classbar .cmsg');
    if (msg.error) {
      if (cmsg) cmsg.textContent = msg.error;
      $('status').textContent = msg.error;
      return;
    }
    commit(() => {
      S.model.dataClass = msg.className;
      S.model.fields = mergeFields(msg.fields || [], S.model.fields);
      S.open.add(openKey(['report'], 'fields', -1));
    });
    const n = (msg.fields || []).length;
    const short = String(msg.className).split('.').pop();
    $('status').textContent = n + ' campo(s) importados de ' + short;
    const again = document.querySelector('.classbar .cmsg');
    if (again) again.textContent = '✓ ' + n + ' campo(s) de ' + short;
  }

  // ---------------------------------------------------------------- avisos de campos

  function problemsOf(path) {
    const key = JSON.stringify(path);
    return S.problems.filter((p) => {
      const b = blockPathFor(p.path);
      return b && JSON.stringify(b) === key;
    });
  }

  /** Bloco (caminho de elemento) mais próximo de um caminho de propriedade. */
  function blockPathFor(path) {
    for (let n = path.length; n > 0; n--) {
      const sub = path.slice(0, n);
      if (typeof sub[sub.length - 1] === 'number' && isElement(getAt(sub))) return sub;
    }
    return null;
  }

  function decorateProblems() {
    document.querySelectorAll('.blk.warn').forEach((b) => { b.classList.remove('warn'); b.removeAttribute('title'); });
    const byBlock = new Map();
    for (const p of S.problems) {
      const b = blockPathFor(p.path);
      if (!b) continue;
      const k = JSON.stringify(b);
      if (!byBlock.has(k)) byBlock.set(k, []);
      byBlock.get(k).push(p.message);
    }
    for (const [k, msgs] of byBlock) {
      const el = document.querySelector(`.blk[data-path='${k}']`);
      if (!el) continue;
      el.classList.add('warn');
      el.title = msgs.join('\n');
    }
    const st = $('status');
    if (S.problems.length && !S.preview) st.textContent = '⚠ ' + S.problems.length + ' aviso(s) de campos';
    else if (!S.preview && S.model) st.textContent = countBlocks() + ' bloco(s)';
  }

  function renderProblemsBox() {
    const bodyEl = document.querySelector('#props .pbody');
    if (!bodyEl) return;
    bodyEl.querySelector('.pwarn')?.remove();
    const list = S.sel ? problemsOf(S.sel) : S.problems;
    if (!list.length) return;
    const box = document.createElement('div');
    box.className = 'pwarn';
    box.innerHTML = '<b>⚠ Avisos</b>' + list.map((p) => `<div>${esc(p.message)}</div>`).join('');
    bodyEl.prepend(box);
  }

  // ---------------------------------------------------------------- preview

  let previewTimer = null;

  function requestPreview() {
    const pv = $('preview');
    if (!S.hasHost && !window.relatoMockHost) {
      pv.innerHTML = '<div class="msg">O preview do PDF é gerado pelo plugin.</div>';
      return;
    }
    if (!pv.querySelector('img')) pv.innerHTML = '<div class="msg">Gerando PDF…</div>';
    clearTimeout(previewTimer);
    previewTimer = setTimeout(() => host.send({ type: 'preview' }), 250);
  }

  function onPreview(msg) {
    const pv = $('preview');
    if (msg.error) {
      pv.innerHTML = `<div class="msg err">${esc(msg.error)}</div>`;
      return;
    }
    let h = '';
    if (msg.notes?.length) h += `<div class="msg">${msg.notes.map(esc).join('\n')}</div>`;
    h += (msg.pages || []).map((src) => `<img src="${src}" alt="">`).join('');
    if (msg.total > (msg.pages || []).length) h += `<div class="msg">Mostrando ${msg.pages.length} de ${msg.total} páginas.</div>`;
    pv.innerHTML = h;
    $('status').textContent = msg.total + ' página(s) no PDF';
  }

  /** mode: 'full' (só o PDF) ou 'split' (layout e PDF lado a lado, atualizando sozinho). */
  function togglePreview(mode = 'full') {
    S.preview = S.preview === mode ? false : mode;
    document.body.classList.toggle('previewing', S.preview === 'full');
    document.body.classList.toggle('splitting', S.preview === 'split');
    $('togglePreview').classList.toggle('on', S.preview === 'full');
    $('splitPreview').classList.toggle('on', S.preview === 'split');
    if (S.preview) requestPreview();
    renderCanvas();
  }

  // ---------------------------------------------------------------- geral

  function updateButtons() {
    $('undo').disabled = !S.history.length;
    $('redo').disabled = !S.future.length;
  }

  function renderAll() {
    renderPalette();
    renderCanvas();
    renderProps();
    updateButtons();
  }

  function onKey(e) {
    const tag = e.target.tagName;
    const editing = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || e.target.isContentEditable;
    const ctrl = e.ctrlKey || e.metaKey;
    if (ctrl && !editing && (e.key === 'z' || e.key === 'Z')) { e.preventDefault(); if (e.shiftKey) redo(); else undo(); return; }
    if (ctrl && !editing && (e.key === 'y' || e.key === 'Y')) { e.preventDefault(); redo(); return; }
    if (e.key === 'Escape' && S.drag) { endDrag(); return; }
    if (ctrl && !editing && (e.key === 'v' || e.key === 'V')) { e.preventDefault(); requestPaste(); return; }
    if (editing || !S.sel) return;
    const sel = selection();
    if (e.key === 'Delete' || e.key === 'Backspace') { e.preventDefault(); removeMany(sel); }
    else if (ctrl && (e.key === 'd' || e.key === 'D')) { e.preventDefault(); duplicateMany(sel); }
    else if (ctrl && (e.key === 'c' || e.key === 'C')) { e.preventDefault(); copyPaths(sel); }
    else if (ctrl && (e.key === 'x' || e.key === 'X')) { e.preventDefault(); cutPaths(sel); }
    else if (ctrl && (e.key === 'a' || e.key === 'A')) {
      e.preventDefault();
      const arr = getAt(S.sel.slice(0, -1));
      S.multi = arr.map((_, i) => S.sel.slice(0, -1).concat(i));
      renderCanvas();
      renderProps();
    }
    else if (e.altKey && e.key === 'ArrowUp') { e.preventDefault(); shift(S.sel, -1); }
    else if (e.altKey && e.key === 'ArrowDown') { e.preventDefault(); shift(S.sel, 1); }
    else if (e.key === 'Escape') select(null);
  }

  function init() {
    const canvas = $('canvas');
    canvas.addEventListener('click', onCanvasClick);
    canvas.addEventListener('dblclick', onCanvasDblClick);
    document.addEventListener('mousedown', onMouseDown);
    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
    window.addEventListener('blur', endDrag);
    document.addEventListener('keydown', onKey);
    $('undo').addEventListener('click', undo);
    $('redo').addEventListener('click', redo);
    $('togglePreview').addEventListener('click', () => togglePreview('full'));
    $('splitPreview').addEventListener('click', () => togglePreview('split'));
    $('pasteBtn').addEventListener('click', requestPaste);
    $('reportProps').addEventListener('click', () => select(null));
    $('zoomOut').addEventListener('click', () => zoomBy(1 / 1.15));
    $('zoomIn').addEventListener('click', () => zoomBy(1.15));
    $('zoomFit').addEventListener('click', () => { S.zoom = 'fit'; applyZoom(); });
    window.addEventListener('resize', () => { if (S.zoom === 'fit') applyZoom(); });
    updateButtons();
    $('canvas').innerHTML = '<div class="hint" style="padding:24px">Carregando…</div>';
    if (typeof window.relatoHost === 'function') window.relato.hostReady();
    else if (window.relatoMockHost) window.relatoMockHost({ type: 'ready' });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
