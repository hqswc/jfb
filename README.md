# 记分板 · 管理端 Android App（离线版 2.0.0）

原本是一个部署在 Cloudflare Pages 上的计分板网页（<https://jfbgv1.cn>），后端 Supabase。
现在已改造为**完全独立的单机 Android 应用**：页面打包进 APK，数据存手机本地 SQLite，
不联网、不依赖任何服务器。

## 特性

- **完全离线**：APK 内不含 `INTERNET` 权限，飞行模式下加减分、抽签、导出全部正常。
- **数据本地化**：SQLite（`scoreboard.db`），首次启动从内置 seed 灌入 12 名成员 / 242 条记录。
- **完整功能**：加减分、批量设置、成员管理、详细记录筛选、撤销/编辑、幸运抽签/分组、暗色主题。
- **Excel 导出**：原生桥接分块写文件（避开 Binder 1MB 限制），落到「内部存储/Download/记分板导出」。
- **返回键导航**：优先关闭弹窗 → 回到统计页 → 双击退出。
- **安全上下文**：页面运行在 `https://appassets.androidplatform.net` 虚拟域，
  `localStorage`、`crypto.randomUUID` 等 API 可用（实际请求由 `shouldInterceptRequest` 从 assets 读，不出网）。

## 目录结构

```
android/            原生壳工程
  AndroidManifest.xml                        仅 VIBRATE 权限，versionCode 2
  src/com/jfbg/scoreboard/
    MainActivity.java       WebView 容器 + assets 拦截 + 原生桥注册 + 返回键
    ScoreStore.java         SQLite 数据层（members / records / meta）
    ExportBuffer.java       分块累积二进制，供 base64 分段写入
  res/               图标、主题、字符串
  assets/
    www/             打包进 APK 的页面（index.html、SheetJS、桥接脚本）
    seed/            初始数据 initial-data.json（12 人 / 242 条）
web-src/             App 版网页源码（CDN 已换本地，不能直接部署到 Cloudflare）
web-deploy/          网页版部署包（保留 CDN 与 Service Worker）
release/             可直接安装的签名 APK + 使用说明
seed/                种子数据（构建时拷进 assets/seed）
build.sh             一键构建脚本
keystore-private/    签名密钥（不纳入版本库）
```

## 仓库

<https://github.com/hqswc/jfb>

## 构建

需要 Android SDK 工具链（`aapt`、`javac`、`d8`、`zipalign`、`apksigner`）与 `android.jar`：

```bash
./build.sh
# 产物：build/记分板-v2.0.0-离线版.apk
```

`javac` 必须带 `-source 8 -target 8 -bootclasspath android.jar`，且**不能用 lambda**
（运行时缺 `LambdaMetafactory`），多线程/回调一律写匿名内部类。

## 安装

把 APK 传到手机后点击安装（需允许「安装未知来源应用」）。
与 v1.0.0 同包名同签名，可直接覆盖升级，本地数据不受影响。

## 数据说明

- 表结构：`members(id, name, sort_index)`、`records(id, member_id, ts, change, category, reason, operator, deleted, deleted_at)`、`meta(key, value)`。
- 删除记录是**软删除**（`deleted=1`），总分由 `loadAll()` 现算，只累加 `deleted=0` 的 `change`。
- `replaceAll()` 全量重写（前端 `autoSync()` 400ms 防抖后整体落库）。
- 升级路径：`onUpgrade()` 为空实现，**绝不清库**。

## 与网页版的关系

两套系统现在**彼此独立**：App 数据在手机本地，网页数据在 Supabase，互不同步。
App 的初始数据是网页版导出那一刻的快照。

## 安全说明

- 仓库中**不包含**签名私钥（`*.jks` 已 gitignore），也不含任何服务端密钥。
- 管理员密码默认 `admin2024`，存于 SQLite `meta` 表；忘记了无法找回，只能卸载重装。
- 单机 App，无网络攻击面；风险仅限手机被他人物理接触。
