package com.gathin.tvhome.weather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Hand-drawn line icons for the weather card, kept in the same thin white-on-glass language as the rest
 * of the launcher (no bitmap assets, so they stay crisp at any TV density).
 */
public class WeatherIconView extends View {

    public static final int SUN = 0;
    public static final int PARTLY_CLOUDY = 1;
    public static final int CLOUD = 2;
    public static final int FOG = 3;
    public static final int RAIN = 4;
    public static final int SNOW = 5;
    public static final int THUNDER = 6;
    public static final int TSHIRT = 7;
    public static final int COAT = 8;
    public static final int UMBRELLA = 9;
    public static final int MOON = 10;
    public static final int NIGHT_PARTLY_CLOUDY = 11;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private int kind = SUN;

    public WeatherIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(6f);
        stroke.setColor(0xFFFFFFFF);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFFFFFFF);
    }

    public void setKind(int kind) {
        if (this.kind == kind) return;
        this.kind = kind;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        if (size <= 0) return;
        canvas.save();
        canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        canvas.scale(size / 100f, size / 100f);

        switch (kind) {
            case PARTLY_CLOUDY:
                sun(canvas, 33f, 34f, 13f, 5f);
                cloud(canvas, 3f, 6f, 1f, 0xF2FFFFFF);
                break;
            case CLOUD:
                cloud(canvas, 7f, -7f, 0.9f, 0x59FFFFFF);
                cloud(canvas, 0f, 0f, 1f, 0xFFFFFFFF);
                break;
            case FOG:
                cloud(canvas, 0f, -10f, 1f, 0xF2FFFFFF);
                stroke.setStrokeWidth(5f);
                stroke.setColor(0xCCFFFFFF);
                canvas.drawLine(26f, 80f, 74f, 80f, stroke);
                canvas.drawLine(34f, 91f, 66f, 91f, stroke);
                break;
            case RAIN:
                cloud(canvas, 0f, -10f, 1f, 0xF2FFFFFF);
                drops(canvas);
                break;
            case SNOW:
                cloud(canvas, 0f, -10f, 1f, 0xF2FFFFFF);
                flake(canvas, 35f, 84f, 6f);
                flake(canvas, 55f, 90f, 6f);
                flake(canvas, 74f, 82f, 5f);
                break;
            case THUNDER:
                cloud(canvas, 0f, -12f, 1f, 0xF2FFFFFF);
                bolt(canvas);
                break;
            case TSHIRT:
                tshirt(canvas);
                break;
            case COAT:
                coat(canvas);
                break;
            case UMBRELLA:
                umbrella(canvas);
                break;
            case MOON:
                moon(canvas, 50f, 50f, 30f);
                break;
            case NIGHT_PARTLY_CLOUDY:
                moon(canvas, 34f, 33f, 22f);
                cloud(canvas, 3f, 6f, 1f, 0xF2FFFFFF);
                break;
            case SUN:
            default:
                sun(canvas, 50f, 50f, 19f, 6f);
                break;
        }
        canvas.restore();
    }

    // ---- weather glyphs --------------------------------------------------------------------------

    private void sun(Canvas c, float cx, float cy, float r, float weight) {
        stroke.setStrokeWidth(weight);
        stroke.setColor(0xFFFFFFFF);
        c.drawCircle(cx, cy, r, stroke);
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45);
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            c.drawLine(cx + cos * (r + 7), cy + sin * (r + 7),
                    cx + cos * (r + 15), cy + sin * (r + 15), stroke);
        }
    }

    /** Crescent: a filled disc with a second disc bitten out of it. */
    private void moon(Canvas c, float cx, float cy, float r) {
        path.reset();
        path.addCircle(cx, cy, r, Path.Direction.CW);
        path.addCircle(cx + r * 0.42f, cy - r * 0.30f, r * 0.86f, Path.Direction.CCW);
        path.setFillType(Path.FillType.EVEN_ODD);
        fill.setColor(0xFFFFFFFF);
        c.drawPath(path, fill);
    }

    private void drops(Canvas c) {
        stroke.setStrokeWidth(6f);
        stroke.setColor(0xCCFFFFFF);
        c.drawLine(38f, 78f, 33f, 91f, stroke);
        c.drawLine(57f, 78f, 52f, 91f, stroke);
        c.drawLine(75f, 78f, 70f, 91f, stroke);
    }

    private void flake(Canvas c, float cx, float cy, float r) {
        stroke.setStrokeWidth(4.5f);
        stroke.setColor(0xE6FFFFFF);
        for (int i = 0; i < 3; i++) {
            double a = Math.toRadians(i * 60);
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            c.drawLine(cx - cos * r, cy - sin * r, cx + cos * r, cy + sin * r, stroke);
        }
    }

    private void bolt(Canvas c) {
        path.reset();
        path.moveTo(56f, 64f);
        path.lineTo(39f, 89f);
        path.lineTo(51f, 89f);
        path.lineTo(46f, 103f);
        path.lineTo(65f, 79f);
        path.lineTo(53f, 79f);
        path.close();
        fill.setColor(0xFFFFFFFF);
        c.drawPath(path, fill);
    }

    /** Cloud as the union of three blobs over a rounded base. */
    private void cloud(Canvas c, float dx, float dy, float s, int color) {
        path.reset();
        path.addCircle(36f + dx, 62f + dy, 15f * s, Path.Direction.CW);
        path.addCircle(57f + dx, 53f + dy, 19f * s, Path.Direction.CW);
        path.addCircle(75f + dx, 63f + dy, 13f * s, Path.Direction.CW);
        rect.set(21f + dx, 62f + dy, 74f + dx, 76f + dy);
        path.addRoundRect(rect, 7f * s, 7f * s, Path.Direction.CW);
        fill.setColor(color);
        c.drawPath(path, fill);
    }

    // ---- clothing glyphs -------------------------------------------------------------------------

    private void tshirt(Canvas c) {
        stroke.setStrokeWidth(6f);
        stroke.setColor(0xFFFFFFFF);
        path.reset();
        path.moveTo(36f, 28f);
        path.lineTo(22f, 36f);
        path.lineTo(29f, 51f);
        path.lineTo(36f, 47f);
        path.lineTo(36f, 82f);
        path.lineTo(64f, 82f);
        path.lineTo(64f, 47f);
        path.lineTo(71f, 51f);
        path.lineTo(78f, 36f);
        path.lineTo(64f, 28f);
        path.quadTo(50f, 41f, 36f, 28f);
        path.close();
        c.drawPath(path, stroke);
    }

    private void coat(Canvas c) {
        stroke.setStrokeWidth(6f);
        stroke.setColor(0xFFFFFFFF);
        path.reset();
        path.moveTo(37f, 25f);
        path.lineTo(22f, 34f);
        path.lineTo(29f, 51f);
        path.lineTo(37f, 46f);
        path.lineTo(37f, 84f);
        path.lineTo(63f, 84f);
        path.lineTo(63f, 46f);
        path.lineTo(71f, 51f);
        path.lineTo(78f, 34f);
        path.lineTo(63f, 25f);
        path.quadTo(50f, 39f, 37f, 25f);
        path.close();
        c.drawPath(path, stroke);
        // Lapels and a centre seam: reads as outerwear at a glance.
        c.drawLine(50f, 36f, 43f, 50f, stroke);
        c.drawLine(50f, 36f, 57f, 50f, stroke);
        c.drawLine(50f, 50f, 50f, 84f, stroke);
    }

    private void umbrella(Canvas c) {
        stroke.setStrokeWidth(6f);
        stroke.setColor(0xFFFFFFFF);
        rect.set(20f, 24f, 80f, 82f);
        path.reset();
        path.addArc(rect, 180f, 180f);
        path.close();
        c.drawPath(path, stroke);
        c.drawLine(50f, 53f, 50f, 78f, stroke);
        rect.set(50f, 70f, 62f, 86f);
        path.reset();
        path.addArc(rect, 0f, 90f);
        c.drawPath(path, stroke);
        // Ribs.
        stroke.setStrokeWidth(4f);
        c.drawLine(50f, 28f, 33f, 53f, stroke);
        c.drawLine(50f, 28f, 67f, 53f, stroke);
    }
}
