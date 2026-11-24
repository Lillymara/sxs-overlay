package com.lmthings.sxsoverlay;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;

/**
 * Opens the phone's picker for an {@code <input type="file">} on the site, and hands the picked files
 * back to the overlay's WebView. The overlay is a service, and only an activity can show a picker.
 *
 * Images use Android's photo picker (screenshots are right there), whose read access lasts as long
 * as the app runs, so the page can read the files after this activity closes.
 */
public class PickerActivity extends Activity {
    static final String EXTRA_MULTIPLE = "multiple";
    static final String EXTRA_ACCEPT = "accept";
    private boolean delivered;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // Recreated while the picker was open (rotation): the picker's result still comes here.
        if (saved != null) return;
        boolean multiple = getIntent().getBooleanExtra(EXTRA_MULTIPLE, false);
        String[] accept = getIntent().getStringArrayExtra(EXTRA_ACCEPT);
        try {
            startActivityForResult(pickIntent(multiple, accept), 1);
        } catch (Exception e) {
            deliver(null);
            finish();
        }
    }

    private Intent pickIntent(boolean multiple, String[] accept) {
        String[] types = cleanTypes(accept);
        boolean imagesOnly = types.length > 0;
        for (String t : types) imagesOnly &= t.startsWith("image/");
        if (imagesOnly && Build.VERSION.SDK_INT >= 33) {
            Intent i = new Intent(MediaStore.ACTION_PICK_IMAGES);
            if (multiple) i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
            return i;
        }
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(types.length == 1 ? types[0] : "*/*");
        if (types.length > 1) i.putExtra(Intent.EXTRA_MIME_TYPES, types);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        return i;
    }

    /** The page's accept list, as MIME types ("image/*"); file extensions are dropped. */
    private static String[] cleanTypes(String[] accept) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (accept != null) {
            for (String a : accept) {
                if (a == null) continue;
                for (String t : a.split(",")) {
                    t = t.trim();
                    if (t.contains("/")) out.add(t);
                }
            }
        }
        return out.toArray(new String[0]);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        Uri[] uris = null;
        if (result == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                uris = new Uri[clip.getItemCount()];
                for (int i = 0; i < uris.length; i++) uris[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                uris = new Uri[] {data.getData()};
            }
        }
        deliver(uris);
        finish();
    }

    @Override
    protected void onDestroy() {
        // Closed some other way: the page still needs an answer, or its file input stops working.
        if (isFinishing()) deliver(null);
        super.onDestroy();
    }

    private void deliver(Uri[] uris) {
        if (delivered) return;
        delivered = true;
        OverlayService s = OverlayService.current;
        if (s != null) s.deliverFiles(uris);
    }
}
