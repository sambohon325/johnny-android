package com.johnny6.riotdash;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity
        implements HiveConnection.ProvisioningListener {

    private WebView dashboardView;
    private WebView youtubeView;
    private HiveConnection hiveConnection;
    private DeviceIdentity identity;

    private static final String DASHBOARD_URL = "https://johnny6.sambohon.digital";
    private static final int PERMISSION_REQUEST_CODE = 100;

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

        // ── Identity & registration ────────────────────────────────────────────
        identity = new DeviceIdentity(this);
        identity.registerWithHive(getAppVersion());

        // ── Hive connection ────────────────────────────────────────────────────
        requestPermissionsIfNeeded();
        hiveConnection = new HiveConnection(this, identity, this);
        hiveConnection.connect();

        // ── Check for app updates ──────────────────────────────────────────────
        new UpdateChecker(this).checkForUpdates();

        // ── Show correct screen ────────────────────────────────────────────────
        if (identity.isProvisioned()) {
            showKioskScreen();
        } else {
            showWaitingScreen();
        }
    }

    // ── Screens ────────────────────────────────────────────────────────────────

    private void showWaitingScreen() {
        setContentView(R.layout.activity_waiting);

        TextView deviceIdText = findViewById(R.id.deviceIdText);
        if (deviceIdText != null) {
            deviceIdText.setText("Device ID: " + identity.getDeviceId());
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showKioskScreen() {
        setContentView(R.layout.activity_main);

        dashboardView = findViewById(R.id.dashboardView);
        youtubeView   = findViewById(R.id.youtubeView);

        setupWebView(dashboardView);
        setupWebView(youtubeView);

        dashboardView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view,
                                                     WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith(DASHBOARD_URL)) return false;
                youtubeView.loadUrl(url);
                return true;
            }
        });

        youtubeView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view,
                                                     WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.contains("youtube.com") || url.contains("youtu.be")
                        || url.contains("googlevideo.com")
                        || url.contains("googleapis.com")
                        || url.contains("accounts.google.com")
                        || url.contains("google.com/accounts")) {
                    return false;
                }
                return true;
            }
        });

        dashboardView.loadUrl(DASHBOARD_URL);
        youtubeView.loadUrl("https://www.youtube.com");
    }

    // ── ProvisioningListener ───────────────────────────────────────────────────

    @Override
    public void onProvisioned(String profileId, String role,
                               String displayName, String location) {
        // Hive just provisioned this device — load the right screen
        runOnUiThread(() -> {
            if ("kiosk".equals(role)) {
                showKioskScreen();
            }
            // robot / wearable / drone screens: Phase 4
        });
    }

    @Override
    public void onDeprovisioned() {
        runOnUiThread(this::showWaitingScreen);
    }

    // ── Permissions ────────────────────────────────────────────────────────────

    private void requestPermissionsIfNeeded() {
        String[] permissions = {
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        };

        boolean needsRequest = false;
        for (String perm : permissions) {
            if (ActivityCompat.checkSelfPermission(this, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                needsRequest = true;
                break;
            }
        }
        if (needsRequest) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (dashboardView != null) dashboardView.onResume();
        if (youtubeView != null) youtubeView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (dashboardView != null) dashboardView.onPause();
        if (youtubeView != null) youtubeView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (hiveConnection != null) hiveConnection.disconnect();
    }

    // ── System UI ──────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(WebView webView) {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setOnLongClickListener(v -> true);
        webView.setLongClickable(false);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyDown(keyCode, event);
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

    private String getAppVersion() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
