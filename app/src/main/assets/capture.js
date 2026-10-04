(function () {
  if (window.__pcs) return;
  window.__pcs = 1;
  var MIN = 128, MAX_B64 = 36 * 1024 * 1024;
  var FID = Math.random().toString(36).slice(2, 8), FLUSH = '__pcs_flush__';

  function send(o) { try { shell.postMessage(JSON.stringify(o)); } catch (e) {} }

  function toB64(blob) {
    return new Promise(function (res, rej) {
      var r = new FileReader();
      r.onload = function () {
        var s = String(r.result), i = s.indexOf(',');
        res({ mime: blob.type || 'image/png', b64: s.slice(i + 1) });
      };
      r.onerror = rej;
      r.readAsDataURL(blob);
    });
  }
  function fromSrc(src) {
    if (src.indexOf('data:') === 0) {
      var i = src.indexOf(',');
      if (i < 0) return Promise.reject();
      var head = src.slice(5, i);
      if (head.indexOf(';base64') >= 0) return Promise.resolve({ mime: head.split(';')[0], b64: src.slice(i + 1) });
    }
    return fetch(src).then(function (r) { if (!r.ok) throw 0; return r.blob(); }).then(toB64);
  }
  function fromCanvas(c) {
    return new Promise(function (res, rej) {
      try { c.toBlob(function (b) { b ? toB64(b).then(res, rej) : rej(); }, 'image/png'); } catch (e) { rej(e); }
    });
  }
  function all(sel, root, out) {
    out = out || [];
    root.querySelectorAll(sel).forEach(function (n) { out.push(n); });
    root.querySelectorAll('*').forEach(function (n) { if (n.shadowRoot) all(sel, n.shadowRoot, out); });
    return out;
  }
  function collect() {
    var items = [], seen = {};
    all('img', document).forEach(function (im) {
      var s = im.currentSrc || im.src;
      if (!s || seen[s] || !im.complete || Math.min(im.naturalWidth, im.naturalHeight) < MIN) return;
      seen[s] = 1;
      items.push(function () { return fromSrc(s); });
    });
    if (!items.length) {
      all('canvas', document).forEach(function (c) {
        if (Math.min(c.width, c.height) >= MIN) items.push(function () { return fromCanvas(c); });
      });
    }
    return items;
  }
  function forward() {
    all('iframe', document).forEach(function (f) {
      try { f.contentWindow.postMessage(FLUSH, '*'); } catch (e) {}
    });
  }
  function flush() {
    var items = collect();
    send({ t: 'count', f: FID, n: items.length });
    items.forEach(function (get, i) {
      var id = FID + '_' + i;
      get().then(function (r) {
        if (!r || !r.b64 || r.b64.length > MAX_B64) throw 0;
        send({ t: 'img', f: FID, id: id, mime: r.mime, b64: r.b64 });
      }).catch(function () { send({ t: 'fail', f: FID, id: id }); });
    });
    forward();
  }
  // child frames only obey their direct parent
  window.addEventListener('message', function (e) {
    if (e.data === FLUSH && e.source === window.parent && window.parent !== window) flush();
  });
  window.__pcsFlush = flush;
})();
