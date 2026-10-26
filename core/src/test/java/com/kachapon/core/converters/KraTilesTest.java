package com.kachapon.core.converters;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class KraTilesTest {
    @Test
    void lzfLiteralAndOverlappingCopy() throws Exception {
        byte[] out = new byte[9];
        // literal "ABC", luego copia de 6 bytes desde 3 atrás (se solapa consigo misma)
        KraTiles.lzf(new byte[]{2, 'A', 'B', 'C', (byte) 128, 2}, 0, 6, out);
        assertEquals("ABCABCABC", new String(out, StandardCharsets.US_ASCII));
        assertThrows(java.io.IOException.class, () -> KraTiles.lzf(new byte[]{2, 'A', 'B', 'C'}, 0, 4, new byte[9]));
    }

    private static byte[] file(String tileHeader, byte[] tile) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write("VERSION 2\nTILEWIDTH 64\nTILEHEIGHT 64\nPIXELSIZE 4\nDATA 1\n".getBytes(StandardCharsets.US_ASCII));
        b.write((tileHeader + "," + tile.length + "\n").getBytes(StandardCharsets.US_ASCII));
        b.write(tile);
        return b.toByteArray();
    }

    /** Un bloque sin comprimir (BGRA intercalado) y uno por planos (como lo guarda Krita) dan el mismo píxel, colocados y recortados igual. */
    @Test
    void rawAndPlanarTilesDecodeAlikeAndClip() throws Exception {
        byte[] raw = new byte[1 + 64 * 64 * 4];
        raw[0] = 0;
        raw[1] = 30; raw[2] = 20; raw[3] = 10; raw[4] = (byte) 200; // píxel (0,0): B=30 G=20 R=10 A=200
        // por planos, comprimido a mano como un tramo literal por cada 32 bytes
        byte[] planes = new byte[64 * 64 * 4];
        planes[0] = 30; planes[4096] = 20; planes[8192] = 10; planes[12288] = (byte) 200;
        ByteArrayOutputStream lz = new ByteArrayOutputStream();
        lz.write(1);
        for (int i = 0; i < planes.length; i += 32) {
            lz.write(31);
            lz.write(planes, i, 32);
        }
        byte[] packed = lz.toByteArray();

        int[] a = KraTiles.decode(file("-2,-3,LZF", raw), 8, 8, 2, 3); // el bloque cae en (0,0)
        int[] b = KraTiles.decode(file("-2,-3,LZF", packed), 8, 8, 2, 3);
        assertArrayEquals(a, b);
        assertEquals(200 << 24 | 10 << 16 | 20 << 8 | 30, a[0]);
        assertEquals(0, a[1]);
        assertNull(KraTiles.decode(file("100,100,LZF", raw), 8, 8, 0, 0), "fuera del lienzo no queda nada visible");
        assertThrows(java.io.IOException.class, () -> KraTiles.decode("VERSION 3\n".getBytes(StandardCharsets.US_ASCII), 8, 8, 0, 0));
    }
}
