package com.personal.sonyvegasvideo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Static demo frames cut from one shared atlas. */
final class EffectPreview extends Drawable {
    private static final int WIDTH = 144;
    private static final int HEIGHT = 90;
    private static final int COLUMNS = 6;
    private static Bitmap atlas;

    private final Bitmap bitmap;
    private final Rect source = new Rect();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private int tile;
    private float strength = 1;

    EffectPreview(Context context, int tile) {
        bitmap = loadAtlas(context);
        this.tile = tile;
    }

    private static synchronized Bitmap loadAtlas(Context context) {
        if (atlas == null) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            atlas = BitmapFactory.decodeResource(context.getResources(), R.drawable.effect_previews, options);
        }
        return atlas;
    }

    static int transitionTile(int selection) { return selection == 0 ? 0 : 20 + selection; }
    static int presetTile(int selection) { return selection == 0 ? 0 : 24 + selection; }

    void setTile(int tile) {
        this.tile = tile;
        invalidateSelf();
    }

    void setStrength(float strength) {
        this.strength = strength;
        invalidateSelf();
    }

    @Override public void draw(Canvas canvas) {
        if (bitmap == null) return;
        int alpha = paint.getAlpha();
        if (strength < 1 && tile != 0) {
            drawTile(canvas, 0);
            paint.setAlpha(Math.round(alpha * strength));
        }
        drawTile(canvas, tile);
        paint.setAlpha(alpha);
    }

    private void drawTile(Canvas canvas, int index) {
        int left = (index % COLUMNS) * WIDTH;
        int top = (index / COLUMNS) * HEIGHT;
        source.set(left, top, left + WIDTH, top + HEIGHT);
        canvas.drawBitmap(bitmap, source, getBounds(), paint);
    }

    @Override public int getIntrinsicWidth() { return WIDTH; }
    @Override public int getIntrinsicHeight() { return HEIGHT; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
