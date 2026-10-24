package com.kachapon.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompositeTest {
    private static int[] px(int argb) {
        return new int[]{argb};
    }

    private static void assertPixel(int expected, int actual, int tolerance) {
        for (int s = 0; s < 32; s += 8) {
            int e = (expected >>> s) & 0xFF, a = (actual >>> s) & 0xFF;
            assertEquals(e, a, tolerance, "canal " + s / 8 + " de " + Integer.toHexString(expected) + " vs " + Integer.toHexString(actual));
        }
    }

    @Test
    void normalHalfOpacityOverOpaque() {
        int[] d = px(0xFFFFFFFF);
        Composite.blend(d, px(0xFF000000), "normal", 128);
        assertPixel(0xFF7F7F7F, d[0], 1);
    }

    @Test
    void multiplyAndScreenOnOpaque() {
        int[] d = px(0xFF808080);
        Composite.blend(d, px(0xFF808080), "multiply", 255);
        assertPixel(0xFF404040, d[0], 1); // 0,502 * 0,502 = 0,252

        d = px(0xFF808080);
        Composite.blend(d, px(0xFF808080), "screen", 255);
        assertPixel(0xFFC0C0C0, d[0], 1); // 1 - (1 - 0,502)^2 = 0,752
    }

    @Test
    void blendOverTransparentIsNormal() {
        int[] d = px(0);
        Composite.blend(d, px(0x80FF0000), "multiply", 255);
        assertPixel(0x80FF0000, d[0], 1);
    }

    @Test
    void zeroOpacityAndTransparentSourceChangeNothing() {
        int[] d = px(0xFF336699);
        Composite.blend(d, px(0xFFFFFFFF), "overlay", 0);
        assertEquals(0xFF336699, d[0]);
        Composite.blend(d, px(0x00FFFFFF), "overlay", 255);
        assertEquals(0xFF336699, d[0]);
    }

    @Test
    void differenceAndColor() {
        int[] d = px(0xFF333333); // 0,2
        Composite.blend(d, px(0xFFB2B2B2), "diff", 255); // 0,7
        assertPixel(0xFF7F7F7F, d[0], 1);

        // "color": el tono y la saturación de la capa con la luminosidad de abajo; rojo sobre gris medio da (255, 74, 74)
        d = px(0xFF808080);
        Composite.blend(d, px(0xFFFF0000), "color", 255);
        assertPixel(0xFFFF4A4A, d[0], 2);
    }
}
