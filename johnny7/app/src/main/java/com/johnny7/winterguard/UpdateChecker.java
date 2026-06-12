package com.johnny7.winterguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String VERSION_URL =
            "https://api.sambohon.digital/api/version/latest/johnny7";
    private static final String DOWNLOAD_URL =
            "https://api.sambohon.digital/api/version/download/johnny7";

    private final Activity activity;
    private final OkHttpClient client;
    private final String currentVersion;

    public UpdateChecker(Activity activity) {
        this.activity = activity;
        this.client = new OkHttpClient();
        this.currentVersion = getCurrentVersion();
    }

    /**
     * Call this on app launch.
     * Checks the Hive for a newer APK version silently in the background.
     * Only shows a dialog if an update is actually available.
     */
    public void checkForUpdates() {
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                try {
                    Request request = new Request.Builder()
                            .url(VERSION_URL)
                            .build();
                    Response response = client.newCall(request).execute();
                    if (response.isSuccessful() && response.body() != null) {
                        return response.body().string();
                    }
                } catch (IOException e) {
                    Log.d(TAG, "Update check failed (offline?): " + e.getMessage());
                }
                return null;
            }

            @Override
            protected void onPostExecute(String result) {
                if (result == null) return;
                try {
                    JSONObject json = new JSONObject(result);
                    boolean hasUpdate = json.optBoolean("has_update", false);
                    String latestVersion = json.optString("version", "");
                    String releaseNotes = json.optString("release_notes", "");

                    if (hasUpdate && !latestVersion.equals(currentVersion)) {
                        Log.i(TAG, "Update available: " + latestVersion);
                        showUpdateDialog(latestVersion, releaseNotes);
                    } else {
                        Log.d(TAG, "App is up to date: " + currentVersion);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Update check parse error: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void showUpdateDialog(String version, String releaseNotes) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        activity.runOnUiThread(() -> {
            String message = "Version " + version + " is available.\n\n" +
                    (releaseNotes.isEmpty() ? "" : releaseNotes + "\n\n") +
                    "Install now?";

            new AlertDialog.Builder(activity)
                    .setTitle("Johnny Update Available")
                    .setMessage(message)
                    .setPositiveButton("Install", (dialog, which) -> downloadAndInstall())
                    .setNegativeButton("Later", null)
                    .show();
        });
    }

    private void downloadAndInstall() {
        new AsyncTask<Void, Void, File>() {
            @Override
            protected File doInBackground(Void... voids) {
                try {
                    Request request = new Request.Builder()
                            .url(DOWNLOAD_URL)
                            .build();
                    Response response = client.newCall(request).execute();
                    if (!response.isSuccessful() || response.body() == null) return null;

                    // Save to app's external files dir (doesn't need WRITE_EXTERNAL_STORAGE)
                    File apkFile = new File(
                            activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                            "johnny6-update.apk"
                    );

                    try (InputStream in = response.body().byteStream();
                         FileOutputStream out = new FileOutputStream(apkFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            out.write(buffer, 0, len);
                        }
                    }
                    return apkFile;

                } catch (IOException e) {
                    Log.e(TAG, "APK download error: " + e.getMessage());
                    return null;
                }
            }

            @Override
            protected void onPostExecute(File apkFile) {
                if (apkFile == null) {
                    Log.e(TAG, "Download failed");
                    return;
                }
                installApk(apkFile);
            }
        }.execute();
    }

    private void installApk(File apkFile) {
        try {
            Uri apkUri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".provider",
                    apkFile
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Install error: " + e.getMessage());
        }
    }

    private String getCurrentVersion() {
        try {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.0.0";
        }
    }
}
