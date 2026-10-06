// Strips Perchance's own navigation so the app stays a single-purpose shell.
// Runs in every perchance.org frame. Label matching is heuristic: adjust the lists below if Perchance changes its UI.
(function () {
  if (window.__pcsUi) return;
  window.__pcsUi = 1;

  var HIDE = /^(generators|new|edit|login|log in|sign in|sign up|feedback|view gallery|public gallery|community gallery)$/;
  var BAR = /^(generators|new|edit|login)$/;
  var TITLE = /public gallery|view gallery|community/i;
  var GALLERY_ICON = '\uD83C\uDF04'; // the icon-only gallery button

  function norm(s) {
    return String(s || '').replace(/[^a-z\s]/gi, ' ').replace(/\s+/g, ' ').trim().toLowerCase();
  }

  function hide(el) {
    el.style.setProperty('display', 'none', 'important');
    var p = el.parentElement;
    while (p && p !== document.body && p !== document.documentElement) {
      var all = true;
      for (var i = 0; i < p.children.length; i++) {
        var c = p.children[i], tag = c.tagName;
        if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'LINK' || tag === 'TEMPLATE') continue;
        if (c.style.display !== 'none') { all = false; break; }
      }
      if (!all) break;
      p.style.setProperty('display', 'none', 'important');
      p = p.parentElement;
    }
  }

  function rename(el) {
    var w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
    var n;
    while ((n = w.nextNode())) {
      if (/private\s+gallery/i.test(n.nodeValue)) n.nodeValue = n.nodeValue.replace(/private\s+gallery/ig, 'gallery');
    }
    var t = el.getAttribute && el.getAttribute('title');
    if (t && /private\s+gallery/i.test(t)) el.setAttribute('title', t.replace(/private\s+gallery/ig, 'gallery'));
  }

  function hideBar(bar) {
    if (bar.length < 2) return;
    var last = bar[bar.length - 1];
    var a = bar[0].parentElement;
    while (a && !a.contains(last)) a = a.parentElement;
    if (a && a !== document.body && a !== document.documentElement && (a.textContent || '').length < 120) hide(a);
  }

  function run() {
    var bar = [];
    var els = document.querySelectorAll('a,button,[role="button"],span,div,label,li');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.__pcs || el.childElementCount > 4) continue;
      var raw = (el.textContent || '').trim();
      if (raw.length > 40) continue;
      var t = norm(raw);
      var ttl = el.getAttribute('title') || el.getAttribute('aria-label') || '';
      if (t === 'private gallery') { rename(el); el.__pcs = 1; continue; }
      if (HIDE.test(t) || raw === GALLERY_ICON || TITLE.test(ttl)) {
        if (BAR.test(t)) bar.push(el);
        hide(el);
        el.__pcs = 1;
      }
    }
    hideBar(bar);
  }

  // Links cannot take the user away from the current page (downloads are left alone).
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a || a.hasAttribute('download')) return;
    var u;
    try { u = new URL(a.href, location.href); } catch (_) { return; }
    if (u.protocol === 'javascript:') return;
    if (u.host === location.host && u.pathname === location.pathname) return; // in-page anchors
    e.preventDefault();
    e.stopPropagation();
  }, true);

  var timer = 0;
  function schedule() {
    if (timer) return;
    timer = setTimeout(function () { timer = 0; try { run(); } catch (_) {} }, 150);
  }
  function init() {
    schedule();
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true, characterData: true });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
