package com.lmthings.sxsoverlay;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/**
 * Asks Android once per bubble session for permission to read the screen, so the site can read the
 * game screen directly instead of from a saved screenshot. Only an activity can show that prompt.
 */
public class CaptureActivity extends Activity {
    private boolean delivered;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) return;
        try {
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            startActivityForResult(mpm.createScreenCaptureIntent(), 1);
        } catch (Exception e) {
            deliver(RESULT_CANCELED, null);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        deliver(result, data);
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) deliver(RESULT_CANCELED, null);
        super.onDestroy();
    }

    private void deliver(int result, Intent data) {
        if (delivered) return;
        delivered = true;
        OverlayService s = OverlayService.current;
        if (s != null) s.onCaptureConsent(result, data);
    }
}
