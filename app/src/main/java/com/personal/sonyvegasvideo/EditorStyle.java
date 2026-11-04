package com.personal.sonyvegasvideo;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.TextView;

/** Shared light surfaces and touch feedback for the two editor workspaces. */
final class EditorStyle {
    static final int BACKGROUND = 0xfff3f5fa;
    static final int SURFACE = Color.WHITE;
    static final int TONAL = 0xffeef2fa;
    static final int SELECTED = 0xffdce8ff;
    static final int DIVIDER = 0xffdde4ef;
    static final int TEXT = 0xff18243b;
    static final int SECONDARY = 0xff4c5b73;
    static final int ACCENT = 0xff2459b9;
    static final int VIDEO = 0xff365da6;
    static final int AUDIO = 0xff38705f;
    static final int PREVIEW = 0xff141e2e;
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private EditorStyle() { }

    static int dp(Context context, int size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(Context context, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        return shape;
    }

    static void panel(View view) {
        GradientDrawable surface = shape(view.getContext(), SURFACE, 14);
        surface.setStroke(dp(view.getContext(), 1), DIVIDER);
        view.setBackground(surface);
    }

    static void touch(View view, int color, int radius, int inset) {
        Context context = view.getContext();
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected}, shape(context, SELECTED, radius));
        states.addState(new int[]{android.R.attr.state_focused}, shape(context, TONAL, radius));
        states.addState(new int[0], shape(context, color, radius));
        Drawable ripple = new RippleDrawable(ColorStateList.valueOf(0x252459b9),
                states, shape(context, Color.WHITE, radius));
        view.setBackground(inset == 0 ? ripple : new InsetDrawable(ripple, dp(context, inset)));
    }

    static void heading(TextView view) {
        view.setTypeface(MEDIUM);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }
}
