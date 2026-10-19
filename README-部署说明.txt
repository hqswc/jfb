记分板网页版部署包
==================

这 5 个文件就是 jfbgv1.cn 的完整站点内容：

  index.html      主页面（已修复日期筛选时区问题）
  manifest.json   PWA 清单
  sw.js           Service Worker（离线缓存）
  icon-192.png    图标
  icon-512.png    图标

上传方式（Cloudflare Pages）：
  1. 打开 https://dash.cloudflare.com
  2. Workers & Pages -> 选择你的项目
  3. Create new deployment
  4. 把这 5 个文件直接拖进去（不要包一层文件夹）
  5. Save and Deploy

与上一版的唯一区别：
  日期筛选的起始日期原来按 UTC 零点解析（= 北京时间 08:00），
  会漏掉每天 0:00-8:00 的记录。现已改为本地时区解析。

注意：App 里用的版本另外放在 android/assets/www/，那份把 SheetJS
改成了本地内置（为了离线可用），不要拿那份覆盖这里。
