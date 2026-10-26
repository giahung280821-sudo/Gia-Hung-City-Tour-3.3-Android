
package vn.giahung.citytour;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.GeolocationPermissions;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

public class MainActivity extends Activity {
    WebView web;
    ValueCallback<Uri[]> fileCallback;
    static final int PICK_FILE = 100;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        web = new WebView(this);
        setContentView(web);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        web.setWebViewClient(new WebViewClient());

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin,
                    GeolocationPermissions.Callback callback) {
                if (checkSelfPermission(
                        Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                } else {
                    callback.invoke(origin, false, false);
                }
            }

            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                fileCallback = callback;
                try {
                    startActivityForResult(
                        params.createIntent(), PICK_FILE);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
            }
        });

        if (checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            }, 10);
        }

        web.loadUrl(getString(R.string.site_url));
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_FILE && fileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams
                    .parseResult(resultCode, data);
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
