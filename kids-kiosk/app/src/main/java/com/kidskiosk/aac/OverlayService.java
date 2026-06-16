package com.kidskiosk.aac;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public class OverlayService extends Service {

    private static final String CHANNEL_ID = "kiosk_overlay";
    private static final int NOTIFICATION_ID = 42;
    private static final String PREFS_NAME = "kids_kiosk";
    private static final String KEY_PRO = "is_pro_unlocked";

    private WindowManager windowManager;
    private WebView kioskView;   // sidebar/drawer
    private WebView contentView; // right panel for URLs
    private WindowManager.LayoutParams overlayParams;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        showOverlay();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        // --- Window 1: Content view, full screen, UNDER everything ---
        // FLAG_NOT_TOUCHABLE so all touches pass through to apps underneath
        // We only use this when actively showing content
        contentView = new WebView(this);
        setupContentView(contentView);
        contentView.loadUrl("file:///android_asset/placeholder.html");

        WindowManager.LayoutParams contentParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        );
        contentParams.gravity = Gravity.START | Gravity.TOP;
        windowManager.addView(contentView, contentParams);

        // --- Window 2: Kiosk overlay, LEFT SIDE ONLY (300dp wide) ---
        // Only covers the drawer area so right side touches go to content/apps natively
        kioskView = new WebView(this);
        setupKioskView(kioskView);
        kioskView.loadUrl("file:///android_asset/kiosk.html");

        overlayParams = new WindowManager.LayoutParams(
            dpToPx(300),
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.START | Gravity.TOP;
        windowManager.addView(kioskView, overlayParams);
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupKioskView(WebView webView) {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setBackgroundColor(0x00000000); // transparent
        webView.setLayerType(WebView.LAYER_TYPE_SOFTWARE, null); // needed for transparency
        webView.addJavascriptInterface(new OverlayBridge(), "KioskBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                // Intercept app:// links
                String url = req.getUrl().toString();
                if (url.startsWith("app://")) {
                    launchApp(url.substring(6));
                    return true;
                }
                return false;
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupContentView(WebView webView) {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setBackgroundColor(0xFF000000);
        webView.loadUrl("file:///android_asset/placeholder.html");
    }

    public void setFocusable(boolean focusable) {
        if (overlayParams == null || windowManager == null) return;
        if (focusable) {
            overlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        try { windowManager.updateViewLayout(kioskView, overlayParams); } catch (Exception e) {}
    }

    public class OverlayBridge {

        @JavascriptInterface
        public boolean isPro() {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(KEY_PRO, false);
        }

        @JavascriptInterface
        public String getInstalledApps() {
            try {
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                JSONArray result = new JSONArray();
                for (ApplicationInfo app : apps) {
                    if (pm.getLaunchIntentForPackage(app.packageName) != null
                            && !app.packageName.equals(getPackageName())) {
                        JSONObject obj = new JSONObject();
                        obj.put("packageName", app.packageName);
                        obj.put("label", pm.getApplicationLabel(app).toString());
                        result.put(obj);
                    }
                }
                return result.toString();
            } catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface
        public void launchAppFromJs(String packageName) {
            launchApp(packageName);
        }

        @JavascriptInterface
        public void loadUrl(String url) {
            if (contentView != null) {
                contentView.post(() -> {
                    contentView.loadUrl(url);
                });
            }
        }

        @JavascriptInterface
        public void enableKeyboard() {
            if (kioskView != null) kioskView.post(() -> setFocusable(true));
        }

        @JavascriptInterface
        public void disableKeyboard() {
            if (kioskView != null) kioskView.post(() -> setFocusable(false));
        }

        @JavascriptInterface
        public void openDiscord() {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://discord.com/channels/1516172867119612014/1516172869837787178"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }

        @JavascriptInterface
        public void unlockPro() {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(KEY_PRO, true).apply();
            if (kioskView != null) {
                kioskView.post(() -> kioskView.evaluateJavascript("onProUnlocked()", null));
            }
        }

        @JavascriptInterface
        public void exitKioskMode() {
            Intent i = new Intent(OverlayService.this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            i.putExtra("show_setup", true);
            startActivity(i);
            stopSelf();
        }
    }

    private void launchApp(String packageName) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(packageName);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (contentView != null && windowManager != null) {
            try { windowManager.removeView(contentView); } catch (Exception e) {}
        }
        if (kioskView != null && windowManager != null) {
            try { windowManager.removeView(kioskView); } catch (Exception e) {}
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Kids Kiosk", NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Kids Kiosk")
                .setContentText("Kiosk active")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }
}
