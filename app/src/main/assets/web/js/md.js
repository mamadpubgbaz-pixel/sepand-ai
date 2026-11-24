/* Small, safe Markdown renderer. All text is escaped first, so model output can never inject HTML or scripts. */
(function () {
  'use strict';
  var MAP = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return MAP[c]; }); }
  function safeUrl(u) { u = String(u).trim(); return /^(https?:\/\/|mailto:)/i.test(u) ? u : ''; }

  function inline(raw) {
    var s = esc(raw), codes = [];
    s = s.replace(/`([^`\n]+)`/g, function (m, c) { codes.push(c); return '\u0000' + (codes.length - 1) + '\u0000'; });
    s = s.replace(/\[([^\]\n]+)\]\(([^)\s]+)\)/g, function (m, txt, url) {
      var u = safeUrl(url.replace(/&amp;/g, '&'));
      return u ? '<a href="#" data-u="' + esc(u) + '">' + txt + '</a>' : m;
    });
    s = s.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
      .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
      .replace(/(^|[^\w])_([^_\n]+)_(?=$|[^\w])/g, '$1<em>$2</em>')
      .replace(/~~([^~\n]+)~~/g, '<del>$1</del>')
      .replace(/\[\[([^\]\n]+)\]\]/g, '<span class="wl">$1</span>');
    return s.replace(/\u0000(\d+)\u0000/g, function (m, i) { return '<code>' + codes[+i] + '</code>'; });
  }

  var TBL_SEP = /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/;
  var BLOCK_START = /^\s*(```|#{1,4}\s|[-*+]\s|\d+[.)]\s|>|\|)/;

  function render(src, depth) {
    depth = depth || 0;
    var lines = String(src == null ? '' : src).replace(/\r/g, '').split('\n'), out = '', i = 0, m;
    while (i < lines.length) {
      var l = lines[i];
      if ((m = l.match(/^\s*```(\w*)/))) {
        var buf = []; i++;
        while (i < lines.length && !/^\s*```/.test(lines[i])) buf.push(lines[i++]);
        i++;
        out += '<pre><button class="cp" data-act="copy-code" aria-label="copy"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a1 1 0 01-1-1V4a1 1 0 011-1h10a1 1 0 011 1v1"/></svg></button><code>' + esc(buf.join('\n')) + '</code></pre>';
        continue;
      }
      if (/^\s*$/.test(l)) { i++; continue; }
      if ((m = l.match(/^(#{1,4})\s+(.*)$/))) { var n = m[1].length + 1; out += '<h' + n + '>' + inline(m[2]) + '</h' + n + '>'; i++; continue; }
      if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(l)) { out += '<hr>'; i++; continue; }
      if (/^\s*>/.test(l) && depth < 3) {
        var q = [];
        while (i < lines.length && /^\s*>/.test(lines[i])) q.push(lines[i++].replace(/^\s*>\s?/, ''));
        out += '<blockquote>' + render(q.join('\n'), depth + 1) + '</blockquote>';
        continue;
      }
      if (/^\s*[-*+]\s+/.test(l)) {
        var it = [];
        while (i < lines.length && /^\s*[-*+]\s+/.test(lines[i])) it.push(lines[i++].replace(/^\s*[-*+]\s+/, ''));
        out += '<ul>' + it.map(function (x) { return '<li>' + inline(x) + '</li>'; }).join('') + '</ul>';
        continue;
      }
      if (/^\s*\d+[.)]\s+/.test(l)) {
        var ol = [];
        while (i < lines.length && /^\s*\d+[.)]\s+/.test(lines[i])) ol.push(lines[i++].replace(/^\s*\d+[.)]\s+/, ''));
        out += '<ol>' + ol.map(function (x) { return '<li>' + inline(x) + '</li>'; }).join('') + '</ol>';
        continue;
      }
      if (/^\s*\|.*\|\s*$/.test(l) && i + 1 < lines.length && TBL_SEP.test(lines[i + 1])) {
        var cells = function (r) { return r.trim().replace(/^\||\|$/g, '').split('|').map(function (c) { return c.trim(); }); };
        var head = cells(l), rows = [];
        i += 2;
        while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) rows.push(cells(lines[i++]));
        out += '<div class="tbl"><table><thead><tr>' + head.map(function (c) { return '<th>' + inline(c) + '</th>'; }).join('') + '</tr></thead><tbody>' +
          rows.map(function (r) { return '<tr>' + r.map(function (c) { return '<td>' + inline(c) + '</td>'; }).join('') + '</tr>'; }).join('') + '</tbody></table></div>';
        continue;
      }
      var p = [l]; i++;
      while (i < lines.length && !/^\s*$/.test(lines[i]) && !BLOCK_START.test(lines[i])) p.push(lines[i++]);
      out += '<p>' + p.map(inline).join('<br>') + '</p>';
    }
    return out;
  }
  window.MD = { render: render, esc: esc };
})();
