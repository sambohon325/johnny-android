package com.kidskiosk.aac;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private WebView setupView;
    private static final int OVERLAY_PERMISSION_REQUEST = 1001;
    private static final String PREFS_NAME = "kids_kiosk";
    private static final String KEY_PRO = "is_pro_unlocked";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);
        setupView = findViewById(R.id.setupView);
        setupWebView();
        setupView.loadUrl("file:///android_asset/setup.html");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = setupView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        setupView.setWebChromeClient(new WebChromeClient());
        setupView.addJavascriptInterface(new SetupBridge(), "SetupBridge");
        setupView.setWebViewClient(new WebViewClient());
    }

    public class SetupBridge {

        @JavascriptInterface
        public boolean isPro() {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(KEY_PRO, false);
        }

        @JavascriptInterface
        public String getInstalledApps() {
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                java.util.List<android.content.pm.ApplicationInfo> apps =
                    pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA);
                org.json.JSONArray result = new org.json.JSONArray();
                for (android.content.pm.ApplicationInfo app : apps) {
                    if (pm.getLaunchIntentForPackage(app.packageName) != null
                            && !app.packageName.equals(getPackageName())) {
                        org.json.JSONObject obj = new org.json.JSONObject();
                        obj.put("packageName", app.packageName);
                        obj.put("label", pm.getApplicationLabel(app).toString());
                        result.put(obj);
                    }
                }
                return result.toString();
            } catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface
        public void unlockPro() {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(KEY_PRO, true).apply();
            runOnUiThread(() ->
                setupView.evaluateJavascript("onProUnlocked()", null));
        }

        @JavascriptInterface
        public void launchKiosk() {
            // Check overlay permission first
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !Settings.canDrawOverlays(MainActivity.this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
            } else {
                startKioskOverlay();
            }
        }

        @JavascriptInterface
        public void openScreenPinning() {
            // Open Android security settings where screen pinning lives
            Intent intent = new Intent(Settings.ACTION_SECURITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }

        @JavascriptInterface
        public String getSlotsJson() {
            // Pass saved slots to setup screen
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString("slots_json", "null");
        }
    }

    private void startKioskOverlay() {
        Intent service = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        // Move app to background so the overlay shows on top of home screen
        moveTaskToBack(true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && Settings.canDrawOverlays(this)) {
                startKioskOverlay();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (setupView != null) setupView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (setupView != null) setupView.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyDown(keyCode, event);
    }
}
