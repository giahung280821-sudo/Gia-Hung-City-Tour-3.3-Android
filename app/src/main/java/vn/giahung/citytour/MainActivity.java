package vn.giahung.citytour;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.*;

public class MainActivity extends Activity {
    private static final String TAG = "GiaHungGPS";
    private static final int FILE_REQUEST = 100;
    private static final int LOCATION_REQUEST = 101;
    private static final int TRIP_LOCATION_REQUEST = 102;
    private WebView web;
    private ValueCallback<Uri[]> uploadCallback;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;
    private String site;
    private String trustedOrigin;
    private boolean receiverRegistered = false;
    private String pendingTripAction;
    private final BroadcastReceiver gpsReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!TripService.ACTION_LOCATION.equals(intent.getAction())) return;
            sendGpsToWeb();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        site = getString(R.string.site_url).trim();
        Uri siteUri = Uri.parse(site);
        if (!"https".equalsIgnoreCase(siteUri.getScheme()) || siteUri.getHost() == null) {
            Toast.makeText(this, "Địa chỉ Netlify không hợp lệ (SITE_URL)", Toast.LENGTH_LONG).show();
            return;
        }
        trustedOrigin = normalizedOrigin(siteUri);
        web = new WebView(this);
        setContentView(web);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("tel".equalsIgnoreCase(scheme) || "mailto".equalsIgnoreCase(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                    return true;
                }
                if (!trustedOrigin.equals(normalizedOrigin(uri))) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (trustedOrigin.equals(normalizedOrigin(Uri.parse(url)))) sendGpsToWeb();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (!trustedOrigin.equals(normalizedOrigin(Uri.parse(origin)))) {
                    callback.invoke(origin, false, false);
                    return;
                }
                if (hasFineLocationPermission()) {
                    callback.invoke(origin, true, false);
                } else {
                    if (geoCallback != null) { callback.invoke(origin, false, false); return; }
                    geoCallback = callback;
                    geoOrigin = origin;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
                }
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (uploadCallback != null) uploadCallback.onReceiveValue(null);
                uploadCallback = callback;
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                picker.addCategory(Intent.CATEGORY_OPENABLE);
                picker.setType("*/*");
                picker.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/jpeg", "image/png", "image/webp"});
                picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(picker, FILE_REQUEST);
                    return true;
                } catch (Exception first) {
                    try {
                        Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                        fallback.addCategory(Intent.CATEGORY_OPENABLE);
                        fallback.setType("image/*");
                        startActivityForResult(Intent.createChooser(fallback, "Chọn ảnh nền"), FILE_REQUEST);
                        return true;
                    } catch (Exception second) {
                        uploadCallback.onReceiveValue(null);
                        uploadCallback = null;
                        Toast.makeText(MainActivity.this, "Không mở được thư viện ảnh", Toast.LENGTH_LONG).show();
                        return true;
                    }
                }
            }
        });
        web.addJavascriptInterface(new QuoteBridge(), "GiaHungAndroid");
        web.addJavascriptInterface(new NativeTripBridge(), "NativeTrip");
        web.loadUrl(site);
    }

    private boolean hasFineLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    private boolean hasLocationPermission() {
        return hasFineLocationPermission() ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    private static String normalizedOrigin(Uri uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) return "";
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        int port = uri.getPort();
        if (port == -1 || ("https".equals(scheme) && port == 443) ||
            ("http".equals(scheme) && port == 80)) return scheme + "://" + host;
        return scheme + "://" + host + ":" + port;
    }

    private JSONObject gpsSnapshot() {
        android.content.SharedPreferences p = getSharedPreferences("trip_gps_state", MODE_PRIVATE);
        JSONObject o = new JSONObject();
        try {
            double meters = Double.longBitsToDouble(p.getLong("distance", Double.doubleToLongBits(0)));
            boolean active = p.getBoolean("active", false);
            boolean paused = p.getBoolean("paused", false);
            o.put("km", meters / 1000.0);
            o.put("active", active);
            o.put("paused", paused);
            o.put("updated", p.getLong("updated", 0));
            o.put("status", !active ? "Đã kết thúc chuyến" : paused ? "Đang tạm dừng" : "Đang ghi nhận hành trình");
        } catch (Exception e) { Log.e(TAG, "GPS snapshot", e); }
        return o;
    }
    private void sendGpsToWeb() {
        if (web == null) return;
        final String payload = gpsSnapshot().toString();
        runOnUiThread(() -> {
            if (web == null || !trustedOrigin.equals(normalizedOrigin(Uri.parse(web.getUrl())))) return;
            web.evaluateJavascript("if(typeof window.onNativeGpsUpdate==='function')window.onNativeGpsUpdate(" +
                JSONObject.quote(payload) + ");", null);
        });
    }
    private void sendTripAction(String action) {
        if (!hasFineLocationPermission() &&
            (TripService.ACTION_START.equals(action) || TripService.ACTION_RESUME.equals(action))) {
            pendingTripAction = action;
            runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, TRIP_LOCATION_REQUEST));
            return;
        }
        runOnUiThread(() -> {
            try {
                Intent intent = new Intent(MainActivity.this, TripService.class);
                intent.setAction(action);
                if (TripService.ACTION_START.equals(action) || TripService.ACTION_RESUME.equals(action)) {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
                    else startService(intent);
                } else {
                    startService(intent);
                }
            } catch (Exception e) {
                Log.e(TAG, "Cannot control GPS service", e);
                Toast.makeText(MainActivity.this, "Không thể điều khiển GPS: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }
    private class NativeTripBridge {
        @JavascriptInterface public void start() { sendTripAction(TripService.ACTION_START); }
        @JavascriptInterface public void pause() { sendTripAction(TripService.ACTION_PAUSE); }
        @JavascriptInterface public void resume() { sendTripAction(TripService.ACTION_RESUME); }
        @JavascriptInterface public void stop() { sendTripAction(TripService.ACTION_STOP); }
        @JavascriptInterface public String getGps() { return gpsSnapshot().toString(); }
    }

    private class QuoteBridge {
        @JavascriptInterface public void saveOrShare(String data, boolean share) {
            try {
                if (data == null || data.length() > 17000000 || !data.startsWith("data:image/png;base64,"))
                    throw new IllegalArgumentException("Ảnh PNG không hợp lệ");
                byte[] bytes = Base64.decode(data.substring("data:image/png;base64,".length()), Base64.DEFAULT);
                if (bytes.length < 8 || bytes.length > 12000000 || bytes[0] != (byte)137 ||
                    bytes[1] != 80 || bytes[2] != 78 || bytes[3] != 71)
                    throw new IllegalArgumentException("Dữ liệu ảnh lỗi");
                String filename = "Bao-gia-Gia-Hung-" + System.currentTimeMillis() + ".png";
                if (share) {
                    File dir = new File(getCacheDir(), "shared_images");
                    if (!dir.exists() && !dir.mkdirs()) throw new Exception("Không tạo được thư mục tạm");
                    File file = new File(dir, filename);
                    try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
                    Uri uri = FileProvider.getUriForFile(MainActivity.this,
                        getPackageName() + ".fileprovider", file);
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("image/png");
                    intent.putExtra(Intent.EXTRA_STREAM, uri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    runOnUiThread(() -> {
                        try { startActivity(Intent.createChooser(intent, "Gửi ảnh báo giá")); }
                        catch (Exception e) { Toast.makeText(MainActivity.this,
                            "Không mở được ứng dụng chia sẻ", Toast.LENGTH_LONG).show(); }
                    });
                } else {
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/GiaHungCityTour");
                        values.put(MediaStore.Images.Media.IS_PENDING, 1);
                        ContentResolver resolver = getContentResolver();
                        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                        if (uri == null) throw new Exception("Không tạo được tệp ảnh");
                        try {
                            try (OutputStream out = resolver.openOutputStream(uri)) {
                                if (out == null) throw new Exception("Không ghi được ảnh");
                                out.write(bytes);
                            }
                            values.clear();
                            values.put(MediaStore.Images.Media.IS_PENDING, 0);
                            resolver.update(uri, values, null, null);
                        } catch (Exception e) { resolver.delete(uri, null, null); throw e; }
                    } else {
                        File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                        if (dir == null) throw new Exception("Không có bộ nhớ");
                        File file = new File(dir, filename);
                        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
                        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)));
                    }
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Đã lưu ảnh vào Pictures/GiaHungCityTour", Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "Lỗi ảnh: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == LOCATION_REQUEST && geoCallback != null) {
            boolean granted = hasLocationPermission();
            if (granted && !hasFineLocationPermission()) Toast.makeText(this,
                "Đang dùng vị trí gần đúng. Hãy bật Vị trí chính xác trong Cài đặt ứng dụng.",
                Toast.LENGTH_LONG).show();
            geoCallback.invoke(geoOrigin, granted, false);
            if (!granted) Toast.makeText(this, "Hãy cấp quyền Vị trí chính xác cho Gia Hưng City Tour",
                Toast.LENGTH_LONG).show();
            geoCallback = null;
            geoOrigin = null;
        }
        if (requestCode == TRIP_LOCATION_REQUEST) {
            String action = pendingTripAction;
            pendingTripAction = null;
            if (hasFineLocationPermission() && action != null) sendTripAction(action);
            else Toast.makeText(this, "Cần cấp Vị trí chính xác để chạy GPS nền", Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_REQUEST && uploadCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    result = new Uri[count];
                    for (int i = 0; i < count; i++) result[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) result = new Uri[]{data.getData()};
            }
            uploadCallback.onReceiveValue(result);
            uploadCallback = null;
        }
    }
    @Override protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(TripService.ACTION_LOCATION);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(gpsReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(gpsReceiver, filter);
            receiverRegistered = true;
        }
    }
    @Override protected void onStop() {
        if (receiverRegistered) { unregisterReceiver(gpsReceiver); receiverRegistered = false; }
        super.onStop();
    }
    @Override protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (web != null) { web.onResume(); sendGpsToWeb(); }
    }
    @Override protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }
    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (uploadCallback != null) { uploadCallback.onReceiveValue(null); uploadCallback = null; }
        if (geoCallback != null) { geoCallback.invoke(geoOrigin, false, false); geoCallback = null; }
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
