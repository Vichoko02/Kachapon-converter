package com.kachapon.core.converters;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** La reducción de mipmaps con límites precalculados debe dar los mismos píxeles que la original. */
class ShrinkEqualityTest {
    @Test
    void sameAsPerPixelDivisions() {
        Random rnd = new Random(5);
        int[][] pairs = {{600, 300, 300, 150}, {601, 301, 300, 150}, {2084, 1469, 1042, 734}, {7, 5, 3, 2}, {5, 5, 5, 5}, {1, 1, 1, 1}, {9, 4, 2, 2}, {131, 77, 65, 38}};
        for (int[] p : pairs) {
            for (int mode = 0; mode < 3; mode++) { // vacío, ruido con alfa y mitad transparente
                int[] src = new int[p[0] * p[1]];
                for (int i = 0; i < src.length; i++) {
                    boolean on = mode == 1 || (mode == 2 && (i / p[0]) % 2 == 0 && rnd.nextBoolean());
                    if (on) src[i] = rnd.nextInt();
                }
                assertArrayEquals(oldShrink(src, p[0], p[1], p[2], p[3]), ClipWriter.shrink(src, p[0], p[1], p[2], p[3]),
                        p[0] + "x" + p[1] + " -> " + p[2] + "x" + p[3] + " modo " + mode);
            }
        }
    }

    private static int[] oldShrink(int[] src, int sw, int sh, int dw, int dh) {
        int[] out = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            int y0 = (int) ((long) y * sh / dh), y1 = Math.max(y0 + 1, (int) (((long) (y + 1) * sh + dh - 1) / dh));
            for (int x = 0; x < dw; x++) {
                int x0 = (int) ((long) x * sw / dw), x1 = Math.max(x0 + 1, (int) (((long) (x + 1) * sw + dw - 1) / dw));
                long a = 0, r = 0, g = 0, b = 0, n = 0;
                int yEnd = Math.min(y1, sh), xEnd = Math.min(x1, sw);
                for (int yy = y0; yy < yEnd; yy++) {
                    for (int xx = x0; xx < xEnd; xx++) {
                        int c = src[yy * sw + xx], al = c >>> 24;
                        a += al;
                        r += (long) ((c >> 16) & 0xFF) * al;
                        g += (long) ((c >> 8) & 0xFF) * al;
                        b += (long) (c & 0xFF) * al;
                        n++;
                    }
                }
                if (a == 0) continue;
                out[y * dw + x] = (int) (a / n) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
            }
        }
        return out;
    }
}
