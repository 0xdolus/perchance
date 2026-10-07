// Dev tool: builds a compact structural outline of this frame and sends it to native (copied to clipboard).
// Child frames only obey their direct parent, same pattern as capture.js.
(function () {
  if (window.__pcsDump) return;
  var CMD = '__pcs_dump__', MAX_NODES = 700, MAX_DEPTH = 16, MAX_CHARS = 60000;
  var SKIP = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, LINK: 1, META: 1, PATH: 1, SVG: 1, TEMPLATE: 1 };

  function cut(s, n) { s = String(s || '').replace(/\s+/g, ' ').trim(); return s.length > n ? s.slice(0, n) + '…' : s; }

  function sig(el) { return el.tagName + '#' + (el.id || '') + '.' + String(el.className && el.className.baseVal !== undefined ? el.className.baseVal : el.className || ''); }

  function line(el, depth) {
    var s = '  '.repeat(depth) + el.tagName;
    if (el.id) s += '#' + el.id;
    var cls = String(el.className && el.className.baseVal !== undefined ? el.className.baseVal : el.className || '').trim().split(/\s+/).filter(Boolean).slice(0, 4);
    cls.forEach(function (c) { s += '.' + cut(c, 30); });
    var own = '';
    for (var n = el.firstChild; n; n = n.nextSibling) if (n.nodeType === 3) own += n.nodeValue;
    own = cut(own, 80);
    if (own) s += ' text="' + own + '"';
    ['aria-label', 'title', 'name', 'type', 'role', 'placeholder'].forEach(function (a) {
      var v = el.getAttribute && el.getAttribute(a);
      if (v) s += ' ' + a + '="' + cut(v, 50) + '"';
    });
    if (el.tagName === 'IMG' || el.tagName === 'IFRAME') {
      var src = el.currentSrc || el.src || '';
      s += ' src="' + (src.indexOf('data:') === 0 ? 'data:…(' + src.length + ')' : cut(src, 90)) + '"';
    }
    if (el.tagName === 'A' && el.href) s += ' href="' + cut(el.href, 90) + '"';
    if (el.tagName === 'SELECT') s += ' value="' + cut(el.value, 30) + '"';
    try { if (getComputedStyle(el).display === 'none') s += ' [hidden]'; } catch (_) {}
    return s;
  }

  function walk(el, depth, out, state) {
    if (state.n >= MAX_NODES || depth > MAX_DEPTH || SKIP[el.tagName]) return;
    state.n++;
    out.push(line(el, depth));
    var kids = [];
    if (el.shadowRoot) { out.push('  '.repeat(depth + 1) + '#shadow-root'); kids = Array.prototype.slice.call(el.shadowRoot.children); }
    kids = kids.concat(Array.prototype.slice.call(el.children));
    var prev = '', run = 0, skipped = 0;
    kids.forEach(function (k) {
      var s = sig(k);
      if (s === prev) { run++; if (run > 2) { skipped++; return; } } else { if (skipped) out.push('  '.repeat(depth + 1) + '… +' + skipped + ' similar'); prev = s; run = 1; skipped = 0; }
      walk(k, depth + 1, out, state);
    });
    if (skipped) out.push('  '.repeat(depth + 1) + '… +' + skipped + ' similar');
  }

  function dump() {
    var out = [], state = { n: 0 };
    try { walk(document.body || document.documentElement, 0, out, state); } catch (e) { out.push('ERROR ' + e); }
    var text = out.join('\n');
    if (text.length > MAX_CHARS) text = text.slice(0, MAX_CHARS) + '\n…(truncated)';
    try { shell.postMessage(JSON.stringify({ t: 'dom', url: location.href, text: text })); } catch (_) {}
    Array.prototype.forEach.call(document.querySelectorAll('iframe'), function (f) {
      try { f.contentWindow.postMessage(CMD, '*'); } catch (_) {}
    });
  }

  window.addEventListener('message', function (e) {
    if (e.data === CMD && e.source === window.parent && window.parent !== window) dump();
  });
  window.__pcsDump = dump;
})();
