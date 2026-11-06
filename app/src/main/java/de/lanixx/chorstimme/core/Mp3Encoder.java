package de.lanixx.chorstimme.core;

import java.io.ByteArrayOutputStream;

/** MP3 encoding with LAME (native library "chorlame", built from app/src/main/cpp). */
public final class Mp3Encoder {

    static {
        System.loadLibrary("chorlame");
    }

    private static native long nativeInit(int sampleRate, int channels, int kbps);
    private static native int nativeEncode(long handle, short[] pcm, int frames, byte[] out);
    private static native int nativeFlush(long handle, byte[] out);
    private static native void nativeClose(long handle);

    private Mp3Encoder() {}

    public interface Progress {
        void update(int percent);
    }

    /** @param interleaved stereo samples (L, R, L, R, ...) */
    public static byte[] encodeStereo(short[] interleaved, int sampleRate, int kbps, Progress progress) {
        long h = nativeInit(sampleRate, 2, kbps);
        if (h == 0) throw new IllegalStateException("LAME konnte nicht gestartet werden");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(interleaved.length / 5);
            int frames = interleaved.length / 2;
            int chunk = 8192;
            short[] buf = new short[chunk * 2];
            byte[] mp3 = new byte[(int) (1.25 * chunk + 7200)];
            for (int f = 0; f < frames; f += chunk) {
                int n = Math.min(chunk, frames - f);
                System.arraycopy(interleaved, 2 * f, buf, 0, 2 * n);
                int w = nativeEncode(h, buf, n, mp3);
                if (w < 0) throw new IllegalStateException("LAME-Fehler " + w);
                out.write(mp3, 0, w);
                if (progress != null && (f / chunk) % 20 == 0) progress.update((int) (100L * f / frames));
            }
            int w = nativeFlush(h, mp3);
            if (w > 0) out.write(mp3, 0, w);
            if (progress != null) progress.update(100);
            return out.toByteArray();
        } finally {
            nativeClose(h);
        }
    }
}
