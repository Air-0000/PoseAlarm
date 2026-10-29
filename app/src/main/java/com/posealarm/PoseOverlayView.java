package com.posealarm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

/**
 * 在摄像头画面上画出识别到的骨架。
 * 坐标换算与 PreviewView 的 FILL_CENTER 一致；前置摄像头的预览是镜像的，骨架也左右翻转。
 */
public class PoseOverlayView extends View {

    private static final int[][] BONES = {
            {11, 12}, {11, 13}, {13, 15}, {12, 14}, {14, 16},
            {11, 23}, {12, 24}, {23, 24},
            {23, 25}, {25, 27}, {24, 26}, {26, 28},
    };
    private static final int[] JOINTS = {0, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28};

    private final Paint bonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint jointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint jointStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float jointRadius;

    private float[][] points;
    private int imageW = 1, imageH = 1;
    private boolean mirror;
    private boolean active;

    public PoseOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        bonePaint.setStyle(Paint.Style.STROKE);
        bonePaint.setStrokeWidth(5 * d);
        bonePaint.setStrokeCap(Paint.Cap.ROUND);
        jointPaint.setColor(ContextCompat.getColor(context, R.color.ink));
        jointStroke.setStyle(Paint.Style.STROKE);
        jointStroke.setStrokeWidth(2 * d);
        jointStroke.setColor(ContextCompat.getColor(context, R.color.hazard));
        jointRadius = 5 * d;
    }

    /** points 为 null 时清空；active 为 true 时骨架变绿表示姿势到位 */
    public void setPose(float[][] points, int imageW, int imageH, boolean mirror, boolean active) {
        this.points = points;
        this.imageW = imageW;
        this.imageH = imageH;
        this.mirror = mirror;
        this.active = active;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (points == null) return;
        float scale = Math.max(getWidth() / (float) imageW, getHeight() / (float) imageH);
        float dx = (getWidth() - imageW * scale) / 2f;
        float dy = (getHeight() - imageH * scale) / 2f;
        bonePaint.setColor(ContextCompat.getColor(getContext(), active ? R.color.ok : R.color.hazard));
        for (int[] b : BONES) {
            float[] p = points[b[0]], q = points[b[1]];
            if (!visible(p) || !visible(q)) continue;
            canvas.drawLine(x(p, scale, dx), p[1] * scale + dy, x(q, scale, dx), q[1] * scale + dy, bonePaint);
        }
        for (int j : JOINTS) {
            float[] p = points[j];
            if (!visible(p)) continue;
            float cx = x(p, scale, dx), cy = p[1] * scale + dy;
            canvas.drawCircle(cx, cy, jointRadius, jointPaint);
            canvas.drawCircle(cx, cy, jointRadius, jointStroke);
        }
    }

    private float x(float[] p, float scale, float dx) {
        float x = mirror ? imageW - p[0] : p[0];
        return x * scale + dx;
    }

    private static boolean visible(float[] p) {
        return p != null && p[2] >= 0.5f;
    }
}
