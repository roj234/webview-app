package com.example;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class TopProgressBar extends View {
    private final Paint paint;
    private int progress = 0;

    public TopProgressBar(Context context) {
        this(context, null);
    }

    public TopProgressBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setStrokeWidth(3f * getResources().getDisplayMetrics().density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);
    }

    public void setBarColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    public void setProgress(int value) {
        progress = Math.min(100, Math.max(0, value));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (progress <= 0) return;
        float cap = paint.getStrokeWidth() / 2f;
        float endX = Math.max(cap, getMeasuredWidth() * progress / 100f);
        canvas.drawLine(cap, getMeasuredHeight() / 2f, endX, getMeasuredHeight() / 2f, paint);
    }
}
