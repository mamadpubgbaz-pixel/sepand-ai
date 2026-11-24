/* Chat view: messages, streaming, attachments, chat history drawer. */
(function () {
  'use strict';
  var SP = window.SP, S = SP.S, $ = SP.$, $$ = SP.$$, esc = SP.esc, icon = SP.icon, api = SP.api;
  var t = function (k, v) { return SP.t(k, v); };

  /* ---------- helpers ---------- */
  function byId(id) { for (var i = S.msgs.length - 1; i >= 0; i--) if (S.msgs[i].id === id) return S.msgs[i]; return S.cur && S.cur.id === id ? S.cur : null; }
  SP.scrollEnd = function (force) {
    var el = $('#msgs');
    if (force || el.scrollHeight - el.scrollTop - el.clientHeight < 160) el.scrollTop = el.scrollHeight;
  };
  function autosize() { var i = $('#input'); i.style.height = 'auto'; i.style.height = Math.min(i.scrollHeight, 140) + 'px'; }

  function attChip(a, removable) {
    var th = a.kind === 'image' && a.thumb ? '<img alt="" src="data:image/jpeg;base64,' + a.thumb + '">' : icon(a.kind === 'image' ? 'image' : 'file');
    return '<span class="att"><span class="th">' + th + '</span><span class="nm">' + esc(a.name) + '<span class="sz">' + esc(a.note || SP.size(a.size)) + '</span></span>' +
      (removable ? '<button class="icon-btn" data-act="att-del" data-id="' + esc(a.id) + '" aria-label="Remove">' + icon('x') + '</button>' : '') + '</span>';
  }

  /* ---------- message html ---------- */
  function userHtml(m) {
    var at = (m.atts || []).map(function (a) { return attChip(a, false); }).join('');
    return '<div class="msg user" data-id="' + esc(m.id) + '"><div class="col">' + (at ? '<div class="attrow">' + at + '</div>' : '') +
      (m.content ? '<div class="bubble"><div class="txt">' + esc(m.content) + '</div></div>' : '') + '</div></div>';
  }

  function asstHtml(m) {
    var h = '<div class="msg asst" data-id="' + esc(m.id) + '"><div class="av">' + icon('spark') + '</div><div class="col">';
    if (m.recall && m.recall.length) {
      h += '<div class="pills">' + m.recall.map(function (r) { return '<span class="pill">' + icon('book') + '<span>' + esc(r.title) + '</span></span>'; }).join('') + '</div>';
    }
    (m.tools || []).forEach(function (tl) {
      var args = ''; try { args = JSON.stringify(tl.args); } catch (e) { }
      h += '<details class="tool"><summary>' + (tl.status === 'run' ? '<span class="spin"></span>' : icon('check')) + '<span>' + esc(tl.name) + '</span><span style="opacity:.55;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + esc(args.length > 60 ? args.slice(0, 60) + '…' : args) + '</span></summary>' +
        '<div class="res">' + esc(tl.result || '…') + '</div></details>';
    });
    if (m.reasoning) {
      var n = m.rt || Math.round(m.reasoning.length / 3.5);
      h += '<details class="think' + (m.live && !m.content ? ' live' : '') + '" data-mid="' + esc(m.id) + '"' + (m.thinkOpen ? ' open' : '') + '><summary>' + icon('brain') + '<span>' + esc(t('chat.thought', { n: SP.nf(n) })) + '</span></summary><div class="tk">' + esc(m.reasoning) + '</div></details>';
    }
    h += '<div class="bubble">';
    if (m.error) h += '<div class="status" style="color:var(--bad)">' + icon('info') + '<span>' + esc(m.error) + '</span></div>';
    else if (m.content) h += '<div class="md">' + MD.render(m.content) + '</div>';
    else if (m.live) h += '<div class="status"><span class="typing"><i></i><i></i><i></i></span><span>' + esc(m.status || t('chat.thinking')) + '</span></div>';
    h += '</div>';
    if (!m.live && !m.error && m.content) {
      var st = m.stats && m.stats.tps ? '<span>' + esc(t('chat.speed', { n: SP.nf(Math.round(m.stats.tps * 10) / 10) })) + '</span>' : '';
      h += '<div class="meta">' + st + '<button class="icon-btn" data-act="copy-msg" data-id="' + esc(m.id) + '" aria-label="Copy">' + icon('copy') + '</button>' +
        '<button class="icon-btn" data-act="note-msg" data-id="' + esc(m.id) + '" aria-label="Save to Obsidian">' + icon('save') + '</button></div>';
    }
    return h + '</div></div>';
  }

  function heroHtml() {
    var noModel = !S.cfg.modelId && !S.models.length;
    return '<div class="hero"><div class="big">' + icon('spark') + '</div><h1>' + esc(t('hero.title')) + '</h1><p>' + esc(t(noModel ? 'hero.nomodel' : 'hero.sub')) + '</p>' +
      (noModel ? '<button class="btn pri" data-act="go-models">' + icon('download') + esc(t('hero.getmodel')) + '</button>' :
        '<div class="sugg">' + [1, 2, 3].map(function (i) { return '<button data-act="sugg" data-i="' + i + '">' + esc(t('sugg' + i)) + '</button>'; }).join('') + '</div>') + '</div>';
  }

  SP.renderMsgs = function () {
    var el = $('#msgs');
    if (!S.msgs.length) { el.innerHTML = heroHtml(); return; }
    el.innerHTML = S.msgs.map(function (m) { return m.role === 'user' ? userHtml(m) : asstHtml(m); }).join('');
  };
  function refresh(m) {
    var node = $('#msgs .msg[data-id="' + (window.CSS && CSS.escape ? CSS.escape(m.id) : m.id) + '"]');
    if (!node) { SP.renderMsgs(); SP.scrollEnd(true); return; }
    var wrap = document.createElement('div'); wrap.innerHTML = m.role === 'user' ? userHtml(m) : asstHtml(m);
    node.replaceWith(wrap.firstChild);
    SP.scrollEnd(false);
  }
  var timers = {};
  function schedule(m) { if (timers[m.id]) return; timers[m.id] = setTimeout(function () { delete timers[m.id]; refresh(m); }, 90); }

  /* ---------- composer ---------- */
  function updateSend() {
    var b = $('#btn-send');
    b.classList.toggle('stop', S.busy);
    b.innerHTML = S.busy ? icon('stop') : icon('send', 'flip');
    b.dataset.act = S.busy ? 'stop' : 'send';
    var lv = S.cfg.thinkLevel || 2;
    $('#chip-think').innerHTML = icon('brain') + '<span>' + esc(S.cfg.thinkOn ? t('think.l' + lv) : t('think.off')) + '</span>';
    var v = S.vault || {};
    $('#chip-notes').innerHTML = icon('book') + '<span>' + esc(v.connected ? (S.cfg.obs && S.cfg.obs.autoRecall ? t('notes.on') : t('notes.paused')) : t('notes.connect')) + '</span>';
  }
  SP.updateComposer = updateSend;
  function renderAtts() { $('#att-row').innerHTML = S.atts.map(function (a) { return attChip(a, true); }).join(''); }

  function send(text0) {
    if (S.busy) return;
    var inp = $('#input'), text = (text0 != null ? text0 : inp.value).trim();
    if (!text && !S.atts.length) return;
    if (!S.cfg.modelId) { SP.toast(t('chat.nomodel')); SP.go('settings'); return; }
    if (!S.chatId) S.chatId = SP.uid();
    var atts = S.atts.slice(); S.atts = [];
    S.msgs.push({ id: 'u' + Date.now(), role: 'user', content: text, atts: atts });
    var m = { id: 'a' + Date.now(), role: 'assistant', content: '', reasoning: '', rt: 0, tools: [], recall: [], live: true, status: S.engine.state === 'ready' ? t('chat.thinking') : t('chat.starting') };
    S.msgs.push(m); S.cur = m; S.busy = true;
    inp.value = ''; autosize(); renderAtts(); SP.renderMsgs(); SP.scrollEnd(true); updateSend();
    api('chat.send', { chatId: S.chatId, text: text, atts: atts.map(function (a) { return a.id; }) }).catch(function (e) {
      m.live = false; m.error = e.message; S.busy = false; refresh(m); updateSend();
    });
  }

  /* ---------- events from the phone ---------- */
  SP.on('chat.start', function (p) { if (S.cur) S.cur.id = p.msgId; });
  SP.on('chat.recall', function (p) { var m = byId(p.msgId); if (m) { m.recall = p.notes || []; schedule(m); } });
  SP.on('chat.delta', function (p) {
    var m = byId(p.msgId); if (!m) return;
    if (p.kind === 'reasoning') { m.reasoning += p.text; m.rt = (m.rt || 0) + 1; } else m.content += p.text;
    schedule(m);
  });
  SP.on('chat.tool', function (p) {
    var m = byId(p.msgId); if (!m) return;
    if (p.status === 'running') m.tools.push({ name: p.name, args: p.args, status: 'run', result: '' });
    else for (var i = m.tools.length - 1; i >= 0; i--) if (m.tools[i].name === p.name && m.tools[i].status === 'run') { m.tools[i].status = 'done'; m.tools[i].result = p.result; break; }
    m.status = ''; schedule(m);
  });
  SP.on('chat.done', function (p) {
    var m = byId(p.msgId) || S.cur; if (!m) return;
    m.live = false; m.stats = { tps: p.tps, tokens: p.tokens }; m.thinkOpen = false;
    if (p.tps) S.tps = p.tps;
    S.busy = false; S.cur = null; refresh(m); updateSend(); loadChats();
  });
  SP.on('chat.error', function (p) {
    var m = S.cur; S.busy = false; S.cur = null;
    if (m) { m.live = false; m.error = p.message; refresh(m); } else SP.toast(p.message);
    updateSend();
  });
  SP.on('chat.title', function (p) { loadChats(); });
  SP.on('engine', function (p) { if (S.cur && S.cur.live && !S.cur.content && !S.cur.reasoning) { S.cur.status = p.state === 'starting' ? t('chat.starting') : t('chat.thinking'); schedule(S.cur); } });
  document.addEventListener('toggle', function (e) {
    var d = e.target; if (d && d.classList && d.classList.contains('think')) { var m = byId(d.dataset.mid); if (m) m.thinkOpen = d.open; }
  }, true);

  /* ---------- chats list ---------- */
  function loadChats() { return api('chat.list').then(function (l) { S.chats = l || []; if (document.body.classList.contains('drawer-open')) SP.renderDrawer(); }).catch(function () { }); }
  SP.loadChats = loadChats;
  function norm(m) {
    if (m.role === 'assistant') { m.live = false; m.tools = (m.tools || []).map(function (x) { x.status = 'done'; return x; }); m.rt = 0; m.reasoning = m.reasoning || ''; m.recall = m.recall || []; }
    return m;
  }
  function loadChat(id) {
    return api('chat.load', { id: id }).then(function (c) {
      S.chatId = id; S.msgs = ((c && c.messages) || []).map(norm); S.cur = null; S.busy = false;
      SP.renderMsgs(); SP.go('chat'); SP.scrollEnd(true); updateSend();
    }).catch(SP.err);
  }
  function newChat() { S.chatId = null; S.msgs = []; S.atts = []; S.cur = null; S.busy = false; renderAtts(); SP.renderMsgs(); SP.go('chat'); updateSend(); }

  SP.renderDrawer = function () {
    var d = $('#drawer');
    d.innerHTML = '<div class="dr-head"><span>' + esc(t('chats')) + '</span><button class="icon-btn" data-act="drawer-close">' + icon('x') + '</button></div>' +
      '<button class="btn pri block" data-act="new-chat">' + icon('plus') + esc(t('chat.new')) + '</button><div class="dr-list">' +
      (S.chats.length ? S.chats.map(function (c) {
        return '<div class="citem ' + (c.id === S.chatId ? 'on' : '') + '"><button class="t" data-act="chat-load" data-id="' + esc(c.id) + '">' + esc(c.title || t('chat.untitled')) + '</button>' +
          '<button class="icon-btn" data-act="chat-del" data-id="' + esc(c.id) + '" aria-label="Delete">' + icon('trash') + '</button></div>';
      }).join('') : '<p class="sub" style="padding:14px 8px">' + esc(t('chat.empty')) + '</p>') + '</div>';
  };

  /* ---------- actions ---------- */
  var A = SP.acts;
  A.send = function () { send(); };
  A.stop = function () { api('chat.stop'); };
  A.sugg = function (el) { send(t('sugg' + el.dataset.i)); };
  A['att-pick'] = function () {
    api('att.pick').then(function (l) { (l || []).forEach(function (a) { S.atts.push(a); }); renderAtts(); }).catch(SP.err);
  };
  A['att-del'] = function (el) {
    var id = el.dataset.id; S.atts = S.atts.filter(function (a) { return a.id !== id; }); api('att.drop', { id: id }); renderAtts();
  };
  A['copy-msg'] = function (el) { var m = byId(el.dataset.id); if (m) api('ui.copy', { text: m.content }); };
  A['copy-code'] = function (el) { var c = el.closest('pre').querySelector('code'); api('ui.copy', { text: c.textContent }); };
  A['note-msg'] = function (el) {
    var m = byId(el.dataset.id); if (!m) return;
    if (!S.vault.connected) { SP.toast(t('notes.connectFirst')); SP.go('memory'); return; }
    var first = (m.content.split('\n')[0] || 'Note').replace(/[#*_`>\[\]]/g, '').trim().slice(0, 60);
    SP.sheet({
      title: t('note.save'), body: '<div class="field" style="margin-top:4px"><label>' + esc(t('note.title')) + '</label><input class="in" id="note-title" value="' + esc(first) + '"></div><p class="sub">' + esc(t('note.where', { f: (S.cfg.obs && S.cfg.obs.folder) || '' })) + '</p>',
      footer: '<button class="btn pri" data-act="note-do" data-id="' + esc(m.id) + '">' + icon('save') + esc(t('save')) + '</button>'
    });
  };
  A['note-do'] = function (el) {
    var m = byId(el.dataset.id); var title = $('#note-title').value.trim() || 'Note';
    api('chat.note', { title: title, content: m.content }).then(function (r) { SP.closeSheet(); SP.toast(t('note.saved', { p: r.path })); }).catch(SP.err);
  };
  A['new-chat'] = newChat;
  A['drawer-close'] = function () { SP.closeDrawer(); };
  A['chat-load'] = function (el) { SP.closeDrawer(); loadChat(el.dataset.id); };
  A['chat-del'] = function (el) {
    var id = el.dataset.id;
    SP.ask(t('chat.delete'), t('chat.deleteQ'), t('delete'), true).then(function (ok) {
      if (!ok) return;
      api('chat.del', { id: id }).then(function () { if (S.chatId === id) newChat(); loadChats(); });
    });
  };
  A['go-memory'] = function () { SP.go('memory'); };
  A['go-models'] = function () { SP.go('settings'); setTimeout(function () { var c = $('#card-models'); if (c) c.scrollIntoView({ behavior: 'smooth' }); }, 60); };

  SP.renderChat = function () { SP.renderMsgs(); updateSend(); renderAtts(); };
  SP.render.chat = function () { updateSend(); };
  $('#input').addEventListener('input', autosize);
})();
