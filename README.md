# 记分板 · 管理端 Android App

把原本部署在 Cloudflare Pages 上的计分板网页（<https://jfbgv1.cn>）封装成原生 Android 应用。
后端仍是 Supabase，数据与网页版实时互通。

## 特性

- **混合加载**：联网启动时自动拉取 <https://jfbgv1.cn> 的最新页面，改完网页无需重装 App 即生效；
  断网时回退到 APK 内置的离线副本，照常查看与操作（数据同步等联网后恢复）。
- **完整功能**：加减分、批量设置、成员管理、详细记录筛选、幸运抽签/分组、主题切换。
- **Excel 导出**：网页原本走浏览器下载，WebView 没有下载通道，这里由原生桥接
  写入系统的「下载/记分板导出」目录，导完可直接打开。
- **返回键导航**：优先关闭弹窗 → 回到统计页 → 双击退出。
- **安全上下文**：页面运行在 `https://appassets.androidplatform.net` 虚拟域，
  `localStorage`、`crypto.randomUUID` 等 API 与在线环境一致，账号与缓存不分裂。

## 目录结构

```
android/            原生壳工程
  AndroidManifest.xml
  src/com/jfbg/scoreboard/MainActivity.java   WebView 容器 + 混合加载 + 导出桥接
  res/               图标、主题、字符串
  assets/www/        离线网页包（index.html、SheetJS、图标、桥接脚本）
web-src/            网页源码（部署到 Cloudflare 的那一份，含本次修复）
release/            可直接安装的签名 APK
build.sh            一键构建脚本
keystore-private/   签名密钥（不纳入版本库）
```

## 构建

需要 Android SDK 工具链（`aapt`、`javac`、`d8`、`zipalign`、`apksigner`）与 `android.jar`：

```bash
./build.sh
# 产物：build/记分板-v1.0.0.apk
```

## 安装

```bash
adb install -r release/记分板-v1.0.0.apk
```

或把 APK 传到手机后点击安装（需允许「安装未知来源应用」）。

## 后端

Supabase（项目 ref `swnuwguqcvosyscujlyz`），写操作统一经过 Edge Function
`admin_api`，由 `x-admin-password` 头校验；表 `members` 开启了 RLS，
匿名 `anon` key 只能读，不能直接写。

## 安全说明

- 仓库中**不包含**签名私钥，也不包含 Supabase `service_role` key。
- 网页里出现的 `anon` key 是设计上公开的，安全性由 RLS + Edge Function 密码校验保证。
- 修改密码后请在 App 中重新登录管理页。
