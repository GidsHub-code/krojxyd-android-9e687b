package co.median.android.krojxyd;


import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private SwipeRefreshLayout refreshLayout;
    // admob disabled
    // rewarded ad disabled
    
    // native ad disabled
    private int downloadNotifId = 9000;
    private ValueCallback<Uri[]> fileChooserCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private PermissionRequest pendingWebPermissionRequest;

    // Pending download details (queued while rewarded ad is shown)
    private String pendingDlUrl;
    private String pendingDlUserAgent;
    private String pendingDlContentDisposition;
    private String pendingDlMimeType;
    private long pendingDlContentLength;
    private String pendingBlobUrl;
    private String pendingBlobMime;
    private String pendingBlobDisposition;

    private static final String START_URL = "https://signalmep2-abaad358.nnadigideon20.workers.dev/";
    private static final String HOST = "signalmep2-abaad358.nnadigideon20.workers.dev";
    private static final int REQ_PERMISSIONS = 4242;
    
    private static final String DOWNLOAD_CHANNEL_ID = "git2app_downloads";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Swap from splash theme back to the normal app theme before drawing the WebView.
        setTheme(R.style.AppTheme);
        super.onCreate(savedInstanceState);
        // Force status bar and navigation bar to solid black on every generated app.
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);
        // Use light (white) icons on the black bars.
        try {
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        } catch (Throwable ignored) {}
        setContentView(R.layout.activity_main);
        refreshLayout = findViewById(R.id.refresh);
        webView = findViewById(R.id.webview);
        // Only trigger pull-to-refresh when the WebView is actually at the top,
        // so normal scrolling never gets hijacked into a reload.
        refreshLayout.setOnRefreshListener(() -> webView.reload());
        webView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            refreshLayout.setEnabled(webView.getScrollY() == 0);
        });

        createNotificationChannel();
        createDownloadNotificationChannel();
        requestRuntimePermissions();
        registerFileChooser();


        // JS bridge so blob:/data: downloads can be handed to native code
        webView.addJavascriptInterface(new Git2AppDownloadBridge(), "Git2AppDownload");

        // Native handler for any download the WebView triggers (content-disposition, <a download>, etc.)
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url == null) return;
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                queueBlobDownloadWithReward(url, mimetype, contentDisposition);
                return;
            }
            queueDownloadWithReward(url, userAgent, contentDisposition, mimetype, contentLength);
        });

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " SignalMeProApp/2.2");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    pendingWebPermissionRequest = request;
                    request.grant(request.getResources());
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback, FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = filePathCallback;
                Intent intent = params.createIntent();
                // Force uploads to image/*, video/* only (configured in Git2App)
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "image/*", "video/*" });
                try {
                    fileChooserLauncher.launch(intent);
                    return true;
                } catch (Exception e) {
                    fileChooserCallback = null;
                    return false;
                }
            }

        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri uri = req.getUrl();
                String scheme = uri.getScheme();
                if ("tel".equals(scheme) || "mailto".equals(scheme) || "sms".equals(scheme) || "geo".equals(scheme) || "intent".equals(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                    return true;
                }
                String host = uri.getHost();
                if (host != null && (host.equals(HOST) || host.endsWith("." + HOST))) {
                    return false;
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                return true;
            }
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) { }
            @Override
            public void onPageFinished(WebView view, String url) {
                if (refreshLayout != null) refreshLayout.setRefreshing(false);
                // Force a mobile-friendly viewport; don't block scroll or overscroll.
                String viewportJs = "(function(){try{"
                    + "var m=document.querySelector('meta[name=viewport]');"
                    + "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);} "
                    + "m.setAttribute('content','width=device-width,initial-scale=1,maximum-scale=5,viewport-fit=cover');"
                    + "var s=document.getElementById('__git2app_fix');"
                    + "if(!s){s=document.createElement('style');s.id='__git2app_fix';"
                    + "s.innerHTML='html,body{-webkit-text-size-adjust:100%!important;}img,video,iframe{max-width:100%!important;height:auto!important;}';"
                    + "document.head.appendChild(s);} "
                    + "}catch(e){}})();";
                view.evaluateJavascript(viewportJs, null);
                String saved = getSharedPreferences("fcm", MODE_PRIVATE).getString("token", null);
                if (saved != null) {
                    String js = "window.__FCM_TOKEN__ = '" + saved + "';"
                        + "if(window.onFcmToken) window.onFcmToken('" + saved + "');";
                    view.evaluateJavascript(js, null);
                }
            }
        });

        // Listen for new FCM tokens and push them into the WebView live
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
            .registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context ctx, android.content.Intent intent) {
                    String token = intent.getStringExtra("token");
                    if (token != null && webView != null) {
                        String js = "window.__FCM_TOKEN__ = '" + token + "';"
                            + "if(window.onFcmToken) window.onFcmToken('" + token + "');";
                        runOnUiThread(() -> webView.evaluateJavascript(js, null));
                    }
                }
            }, new android.content.IntentFilter("FCM_TOKEN"));

        // pull-to-refresh disabled by request
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            String initialUrl = extractSafeUrl(getIntent());
            webView.loadUrl(initialUrl != null ? initialUrl : START_URL);
        }
    }

    /**
     * Cross-App Scripting hardening: validate any URL coming from an Intent
     * before loading it into the WebView. Only allow http(s) URLs whose host
     * matches the app's own origin (HOST or a subdomain of it). Rejects
     * javascript:, data:, file:, content:, and any third-party origin.
     */
    private String extractSafeUrl(Intent intent) {
        if (intent == null) return null;
        String candidate = intent.getStringExtra("open_url");
        if (candidate == null || candidate.isEmpty()) {
            Uri data = intent.getData();
            if (data != null) candidate = data.toString();
        }
        if (candidate == null || candidate.isEmpty()) return null;
        try {
            Uri uri = Uri.parse(candidate);
            String scheme = uri.getScheme();
            if (scheme == null) return null;
            scheme = scheme.toLowerCase(java.util.Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
            String host = uri.getHost();
            if (host == null) return null;
            host = host.toLowerCase(java.util.Locale.ROOT);
            String allowed = HOST.toLowerCase(java.util.Locale.ROOT);
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return uri.toString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void registerFileChooser() {
        fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (fileChooserCallback == null) return;
                Uri[] uris = WebChromeClient.FileChooserParams.parseResult(result.getResultCode(), result.getData());
                fileChooserCallback.onReceiveValue(uris);
                fileChooserCallback = null;
            }
        );
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                getString(R.string.default_notification_channel_id),
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Push notifications");
            channel.enableLights(true);
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 250, 250, 250});
            channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void createDownloadNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                DOWNLOAD_CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("File download progress and completion");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private int nextDownloadNotifId() { return ++downloadNotifId; }

    private void notifyDownloadProgress(int notifId, String fileName) {
        try {
            androidx.core.app.NotificationCompat.Builder b = new androidx.core.app.NotificationCompat.Builder(this, DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading " + fileName)
                .setContentText("Saving to Downloads…")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true);
            NotificationManager nm = (NotificationManager) getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(notifId, b.build());
        } catch (Throwable ignored) {}
    }

    private void notifyDownloadComplete(int notifId, String fileName, boolean success, String errMsg) {
        try {
            androidx.core.app.NotificationCompat.Builder b = new androidx.core.app.NotificationCompat.Builder(this, DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(success ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error)
                .setContentTitle(success ? "Download complete" : "Download failed")
                .setContentText(success ? (fileName + " saved to Downloads") : (fileName + ": " + (errMsg == null ? "error" : errMsg)))
                .setAutoCancel(true)
                .setOngoing(false)
                .setProgress(0, 0, false);
            NotificationManager nm = (NotificationManager) getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(notifId, b.build());
        } catch (Throwable ignored) {}
    }

    private void requestRuntimePermissions() {
        java.util.ArrayList<String> needed = new java.util.ArrayList<>();
        String[] candidates;
        if (Build.VERSION.SDK_INT >= 33) {
            candidates = new String[] {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            };

        } else {
            candidates = new String[] {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }
        for (String p : candidates) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) needed.add(p);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    // ============================================================
    //  Downloads (with optional AdMob rewarded-ad gating)
    // ============================================================

    private void queueDownloadWithReward(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
        pendingDlUrl = url;
        pendingDlUserAgent = userAgent;
        pendingDlContentDisposition = contentDisposition;
        pendingDlMimeType = mimetype;
        pendingDlContentLength = contentLength;
        showRewardedAdThen(this::startPendingDownload);
    }

    private void queueBlobDownloadWithReward(String url, String mimetype, String contentDisposition) {
        pendingBlobUrl = url;
        pendingBlobMime = mimetype;
        pendingBlobDisposition = contentDisposition;
        showRewardedAdThen(this::startPendingBlobDownload);
    }

    private void startPendingBlobDownload() {
        if (pendingBlobUrl == null) return;
        String url = pendingBlobUrl;
        String mime = pendingBlobMime;
        String disp = pendingBlobDisposition;
        pendingBlobUrl = null;
        pendingBlobMime = null;
        pendingBlobDisposition = null;
        fetchBlobViaJs(url, mime, disp);
    }

    private void showRewardedAdThen(final Runnable proceed) {
        proceed.run();
    }






    private void startPendingDownload() {
        if (pendingDlUrl == null) return;
        String url = pendingDlUrl;
        String userAgent = pendingDlUserAgent;
        String contentDisposition = pendingDlContentDisposition;
        String mimetype = pendingDlMimeType;
        pendingDlUrl = null;
        pendingDlUserAgent = null;
        pendingDlContentDisposition = null;
        pendingDlMimeType = null;
        pendingDlContentLength = 0;
        try {
            String fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype);
            android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(Uri.parse(url));
            if (mimetype != null) request.setMimeType(mimetype);
            request.addRequestHeader("User-Agent", userAgent != null ? userAgent : webView.getSettings().getUserAgentString());
            String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
            if (cookies != null) request.addRequestHeader("Cookie", cookies);
            request.setTitle(fileName);
            request.setDescription("Downloading " + fileName);
            request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            // System DownloadManager already posts a live progress notification and a
            // completed notification via the visibility flag above.
            request.allowScanningByMediaScanner();
            request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName);
            android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                android.widget.Toast.makeText(this, "Downloading " + fileName, android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "Download failed: " + t.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Blob/data URLs can't be read by DownloadManager. Fetch them in the WebView,
     * convert to base64, and pass back through the JS bridge to write to Downloads.
     */
    private int currentBlobNotifId = 0;
    private String currentBlobFileName = "download";

    private void fetchBlobViaJs(String url, String mimetype, String contentDisposition) {
        String safeUrl = url.replace("'", "\'");
        String safeMime = (mimetype == null ? "" : mimetype).replace("'", "\'");
        String safeDisp = (contentDisposition == null ? "" : contentDisposition).replace("'", "\'");
        currentBlobFileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype);
        currentBlobNotifId = nextDownloadNotifId();
        notifyDownloadProgress(currentBlobNotifId, currentBlobFileName);
        runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, "Downloading " + currentBlobFileName + "…", android.widget.Toast.LENGTH_SHORT).show());
        String js =
            "(function(){try{"
          + "fetch('" + safeUrl + "').then(function(r){return r.blob();}).then(function(b){"
          + "var reader=new FileReader();"
          + "reader.onloadend=function(){"
          + "var d=reader.result||'';var i=d.indexOf(',');"
          + "var base64=i>=0?d.substring(i+1):d;"
          + "Git2AppDownload.saveBase64(base64,'" + safeMime + "','" + safeDisp + "','" + safeUrl + "');"
          + "};reader.readAsDataURL(b);"
          + "}).catch(function(e){Git2AppDownload.reportError(String(e));});"
          + "}catch(e){Git2AppDownload.reportError(String(e));}})();";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    public class Git2AppDownloadBridge {
        @android.webkit.JavascriptInterface
        public void saveBase64(String base64, String mimetype, String contentDisposition, String sourceUrl) {
            try {
                byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                String fileName = android.webkit.URLUtil.guessFileName(
                    (sourceUrl == null || sourceUrl.isEmpty()) ? "download" : sourceUrl,
                    contentDisposition,
                    mimetype
                );
                String safeMime = (mimetype == null || mimetype.isEmpty()) ? "application/octet-stream" : mimetype;

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    // Scoped storage: use MediaStore on Android 10+
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(android.provider.MediaStore.Downloads.MIME_TYPE, safeMime);
                    values.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);

                    android.content.ContentResolver resolver = getContentResolver();
                    android.net.Uri uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new java.io.IOException("MediaStore insert returned null");

                    java.io.OutputStream os = resolver.openOutputStream(uri);
                    if (os == null) throw new java.io.IOException("Could not open output stream");
                    try {
                        os.write(bytes);
                        os.flush();
                    } finally {
                        os.close();
                    }

                    android.content.ContentValues done = new android.content.ContentValues();
                    done.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
                    resolver.update(uri, done, null, null);
                } else {
                    // Pre-scoped-storage: direct file write + DownloadManager registration
                    java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    java.io.File out = new java.io.File(dir, fileName);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                    fos.write(bytes);
                    fos.close();
                    try {
                        android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(android.content.Context.DOWNLOAD_SERVICE);
                        if (dm != null) {
                            dm.addCompletedDownload(fileName, fileName, true, safeMime,
                                out.getAbsolutePath(), bytes.length, true);
                        }
                    } catch (Throwable ignored) {}
                }

                final String fn = fileName;
                notifyDownloadComplete(currentBlobNotifId, fn, true, null);
                runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, "Saved " + fn + " to Downloads", android.widget.Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                final String msg = t.getMessage();
                notifyDownloadComplete(currentBlobNotifId, currentBlobFileName, false, msg);
                runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, "Download failed: " + msg, android.widget.Toast.LENGTH_LONG).show());
            }
        }

        @android.webkit.JavascriptInterface
        public void reportError(String message) {
            notifyDownloadComplete(currentBlobNotifId, currentBlobFileName, false, message);
            runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, "Download failed: " + message, android.widget.Toast.LENGTH_LONG).show());
        }
    }


    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    // admob lifecycle disabled



    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = extractSafeUrl(intent);
        if (url != null && webView != null) {
            webView.loadUrl(url);
        }
    }
}
