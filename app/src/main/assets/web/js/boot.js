/* Start-up: ask the phone for everything, then draw. */
(function () {
  'use strict';
  var SP = window.SP, S = SP.S;
  function fail(e) {
    document.body.insertAdjacentHTML('beforeend', '<pre style="position:fixed;z-index:99;top:0;left:0;right:0;margin:0;color:#fff;background:#a00;padding:10px;font-size:12px;white-space:pre-wrap">' + SP.esc((e && e.message) || e) + '</pre>');
  }
  window.addEventListener('error', function (e) { if (!S.booted) fail(e.message); });
  SP.api('init').then(function (r) {
    S.cfg = r.cfg; S.models = r.models || []; S.catalog = r.catalog || []; S.engine = r.engine || S.engine; S.gpu = !!r.gpu; S.cpuEngine = r.cpuEngine !== false;
    S.ramMB = r.ramMB; S.perms = r.perms || {}; S.vault = r.vault || {}; S.version = r.version; S.device = r.device; S.locale = r.locale; S.tps = r.tps || 0;
    S.defPrompts = r.defPrompts || []; S.defAgent = r.defAgent || '';
    SP.renderAll(); SP.go('chat'); SP.loadChats(); S.booted = true;
    SP.api('ui.theme', { theme: S.cfg.theme });
  }).catch(fail);
})();
