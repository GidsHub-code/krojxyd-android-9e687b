package signalme.pl;


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
    // Output URIs for photos/videos captured with the camera from the upload chooser
    private Uri pendingCameraPhotoUri;
    private Uri pendingCameraVideoUri;
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
        cleanOldUploads();


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
                pendingCameraPhotoUri = null;
                pendingCameraVideoUri = null;
                try {
                    // Honour what the page asked for (<input accept="...">); default to photos + videos.
                    java.util.ArrayList<String> types = new java.util.ArrayList<>();
                    String[] accept = params.getAcceptTypes();
                    if (accept != null) {
                        for (String a : accept) {
                            if (a == null) continue;
                            for (String part : a.split(",")) {
                                part = part.trim();
                                // Only real MIME types; extensions like ".jpg" can't go in EXTRA_MIME_TYPES
                                if (part.contains("/")) types.add(part);
                            }
                        }
                    }
                    if (types.isEmpty()) {
                        types.add("image/*");
                        types.add("video/*");
                    }
                    boolean multiple = params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                    showMediaSourceDialog(types, multiple);
                    return true;
                } catch (Exception e) {
                    android.widget.Toast.makeText(MainActivity.this, "Could not open picker: " + e, android.widget.Toast.LENGTH_LONG).show();
                    cancelFileChooser();
                    return true;
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

    private Intent buildCameraIntent(String action, String suffix, boolean isPhoto) {
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "uploads");
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = java.io.File.createTempFile("capture_", suffix, dir);
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(action);
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (i.resolveActivity(getPackageManager()) == null) return null;
            if (isPhoto) pendingCameraPhotoUri = uri; else pendingCameraVideoUri = uri;
            return i;
        } catch (Exception e) {
            return null;
        }
    }

    private void cancelFileChooser() {
        ValueCallback<Uri[]> cb = fileChooserCallback;
        fileChooserCallback = null;
        pendingCameraPhotoUri = null;
        pendingCameraVideoUri = null;
        if (cb != null) cb.onReceiveValue(null);
    }

    private static boolean typesInclude(java.util.List<String> types, String prefix) {
        for (String t : types) {
            if (t.startsWith(prefix) || t.equals("*/*")) return true;
        }
        return false;
    }

    /**
     * Instead of the system app-chooser (which behaves differently on every phone brand), show our own
     * short list of sources. Each one reaches the files by a different route, so if one misbehaves on a
     * given phone another still works.
     */
    private void showMediaSourceDialog(final java.util.List<String> types, final boolean multiple) {
        final boolean wantsImage = typesInclude(types, "image/");
        final boolean wantsVideo = typesInclude(types, "video/");
        final boolean cameraOk = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;

        final java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        final java.util.ArrayList<Runnable> actions = new java.util.ArrayList<>();

        if (cameraOk && wantsImage) {
            labels.add("Take a photo");
            actions.add(() -> launchPicker(buildCameraIntent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE, ".jpg", true)));
        }
        if (cameraOk && wantsVideo) {
            labels.add("Record a video");
            actions.add(() -> launchPicker(buildCameraIntent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE, ".mp4", false)));
        }
        labels.add(wantsImage && wantsVideo ? "Choose photos / videos from gallery" : (wantsVideo ? "Choose videos from gallery" : "Choose photos from gallery"));
        actions.add(() -> launchPicker(buildGalleryIntent(types, multiple)));
        labels.add("Browse files");
        actions.add(() -> launchPicker(buildDocumentsIntent(types, multiple)));

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add photos or videos")
            .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
            .setOnCancelListener(dialog -> cancelFileChooser())
            .show();
    }

    private Intent buildGalleryIntent(java.util.List<String> types, boolean multiple) {
        boolean img = typesInclude(types, "image/");
        boolean vid = typesInclude(types, "video/");
        if (Build.VERSION.SDK_INT >= 33) {
            // System Photo Picker: no storage permission needed, works the same on every phone.
            Intent i = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
            if (img && !vid) i.setType("image/*");
            else if (vid && !img) i.setType("video/*");
            if (multiple) {
                int max = Math.min(android.provider.MediaStore.getPickImagesMaxLimit(), 12);
                if (max > 1) i.putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, max);
            }
            return i;
        }
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        if (img && !vid) i.setType("image/*");
        else if (vid && !img) i.setType("video/*");
        else {
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "image/*", "video/*" });
        }
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return i;
    }

    private Intent buildDocumentsIntent(java.util.List<String> types, boolean multiple) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return i;
    }

    private void launchPicker(Intent intent) {
        if (intent == null) {
            android.widget.Toast.makeText(this, "This option is not available on this phone", android.widget.Toast.LENGTH_LONG).show();
            cancelFileChooser();
            return;
        }
        try {
            fileChooserLauncher.launch(intent);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Could not open picker: " + e.getClass().getSimpleName(), android.widget.Toast.LENGTH_LONG).show();
            cancelFileChooser();
        }
    }

    /** Collect every Uri a picker may have returned: ClipData, data, or EXTRA_STREAM. */
    private java.util.List<Uri> extractUris(Intent data) {
        java.util.LinkedHashSet<Uri> set = new java.util.LinkedHashSet<>();
        if (data == null) return new java.util.ArrayList<>();
        android.content.ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u != null) set.add(u);
            }
        }
        if (data.getData() != null) set.add(data.getData());
        try {
            Object one = data.getExtras() != null ? data.getExtras().get(Intent.EXTRA_STREAM) : null;
            if (one instanceof Uri) set.add((Uri) one);
            else if (one instanceof java.util.List) {
                for (Object o : (java.util.List<?>) one) if (o instanceof Uri) set.add((Uri) o);
            }
        } catch (Throwable ignored) {}
        return new java.util.ArrayList<>(set);
    }

    private void registerFileChooser() {
        fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                final ValueCallback<Uri[]> cb = fileChooserCallback;
                final Uri photoOut = pendingCameraPhotoUri;
                final Uri videoOut = pendingCameraVideoUri;
                fileChooserCallback = null;
                pendingCameraPhotoUri = null;
                pendingCameraVideoUri = null;
                if (cb == null) {
                    // The app was restarted by Android while the picker was open, so the page's request is gone.
                    if (result.getResultCode() == RESULT_OK) {
                        android.widget.Toast.makeText(this, "Please tap Add photos / Add videos again", android.widget.Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                if (result.getResultCode() != RESULT_OK) {
                    cb.onReceiveValue(null);
                    return;
                }

                Intent data = result.getData();
                java.util.List<Uri> list = extractUris(data);
                if (list.isEmpty()) {
                    // Camera apps write to our EXTRA_OUTPUT uri and often return no data
                    if (videoOut != null && fileHasContent(videoOut)) list.add(videoOut);
                    else if (photoOut != null && fileHasContent(photoOut)) list.add(photoOut);
                }
                if (list.isEmpty()) {
                    android.widget.Toast.makeText(this, "The picker did not return any file. Please try another option.", android.widget.Toast.LENGTH_LONG).show();
                    cb.onReceiveValue(null);
                    return;
                }

                // Gallery/Files picks are temporary content:// links owned by another app. The page
                // reads them later (when the agent submits the listing) and by then access can be gone,
                // so the upload silently fails. Copy each pick into our own cache first (off the UI
                // thread - videos can be large) and hand the WebView our own FileProvider URIs.
                final Uri[] picked = list.toArray(new Uri[0]);
                boolean needsCopy = false;
                for (Uri u : picked) if (!isOwnUri(u)) { needsCopy = true; break; }
                if (needsCopy) {
                    android.widget.Toast.makeText(this, "Preparing files...", android.widget.Toast.LENGTH_SHORT).show();
                }
                new Thread(() -> {
                    final Uri[] out = new Uri[picked.length];
                    int failed = 0;
                    for (int i = 0; i < picked.length; i++) {
                        out[i] = copyToAppCache(picked[i]);
                        if (!isOwnUri(out[i])) failed++;
                    }
                    final int notCached = failed;
                    runOnUiThread(() -> {
                        String msg = "Added " + out.length + (out.length == 1 ? " file" : " files");
                        if (notCached > 0) msg += " (" + notCached + " could not be copied, sent directly)";
                        android.widget.Toast.makeText(MainActivity.this, msg, android.widget.Toast.LENGTH_SHORT).show();
                        cb.onReceiveValue(out);
                    });
                }).start();
            }
        );
    }

    private boolean isOwnUri(Uri u) {
        return u != null && (getPackageName() + ".fileprovider").equals(u.getAuthority());
    }

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, new String[] { android.provider.OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return null;
    }

    /** Copies a picked content:// file into cache/uploads and returns a FileProvider URI for it (or the original on any failure). */
    private Uri copyToAppCache(Uri src) {
        if (src == null || isOwnUri(src) || !"content".equals(src.getScheme())) return src;
        java.io.File out = null;
        try {
            String mime = getContentResolver().getType(src);
            String name = queryDisplayName(src);
            if (name == null || name.trim().isEmpty()) name = "upload";
            name = name.replaceAll("[^A-Za-z0-9._-]", "_");
            if (name.length() > 80) name = name.substring(name.length() - 80);
            if (name.indexOf('.') < 0 && mime != null) {
                String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                if (ext != null) name = name + "." + ext;
            }
            java.io.File dir = new java.io.File(getCacheDir(), "uploads/" + System.nanoTime());
            if (!dir.mkdirs()) return src;
            out = new java.io.File(dir, name);
            try (java.io.InputStream in = getContentResolver().openInputStream(src);
                 java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                if (in == null) { out.delete(); return src; }
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            out = shrinkImageIfNeeded(out, mime);
            return androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", out);
        } catch (Throwable t) {
            if (out != null) out.delete();
            return src;
        }
    }

    /**
     * The listing form rejects photos over 8MB, and modern phone cameras (50MP+) routinely
     * produce 5-15MB JPEGs. Big photos are also slow to upload on mobile data. So gallery photos
     * larger than ~2MB or wider than 2560px are re-encoded as JPEG (max 2560px, quality 85,
     * EXIF rotation applied). Any failure returns the untouched original file.
     */
    private java.io.File shrinkImageIfNeeded(java.io.File f, String mime) {
        try {
            String lower = f.getName().toLowerCase(java.util.Locale.ROOT);
            boolean isImage = (mime != null && mime.startsWith("image/"))
                || lower.matches(".*\\.(jpe?g|png|webp|heic|heif|bmp)$");
            if (!isImage || (mime != null && mime.contains("gif")) || lower.endsWith(".gif")) return f;

            final int MAX_SIDE = 2560;
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return f;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            if (longest <= MAX_SIDE && f.length() <= 2L * 1024 * 1024) return f;

            int sample = 1;
            while (longest / (sample * 2) >= MAX_SIDE) sample *= 2;
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            if (bmp == null) return f;

            int degrees = 0;
            try {
                android.media.ExifInterface exif = new android.media.ExifInterface(f.getAbsolutePath());
                int o = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
                if (o == android.media.ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
                else if (o == android.media.ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
                else if (o == android.media.ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
            } catch (Throwable ignored) {}

            float scale = Math.min(1f, (float) MAX_SIDE / Math.max(bmp.getWidth(), bmp.getHeight()));
            android.graphics.Bitmap work = bmp;
            if (scale < 1f || degrees != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                if (scale < 1f) m.postScale(scale, scale);
                if (degrees != 0) m.postRotate(degrees);
                work = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (work != bmp) bmp.recycle();
            }
            if (work.hasAlpha()) {
                // JPEG has no transparency - flatten onto white instead of black
                android.graphics.Bitmap flat = android.graphics.Bitmap.createBitmap(work.getWidth(), work.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(flat);
                c.drawColor(android.graphics.Color.WHITE);
                c.drawBitmap(work, 0, 0, null);
                work.recycle();
                work = flat;
            }

            String base = f.getName();
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            java.io.File tmp = new java.io.File(f.getParentFile(), base + ".tmp");
            try (java.io.OutputStream os = new java.io.FileOutputStream(tmp)) {
                work.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, os);
            }
            work.recycle();
            if (tmp.length() == 0) { tmp.delete(); return f; }
            java.io.File jpg = new java.io.File(f.getParentFile(), base + ".jpg");
            if (!f.getAbsolutePath().equals(jpg.getAbsolutePath())) f.delete();
            else jpg.delete();
            if (!tmp.renameTo(jpg)) return f.exists() ? f : tmp;
            return jpg;
        } catch (Throwable t) {
            return f;
        }
    }

    /** Delete picked/captured upload copies older than a day. */
    private void cleanOldUploads() {
        new Thread(() -> {
            try {
                deleteOld(new java.io.File(getCacheDir(), "uploads"), System.currentTimeMillis() - 24L * 60 * 60 * 1000);
            } catch (Throwable ignored) {}
        }).start();
    }

    private void deleteOld(java.io.File f, long cutoff) {
        if (f == null || !f.exists()) return;
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) deleteOld(k, cutoff);
        if (!f.getName().equals("uploads") && f.lastModified() < cutoff) f.delete();
    }

    private boolean fileHasContent(Uri uri) {
        try (android.content.res.AssetFileDescriptor fd = getContentResolver().openAssetFileDescriptor(uri, "r")) {
            return fd != null && fd.getLength() > 0;
        } catch (Exception e) {
            return false;
        }
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
