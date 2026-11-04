package com.personal.sonyvegasvideo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.TextView;

/** Small vector toolbar icons, drawn locally rather than copied bitmap resources. */
public final class ClassicIcon extends View {
    public final String kind;
    private final Paint paint = new Paint(3);
    private final Glyph glyph;
    private boolean selected;
    public ClassicIcon(Context context, String kind, String description) {
        super(context); this.kind = kind;
        glyph = new Glyph(kind);
        setContentDescription(description); setTooltipText(description); setFocusable(true); setClickable(true);
        setMinimumWidth(dp(36)); setMinimumHeight(dp(36));
    }
    public void select(boolean value) { selected = value; setSelected(value); invalidate(); }
    @Override public void setEnabled(boolean enabled) { super.setEnabled(enabled); invalidate(); }
    @Override protected void onDraw(Canvas canvas) {
        if (selected || isPressed()) {
            paint.setColor(0xffbacadc); canvas.drawRect(1, 1, getWidth()-1, getHeight()-1, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(0xff425c7c);
            canvas.drawRect(1, 1, getWidth()-1, getHeight()-1, paint); paint.setStyle(Paint.Style.FILL);
        }
        canvas.save();
        float scale = dp(21) / 24f;
        canvas.translate((getWidth()-24*scale)/2f, (getHeight()-24*scale)/2f);
        canvas.scale(scale, scale);
        glyph.draw(canvas, isEnabled() ? 255 : 85);
        canvas.restore();
    }

    static Drawable drawable(Context context, String kind) {
        return new GlyphDrawable(kind, pixels(context, 24));
    }

    static void decorate(TextView label, String kind, int sizeDp) {
        int size = pixels(label.getContext(), sizeDp);
        decorate(label, new GlyphDrawable(kind, size), sizeDp, sizeDp);
    }

    static void decorate(TextView label, Drawable icon, int widthDp, int heightDp) {
        Context context = label.getContext();
        icon.setBounds(0, 0, pixels(context, widthDp), pixels(context, heightDp));
        label.setCompoundDrawablesRelative(icon, null, null, null);
        label.setCompoundDrawablePadding(pixels(context, 5));
    }

    private int dp(int value) { return pixels(getContext(), value); }

    private static int pixels(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class GlyphDrawable extends Drawable {
        private final Glyph glyph;
        private final int size;
        private int alpha = 255;

        GlyphDrawable(String kind, int size) {
            glyph = new Glyph(kind);
            this.size = size;
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float scale = Math.min(bounds.width(), bounds.height()) / 24f;
            canvas.save();
            canvas.translate(bounds.exactCenterX() - 12 * scale, bounds.exactCenterY() - 12 * scale);
            canvas.scale(scale, scale);
            glyph.draw(canvas, alpha);
            canvas.restore();
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { glyph.paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class Glyph {
        private static final int INK = 0xff365574;
        private static final int GOLD = 0xffcf9841;
        private static final int LIGHT = 0xffdbe8ee;
        private final String kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int alpha;

        Glyph(String kind) { this.kind = kind; }

        void draw(Canvas canvas, int opacity) {
            alpha = opacity;
            switch (kind) {
                case "new": rect(canvas,5,2,18,22,0xffe7edf2); line(canvas,5,2,18,2,0xff425367); line(canvas,5,2,5,22,0xff425367); line(canvas,5,22,18,22,0xff425367); line(canvas,18,2,18,22,0xff425367); line(canvas,8,7,15,7,0xff8095a9); break;
                case "open": rect(canvas,2,6,10,10,0xff8b6c1e); rect(canvas,2,9,21,21,0xffd4ad45); path(canvas,new float[]{3,12,23,12,19,21,1,21},0xffefcf75); line(canvas,3,12,23,12,0xff796525); break;
                case "save": rect(canvas,3,2,21,22,0xff3e5b7a); rect(canvas,7,2,17,10,0xffd9dfe5); rect(canvas,7,14,18,22,0xffe9edf0); rect(canvas,14,3,16,8,0xff49617b); break;
                case "play": path(canvas,new float[]{6,3,21,12,6,21},0xff243c2c); break;
                case "pause": rect(canvas,5,4,10,20,0xff263a43); rect(canvas,14,4,19,20,0xff263a43); break;
                case "stop": rect(canvas,5,5,19,19,0xff293941); break;
                case "begin": line(canvas,4,3,4,21,0xff293941); path(canvas,new float[]{19,4,7,12,19,20},0xff293941); break;
                case "end": line(canvas,20,3,20,21,0xff293941); path(canvas,new float[]{5,4,17,12,5,20},0xff293941); break;
                case "back": line(canvas,5,3,5,21,0xff293941); path(canvas,new float[]{18,5,8,12,18,19},0xff293941); break;
                case "forward": line(canvas,19,3,19,21,0xff293941); path(canvas,new float[]{6,5,16,12,6,19},0xff293941); break;
                case "undo": path(canvas,new float[]{3,8,9,2,9,6,18,6,21,10,20,16,16,20,12,20,17,15,17,11,9,11,9,15},0xff3e6189); break;
                case "redo": canvas.save(); canvas.translate(24,0);canvas.scale(-1,1);path(canvas,new float[]{3,8,9,2,9,6,18,6,21,10,20,16,16,20,12,20,17,15,17,11,9,11,9,15},0xff3e6189);canvas.restore();break;
                case "render": rect(canvas,2,3,19,20,0xff526780); for(int y=5;y<20;y+=5){rect(canvas,3,y,5,y+2,0xffdde5eb);rect(canvas,16,y,18,y+2,0xffdde5eb);} path(canvas,new float[]{9,9,16,9,16,5,23,12,16,19,16,15,9,15},0xff7c342b);break;
                case "loop": paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(color(0xff345c7b));canvas.drawArc(4,4,20,20,35,285,false,paint);paint.setStyle(Paint.Style.FILL);path(canvas,new float[]{17,2,23,7,16,10},0xff345c7b);break;
                case "marker": line(canvas,6,3,6,22,0xff67401d);path(canvas,new float[]{7,3,21,3,16,10,7,10},0xffd49345);break;
                case "zoomIn": case "zoomOut": circle(canvas,10,10,7,0xffd7e3e8);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(color(0xff365574));canvas.drawCircle(10,10,7,paint);paint.setStyle(Paint.Style.FILL);line(canvas,15,15,22,22,0xff365574);line(canvas,6,10,14,10,0xff365574);if(kind.equals("zoomIn"))line(canvas,10,6,10,14,0xff365574);break;
                case "fit": line(canvas,3,3,9,3,0xff365574);line(canvas,3,3,3,21,0xff365574);line(canvas,3,21,9,21,0xff365574);line(canvas,21,3,15,3,0xff365574);line(canvas,21,3,21,21,0xff365574);line(canvas,21,21,15,21,0xff365574);path(canvas,new float[]{8,8,16,12,8,16},0xff365574);break;
                case "mute": path(canvas,new float[]{3,9,8,9,14,4,14,20,8,15,3,15},0xff4b5360);line(canvas,17,9,23,15,0xff8b362f);line(canvas,23,9,17,15,0xff8b362f);break;
                case "select": path(canvas,new float[]{5,2,5,20,10,15,14,23,18,21,14,14,21,14},0xff27343e);break;
                case "cut": circle(canvas,6,18,3,0xff9e5e54);circle(canvas,18,18,3,0xff9e5e54);line(canvas,7,16,19,2,0xff333e49);line(canvas,17,16,5,2,0xff333e49);break;
                case "delete": line(canvas,5,5,19,19,0xff963d32);line(canvas,19,5,5,19,0xff963d32);break;
                case "help": circle(canvas,12,12,10,0xff536f91); paint.setColor(color(Color.WHITE));paint.setTextSize(18);paint.setFakeBoldText(true);canvas.drawText("?",7,19,paint);paint.setFakeBoldText(false);break;
                case "settings": for(int i=0;i<8;i++){double a=i*Math.PI/4;line(canvas,12+(float)Math.cos(a)*6,12+(float)Math.sin(a)*6,12+(float)Math.cos(a)*10,12+(float)Math.sin(a)*10,0xff5b5d66);}circle(canvas,12,12,7,0xff6f7581);circle(canvas,12,12,3,0xffbfc3c9);break;
                case "effects":
                    line(canvas, 4, 21, 16, 9, INK);
                    line(canvas, 7, 21, 18, 10, INK);
                    spark(canvas, 17, 6, 5, GOLD);
                    spark(canvas, 7, 6, 3, INK);
                    break;
                case "brightness":
                    circle(canvas, 12, 12, 4, GOLD);
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4;
                        float x = (float) Math.cos(a), y = (float) Math.sin(a);
                        line(canvas, 12 + x * 7, 12 + y * 7, 12 + x * 10, 12 + y * 10, GOLD);
                    }
                    break;
                case "contrast": case "opacity":
                    circle(canvas, 12, 12, 8, LIGHT);
                    paint.setColor(color(INK));
                    canvas.drawArc(4, 4, 20, 20, 90, 180, true, paint);
                    break;
                case "palette":
                    circle(canvas, 8, 10, 5, 0xffc86b58);
                    circle(canvas, 16, 10, 5, GOLD);
                    circle(canvas, 12, 17, 5, 0xff598d9c);
                    break;
                case "blur":
                    circle(canvas, 12, 12, 9, 0xffb4c9d6);
                    circle(canvas, 12, 12, 6, 0xff7f9db3);
                    circle(canvas, 12, 12, 3, INK);
                    break;
                case "mosaic": case "checker":
                    for (int row = 0; row < 3; row++) {
                        for (int col = 0; col < 3; col++) {
                            int x = 3 + 6 * col, y = 3 + 6 * row;
                            rect(canvas, x, y, x + 5, y + 5, (row + col) % 2 == 0 ? INK : LIGHT);
                        }
                    }
                    break;
                case "key":
                    circle(canvas, 8, 9, 5, 0xff568f70);
                    line(canvas, 12, 13, 20, 21, 0xff568f70);
                    line(canvas, 16, 17, 19, 14, 0xff568f70);
                    break;
                case "rotate":
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(2);
                    paint.setColor(color(INK));
                    canvas.drawArc(4, 4, 20, 20, -65, 290, false, paint);
                    paint.setStyle(Paint.Style.FILL);
                    path(canvas, new float[]{19, 2, 22, 9, 14, 7}, INK);
                    break;
                case "text":
                    line(canvas, 4, 5, 20, 5, INK);
                    line(canvas, 12, 5, 12, 20, INK);
                    line(canvas, 8, 20, 16, 20, INK);
                    break;
                case "audio":
                    line(canvas, 10, 5, 10, 18, INK);
                    line(canvas, 19, 3, 19, 15, INK);
                    line(canvas, 10, 5, 19, 3, INK);
                    circle(canvas, 7, 18, 3, INK);
                    circle(canvas, 16, 15, 3, INK);
                    break;
                case "volume":
                    path(canvas, new float[]{3, 9, 8, 9, 13, 4, 13, 20, 8, 15, 3, 15}, INK);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1.5f);
                    paint.setColor(color(INK));
                    canvas.drawArc(9, 6, 21, 18, -55, 110, false, paint);
                    paint.setStyle(Paint.Style.FILL);
                    break;
                case "pan": case "sliders":
                    line(canvas, 3, 6, 21, 6, INK);
                    line(canvas, 3, 12, 21, 12, INK);
                    line(canvas, 3, 18, 21, 18, INK);
                    circle(canvas, 8, 6, 2.6f, GOLD);
                    circle(canvas, 16, 12, 2.6f, GOLD);
                    circle(canvas, 11, 18, 2.6f, GOLD);
                    break;
                case "video": case "view": case "layout":
                    rect(canvas, 2, 4, 22, 19, INK);
                    rect(canvas, 4, 6, 20, 17, LIGHT);
                    if (kind.equals("layout")) {
                        rect(canvas, 4, 6, 10, 17, 0xff86a3b9);
                        line(canvas, 10, 13, 20, 13, INK);
                    } else if (kind.equals("video")) {
                        path(canvas, new float[]{10, 8, 16, 12, 10, 16}, INK);
                    } else {
                        line(canvas, 12, 19, 12, 22, INK);
                        line(canvas, 7, 22, 17, 22, INK);
                    }
                    break;
                case "layers": case "transition": case "duplicate":
                    rect(canvas, 3, 3, 15, 15, INK);
                    rect(canvas, 5, 5, 13, 13, LIGHT);
                    rect(canvas, 9, 9, 21, 21, kind.equals("transition") ? GOLD : INK);
                    rect(canvas, 11, 11, 19, 19, LIGHT);
                    break;
                case "plus":
                    line(canvas, 12, 4, 12, 20, INK);
                    line(canvas, 4, 12, 20, 12, INK);
                    break;
                case "left": case "right":
                    if (kind.equals("right")) {
                        canvas.save();
                        canvas.translate(24, 0);
                        canvas.scale(-1, 1);
                    }
                    line(canvas, 4, 12, 21, 12, INK);
                    line(canvas, 4, 12, 11, 5, INK);
                    line(canvas, 4, 12, 11, 19, INK);
                    if (kind.equals("right")) canvas.restore();
                    break;
                case "share":
                    line(canvas, 6, 12, 17, 5, INK);
                    line(canvas, 6, 12, 17, 19, INK);
                    circle(canvas, 5, 12, 3, INK);
                    circle(canvas, 18, 5, 3, INK);
                    circle(canvas, 18, 19, 3, INK);
                    break;
                case "range":
                    line(canvas, 4, 5, 4, 19, INK);
                    line(canvas, 20, 5, 20, 19, INK);
                    line(canvas, 4, 12, 20, 12, GOLD);
                    break;
                case "list":
                    for (int y = 6; y <= 18; y += 6) {
                        circle(canvas, 4, y, 1.5f, GOLD);
                        line(canvas, 8, y, 21, y, INK);
                    }
                    break;
                case "speed":
                    line(canvas, 3, 7, 13, 12, INK);
                    line(canvas, 3, 17, 13, 12, INK);
                    line(canvas, 12, 7, 22, 12, GOLD);
                    line(canvas, 12, 17, 22, 12, GOLD);
                    break;
                case "info":
                    circle(canvas, 12, 12, 9, INK);
                    circle(canvas, 12, 7, 1.4f, LIGHT);
                    line(canvas, 12, 11, 12, 17, LIGHT);
                    break;
                case "solid": case "gradient":
                    rect(canvas, 3, 4, 21, 20, INK);
                    if (kind.equals("solid")) rect(canvas, 5, 6, 19, 18, GOLD);
                    else for (int i = 0; i < 7; i++) {
                        rect(canvas, 5 + i * 2, 6, 7 + i * 2, 18, Color.rgb(62 + i * 19, 99 + i * 9, 130 - i * 7));
                    }
                    break;
                case "check":
                    line(canvas, 4, 12, 9, 18, 0xff447657);
                    line(canvas, 9, 18, 20, 5, 0xff447657);
                    break;
                case "none":
                    circle(canvas, 12, 12, 8, LIGHT);
                    line(canvas, 7, 17, 17, 7, INK);
                    break;
                default: for(int i=0;i<3;i++)circle(canvas,5+i*7,12,1.8f,0xff36424d);
            }
        }

        private void spark(Canvas canvas, float x, float y, float r, int color) {
            path(canvas, new float[]{x, y-r, x+1.3f, y-1.3f, x+r, y, x+1.3f, y+1.3f,
                    x, y+r, x-1.3f, y+1.3f, x-r, y, x-1.3f, y-1.3f}, color);
        }

        private int color(int value) { return (value & 0x00ffffff) | (alpha << 24); }
        private void rect(Canvas c,float l,float t,float r,float b,int color){paint.setColor(color(color));c.drawRect(l,t,r,b,paint);}
        private void circle(Canvas c,float x,float y,float r,int color){paint.setColor(color(color));c.drawCircle(x,y,r,paint);}
        private void line(Canvas c,float x,float y,float x2,float y2,int color){paint.setColor(color(color));paint.setStrokeWidth(1.5f);c.drawLine(x,y,x2,y2,paint);}
        private void path(Canvas c,float[] points,int color){Path p=new Path();p.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)p.lineTo(points[i],points[i+1]);p.close();paint.setColor(color(color));c.drawPath(p,paint);}
    }
}
