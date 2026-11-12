package com.wolfbilliards.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class WolfOverlayService extends Service {
    private static final String CHANNEL_ID = "wolf_overlay";
    private static final int NOTIFICATION_ID = 8108;

    private WindowManager windowManager;
    private View bubble;
    private View panel;
    private WindowManager.LayoutParams bubbleParams;
    private WindowManager.LayoutParams panelParams;
    private boolean panelVisible = false;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        startAsForeground();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        showBubble();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void startAsForeground() {
        Intent launchIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        Notification notification = builder
            .setContentTitle("WOLF BILLIARDS")
            .setContentText("الزر العائم جاهز — اضغط لفتح لوحة WOLF")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "WOLF floating assistant", NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps the WOLF floating button available over other apps");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) shape.setStroke(dp(1), strokeColor);
        return shape;
    }

    private void showBubble() {
        if (bubble != null) return;
        TextView button = new TextView(this);
        button.setText("W");
        button.setTextColor(Color.rgb(18, 16, 10));
        button.setTextSize(22);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setBackground(rounded(Color.rgb(214, 179, 106), 28, Color.WHITE));
        bubble = button;

        bubbleParams = new WindowManager.LayoutParams(
            dp(56), dp(56),
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = dp(12);
        bubbleParams.y = dp(180);

        button.setOnClickListener(v -> showPanel());
        button.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float downX, downY;
            private boolean moved;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = bubbleParams.x; startY = bubbleParams.y;
                    downX = event.getRawX(); downY = event.getRawY(); moved = false;
                    return false;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    int dx = (int) (event.getRawX() - downX);
                    int dy = (int) (event.getRawY() - downY);
                    if (Math.abs(dx) + Math.abs(dy) > dp(5)) moved = true;
                    if (moved) {
                        bubbleParams.x = startX + dx; bubbleParams.y = startY + dy;
                        windowManager.updateViewLayout(bubble, bubbleParams);
                    }
                    return moved;
                }
                if (event.getAction() == MotionEvent.ACTION_UP && moved) return true;
                return false;
            }
        });
        windowManager.addView(bubble, bubbleParams);
    }

    private void showPanel() {
        if (panelVisible) return;
        panelVisible = true;

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackground(rounded(Color.rgb(12, 16, 20), 16, Color.rgb(214, 179, 106)));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(8), dp(8), dp(8));
        toolbar.setBackground(rounded(Color.rgb(28, 35, 42), 14, 0));

        TextView title = new TextView(this);
        title.setText("WOLF BILLIARDS");
        title.setTextColor(Color.rgb(214, 179, 106));
        title.setTextSize(14);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1));

        Button close = new Button(this);
        close.setText("×");
        close.setTextSize(20);
        close.setPadding(0, 0, 0, 0);
        toolbar.addView(close, new LinearLayout.LayoutParams(dp(44), dp(42)));
        close.setOnClickListener(v -> hidePanel());
        container.addView(toolbar);

        WebView webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setBackgroundColor(Color.rgb(9, 11, 14));
        webView.loadUrl("file:///android_asset/public/index.html");
        container.addView(webView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1
        ));

        panel = container;
        panelParams = new WindowManager.LayoutParams(
            Math.min(dp(380), getResources().getDisplayMetrics().widthPixels - dp(24)),
            Math.min(dp(620), getResources().getDisplayMetrics().heightPixels - dp(80)),
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        panelParams.gravity = Gravity.CENTER;
        windowManager.addView(panel, panelParams);

        toolbar.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    downX = e.getRawX(); downY = e.getRawY();
                    startX = panelParams.x; startY = panelParams.y;
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    panelParams.x = startX + (int) (e.getRawX() - downX);
                    panelParams.y = startY + (int) (e.getRawY() - downY);
                    windowManager.updateViewLayout(panel, panelParams);
                    return true;
                }
                return e.getAction() == MotionEvent.ACTION_UP;
            }
        });
    }

    private void hidePanel() {
        if (panel != null) {
            try { windowManager.removeView(panel); } catch (Exception ignored) {}
            panel = null;
        }
        panelVisible = false;
    }

    @Override
    public void onDestroy() {
        hidePanel();
        if (bubble != null) {
            try { windowManager.removeView(bubble); } catch (Exception ignored) {}
            bubble = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
