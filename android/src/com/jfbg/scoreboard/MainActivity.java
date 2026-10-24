package com.jfbg.scoreboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 记分板 · 独立安卓 App（不依赖任何网站）
 *
 * 架构：
 *   界面 = 打包在 APK 里的 HTML（assets/www），运行在虚拟安全源
 *           https://appassets.androidplatform.net 上
 *   数据 = 本机 SQLite（ScoreStore），完全离线，不访问网络
 *   导出 = 原生写入「下载/记分板导出」
 *
 * 相比之前的联网版：
 *   - 删除了「启动时从 jfbgv1.cn 拉取最新页面」的逻辑 → 不再依赖网站
 *   - 删除 INTERNET 权限依赖的实际使用（保留权限仅为兼容旧清单）
 *   - 页面里所有 fetch(Supabase) 改成本地 AndroidBridge 调用
 */
public class MainActivity extends Activity {

    private static final String TAG = "ScoreboardApp";

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** 虚拟安全域名：安全上下文（localStorage / crypto.randomUUID 可用），且不需要网络 */
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + APP_HOST + "/index.html";

    private WebView webView;
    private ScoreStore store;
    private long lastBackPress = 0L;
    private Uri lastSavedUri = null;
    private String lastSavedMime = null;
    private String lastSavedPath = null;

    private boolean diagnostics = false;

