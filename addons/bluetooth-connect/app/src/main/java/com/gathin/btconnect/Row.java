package com.gathin.btconnect;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One headphone/speaker row in the frosted Bluetooth popup: name and status/battery on the left, action on the right.
 */
final class Row extends LinearLayout {
    private final TextView value;
    private final TextView desc;

    Row(Context context, String name, String description, boolean highlightGreen, Runnable onClick) {
        super(context);
        float dp = context.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int ph = Math.round(16 * dp), pv = Math.round(10 * dp);
        setPadding(ph, pv, ph, pv);
        setBackgroundResource(R.drawable.row_background);
        setFocusable(onClick != null);
        setClickable(onClick != null);
        if (onClick != null) setOnClickListener(v -> onClick.run());

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(VERTICAL);
        TextView label = new TextView(context);
        label.setText(name);
        label.setTextSize(15.5f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(0xF5FFFFFF);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(label);
        desc = new TextView(context);
        desc.setTextSize(12f);
        desc.setTextColor(highlightGreen ? 0xFF4ADE80 : 0x99FFFFFF);
        desc.setSingleLine(true);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        desc.setPadding(0, Math.round(2 * dp), Math.round(12 * dp), 0);
        text.addView(desc);
        setDetail(description);
        addView(text, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        value = new TextView(context);
        value.setTextSize(13.5f);
        value.setTextColor(highlightGreen ? 0xFF4ADE80 : 0xCCFFFFFF);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setGravity(Gravity.END);
        value.setMinWidth(Math.round(80 * dp));
        addView(value, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    private void setDetail(String d) {
        desc.setText(d == null ? "" : d);
        desc.setVisibility(d == null || d.isEmpty() ? GONE : VISIBLE);
    }

    void setValue(String v) {
        value.setText(v);
    }
}
