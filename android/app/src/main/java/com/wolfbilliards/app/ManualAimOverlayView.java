package com.wolfbilliards.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

public class ManualAimOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF table = new RectF();
    private float startX, startY, endX, endY;
    private boolean tableLocked = false;
    private int dragMode = 0;
    private int tableCorner = 3;
    private static final int NONE = 0, START = 1, END = 2, TABLE = 3, MOVE_TABLE = 4;
    private float lastX, lastY;
    private float handleRadius;

    public interface Actions { void hide(); }
    private final Actions actions;

    public ManualAimOverlayView(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        handleRadius = 18f * getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.TRANSPARENT);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (table.isEmpty()) {
            table.set(w * .09f, h * .19f, w * .91f, h * .88f);
            startX = w * .25f; startY = h * .53f;
            endX = w * .68f; endY = h * .42f;
        }
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float d = getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xD9161C22);
        c.drawRoundRect(0, 0, getWidth(), dp(48), dp(12), dp(12), paint);
        paint.setColor(Color.WHITE); paint.setTextSize(dp(13)); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        c.drawText(tableLocked ? "الطاولة مقفولة" : "اضبط حدود الطاولة", dp(12), dp(30), paint);
        drawToolbarButton(c, getWidth() - dp(174), "إعادة");
        drawToolbarButton(c, getWidth() - dp(112), tableLocked ? "فتح" : "قفل");
        drawToolbarButton(c, getWidth() - dp(55), "إخفاء");

        // Dim the area outside the calibrated table, while keeping the game visible.
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
        paint.setColor(0xFFFFD36A);
        c.drawRoundRect(table, dp(7), dp(7), paint);
        paint.setStyle(Paint.Style.FILL);
        float pocket = dp(9);
        float[][] holes = {{table.left,table.top},{table.centerX(),table.top},{table.right,table.top},
            {table.left,table.bottom},{table.centerX(),table.bottom},{table.right,table.bottom}};
        paint.setColor(0xEE050709);
        for (float[] p : holes) c.drawCircle(p[0], p[1], pocket, paint);

        drawAimAndRebounds(c);
        if (!tableLocked) {
            paint.setColor(Color.WHITE);
            float s = dp(7);
            for (float[] p : new float[][]{{table.left,table.top},{table.right,table.top},{table.left,table.bottom},{table.right,table.bottom}}) {
                c.drawRect(p[0]-s,p[1]-s,p[0]+s,p[1]+s,paint);
            }
        }
        drawHandle(c, startX, startY, 0xFFFF5252, "بداية");
        drawHandle(c, endX, endY, 0xFF58E68A, "اتجاه");
        paint.setTextSize(dp(10)); paint.setColor(Color.WHITE);
        c.drawText("اسحب العلامة الحمراء لبداية التصويب والخضراء لاتجاهه", dp(12), getHeight()-dp(12), paint);
    }

    private void drawToolbarButton(Canvas c, float x, String label) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xFF35414C);
        c.drawRoundRect(x, dp(7), x + dp(52), dp(41), dp(8), dp(8), paint);
        paint.setColor(Color.WHITE); paint.setTextSize(dp(11)); paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(label, x + dp(26), dp(29), paint); paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawHandle(Canvas c, float x, float y, int color, String label) {
        paint.setColor(color); paint.setStyle(Paint.Style.FILL); c.drawCircle(x,y,dp(10),paint);
        paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
        c.drawCircle(x,y,dp(10),paint); paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(dp(10)); paint.setColor(Color.WHITE); c.drawText(label,x+dp(12),y-dp(10),paint);
    }

    private void drawAimAndRebounds(Canvas c) {
        float dx = endX-startX, dy = endY-startY;
        float len = (float)Math.hypot(dx,dy);
        if (len < 1) return;
        dx /= len; dy /= len;
        float x = endX, y = endY;
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2.4f));
        paint.setColor(0xFFFFF4D0); paint.setPathEffect(null);
        Path first = new Path(); first.moveTo(startX,startY); first.lineTo(endX,endY);
        c.drawPath(first,paint);

        for (int bounce=0; bounce<5; bounce++) {
            float tx = Float.POSITIVE_INFINITY, ty = Float.POSITIVE_INFINITY;
            int hitSide = -1;
            if (dx > .0001f) { float t=(table.right-x)/dx; if(t>dp(2)&&t<tx){tx=t;hitSide=1;} }
            if (dx < -.0001f) { float t=(table.left-x)/dx; if(t>dp(2)&&t<tx){tx=t;hitSide=1;} }
            if (dy > .0001f) { float t=(table.bottom-y)/dy; if(t>dp(2)&&t<ty){ty=t;hitSide=2;} }
            if (dy < -.0001f) { float t=(table.top-y)/dy; if(t>dp(2)&&t<ty){ty=t;hitSide=2;} }
            float t=Math.min(tx,ty);
            if (Float.isInfinite(t) || Float.isNaN(t) || t<=0) break;
            float hx=x+dx*t, hy=y+dy*t;
            paint.setColor(new int[]{0xFFFFD34E,0xFFFF914D,0xFFFF5D68,0xFFB18CFF,0xFF51D7D0}[bounce]);
            paint.setStrokeWidth(dp(2)); paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(8),dp(5)},0));
            c.drawLine(x,y,hx,hy,paint); paint.setPathEffect(null);
            x=hx; y=hy;
            if (hitSide==1) dx=-dx; else dy=-dy;
        }
        paint.setStyle(Paint.Style.FILL);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x=e.getX(), y=e.getY(), d=dp(1);
        if (e.getAction()==MotionEvent.ACTION_DOWN) {
            lastX=x; lastY=y; dragMode=NONE;
            if (y<dp(48)) {
                if (x>getWidth()-dp(62)) { actions.hide(); return true; }
                if (x>getWidth()-dp(120)) { tableLocked=!tableLocked; invalidate(); return true; }
                if (x>getWidth()-dp(180)) { startX=getWidth()*.25f; startY=getHeight()*.53f; endX=getWidth()*.68f; endY=getHeight()*.42f; invalidate(); return true; }
            }
            if (distance(x,y,startX,startY)<handleRadius*1.8f) dragMode=START;
            else if(distance(x,y,endX,endY)<handleRadius*1.8f) dragMode=END;
            else if(!tableLocked && nearTableCorner(x,y)) { dragMode=TABLE; tableCorner=nearestCorner(x,y); }
            else if(!tableLocked && table.contains(x,y)) dragMode=MOVE_TABLE;
            return true;
        }
        if(e.getAction()==MotionEvent.ACTION_MOVE) {
            float dx=x-lastX, dy=y-lastY;
            if(dragMode==START){startX=x;startY=y;}
            else if(dragMode==END){endX=x;endY=y;}
            else if(dragMode==TABLE){
                if(tableCorner==0){table.left+=dx;table.top+=dy;}
                else if(tableCorner==1){table.right+=dx;table.top+=dy;}
                else if(tableCorner==2){table.left+=dx;table.bottom+=dy;}
                else {table.right+=dx;table.bottom+=dy;}
                if(table.width()<dp(140)) table.right=table.left+dp(140);
                if(table.height()<dp(100)) table.bottom=table.top+dp(100);
            }
            else if(dragMode==MOVE_TABLE){table.offset(dx,dy);}
            lastX=x;lastY=y;invalidate();return true;
        }
        if(e.getAction()==MotionEvent.ACTION_UP){dragMode=NONE;return true;}
        return true;
    }

    private int nearestCorner(float x,float y) {
        float[][] points={{table.left,table.top},{table.right,table.top},{table.left,table.bottom},{table.right,table.bottom}};
        int best=0; float bestDistance=Float.MAX_VALUE;
        for(int i=0;i<points.length;i++){float v=distance(x,y,points[i][0],points[i][1]);if(v<bestDistance){bestDistance=v;best=i;}}
        return best;
    }
    private boolean nearTableCorner(float x,float y) {
        float r=handleRadius*2;
        return distance(x,y,table.left,table.top)<r || distance(x,y,table.right,table.top)<r ||
            distance(x,y,table.left,table.bottom)<r || distance(x,y,table.right,table.bottom)<r;
    }
    private float distance(float x1,float y1,float x2,float y2){return (float)Math.hypot(x1-x2,y1-y2);}
}
