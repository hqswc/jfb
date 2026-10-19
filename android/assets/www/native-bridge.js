/* 记分板 · 安卓外壳原生桥接脚本（由 MainActivity 在页面加载完成后注入） */
(function () {
  'use strict';
  if (window.__nativeBridgeInstalled) return;
  window.__nativeBridgeInstalled = true;

  var bridge = window.AndroidBridge;

  function toast(msg) {
    try { if (bridge && bridge.toast) { bridge.toast(String(msg)); return; } } catch (e) {}
    console.log('[native]', msg);
  }

  /* ---------- 1. xlsx 导出：交给原生写入"下载/记分板导出" ---------- */
  // 把 ArrayBuffer 转 base64（分块，避免大文件爆栈）
  function abToBase64(buffer) {
    var bytes = new Uint8Array(buffer);
    var chunk = 0x8000, parts = [];
    for (var i = 0; i < bytes.length; i += chunk) {
      parts.push(String.fromCharCode.apply(null, bytes.subarray(i, i + chunk)));
    }
    return btoa(parts.join(''));
  }

  function saveNative(fileName, mime, arrayBuffer) {
    if (!bridge || !bridge.saveFile) return false;
    try {
      bridge.saveFile(fileName, mime, abToBase64(arrayBuffer));
      return true;
    } catch (e) {
      console.error('原生保存失败', e);
      return false;
    }
  }

  var XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

  function patchXLSX() {
    if (!window.XLSX || window.XLSX.__nativePatched) return !!window.XLSX;
    var X = window.XLSX;
    var origWrite = X.write;
    var origWriteFile = X.writeFile;

    // 重写 writeFile：生成二进制后走原生
    X.writeFile = function (wb, filename, opts) {
      opts = opts || {};
      var name = typeof filename === 'string' ? filename : '记分板导出.xlsx';
      if (/\.csv$/i.test(name)) {
        try {
          var csv = X.write(wb, { bookType: 'csv', type: 'string' });
          saveNative(name, 'text/csv', new TextEncoder().encode(csv).buffer);
          return;
        } catch (e) { /* 落到下面 */ }
      }
      try {
        var out = X.write(wb, {
          bookType: opts.bookType || 'xlsx',
          type: 'array',
          compression: true
        });
        if (saveNative(name, XLSX_MIME, out)) return;
      } catch (e) {
        console.error('生成 xlsx 失败', e);
      }
      try { return origWriteFile.call(X, wb, filename, opts); } catch (e) {}
    };

    X.__nativePatched = true;
    return true;
  }

  // XLSX 可能晚于本脚本加载，轮询等待
  if (!patchXLSX()) {
    var tries = 0;
    var timer = setInterval(function () {
      tries++;
      if (patchXLSX() || tries > 40) clearInterval(timer); // 最多等 20 秒
    }, 500);
  }

  /* ---------- 2. 兜底：拦截 blob:/data: 的 <a download> ---------- */
  var origCreate = URL.createObjectURL;
  URL.createObjectURL = function (blob) {
    var url = origCreate.call(URL, blob);
    var name = (blob && blob.__name) || '导出文件';
    return url;
  };

  document.addEventListener('click', function (ev) {
    var a = ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;
    if (!a) return;
    var href = a.getAttribute('href') || '';
    var name = a.getAttribute('download') || '导出文件';
    if (href.indexOf('blob:') !== 0 && href.indexOf('data:') !== 0) return;
    ev.preventDefault();
    ev.stopPropagation();
    fetch(href).then(function (r) { return r.arrayBuffer(); }).then(function (ab) {
      var mime = a.getAttribute('data-mime') || XLSX_MIME;
      if (!saveNative(name, mime, ab)) toast('导出失败');
    }).catch(function () { toast('导出失败'); });
  }, true);

  /* ---------- 3. 返回键：先关弹窗，再回首页，最后交给原生退出 ---------- */
  window.__handleBack = function () {
    // 3.1 有弹窗就关
    var overlay = document.getElementById('dialogOverlay');
    if (overlay && !overlay.classList.contains('hidden')) {
      try {
        if (typeof window.closeDialog === 'function') window.closeDialog(null);
        else overlay.classList.add('hidden');
      } catch (e) { overlay.classList.add('hidden'); }
      return true;
    }
    // 3.2 不在统计页就回统计页
    try {
      var home = document.getElementById('homeView');
      var hidden = home && home.classList.contains('hidden');
      if (hidden) {
        if (typeof window.switchView === 'function') {
          window.switchView('homeView');
          return true;
        }
      }
    } catch (e) {}
    // 3.3 已在本页：让原生按两次退出
    return false;
  };

  console.log('[native] bridge ready');
})();
