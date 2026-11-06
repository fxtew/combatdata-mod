package dev.combatdata.core;

/**
 * Bytes as text, 15 bits per character, for codes pasted into chat (about 2.5 times shorter than Base64).
 * Characters come only from blocks that are letters in every font and stay the same under Unicode normalisation:
 * CJK Unified Ideographs, CJK Extension A, Yi Syllables and the start of Hangul Syllables. All are in the Basic
 * Multilingual Plane, so chat apps count each as one character. The last 1-7 bits use a separate set of 128
 * characters; the decoder drops the padding bits that do not make a whole byte.
 */
public final class Base32768 {
    private static final int[][] MAIN = {{0x4E00, 0x9FFF}, {0x3400, 0x4DBF}, {0xA000, 0xA48C}, {0xAC00, 0xBBB2}};
    private static final int TAIL_START = 0xBBB3, TAIL_SIZE = 128;
    private static final char[] ENC = new char[1 << 15];
    private static final int[] DEC = new int[0x10000];

    static {
        java.util.Arrays.fill(DEC, -1);
        int n = 0;
        for (int[] r : MAIN) for (int c = r[0]; c <= r[1] && n < ENC.length; c++) ENC[n++] = (char) c;
        if (n != ENC.length) throw new IllegalStateException("Base32768 repertoire: " + n);
        for (int i = 0; i < ENC.length; i++) DEC[ENC[i]] = i;
        for (int i = 0; i < TAIL_SIZE; i++) DEC[TAIL_START + i] = (1 << 15) | i;
    }

    private Base32768() {
    }

    public static String encode(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 8 / 15 + 2);
        int acc = 0, bits = 0;
        for (byte b : data) {
            acc = (acc << 8) | (b & 0xFF);
            bits += 8;
            if (bits >= 15) {
                bits -= 15;
                out.append(ENC[(acc >>> bits) & 0x7FFF]);
            }
        }
        if (bits > 0) {
            // Padding with ones: fewer than 8 padding bits never make a whole byte, so the decoder drops them.
            if (bits <= 7) out.append((char) (TAIL_START + (((acc << (7 - bits)) | ((1 << (7 - bits)) - 1)) & 0x7F)));
            else out.append(ENC[((acc << (15 - bits)) | ((1 << (15 - bits)) - 1)) & 0x7FFF]);
        }
        return out.toString();
    }

    /** Throws IllegalArgumentException on a character outside the repertoire. */
    public static byte[] decode(CharSequence s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length() * 15 / 8 + 1);
        int acc = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int v = DEC[c];
            if (v < 0) throw new IllegalArgumentException("bad character");
            boolean tail = (v & (1 << 15)) != 0;
            if (tail && i != s.length() - 1) throw new IllegalArgumentException("tail character inside the text");
            int width = tail ? 7 : 15;
            acc = (acc << width) | (v & 0x7FFF);
            bits += width;
            while (bits >= 8) {
                bits -= 8;
                out.write((acc >>> bits) & 0xFF);
            }
            acc &= (1 << bits) - 1;
        }
        return out.toByteArray();
    }

    /** True for characters this encoding produces. */
    public static boolean isCodeChar(char c) {
        return DEC[c] >= 0;
    }
}
