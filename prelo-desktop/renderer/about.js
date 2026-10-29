// Tela "Sobre": versão, privacidade, créditos e licenças. Os textos vêm de about-info.js (window.ABOUT_INFO);
// qualquer elemento com data-about abre a tela. Mesma tela do Lembreme e do Overtone.
(() => {
  const A = window.ABOUT_INFO;
  const api = window.aboutApi;
  if (!A || !api) return;
  const svg = p => `<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${p}</svg>`;
  const IC = {
    x: '<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>',
    shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',
    file: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>',
    folder: '<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>',
    alert: '<path d="m10.29 3.86-8.47 14.67A2 2 0 0 0 3.55 21.5h16.9a2 2 0 0 0 1.73-2.97L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>',
    ext: '<path d="M15 3h6v6"/><path d="M10 14 21 3"/><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>'
  };
  const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);

  const wrap = document.createElement('div');
  wrap.className = 'ab-wrap ab-hidden';
  wrap.setAttribute('role', 'dialog');
  wrap.setAttribute('aria-modal', 'true');
  wrap.innerHTML = `
    <div class="ab-modal">
      <div class="ab-head">
        <img src="icon.png" alt="">
        <div><h2>${esc(A.name)}</h2><div class="ab-muted">Versão <span class="ab-version"></span></div></div>
        <span class="ab-spacer"></span>
        <button class="ab-btn ab-icon ab-close" title="Fechar (Esc)">${svg(IC.x)}</button>
      </div>
      <div class="ab-body">
        <p>${esc(A.tagline)}</p>
        <p class="ab-note">${svg(IC.shield)}<span>${A.privacy}</span></p>
        <h3>Créditos</h3>
        <ul class="ab-credits">${A.credits.map(([k, v, lic]) => `<li><b>${esc(k)}</b><span>${esc(v)}${lic ? ` <span class="ab-muted">(${esc(lic)})</span>` : ''}</span></li>`).join('')}</ul>
        <p class="ab-muted ab-small">Software livre sob a licença ${esc(A.license || 'MIT')} · © 2026 Mateus Vinícius dos Passos. Fornecido "como está", sem garantia.</p>
      </div>
      <pre class="ab-text ab-hidden"></pre>
      <div class="ab-foot">
        <button class="ab-btn ab-licenses">${svg(IC.file)}<span>Licenças completas</span></button>
        ${A.licensesFolder ? `<button class="ab-btn ab-ghost" data-ab-open="licenses" title="${esc(A.licensesFolder)}">${svg(IC.folder)}Textos das licenças</button>` : ''}
        <span class="ab-spacer"></span>
        ${A.repo ? `<button class="ab-btn ab-ghost" data-ab-open="issues">${svg(IC.alert)}Reportar problema</button>
        <button class="ab-btn ab-ghost" data-ab-open="repo">${svg(IC.ext)}GitHub</button>` : ''}
      </div>
    </div>`;
  document.body.append(wrap);
  const $ = s => wrap.querySelector(s);

  async function notices(on) {
    if (on && !$('.ab-text').textContent) $('.ab-text').textContent = await api.notices();
    $('.ab-body').classList.toggle('ab-hidden', on);
    $('.ab-text').classList.toggle('ab-hidden', !on);
    $('.ab-licenses span').textContent = on ? 'Voltar' : 'Licenças completas';
  }
  async function open() {
    $('.ab-version').textContent = (await api.info()).version;
    notices(false);
    wrap.classList.remove('ab-hidden');
    $('.ab-close').focus();
  }
  const close = () => wrap.classList.add('ab-hidden');
  const isOpen = () => !wrap.classList.contains('ab-hidden');

  document.addEventListener('click', e => { if (e.target.closest('[data-about]')) open(); });
  $('.ab-close').addEventListener('click', close);
  wrap.addEventListener('pointerdown', e => { if (e.target === wrap) close(); });
  $('.ab-licenses').addEventListener('click', () => notices($('.ab-text').classList.contains('ab-hidden')));
  for (const b of wrap.querySelectorAll('[data-ab-open]')) b.addEventListener('click', () => api.open(b.dataset.abOpen));
  // com a tela aberta, as teclas não chegam no app (notas, atalhos); Esc fecha
  window.addEventListener('keydown', e => {
    if (!isOpen()) return;
    if (e.key === 'Escape') close();
    e.stopImmediatePropagation();
  }, true);
  window.openAbout = open;
})();
