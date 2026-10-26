package vn.giahung.citytour;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.webkit.*;
import android.widget.Toast;
import android.webkit.ValueCallback;
import android.view.View;

public class MainActivity extends Activity {
 private WebView web;
 private ValueCallback<Uri[]> uploadCallback;
 private GeolocationPermissions.Callback geoCallback;
 private String geoOrigin;
 private static final int FILE_REQUEST = 100;
 private static final int LOCATION_REQUEST = 101;
 private String site;
 @Override public void onCreate(Bundle state) {
  super.onCreate(state);
  site=getString(R.string.site_url);
  if(!site.startsWith("https://") || site.contains("example.netlify.app")) {
   Toast.makeText(this,"Chưa cấu hình địa chỉ Netlify trong GitHub Actions",Toast.LENGTH_LONG).show();
  }
  web=new WebView(this); setContentView(web);
  WebSettings settings=web.getSettings();
  settings.setJavaScriptEnabled(true);
  settings.setDomStorageEnabled(true);
  settings.setDatabaseEnabled(true);
  settings.setGeolocationEnabled(true);
  settings.setAllowFileAccess(false);
  settings.setAllowContentAccess(true);
  settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
  web.setWebViewClient(new WebViewClient() {
   @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
    Uri uri=request.getUrl();
    if(uri.getScheme()!=null && (uri.getScheme().equals("tel") || uri.getScheme().equals("mailto"))) {
     try {startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(Exception ignored){} return true;
    }
    if(uri.getHost()!=null && Uri.parse(site).getHost()!=null && !uri.getHost().equalsIgnoreCase(Uri.parse(site).getHost())) {
     try {startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(Exception ignored){} return true;
    }
    return false;
   }
  });
  web.setWebChromeClient(new WebChromeClient() {
   @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
    if(!origin.startsWith("https://") || !origin.equals(Uri.parse(site).getScheme()+"://"+Uri.parse(site).getAuthority())) {callback.invoke(origin,false,false);return;}
    if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED) callback.invoke(origin,true,false);
    else {geoCallback=callback;geoOrigin=origin;requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},LOCATION_REQUEST);}
   }
   @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
    if(uploadCallback!=null) uploadCallback.onReceiveValue(null);
    uploadCallback=callback;
    Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);
    picker.addCategory(Intent.CATEGORY_OPENABLE);
    picker.setType("*/*");
    picker.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/jpeg","image/png","image/webp"});
    picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,params.getMode()==FileChooserParams.MODE_OPEN_MULTIPLE);
    try {
     startActivityForResult(picker,FILE_REQUEST);
     return true;
    } catch(Exception first) {
     try {
      Intent fallback=new Intent(Intent.ACTION_GET_CONTENT);
      fallback.addCategory(Intent.CATEGORY_OPENABLE);
      fallback.setType("image/*");
      startActivityForResult(Intent.createChooser(fallback,"Chọn ảnh nền"),FILE_REQUEST);
      return true;
     } catch(Exception second) {
      uploadCallback.onReceiveValue(null);
      uploadCallback=null;
      Toast.makeText(MainActivity.this,"Không mở được thư viện ảnh",Toast.LENGTH_LONG).show();
      return true;
     }
    }
   }
  });
  web.loadUrl(site);
 }
 @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {
  super.onRequestPermissionsResult(requestCode,permissions,results);
  if(requestCode==LOCATION_REQUEST && geoCallback!=null){
   boolean granted=false;for(int r:results)if(r==PackageManager.PERMISSION_GRANTED)granted=true;
   geoCallback.invoke(geoOrigin,granted,false);geoCallback=null;
  }
 }
 @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
  super.onActivityResult(requestCode,resultCode,data);
  if(requestCode==FILE_REQUEST && uploadCallback!=null){
   Uri[] result=null;
   if(resultCode==RESULT_OK && data!=null){
    if(data.getClipData()!=null){
     int count=data.getClipData().getItemCount();
     result=new Uri[count];
     for(int i=0;i<count;i++)result[i]=data.getClipData().getItemAt(i).getUri();
    } else if(data.getData()!=null)result=new Uri[]{data.getData()};
   }
   uploadCallback.onReceiveValue(result);
   uploadCallback=null;
  }
 }
 @Override public void onBackPressed(){if(web.canGoBack())web.goBack();else super.onBackPressed();}
 @Override protected void onDestroy(){if(uploadCallback!=null){uploadCallback.onReceiveValue(null);uploadCallback=null;}if(web!=null)web.destroy();super.onDestroy();}
}
