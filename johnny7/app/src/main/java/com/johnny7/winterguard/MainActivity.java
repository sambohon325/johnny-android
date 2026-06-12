package com.johnny7.winterguard;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * Johnny 7 — Winter's silent guardian.
 *
 * This activity exists only to start the background service.
 * It immediately finishes — Winter never sees a UI.
 *
 * If she taps the app icon, it starts the service (if not running)
 * and disappears. Nothing to see here.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Start the background service
        Intent serviceIntent = new Intent(this, HiveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Close immediately — no UI
        finish();
    }
}
