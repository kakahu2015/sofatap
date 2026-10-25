package com.chekusu.sofatap.tv;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;

/** Local instrumentation fixture only; absent from release builds and not exported. */
public final class InputFixtureActivity extends Activity {
    Button click;
    ScrollView scroll;
    int clicks;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        click = new Button(this); click.setText("Fixture clicks: 0");
        click.setOnClickListener(view -> click.setText("Fixture clicks: " + (++clicks)));
        root.addView(click, new LinearLayout.LayoutParams(-1, 140));
        scroll = new ScrollView(this); LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 80; i++) { TextView row = new TextView(this); row.setText("Fixture row " + i); row.setTextSize(24); row.setPadding(32, 24, 32, 24); content.addView(row); }
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }
}
