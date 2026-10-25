package com.kachapon.core.converters;

import com.kachapon.core.Png;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** La versión rápida de rpng debe dar los mismos bytes que el algoritmo original con listas. */
class RpngEqualityTest {
    @Test
    void sameBytesAsListVersion() throws Exception {
        Random rnd = new Random(7);
        int[][] sizes = {{1, 1}, {7, 5}, {64, 48}, {300, 200}, {400, 300}};
        for (int[] s : sizes) {
            int w = s[0], h = s[1];
            for (int mode = 0; mode < 4; mode++) {
                int[] px = new int[w * h];
                for (int i = 0; i < px.length; i++) {
                    boolean on = mode == 0 ? false : mode == 1 ? true : mode == 2 ? rnd.nextInt(10) == 0 : (i / w > h / 3 && i % w > w / 4 && i % w < w / 2);
                    if (on) px[i] = (1 + rnd.nextInt(255)) << 24 | rnd.nextInt(0x1000000);
                }
                for (boolean legacy : new boolean[]{false, true}) {
                    assertArrayEquals(oldRpng(px, w, h, legacy), IbisConverter.rpng(px, w, h, legacy), w + "x" + h + " modo " + mode + " legacy " + legacy);
                }
            }
        }
    }

    private static byte[] oldRpng(int[] argb, int w, int h, boolean legacy) throws IOException {
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (argb[(h - 1 - y) * w + x] >>> 24 == 0) continue;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        }
        boolean empty = maxX < 0;
        int rx = empty ? 0 : minX, ry = empty ? 0 : minY;
        int rw = empty ? 1 : maxX - minX + 1, rh = empty ? 1 : maxY - minY + 1;

        List<Integer> runs = new ArrayList<>();
        List<Integer> visible = new ArrayList<>();
        boolean cls = false;
        int n = 0;
        for (int o = 0; o < rw * rh; o++) {
            int x = rx + o % rw, y = ry + o / rw;
            int c = argb[(h - 1 - y) * w + x];
            boolean v = !empty && c >>> 24 != 0;
            if (v != cls) {
                runs.add(n);
                n = 0;
                cls = v;
            }
            if (n == 0xFFFF) { // los tramos son u16: se parte con un tramo vacío de la otra clase
                runs.add(n);
                runs.add(0);
                n = 0;
            }
            n++;
            if (v) visible.add(c);
        }
        runs.add(n);
        if (runs.size() % 2 == 1) runs.add(0);
        if (visible.isEmpty()) { // ibisPaint necesita al menos un píxel guardado
            runs.clear();
            runs.add(0);
            runs.add(1);
            visible.add(0);
            rw = rh = 1;
        }

        int count = visible.size(), pw = 8;
        while ((long) pw * pw < count) pw += 8;
        int ph = (count + pw - 1) / pw, pad = pw * ph - count;
        int[] grid = new int[pw * ph];
        int row = pad / pw, col = pw - 1 - pad % pw;
        for (int k = count - 1; k >= 0; k--) { // el último visible va al primer hueco consumido
            while (col < 0) {
                row++;
                col = pw - 1;
            }
            grid[row * pw + col--] = visible.get(k);
        }
        byte[] png = Png.encode(grid, pw, ph);

        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(b);
        // Cabecera de 45 bytes con el color de transparencia (bandera 2) o la antigua de 41 sin él; el byte de versión debe ser 0
        c.writeInt(0x52504e47); c.writeInt(legacy ? 41 : 45); c.writeByte(0);
        c.writeInt(legacy ? 1 : 3); c.writeInt(w); c.writeInt(h); c.writeInt(rw); c.writeInt(rh); c.writeInt(rx); c.writeInt(ry);
        c.writeInt(pw); c.writeInt(ph); c.writeInt(pad);
        if (!legacy) c.writeInt(0); // color de transparencia
        c.writeInt(runs.size() * 2);
        for (int r : runs) c.writeShort(r);
        c.writeInt(png.length);
        c.write(png);
        return b.toByteArray();
    }
}
