package com.wolfbilliards.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * WOLF BILLIARDS — مضيف محرك التصويب (المرحلة الرابعة: WolfEngineHost)
 * =====================================================================
 * جسر تشغيل wolf-engine.js داخل الخدمة عبر WebView مخفي (لا يُضاف إلى أي نافذة).
 *
 * - يُنشأ ويُستخدم على الخيط الرئيسي (متطلب WebView)، وأي استدعاء من خيط آخر
 *   يُحوَّل داخليًا عبر Handler إلى الخيط الرئيسي.
 * - يحمّل مصدر المحرك من أصول APK: assets/public/wolf-engine.js
 *   (النسخة التي ينسخها `npx cap sync android` من مجلد www/ إلى APK)،
 *   مع مسار احتياطي assets/wolf-engine.js.
 * - استدعاء JS واحد لكل حل: WolfEngine.solve(tableState) ثم JSON.stringify،
 *   والنتيجة تُحلَّل إلى كائن Solution جاهز للرسم.
 * - Throttle داخلي ذكي:
 *     1) أثناء تنفيذ حل قائم يُدمَج أي طلب جديد مكان القديم (الأحدث يفوز)،
 *     2) حد أدنى بين بداية حلّين متتاليين (الـ bench أظهر أسوأ حالة 58ms،
 *        فالفاصل 350ms يبقي الاستهلاك منخفضًا ويحدّث ~2.8 مرة/ثانية كحد أقصى).
 * - أي فشل (أصل مفقود/خطأ JS/فشل تحليل) يعود للمستدعي عبر onEngineError
 *   ليعمل الرسم التقليدي في AutoAimOverlayView كـ fallback دون أي انقطاع.
 */
final class WolfEngineHost {
    private static final String TAG = "WolfEngineHost";

    /** مسار أصل المحرك كما ينسخه Capacitor من www/ داخل APK. */
    private static final String ENGINE_ASSET = "public/wolf-engine.js";
    /** مسار احتياطي لو وُضع الملف مباشرة في جذر assets/. */
    private static final String ENGINE_ASSET_FALLBACK = "wolf-engine.js";

    /**
     * قوة افتراضية تُمرَّر للمحرك (حلّاله يجرب بنفسه [المفضلة، 80، 45]
     * ويختار الأفضل، فهذه القيمة ليست حرجة).
     */
    private static final double ENGINE_POWER = 60;

    /** حد أدنى بين بداية حلّين متتاليين داخل المضيف. */
    private static final long MIN_SOLVE_INTERVAL_MS = 350;

    /** مهلة احتياطية لو لم يصل onPageFinished لسبب ما (إعادة حقن المصدر). */
    private static final long INJECT_TIMEOUT_MS = 1500;

    // ==================================================================
    // أنواع البيانات (ناتج الحل — فضاء إحداثيات موحد x∈[0,2], y∈[0,1])
    // ==================================================================

    /** لقطة إطار الرؤية التي حُسب عليها الحل (بكسل مصدر، نفس فضاء Result). */
    static final class Snapshot {
        final int sourceWidth, sourceHeight;
        final int tableLeft, tableTop, tableRight, tableBottom;
        final float ballRadiusN;

        Snapshot(int sourceWidth, int sourceHeight,
                 int tableLeft, int tableTop, int tableRight, int tableBottom,
                 float ballRadiusN) {
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.tableLeft = tableLeft;
            this.tableTop = tableTop;
            this.tableRight = tableRight;
            this.tableBottom = tableBottom;
            this.ballRadiusN = ballRadiusN;
        }
    }

    /** اقتراح تصويب واحد من حلّال المحرك. */
    static final class Aim {
        final int ballIndex, ballNumber, pocketIndex;
        final float ballX, ballY;      // مركز الكرة الهدف
        final float pocketX, pocketY;  // مركز الجيب المستهدف
        final float ghostX, ghostY;    // موضع الكرة الشبحية
        final float cutAngleDeg;
        final float aimDirX, aimDirY;  // اتجاه تصويب البيضاء (وحدة طول)
        final boolean makeable, scratch;
        final float power, quality;
        final float[] cuePath;     // مسار البيضاء [x0,y0,x1,y1,...] بعد المحاكاة
        final float[] objectPath;  // مسار الكرة الهدف بعد المحاكاة

