package com.example;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.ServiceWorkerController;
import android.webkit.ServiceWorkerClient;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebResourceResponse;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {
    private static final String APP_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final int MANUAL_IMAGE_UPLOAD_REQUEST = 1001;
    private static final int WEB_FILE_CHOOSER_REQUEST = 1002;
    private static final int AUDIO_PERMISSION_REQUEST = 1003;

    private static int blobServerPort;

    private WebView webView;
    private TopProgressBar progressBar;
    private boolean isDarkMode;
    private LocalHttpServer localHttpServer;

    /** 每次启动随机生成，全程不变，防止其它 APP 乱下载文件 */
    private String sessionToken;

    // 边缘滑动返回
    private static final float EDGE_SWIPE_MIN_X = 80;
    private static final float EDGE_SWIPE_MAX_Y = 120;
    private static final float EDGE_SWIPE_MIN_V = 200;

    private String pendingUploadCallbackId;
    private Uri pendingCameraImageUri;
    private ValueCallback<Uri[]> pendingFileChooserCallback;
    private String pendingAudioPermissionCallbackId;
    private long backPressedTime = 0L;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 沉浸式全屏 — 必须在 setContentView 之后，DecorView 挂上 Window 才能拿到 InsetsController
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController insetsController = getWindow().getInsetsController();
        if (insetsController != null) {
            insetsController.hide(WindowInsets.Type.navigationBars());
            insetsController.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);

        // 检测夜间模式 & 应用颜色
        isDarkMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        applyThemeColors();

        // IME 适配 — 键盘弹出时把 WebView 往上顶
        FrameLayout root = findViewById(R.id.rootLayout);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int statusBarTop = insets.getInsets(WindowInsets.Type.statusBars()).top;
            int imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
            root.setPadding(0, statusBarTop, 0, imeBottom);
            return WindowInsets.CONSUMED;
        });

        clearUploadCache();
        startLocalHttpServer();
        setupWebView();
        setupEdgeSwipe();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setAllowFileAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);

        // 夜间模式：WebView 自动暗色渲染 + 告知网页
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.setAlgorithmicDarkeningAllowed(true);
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(false);
        cookieManager.setAcceptThirdPartyCookies(webView, false);

        webView.addJavascriptInterface(new WebViewApi(), "WebViewApi");

        var wc = new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progressBar.setProgress(0);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setProgress(100);
                progressBar.setVisibility(View.GONE);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // 内部虚拟域放行，外部 URL 跳系统浏览器
                if (url.startsWith("https://appassets.androidplatform.net/")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
                return true;
            }

            /** 拦截虚拟域 https://appassets.androidplatform.net，从 assets/ 目录读取并返回 */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"appassets.androidplatform.net".equals(uri.getHost())) {
                    return super.shouldInterceptRequest(view, request);
                }
                String path = uri.getPath();
                if (path == null) return assetNotFound();
                if (path.startsWith("/assets/")) {
                    String assetPath = path.substring("/assets/".length());
                    if (assetPath.isEmpty()) assetPath = "index.html";
                    try {
                        InputStream is = getAssets().open(assetPath);
                        String mime = guessMimeType(assetPath);
                        String charset = isTextMime(mime) ? "UTF-8" : null;
                        return new WebResourceResponse(mime, charset, is);
                    } catch (IOException e) {
                        return assetNotFound();
                    }
                }
                return assetNotFound();
            }
        };
        webView.setWebViewClient(wc);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ServiceWorkerController swController = ServiceWorkerController.getInstance();
            swController.setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                    return wc.shouldInterceptRequest(null, request);
                }
            });
        }

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (pendingFileChooserCallback != null) {
                    pendingFileChooserCallback.onReceiveValue(null);
                }
                pendingFileChooserCallback = filePathCallback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    startActivityForResult(intent, WEB_FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    pendingFileChooserCallback = null;
                    filePathCallback.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });

        webView.loadUrl(APP_URL);
    }

    public class WebViewApi {
        @JavascriptInterface
        public void downloadFile(String url, String filename) {
            runOnUiThread(() -> {
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, sanitizeFilename(filename));
                    DownloadManager downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (downloadManager != null) {
                        downloadManager.enqueue(request);
                        Toast.makeText(MainActivity.this, "开始下载 "+filename, Toast.LENGTH_SHORT).show();
                        return;
                    }
                } catch (Exception e) {
                    System.err.println("downloadFile error");
                    e.printStackTrace();
                }
                Toast.makeText(MainActivity.this, "下载失败", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void uploadImage(String callbackId) {
            runOnUiThread(() -> {
                if (pendingUploadCallbackId != null) {
                    resolveUploadImage(pendingUploadCallbackId, null);
                }
                pendingUploadCallbackId = callbackId;
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                                checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, MANUAL_IMAGE_UPLOAD_REQUEST);
                        return;
                    }
                    openCameraForUpload();
                } catch (Exception e) {
                    resolveUploadImage(callbackId, null);
                    pendingUploadCallbackId = null;
                    pendingCameraImageUri = null;
                }
            });
        }

        @JavascriptInterface
        public void setUserAgent(String ua) {runOnUiThread(() -> webView.getSettings().setUserAgentString(ua));}

        @JavascriptInterface
        public int serverPort() {return blobServerPort;}

        @JavascriptInterface
        public String getToken() {return sessionToken;}

        @JavascriptInterface
        public boolean hasAudioPermission() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
            return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void requestAudioPermission(String callbackId) {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    resolveAudioPermission(callbackId, true);
                    return;
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    resolveAudioPermission(callbackId, true);
                } else {
                    pendingAudioPermissionCallbackId = callbackId;
                    requestPermissions(
                            new String[]{Manifest.permission.RECORD_AUDIO},
                            AUDIO_PERMISSION_REQUEST);
                }
            });
        }
    }

    private void clearUploadCache() {
        File dir = new File(getCacheDir(), "webview_uploads");
        deleteRecursively(dir);
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private void openCameraForUpload() {
        try {
            File dir = new File(getCacheDir(), "webview_uploads");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Cannot create upload cache dir");
            }
            File photo = File.createTempFile("camera_", ".jpg", dir);
            pendingCameraImageUri = SimpleFileProvider.getUriForFile(MainActivity.this, photo);

            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraImageUri);
            intent.setClipData(ClipData.newRawUri("photo", pendingCameraImageUri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (intent.resolveActivity(getPackageManager()) == null) {
                throw new ActivityNotFoundException("No camera app found");
            }
            startActivityForResult(intent, MANUAL_IMAGE_UPLOAD_REQUEST);
        } catch (Exception e) {
            System.err.println("openCameraForUpload error");
            e.printStackTrace();
            String callbackId = pendingUploadCallbackId;
            pendingUploadCallbackId = null;
            pendingCameraImageUri = null;
            resolveUploadImage(callbackId, null);
            Toast.makeText(MainActivity.this, "无法打开相机", Toast.LENGTH_SHORT).show();
        }
    }

    private void startLocalHttpServer() {
        if (localHttpServer != null) return;
        sessionToken = UUID.randomUUID().toString();
        localHttpServer = new LocalHttpServer();
        localHttpServer.start();
    }

    private class LocalHttpServer extends Thread {
        private volatile boolean running = true;
        private ServerSocket serverSocket;

        private final Map<String, Uri> uploadImages = new ConcurrentHashMap<>();

        LocalHttpServer() {
            super("LocalHttpServer");
            setDaemon(true);
        }

        @Override
        public void run() {
            try (ServerSocket ss = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))) {
                blobServerPort = ss.getLocalPort();
                serverSocket = ss;
                while (running) {
                    try {
                        Socket socket = ss.accept();
                        new Thread(() -> handle(socket), "BlobSaveClient").start();
                    } catch (IOException ignored) {
                        if (!running) break;
                    }
                }
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Blob保存服务启动失败", Toast.LENGTH_SHORT).show());
            }
        }

        void shutdown() {
            running = false;
            uploadImages.clear();
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (IOException ignored) {}
        }

        String registerUploadImage(Uri uri) {
            String id = UUID.randomUUID().toString();
            uploadImages.put(id, uri);
            return "http://127.0.0.1:"+blobServerPort+"/upload/"+id+"?tk="+sessionToken;
        }

        private static String readHeader(InputStream input, byte[] buffer) throws IOException {
            int i = 0;
            int b;
            while ((b = input.read()) != -1) {
                if (b == '\n') break;
                if (b != '\r') buffer[i++] = (byte) b;
            }
            if (b == -1 && i == 0) return null;
            return new String(buffer, 0, i, StandardCharsets.ISO_8859_1);
        }

        private static void copyFixed(InputStream input, OutputStream output, long bytes, byte[] buffer) throws IOException {
            long remaining = bytes;
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read == -1) throw new IOException("Unexpected EOF");
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }

        private static void sendOptionsResponse(OutputStream output) throws IOException {
            String response =
                    "HTTP/1.1 204 No Content\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Access-Control-Allow-Methods: GET, POST, PUT, DELETE, PATCH, OPTIONS\r\n" +
                            "Access-Control-Allow-Headers: *\r\n" +
                            "Access-Control-Max-Age: 86400\r\n" +
                            "Content-Length: 0\r\n" +
                            "Connection: close\r\n" +
                            "\r\n";

            output.write(response.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        private static void sendResponse(
                OutputStream output,
                int code,
                String status,
                String body
        ) throws IOException {
            if (body == null) body = "";

            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

            String response =
                    "HTTP/1.1 " + code + " " + status + "\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                            "Access-Control-Allow-Headers: Content-Type, Content-Length\r\n" +
                            "Content-Type: text/plain; charset=utf-8\r\n" +
                            "Content-Length: " + bodyBytes.length + "\r\n" +
                            "Connection: close\r\n" +
                            "\r\n";

            output.write(response.getBytes(StandardCharsets.UTF_8));
            output.write(bodyBytes);
            output.flush();
        }

        private void sendUriResponse(OutputStream output, Uri uri) throws IOException {
            String mime = getContentResolver().getType(uri);
            if (mime == null) mime = "application/octet-stream";
            long length = getUriLength(uri);

            String response =
                    "HTTP/1.1 200 OK\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                            "Access-Control-Allow-Headers: Content-Type, Content-Length\r\n" +
                            "Content-Type: " + mime + "\r\n" +
                            (length >= 0 ? "Content-Length: " + length + "\r\n" : "") +
                            "Connection: close\r\n" +
                            "\r\n";

            output.write(response.getBytes(StandardCharsets.UTF_8));
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Cannot open upload image");
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
            output.flush();
        }

        private static String getQueryParam(String path, String key) {
            int question = path.indexOf('?');
            if (question < 0) return null;

            String query = path.substring(question + 1);
            String[] pairs = query.split("&");

            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;

                String k = pair.substring(0, eq);
                String v = pair.substring(eq + 1);

                if (key.equals(k)) {
                    return v;
                }
            }

            return null;
        }

        private static void copyChunked(
                InputStream input,
                OutputStream output,
                byte[] buffer
        ) throws IOException {
            while (true) {
                String sizeLine = readHeader(input, buffer);
                if (sizeLine == null) {
                    throw new IOException("Missing chunk size");
                }

                int semi = sizeLine.indexOf(';');
                if (semi >= 0) {
                    sizeLine = sizeLine.substring(0, semi);
                }

                long size = Long.parseLong(sizeLine.trim(), 16);

                if (size == 0) {
                    // 读取 trailer headers
                    String trailer;
                    while ((trailer = readHeader(input, buffer)) != null && trailer.length() > 0) {
                        // ignore
                    }
                    break;
                }

                copyFixed(input, output, size, buffer);

                // 读取 chunk 后面的 CRLF
                String crlf = readHeader(input, buffer);
                // 一般这里是空字符串，忽略即可
            }
        }

        private void handle(Socket socket) {
            try (Socket s = socket) {
                s.setSoTimeout(1000);

                BufferedInputStream input = new BufferedInputStream(s.getInputStream(), 8192);
                OutputStream rawOutput = s.getOutputStream();

                byte[] buffer = new byte[8192];

                String requestLine = readHeader(input, buffer);
                if (requestLine == null || requestLine.isEmpty()) {
                    sendResponse(rawOutput, 400, "Bad Request", "empty request");
                    return;
                }

                System.out.println("[Blob] RequestLine: " + requestLine);

                String[] parts = requestLine.split(" ");
                if (parts.length < 2) {
                    sendResponse(rawOutput, 400, "Bad Request", "invalid request line");
                    return;
                }

                String method = parts[0];
                String path = parts[1];

                boolean chunked = false;
                long contentLength = -1L;
                Map<String, String> reqHeaders = new LinkedHashMap<>();

                String line;
                while ((line = readHeader(input, buffer)) != null && !line.isEmpty()) {
                    //System.out.println("[Blob] Header: " + line);

                    String lower = line.toLowerCase(Locale.US);

                    if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                        chunked = true;
                    } else if (lower.startsWith("content-length:")) {
                        try {
                            contentLength = Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
                        } catch (Exception ignored) {
                        }
                    }

                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        reqHeaders.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                    }
                }

                // 处理 CORS 预检请求
                if ("OPTIONS".equalsIgnoreCase(method)) {
                    sendOptionsResponse(rawOutput);
                    return;
                }

                String reqToken = getQueryParam(path, "tk");
                if (!sessionToken.equals(reqToken)) {
                    sendResponse(rawOutput, 403, "Forbidden", "invalid token");
                    return;
                }

                if (path.startsWith("/proxy")) {
                    handleProxy(method, path, reqHeaders, chunked, contentLength, input, rawOutput, buffer);
                    return;
                }

                if ("GET".equalsIgnoreCase(method) && path.startsWith("/upload/")) {
                    String id = path.substring("/upload/".length());
                    int question = id.indexOf('?');
                    if (question >= 0) id = id.substring(0, question);
                    Uri uri = uploadImages.get(id);
                    if (uri == null) {
                        sendResponse(rawOutput, 404, "Not Found", "upload image not found");
                    } else {
                        sendUriResponse(rawOutput, uri);
                    }
                    return;
                }

                if (!"POST".equalsIgnoreCase(method)) {
                    sendResponse(rawOutput, 405, "Method Not Allowed", "Only GET /upload/{id} or POST /save allowed");
                    return;
                }

                if (!path.startsWith("/save")) {
                    sendResponse(rawOutput, 404, "Not Found", "not found");
                    return;
                }

                String filename = getQueryParam(path, "filename");
                if (filename == null || filename.trim().isEmpty()) {
                    filename = "download.bin";
                }

                filename = URLDecoder.decode(filename, "UTF-8");
                String safeName = sanitizeFilename(filename);

                System.out.println("[Blob] save filename: " + safeName);
                System.out.println("[Blob] chunked: " + chunked + ", contentLength: " + contentLength);

                Uri uri = createDownloadUri(safeName);

                try (OutputStream fileOutput = openDownloadOutput(uri)) {
                    if (chunked) {
                        copyChunked(input, fileOutput, buffer);
                    } else if (contentLength >= 0) {
                        copyFixed(input, fileOutput, contentLength, buffer);
                    } else {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            fileOutput.write(buffer, 0, read);
                        }
                    }

                    fileOutput.flush();
                }

                finishDownloadUri(uri);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "文件 " + safeName + " 已下载", Toast.LENGTH_SHORT).show());

                sendResponse(rawOutput, 201, "Created", "OK");

            } catch (Exception e) {
                e.printStackTrace();

                try {
                    OutputStream output = socket.getOutputStream();
                    sendResponse(output, 500, "Internal Server Error", e.toString());
                } catch (Exception ignored) {
                }
            }
        }

        /**
         * 代理转发：接收 WebView 的请求，转发到用户自定义 URL，把上游响应透传回 WebView。
         * 响应自动附带 CORS 头，绕过浏览器同源策略。
         */
        private void handleProxy(String method, String path, Map<String, String> reqHeaders,
                                 boolean chunked, long contentLength,
                                 InputStream input, OutputStream rawOutput, byte[] buffer) throws IOException {
            String targetUrl = getQueryParam(path, "url");
            if (targetUrl == null || targetUrl.isEmpty()) {
                sendResponse(rawOutput, 400, "Bad Request", "missing 'url' parameter");
                return;
            }
            try {
                targetUrl = URLDecoder.decode(targetUrl, "UTF-8");
            } catch (Exception e) {
                sendResponse(rawOutput, 400, "Bad Request", "invalid url encoding");
                return;
            }

            HttpURLConnection conn = null;
            boolean headersSent = false;
            try {
                URL url = new URL(targetUrl);
                String scheme = url.getProtocol();
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    sendResponse(rawOutput, 400, "Bad Request", "only http/https supported");
                    return;
                }

                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(600000);  // 10 分钟，防止无响应请求堆积
                conn.setInstanceFollowRedirects(true);

                // 转发请求头（跳过逐跳头和代理专有头）
                for (Map.Entry<String, String> entry : reqHeaders.entrySet()) {
                    String name = entry.getKey().toLowerCase(Locale.US);
                    if (name.equals("host") || name.equals("content-length") ||
                        name.equals("transfer-encoding") || name.equals("connection") ||
                        name.equals("x-requested-with")) {
                        continue;
                    }
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }

                // 转发请求体
                boolean hasBody = !method.equalsIgnoreCase("GET") && !method.equalsIgnoreCase("HEAD");
                if (hasBody) {
                    conn.setDoOutput(true);
                    if (contentLength > 0) {
                        conn.setFixedLengthStreamingMode(contentLength);
                    } else {
                        conn.setChunkedStreamingMode(8192);
                    }
                    try (OutputStream out = conn.getOutputStream()) {
                        if (chunked) {
                            copyChunked(input, out, buffer);
                        } else if (contentLength > 0) {
                            copyFixed(input, out, contentLength, buffer);
                        } else if (contentLength != 0) {
                            int read;
                            while ((read = input.read(buffer)) != -1) {
                                out.write(buffer, 0, read);
                            }
                        }
                        out.flush();
                    }
                }

                // 读取上游响应
                int status = conn.getResponseCode();
                String reason = conn.getResponseMessage();
                if (reason == null) reason = "";

                StringBuilder resp = new StringBuilder();
                resp.append("HTTP/1.1 ").append(status).append(" ").append(reason).append("\r\n");
                resp.append("Access-Control-Allow-Origin: *\r\n");
                resp.append("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, PATCH, OPTIONS\r\n");
                resp.append("Access-Control-Allow-Headers: *\r\n");
                resp.append("Access-Control-Expose-Headers: *\r\n");

                // 透传上游响应头（跳过逐跳头和 CORS 头，避免重复）
                Map<String, List<String>> respHeaders = conn.getHeaderFields();
                if (respHeaders != null) {
                    for (Map.Entry<String, List<String>> entry : respHeaders.entrySet()) {
                        String name = entry.getKey();
                        if (name == null) continue;
                        String lower = name.toLowerCase(Locale.US);
                        if (lower.startsWith("access-control-") ||
                            lower.equals("transfer-encoding") ||
                            lower.equals("connection")) {
                            continue;
                        }
                        for (String value : entry.getValue()) {
                            resp.append(name).append(": ").append(value).append("\r\n");
                        }
                    }
                }
                resp.append("Connection: close\r\n");
                resp.append("\r\n");

                rawOutput.write(resp.toString().getBytes(StandardCharsets.UTF_8));
                rawOutput.flush();
                headersSent = true;

                // 流式转发响应体
                InputStream respInput = status < 400 ? conn.getInputStream() : conn.getErrorStream();
                if (respInput != null) {
                    int read;
                    while ((read = respInput.read(buffer)) != -1) {
                        rawOutput.write(buffer, 0, read);
                    }
                }
                rawOutput.flush();

            } catch (Exception e) {
                e.printStackTrace();
                if (!headersSent) {
                    try {
                        sendResponse(rawOutput, 502, "Bad Gateway", e.toString());
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    private Uri createDownloadUri(String filename) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Cannot create download item");
            return uri;
        }
        File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), filename);
        return Uri.fromFile(file);
    }

    private OutputStream openDownloadOutput(Uri uri) throws IOException {
        if ("file".equals(uri.getScheme())) {
            File file = new File(uri.getPath());
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            return new FileOutputStream(file);
        }
        OutputStream output = getContentResolver().openOutputStream(uri);
        if (output == null) throw new IOException("Cannot open output");
        return output;
    }

    private void finishDownloadUri(Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !"file".equals(uri.getScheme())) {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
        }
    }

    private long getUriLength(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(
                uri,
                new String[]{android.provider.OpenableColumns.SIZE},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getLong(index);
                }
            }
        } catch (Exception ignored) {}
        return -1L;
    }

    private String sanitizeFilename(String filename) {
        String safe = filename == null ? "" : filename.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return safe.isEmpty() ? "download" : safe;
    }

    private void resolveUploadImage(String callbackId, String url) {
        if (callbackId == null) return;
        String js;
        if (url == null) {
            js = "window.__imageUploaded(\"" + callbackId + "\");";
        } else {
            js = "window.__imageUploaded(\"" + callbackId + "\", \"" + url + "\");";
        }
        webView.evaluateJavascript(js, null);
    }

    private void resolveAudioPermission(String callbackId, boolean granted) {
        if (callbackId == null) return;
        String js = "window.__audioPermissionResult(\"" + callbackId + "\", " + granted + ");";
        webView.evaluateJavascript(js, null);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupEdgeSwipe() {
        View edgeLeft = findViewById(R.id.edgeLeft);
        View edgeRight = findViewById(R.id.edgeRight);

        GestureDetector leftGesture = makeGesture(true);
        GestureDetector rightGesture = makeGesture(false);

        edgeLeft.setOnTouchListener((v, event) -> leftGesture.onTouchEvent(event));
        edgeRight.setOnTouchListener((v, event) -> rightGesture.onTouchEvent(event));
    }

    private GestureDetector makeGesture(boolean isLeft) {
        return new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) { return true; }
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                float dx = e2.getX() - (e1 != null ? e1.getX() : e2.getX());
                float dy = e2.getY() - (e1 != null ? e1.getY() : e2.getY());
                if (Math.abs(dx) < EDGE_SWIPE_MIN_X) return false;
                if (Math.abs(dy) > EDGE_SWIPE_MAX_Y) return false;
                if (Math.abs(vx) < EDGE_SWIPE_MIN_V) return false;
                if (dx > 0) {
                    if (isLeft) {
                        if (webView.canGoBack()) webView.goBack();
                    }
                } else {
                    if (!isLeft) {
                        if (webView.canGoForward()) webView.goForward();
                    }
                }
                return true;
            }
        });
    }

    private void applyThemeColors() {
        int bgColor = isDarkMode ? 0xFF1a1a1a : 0xFFFFFFFF;
        int fgColor = isDarkMode ? 0xFFFFFFFF : 0xFF000000;
        webView.setBackgroundColor(bgColor);
        progressBar.setBarColor(fgColor);

        // 状态栏背景色跟随
        getWindow().setStatusBarColor(bgColor);
        // 状态栏图标/文字颜色（亮底深字，暗底浅字）
        View decor = getWindow().getDecorView();
        int visibility = decor.getSystemUiVisibility();
        if (isDarkMode) {
            // 暗底白字 → 移除 LIGHT_STATUS_BAR 标志
            decor.setSystemUiVisibility(visibility & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } else {
            // 亮底黑字 → 添加 LIGHT_STATUS_BAR 标志
            decor.setSystemUiVisibility(visibility | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            long now = System.currentTimeMillis();
            if (now - backPressedTime < 2000) {
                super.onBackPressed();
            } else {
                backPressedTime = now;
                Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        webView.resumeTimers();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        webView.pauseTimers();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onStop() {
        super.onStop();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        pendingUploadCallbackId = null;
        pendingCameraImageUri = null;
        if (pendingFileChooserCallback != null) {
            pendingFileChooserCallback.onReceiveValue(null);
            pendingFileChooserCallback = null;
        }
        if (localHttpServer != null) {
            localHttpServer.shutdown();
            localHttpServer = null;
        }
        clearUploadCache();
        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent != null) {
            parent.removeView(webView);
        }
        webView.destroy();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == MANUAL_IMAGE_UPLOAD_REQUEST) {
            String callbackId = pendingUploadCallbackId;
            pendingUploadCallbackId = null;
            String url = null;

            if (resultCode == RESULT_OK && pendingCameraImageUri != null && localHttpServer != null) {
                url = localHttpServer.registerUploadImage(pendingCameraImageUri);
            }

            pendingCameraImageUri = null;
            resolveUploadImage(callbackId, url);
            return;
        }
        if (requestCode == WEB_FILE_CHOOSER_REQUEST) {
            ValueCallback<Uri[]> callback = pendingFileChooserCallback;
            pendingFileChooserCallback = null;
            if (callback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        ClipData clipData = data.getClipData();
                        results = new Uri[clipData.getItemCount()];
                        for (int i = 0; i < clipData.getItemCount(); i++) {
                            results[i] = clipData.getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }
                callback.onReceiveValue(results);
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MANUAL_IMAGE_UPLOAD_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (pendingUploadCallbackId != null) {
                    openCameraForUpload();
                }
            } else {
                String callbackId = pendingUploadCallbackId;
                pendingUploadCallbackId = null;
                pendingCameraImageUri = null;
                resolveUploadImage(callbackId, null);
            }
        }
        if (requestCode == AUDIO_PERMISSION_REQUEST) {
            String callbackId = pendingAudioPermissionCallbackId;
            pendingAudioPermissionCallbackId = null;
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            resolveAudioPermission(callbackId, granted);
        }
    }

    /** 根据文件扩展名推断 MIME 类型 */
    private static String guessMimeType(String path) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(path);
        if (ext != null) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        // MimeTypeMap 不覆盖的常见类型
        if (path.endsWith(".html") || path.endsWith(".htm")) return "text/html";
        if (path.endsWith(".js") || path.endsWith(".mjs"))  return "application/javascript";
        if (path.endsWith(".css"))   return "text/css";
        if (path.endsWith(".json"))  return "application/json";
        if (path.endsWith(".xml"))   return "application/xml";
        if (path.endsWith(".svg"))   return "image/svg+xml";
        if (path.endsWith(".png"))   return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".gif"))   return "image/gif";
        if (path.endsWith(".webp"))  return "image/webp";
        if (path.endsWith(".ico"))   return "image/x-icon";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff"))  return "font/woff";
        if (path.endsWith(".ttf"))   return "font/ttf";
        if (path.endsWith(".otf"))   return "font/otf";
        if (path.endsWith(".wasm"))  return "application/wasm";
        return "application/octet-stream";
    }

    /** 是否需要以文本编码返回（charset=UTF-8） */
    private static boolean isTextMime(String mime) {
        return mime.startsWith("text/")
            || "application/javascript".equals(mime)
            || "application/json".equals(mime)
            || "application/xml".equals(mime);
    }

    /** 统一 404 响应 */
    private static WebResourceResponse assetNotFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, null);
    }
}
