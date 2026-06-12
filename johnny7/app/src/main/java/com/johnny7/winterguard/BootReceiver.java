package com.johnny7.winterguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Starts Johnny 7 automatically when the tablet boots.
 * Winter never needs to open the app — it just runs.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "Johnny7Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {

            Log.i(TAG, "Boot complete — starting Johnny 7 service");
            Intent serviceIntent = new Intent(context, HiveService.class);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        }
    }
}