        Aim(int ballIndex, int ballNumber, int pocketIndex,
            float ballX, float ballY, float pocketX, float pocketY,
            float ghostX, float ghostY, float cutAngleDeg,
            float aimDirX, float aimDirY, boolean makeable, boolean scratch,
            float power, float quality, float[] cuePath, float[] objectPath) {
            this.ballIndex = ballIndex;
            this.ballNumber = ballNumber;
            this.pocketIndex = pocketIndex;
            this.ballX = ballX;
            this.ballY = ballY;
            this.pocketX = pocketX;
            this.pocketY = pocketY;
            this.ghostX = ghostX;
            this.ghostY = ghostY;
            this.cutAngleDeg = cutAngleDeg;
            this.aimDirX = aimDirX;
            this.aimDirY = aimDirY;
            this.makeable = makeable;
            this.scratch = scratch;
            this.power = power;
            this.quality = quality;
            this.cuePath = cuePath;
            this.objectPath = objectPath;
        }
    }

    /** تقييم الضربة الحالية (اتجاه العصا المكتشف من الرؤية). قد يكون null. */
    static final class CurrentShot {
        final float aimDirX, aimDirY;
        final boolean hasFirstHit;
        final float contactX, contactY;    // مركز البيضاء لحظة التلامس
        final float objectDirX, objectDirY; // اتجاه انطلاق الهدف بعد التلامس
        final boolean targetPocketed, cuePocketed;
        final float[] cuePath, targetPath;

        CurrentShot(float aimDirX, float aimDirY, boolean hasFirstHit,
                    float contactX, float contactY, float objectDirX, float objectDirY,
                    boolean targetPocketed, boolean cuePocketed,
                    float[] cuePath, float[] targetPath) {
            this.aimDirX = aimDirX;
            this.aimDirY = aimDirY;
            this.hasFirstHit = hasFirstHit;
            this.contactX = contactX;
            this.contactY = contactY;
            this.objectDirX = objectDirX;
            this.objectDirY = objectDirY;
            this.targetPocketed = targetPocketed;
            this.cuePocketed = cuePocketed;
            this.cuePath = cuePath;
            this.targetPath = targetPath;
        }
    }

    /** ناتج الحل الكامل: أهداف + تقييم الضربة الحالية + لقطة الإطار. */
    static final class Solution {
        final boolean ok;
        final String error;
        final Aim[] aims;
        final CurrentShot currentShot; // قد يكون null
        final Snapshot snapshot;
        /** نصف قطر الكرة الذي استخدمه المحرك فعليًا (meta) — للرسم المتطابق. */
        final float engineBallRadiusN;

        Solution(boolean ok, String error, Aim[] aims, CurrentShot currentShot,
                 Snapshot snapshot, float engineBallRadiusN) {
            this.ok = ok;
            this.error = error;
            this.aims = aims == null ? new Aim[0] : aims;
            this.currentShot = currentShot;
            this.snapshot = snapshot;
            this.engineBallRadiusN = engineBallRadiusN;
        }

        boolean hasContent() {
            return aims.length > 0 || currentShot != null;
        }

        /** تحويل إحداثية موحدة (x) إلى بكسل مصدر — نفس معادلة ترويسة المحرك. */
        float px(float xN) {
            return snapshot.tableLeft + xN * (snapshot.tableRight - snapshot.tableLeft) / 2f;
        }

        /** تحويل إحداثية موحدة (y) إلى بكسل مصدر. */
        float py(float yN) {
            return snapshot.tableTop + yN * (snapshot.tableBottom - snapshot.tableTop);
        }

        /** نصف قطر الكرة بالبكسل المصدر (نفس مقياس px/py). */
        float ballRadiusPx() {
            return engineBallRadiusN * (snapshot.tableRight - snapshot.tableLeft) / 2f;
        }
    }

    /** مستلم نتيجة الحل (يُستدعى على الخيط الرئيسي). */
    interface Listener {
        void onSolved(Solution solution);
        void onEngineError(String reason);
    }

