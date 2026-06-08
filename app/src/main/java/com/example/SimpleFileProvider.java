package com.example;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

public class SimpleFileProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file = resolveFile(uri);
        if (file == null || !file.exists()) return null;
        if (projection == null) {
            projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        }
        MatrixCursor cursor = new MatrixCursor(projection, 1);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            switch (projection[i]) {
                case OpenableColumns.DISPLAY_NAME: row[i] = file.getName(); break;
                case OpenableColumns.SIZE:        row[i] = file.length(); break;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        File file = resolveFile(uri);
        if (file == null) return null;
        String ext = MimeTypeMap.getFileExtensionFromUrl(file.getName());
        if (ext == null || ext.isEmpty()) return "application/octet-stream";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolveFile(uri);
        if (file == null) throw new FileNotFoundException("No file for: " + uri);
        int modeBits = parseMode(mode);
        return ParcelFileDescriptor.open(file, modeBits);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }

    // ── helpers ──────────────────────────────────────────────

    /** Build a content:// URI for a file under the app's cache directory. */
    public static Uri getUriForFile(Context context, File file) {
        String authority = context.getPackageName() + ".fileprovider";
        String cacheRoot = context.getCacheDir().getAbsolutePath();
        String absPath   = file.getAbsolutePath();
        if (!absPath.startsWith(cacheRoot)) {
            throw new IllegalArgumentException("File not under cache dir: " + absPath);
        }
        String rel = absPath.substring(cacheRoot.length());
        if (rel.startsWith("/")) rel = rel.substring(1);
        return new Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendEncodedPath(Uri.encode(rel, "/"))
                .build();
    }

    private File resolveFile(Uri uri) {
        String path = uri.getPath();
        if (path == null || path.isEmpty()) return null;
        if (path.startsWith("/")) path = path.substring(1);
        return new File(getContext().getCacheDir(), path);
    }

    private static int parseMode(String mode) {
        if (mode == null) return ParcelFileDescriptor.MODE_READ_ONLY;
        switch (mode) {
            case "r":   return ParcelFileDescriptor.MODE_READ_ONLY;
            case "w":
            case "wt":  return ParcelFileDescriptor.MODE_WRITE_ONLY
                                | ParcelFileDescriptor.MODE_CREATE
                                | ParcelFileDescriptor.MODE_TRUNCATE;
            case "rw":  return ParcelFileDescriptor.MODE_READ_WRITE
                                | ParcelFileDescriptor.MODE_CREATE;
            case "rwt": return ParcelFileDescriptor.MODE_READ_WRITE
                                | ParcelFileDescriptor.MODE_CREATE
                                | ParcelFileDescriptor.MODE_TRUNCATE;
        }
        return ParcelFileDescriptor.MODE_READ_ONLY;
    }
}
