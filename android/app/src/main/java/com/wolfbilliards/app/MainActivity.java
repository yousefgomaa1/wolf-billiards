package com.wolfbilliards.app;

import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private static final int REQUEST_OVERLAY_PERMISSION = 7401;
    private static final int REQUEST_SCREEN_CAPTURE = 7402;
    public static final String ACTION_REQUEST_CAPTURE = "com.wolfbilliards.app.REQUEST_CAPTURE";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureOverlayPermission();
        handleCaptureRequest(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleCaptureRequest(intent);
    }

    private void handleCaptureRequest(Intent intent) {
        if (intent != null && ACTION_REQUEST_CAPTURE.equals(intent.getAction())) {
            intent.setAction(null);
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent capture = new Intent(this, WolfCaptureService.class);
                capture.putExtra(WolfCaptureService.EXTRA_RESULT_CODE, resultCode);
                capture.putExtra(WolfCaptureService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(capture);
                else startService(capture);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (Settings.canDrawOverlays(this)) startWolfOverlay();
    }

    private void ensureOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
        }
    }

    private void startWolfOverlay() {
        Intent serviceIntent = new Intent(this, WolfOverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }
}
