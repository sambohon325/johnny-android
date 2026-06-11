package com.johnny6.riotdash;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.UUID;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Manages this device's persistent identity with the Hive.
 *
 * On first launch:
 *   - Generates a UUID and saves it to SharedPreferences (permanent)
 *   - Registers with the Hive API
 *   - Waits for provisioning from the dashboard
 *
 * On subsequent launches:
 *   - Reads UUID from SharedPreferences
 *   - Checks provisioning status with the Hive
 *   - Returns profile/role so MainActivity knows what UI to show
 */
public class DeviceIdentity {

    private static final String TAG = "DeviceIdentity";
    private static final String PREFS_NAME = "johnny_identity";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_PROVISIONED = "provisioned";
    private static final String KEY_PROFILE_ID = "profile_id";
    private static final String KEY_ROLE = "role";
    private static final String KEY_DISPLAY_NAME = "display_name";
    private static final String KEY_LOCATION = "location";

    private static final String REGISTER_URL =
            "https://api.sambohon.digital/api/devices/register";

    private final SharedPreferences prefs;
    private final OkHttpClient client;
    private final Context context;

    public DeviceIdentity(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.client = new OkHttpClient();
    }

    // ── Device ID ──────────────────────────────────────────────────────────────

    public String getDeviceId() {
        String id = prefs.getString(KEY_DEVICE_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE_ID, id).apply();
            Log.i(TAG, "Generated new device ID: " + id);
        }
        return id;
    }

    // ── Provisioning state ─────────────────────────────────────────────────────

    public boolean isProvisioned() {
        return prefs.getBoolean(KEY_PROVISIONED, false);
    }

    public String getProfileId() {
        return prefs.getString(KEY_PROFILE_ID, null);
    }

    public String getRole() {
        return prefs.getString(KEY_ROLE, "kiosk");
    }

    public String getDisplayName() {
        return prefs.getString(KEY_DISPLAY_NAME, "Johnny Device");
    }

    public String getLocation() {
        return prefs.getString(KEY_LOCATION, null);
    }

    /**
     * Called by HiveConnection when it receives a "provisioned" command.
     * Saves provisioning data locally so it survives app restarts.
     */
    public void applyProvisioning(String profileId, String role,
                                   String displayName, String location) {
        prefs.edit()
                .putBoolean(KEY_PROVISIONED, true)
                .putString(KEY_PROFILE_ID, profileId)
                .putString(KEY_ROLE, role)
                .putString(KEY_DISPLAY_NAME, displayName)
                .putString(KEY_LOCATION, location)
                .apply();
        Log.i(TAG, "Provisioned: " + displayName + " (" + profileId + "/" + role + ")");
    }

    /**
     * Called when Hive sends a "deprovisioned" command.
     * Clears provisioning — device returns to waiting screen.
     */
    public void clearProvisioning() {
        prefs.edit()
                .putBoolean(KEY_PROVISIONED, false)
                .remove(KEY_PROFILE_ID)
                .remove(KEY_ROLE)
                .remove(KEY_DISPLAY_NAME)
                .remove(KEY_LOCATION)
                .apply();
        Log.i(TAG, "Device deprovisioned — returning to waiting screen");
    }

    // ── Registration ───────────────────────────────────────────────────────────

    /**
     * Registers this device with the Hive.
     * Safe to call on every launch — Hive handles duplicates gracefully.
     * Runs on a background thread.
     */
    public void registerWithHive(String appVersion) {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("device_id", getDeviceId());
                body.put("model", getDeviceModel());
                body.put("apk_version", appVersion);

                RequestBody requestBody = RequestBody.create(
                        body.toString(),
                        MediaType.parse("application/json")
                );

                Request request = new Request.Builder()
                        .url(REGISTER_URL)
                        .post(requestBody)
                        .build();

                Response response = client.newCall(request).execute();
                if (response.isSuccessful() && response.body() != null) {
                    String responseStr = response.body().string();
                    JSONObject json = new JSONObject(responseStr);

                    // If Hive says we're already provisioned (e.g. fresh install
                    // but Hive remembers us), apply the provisioning data
                    if (json.optBoolean("provisioned", false)) {
                        String profileId   = json.optString("profile_id", null);
                        String role        = json.optString("role", "kiosk");
                        String displayName = json.optString("display_name", "Johnny Device");
                        String location    = json.optString("location", null);

                        if (profileId != null) {
                            applyProvisioning(profileId, role, displayName, location);
                        }
                    }

                    Log.i(TAG, "Registration response: " + responseStr);
                } else {
                    Log.w(TAG, "Registration failed: " + response.code());
                }
            } catch (Exception e) {
                Log.e(TAG, "Registration error: " + e.getMessage());
            }
        }).start();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String getDeviceModel() {
        return Build.MANUFACTURER + " " + Build.MODEL;
    }
}
