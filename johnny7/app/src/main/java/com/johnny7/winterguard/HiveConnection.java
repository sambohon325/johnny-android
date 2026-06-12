package com.johnny7.winterguard;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.location.Location;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class HiveConnection {

    private static final String TAG = "Johnny7Hive";
    private static final String HIVE_WS_BASE = "wss://api.sambohon.digital/ws/device/";
    private static final int HEARTBEAT_INTERVAL_MS = 60_000;
    private static final int RECONNECT_DELAY_MS = 10_000;

    private final Context context;
    private final DeviceIdentity identity;
    private final ProvisioningListener provisioningListener;
    private OkHttpClient client;
    private WebSocket webSocket;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean intentionalClose = false;

    public interface ProvisioningListener {
        void onProvisioned(String profileId, String role, String displayName, String location);
        void onDeprovisioned();
    }

    public HiveConnection(Context context, DeviceIdentity identity,
                          ProvisioningListener listener) {
        this.context = context.getApplicationContext();
        this.identity = identity;
        this.provisioningListener = listener;
        this.client = new OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build();
    }

    public void connect() {
        intentionalClose = false;
        String wsUrl = HIVE_WS_BASE + identity.getDeviceId();
        Request request = new Request.Builder().url(wsUrl).build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket ws, @NonNull Response response) {
                Log.i(TAG, "Connected to Hive");
                sendHeartbeat();
                scheduleHeartbeat();
            }

            @Override
            public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
                handleCommand(text);
            }

            @Override
            public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t, Response response) {
                Log.w(TAG, "Connection failed: " + t.getMessage());
                scheduleReconnect();
            }

            @Override
            public void onClosed(@NonNull WebSocket ws, int code, @NonNull String reason) {
                if (!intentionalClose) scheduleReconnect();
            }
        });
    }

    public void disconnect() {
        intentionalClose = true;
        mainHandler.removeCallbacksAndMessages(null);
        if (webSocket != null) webSocket.close(1000, "Service stopping");
    }

    // ── Heartbeat ──────────────────────────────────────────────────────────────

    private void scheduleHeartbeat() {
        mainHandler.postDelayed(() -> {
            sendHeartbeat();
            scheduleHeartbeat();
        }, HEARTBEAT_INTERVAL_MS);
    }

    private void sendHeartbeat() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "heartbeat");
            msg.put("battery", getBatteryPercent());
            msg.put("apk_version", getAppVersion());
            msg.put("profile_id", identity.getProfileId());
            msg.put("role", identity.getRole());

            Location loc = getLastLocation();
            if (loc != null) {
                msg.put("lat", loc.getLatitude());
                msg.put("lng", loc.getLongitude());
            }
            send(msg.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Heartbeat error: " + e.getMessage());
        }
    }

    // ── Usage reporting ────────────────────────────────────────────────────────

    public void sendUsageReport(String packageName, long timeInForegroundMs) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "usage_report");
            msg.put("package_name", packageName);
            msg.put("time_ms", timeInForegroundMs);
            send(msg.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Usage report error: " + e.getMessage());
        }
    }

    // ── Command handler ────────────────────────────────────────────────────────

    private void handleCommand(String text) {
        try {
            JSONObject cmd = new JSONObject(text);
            String command = cmd.optString("command", "");

            switch (command) {
                case "provisioned":
                    String profileId   = cmd.optString("profile_id");
                    String role        = cmd.optString("role", "kiosk");
                    String displayName = cmd.optString("display_name");
                    String location    = cmd.optString("location");
                    identity.applyProvisioning(profileId, role, displayName, location);
                    if (provisioningListener != null) {
                        mainHandler.post(() -> provisioningListener.onProvisioned(
                                profileId, role, displayName, location));
                    }
                    break;

                case "deprovisioned":
                    identity.clearProvisioning();
                    if (provisioningListener != null) {
                        mainHandler.post(() -> provisioningListener.onDeprovisioned());
                    }
                    break;

                case "take_photo_front":
                    capturePhoto(true);
                    break;

                case "take_photo_back":
                    capturePhoto(false);
                    break;

                case "locate":
                    sendLocation();
                    break;

                default:
                    Log.d(TAG, "Unhandled command: " + command);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Command parse error: " + e.getMessage());
        }
    }

    // ── Location ───────────────────────────────────────────────────────────────

    private void sendLocation() {
        Location loc = getLastLocation();
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "location_result");
            if (loc != null) {
                msg.put("lat", loc.getLatitude());
                msg.put("lng", loc.getLongitude());
            } else {
                msg.put("lat", JSONObject.NULL);
                msg.put("lng", JSONObject.NULL);
            }
            send(msg.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Location error: " + e.getMessage());
        }
    }

    private Location getLastLocation() {
        if (ActivityCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;

        Location best = null;
        for (String provider : lm.getProviders(true)) {
            Location loc = lm.getLastKnownLocation(provider);
            if (loc != null && (best == null || loc.getAccuracy() < best.getAccuracy())) {
                best = loc;
            }
        }
        return best;
    }

    // ── Camera ─────────────────────────────────────────────────────────────────

    private void capturePhoto(boolean useFrontCamera) {
        if (ActivityCompat.checkSelfPermission(context,
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission not granted");
            return;
        }

        CameraManager cameraManager =
                (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) return;

        String cameraId = null;
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing == null) continue;
                if (useFrontCamera && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    cameraId = id; break;
                } else if (!useFrontCamera && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id; break;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera list error: " + e.getMessage());
            return;
        }

        if (cameraId == null) return;

        final String finalCameraId = cameraId;
        final String cameraLabel = useFrontCamera ? "front" : "back";

        HandlerThread thread = new HandlerThread("CameraCapture");
        thread.start();
        Handler cameraHandler = new Handler(thread.getLooper());
        ImageReader imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1);

        try {
            cameraManager.openCamera(finalCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    try {
                        CaptureRequest.Builder builder = camera.createCaptureRequest(
                                CameraDevice.TEMPLATE_STILL_CAPTURE);
                        builder.addTarget(imageReader.getSurface());
                        camera.createCaptureSession(Arrays.asList(imageReader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(@NonNull CameraCaptureSession session) {
                                        try {
                                            session.capture(builder.build(),
                                                    new CameraCaptureSession.CaptureCallback() {
                                                        @Override
                                                        public void onCaptureCompleted(
                                                                @NonNull CameraCaptureSession s,
                                                                @NonNull CaptureRequest req,
                                                                @NonNull TotalCaptureResult result) {
                                                            Image image = imageReader.acquireLatestImage();
                                                            if (image != null) {
                                                                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                                                                byte[] bytes = new byte[buffer.remaining()];
                                                                buffer.get(bytes);
                                                                image.close();
                                                                sendPhoto(cameraLabel, bytes);
                                                            }
                                                            camera.close();
                                                            thread.quitSafely();
                                                        }
                                                    }, cameraHandler);
                                        } catch (CameraAccessException e) {
                                            camera.close(); thread.quitSafely();
                                        }
                                    }
                                    @Override
                                    public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                                        camera.close(); thread.quitSafely();
                                    }
                                }, cameraHandler);
                    } catch (CameraAccessException e) {
                        camera.close(); thread.quitSafely();
                    }
                }
                @Override public void onDisconnected(@NonNull CameraDevice c) { c.close(); thread.quitSafely(); }
                @Override public void onError(@NonNull CameraDevice c, int e) { c.close(); thread.quitSafely(); }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "Camera error: " + e.getMessage());
            thread.quitSafely();
        }
    }

    private void sendPhoto(String camera, byte[] jpegBytes) {
        try {
            String b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP);
            JSONObject msg = new JSONObject();
            msg.put("type", "photo_result");
            msg.put("camera", camera);
            msg.put("data", b64);
            send(msg.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Photo error: " + e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void send(String message) {
        if (webSocket != null) webSocket.send(message);
    }

    private int getBatteryPercent() {
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (bm == null) return -1;
        int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return (pct >= 0 && pct <= 100) ? pct : -1;
    }

    private String getAppVersion() {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    private void scheduleReconnect() {
        if (intentionalClose) return;
        mainHandler.postDelayed(this::connect, RECONNECT_DELAY_MS);
    }
}
