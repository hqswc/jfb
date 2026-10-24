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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 记分板 · 安卓外壳
 *
 * 设计要点：
 * 1) 页面固定在虚拟安全源 https://appassets.androidplatform.net 下运行，
 *    这样 localStorage / crypto.randomUUID / 安全上下文都正常，且离线可用。
 * 2) 混合更新：启动时若联网，则从 https://jfbgv1.cn 拉取最新 index.html；
 *    拉取失败（断网/超时/内容异常）则回退到内置 assets/www 离线包。
 *    —— 这样你在 Cloudflare 上改完网页，App 不用重装就能生效；断网也照常打开。
 * 3) 补上 WebView 没有的能力：xlsx 导出写入「下载/记分板导出」、返回键导航、状态栏配色。
 */
public class MainActivity extends Activity {

    private static final String TAG = "ScoreboardApp";

    /** 虚拟安全域名（Android WebView 官方约定的本地源） */
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + APP_HOST + "/index.html";

    /** 线上站点：App 启动时尝试拉取最新页面 */
    private static final String REMOTE_BASE = "https://jfbgv1.cn";
    private static final boolean REMOTE_FIRST = true;
    private static final int CONNECT_TIMEOUT_MS = 2500;
    private static final int READ_TIMEOUT_MS = 4000;

    private WebView webView;
    private long lastBackPress = 0L;
    private Uri lastSavedUri = null;
    private String lastSavedMime = null;
    private String lastSavedPath = null;

    /** 诊断开关（仅 adb / am 附加参数可触发，普通用户不会遇到） */
    private boolean diagnostics = false;
    private boolean forceOffline = false;

    private volatile String remoteHtml = null;   // 本次进程内缓存线上页面
    private volatile boolean remoteTried = false;

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
        Intent it = getIntent();
        diagnostics = it != null && it.getBooleanExtra("selftest", false);
        forceOffline = it != null && it.getBooleanExtra("forceoffline", false);

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
        s.setTextZoom(100);                 // 不跟随系统字体缩放，版式与网页一致
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            s.setSafeBrowsingEnabled(true);
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

    /** 状态栏与网页底色一致（#e9edf6），深色图标 */
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

    /** appassets.androidplatform.net/** -> 线上最新页（联网时），否则内置离线包 */
    private final class AssetClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (url == null || !APP_HOST.equals(url.getHost())) return null;
            return serveAsset(url.getPath());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (url == null) return false;
            if (APP_HOST.equals(url.getHost())) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, url));
            } catch (Exception ignored) {
            }
            return true;
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
                }, 8000L);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            Log.e(TAG, "load error " + request.getUrl() + " -> " + error.getDescription());
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

        // index.html：优先线上最新版（保持同一虚拟源，localStorage 不分裂）
        if ("/index.html".equals(path) || "/".equals(path)) {
            String html = obtainRemoteHtml();
            if (html != null) {
                Log.i(TAG, "SERVING remote https://jfbgv1.cn/index.html (" + html.length() + " chars)");
                return new WebResourceResponse("text/html", "utf-8", 200, "OK", headers,
                        new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
            }
            Log.i(TAG, "SERVING bundled offline copy");
            return assetResponse("www/index.html", mime, headers);
        }
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

    /**
     * 拉取线上页面并做两处改造（每次启动只拉一次）：
     *  - 把 CDN 上的 SheetJS 换成本地内置副本（离线也能导出）
     *  - 去掉 Service Worker 注册（App 内由原生层负责更新，避免缓存叠加）
     */
    private String obtainRemoteHtml() {
        if (!REMOTE_FIRST || forceOffline) return null;
        if (remoteTried) return remoteHtml;
        synchronized (this) {
            if (remoteTried) return remoteHtml;
            remoteTried = true;
            String raw = fetchRemote(REMOTE_BASE + "/index.html");
            if (raw == null) {
                Log.w(TAG, "remote fetch failed, fallback to bundled");
                return null;
            }
            String html = transformHtml(raw);
            // 基本健康检查：像我们自己的页面才用，避免线上误部署把 App 打挂
            if (html.length() < 8000 || !html.contains("记分板")) {
                Log.w(TAG, "remote html rejected by sanity check");
                return null;
            }
            remoteHtml = html;
            return html;
        }
    }

    private String fetchRemote(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "ScoreboardApp/1.0 (Android)");
            conn.setRequestProperty("Accept", "text/html,*/*");
            conn.setRequestProperty("Cache-Control", "no-cache");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "remote http " + code);
                return null;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "remote fetch error: " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String transformHtml(String html) {
        String out = html.replaceAll(
                "<script src=\"https://cdn\\.sheetjs\\.com/[^\"]*\"></script>",
                "<script src=\"xlsx.full.min.js\"></script>");
        // 删除 Service Worker 注册块
        int s = out.indexOf("if('serviceWorker' in navigator){");
        if (s < 0) s = out.indexOf("if (\"serviceWorker\" in navigator)");
        if (s >= 0) {
            int e = out.indexOf("</script>", s);
            if (e > s) out = out.substring(0, s) + out.substring(e);
        }
        return out;
    }

    /** 原生桥接脚本（xlsx 落盘 / 返回键） */
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

    /** 供网页调用的原生能力 */
    private final class NativeBridge {
        @JavascriptInterface
        public boolean isAvailable() {
            return true;
        }

        /** 保存网页生成的 xlsx（base64）到「下载/记分板导出」 */
        @JavascriptInterface
        public void saveFile(final String fileName, final String mimeType, final String base64) {
            final byte[] data;
            try {
                data = Base64.decode(base64, Base64.DEFAULT);
            } catch (Exception e) {
                toastOnUi("导出失败：文件数据损坏");
                return;
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String safeName = sanitize(fileName == null || fileName.trim().isEmpty()
                                ? "记分板导出.xlsx" : fileName.trim());
                        String mime = (mimeType == null || mimeType.trim().isEmpty())
                                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : mimeType;
                        saveToDownloads(safeName, mime, data);
                        showSavedDialog();
                    } catch (Exception e) {
                        toastOnUi("导出失败：" + e.getMessage());
                    }
                }
            });
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
        super.onDestroy();
    }
}
