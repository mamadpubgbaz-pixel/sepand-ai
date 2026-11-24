/* Agent (phone control + messages), Memory (Obsidian) and Tools (MCP) views. */
(function () {
  'use strict';
  var SP = window.SP, S = SP.S, $ = SP.$, $$ = SP.$$, esc = SP.esc, icon = SP.icon, api = SP.api, nf = SP.nf;
  var t = function (k, v) { return SP.t(k, v); };
  var A = SP.acts;
  function keep(id, html) { var v = $(id), top = v.scrollTop; v.innerHTML = html; v.scrollTop = top; }
  function sw(path, on, inv) { return '<label class="sw"><input type="checkbox" data-cfg="' + path + '" ' + (inv ? 'data-inv ' : '') + (on ? 'checked' : '') + '><i></i></label>'; }
  function rowSw(title, sub, path, on) { return '<div class="row"><div class="grow"><b>' + esc(title) + '</b>' + (sub ? '<div class="sub">' + esc(sub) + '</div>' : '') + '</div>' + sw(path, on) + '</div>'; }

  /* ================= AGENT ================= */
  var APPS = [['com.whatsapp', 'WhatsApp'], ['com.whatsapp.w4b', 'WhatsApp Business'], ['org.telegram.messenger', 'Telegram'], ['org.thunderdog.challegram', 'Telegram X'],
    ['com.google.android.apps.messaging', 'Messages'], ['com.android.mms', 'Messages (MIUI)'], ['com.samsung.android.messaging', 'Samsung Messages'],
    ['com.instagram.android', 'Instagram'], ['com.facebook.orca', 'Messenger'], ['org.thoughtcrime.securesms', 'Signal'], ['ir.nasim', 'Bale'], ['ir.eitaa.messenger', 'Eitaa'], ['ir.resaneh1.iptv', 'Rubika'], ['net.iGap', 'iGap']];

  function perm(ok, title, hint, btns) {
    return '<div class="perm"><span class="dot ' + (ok ? 'ready' : 'error') + '"></span><div class="grow"><div class="nm">' + esc(title) + '</div><div class="sub">' + esc(hint) + '</div></div>' + (ok ? '' : btns) + '</div>';
  }
  function pb(label, which, pri) { return '<button class="btn sm ' + (pri ? 'pri' : '') + '" data-act="perm" data-which="' + which + '">' + esc(label) + '</button>'; }

  function cardPerms() {
    var p = S.perms || {};
    return '<div class="card glass"><h3>' + icon('shield') + esc(t('perm.title')) + '</h3>' +
      perm(p.a11y, t('perm.a11y'), t('perm.a11y.hint'), '<div style="display:flex;flex-direction:column;gap:6px">' + pb(t('perm.open'), 'a11y', true) + pb(t('perm.appinfo'), 'appinfo') + '</div>') +
      perm(p.notif, t('perm.notif'), t('perm.notif.hint'), pb(t('perm.open'), 'notif', true)) +
      perm(p.post, t('perm.post'), t('perm.post.hint'), pb(t('perm.allow'), 'post', true)) +
      perm(p.battery, t('perm.battery'), t('perm.battery.hint'), pb(t('perm.open'), 'battery', true)) +
      '<div class="perm"><span class="dot"></span><div class="grow"><div class="nm">' + esc(t('perm.autostart')) + '</div><div class="sub">' + esc(t('perm.autostart.hint')) + '</div></div>' + pb(t('perm.open'), 'autostart') + '</div>' +
      perm(p.files, t('perm.files'), t('perm.files.hint'), pb(t('perm.open'), 'files')) + '</div>';
  }

  function stepHtml(s) {
    return '<div class="step"><span class="n">' + nf(s.n) + '</span><div class="grow"><div class="act">' + esc(s.action) + ' ' + esc(s.arg || '') + '</div>' + (s.thought ? '<div class="th">' + esc(s.thought) + '</div>' : '') + (s.result ? '<div class="rs">' + esc(s.result) + '</div>' : '') + '</div></div>';
  }
  function cardRun() {
    var a = S.agent, run = a.running;
    return '<div class="card glass"><h3>' + icon('pointer') + esc(t('agent.title')) + '</h3><p class="sub" style="margin:0 0 10px">' + esc(t('agent.sub')) + '</p>' +
      '<textarea class="in" id="goal" rows="3" placeholder="' + esc(t('agent.goal')) + '">' + esc(a.goal || '') + '</textarea>' +
      '<div class="chips">' + [1, 2, 3].map(function (i) { return '<button class="chip" data-act="goal-ex" data-i="' + i + '">' + esc(t('agent.ex' + i)) + '</button>'; }).join('') + '</div>' +
      '<div class="btns">' + (run ? '<button class="btn bad" data-act="agent-stop">' + icon('stop') + esc(t('agent.stop')) + '</button>' : '<button class="btn pri" data-act="agent-run">' + icon('play') + esc(t('agent.run')) + '</button>') + '</div>' +
      (a.status === 'error' ? '<p class="sub" style="color:var(--bad);margin-top:10px">' + esc(a.text) + '</p>' : '') +
      (a.done ? '<div class="banner glass" style="margin:12px 0 0"><div class="grow"><b>' + esc(t('agent.result')) + '</b><div style="white-space:pre-wrap">' + esc(a.done) + '</div></div></div>' : '') +
      (a.steps.length ? '<div style="margin-top:10px">' + a.steps.slice().reverse().map(stepHtml).join('') + '</div>' : '') + '</div>';
  }
  function cardMsg() {
    var m = S.cfg.msg || {}, mode = m.mode || 'off', apps = m.apps || [], prompts = S.cfg.prompts || [];
    return '<div class="card glass"><h3>' + icon('chat') + esc(t('msg.title')) + '</h3><p class="sub" style="margin:0 0 10px">' + esc(t('msg.sub')) + '</p>' +
      '<div class="seg" data-seg="msg.mode">' + ['off', 'draft', 'ask', 'auto'].map(function (k) { return '<button data-val="' + k + '" class="' + (mode === k ? 'on' : '') + '">' + esc(t('msg.m.' + k)) + '</button>'; }).join('') + '</div>' +
      '<p class="sub" id="msg-mode-d" style="margin:8px 0 0;' + (mode === 'auto' ? 'color:var(--warn)' : '') + '">' + esc(t('msg.d.' + mode)) + '</p>' +
      '<div class="lbl" style="margin-top:16px">' + esc(t('msg.apps')) + '</div><div class="chips" style="margin-top:0">' + APPS.map(function (a) {
        return '<button class="chip ' + (apps.indexOf(a[0]) >= 0 ? 'on' : '') + '" data-act="msg-app" data-pkg="' + esc(a[0]) + '">' + esc(a[1]) + '</button>';
      }).join('') + '</div>' +
      '<div class="field"><label>' + esc(t('msg.contacts')) + '</label><input class="in" data-cfg="msg.contacts" data-csv value="' + esc((m.contacts || []).join(', ')) + '" placeholder="' + esc(t('msg.contactsPh')) + '"><p class="sub" style="margin:6px 0 0">' + esc(t('msg.contactsHint')) + '</p></div>' +
      rowSw(t('msg.groups'), t('msg.groupsSub'), 'msg.groups', !!m.groups) + rowSw(t('msg.autostart'), t('msg.autostartSub'), 'msg.autoStart', m.autoStart !== false) +
      '<div class="field"><label>' + esc(t('msg.limit')) + ' <b data-v="msg.maxPerHour">' + nf(m.maxPerHour || 6) + '</b></label><input type="range" min="1" max="30" step="1" value="' + (m.maxPerHour || 6) + '" data-cfg="msg.maxPerHour"></div>' +
      '<div class="field"><label>' + esc(t('msg.quiet')) + '</label><div style="display:flex;gap:8px;align-items:center"><input class="in ltr" type="time" data-cfg="msg.quietFrom" value="' + esc(m.quietFrom || '') + '"><span>→</span><input class="in ltr" type="time" data-cfg="msg.quietTo" value="' + esc(m.quietTo || '') + '"></div></div>' +
      '<div class="field"><label>' + esc(t('msg.prompt')) + '</label><select class="in" data-cfg="msg.promptId">' + prompts.map(function (p) {
        var k = 'preset.' + p.id; return '<option value="' + esc(p.id) + '" ' + (p.id === (m.promptId || 'reply') ? 'selected' : '') + '>' + esc((p.builtin && window.I18N.en[k]) ? t(k) : p.name) + '</option>';
      }).join('') + '</select></div>' +
      '<h4>' + esc(t('msg.log')) + '</h4>' + (S.msgLog.length ? S.msgLog.slice(0, 12).map(function (e) {
        return '<div class="ev"><span class="k">' + esc(t('msg.k.' + e.kind)) + '</span><div class="grow"><b>' + esc(e.who) + '</b> <span class="sub">' + esc(e.text) + '</span></div></div>';
      }).join('') : '<p class="sub">' + esc(t('msg.logEmpty')) + '</p>') + '</div>';
  }
  SP.render.agent = function () { keep('#v-agent', cardPerms() + cardRun() + cardMsg()); };
  function again() { if (S.tab === 'agent') SP.render.agent(); }

  A.perm = function (el) { api('perm.open', { which: el.dataset.which }).catch(SP.err); };
  A['goal-ex'] = function (el) { $('#goal').value = t('agent.ex' + el.dataset.i); };
  A['agent-run'] = function () {
    var g = ($('#goal').value || '').trim(); if (!g) { SP.toast(t('agent.goal')); return; }
    if (!S.cfg.modelId) { SP.toast(t('chat.nomodel')); SP.go('settings'); return; }
    S.agent = { running: true, status: 'running', text: '', steps: [], done: '', goal: g }; again();
    api('agent.run', { goal: g }).catch(function (e) { S.agent.running = false; S.agent.status = 'error'; S.agent.text = e.message; again(); });
  };
  A['agent-stop'] = function () { api('agent.stop'); };
  A['msg-app'] = function (el) {
    var m = Object.assign({}, S.cfg.msg || {}), apps = (m.apps || []).slice(), i = apps.indexOf(el.dataset.pkg);
    if (i >= 0) apps.splice(i, 1); else apps.push(el.dataset.pkg);
    m.apps = apps; S.cfg.msg = m; SP.setPath('msg', m); again();
  };
  SP.on('agent.status', function (p) {
    S.agent.status = p.state; if (p.state === 'running') { S.agent.running = true; } else { S.agent.running = false; }
    if (p.state === 'error') S.agent.text = p.text; again();
  });
  SP.on('agent.step', function (p) { S.agent.steps.push(p); again(); });
  SP.on('agent.done', function (p) { S.agent.done = p.text; S.agent.running = false; again(); });
  SP.on('msg.event', function (p) { S.msgLog.unshift(p); if (S.msgLog.length > 50) S.msgLog.pop(); again(); });
  SP.on('perm', function (p) { S.perms = p; again(); });
  SP.hooks.push(function (p) { if (p === 'msg.mode') { var d = $('#msg-mode-d'), m = (S.cfg.msg || {}).mode || 'off'; if (d) { d.textContent = t('msg.d.' + m); d.style.color = m === 'auto' ? 'var(--warn)' : ''; } } });

  /* ================= MEMORY (Obsidian) ================= */
  var hits = null, q = '';
  function cardVault() {
    var v = S.vault || {};
    if (!v.connected) {
      return '<div class="card glass"><h3>' + icon('book') + esc(t('mem.title')) + '</h3><p class="sub" style="margin:0 0 12px">' + esc(t('mem.intro')) + '</p>' +
        '<button class="btn pri block" data-act="obs-pick">' + icon('folder') + esc(t('mem.choose')) + '</button><p class="sub" style="margin:12px 0 0">' + esc(t('mem.how')) + '</p></div>';
    }
    return '<div class="card glass"><h3>' + icon('book') + esc(t('mem.title')) + '</h3><div class="row"><span class="dot ' + (v.indexing ? 'starting' : 'ready') + '"></span><div class="grow"><b class="ltr">' + esc(v.name || 'Vault') + '</b><div class="sub">' +
      esc(v.indexing ? t('mem.indexing') : t('mem.notes', { n: nf(v.notes || 0) })) + '</div></div></div>' +
      '<div class="btns"><button class="btn sm" data-act="obs-reindex">' + icon('refresh') + esc(t('mem.rescan')) + '</button><button class="btn sm" data-act="obs-pick">' + icon('folder') + esc(t('mem.change')) + '</button><button class="btn sm bad" data-act="obs-off">' + esc(t('mem.disconnect')) + '</button></div></div>';
  }
  function cardMemSet() {
    var o = S.cfg.obs || {};
    return '<div class="card glass"><h3>' + icon('sliders') + esc(t('mem.settings')) + '</h3>' +
      rowSw(t('mem.recall'), t('mem.recallSub'), 'obs.autoRecall', o.autoRecall !== false) + rowSw(t('mem.save'), t('mem.saveSub'), 'obs.autoSave', o.autoSave !== false) +
      '<div class="field"><label>' + esc(t('mem.scope')) + '</label><div class="seg" data-seg="obs.scope"><button data-val="vault" class="' + (o.scope !== 'memory' ? 'on' : '') + '">' + esc(t('mem.scopeAll')) + '</button><button data-val="memory" class="' + (o.scope === 'memory' ? 'on' : '') + '">' + esc(t('mem.scopeMem')) + '</button></div></div>' +
      '<div class="field"><label>' + esc(t('mem.folder')) + '</label><input class="in ltr" data-cfg="obs.folder" value="' + esc(o.folder || '') + '"><p class="sub" style="margin:6px 0 0">' + esc(t('mem.folderHint')) + '</p></div>' +
      '<div class="field"><label>' + esc(t('mem.topk')) + ' <b data-v="obs.topK">' + nf(o.topK || 4) + '</b></label><input type="range" min="1" max="8" step="1" value="' + (o.topK || 4) + '" data-cfg="obs.topK"></div></div>';
  }
  function cardSearch() {
    return '<div class="card glass"><h3>' + icon('search') + esc(t('mem.search')) + '</h3><div style="display:flex;gap:8px"><input class="in" id="obs-q" value="' + esc(q) + '" placeholder="' + esc(t('mem.searchPh')) + '"><button class="btn pri" data-act="obs-search" aria-label="Search">' + icon('search') + '</button></div>' +
      (hits === null ? '' : hits.length ? hits.map(function (h) { return '<div class="hit" data-act="obs-open" data-path="' + esc(h.path) + '"><b class="ltr">' + esc(h.title) + '</b><div class="sub">' + esc(h.snippet) + '</div></div>'; }).join('') : '<p class="sub" style="margin-top:10px">' + esc(t('mem.none')) + '</p>') + '</div>';
  }
  SP.render.memory = function () { keep('#v-memory', cardVault() + (S.vault.connected ? cardMemSet() + cardSearch() : '')); };
  A['obs-pick'] = function () { api('obs.pick').then(function (v) { S.vault = v; SP.render.memory(); SP.updateComposer(); }).catch(SP.err); };
  A['obs-reindex'] = function () { api('obs.reindex'); SP.toast(t('mem.indexing')); };
  A['obs-off'] = function () { SP.ask(t('mem.disconnect'), t('mem.disconnectQ'), t('mem.disconnect'), true).then(function (ok) { if (ok) api('obs.off').then(function (v) { S.vault = v; hits = null; SP.render.memory(); SP.updateComposer(); }); }); };
  A['obs-search'] = function () {
    q = ($('#obs-q').value || '').trim(); if (!q) return;
    api('obs.search', { q: q }).then(function (r) { hits = r || []; SP.render.memory(); }).catch(SP.err);
  };
  A['obs-open'] = function (el) {
    api('obs.read', { path: el.dataset.path }).then(function (r) { SP.sheet({ title: el.dataset.path.replace(/\.md$/, '').split('/').pop(), body: '<div class="md">' + MD.render(r.text || '') + '</div>' }); }).catch(SP.err);
  };
  SP.on('obs', function (p) { S.vault = p; if (S.tab === 'memory' && !(document.activeElement && document.activeElement.tagName === 'INPUT')) SP.render.memory(); SP.updateComposer(); });
  SP.hooks.push(function (p) { if (p.indexOf('obs.') === 0) SP.updateComposer(); });
  document.addEventListener('keydown', function (e) { if (e.key === 'Enter' && e.target.id === 'obs-q') A['obs-search'](); });

  /* ================= TOOLS (MCP) ================= */
  function host(u) { try { return new URL(u).host; } catch (e) { return u; } }
  function cardBuiltin() {
    var o = S.cfg.tools || {};
    return '<div class="card glass"><h3>' + icon('globe') + esc(t('tools.builtin')) + '</h3>' + rowSw(t('tools.web'), t('tools.webSub'), 'tools.web', o.web !== false) + rowSw(t('tools.memory'), t('tools.memorySub'), 'tools.memory', o.memory !== false) + rowSw(t('tools.phone'), t('tools.phoneSub'), 'tools.phone', !!o.phone) + '</div>';
  }
  function cardMcp() {
    var l = S.cfg.mcp || [];
    return '<div class="card glass"><h3>' + icon('plug') + esc(t('mcp.title')) + '</h3><p class="sub" style="margin:0 0 6px">' + esc(t('mcp.sub')) + '</p>' +
      (l.length ? l.map(function (s) {
        var info = S.mcpInfo[s.id];
        return '<div class="mrow"><div class="grow"><div class="nm">' + esc(s.name || host(s.url)) + '</div><div class="sub ltr">' + esc(host(s.url)) + '</div>' + (info ? '<div class="sub" style="' + (info.err ? 'color:var(--bad)' : '') + '">' + esc(info.err || t('mcp.tools', { n: nf(info.n) })) + '</div>' : '') + '</div>' +
          '<label class="sw"><input type="checkbox" data-act="mcp-toggle" data-id="' + esc(s.id) + '" ' + (s.enabled !== false ? 'checked' : '') + '><i></i></label>' +
          '<button class="icon-btn" data-act="mcp-edit" data-id="' + esc(s.id) + '" aria-label="Edit">' + icon('edit') + '</button></div>';
      }).join('') : '<p class="sub">' + esc(t('mcp.none')) + '</p>') +
      '<div class="btns"><button class="btn pri block" data-act="mcp-add">' + icon('plus') + esc(t('mcp.add')) + '</button></div><p class="sub" style="margin:10px 0 0">' + esc(t('mcp.local')) + '</p></div>';
  }
  SP.render.tools = function () { keep('#v-tools', cardBuiltin() + cardMcp()); };
  function parseHeaders(txt) {
    var h = {}; String(txt || '').split('\n').forEach(function (l) { var i = l.indexOf(':'); if (i > 0) { var k = l.slice(0, i).trim(), v = l.slice(i + 1).trim(); if (k) h[k] = v; } }); return h;
  }
  function mcpForm() {
    return { id: S.mcpEdit.id, name: $('#mcp-name').value.trim(), url: $('#mcp-url').value.trim(), headers: parseHeaders($('#mcp-headers').value), enabled: S.mcpEdit.enabled !== false, off: S.mcpEdit.off || [] };
  }
  function mcpSheet(s) {
    S.mcpEdit = s;
    var hv = Object.keys(s.headers || {}).map(function (k) { return k + ': ' + s.headers[k]; }).join('\n');
    SP.sheet({
      title: t(s.isNew ? 'mcp.add' : 'mcp.edit'), kind: 'mcp',
      body: '<div class="field" style="margin-top:2px"><label>' + esc(t('mcp.name')) + '</label><input class="in" id="mcp-name" value="' + esc(s.name || '') + '"></div>' +
        '<div class="field"><label>' + esc(t('mcp.url')) + '</label><input class="in ltr" id="mcp-url" inputmode="url" placeholder="https://host/mcp" value="' + esc(s.url || '') + '"></div>' +
        '<div class="field"><label>' + esc(t('mcp.headers')) + '</label><textarea class="in ltr" id="mcp-headers" rows="3" placeholder="Authorization: Bearer …">' + esc(hv) + '</textarea></div>' +
        '<div class="btns"><button class="btn" data-act="mcp-test">' + icon('refresh') + esc(t('mcp.test')) + '</button></div><div id="mcp-tools"></div>',
      footer: (s.isNew ? '' : '<button class="btn bad" data-act="mcp-del">' + icon('trash') + '</button>') + '<button class="btn pri" data-act="mcp-save">' + icon('check') + esc(t('save')) + '</button>'
    });
  }
  A['mcp-add'] = function () { mcpSheet({ id: 's' + SP.uid(), name: '', url: '', headers: {}, enabled: true, off: [], isNew: true }); };
  A['mcp-edit'] = function (el) { var s = (S.cfg.mcp || []).filter(function (x) { return x.id === el.dataset.id; })[0]; if (s) mcpSheet(Object.assign({}, s)); };
  A['mcp-test'] = function () {
    var f = mcpForm(); if (!/^https?:\/\//i.test(f.url)) { SP.toast(t('models.badUrl')); return; }
    var box = $('#mcp-tools'); box.innerHTML = '<p class="sub"><span class="spin" style="display:inline-block;vertical-align:middle"></span> ' + esc(t('mcp.testing')) + '</p>';
    api('mcp.test', f).then(function (r) {
      S.mcpTools = r.tools || []; S.mcpInfo[f.id] = { n: S.mcpTools.length };
      box.innerHTML = '<p class="sub" style="color:var(--ok)">' + esc(t('mcp.ok', { n: nf(S.mcpTools.length) })) + '</p>' + S.mcpTools.map(function (x) {
        return '<div class="row"><div class="grow"><b class="ltr">' + esc(x.name) + '</b><div class="sub">' + esc(x.description) + '</div></div><label class="sw"><input type="checkbox" data-tool="' + esc(x.name) + '" ' + (f.off.indexOf(x.name) < 0 ? 'checked' : '') + '><i></i></label></div>';
      }).join('');
    }).catch(function (e) { S.mcpInfo[f.id] = { err: e.message }; box.innerHTML = '<p class="sub" style="color:var(--bad)">' + esc(e.message) + '</p>'; });
  };
  A['mcp-save'] = function () {
    var f = mcpForm(); if (!/^https?:\/\//i.test(f.url)) { SP.toast(t('models.badUrl')); return; }
    var tl = $$('#mcp-tools input[data-tool]'); if (tl.length) f.off = tl.filter(function (i) { return !i.checked; }).map(function (i) { return i.dataset.tool; });
    var list = (S.cfg.mcp || []).filter(function (x) { return x.id !== f.id; }); var old = (S.cfg.mcp || []).filter(function (x) { return x.id === f.id; })[0];
    list.push(f); if (old) list.splice(list.indexOf(f), 1, f); S.cfg.mcp = list; SP.setPath('mcp', list); SP.closeSheet(); SP.render.tools(); SP.toast(t('saved'));
  };
  A['mcp-del'] = function () {
    var id = S.mcpEdit.id; var list = (S.cfg.mcp || []).filter(function (x) { return x.id !== id; }); S.cfg.mcp = list; SP.setPath('mcp', list); SP.closeSheet(); SP.render.tools();
  };
  document.addEventListener('change', function (e) {
    var el = e.target;
    if (el.dataset && el.dataset.act === 'mcp-toggle') {
      var list = (S.cfg.mcp || []).map(function (s) { return s.id === el.dataset.id ? Object.assign({}, s, { enabled: el.checked }) : s; }); S.cfg.mcp = list; SP.setPath('mcp', list);
    }
  });
})();
