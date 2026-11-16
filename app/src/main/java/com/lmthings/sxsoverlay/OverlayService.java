package com.lmthings.sxsoverlay;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The floating bubble and the panel it opens. The panel shows sxs.lmthings.com in a WebView, so it has
 * everything the site has (loadouts, level timer) and picks up site updates without a new app.
 *
 * The panel only takes keyboard focus while you're touching it: tap the game and focus goes back to
 * the game, so it never pauses or misses input because of the overlay.
 */
public class OverlayService extends Service {
    private static final String CHANNEL = "overlay";
    private static final String ACTION_STOP = "stop";
    private static final String SITE = "https://sxs.lmthings.com";

    private WindowManager wm;
    private SharedPreferences prefs;
    private ImageView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private TextView closeTarget;
    private WindowManager.LayoutParams closeLp;
    private PanelRoot panel;
    private WindowManager.LayoutParams panelLp;
    private WebView web;
    private boolean panelShown;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences("overlay", MODE_PRIVATE);
        startInForeground();
        addBubble();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) stopSelf();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        removeQuietly(bubble);
        removeQuietly(closeTarget);
        if (panelShown) removeQuietly(panel);
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (panelShown) {
            sizePanel();
            wm.updateViewLayout(panel, panelLp);
        }
        keepBubbleOnScreen();
    }

    // ---- Foreground notification (Android needs one to keep the bubble alive) ----

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Bubble", NotificationManager.IMPORTANCE_MIN));
        PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, OverlayService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("SxS Overlay bubble is on")
                .setContentText("Tap the bubble in game to open your loadouts.")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    // ---- Bubble ----

    @SuppressLint("ClickableViewAccessibility")
    private void addBubble() {
        int size = dp(54);
        bubble = new ImageView(this);
        bubble.setImageResource(R.drawable.bubble);
        bubble.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bubble.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        bubble.setClipToOutline(true);
        bubble.setElevation(dp(6));
        bubble.setAlpha(0.92f);
        bubble.setContentDescription("SxS Tools");

        bubbleLp = overlayParams(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = prefs.getInt("x", 0);
        bubbleLp.y = prefs.getInt("y", dp(140));

        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = bubbleLp.x;
                        startY = bubbleLp.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > slop) {
                            dragging = true;
                            showCloseTarget(true);
                        }
                        if (dragging) {
                            bubbleLp.x = startX + Math.round(dx);
                            bubbleLp.y = startY + Math.round(dy);
                            wm.updateViewLayout(bubble, bubbleLp);
                            closeTarget.setScaleX(overClose() ? 1.25f : 1f);
                            closeTarget.setScaleY(overClose() ? 1.25f : 1f);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            boolean close = overClose();
                            showCloseTarget(false);
                            if (close) {
                                stopSelf();
                                return true;
                            }
                            snapToEdge();
                        } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                            togglePanel();
                        }
                        return true;
                }
                return false;
            }
        });
        wm.addView(bubble, bubbleLp);
        keepBubbleOnScreen();
    }

    private void snapToEdge() {
        DisplayMetrics m = screen();
        int size = dp(54);
        bubbleLp.x = bubbleLp.x + size / 2 < m.widthPixels / 2 ? 0 : m.widthPixels - size;
        keepBubbleOnScreen();
    }

    private void keepBubbleOnScreen() {
        if (bubble == null) return;
        DisplayMetrics m = screen();
        int size = dp(54);
        bubbleLp.x = Math.max(0, Math.min(bubbleLp.x, m.widthPixels - size));
        bubbleLp.y = Math.max(0, Math.min(bubbleLp.y, m.heightPixels - size));
        wm.updateViewLayout(bubble, bubbleLp);
        prefs.edit().putInt("x", bubbleLp.x).putInt("y", bubbleLp.y).apply();
    }

    private void showCloseTarget(boolean show) {
        if (closeTarget == null) {
            closeTarget = new TextView(this);
            closeTarget.setText("✕");
            closeTarget.setTextColor(Color.WHITE);
            closeTarget.setTextSize(24);
            closeTarget.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Color.argb(200, 30, 30, 36));
            bg.setStroke(dp(2), Color.argb(160, 255, 255, 255));
            closeTarget.setBackground(bg);
            closeLp = overlayParams(dp(64), dp(64), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            closeLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            closeLp.y = dp(36);
        }
        if (show && closeTarget.getParent() == null) wm.addView(closeTarget, closeLp);
        if (!show) removeQuietly(closeTarget);
    }

    private boolean overClose() {
        DisplayMetrics m = screen();
        float bx = bubbleLp.x + dp(27), by = bubbleLp.y + dp(27);
        float cx = m.widthPixels / 2f, cy = m.heightPixels - dp(36) - dp(32);
        return Math.hypot(bx - cx, by - cy) < dp(70);
    }

    // ---- Panel ----

    private void togglePanel() {
        if (panelShown) hidePanel();
        else showPanel();
    }

    private void showPanel() {
        if (panel == null) buildPanel();
        sizePanel();
        wm.addView(panel, panelLp);
        panelShown = true;
        bubble.setAlpha(1f);
        // Keep the bubble above the panel so it can always close it.
        wm.removeView(bubble);
        wm.addView(bubble, bubbleLp);
    }

    private void hidePanel() {
        if (!panelShown) return;
        setPanelFocus(false);
        removeQuietly(panel);
        panelShown = false;
        bubble.setAlpha(0.92f);
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private void buildPanel() {
        panel = new PanelRoot(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(16, 17, 22));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.argb(80, 255, 255, 255));
        panel.setBackground(bg);
        panel.setClipToOutline(true);
        panel.setElevation(dp(10));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        panel.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // Header: drag it to move the panel; buttons to switch page, reload and hide.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(10), dp(4), dp(4), dp(4));
        head.setBackgroundColor(Color.rgb(28, 29, 36));
        TextView grip = new TextView(this);
        grip.setText("⠿ SxS Tools");
        grip.setTextColor(Color.rgb(220, 220, 228));
        grip.setTextSize(14);
        head.addView(grip, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(headButton("Loadouts", v -> web.loadUrl(SITE + "/loadouts")));
        head.addView(headButton("Level", v -> web.loadUrl(SITE + "/timer")));
        head.addView(headButton("↻", v -> web.reload()));
        head.addView(headButton("—", v -> hidePanel()));
        col.addView(head, new LinearLayout.LayoutParams(-1, dp(40)));
        head.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = panelLp.x;
                    startY = panelLp.y;
                } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    DisplayMetrics m = screen();
                    panelLp.x = Math.max(0, Math.min(m.widthPixels - panelLp.width, startX + Math.round(e.getRawX() - downX)));
                    panelLp.y = Math.max(0, Math.min(m.heightPixels - dp(60), startY + Math.round(e.getRawY() - downY)));
                    wm.updateViewLayout(panel, panelLp);
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    prefs.edit().putInt("px", panelLp.x).putInt("py", panelLp.y).putBoolean("moved", true).apply();
                }
                return true;
            }
        });

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString() + " SxSOverlay/0.1");
        web.setBackgroundColor(Color.rgb(16, 17, 22));
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String host = u.getHost() == null ? "" : u.getHost();
                if (host.equals("sxs.lmthings.com") || host.equals("l.lmthings.com")) return false;
                // Anything else (Discord, other sites) opens in the phone's browser.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    hidePanel();
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        col.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        web.loadUrl(SITE + "/loadouts");
    }

    private TextView headButton(String text, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(40, 255, 255, 255));
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    /** Landscape: a tall panel on the right. Portrait: most of the screen, from the top. */
    private void sizePanel() {
        if (panelLp == null) {
            panelLp = overlayParams(0, 0, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
            panelLp.gravity = Gravity.TOP | Gravity.START;
            panelLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        }
        DisplayMetrics m = screen();
        boolean landscape = m.widthPixels > m.heightPixels;
        if (landscape) {
            panelLp.width = Math.min(m.widthPixels - dp(70), Math.max(dp(380), Math.round(m.widthPixels * 0.5f)));
            panelLp.height = Math.round(m.heightPixels * 0.94f);
        } else {
            panelLp.width = Math.round(m.widthPixels * 0.96f);
            panelLp.height = Math.round(m.heightPixels * 0.75f);
        }
        if (prefs.getBoolean("moved", false)) {
            panelLp.x = prefs.getInt("px", 0);
            panelLp.y = prefs.getInt("py", 0);
        } else {
            panelLp.x = landscape ? m.widthPixels - panelLp.width - dp(8) : (m.widthPixels - panelLp.width) / 2;
            panelLp.y = landscape ? (m.heightPixels - panelLp.height) / 2 : dp(32);
        }
        panelLp.x = Math.max(0, Math.min(panelLp.x, m.widthPixels - panelLp.width));
        panelLp.y = Math.max(0, Math.min(panelLp.y, m.heightPixels - dp(60)));
    }

    /** Focus (for typing, and the Back key) only while the panel is being used. */
    private void setPanelFocus(boolean focus) {
        if (panelLp == null) return;
        boolean has = (panelLp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
        if (has == focus) return;
        if (focus) panelLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else panelLp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (panelShown) wm.updateViewLayout(panel, panelLp);
    }

    /** The panel's root: hands focus to the panel when touched, back to the game when you touch outside. */
    private class PanelRoot extends FrameLayout {
        PanelRoot(Context c) {
            super(c);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                setPanelFocus(false);
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) setPanelFocus(true);
            return super.dispatchTouchEvent(e);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) {
                    if (web != null && web.canGoBack()) web.goBack();
                    else hidePanel();
                }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }

    // ---- Helpers ----

    private WindowManager.LayoutParams overlayParams(int w, int h, int flags) {
        return new WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
    }

    private DisplayMetrics screen() {
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return m;
    }

    private void removeQuietly(View v) {
        if (v == null || v.getParent() == null) return;
        try {
            wm.removeView(v);
        } catch (Exception ignored) {
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
