package com.bbu.sbl;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The launcher's own dialog: a glass panel with a title, text, a list of choices and pill buttons, in the
 * same look as the rest of the home screen, driven entirely by the D-pad. Views are added in the order the
 * methods are called: title, message and input first, then the items, then the buttons.
 */
final class TvDialog {
    private final Activity activity;
    private final Dialog dialog;
    private final LinearLayout panel;
    private final LinearLayout itemBox;
    private final LinearLayout buttonBox;
    private final float density;
    private EditText input;

    TvDialog(Activity activity) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        int pad = (int) (28 * density);
        panel.setPadding(pad, (int) (26 * density), pad, (int) (22 * density));

        itemBox = new LinearLayout(activity);
        itemBox.setOrientation(LinearLayout.VERTICAL);
        buttonBox = new LinearLayout(activity);
        buttonBox.setOrientation(LinearLayout.HORIZONTAL);
        buttonBox.setGravity(Gravity.END);

        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (560 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
    }

    TvDialog title(CharSequence text) {
        TextView t = text(text, 24, 400, activity.getColor(R.color.ink_ff));
        panel.addView(t, params(0));
        return this;
    }

    TvDialog message(CharSequence text) {
        TextView t = text(text, 15, 400, activity.getColor(R.color.ink_cc));
        t.setLineSpacing(0f, 1.15f);
        panel.addView(t, params(10));
        return this;
    }

    /** A single-line text field; read its text with the returned view. */
    EditText input(CharSequence hint) {
        input = new EditText(activity);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextColor(activity.getColor(R.color.ink_ff));
        input.setHintTextColor(activity.getColor(R.color.ink_66));
        input.setTextSize(16f);
        input.setTypeface(activity.getResources().getFont(R.font.outfit));
        input.setBackgroundResource(R.drawable.input_field_bg);
        int p = (int) (14 * density);
        input.setPadding(p, p, p, p);
        panel.addView(input, params(14));
        return input;
    }

    /** A row in the list; the dialog closes, then the action runs. */
    TvDialog item(CharSequence label, Runnable action) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_setting, itemBox, false);
        ((TextView) row.findViewById(R.id.sTitle)).setText(label);
        row.setOnClickListener(v -> {
            dialog.dismiss();
            if (action != null) action.run();
        });
        itemBox.addView(row);
        return this;
    }

    /** A pill button. The last button added is the main one and takes focus first. */
    TvDialog button(CharSequence label, Runnable action) {
        TextView b = new TextView(activity, null, 0, R.style.InputPill);
        b.setText(label);
        b.setOnClickListener(v -> {
            dialog.dismiss();
            if (action != null) action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int) (38 * density));
        lp.setMarginStart((int) (10 * density));
        buttonBox.addView(b, lp);
        return this;
    }

    /** Called when the dialog closes in any way (a button, Back). */
    TvDialog onDismiss(Runnable r) {
        dialog.setOnDismissListener(d -> r.run());
        return this;
    }

    void show() {
        if (itemBox.getChildCount() > 0) panel.addView(itemBox, params(14));
        if (buttonBox.getChildCount() > 0) panel.addView(buttonBox, params(16));
        dialog.show();
        if (itemBox.getChildCount() > 0) itemBox.getChildAt(0).requestFocus();
        else if (input != null) input.requestFocus();
        else if (buttonBox.getChildCount() > 0) buttonBox.getChildAt(buttonBox.getChildCount() - 1).requestFocus();
    }

    private TextView text(CharSequence text, int sp, int weight, int color) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(activity.getResources().getFont(R.font.outfit));
        t.setFontVariationSettings("'wght' " + weight);
        return t;
    }

    private LinearLayout.LayoutParams params(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (topDp * density);
        return lp;
    }
}
