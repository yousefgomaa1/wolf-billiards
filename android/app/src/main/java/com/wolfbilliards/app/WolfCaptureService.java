package com.wolfbilliards.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import java.nio.ByteBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

public class WolfCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "capture_result_code";
    public static final String EXTRA_RESULT_DATA = "capture_result_data";
    private static final String ACTION_STOP = "com.wolfbilliards.app.STOP_CAPTURE";
    private static final String CHANNEL_ID = "wolf_capture";
    private static final int NOTIFICATION_ID = 8109;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread imageThread;
    private Handler imageHandler;
    private NotificationManager notificationManager;
    private int frameCount = 0;
    private long lastNotificationAt = 0;
    private boolean stopping = false;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopCapture();
            return START_NOT_STICKY;
        }
        if (intent == null || !intent.hasExtra(EXTRA_RESULT_DATA)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startCaptureForeground();
        MediaProjectionManager manager =
            (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            stopCapture();
            return START_NOT_STICKY;
        }

        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                if (!stopping) stopCapture();
            }
        }, new Handler(getMainLooper()));

        startImageCapture();
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "WOLF screen analysis", NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Visible notification while WOLF captures the screen with your permission");
            notificationManager.createNotificationChannel(channel);
        }
    }

    private PendingIntent stopPendingIntent() {
        Intent stop = new Intent(this, WolfCaptureService.class);
        stop.setAction(ACTION_STOP);
        return PendingIntent.getService(
            this, NOTIFICATION_ID, stop,
            PendingIntent.FLAG_UPDATE_CURRENT |
                (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );
    }

    private Notification buildNotification(String message) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return builder
            .setContentTitle("WOLF — قراءة الشاشة")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف", stopPendingIntent())
            .build();
    }

    private void startCaptureForeground() {
        Notification notification = buildNotification("مشاركة الشاشة نشطة بإذنك. اضغط إيقاف لإنهائها.");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startImageCapture() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Point size = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(size);
            metrics.widthPixels = size.x;
            metrics.heightPixels = size.y;
            metrics.densityDpi = getResources().getDisplayMetrics().densityDpi;
        } else {
            wm.getDefaultDisplay().getRealMetrics(metrics);
        }

        imageThread = new HandlerThread("WolfScreenFrames");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());
        imageReader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels, android.graphics.PixelFormat.RGBA_8888, 2
        );
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;
                frameCount++;
                // Convert the RGBA ImageReader plane into a Bitmap while respecting
                // rowStride/pixelStride, then analyze it on this dedicated worker thread.
                Bitmap frame = imageToBitmap(image);
                WolfVisionAnalyzer.Result analysis = frame == null
                    ? null : WolfVisionAnalyzer.analyze(frame);
                if (analysis != null) {
                    WolfOverlayService.publishVisionResult(analysis, frame.getWidth(), frame.getHeight());
                }
                if (frame != null) frame.recycle();

                long now = System.currentTimeMillis();
                if (now - lastNotificationAt > 2500) {
                    lastNotificationAt = now;
                    String status = analysis == null
                        ? "التقاط نشط · في انتظار إطار صالح"
                        : analysis.describe();
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        buildNotification(status + " · إطارات: " + frameCount)
                    );
                }
            } catch (Exception ignored) {
            } finally {
                if (image != null) image.close();
            }
        }, imageHandler);

        virtualDisplay = projection.createVirtualDisplay(
            "WOLF_SCREEN_CAPTURE",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(),
            null,
            imageHandler
        );
    }

    private Bitmap imageToBitmap(Image image) {
        if (image == null || image.getPlanes() == null || image.getPlanes().length == 0) return null;
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        int paddedWidth = width + Math.max(0, rowPadding / Math.max(1, pixelStride));
        Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        buffer.rewind();
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        if (cropped != padded) padded.recycle();
        return cropped;
    }

    private void stopCapture() {
        if (stopping) return;
        stopping = true;

        VirtualDisplay display = virtualDisplay;
        virtualDisplay = null;
        if (display != null) display.release();

        ImageReader reader = imageReader;
        imageReader = null;
        if (reader != null) reader.close();

        MediaProjection activeProjection = projection;
        projection = null;
        if (activeProjection != null) activeProjection.stop();

        if (imageThread != null) {
            imageThread.quitSafely();
            imageThread = null;
            imageHandler = null;
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (!stopping) stopCapture();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
