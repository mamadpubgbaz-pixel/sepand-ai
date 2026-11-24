/* Settings: models, engine (CPU/GPU), thinking levels, system prompts, look & language. */
(function () {
  'use strict';
  var SP = window.SP, S = SP.S, $ = SP.$, $$ = SP.$$, esc = SP.esc, icon = SP.icon, api = SP.api, nf = SP.nf;
  var t = function (k, v) { return SP.t(k, v); };
  var BUDGET = [160, 400, 1000, 2000, 4000, -1];
  var r1 = function (n) { return Math.round(n * 10) / 10; };

  /* hooks run after any setting changes */
  SP.hooks = [];
  SP.afterCfg = function (p, v, el) { SP.hooks.forEach(function (f) { try { f(p, v, el); } catch (e) { console.error(e); } }); };

  function fmtDur(s) { return s < 90 ? nf(Math.round(s)) + ' ' + t('sec') : nf(Math.round(s / 60)) + ' ' + t('min'); }
  function lvlDesc(lv) {
    var b = BUDGET[lv - 1];
    if (b < 0) return t('think.nolimit');
    return t('think.budget', { n: nf(b), s: fmtDur(b / (S.tps || 4.5)) });
  }
  function pct(d) { return d && d.total ? Math.min(100, Math.round(d.done * 100 / d.total)) : 0; }

  /* ---------- models ---------- */
  function modelRow(m) {
    var on = m.id === S.cfg.modelId;
    return '<div class="mrow ' + (on ? 'on' : '') + '" data-act="model-pick" data-id="' + esc(m.id) + '"><span class="radio">' + (on ? icon('check') : '') + '</span>' +
      '<div class="grow"><div class="nm ltr">' + esc(m.name) + (m.vision ? ' <span class="badge">' + esc(t('vision')) + '</span>' : '') + '</div><div class="sub">' + esc(SP.size(m.size)) + (m.external ? ' · ' + esc(t('m.external')) : '') + '</div></div>' +
      '<button class="icon-btn" data-act="model-del" data-id="' + esc(m.id) + '" aria-label="Delete">' + icon('trash') + '</button></div>';
  }
  function catRow(c) {
    var have = S.models.some(function (m) { return m.id === c.id; }), d = S.dl[c.id], run = d && d.state === 'run';
    return '<div class="mrow"><div class="grow"><div class="nm">' + esc(c.name) + ' <span class="badge soft">' + esc(t('cat.' + c.note)) + '</span></div>' +
      '<div class="sub">≈ ' + esc(SP.size(c.mb * 1048576)) + ' · ' + esc(c.quant) + (c.vision ? ' · ' + esc(t('cat.visionNote')) : '') + '</div>' +
      (run ? '<div class="bar" style="margin-top:9px"><i id="bar-' + esc(c.id) + '" style="width:' + pct(d) + '%"></i></div><div class="sub" id="pct-' + esc(c.id) + '">' + esc(dlText(d)) + '</div>' : '') +
      (d && d.state === 'error' ? '<div class="sub" style="color:var(--bad)">' + esc(d.message || '') + '</div>' : '') + '</div>' +
      (have ? '<span class="badge soft">' + icon('check') + '</span>' : run ? '<button class="btn sm" data-act="dl-cancel" data-id="' + esc(c.id) + '">' + esc(t('cancel')) + '</button>' :
        '<button class="btn sm pri" data-act="dl-cat" data-id="' + esc(c.id) + '" aria-label="Download">' + icon('download') + '</button>') + '</div>';
  }
  function dlText(d) { return d.total ? SP.size(d.done) + ' / ' + SP.size(d.total) + ' · ' + nf(pct(d)) + '%' : (d.done ? SP.size(d.done) : t('dl.start')); }

  function cardModels() {
    var imp = S.dl['import'], url = S.dl['url:'];
    return '<div class="card glass" id="card-models"><h3>' + icon('folder') + esc(t('models.title')) + '</h3>' +
      (S.models.length ? S.models.map(modelRow).join('') : '<p class="sub">' + esc(t('models.none')) + '</p>') +
      '<h4>' + esc(t('models.download')) + '</h4>' + (S.catalog || []).map(catRow).join('') +
      '<div class="field"><label>' + esc(t('models.url')) + '</label><div style="display:flex;gap:8px"><input class="in ltr" id="dl-url" placeholder="https://…/model.gguf" inputmode="url"><button class="btn pri" data-act="dl-url" aria-label="Download">' + icon('download') + '</button></div></div>' +
      '<div class="btns"><button class="btn block" data-act="model-import">' + icon('folder') + esc(t('models.pick')) + '</button></div>' +
      (imp && imp.state === 'run' ? '<div class="bar" style="margin-top:10px"><i id="bar-import" style="width:' + pct(imp) + '%"></i></div><div class="sub" id="pct-import">' + esc(dlText(imp)) + '</div>' : '') +
      '<p class="sub" style="margin-top:10px">' + esc(t('models.hint')) + '</p>' +
      '<details style="margin-top:8px"><summary class="lbl" style="cursor:pointer">' + esc(t('models.adv')) + '</summary><div class="field" style="margin-top:4px"><label>' + esc(t('models.hf')) + '</label><input class="in ltr" data-cfg="hfBase" value="' + esc(S.cfg.hfBase || '') + '"></div></details></div>';
  }

  /* ---------- engine ---------- */
  function engineStatusHtml() {
    var e = S.engine, st = e.state || 'stopped', txt, sub = '';
    if (st === 'ready') { txt = t('eng.runningWith', { m: e.model }); sub = [e.gpu ? 'GPU' : 'CPU', e.vision ? t('vision') : '', S.tps ? t('chat.speed', { n: nf(r1(S.tps)) }) : '', e.message || ''].filter(Boolean).join(' · '); }
    else if (st === 'starting') txt = t('eng.loadingModel');
    else if (st === 'error') txt = e.message || t('eng.error');
    else txt = t('eng.stoppedMsg');
    var btn = (st === 'ready' || st === 'starting') ? '<button class="btn bad" data-act="eng-toggle">' + icon('stop') + esc(t('eng.stop')) + '</button>' :
      '<button class="btn pri" data-act="eng-toggle" ' + (S.cfg.modelId ? '' : 'disabled') + '>' + icon('play') + esc(t('eng.start')) + '</button>';
    return '<div class="row"><span class="dot ' + st + '"></span><div class="grow"><b>' + esc(txt) + '</b>' + (sub ? '<div class="sub">' + esc(sub) + '</div>' : '') + '</div>' + btn + '</div>' +
      (S.dirty && st === 'ready' ? '<div class="banner glass" style="margin:10px 0 0"><div class="grow">' + esc(t('eng.restartNeeded')) + '</div><button class="btn sm pri" data-act="eng-restart">' + icon('refresh') + esc(t('eng.restart')) + '</button></div>' : '');
  }
  function computeHtml() {
    var gpu = S.cfg.compute === 'gpu';
    return '<div class="field" style="margin-top:6px"><div class="seg" data-seg="compute" data-restart><button data-val="cpu" class="' + (!gpu ? 'on' : '') + '">' + icon('cpu') + 'CPU</button>' +
      '<button data-val="gpu" class="' + (gpu ? 'on' : '') + '" ' + (S.gpu ? '' : 'disabled') + '>' + icon('bolt') + 'GPU</button></div>' +
      '<p class="sub" style="margin:8px 0 0">' + esc(t(S.gpu ? 'compute.note' : 'compute.nogpu')) + '</p></div>' +
      '<div class="field"><label>' + esc(t('compute.threads')) + ' <b data-v="threads">' + nf(S.cfg.threads || 4) + '</b></label><input type="range" min="1" max="8" step="1" value="' + (S.cfg.threads || 4) + '" data-cfg="threads" data-restart></div>' +
      (gpu && S.gpu ? '<div class="field"><label>' + esc(t('compute.layers')) + ' <b data-v="gpuLayers">' + nf(S.cfg.gpuLayers || 0) + '</b></label><input type="range" min="0" max="99" step="1" value="' + (S.cfg.gpuLayers || 0) + '" data-cfg="gpuLayers" data-restart><p class="sub" style="margin:4px 0 0">' + esc(t('compute.layersHint')) + '</p></div>' : '');
  }
  function cardEngine() {
    var kv = S.cfg.kv || 'q8_0';
    return '<div class="card glass" id="card-engine"><h3>' + icon('cpu') + esc(t('eng.title')) + '</h3>' + engineStatusHtml() + computeHtml() +
      '<div class="field"><label>' + esc(t('ctx.title')) + '</label><select class="in" data-cfg="ctx" data-num data-restart>' +
      [2048, 3072, 4096, 6144, 8192, 12288, 16384].map(function (n) { return '<option value="' + n + '" ' + (n === S.cfg.ctx ? 'selected' : '') + '>' + nf(n) + '</option>'; }).join('') + '</select><p class="sub" style="margin:6px 0 0">' + esc(t('ctx.hint')) + '</p></div>' +
      '<div class="field"><label>' + esc(t('kv.title')) + '</label><div class="seg" data-seg="kv" data-restart>' + [['f16', 'kv.f16'], ['q8_0', 'kv.q8'], ['q4_0', 'kv.q4']].map(function (x) { return '<button data-val="' + x[0] + '" class="' + (kv === x[0] ? 'on' : '') + '">' + esc(t(x[1])) + '</button>'; }).join('') + '</div></div>' +
      '<div class="btns"><button class="btn" data-act="eng-logs">' + icon('file') + esc(t('eng.logs')) + '</button></div></div>';
  }

  /* ---------- thinking ---------- */
  function thinkBody() {
    var on = !!S.cfg.thinkOn, lv = S.cfg.thinkLevel || 2;
    return '<div class="row"><div class="grow"><b>' + esc(t('think.title')) + '</b><div class="sub">' + esc(t('think.sub')) + '</div></div><label class="sw"><input type="checkbox" data-cfg="thinkOn" ' + (on ? 'checked' : '') + '><i></i></label></div>' +
      '<div id="think-ctl" style="' + (on ? '' : 'opacity:.45') + '"><div class="lvl-name" id="lvl-name">' + esc(t('think.l' + lv)) + '</div><div class="sub" id="lvl-desc" style="min-height:2.9em">' + esc(lvlDesc(lv)) + '</div>' +
      '<input type="range" min="1" max="6" step="1" value="' + lv + '" data-cfg="thinkLevel" data-num><div class="ticks">' + [1, 2, 3, 4, 5, 6].map(function (i) { return '<span>' + nf(i) + '</span>'; }).join('') + '</div></div>';
  }
  function cardThink() { return '<div class="card glass" id="card-think"><h3>' + icon('brain') + esc(t('think.head')) + '</h3>' + thinkBody() + '</div>'; }

  /* ---------- system prompts ---------- */
  function pname(p) { var k = 'preset.' + p.id; return (p.builtin && window.I18N.en[k]) ? t(k) : p.name; }
  function cardPrompt() {
    var list = S.cfg.prompts || [];
    if (!S.pe || !list.some(function (p) { return p.id === S.pe; })) S.pe = S.cfg.promptId || (list[0] && list[0].id);
    var cur = list.filter(function (p) { return p.id === S.pe; })[0] || {};
    return '<div class="card glass"><h3>' + icon('edit') + esc(t('prompt.title')) + '</h3><div class="sub">' + esc(t('prompt.sub')) + '</div>' +
      '<div class="chips">' + list.map(function (p) { return '<button class="chip ' + (p.id === S.pe ? 'on' : '') + '" data-act="pe-pick" data-id="' + esc(p.id) + '">' + (p.id === S.cfg.promptId ? '✓ ' : '') + esc(pname(p)) + '</button>'; }).join('') +
      '<button class="chip" data-act="pe-new">+ ' + esc(t('prompt.new')) + '</button></div>' +
      '<div class="field"><label>' + esc(t('prompt.name')) + '</label><input class="in" id="pe-name" value="' + esc(pname(cur)) + '"></div>' +
      '<div class="field"><label>' + esc(t('prompt.text')) + '</label><textarea class="in" id="pe-text" rows="7">' + esc(cur.text || '') + '</textarea></div>' +
      '<div class="btns"><button class="btn pri" data-act="pe-use">' + icon('check') + esc(t('prompt.use')) + '</button><button class="btn" data-act="pe-save">' + esc(t('save')) + '</button>' +
      (cur.builtin ? '<button class="btn" data-act="pe-reset">' + esc(t('prompt.reset')) + '</button>' : '') + '<button class="btn bad" data-act="pe-del" aria-label="Delete">' + icon('trash') + '</button></div>' +
      '<details style="margin-top:16px"><summary class="lbl" style="cursor:pointer">' + esc(t('prompt.agent')) + '</summary><div class="sub">' + esc(t('prompt.agentSub')) + '</div>' +
      '<textarea class="in ltr" id="agent-prompt" rows="9" placeholder="' + esc((S.defAgent || '').slice(0, 200)) + '…">' + esc(S.cfg.agentPrompt || '') + '</textarea>' +
      '<div class="btns"><button class="btn" data-act="ap-save">' + esc(t('save')) + '</button><button class="btn" data-act="ap-reset">' + esc(t('prompt.reset')) + '</button></div></details></div>';
  }

  /* ---------- generation, look, about ---------- */
  function slider(label, path, min, max, step, val, fmt) {
    return '<div class="field"><label>' + esc(label) + ' <b data-v="' + path + '">' + (fmt ? fmt(val) : nf(val)) + '</b></label><input type="range" min="' + min + '" max="' + max + '" step="' + step + '" value="' + val + '" data-cfg="' + path + '"></div>';
  }
  function cardGen() {
    return '<div class="card glass"><h3>' + icon('sliders') + esc(t('gen.title')) + '</h3>' + slider(t('gen.temp'), 'temp', 0, 1.5, 0.05, S.cfg.temp) + slider(t('gen.topp'), 'topP', 0.1, 1, 0.05, S.cfg.topP) + slider(t('gen.max'), 'maxTokens', 128, 4096, 128, S.cfg.maxTokens) + '</div>';
  }
  function cardLook() {
    var th = [['dark-red', 'theme.dr'], ['dark-green', 'theme.dg'], ['white-red', 'theme.wr'], ['white-green', 'theme.wg']], lg = S.cfg.lang || 'auto';
    return '<div class="card glass"><h3>' + icon('spark') + esc(t('look.title')) + '</h3>' +
      '<div class="lbl">' + esc(t('look.lang')) + '</div><div class="seg" data-seg="lang"><button data-val="auto" class="' + (lg === 'auto' ? 'on' : '') + '">' + esc(t('lang.auto')) + '</button><button data-val="fa" class="' + (lg === 'fa' ? 'on' : '') + '">فارسی</button><button data-val="en" class="' + (lg === 'en' ? 'on' : '') + '">English</button></div>' +
      '<div class="lbl" style="margin-top:16px">' + esc(t('look.theme')) + '</div><div class="themes">' + th.map(function (x) {
        return '<button class="theme t-' + x[0] + ' ' + (S.cfg.theme === x[0] ? 'on' : '') + '" data-act="theme" data-val="' + x[0] + '">' + esc(t(x[1])) + '<span class="ck">' + icon('check') + '</span></button>';
      }).join('') + '</div>' +
      '<div class="row" style="margin-top:12px"><div class="grow"><b>' + esc(t('look.anim')) + '</b><div class="sub">' + esc(t('look.animSub')) + '</div></div><label class="sw"><input type="checkbox" data-cfg="lite" data-inv ' + (S.cfg.lite ? '' : 'checked') + '><i></i></label></div></div>';
  }
  function cardAbout() {
    return '<div class="card glass"><h3>' + icon('info') + esc(t('about.title')) + '</h3><div class="sub">Sepand AI ' + esc(S.version) + ' · ' + esc(S.device) + ' · ' + esc(t('about.ram', { n: nf(Math.round(S.ramMB / 1024 * 10) / 10) })) + '</div><p class="sub">' + esc(t('about.local')) + '</p></div>';
  }

  SP.render.settings = function () {
    var v = $('#v-settings'), top = v.scrollTop;
    v.innerHTML = cardModels() + cardEngine() + cardThink() + cardPrompt() + cardGen() + cardLook() + cardAbout();
    v.scrollTop = top;
  };
  function rerender() { if (S.tab === 'settings') SP.render.settings(); }

  /* ---------- sheets (opened from the top bar and composer) ---------- */
  function engineSheet() {
    SP.sheet({ title: t('eng.title'), kind: 'engine', body: '<div id="eng-sheet">' + engineStatusHtml() + '</div><div class="btns"><button class="btn" data-act="eng-logs">' + icon('file') + esc(t('eng.logs')) + '</button><button class="btn" data-act="go-settings">' + icon('sliders') + esc(t('tab.settings')) + '</button></div>' });
  }
  function refreshEngineSheet() { var e = $('#eng-sheet'); if (e && S.sheet && S.sheet.kind === 'engine') e.innerHTML = engineStatusHtml(); }
  var A = SP.acts;
  A['engine-sheet'] = engineSheet;
  A['compute-sheet'] = function () { SP.sheet({ title: t('compute.title'), kind: 'compute', body: computeHtml() + (S.engine.state === 'ready' || S.dirty ? '<div class="btns"><button class="btn pri" data-act="eng-restart">' + icon('refresh') + esc(t('eng.restart')) + '</button></div>' : '') }); };
  A['think-sheet'] = function () { SP.sheet({ title: t('think.head'), kind: 'think', body: thinkBody() }); };
  A['go-settings'] = function () { SP.closeSheet(); SP.go('settings'); };
  A['eng-logs'] = function () {
    api('engine.logs').then(function (r) { SP.sheet({ title: t('eng.logs'), body: '<pre class="log">' + esc((r.lines || []).join('\n') || '—') + '</pre>' }); }).catch(SP.err);
  };
  A['eng-toggle'] = function () {
    var st = S.engine.state;
    if (st === 'ready' || st === 'starting') api('engine.stop').catch(SP.err);
    else { S.dirty = false; api('engine.start', { modelId: S.cfg.modelId }).catch(SP.err); }
  };
  A['eng-restart'] = function () {
    SP.closeSheet(); S.dirty = false;
    api('engine.stop').then(function () { return api('engine.start', { modelId: S.cfg.modelId }); }).catch(SP.err);
  };

  /* ---------- model actions ---------- */
  A['model-pick'] = function (el) {
    var id = el.dataset.id; if (S.cfg.modelId === id) return;
    S.cfg.modelId = id; api('models.select', { id: id });
    if (S.engine.state === 'ready') SP.markDirty();
    SP.renderHeader(); rerender();
  };
  A['model-del'] = function (el) {
    var id = el.dataset.id;
    SP.ask(t('models.delete'), t('models.deleteQ'), t('delete'), true).then(function (ok) {
      if (!ok) return;
      api('models.remove', { id: id }).then(function (list) { S.models = list; if (S.cfg.modelId === id) S.cfg.modelId = ''; SP.renderHeader(); rerender(); });
    });
  };
  A['dl-cat'] = function (el) { var id = el.dataset.id; S.dl[id] = { state: 'run', done: 0, total: 0 }; rerender(); api('models.download', { catalogId: id }).catch(SP.err); };
  A['dl-cancel'] = function (el) { api('models.cancel', { id: el.dataset.id }); };
  A['dl-url'] = function () {
    var u = ($('#dl-url').value || '').trim(); if (!/^https?:\/\//i.test(u)) { SP.toast(t('models.badUrl')); return; }
    S.dl[u] = { state: 'run', done: 0, total: 0 }; SP.toast(t('dl.start')); api('models.download', { url: u }).catch(SP.err);
  };
  A['model-import'] = function () {
    S.dl['import'] = { state: 'run', done: 0, total: 0 };
    api('models.pick').then(function (list) { S.models = list; var a = list.filter(function (m) { return m.active; })[0]; if (a) S.cfg.modelId = a.id; SP.renderHeader(); rerender(); }).catch(SP.err);
  };
  SP.on('dl', function (p) {
    var d = S.dl[p.id] = Object.assign(S.dl[p.id] || {}, p);
    if (p.state === 'run') {
      var bar = document.getElementById('bar-' + p.id), tx = document.getElementById('pct-' + p.id);
      if (bar && tx) { bar.style.width = pct(d) + '%'; tx.textContent = dlText(d); return; }
      rerender(); return;
    }
    if (p.state === 'error') SP.toast(p.message || 'Download failed');
    if (p.state === 'done') SP.toast(t('dl.done'));
    api('models.list').then(function (list) {
      S.models = list; var a = list.filter(function (m) { return m.active; })[0]; if (a) S.cfg.modelId = a.id;
      delete S.dl[p.id]; if (p.state === 'cancel' || p.state === 'error') S.dl[p.id] = p; SP.renderHeader(); rerender(); if (S.tab === 'chat') SP.renderMsgs();
    });
  });
  SP.on('engine', function (p) {
    S.engine = p; if (p.state === 'ready') S.dirty = false;
    SP.renderHeader(); rerender(); refreshEngineSheet();
  });

  /* ---------- prompt actions ---------- */
  function peRead() { return { name: $('#pe-name').value.trim(), text: $('#pe-text').value }; }
  function peSave() {
    var list = (S.cfg.prompts || []).map(function (p) { return Object.assign({}, p); }), cur = list.filter(function (p) { return p.id === S.pe; })[0]; if (!cur) return;
    var r = peRead(); cur.name = r.name || cur.name; cur.text = r.text; S.cfg.prompts = list; return SP.setPath('prompts', list);
  }
  A['pe-pick'] = function (el) { S.pe = el.dataset.id; rerender(); };
  A['pe-new'] = function () {
    var list = (S.cfg.prompts || []).slice(), id = 'p' + SP.uid(); list.push({ id: id, name: t('prompt.newName'), text: '' }); S.cfg.prompts = list; S.pe = id; SP.setPath('prompts', list); rerender();
  };
  A['pe-save'] = function () { peSave(); SP.toast(t('saved')); rerender(); };
  A['pe-use'] = function () { peSave(); S.cfg.promptId = S.pe; SP.setPath('promptId', S.pe); SP.toast(t('prompt.using', { n: $('#pe-name').value })); rerender(); };
  A['pe-del'] = function () {
    var list = S.cfg.prompts || []; if (list.length <= 1) { SP.toast(t('prompt.last')); return; }
    SP.ask(t('prompt.delete'), t('prompt.deleteQ'), t('delete'), true).then(function (ok) {
      if (!ok) return; var id = S.pe, nl = list.filter(function (p) { return p.id !== id; }); S.cfg.prompts = nl; SP.setPath('prompts', nl);
      if (S.cfg.promptId === id) { S.cfg.promptId = nl[0].id; SP.setPath('promptId', nl[0].id); } S.pe = nl[0].id; rerender();
    });
  };
  A['pe-reset'] = function () {
    var def = (S.defPrompts || []).filter(function (p) { return p.id === S.pe; })[0]; if (!def) return;
    var list = (S.cfg.prompts || []).map(function (p) { return p.id === S.pe ? Object.assign({}, def) : p; }); S.cfg.prompts = list; SP.setPath('prompts', list); rerender();
  };
  A['ap-save'] = function () { var v = $('#agent-prompt').value; S.cfg.agentPrompt = v; SP.setPath('agentPrompt', v); SP.toast(t('saved')); };
  A['ap-reset'] = function () { S.cfg.agentPrompt = ''; SP.setPath('agentPrompt', ''); rerender(); SP.toast(t('saved')); };
  A.theme = function (el) {
    var v = el.dataset.val; S.cfg.theme = v; document.documentElement.setAttribute('data-theme', v); SP.setPath('theme', v); rerender();
  };

  /* ---------- live updates ---------- */
  SP.onRange = function (path, v) {
    if (path === 'thinkLevel') { var n = $('#lvl-name'), d = $('#lvl-desc'); if (n) n.textContent = t('think.l' + v); if (d) d.textContent = lvlDesc(v); }
  };
  SP.hooks.push(function (p, v) {
    if (p === 'thinkOn') { var c = $('#think-ctl'); if (c) c.style.opacity = v ? '' : '.45'; SP.updateComposer(); }
    if (p === 'thinkLevel') SP.updateComposer();
    if (p === 'lang') SP.renderAll();
    if (p === 'compute') { SP.renderHeader(); rerender(); if (S.sheet && S.sheet.kind === 'compute') A['compute-sheet'](); }
    if (p === 'lite') document.documentElement.classList.toggle('lite', !!v);
    if (p === 'modelId') SP.renderHeader();
  });
})();
