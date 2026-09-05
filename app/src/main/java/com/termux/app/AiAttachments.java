package com.termux.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

/** Chat attachments (vision/image + file clip-button port).
 *
 * Hermes carries images as native message parts (Chat {@code image_url},
 * Responses {@code input_image}, Anthropic image blocks) resolved from URLs
 * or local paths with size/type guards. The app equivalent: the clip button
 * imports content URIs / typed paths into a per-session directory under
 * {@code ~/.khabeer/attachments/<sessionId>/}, downscales images at import,
 * and the runtime renders them as native parts per dialect at wire time.
 * Only lightweight refs ({name, mime, path}) persist in the transcript —
 * base64 never touches the database.
 */
public final class AiAttachments {
    private AiAttachments() {}

    public static final long MAX_IMAGE_DIMENSION = 1568;
    public static final long MAX_IMPORT_BYTES = 15L * 1024 * 1024;
    public static final int MAX_IMAGES_PER_MESSAGE = 4;

    public static File sessionDir(String sessionId) {
        String safe = TextUtils.isEmpty(sessionId) ? "pending" : sessionId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".khabeer/attachments/" + safe);
    }

    /** Copies a content URI into the session dir. Returns the attachment
     * record {name, mime, path, isImage} or null with the reason logged. */
    public static JSONObject importUri(ContentResolver resolver, Uri uri, String sessionId) {
        if (resolver == null || uri == null) return null;
        String name = displayName(resolver, uri);
        if (TextUtils.isEmpty(name)) name = "attachment-" + (System.currentTimeMillis() / 1000L);
        name = new File(name).getName();
        if (name.contains("..")) return null;
        String mime = resolver.getType(uri);
        if (TextUtils.isEmpty(mime)) mime = guessMime(name);
        try {
            File dir = sessionDir(sessionId);
            dir.mkdirs();
            File dest = uniqueFile(dir, name);
            try (InputStream in = resolver.openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(dest)) {
                if (in == null) return null;
                byte[] buf = new byte[8192];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_IMPORT_BYTES) return null;
                    out.write(buf, 0, n);
                }
            }
            return finalizeImport(dest, mime);
        } catch (Exception e) {
            return null;
        }
    }

    /** Imports an already-local file (typed path or workspace file) by
     * copying it into the session dir. */
    public static JSONObject importFile(File src, String sessionId) {
        if (src == null || !src.isFile()) return null;
        if (src.length() > MAX_IMPORT_BYTES) return null;
        String name = src.getName();
        if (name.contains("..")) return null;
        try {
            File dir = sessionDir(sessionId);
            dir.mkdirs();
            File dest = uniqueFile(dir, name);
            java.nio.file.Files.copy(src.toPath(), dest.toPath());
            return finalizeImport(dest, guessMime(name));
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject finalizeImport(File dest, String mime) {
        try {
            boolean image = isImageMime(mime) || isImageMime(guessMime(dest.getName()));
            if (image) {
                if (!downscaleImage(dest)) {
                    //noinspection ResultOfMethodCallIgnored
                    dest.delete();
                    return null;
                }
                mime = "image/jpeg";
            }
            JSONObject out = new JSONObject();
            out.put("name", dest.getName());
            out.put("mime", image ? "image/jpeg" : (TextUtils.isEmpty(mime) ? "application/octet-stream" : mime));
            out.put("path", dest.getAbsolutePath());
            out.put("isImage", image);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Downscales an image file in place (longest edge capped). Returns
     * false when the file is not a decodable image. */
    private static boolean downscaleImage(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_DIMENSION * 2) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bitmap == null) return false;
            try {
                float scale = Math.min(1.0f, (float) MAX_IMAGE_DIMENSION
                    / Math.max(bitmap.getWidth(), bitmap.getHeight()));
                Bitmap out = scale < 1.0f
                    ? Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(bitmap.getWidth() * scale)),
                        Math.max(1, Math.round(bitmap.getHeight() * scale)), true)
                    : bitmap;
                try (FileOutputStream fos = new FileOutputStream(file, false)) {
                    out.compress(Bitmap.CompressFormat.JPEG, 80, fos);
                }
                if (out != bitmap) out.recycle();
            } finally {
                bitmap.recycle();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Data URL for wire payloads, or null when the file is gone/too big. */
    public static String dataUrl(JSONObject image) {
        if (image == null) return null;
        try {
            File f = new File(image.optString("path", ""));
            if (!f.isFile() || f.length() > MAX_IMPORT_BYTES) return null;
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            String mime = image.optString("mime", "image/jpeg");
            return "data:" + mime + ";base64,"
                + android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isImageRecord(JSONObject o) {
        return o != null && (o.optBoolean("isImage", false)
            || isImageMime(o.optString("mime", "")));
    }

    public static boolean isImageMime(String mime) {
        return mime != null && mime.toLowerCase(Locale.US).startsWith("image/");
    }

    private static String guessMime(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain";
        return "application/octet-stream";
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        Cursor c = null;
        try {
            c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String path = uri.getLastPathSegment();
        return path == null ? "" : path;
    }

    private static File uniqueFile(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot);
        for (int i = 2; i < 1000; i++) {
            File next = new File(dir, stem + "-" + i + ext);
            if (!next.exists()) return next;
        }
        return new File(dir, stem + "-" + System.currentTimeMillis() + ext);
    }

    /** Moves pending imports into the real session dir once it exists. */
    public static JSONArray rehome(JSONArray images, String sessionId) {
        JSONArray out = new JSONArray();
        if (images == null) return out;
        File dest = sessionDir(sessionId);
        dest.mkdirs();
        for (int i = 0; i < images.length(); i++) {
            JSONObject img = images.optJSONObject(i);
            if (img == null) continue;
            try {
                File src = new File(img.optString("path", ""));
                File target = uniqueFile(dest, img.optString("name", src.getName()));
                if (src.isFile() && !src.getCanonicalPath().startsWith(dest.getCanonicalPath())) {
                    java.nio.file.Files.move(src.toPath(), target.toPath());
                    img.put("path", target.getAbsolutePath());
                    img.put("name", target.getName());
                }
            } catch (Exception ignored) {}
            out.put(img);
        }
        return out;
    }

    public static void removeRecord(JSONObject o) {
        if (o == null) return;
        try {
            File f = new File(o.optString("path", ""));
            File base = sessionDir("pending").getParentFile();
            if (f.isFile() && base != null
                && f.getCanonicalPath().startsWith(base.getCanonicalPath())) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Exception ignored) {}
    }

    public static JSONArray imagesOnly(JSONArray attachments) {
        JSONArray out = new JSONArray();
        if (attachments == null) return out;
        for (int i = 0; i < attachments.length() && out.length() < MAX_IMAGES_PER_MESSAGE; i++) {
            JSONObject o = attachments.optJSONObject(i);
            if (isImageRecord(o)) out.put(o);
        }
        return out;
    }
}
