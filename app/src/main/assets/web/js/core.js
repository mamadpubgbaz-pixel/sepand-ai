/* Core: state, bridge to the phone, translations, icons, sheets, tabs. */
(function () {
  'use strict';
  var SP = window.SP = {};
  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };
  var esc = MD.esc;
  SP.$ = $; SP.$$ = $$; SP.esc = esc;

  var S = SP.S = {
    cfg: {}, models: [], catalog: [], engine: { state: 'stopped', message: '', model: '' }, perms: {}, vault: {}, gpu: false, cpuEngine: true,
    ramMB: 0, version: '', device: '', locale: 'en', tps: 0, defPrompts: [], defAgent: '',
    chats: [], chatId: null, msgs: [], atts: [], busy: false, cur: null, tab: 'chat', dl: {}, sheet: null, cf: null,
    agent: { running: false, status: 'idle', text: '', steps: [], done: '' }, msgLog: [], dirty: false, mcpInfo: {}, pe: null
  };

  /* ---------- icons ---------- */
  var IC = {
    menu: '<path d="M4 6h16M4 12h16M4 18h16"/>', plus: '<path d="M12 5v14M5 12h14"/>',
    clip: '<path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48"/>',
    send: '<path d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z"/>', stop: '<rect x="6.5" y="6.5" width="11" height="11" rx="2.5" fill="currentColor" stroke="none"/>',
    brain: '<path d="M9 18h6M10 22h4M12 2a7 7 0 00-4 12.7V17h8v-2.3A7 7 0 0012 2z"/>',
    cpu: '<rect x="6" y="6" width="12" height="12" rx="2"/><path d="M9.5 9.5h5v5h-5zM9 2v3M15 2v3M9 19v3M15 19v3M2 9h3M2 15h3M19 9h3M19 15h3"/>',
    bolt: '<path d="M13 2L3 14h9l-1 8 10-12h-9z"/>',
    sliders: '<path d="M3 6h9M16 6h5M3 12h3M10 12h11M3 18h11M18 18h3"/><circle cx="14" cy="6" r="2"/><circle cx="8" cy="12" r="2"/><circle cx="16" cy="18" r="2"/>',
    pointer: '<path d="M4 4l7.07 17 2.51-7.39L21 11.07z"/>',
    book: '<path d="M4 19.5A2.5 2.5 0 016.5 17H20V3H6.5A2.5 2.5 0 004 5.5v14zM20 17v4H6.5A2.5 2.5 0 014 18.5"/>',
    plug: '<path d="M9 2v6M15 2v6M6 8h12v4a6 6 0 01-12 0V8zM12 18v4"/>',
    chat: '<path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z"/>',
    copy: '<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a1 1 0 01-1-1V4a1 1 0 011-1h10a1 1 0 011 1v1"/>',
    trash: '<path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14M10 11v6M14 11v6"/>', check: '<path d="M20 6L9 17l-5-5"/>', x: '<path d="M18 6L6 18M6 6l12 12"/>',
    download: '<path d="M12 3v12M7 10l5 5 5-5M4 21h16"/>', folder: '<path d="M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2z"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/>', globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 010 18M12 3a14 14 0 000 18"/>',
    spark: '<path d="M12 2C12.6 7.5 16.5 11.4 22 12C16.5 12.6 12.6 16.5 12 22C11.4 16.5 7.5 12.6 2 12C7.5 11.4 11.4 7.5 12 2Z" fill="currentColor" stroke="none"/>',
    play: '<path d="M7 4l13 8-13 8z" fill="currentColor"/>', info: '<circle cx="12" cy="12" r="9"/><path d="M12 16v-4M12 8h.01"/>',
    chev: '<path d="M9 6l6 6-6 6"/>', save: '<path d="M19 21H5a2 2 0 01-2-2V5a2 2 0 012-2h11l5 5v11a2 2 0 01-2 2zM17 21v-8H7v8M7 3v5h8"/>',
    refresh: '<path d="M21 12a9 9 0 11-3-6.7L21 8M21 3v5h-5"/>', edit: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 013 3L7 19l-4 1 1-4z"/>',
    file: '<path d="M14 3H7a2 2 0 00-2 2v14a2 2 0 002 2h10a2 2 0 002-2V8z"/><path d="M14 3v5h5"/>',
    image: '<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="10" r="1.6"/><path d="M21 16l-5-5-8 8"/>',
    link: '<path d="M10 13a5 5 0 007 0l3-3a5 5 0 00-7-7l-1 1"/><path d="M14 11a5 5 0 00-7 0l-3 3a5 5 0 007 7l1-1"/>',
    shield: '<path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z"/>'
  };
  SP.icon = function (n, c) {
    return '<svg class="ic ' + (c || '') + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + (IC[n] || '') + '</svg>';
  };

  /* ---------- language ---------- */
  SP.L = 'en';
  SP.lang = function () { return (S.cfg.lang === 'fa' || S.cfg.lang === 'en') ? S.cfg.lang : (S.locale === 'fa' ? 'fa' : 'en'); };
  SP.applyLang = function () {
    SP.L = SP.lang();
    var h = document.documentElement;
    h.lang = SP.L; h.dir = SP.L === 'fa' ? 'rtl' : 'ltr';
  };
  SP.t = function (k, v) {
    var D = window.I18N, s = (D[SP.L] && D[SP.L][k] != null) ? D[SP.L][k] : (D.en[k] != null ? D.en[k] : k);
    if (v) for (var x in v) s = s.split('{' + x + '}').join(v[x]);
    return s;
  };
  SP.nf = function (n) {
    var s = String(n);
    return SP.L === 'fa' ? s.replace(/\d/g, function (d) { return '۰۱۲۳۴۵۶۷۸۹'.charAt(d); }).replace('.', '٫') : s;
  };
  SP.size = function (b) {
    if (!b) return '';
    var u = SP.L === 'fa' ? ['بایت', 'کیلوبایت', 'مگابایت', 'گیگابایت'] : ['B', 'KB', 'MB', 'GB'], i = 0;
    while (b >= 1024 && i < 3) { b /= 1024; i++; }
    return SP.nf((b >= 100 || i === 0 ? Math.round(b) : Math.round(b * 10) / 10)) + ' ' + u[i];
  };
  SP.uid = function () { return Math.random().toString(36).slice(2, 10) + Date.now().toString(36); };

  /* ---------- bridge to the phone ---------- */
  var pend = {}, nextCb = 1, hs = {};
  window.__cb = function (id, ok, s) {
    var p = pend[id]; if (!p) return; delete pend[id];
    if (ok) { var v; try { v = JSON.parse(s); } catch (e) { v = s; } p.res(v); } else p.rej(new Error(s));
  };
  SP.api = function (method, args) {
    return new Promise(function (res, rej) {
      var id = nextCb++; pend[id] = { res: res, rej: rej };
      try {
        if (window.Android) window.Android.call(method, JSON.stringify(args || {}), id);
        else if (window.SP_MOCK) window.SP_MOCK.call(method, args || {}, id);
        else throw new Error('No bridge');
      } catch (e) { delete pend[id]; rej(e); }
    });
  };
  SP.on = function (type, fn) { (hs[type] = hs[type] || []).push(fn); };
  window.__ev = function (type, json) {
    var p = {}; try { p = JSON.parse(json); } catch (e) { }
    (hs[type] || []).forEach(function (fn) { try { fn(p); } catch (e) { console.error(type, e); } });
  };

  /* ---------- settings helpers ---------- */
  SP.err = function (e) { SP.toast(String((e && e.message) || e)); };
  SP.getPath = function (p) { return p.split('.').reduce(function (o, k) { return o == null ? o : o[k]; }, S.cfg); };
  SP.setPath = function (p, v) {
    var parts = p.split('.'), top = parts[0];
    if (parts.length === 1) S.cfg[top] = v;
    else {
      var o = S.cfg;
      for (var i = 0; i < parts.length - 1; i++) { if (typeof o[parts[i]] !== 'object' || o[parts[i]] === null) o[parts[i]] = {}; o = o[parts[i]]; }
      o[parts[parts.length - 1]] = v;
    }
    return SP.api('cfg.set', { key: top, value: S.cfg[top] }).catch(SP.err);
  };
  SP.markDirty = function () {
    var st = S.engine.state;
    if (st === 'ready' || st === 'starting') { S.dirty = true; SP.toast(SP.t('eng.restartNeeded')); if (SP.render.settings && S.tab === 'settings') SP.render.settings(); }
  };

  /* ---------- toast ---------- */
  var toastT;
  SP.toast = function (msg, ms) {
    var el = $('#toast'); el.textContent = msg; el.classList.add('on');
    clearTimeout(toastT); toastT = setTimeout(function () { el.classList.remove('on'); }, ms || 2600);
  };

  /* ---------- sheet (bottom panel) ---------- */
  SP.sheet = function (o) {
    if (S.sheet && S.sheet.onClose) { var prev = S.sheet; S.sheet = null; try { prev.onClose(); } catch (e) { } }
    var el = $('#sheet');
    el.innerHTML = '<div class="grab"></div><div class="sh-head"><b>' + esc(o.title || '') + '</b><button class="icon-btn" data-act="sheet-close" aria-label="Close">' + SP.icon('x') + '</button></div><div class="sh-body"></div>' + (o.footer ? '<div class="sh-foot">' + o.footer + '</div>' : '');
    var b = $('.sh-body', el);
    if (typeof o.body === 'string') b.innerHTML = o.body; else if (o.body) b.appendChild(o.body);
    S.sheet = o; document.body.classList.add('sheet-on');
    if (o.onOpen) o.onOpen(el);
  };
  SP.closeSheet = function () {
    document.body.classList.remove('sheet-on');
    var s = S.sheet; S.sheet = null;
    if (s && s.onClose) try { s.onClose(); } catch (e) { }
  };
  SP.ask = function (title, text, okLabel, danger) {
    return new Promise(function (res) {
      var done = false, fin = function (v) { if (!done) { done = true; res(v); } };
      SP.sheet({
        title: title, body: '<p style="font-size:14.5px;margin:6px 0 0;white-space:pre-wrap">' + esc(text) + '</p>',
        footer: '<button class="btn" data-act="ask-no">' + esc(SP.t('cancel')) + '</button><button class="btn ' + (danger ? 'bad' : 'pri') + '" data-act="ask-yes">' + esc(okLabel || SP.t('ok')) + '</button>',
        onClose: function () { fin(false); }
      });
      SP.acts['ask-no'] = function () { SP.closeSheet(); };
      SP.acts['ask-yes'] = function () { fin(true); SP.closeSheet(); };
    });
  };

  /* ---------- drawer ---------- */
  SP.openDrawer = function () { if (SP.renderDrawer) SP.renderDrawer(); document.body.classList.add('drawer-open'); };
  SP.closeDrawer = function () { document.body.classList.remove('drawer-open'); };

  /* ---------- header + tabs ---------- */
  SP.renderHeader = function () {
    var e = S.engine, st = e.state || 'stopped', t = SP.t;
    var label = st === 'ready' ? (e.model || t('eng.ready')) : st === 'starting' ? t('eng.loading') : st === 'error' ? t('eng.error') : (S.cfg.modelId ? t('eng.off') : t('eng.nomodel'));
    $('#chip-engine').innerHTML = '<span class="dot ' + st + '"></span><span>' + esc(label) + '</span>';
    var gpu = S.cfg.compute === 'gpu';
    $('#chip-compute').innerHTML = SP.icon(gpu ? 'bolt' : 'cpu') + '<span>' + (gpu ? 'GPU' : 'CPU') + '</span>';
    $('#logo').innerHTML = SP.icon('spark');
    $('#btn-menu').innerHTML = SP.icon('menu');
  };
  var TABS = [['chat', 'chat'], ['agent', 'pointer'], ['memory', 'book'], ['tools', 'plug'], ['settings', 'sliders']];
  SP.render = {};
  SP.renderTabs = function () {
    $('#tabs').innerHTML = TABS.map(function (x) {
      return '<button class="tab ' + (S.tab === x[0] ? 'on' : '') + '" data-act="tab" data-tab="' + x[0] + '">' + SP.icon(x[1]) + '<span>' + esc(SP.t('tab.' + x[0])) + '</span></button>';
    }).join('');
  };
  SP.go = function (tab) {
    S.tab = tab;
    $$('.view').forEach(function (v) { v.hidden = v.id !== 'v-' + tab; });
    $$('#tabs .tab').forEach(function (b) { b.classList.toggle('on', b.dataset.tab === tab); });
    SP.closeDrawer();
    if (SP.render[tab]) SP.render[tab]();
    if (tab === 'chat' && SP.scrollEnd) SP.scrollEnd(true);
  };
  SP.renderAll = function () {
    SP.applyLang();
    document.documentElement.setAttribute('data-theme', S.cfg.theme || 'dark-red');
    document.documentElement.classList.toggle('lite', !!S.cfg.lite);
    SP.renderHeader(); SP.renderTabs();
    $('#input').placeholder = SP.t('chat.placeholder');
    $('#btn-att').innerHTML = SP.icon('clip');
    if (SP.renderChat) SP.renderChat();
    if (SP.render[S.tab]) SP.render[S.tab]();
  };

  /* ---------- central click / change handling ---------- */
  SP.acts = {
    tab: function (el) { SP.go(el.dataset.tab); },
    drawer: function () { SP.openDrawer(); },
    scrim: function () { if (S.sheet) SP.closeSheet(); SP.closeDrawer(); },
    'sheet-close': function () { SP.closeSheet(); }
  };
  document.addEventListener('click', function (e) {
    var a = e.target.closest('a[data-u]');
    if (a) { e.preventDefault(); SP.api('ui.open', { url: a.dataset.u }); return; }
    var seg = e.target.closest('.seg button[data-val]');
    if (seg && seg.parentNode.dataset.seg) {
      var box = seg.parentNode, p = box.dataset.seg, v = seg.dataset.val;
      if (box.dataset.num != null) v = Number(v);
      if (box.dataset.bool != null) v = v === 'true';
      $$('button', box).forEach(function (x) { x.classList.toggle('on', x === seg); });
      SP.setPath(p, v);
      if (box.dataset.restart != null) SP.markDirty();
      if (SP.afterCfg) SP.afterCfg(p, v, box);
      return;
    }
    var el = e.target.closest('[data-act]');
    if (!el) return;
    var f = SP.acts[el.dataset.act];
    if (f) f(el, e);
  });
  document.addEventListener('change', function (e) {
    var el = e.target, p = el.dataset && el.dataset.cfg;
    if (!p) return;
    var v = el.type === 'checkbox' ? el.checked : el.value;
    if (el.dataset.num != null || el.type === 'range') v = Number(v);
    if (el.dataset.inv != null) v = !v;
    if (el.dataset.csv != null) v = String(el.value).split(',').map(function (s) { return s.trim(); }).filter(Boolean);
    SP.setPath(p, v);
    if (el.dataset.restart != null) SP.markDirty();
    if (SP.afterCfg) SP.afterCfg(p, v, el);
  });
  document.addEventListener('input', function (e) {
    var el = e.target;
    if (el.type === 'range' && el.dataset.cfg) {
      var out = $('[data-v="' + el.dataset.cfg + '"]');
      if (out) out.textContent = SP.nf(el.value);
      if (SP.onRange) SP.onRange(el.dataset.cfg, Number(el.value));
    }
  });
  var kbOn = function (t) { return /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName) && t.type !== 'range' && t.type !== 'checkbox'; };
  document.addEventListener('focusin', function (e) { if (kbOn(e.target)) document.body.classList.add('kb'); });
  document.addEventListener('focusout', function () {
    setTimeout(function () { var a = document.activeElement; if (!a || !kbOn(a)) document.body.classList.remove('kb'); }, 90);
  });
  window.__back = function () {
    if (S.sheet) { SP.closeSheet(); return true; }
    if (document.body.classList.contains('drawer-open')) { SP.closeDrawer(); return true; }
    if (S.tab !== 'chat') { SP.go('chat'); return true; }
    return false;
  };

  /* ---------- questions from the phone ("may I send this?") ---------- */
  SP.on('confirm', function (p) {
    S.cf = p.id;
    SP.sheet({
      title: SP.t('cf.title'), body: '<p style="white-space:pre-wrap;font-size:15px;margin:6px 0 0">' + esc(p.text) + '</p>',
      footer: '<button class="btn" data-act="cf-no">' + esc(SP.t('deny')) + '</button><button class="btn pri" data-act="cf-yes">' + esc(SP.t('allow')) + '</button>',
      onClose: function () { if (S.cf) { SP.api('confirm', { id: S.cf, ok: false }); S.cf = null; } }
    });
  });
  SP.acts['cf-yes'] = function () { SP.api('confirm', { id: S.cf, ok: true }); S.cf = null; SP.closeSheet(); };
  SP.acts['cf-no'] = function () { SP.closeSheet(); };
  SP.on('confirm.done', function (p) { if (S.cf === p.id) { S.cf = null; SP.closeSheet(); } });
})();
