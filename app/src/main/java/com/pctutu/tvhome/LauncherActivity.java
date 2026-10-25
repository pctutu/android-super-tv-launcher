package com.pctutu.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Minimal TV-safe process entry point; the full home UI is opened after launch. */
public final class LauncherActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(1024, 1024);
        TextView view = new TextView(this);
        view.setText(getString(R.string.app_name_zh));
        view.setTextColor(Color.WHITE);
        view.setTextSize(32);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundColor(Color.rgb(8, 19, 38));
        setContentView(view);
        view.postDelayed(new Runnable() { @Override public void run() {
            try { startActivity(new android.content.Intent(LauncherActivity.this, MainActivity.class)); }
            catch (Throwable ignored) { }
        }}, 250);
    }
}
