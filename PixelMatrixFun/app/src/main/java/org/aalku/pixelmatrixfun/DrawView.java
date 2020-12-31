package org.aalku.pixelmatrixfun;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.RequiresApi;

/**
 * Bitmap to draw on
 */
public class DrawView extends View {
    private final Bitmap bitmap;
    private int imageWidth = 16;
    private int imageHeight = 16;
    private Rect paintRect;
    private final Paint paint = new Paint(0);
    private DrawListener onDrawListener = null;

    public DrawView(Context context) {
        this(context, null);
    }

    public DrawView(Context context, AttributeSet attrs) {
        super(context, attrs);
        bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLACK);
        paint.setAntiAlias(false);
        paint.setDither(false);
        paint.setFilterBitmap(false);
        if (isInEditMode()) {
            for (int x = 0; x < imageWidth; x++) {
                if (x > 0) {
                    bitmap.setPixel(x, x*imageHeight/imageWidth-1, Color.RED);
                }
                bitmap.setPixel(x, x*imageHeight/imageWidth, Color.GREEN);
                if (x < imageWidth - 1) {
                    bitmap.setPixel(x, x*imageHeight/imageWidth+1, Color.BLUE);
                }
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawBitmap(bitmap, null, this.paintRect, paint);
    }

    private void drawPixel(int x, int y, int width, int height) {

        boolean ok =  onDrawListener == null || onDrawListener.isDrawAllowed();
        if (!ok) {
            return;
        }

        int rx = Math.max(Math.min(imageWidth*x/width,imageHeight - 1), 0);
        int ry = Math.max(Math.min(imageHeight*y/height, imageHeight - 1), 0);
        Log.d("DRAW", String.format("[%s,%s] out of [%s,%s]", rx, ry, bitmap.getWidth(), bitmap.getHeight()));
        int color = Color.WHITE;
        int prev = bitmap.getPixel(rx, ry);
        if (prev != color) {
            ok =  onDrawListener == null || onDrawListener.notifyPixel(rx, ry, color);
            if (ok) {
                bitmap.setPixel(rx, ry, color);
                invalidate();
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {

        for (int p = 0; p < event.getPointerCount(); p++) {
            float x = event.getX(p);
            float y = event.getY(p);

            switch(event.getAction()) {
                case MotionEvent.ACTION_DOWN :
                    drawPixel((int)x, (int)y, this.getWidth(), this.getHeight());
                case MotionEvent.ACTION_MOVE :
                    drawPixel((int)x, (int)y, this.getWidth(), this.getHeight());
                case MotionEvent.ACTION_UP :
                    //touchUp();
                    invalidate();
            }
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (changed) {
            int paddingLeft = getPaddingLeft();
            int paddingTop = getPaddingTop();
            int paddingRight = getPaddingRight();
            int paddingBottom = getPaddingBottom();

            int contentWidth = getWidth() - paddingLeft - paddingRight;
            int contentHeight = getHeight() - paddingTop - paddingBottom;
            this.paintRect = new Rect(0, 0, contentWidth, contentHeight);
        }

        super.onLayout(changed, left, top, right, bottom);
    }

    public Bitmap getBitmap() {
        return Bitmap.createBitmap(bitmap);
    }

    public void setOnDrawListener(DrawListener drawListener) {
        this.onDrawListener = drawListener;
    }

    interface DrawListener {
        boolean notifyPixel(int x, int y, int color);

        boolean isDrawAllowed();
    }
}