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
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import org.json.JSONObject;
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
 *
 * Screenshots: the site's "Choose screenshots" opens the phone's photo picker (via PickerActivity),
 * and its "Read the game screen" button (shown only in this app) grabs the screen behind the panel
 * through Android's screen capture, with the bubble and panel hidden, and hands it to the page.
 */
public class OverlayService extends Service {
    private static final String CHANNEL = "overlay";
    private static final String ACTION_STOP = "stop";
    private static final String SITE = "https://sxs.lmthings.com";
    private static final String SITE_HOST = "sxs.lmthings.com";

    /** The running service, for the helper activities to hand their results back to. */
    static OverlayService current;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> pendingFiles;
    private boolean overlaysHidden;

    // Screen reading. One permission prompt per bubble session: the projection and its virtual
    // display stay alive, with no surface (so nothing is drawn) between reads.
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private boolean capturing;
    private boolean wasTall;

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
        current = this;
        wasTall = tall(screen());
        startInForeground(false);
        addBubble();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) stopSelf();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (current == this) current = null;
        if (pendingFiles != null) pendingFiles.onReceiveValue(null);
        pendingFiles = null;
        main.removeCallbacksAndMessages(null);
        releaseProjection();
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
        // Folded or unfolded: the safe spot for the bubble is somewhere else on the other screen shape.
        boolean nowTall = tall(screen());
        if (bubbleLp != null && nowTall != wasTall) defaultBubbleSpot();
        wasTall = nowTall;
        keepBubbleOnScreen();
    }

    // ---- Foreground notification (Android needs one to keep the bubble alive) ----

    /** Screen reading needs the service to also be a "screen capture" foreground service while it's on. */
    private void startInForeground(boolean capture) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Bubble", NotificationManager.IMPORTANCE_MIN));
        PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, OverlayService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("SxS Overlay bubble is on")
                .setContentText(capture ? "Screen reading is on until you stop the bubble." : "Tap the bubble in game to open SxS Tools.")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            if (capture) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            startForeground(1, n, type);
        } else if (Build.VERSION.SDK_INT >= 29 && capture) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    // ---- Hiding the overlay (while a picker is open, or while the screen is read) ----

    /** Hidden overlays are also untouchable, so touches reach the picker or game underneath. */
    private void setOverlaysHidden(boolean hide) {
        if (overlaysHidden == hide) return;
        overlaysHidden = hide;
        hideWindow(bubble, bubbleLp, hide);
        if (panelShown) hideWindow(panel, panelLp, hide);
    }

    private void hideWindow(View v, WindowManager.LayoutParams lp, boolean hide) {
        if (v == null || lp == null) return;
        v.setVisibility(hide ? View.INVISIBLE : View.VISIBLE);
        if (hide) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (v.getParent() != null) wm.updateViewLayout(v, lp);
    }

    // ---- Picking screenshots for the site's file inputs ----

    private boolean showFileChooser(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (pendingFiles != null) pendingFiles.onReceiveValue(null);
        pendingFiles = callback;
        setOverlaysHidden(true);
        try {
            startActivity(new Intent(this, PickerActivity.class)
                    .putExtra(PickerActivity.EXTRA_MULTIPLE, params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
                    .putExtra(PickerActivity.EXTRA_ACCEPT, params.getAcceptTypes())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Exception e) {
            deliverFiles(null);
        }
        return true;
    }

    /** From PickerActivity: the picked files, or null if nothing was picked. */
    void deliverFiles(Uri[] uris) {
        main.post(() -> {
            setOverlaysHidden(false);
            if (pendingFiles != null) pendingFiles.onReceiveValue(uris);
            pendingFiles = null;
        });
    }

    // ---- Reading the game screen directly ----

    /** What the site calls. Only pages from the site get to use it. */
    private class Bridge {
        @JavascriptInterface
        public void capture() {
            main.post(() -> {
                String url = web == null ? null : web.getUrl();
                String host = url == null ? null : Uri.parse(url).getHost();
                if (SITE_HOST.equals(host)) requestCapture();
            });
        }

        @JavascriptInterface
        public int version() {
            return 1;
        }
    }

    private void requestCapture() {
        if (capturing) return;
        capturing = true;
        setOverlaysHidden(true);
        if (projection != null) {
            // Give the window manager a moment to take the bubble and panel off the screen.
            main.postDelayed(this::grabFrame, 150);
            return;
        }
        try {
            startActivity(new Intent(this, CaptureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Exception e) {
            finishCapture(null, "Android wouldn't open the screen-reading prompt.");
        }
    }

    /** From CaptureActivity: the answer to Android's "start recording or casting?" prompt. */
    void onCaptureConsent(int result, Intent data) {
        main.post(() -> {
            if (!capturing) return;
            if (result != android.app.Activity.RESULT_OK || data == null) {
                finishCapture(null, "Screen reading wasn't allowed, so nothing was read. You can still pick saved screenshots.");
                return;
            }
            try {
                startInForeground(true);
                MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
                projection = mpm.getMediaProjection(result, data);
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        // Stopped from the status bar, or the screen locked: ask again next time.
                        main.post(OverlayService.this::releaseProjection);
                    }
                }, main);
                DisplayMetrics m = screen();
                reader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2);
                display = projection.createVirtualDisplay("sxs-overlay-read", m.widthPixels, m.heightPixels, m.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null);
            } catch (Exception e) {
                releaseProjection();
                finishCapture(null, "Android wouldn't start screen reading (" + e.getClass().getSimpleName() + ").");
                return;
            }
            // Let the prompt finish closing so the game is what's on screen.
            main.postDelayed(this::grabFrame, 700);
        });
    }

    private void grabFrame() {
        if (!capturing) return;
        if (display == null) {
            finishCapture(null, "Screen reading stopped. Try again.");
            return;
        }
        DisplayMetrics m = screen();
        if (reader.getWidth() != m.widthPixels || reader.getHeight() != m.heightPixels) {
            // Turned since the last read.
            reader.close();
            reader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2);
            display.resize(m.widthPixels, m.heightPixels, m.densityDpi);
        }
        display.setSurface(reader.getSurface());
        long until = SystemClock.uptimeMillis() + 2500;
        // The first frames can still be on their way; take the newest one after a short wait.
        main.postDelayed(() -> takeLatest(until), 350);
    }

    private void takeLatest(long until) {
        if (!capturing || reader == null) return;
        Image img = null;
        try {
            img = reader.acquireLatestImage();
        } catch (Exception ignored) {
        }
        if (img == null) {
            if (SystemClock.uptimeMillis() < until) main.postDelayed(() -> takeLatest(until), 100);
            else {
                stopFrames();
                finishCapture(null, "The screen didn't come through. Try again.");
            }
            return;
        }
        Bitmap bmp;
        try {
            bmp = toBitmap(img);
        } catch (Exception | OutOfMemoryError e) {
            bmp = null;
        } finally {
            img.close();
        }
        stopFrames();
        // Back on screen straight away; the picture is packed up off the main thread.
        setOverlaysHidden(false);
        if (bmp == null) {
            finishCapture(null, "The screen couldn't be read (out of memory). Try again.");
            return;
        }
        final Bitmap shot = bmp;
        new Thread(() -> {
            String data = null;
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
                // JPEG like the phone's own screenshots: a fraction of a PNG's size, which matters
                // because the page decodes it in a WebView that's short on memory.
                shot.compress(Bitmap.CompressFormat.JPEG, 95, out);
                data = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            } catch (Exception | OutOfMemoryError ignored) {
            } finally {
                shot.recycle();
            }
            final String result = data;
            main.post(() -> finishCapture(result, result == null ? "The screen couldn't be read (out of memory). Try again." : null));
        }, "sxs-shot").start();
    }

    /** Stops drawing into the reader and drops leftover frames, so the next read can't pick up an old one. */
    private void stopFrames() {
        if (display != null) display.setSurface(null);
        if (reader == null) return;
        try {
            Image old;
            while ((old = reader.acquireNextImage()) != null) old.close();
        } catch (Exception ignored) {
        }
    }

    private static Bitmap toBitmap(Image img) {
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer buf = p.getBuffer();
        int w = img.getWidth(), h = img.getHeight();
        int stride = p.getRowStride() / p.getPixelStride();
        Bitmap padded = Bitmap.createBitmap(stride, h, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buf);
        if (stride == w) return padded;
        Bitmap exact = Bitmap.createBitmap(padded, 0, 0, w, h);
        padded.recycle();
        return exact;
    }

    /** Shows the overlay again and tells the page: {image: data URL} or {error: message}. */
    private void finishCapture(String image, String error) {
        capturing = false;
        setOverlaysHidden(false);
        if (web == null) return;
        try {
            JSONObject detail = new JSONObject();
            if (image != null) detail.put("image", image);
            else detail.put("error", error);
            web.evaluateJavascript("window.dispatchEvent(new CustomEvent('sxs-overlay-shot',{detail:" + detail + "}))", null);
        } catch (Exception ignored) {
        }
    }

    private void releaseProjection() {
        if (display != null) display.release();
        display = null;
        if (reader != null) reader.close();
        reader = null;
        MediaProjection p = projection;
        projection = null;
        if (p != null) {
            try {
                p.stop();
            } catch (Exception ignored) {
            }
            if (current == this) startInForeground(false);
        }
        if (capturing) finishCapture(null, "Screen reading stopped. Try again.");
    }

    // ---- Where the game leaves room ----
    //
    // Measured on 39 Sword x Staff screenshots (Character, Bed, Cart, Item, Garden, Loadouts, Season
    // Overview/Gameplay/Progression/Gear, events) at 1080x2340, 1440x3120 and 1848x2448 (Fold, open).
    //  - Phones: nothing is drawn in the top 5.5% of the screen right of the avatar (which takes the
    //    top-left ~20%). The header (title, Rola/currency counters, Type filter) runs from ~5.5% to ~14% (Character's outfit button),
    //    and the bottom bar (back button, tabs) from ~92% down. Everything between is game info.
    //  - Fold (open) and other squarer screens: the top strip is too thin, but a 40dp bubble on the
    //    right edge at 58% height is clear on every screen (below Character's gear icons, above the
    //    level bar and the Season screens' "Highest rating reached", beside the Item grid).

    /** Tall phone screen (the game leaves an empty strip along the top). */
    private static boolean tall(DisplayMetrics m) {
        return m.heightPixels > m.widthPixels * 1.9f;
    }

    /** Height of the empty strip along the top of a tall phone. */
    private static int topStrip(DisplayMetrics m) {
        return Math.round(m.heightPixels * 0.054f);
    }

    /** Small enough to fit inside the empty strip on phones. */
    private int bubbleSize() {
        DisplayMetrics m = screen();
        if (m.widthPixels > m.heightPixels) return dp(50);
        if (tall(m)) return Math.max(dp(36), Math.min(dp(54), topStrip(m) - dp(4)));
        return dp(40);
    }

    /** First run: in the empty top strip right of the camera, or on the clear part of the right edge. */
    private void defaultBubbleSpot() {
        DisplayMetrics m = screen();
        int size = bubbleSize();
        if (tall(m)) {
            bubbleLp.x = Math.round(m.widthPixels * 0.80f) - size / 2;
            bubbleLp.y = (topStrip(m) - size) / 2;
        } else {
            bubbleLp.x = m.widthPixels - size - dp(2);
            bubbleLp.y = Math.round(m.heightPixels * 0.58f) - size / 2;
        }
    }

    // ---- Bubble ----

    @SuppressLint("ClickableViewAccessibility")
    private void addBubble() {
        int size = bubbleSize();
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
        // New keys from 0.2.0, so everyone starts from the spot that keeps game info clear.
        if (prefs.contains("bx")) {
            bubbleLp.x = prefs.getInt("bx", 0);
            bubbleLp.y = prefs.getInt("by", 0);
        } else {
            defaultBubbleSpot();
        }

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

    /**
     * After a drag: dropped near the top of a phone, it settles into the empty top strip (clear of the
     * avatar on the left and the camera in the middle); anywhere else, it goes to the nearest side.
     */
    private void snapToEdge() {
        DisplayMetrics m = screen();
        int size = bubbleSize();
        if (tall(m) && bubbleLp.y + size / 2 < topStrip(m) * 2) {
            int strip = topStrip(m);
            float cx = bubbleLp.x + size / 2f;
            cx = Math.max(m.widthPixels * 0.27f, Math.min(cx, m.widthPixels - size / 2f - dp(10)));
            if (Math.abs(cx - m.widthPixels / 2f) < m.widthPixels * 0.07f) {
                cx = cx < m.widthPixels / 2f ? m.widthPixels * 0.42f : m.widthPixels * 0.58f;
            }
            bubbleLp.x = Math.round(cx - size / 2f);
            bubbleLp.y = (strip - size) / 2;
        } else {
            bubbleLp.x = bubbleLp.x + size / 2 < m.widthPixels / 2 ? 0 : m.widthPixels - size;
        }
        keepBubbleOnScreen();
    }

    private void keepBubbleOnScreen() {
        if (bubble == null) return;
        DisplayMetrics m = screen();
        int size = bubbleSize();
        if (bubbleLp.width != size) {
            bubbleLp.width = size;
            bubbleLp.height = size;
        }
        bubbleLp.x = Math.max(0, Math.min(bubbleLp.x, m.widthPixels - size));
        bubbleLp.y = Math.max(0, Math.min(bubbleLp.y, m.heightPixels - size));
        wm.updateViewLayout(bubble, bubbleLp);
        prefs.edit().putInt("bx", bubbleLp.x).putInt("by", bubbleLp.y).apply();
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
        float bx = bubbleLp.x + bubbleLp.width / 2f, by = bubbleLp.y + bubbleLp.height / 2f;
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
        grip.setText("⠿ SxS");
        grip.setTextColor(Color.rgb(220, 220, 228));
        grip.setTextSize(14);
        head.addView(grip, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(headButton("Loadouts", v -> web.loadUrl(SITE + "/loadouts")));
        head.addView(headButton("Level", v -> web.loadUrl(SITE + "/timer")));
        head.addView(headButton("Planner", v -> web.loadUrl(SITE + "/planner")));
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
                    prefs.edit().putInt("ppx", panelLp.x).putInt("ppy", panelLp.y).putBoolean("pmoved", true).apply();
                }
                return true;
            }
        });

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString() + " SxSOverlay/0.2");
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
        // Without this, the site's "Choose screenshots" button does nothing inside the app.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return showFileChooser(callback, params);
            }
        });
        web.addJavascriptInterface(new Bridge(), "SxSOverlay");
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

    /**
     * Landscape: a tall panel on the right. Portrait: between the game's header and its bottom bar,
     * so your currencies stay in view and Back and the tabs stay tappable with the panel open.
     */
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
            float top = tall(m) ? 0.145f : 0.10f, bottom = tall(m) ? 0.915f : 0.885f;
            panelLp.width = Math.round(m.widthPixels * 0.96f);
            panelLp.height = Math.round(m.heightPixels * (bottom - top));
        }
        if (prefs.getBoolean("pmoved", false)) {
            panelLp.x = prefs.getInt("ppx", 0);
            panelLp.y = prefs.getInt("ppy", 0);
        } else {
            panelLp.x = landscape ? m.widthPixels - panelLp.width - dp(8) : (m.widthPixels - panelLp.width) / 2;
            panelLp.y = landscape ? (m.heightPixels - panelLp.height) / 2 : Math.round(m.heightPixels * (tall(m) ? 0.145f : 0.10f));
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
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        // Positions are measured from the very top of the screen, camera cut-out included, which is
        // where the bubble's empty strip is.
        if (Build.VERSION.SDK_INT >= 30) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        else if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        return lp;
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
