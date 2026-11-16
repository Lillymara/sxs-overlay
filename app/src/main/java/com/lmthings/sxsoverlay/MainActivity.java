package com.lmthings.sxsoverlay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Setup screen: grant "Display over other apps", then start or stop the bubble. */
public class MainActivity extends Activity {
    private TextView status;
    private Button permission;
    private Button start;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);
        root.setBackgroundColor(Color.rgb(16, 17, 22));

        TextView title = new TextView(this);
        title.setText("SxS Overlay");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        root.addView(title);

        TextView about = new TextView(this);
        about.setText("A floating bubble over Sword x Staff. Tap it to open your SxS Tools loadouts without leaving the game. Drag the bubble anywhere; drag it to the X at the bottom to close it.");
        about.setTextColor(Color.rgb(190, 192, 200));
        about.setTextSize(15);
        about.setPadding(0, dp(8), 0, dp(20));
        root.addView(about);

        status = new TextView(this);
        status.setTextColor(Color.rgb(255, 190, 120));
        status.setTextSize(15);
        status.setPadding(0, 0, 0, dp(12));
        root.addView(status);

        permission = button("Allow display over other apps", v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        root.addView(permission);

        start = button("Start bubble", v -> {
            startForegroundService(new Intent(this, OverlayService.class));
            moveTaskToBack(true);
        });
        root.addView(start);

        root.addView(button("Stop bubble", v -> stopService(new Intent(this, OverlayService.class))));

        TextView foot = new TextView(this);
        foot.setText("Loadouts you save are kept inside this app. Game art by Boltray Games. Unofficial fan tool.");
        foot.setTextColor(Color.rgb(130, 132, 140));
        foot.setTextSize(12);
        foot.setPadding(0, dp(24), 0, 0);
        root.addView(foot);

        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean ok = Settings.canDrawOverlays(this);
        status.setText(ok ? "Ready. Tap Start bubble, then open the game." : "Step 1: allow SxS Overlay to display over other apps.");
        permission.setEnabled(!ok);
        start.setEnabled(ok);
    }

    private Button button(String text, android.view.View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
