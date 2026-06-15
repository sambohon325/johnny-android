package com.kidskiosk.aac;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private WebView kioskView;
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
        hideSystemUI();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);
        kioskView = findViewById(R.id.kioskView);
        setupWebView();
        kioskView.loadUrl("file:///android_asset/kiosk.html");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = kioskView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        kioskView.setWebChromeClient(new WebChromeClient());
        kioskView.setOnLongClickListener(v -> true);
        kioskView.setLongClickable(false);

        // JavaScript bridge
        kioskView.addJavascriptInterface(new KioskBridge(), "KioskBridge");

        kioskView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();

                // App launch scheme: app://com.package.name
                if (url.startsWith("app://")) {
                    String packageName = url.substring(6);
                    launchApp(packageName);
                    return true;
                }

                // External URLs — open in WebView (YouTube etc)
                return false;
            }
        });
    }

    // ── JavaScript Bridge ──────────────────────────────────────────────────────

    public class KioskBridge {

        @JavascriptInterface
        public boolean isPro() {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(KEY_PRO, false);
        }

        @JavascriptInterface
        public String getInstalledApps() {
            // Returns JSON array of {packageName, label} for user-installed apps
            try {
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                JSONArray result = new JSONArray();

                for (ApplicationInfo app : apps) {
                    // Only include apps that have a launcher icon (user-facing apps)
                    if (pm.getLaunchIntentForPackage(app.packageName) != null
                            && !app.packageName.equals(getPackageName())) {
                        JSONObject obj = new JSONObject();
                        obj.put("packageName", app.packageName);
                        obj.put("label", pm.getApplicationLabel(app).toString());
                        result.put(obj);
                    }
                }
                return result.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public void launchAppFromJs(String packageName) {
            launchApp(packageName);
        }

        @JavascriptInterface
        public void openDiscord() {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://discord.com/channels/1516172867119612014/1516172869837787178"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }

        @JavascriptInterface
        public void unlockPro() {
            // TODO: Replace with real Google Play Billing check
            // For now this is a placeholder — real IAP goes here
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_PRO, true)
                    .apply();
            runOnUiThread(() ->
                kioskView.evaluateJavascript("onProUnlocked()", null));
        }
    }

    // ── App launching ──────────────────────────────────────────────────────────

    private void launchApp(String packageName) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(packageName);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            // App not installed — open Play Store
            try {
                Intent store = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=" + packageName));
                store.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(store);
            } catch (Exception e) {
                Intent web = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + packageName));
                web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(web);
            }
        }
    }

    // ── System UI ──────────────────────────────────────────────────────────────

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (kioskView != null) kioskView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (kioskView != null) kioskView.onPause();
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }
}
