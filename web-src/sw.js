const CACHE_NAME = 'scoreboard-admin-v3';
const APP_SHELL = [
  './',
  './index.html',
  './manifest.json',
  './icon-192.png',
  './icon-512.png',
  'https://cdn.sheetjs.com/xlsx-0.20.3/package/dist/xlsx.full.min.js'
];

// 安装：预缓存应用外壳
self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then(cache => cache.addAll(APP_SHELL).catch(err => {
        console.warn('部分资源缓存失败：', err);
      }))
      .then(() => self.skipWaiting())
  );
});

// 激活：清理旧版本缓存
self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(keys => Promise.all(
      keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k))
    )).then(() => self.clients.claim())
  );
});

// 拦截请求
self.addEventListener('fetch', event => {
  const req = event.request;
  const url = new URL(req.url);

  // 只处理 GET
  if (req.method !== 'GET') return;

  // ⭐ 关键：非本站请求（Supabase API / Worker 代理 / Edge Function）
  // 一律走网络，不缓存，保证数据实时
  if (url.origin !== self.location.origin) return;

  // ⭐ 关键：本站路径以 /sb/ 或 /functions/ 开头的也是 API 请求，跳过缓存
  if (url.pathname.startsWith('/sb/') || url.pathname.startsWith('/functions/')) return;

  // 页面导航：网络优先，失败回退缓存
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req).then(res => {
        const clone = res.clone();
        caches.open(CACHE_NAME).then(c => c.put('./index.html', clone));
        return res;
      }).catch(() => caches.match('./index.html'))
    );
    return;
  }

  // 其他静态资源（含 SheetJS CDN）：缓存优先，回退网络
  event.respondWith(
    caches.match(req).then(cached => {
      if (cached) return cached;
      return fetch(req).then(res => {
        const isSameOrigin = res.type === 'basic';
        const isSheetJS = req.url.includes('cdn.sheetjs.com');
        if (res && res.status === 200 && (isSameOrigin || isSheetJS)) {
          const clone = res.clone();
          caches.open(CACHE_NAME).then(c => c.put(req, clone));
        }
        return res;
      });
    })
  );
});