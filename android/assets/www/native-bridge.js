/* 记分板 · 离线版原生桥（由 MainActivity 在页面加载完成后注入）
 *
 * 与联网版的区别：
 *   - 数据读写全部走 AndroidBridge → 本机 SQLite（不联网）
 *   - 导出改为「分块」写入，避免大文件触发 Binder 1MB 限制
 */
(function () {
  'use strict';
  if (window.__nativeBridgeInstalled) return;
  window.__nativeBridgeInstalled = true;

  var bridge = window.AndroidBridge;
  if (!bridge) {
    console.warn('[native] AndroidBridge 不可用（可能在普通浏览器里打开）');
    return;
  }

  var XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
  var CHUNK = 256 * 1024;   // 每块 256KB 原始字节 → base64 约 350KB，安全低于 1MB

  function toast(msg) {
    try { if (bridge.toast) { bridge.toast(String(msg)); return; } } catch (e) {}
    console.log('[native]', msg);
  }

  function abToBase64(buffer, start, end) {
    var bytes = new Uint8Array(buffer, start, end - start);
    var parts = [];
    var step = 0x8000;
    for (var i = 0; i < bytes.length; i += step) {
      parts.push(String.fromCharCode.apply(null, bytes.subarray(i, i + step)));
    }
    return btoa(parts.join(''));
  }

  /** 分块保存任意二进制 */
  function saveNative(fileName, mime, arrayBuffer) {
    if (!bridge.saveFileBegin) return false;
    var total = arrayBuffer.byteLength;
    try {
      if (!bridge.saveFileBegin(fileName, mime)) return false;
      for (var off = 0; off < total; off += CHUNK) {
        var end = Math.min(off + CHUNK, total);
        if (!bridge.saveFileChunk(abToBase64(arrayBuffer, off, end))) {
          toast('导出失败：写入中断');
          return false;
        }
      }
      return bridge.saveFileEnd();
    } catch (e) {
      console.error('原生保存失败', e);
      toast('导出失败：' + e.message);
      return false;
    }
  }

  /* ---------- 1. xlsx 导出：接管 XLSX.writeFile ---------- */
  function patchXLSX() {
    if (!window.XLSX || window.XLSX.__nativePatched) return !!window.XLSX;
    var X = window.XLSX;
    var origWriteFile = X.writeFile;

    X.writeFile = function (wb, filename, opts) {
      opts = opts || {};
      var name = typeof filename === 'string' ? filename : '记分板导出.xlsx';
      try {
        var isCSV = /\.csv$/i.test(name) || opts.bookType === 'csv';
        var out = X.write(wb, {
          bookType: isCSV ? 'csv' : (opts.bookType || 'xlsx'),
          type: 'array',
          compression: !isCSV
        });
        var buf = out instanceof ArrayBuffer ? out : new Uint8Array(out).buffer;
        var mime = isCSV ? 'text/csv' : XLSX_MIME;
        if (saveNative(name, mime, buf)) return;
      } catch (e) {
        console.error('生成表格失败', e);
      }
      try { return origWriteFile.call(X, wb, filename, opts); } catch (e) {}
    };

    X.__nativePatched = true;
    return true;
  }

  if (!patchXLSX()) {
    var tries = 0;
    var timer = setInterval(function () {
      tries++;
      if (patchXLSX() || tries > 40) clearInterval(timer);
    }, 250);
  }

  /* ---------- 2. 兜底：拦截 <a download> 的 blob:/data: ---------- */
  document.addEventListener('click', function (ev) {
    var a = ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;
    if (!a) return;
    var href = a.getAttribute('href') || '';
    if (href.indexOf('blob:') !== 0 && href.indexOf('data:') !== 0) return;
    ev.preventDefault();
    ev.stopPropagation();
    var name = a.getAttribute('download') || '导出文件';
    var mime = a.getAttribute('data-mime') || XLSX_MIME;
    fetch(href).then(function (r) { return r.arrayBuffer(); }).then(function (ab) {
      saveNative(name, mime, ab);
    }).catch(function () { toast('导出失败'); });
  }, true);

  /* ---------- 3. 返回键：先关弹窗 → 再回首页 → 最后原生退出 ---------- */
  window.__handleBack = function () {
    var overlay = document.getElementById('dialogOverlay');
    if (overlay && !overlay.classList.contains('hidden')) {
      try {
        if (typeof window.closeDialog === 'function') window.closeDialog(null);
        else overlay.classList.add('hidden');
      } catch (e) { overlay.classList.add('hidden'); }
      return true;
    }
    try {
      var home = document.getElementById('homeView');
      if (home && home.classList.contains('hidden')) {
        if (typeof window.switchView === 'function') { window.switchView('homeView'); return true; }
      }
    } catch (e) {}
    return false;
  };

  console.log('[native] 离线版桥接就绪');
})();