    // ==================================================================
    // الحالة الداخلية
    // ==================================================================

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());

    private WebView webView;
    private boolean engineReady;   // حُقن المصدر وتأكدنا من وجود WolfEngine.solve
    private boolean injected;      // جرت محاولة حقن المصدر (تُصفَّر عند الفشل للسماح بإعادة المحاولة)
    private boolean destroyed;
    private boolean pageLoaded;    // وصل onPageFinished للصفحة المخفية
    private boolean solveInFlight;

    // طلب معلَّق واحد فقط (الأحدث يفوز أثناء التنفيذ أو قبل الجاهزية)
    private String pendingStateJson;
    private Snapshot pendingSnapshot;
    private Listener pendingListener;
    private long lastSolveStartedAtMs;

    private final Runnable injectTimeout = new Runnable() {
        @Override public void run() {
            if (destroyed || engineReady) return;
            if (pageLoaded) {
                // الصفحة جاهزة لكن المحرك غير مثبت — إعادة حقن (مثال: فشل الفحص سابقًا)
                injectEngineSource();
            } else if (webView != null) {
                // لم يصل onPageFinished لسبب ما — إعادة تحميل كمحاولة أخيرة
                try { webView.reload(); } catch (Throwable ignored) {}
            }
        }
    };

    private final Runnable flushPending = new Runnable() {
        @Override public void run() {
            startPendingSolveIfDue();
        }
    };

    // ==================================================================
    // الإنشاء والتحرير
    // ==================================================================

    private WolfEngineHost(Context appContext) {
        this.appContext = appContext;
    }

    /**
     * ينشئ المضيف. يجب استدعاؤه من الخيط الرئيسي (متطلب WebView)،
     * لكن الاستدعاءات اللاحقة لـ requestSolve جاهزة من أي خيط.
     *
     * @return المضيف أو null لو فشل إنشاء WebView (يستمر fallback كاملًا).
     */
    static WolfEngineHost create(Context context) {
        try {
            return new WolfEngineHost(context.getApplicationContext());
        } catch (Throwable error) {
            Log.e(TAG, "create failed", error);
            return null;
        }
    }

    /** يوقف الـ WebView المخفي ويلغي كل الطلبات المعلقة. */
    void destroy() {
        main.post(() -> {
            destroyed = true;
            main.removeCallbacks(injectTimeout);
            main.removeCallbacks(flushPending);
            pendingStateJson = null;
            pendingSnapshot = null;
            Listener listener = pendingListener;
            pendingListener = null;
            if (webView != null) {
                try {
                    webView.stopLoading();
                    webView.destroy();
                } catch (Throwable ignored) {
                }
                webView = null;
            }
            if (listener != null) listener.onEngineError("host-destroyed");
        });
    }

    // ==================================================================
    // بناء حالة الطاولة (JSON) من نتيجة الرؤية الموحدة
    // ==================================================================

    /**
     * يبني نص JSON لحالة الطاولة بالفضاء الموحد من Result.normalized.
     * يُستخدم نفس العقد الموثق في ترويسة wolf-engine.js.
     *
     * @return JSON نصي أو null لو تعذر البناء (المستدعي يعتبرها فشلًا مؤقتًا).
     */
    static String buildTableStateJson(WolfVisionAnalyzer.Result vision) {
        if (vision == null || !vision.hasNormalized()) return null;
        WolfVisionAnalyzer.Normalized n = vision.normalized;
        try {
            JSONObject state = new JSONObject();
            state.put("cueBall", new JSONObject()
                .put("x", (double) n.cueBallX)
                .put("y", (double) n.cueBallY));
            JSONArray balls = new JSONArray();
            for (int i = 0; i < n.ballCount; i++) {
                balls.put(new JSONObject()
                    .put("x", (double) n.ballX[i])
                    .put("y", (double) n.ballY[i])
                    .put("number", i + 1));
            }
            state.put("balls", balls);
            state.put("power", ENGINE_POWER);
            if (n.aimReady) {
                state.put("aimDirection", new JSONObject()
                    .put("x", (double) n.aimDirX)
                    .put("y", (double) n.aimDirY));
            }
            // ملاحظة: ballRadius لا يُمرَّر عمدًا — نستخدم الافتراضي المعاير
            // في المحرك (11/402) لأن تقدير الرؤية تقريبي، والمحرك يعيد نصف
            // القطر الفعلي في meta.ballRadius للرسم المتطابق.
            return state.toString();
        } catch (JSONException error) {
            Log.e(TAG, "buildTableStateJson failed", error);
            return null;
        }
    }

    // ==================================================================
    // طلب حل
    // ==================================================================

    /**
     * يطلب حلّ حالة طاولة. الطلبات المتزامنة تُدمج (الأحدث يفوز)،
     * والنتيجة تصل عبر listener على الخيط الرئيسي.
     */
    void requestSolve(String tableStateJson, int sourceWidth, int sourceHeight,
                      int tableLeft, int tableTop, int tableRight, int tableBottom,
                      float ballRadiusN, Listener listener) {
        if (listener == null) return;
        final String json = tableStateJson;
        final Snapshot snapshot = new Snapshot(sourceWidth, sourceHeight,
            tableLeft, tableTop, tableRight, tableBottom, ballRadiusN);
        main.post(() -> {
            if (destroyed) {
                listener.onEngineError("host-destroyed");
                return;
            }
            // الطلب الجديد يحل محل أي طلب معلق قديم — الأحدث يفوز دائمًا.
            pendingStateJson = json;
            pendingSnapshot = snapshot;
            pendingListener = listener;
            ensureWebViewCreated();
            if (!engineReady) {
                // لو فشل الحقن سابقًا والصفحة جاهزة — أعد المحاولة فورًا
                // (injectEngineSource محمي بعلم injected فلا يتكرر أثناء التنفيذ)
                if (pageLoaded) injectEngineSource();
                return; // سيبدأ الحل تلقائيًا فور جاهزية المحرك
            }
            startPendingSolveIfDue();
        });
    }

    // ==================================================================
    // إنشاء الـ WebView المخفي وحقن المحرك
    // ==================================================================

    private void ensureWebViewCreated() {
        if (webView != null || destroyed) return;
        try {
            webView = new WebView(appContext);
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(false);
            settings.setAllowFileAccess(false);      // نحقن المصدر نصيًا — لا حاجة لملفات
            settings.setSupportZoom(false);
            settings.setBuiltInZoomControls(false);
            settings.setDisplayZoomControls(false);
            settings.setLoadWithOverviewMode(false);
            settings.setUseWideViewPort(false);
            webView.setWebChromeClient(new WebChromeClient());
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) {
                    pageLoaded = true;
                    injectEngineSource();
                }
            });
            // صفحة فارغة مخفية تمامًا — الـ WebView لا يُضاف إلى أي نافذة.
            webView.loadDataWithBaseURL("about:blank",
                "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body></body></html>",
                "text/html", "utf-8", null);
            main.postDelayed(injectTimeout, INJECT_TIMEOUT_MS);
        } catch (Throwable error) {
            Log.e(TAG, "webView init failed", error);
            webView = null;
            engineReady = false;
            injected = false;
            notifyPendingFailure("webview-init-failed");
        }
    }

    /** يقرأ مصدر المحرك من الأصول ويحقنه في الصفحة المخفية (مرة واحدة). */
    private void injectEngineSource() {
        if (injected || destroyed || webView == null) return;
        injected = true;
        String source = readAsset(ENGINE_ASSET);
        if (source == null) source = readAsset(ENGINE_ASSET_FALLBACK);
        if (source == null) {
            engineReady = false;
            injected = false; // اسمح بإعادة المحاولة عند الطلب التالي
            notifyPendingFailure("engine-asset-missing");
            return;
        }
        final String engineSource = source;
        webView.evaluateJavascript(engineSource, ignoredValue -> {
            if (destroyed) return;
            // تحقق سريع أن window.WolfEngine.solve متاح قبل الإعلان عن الجاهزية.
            webView.evaluateJavascript(
                "(function(){return !!(window.WolfEngine&&window.WolfEngine.solve)})()",
                probe -> {
                    if (destroyed) return;
                    engineReady = "true".equals(probe);
                    if (engineReady) {
                        main.removeCallbacks(injectTimeout);
                        startPendingSolveIfDue();
                    } else {
                        injected = false; // حرّض المحاولة القادمة على إعادة الحقن
                        notifyPendingFailure("engine-probe-failed");
                    }
                });
        });
    }

    // ==================================================================
    // تنفيذ الحل مع الدمج والتحديد الزمني
    // ==================================================================

    private void startPendingSolveIfDue() {
        if (destroyed || solveInFlight || pendingStateJson == null) return;
        long sinceLast = SystemClock.elapsedRealtime() - lastSolveStartedAtMs;
        if (sinceLast < MIN_SOLVE_INTERVAL_MS) {
            // احترم الفاصل الأدنى — الطلب المعلق يبقى (وأي طلب أحدث يستبدله).
            main.removeCallbacks(flushPending);
            main.postDelayed(flushPending, MIN_SOLVE_INTERVAL_MS - sinceLast + 1);
            return;
        }
        final String stateJson = pendingStateJson;
        final Snapshot snapshot = pendingSnapshot;
        final Listener listener = pendingListener;
        pendingStateJson = null;
        pendingSnapshot = null;
        pendingListener = null;
        startSolve(stateJson, snapshot, listener);
    }

    private void startSolve(final String stateJson, final Snapshot snapshot, final Listener listener) {
        if (destroyed || webView == null || listener == null) {
            if (listener != null) listener.onEngineError("host-not-ready");
            return;
        }
        solveInFlight = true;
        lastSolveStartedAtMs = SystemClock.elapsedRealtime();
        // نفس غلاف الحماية الذي يُرجع دائمًا JSON نصيًا مهما حدث داخل المحرك.
        String expression = "(function(){try{var e=window.WolfEngine;"
            + "if(!e||!e.solve){return JSON.stringify({ok:false,error:'engine-missing'});}"
            + "return JSON.stringify(e.solve(" + stateJson + "));"
            + "}catch(err){return JSON.stringify({ok:false,"
            + "error:String(err&&err.message||err)});}})()";
        webView.evaluateJavascript(expression, value -> {
            solveInFlight = false;
            if (destroyed) return;
            Solution solution = null;
            String failure = null;
            try {
                solution = parseSolution(value, snapshot);
            } catch (Throwable error) {
                Log.e(TAG, "solve parse failed", error);
                failure = "solve-parse-failed";
            }
            if (solution != null && solution.ok) {
                listener.onSolved(solution);
            } else {
                String reason = solution != null && solution.error != null
                    ? solution.error : failure;
                listener.onEngineError(reason == null ? "solve-failed" : reason);
            }
            startPendingSolveIfDue();
        });
    }

    private void notifyPendingFailure(String reason) {
        Listener listener = pendingListener;
        pendingStateJson = null;
        pendingSnapshot = null;
        pendingListener = null;
        if (listener != null) listener.onEngineError(reason);
    }

    // ==================================================================
    // تحليل ناتج JSON إلى Solution
    // ==================================================================

    /**
     * يفك ناتج evaluateJavascript: القيمة المرجعة سلسلة JSON مقتبة،
     * فنفك الاقتباس الأول (JSONTokener) ثم نحلل الـ JSON الداخلي.
     */
    private static Solution parseSolution(String rawValue, Snapshot snapshot) throws JSONException {
        if (rawValue == null || rawValue.isEmpty())
            throw new JSONException("empty-eval-result");
        Object decoded = new JSONTokener(rawValue).nextValue();
        String inner = decoded instanceof String ? (String) decoded : rawValue;
        JSONObject root = new JSONObject(inner);

        boolean ok = root.optBoolean("ok", false);
        if (!ok) {
            return new Solution(false, root.optString("error", "engine-returned-not-ok"),
                null, null, snapshot, snapshot.ballRadiusN);
        }

        float engineBallRadius = snapshot.ballRadiusN;
        JSONObject meta = root.optJSONObject("meta");
        if (meta != null) {
            double metaRadius = meta.optDouble("ballRadius", Double.NaN);
            if (!Double.isNaN(metaRadius) && metaRadius > 0)
                engineBallRadius = (float) metaRadius;
        }

        JSONArray aimsArray = root.optJSONArray("aims");
        Aim[] aims = new Aim[aimsArray == null ? 0 : aimsArray.length()];
        for (int i = 0; i < aims.length; i++) {
            JSONObject a = aimsArray.optJSONObject(i);
            aims[i] = a == null ? null : parseAim(a);
        }
        // استبعاد أي عنصر فاشل دون كسر الترتيب
        int valid = 0;
        for (Aim aim : aims) if (aim != null) valid++;
        Aim[] compact = new Aim[valid];
        int index = 0;
        for (Aim aim : aims) if (aim != null) compact[index++] = aim;

        CurrentShot currentShot = parseCurrentShot(root.optJSONObject("currentShot"));
        return new Solution(true, null, compact, currentShot, snapshot, engineBallRadius);
    }

    private static Aim parseAim(JSONObject a) throws JSONException {
        JSONObject ballPos = a.optJSONObject("ballPos");
        JSONObject pocketPos = a.optJSONObject("pocketPos");
        JSONObject ghost = a.optJSONObject("ghost");
        JSONObject aimDir = a.optJSONObject("aimDirection");
        if (ghost == null || aimDir == null) return null;
        return new Aim(
            a.optInt("ballIndex", -1),
            a.optInt("ballNumber", -1),
            a.optInt("pocketIndex", -1),
            (float) (ballPos == null ? Double.NaN : ballPos.optDouble("x", Double.NaN)),
            (float) (ballPos == null ? Double.NaN : ballPos.optDouble("y", Double.NaN)),
            (float) (pocketPos == null ? Double.NaN : pocketPos.optDouble("x", Double.NaN)),
            (float) (pocketPos == null ? Double.NaN : pocketPos.optDouble("y", Double.NaN)),
            (float) ghost.optDouble("x", Double.NaN),
            (float) ghost.optDouble("y", Double.NaN),
            (float) a.optDouble("cutAngleDeg", 0),
            (float) aimDir.optDouble("x", 0),
            (float) aimDir.optDouble("y", 0),
            a.optBoolean("makeable", false),
            a.optBoolean("scratch", false),
            (float) a.optDouble("power", 0),
            (float) a.optDouble("quality", 0),
            readPath(a.optJSONArray("cuePath")),
            readPath(a.optJSONArray("objectPath")));
    }

    private static CurrentShot parseCurrentShot(JSONObject c) throws JSONException {
        if (c == null) return null;
        JSONObject aimDir = c.optJSONObject("aimDirection");
        JSONObject objectDir = c.optJSONObject("objectDirection");
        JSONObject firstHit = c.optJSONObject("firstHit");
        JSONObject contact = firstHit == null ? null : firstHit.optJSONObject("contact");
        boolean hasFirstHit = firstHit != null && contact != null;
        return new CurrentShot(
            aimDir == null ? 0f : (float) aimDir.optDouble("x", 0),
            aimDir == null ? 0f : (float) aimDir.optDouble("y", 0),
            hasFirstHit,
            hasFirstHit ? (float) contact.optDouble("x", Float.NaN) : Float.NaN,
            hasFirstHit ? (float) contact.optDouble("y", Float.NaN) : Float.NaN,
            objectDir == null ? 0f : (float) objectDir.optDouble("x", 0),
            objectDir == null ? 0f : (float) objectDir.optDouble("y", 0),
            c.optBoolean("targetPocketed", false),
            c.optBoolean("cuePocketed", false),
            readPath(c.optJSONArray("cuePath")),
            readPath(c.optJSONArray("targetPath")));
    }

    /** يحول مصفوفة نقاط [{x,y},...] إلى أزواج مسطحة [x0,y0,x1,y1,...]. */
    private static float[] readPath(JSONArray path) {
        if (path == null) return new float[0];
        float[] out = new float[path.length() * 2];
        int count = 0;
        for (int i = 0; i < path.length(); i++) {
            JSONObject point = path.optJSONObject(i);
            if (point == null) continue;
            out[count++] = (float) point.optDouble("x", Double.NaN);
            out[count++] = (float) point.optDouble("y", Double.NaN);
        }
        return count == out.length ? out : Arrays.copyOf(out, count);
    }

    // ==================================================================
    // أدوات مساعدة
    // ==================================================================

    /** يقرأ ملف نصي من أصول APK (نسخة www/ التي ينسخها Capacitor). */
    private String readAsset(String path) {
        InputStream input = null;
        try {
            input = appContext.getAssets().open(path);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            Log.w(TAG, "asset not readable: " + path);
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
