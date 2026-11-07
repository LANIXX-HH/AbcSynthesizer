package de.lanixx.chorstimme;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.ByteArrayInputStream;

import de.lanixx.chorstimme.core.ImageScoreReader;

/** Turns photo/scan bytes (JPEG, PNG, WebP, HEIC …) into a grey picture for the note reader. */
final class ImageDecoder {

    /** Longer side after scaling: an A4 page at about 200 dpi; enough for printed notes. */
    private static final int MAX_SIDE = 2400;

    private ImageDecoder() {
    }

    /** True if Android can decode the bytes as a picture. */
    static boolean isImage(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        return o.outWidth > 0 && o.outHeight > 0;
    }

    static ImageScoreReader.Gray decode(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        int side = Math.max(bounds.outWidth, bounds.outHeight);
        // skip whole pixels while the picture stays above the target size, then scale smoothly
        int sample = 1;
        while (side / (sample * 2) >= MAX_SIDE) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (bmp == null) throw new IllegalArgumentException("Bild konnte nicht dekodiert werden");

        // camera photos are often stored sideways with a rotation note
        int rotation = 0;
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(data));
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
                default: break;
            }
        } catch (Exception ignored) {
            // no EXIF (PNG, screenshots)
        }
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(bmp.getWidth(), bmp.getHeight()));
        if (rotation != 0 || scale < 1.0) {
            Matrix m = new Matrix();
            m.postScale((float) scale, (float) scale);
            m.postRotate(rotation);
            Bitmap t = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (t != bmp) bmp.recycle();
            bmp = t;
        }

        int w = bmp.getWidth(), h = bmp.getHeight();
        byte[] px = new byte[w * h];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            bmp.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int c = row[x];
                int a = c >>> 24;
                int r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255;
                int v = (r * 3 + g * 6 + b) / 10;
                v = (v * a + 255 * (255 - a)) / 255;           // transparent = white paper
                px[y * w + x] = (byte) v;
            }
        }
        bmp.recycle();
        return new ImageScoreReader.Gray(w, h, px);
    }
}
