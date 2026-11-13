package com.wolfbilliards.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.ValueCallback;
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
    private AutoAimOverlayView autoAimView;
    private WindowManager.LayoutParams autoAimParams;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static volatile WolfOverlayService activeService;
    private static ValueCallback<Uri[]> pendingImageCallback;

    // ==================================================================
    // المرحلة الرابعة: ربط محرك التصويب (wolf-engine.js) بالـ Overlay
    // عبر WebView مخفي داخل الخدمة + throttle ذكي:
    //   1) كشف تغير المشهد (توقيع الإحداثيات الموحدة) — مشهد ثابت = لا حل جديد،
    //   2) دمج الطلبات + حد أدنى بين الحلول داخل WolfEngineHost
    //      (الـ bench: أسوأ حالة 58ms → حد 350ms ≈ 2.8 حل/ث كحد أقصى)،
    //   3) تراجع تدريجي (backoff) بعد الإخفاقات المتتالية مع عودة تلقائية.
    // ==================================================================

    /** أقصى إزاحة (وحدة موحدة) تعتبر "مشهدًا ثابتًا" — ≈ 1.2% من نصف عرض الطاولة. */
    private static final float SCENE_MOVE_EPS = 0.012f;
    /** أقل تغير في زاوية العصا (درجات) يستحق إعادة الحساب. */
    private static final float SCENE_AIM_EPS_DEG = 3f;
    /** تغير أبعاد مستطيل الطاولة (نسبة) يستحق إعادة الحساب. */
    private static final float SCENE_RECT_EPS = 0.02f;
    /** عدد الإخفاقات المتتالية قبل بدء التراجع التدريجي. */
    private static final int ENGINE_FAILURES_BEFORE_BACKOFF = 3;

    // حالة محرك التصويب
    private WolfEngineHost engineHost;
    private float[] lastSceneSignature;
    private int engineFailureCount;
    private long enginePausedUntilMs;

    private final WolfEngineHost.Listener engineListener = new WolfEngineHost.Listener() {
        @Override public void onSolved(WolfEngineHost.Solution solution) {
            engineFailureCount = 0;
            enginePausedUntilMs = 0;
            // عرض مسارات المحرك فوق اللعبة (الرسم التقليدي يظل يعمل بالتوازي كـ fallback)
            if (autoAimView != null && solution != null) autoAimView.setEngineResult(solution);
        }

        @Override public void onEngineError(String reason) {
            noteEngineFailure(reason);
        }
    };

    static void publishVisionResult(WolfVisionAnalyzer.Result result, int width, int height) {
        WolfOverlayService service = activeService;
        if (service == null) return;
        service.mainHandler.post(() -> service.showOrUpdateAutoAim(result, width, height));
    }

    private void showOrUpdateAutoAim(WolfVisionAnalyzer.Result result, int width, int height) {
        if (windowManager == null || !android.provider.Settings.canDrawOverlays(this)) return;
        if (autoAimView == null) {
            autoAimView = new AutoAimOverlayView(this);
            autoAimParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            );
            autoAimParams.gravity = Gravity.TOP | Gravity.START;
            try { windowManager.addView(autoAimView, autoAimParams); }
            catch (Exception error) { autoAimView = null; autoAimParams = null; return; }
        }
        autoAimView.setAnalysis(result, width, height);

        // المرحلة الرابعة: تغذية المحرك بنفس الإطار مع throttle ذكي
        maybeRequestEngineSolve(result, width, height);
    }

    // ==================================================================
    // المرحلة الرابعة: خط أنابيب المحرك + throttle ذكي
    // ==================================================================

    /**
     * يقرر هل يستحق هذا الإطار حلًا جديدًا من المحرك:
     * فقدان الطاولة/البيضاء → مسح المسارات والعودة للرسم التقليدي،
     * مشهد ثابت (توقيع متطابق ضمن السماحيات) → لا إعادة حساب،
     * مشهد متغير → طلب حل عبر WolfEngineHost (يدمج الطلبات وينظّم الإيقاع).
     */
    private void maybeRequestEngineSolve(WolfVisionAnalyzer.Result vision, int width, int height) {
        if (autoAimView == null) return;
        if (vision == null || !vision.tableFound || !vision.hasNormalized()) {
            // لا طاولة/بيضاء → مسارات المحرك القديمة لم تعد صالحة
            autoAimView.setEngineResult(null);
            lastSceneSignature = null;
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now < enginePausedUntilMs) return; // فترة تراجع بعد إخفاقات متتالية

        if (engineHost == null) engineHost = WolfEngineHost.create(this);
        if (engineHost == null) {
            // تعذر إنشاء الـ WebView المخفي — محاولة جديدة بعد قليل
            enginePausedUntilMs = now + 3000;
            return;
        }

        float[] signature = sceneSignature(vision);
        if (sceneUnchanged(signature)) return; // مشهد ثابت → لا حل جديد
        startEngineSolve(vision, width, height, signature);
    }

    /** يبدأ حلًا جديدًا عبر المضيف ويحدّث التوقيع المرجعي للمشهد. */
    private void startEngineSolve(WolfVisionAnalyzer.Result vision, int width, int height,
                                  float[] signature) {
        String tableStateJson = WolfEngineHost.buildTableStateJson(vision);
        if (tableStateJson == null) {
            noteEngineFailure("state-json-null");
            return;
        }
        lastSceneSignature = signature;
        WolfVisionAnalyzer.Normalized n = vision.normalized;
        engineHost.requestSolve(tableStateJson, width, height,
            vision.tableLeft, vision.tableTop, vision.tableRight, vision.tableBottom,
            n.ballRadius, engineListener);
    }

    /**
     * توقيع المشهد: [عرض الطاولة، ارتفاعها، بيضاء (x,y)، زاوية العصا أو NaN،
     * ثم الكرات مرتبة بالإحداثيات] — الترتيب يمنع تذبذب ترتيب الفحص بين
     * الإطارات من التسبب في حلول لا لزوم لها.
     */
    private static float[] sceneSignature(WolfVisionAnalyzer.Result vision) {
        WolfVisionAnalyzer.Normalized n = vision.normalized;
        int count = n.ballCount;
        float[] bx = new float[count];
        float[] by = new float[count];
        System.arraycopy(n.ballX, 0, bx, 0, count);
        System.arraycopy(n.ballY, 0, by, 0, count);
        sortByPosition(bx, by);

        float[] signature = new float[5 + count * 2];
        signature[0] = vision.tableRight - vision.tableLeft;
        signature[1] = vision.tableBottom - vision.tableTop;
        signature[2] = n.cueBallX;
        signature[3] = n.cueBallY;
        signature[4] = n.aimReady
            ? (float) Math.atan2(n.aimDirY, n.aimDirX) : Float.NaN;
        for (int i = 0; i < count; i++) {
            signature[5 + i * 2] = bx[i];
            signature[6 + i * 2] = by[i];
        }
        return signature;
    }

    /** مقارنة التوقيع الحالي بآخر توقيع حُلّ من أجله — ضمن السماحيات. */
    private boolean sceneUnchanged(float[] signature) {
        float[] last = lastSceneSignature;
        if (last == null || last.length != signature.length) return false;

        // استقرار مستطيل الطاولة (تغير التكبير/البانوراما يستحق إعادة الحساب)
        if (Math.abs(last[0] - signature[0]) > last[0] * SCENE_RECT_EPS) return false;
        if (Math.abs(last[1] - signature[1]) > last[1] * SCENE_RECT_EPS) return false;

        // حركة الكرة البيضاء
        if (Math.hypot(last[2] - signature[2], last[3] - signature[3]) > SCENE_MOVE_EPS)
            return false;

        // اتجاه العصا (أهم إشارة أثناء التصويب — يجب أن يستجيب التحديث لها)
        boolean hadAim = !Float.isNaN(last[4]);
        boolean hasAim = !Float.isNaN(signature[4]);
        if (hadAim != hasAim) return false;
        if (hasAim
                && Math.abs(angleDifference(last[4], signature[4]))
                    > Math.toRadians(SCENE_AIM_EPS_DEG))
            return false;

        // حركة أي كرة هدف
        for (int i = 5; i < signature.length; i += 2) {
            if (Math.hypot(last[i] - signature[i], last[i + 1] - signature[i + 1])
                    > SCENE_MOVE_EPS) return false;
        }
        return true;
    }

    /** فرز الإدراج للكرات بالإحداثيات (x ثم y) — عدد الكرات صغير (≤16). */
    private static void sortByPosition(float[] bx, float[] by) {
        for (int i = 1; i < bx.length; i++) {
            float x = bx[i], y = by[i];
            int j = i - 1;
            while (j >= 0 && (bx[j] > x || (bx[j] == x && by[j] > y))) {
                bx[j + 1] = bx[j];
                by[j + 1] = by[j];
                j--;
            }
            bx[j + 1] = x;
            by[j + 1] = y;
        }
    }

    /** فرق زاويتين مطبّع إلى [-π, +π]. */
    private static float angleDifference(float a, float b) {
        float diff = a - b;
        while (diff > Math.PI) diff -= (float) (2 * Math.PI);
        while (diff < -Math.PI) diff += (float) (2 * Math.PI);
        return diff;
    }

    /** تسجيل إخفاق محرك + تراجع تدريجي (2ث ← 4ث ← 8ث ← 15ث كحد أقصى). */
    private void noteEngineFailure(String reason) {
        engineFailureCount++;
        if (engineFailureCount >= ENGINE_FAILURES_BEFORE_BACKOFF) {
            int step = Math.min(3, engineFailureCount - ENGINE_FAILURES_BEFORE_BACKOFF);
            long backoffMs = (long) Math.min(15000, 2000L * (1L << step));
            enginePausedUntilMs = SystemClock.elapsedRealtime() + backoffMs;
        }
        // لا مسح للحل الأخير الناجح — يظل معروضًا، والرسم التقليدي يعمل بالتوازي
    }

    private void hideAutoAimOverlay() {
        if (autoAimView != null) {
            try { windowManager.removeView(autoAimView); } catch (Exception ignored) {}
            autoAimView = null;
            autoAimParams = null;
        }
    }

    static synchronized void deliverImagePickerResult(Uri[] result) {
        if (pendingImageCallback != null) {
            pendingImageCallback.onReceiveValue(result);
            pendingImageCallback = null;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        activeService = this;
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

        Button captureButton = new Button(this);
        captureButton.setText("بدء قراءة الشاشة");
        captureButton.setOnClickListener(v -> {
            Intent request = new Intent(WolfOverlayService.this, MainActivity.class);
            request.setAction(MainActivity.ACTION_REQUEST_CAPTURE);
            request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(request);
        });
        container.addView(captureButton, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ));

        TextView status = new TextView(this);
        status.setText("التصويب التلقائي\n\nابدأ قراءة الشاشة، ثم افتح 8 Ball Pool. سيحاول WOLF اكتشاف الطاولة والكرات واتجاه العصاية ورسم التحليل تلقائيًا فوق اللعبة.\n\nملاحظة: دقة الكشف ما زالت تجريبية وتحتاج اختبارًا على شاشة لعب حقيقية.");
        status.setTextColor(Color.WHITE);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(18), dp(24), dp(18), dp(24));
        container.addView(status, new LinearLayout.LayoutParams(
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
        if (activeService == this) activeService = null;
        hideAutoAimOverlay();
        hidePanel();
        // المرحلة الرابعة: تحرير الـ WebView المخفي مع الخدمة
        if (engineHost != null) {
            engineHost.destroy();
            engineHost = null;
        }
        lastSceneSignature = null;
        if (bubble != null) {
            try { windowManager.removeView(bubble); } catch (Exception ignored) {}
            bubble = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
