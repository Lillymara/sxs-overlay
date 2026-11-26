package com.lmthings.sxsoverlay;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.database.Cursor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Opens the phone's picker for an {@code <input type="file">} on the site, and hands the picked files
 * back to the overlay's WebView. The overlay is a service, and only an activity can show a picker.
 *
 * Images use Android's photo picker (screenshots are right there). The read access a picker grants
 * can end as soon as this activity closes, before the page gets to the files, so each picked file is
 * copied into the app's cache first and the page gets those copies.
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
        if (uris == null) {
            deliver(null);
            finish();
            return;
        }
        final Uri[] picked = uris;
        new Thread(() -> {
            Uri[] copies = copyAll(picked);
            runOnUiThread(() -> {
                deliver(copies);
                finish();
            });
        }).start();
    }

    /** Copies picked files into cache/picks (cleared each time). A file that can't be copied is passed on as is. */
    private Uri[] copyAll(Uri[] uris) {
        File dir = new File(getCacheDir(), "picks");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        dir.mkdirs();
        Uri[] out = new Uri[uris.length];
        for (int i = 0; i < uris.length; i++) {
            out[i] = uris[i];
            if (uris[i] == null) continue;
            File f = new File(dir, i + "-" + safeName(displayName(uris[i]), i));
            try (InputStream in = getContentResolver().openInputStream(uris[i]); OutputStream o = new FileOutputStream(f)) {
                if (in == null) continue;
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                out[i] = Uri.fromFile(f);
            } catch (Exception e) {
                f.delete();
            }
        }
        return out;
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String safeName(String name, int i) {
        if (name == null || name.isEmpty()) return "screenshot" + i + ".jpg";
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
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
