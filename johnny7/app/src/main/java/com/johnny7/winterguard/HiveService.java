package com.johnny7.winterguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Johnny 7 — Winter's silent guardian.
 *
 * Runs as a foreground service (required by Android for persistent background work).
 * Shows a minimal notification — "Johnny 7 is running" — nothing more.
 *
 * Does:
 *   - Connects to Hive via WebSocket
 *   - Sends heartbeat every 60s (battery, location)
 *   - Reports app usage every 5 minutes
 *   - Responds to camera and locate commands
 *   - Auto-reconnects if connection drops
 *
 * Does NOT:
 *   - Show any UI to Winter
 *   - Block any apps (Family Link handles that)
 *   - Interfere with her normal tablet use
 */
public class HiveService extends Service {

    private static final String TAG = "Johnny7";
    private static final String CHANNEL_ID = "johnny7_channel";
    private static final int NOTIFICATION_ID = 7;
    private static final long USAGE_REPORT_INTERVAL = 5 * 60 * 1000L; // 5 minutes

    private HiveConnection hiveConnection;
    private DeviceIdentity identity;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Johnny 7 service starting");

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        identity = new DeviceIdentity(this);
        identity.registerWithHive(getAppVersion());

        hiveConnection = new HiveConnection(this, identity, new HiveConnection.ProvisioningListener() {
            @Override
            public void onProvisioned(String profileId, String role, String displayName, String location) {
                Log.i(TAG, "Provisioned as: " + displayName);
            }
            @Override
            public void onDeprovisioned() {
                Log.i(TAG, "Deprovisioned — waiting for setup");
            }
        });

        hiveConnection.connect();
        scheduleUsageReports();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // Restart if killed
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (hiveConnection != null) hiveConnection.disconnect();
        Log.i(TAG, "Johnny 7 service stopped");
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Johnny 7",
                    NotificationManager.IMPORTANCE_MIN // Minimal — no sound, no popup
            );
            channel.setDescription("Winter's device guardian");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Johnny 7")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    // ── App usage reporting ────────────────────────────────────────────────────

    private void scheduleUsageReports() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                reportAppUsage();
                handler.postDelayed(this, USAGE_REPORT_INTERVAL);
            }
        }, USAGE_REPORT_INTERVAL);
    }

    private void reportAppUsage() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return;

        long now = System.currentTimeMillis();
        long fiveMinutesAgo = now - USAGE_REPORT_INTERVAL;

        List<UsageStats> stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                fiveMinutesAgo,
                now
        );

        if (stats == null || stats.isEmpty()) return;

        // Find the most recently used app
        SortedMap<Long, UsageStats> sortedMap = new TreeMap<>();
        for (UsageStats usageStats : stats) {
            if (usageStats.getTotalTimeInForeground() > 0) {
                sortedMap.put(usageStats.getLastTimeUsed(), usageStats);
            }
        }

        if (sortedMap.isEmpty()) return;

        UsageStats mostRecent = sortedMap.get(sortedMap.lastKey());
        if (mostRecent == null) return;

        hiveConnection.sendUsageReport(
                mostRecent.getPackageName(),
                mostRecent.getTotalTimeInForeground()
        );
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
