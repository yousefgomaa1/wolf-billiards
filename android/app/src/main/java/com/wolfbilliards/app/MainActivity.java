package com.wolfbilliards.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private static final int REQUEST_OVERLAY_PERMISSION = 7401;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureOverlayPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Settings.canDrawOverlays(this)) {
            startWolfOverlay();
        }
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
