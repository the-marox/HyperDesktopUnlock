/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Salva gli screenshot del monitor nella Galleria per conto del sistema.
 *
 * Gira nel processo dell'APP del modulo (non dentro un hook): niente API Xposed
 * qui. system_server cattura il monitor, apre
 *   content://<pacchetto>.shots/<nome>.png  in scrittura
 * e ci scrive il PNG; noi lo copiamo in MediaStore (Pictures/Screenshots).
 * Serve perche' MediaProvider rifiuta di aprire file in scrittura al processo
 * di sistema, mentre un'app normale puo' contribuire immagini senza permessi.
 *
 * Il provider e' esportato ma protetto da INTERACT_ACROSS_USERS_FULL, che le
 * app di terze parti non possono avere; il sistema la supera sempre.
 */
public class ShotProvider extends ContentProvider {

    private static final String TAG = "HyperDesktop";

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode == null || !mode.contains("w")) throw new FileNotFoundException("solo scrittura");
        final String name = sanitize(uri.getLastPathSegment());
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            final ParcelFileDescriptor readSide = pipe[0];
            new Thread(new Runnable() {
                @Override public void run() { save(name, readSide); }
            }, "HyperDesktop-shot").start();
            return pipe[1];
        } catch (IOException e) {
            throw new FileNotFoundException("pipe: " + e);
        }
    }

    private void save(String name, ParcelFileDescriptor readSide) {
        ContentResolver cr = getContext().getContentResolver();
        Uri item = null;
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(readSide)) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Screenshots");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            item = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (item == null) throw new IOException("MediaStore insert ha restituito null");
            try (OutputStream out = cr.openOutputStream(item)) {
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(item, v, null, null);
            Log.i(TAG, "[shot] salvato Pictures/Screenshots/" + name);
        } catch (Throwable t) {
            Log.e(TAG, "[shot] salvataggio fallito", t);
            if (item != null) try { cr.delete(item, null, null); } catch (Throwable ignored) {}
        }
    }

    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) s = "Screenshot_monitor.png";
        s = s.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!s.endsWith(".png")) s += ".png";
        return s;
    }

    // ---- il provider serve solo per openFile ----
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return "image/png"; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