    private static final Map<String, String> MIME = new HashMap<>();
    static {
        MIME.put("html", "text/html");
        MIME.put("js", "application/javascript");
        MIME.put("css", "text/css");
        MIME.put("json", "application/json");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("svg", "image/svg+xml");
        MIME.put("ico", "image/x-icon");
        MIME.put("txt", "text/plain");
        MIME.put("webmanifest", "application/manifest+json");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        diagnostics = getIntent() != null && getIntent().getBooleanExtra("selftest", false);

        store = new ScoreStore(this);
        store.ensureSeeded();

        applySystemBars();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#e9edf6"));
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setTextZoom(100);
        // 完全本地：所有请求都由 shouldInterceptRequest 从 assets 返回，
        // 非本机虚拟域名一律 403，因此不会有任何真实网络流量。
        // 这里显式置 false —— 若置 true，部分 WebView 版本会把主文档也判为网络加载而白屏。
        s.setBlockNetworkLoads(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            s.setSafeBrowsingEnabled(false);   // 纯本地内容，无需安全浏览联网校验
        }

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.i(TAG, "console/" + cm.messageLevel() + " " + cm.message()
                        + " @" + cm.sourceId() + ":" + cm.lineNumber());
                return true;
            }
        });
        webView.addJavascriptInterface(new NativeBridge(), "AndroidBridge");
        webView.setWebViewClient(new AssetClient());
        webView.loadUrl(START_URL);
    }

    private void applySystemBars() {
        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#e9edf6"));
        if (Build.VERSION.SDK_INT >= 26) {
            w.setNavigationBarColor(Color.parseColor("#e9edf6"));
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    /** 所有请求都从 assets 读取，永不联网 */
    private final class AssetClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (url == null || !APP_HOST.equals(url.getHost())) {
                // 外部资源一律拦截为空（App 内不该出现任何外部请求）
                return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", null,
                        new ByteArrayInputStream(new byte[0]));
            }
            return serveAsset(url.getPath());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return true;   // 不跳转任何外部链接
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            injectBridgeScript(view);
            if (diagnostics) {
                view.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        runDiagnostics(view);
                    }
                }, 3000L);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            Log.e(TAG, "asset error " + request.getUrl() + " -> " + error.getDescription());
        }
    }

    private WebResourceResponse serveAsset(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) path = "/index.html";

        String ext = "";
        int dot = path.lastIndexOf('.');
        if (dot >= 0) ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = MIME.containsKey(ext) ? MIME.get(ext) : "application/octet-stream";

        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        return assetResponse("www" + path, mime, headers);
    }

    private WebResourceResponse assetResponse(String assetPath, String mime, Map<String, String> headers) {
        try {
            InputStream in = getAssets().open(assetPath);
            return new WebResourceResponse(mime, "utf-8", 200, "OK", headers, in);
        } catch (IOException e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers,
                    new ByteArrayInputStream("not found".getBytes(StandardCharsets.UTF_8)));
        }
    }

    private void injectBridgeScript(WebView view) {
        try {
            byte[] buf = readAsset("www/native-bridge.js");
            if (buf == null) return;
            view.evaluateJavascript(new String(buf, StandardCharsets.UTF_8), null);
        } catch (Exception ignored) {
        }
    }

    private void runDiagnostics(WebView view) {
        try {
            byte[] buf = readAsset("www/diagnostics.js");
            if (buf == null) return;
            view.evaluateJavascript(new String(buf, StandardCharsets.UTF_8), new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String v) {
                    Log.i(TAG, "DIAG " + v);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "diagnostics failed", e);
        }
    }

    private byte[] readAsset(String path) {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) bos.write(tmp, 0, n);
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** 网页可调用的本地数据 + 文件能力 */
    private final class NativeBridge {

        @JavascriptInterface
        public boolean isAvailable() {
            return true;
        }

        // ---------- 数据读写（全部本地） ----------

        /** 读出全部成员与记录 */
        @JavascriptInterface
        public String loadAll() {
            try {
                return store.loadAll().toString();
            } catch (Exception e) {
                Log.e(TAG, "loadAll failed", e);
                return "{\"members\":[]}";
            }
        }

        /** 整体覆盖（备份恢复/导入用） */
        @JavascriptInterface
        public boolean replaceAll(String json) {
            try {
                store.replaceAll(new JSONObject(json));
                return true;
            } catch (Exception e) {
                Log.e(TAG, "replaceAll failed", e);
                return false;
            }
        }

        @JavascriptInterface
        public boolean upsertMember(String id, String name) {
            try {
                store.upsertMember(id, name);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "upsertMember failed", e);
                return false;
            }
        }

        @JavascriptInterface
        public boolean renameMember(String id, String name) {
            try {
                store.renameMember(id, name);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean deleteMember(String id) {
            try {
                store.deleteMember(id);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** 写一条记录：rec 为 JSON 字符串 */
        @JavascriptInterface
        public boolean putRecord(String memberId, String recJson) {
            try {
                store.putRecordForMember(memberId, new JSONObject(recJson));
                return true;
            } catch (Exception e) {
                Log.e(TAG, "putRecord failed", e);
                return false;
            }
        }

        /** 软删除（撤回） */
        @JavascriptInterface
        public boolean softDeleteRecord(String recordId) {
            try {
                store.softDeleteRecord(recordId);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        // ---------- 管理员密码（本地） ----------

        @JavascriptInterface
        public boolean verifyPassword(String pwd) {
            return pwd != null && pwd.equals(store.getPassword());
        }

        @JavascriptInterface
        public boolean changePassword(String newPwd) {
            if (newPwd == null || newPwd.trim().isEmpty()) return false;
            store.setPassword(newPwd);
            return true;
        }

        // ---------- 文件导出（分块，避免 Binder 1MB 限制） ----------

        private ExportBuffer pending = null;

        /** 开始一个导出文件 */
        @JavascriptInterface
        public synchronized boolean saveFileBegin(String fileName, String mimeType) {
            try {
                String safeName = sanitize(fileName == null || fileName.trim().isEmpty()
                        ? "记分板导出.xlsx" : fileName.trim());
                String mime = (mimeType == null || mimeType.trim().isEmpty())
                        ? XLSX_MIME : mimeType;
                pending = new ExportBuffer(safeName, mime);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "saveFileBegin failed", e);
                pending = null;
                return false;
            }
        }

        /** 追加一块 base64 数据 */
        @JavascriptInterface
        public synchronized boolean saveFileChunk(String base64) {
            if (pending == null) return false;
            try {
                pending.add(Base64.decode(base64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                Log.e(TAG, "saveFileChunk failed", e);
                return false;
            }
        }

        /** 结束并落盘 */
        @JavascriptInterface
        public synchronized boolean saveFileEnd() {
            final ExportBuffer buf = pending;
            pending = null;
            if (buf == null) return false;
            final byte[] data = buf.drain();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        saveToDownloads(buf.getFileName(), buf.getMimeType(), data);
                        showSavedDialog();
                    } catch (Exception e) {
                        Log.e(TAG, "saveFileEnd failed", e);
                        toastOnUi("导出失败：" + e.getMessage());
                    }
                }
            });
            return true;
        }

        /** 兼容旧调用：一次传完整文件 */
        @JavascriptInterface
        public void saveFile(final String fileName, final String mimeType, final String base64) {
            if (!saveFileBegin(fileName, mimeType)) { toastOnUi("导出失败"); return; }
            if (!saveFileChunk(base64)) { toastOnUi("导出失败：数据损坏"); return; }
            saveFileEnd();
        }

        @JavascriptInterface
        public void toast(String msg) {
            toastOnUi(msg);
        }
    }

    private String sanitize(String name) {
        String n = name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (n.isEmpty()) n = "记分板导出.xlsx";
        if (n.length() > 120) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 ? n.substring(dot) : "";
            n = n.substring(0, 100) + ext;
        }
        return n;
    }

    private void saveToDownloads(String fileName, String mime, byte[] data) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/记分板导出");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("无法创建下载文件");
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IOException("无法写入下载文件");
                os.write(data);
                os.flush();
            }
            lastSavedUri = uri;
            lastSavedMime = mime;
            lastSavedPath = "下载/记分板导出/" + fileName;
        } else {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "记分板导出");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建目录");
            File f = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(data);
            }
            lastSavedUri = null;
            lastSavedMime = mime;
            lastSavedPath = f.getAbsolutePath();
        }
    }

    private void showSavedDialog() {
        String msg = "文件已保存到：\n" + lastSavedPath;
        try {
            new AlertDialog.Builder(this)
                    .setTitle("导出完成")
                    .setMessage(msg)
                    .setPositiveButton("打开", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            openSaved();
                        }
                    })
                    .setNegativeButton("知道了", null)
                    .show();
        } catch (Exception e) {
            toastOnUi("已保存：" + lastSavedPath);
        }
    }

    private void openSaved() {
        if (lastSavedUri == null) {
            Toast.makeText(this, "文件已保存到 " + lastSavedPath, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(lastSavedUri, lastSavedMime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "没有可打开该文件的应用，请在文件管理器打开 " + lastSavedPath,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void toastOnUi(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        webView.evaluateJavascript(
                "(function(){try{return !!(window.__handleBack && window.__handleBack());}catch(e){return false;}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if ("true".equals(value)) return;
                        long now = System.currentTimeMillis();
                        if (now - lastBackPress < 2000) {
                            finish();
                        } else {
                            lastBackPress = now;
                            Toast.makeText(MainActivity.this, "再按一次返回键退出", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        if (store != null) store.close();
        super.onDestroy();
    }
}
