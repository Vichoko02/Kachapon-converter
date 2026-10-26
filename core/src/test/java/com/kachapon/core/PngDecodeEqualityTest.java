package com.kachapon.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** La decodificación rápida debe dar los mismos píxeles que el algoritmo original, con los cinco filtros y de 1 a 4 canales. */
class PngDecodeEqualityTest {
    @Test
    void sameAsOriginalWithEveryFilter() throws Exception {
        Random rnd = new Random(11);
        int[][] sizes = {{1, 1}, {1, 7}, {5, 1}, {7, 5}, {64, 48}, {131, 77}};
        for (int channels = 1; channels <= 4; channels++) {
            int type = channels == 1 ? 0 : channels == 2 ? 4 : channels == 3 ? 2 : 6;
            for (int[] s : sizes) {
                for (int mode = 0; mode < 3; mode++) { // ruido, degradado suave y plano
                    byte[] pixels = new byte[s[0] * s[1] * channels];
                    for (int i = 0; i < pixels.length; i++) pixels[i] = (byte) (mode == 0 ? rnd.nextInt(256) : mode == 1 ? i / 3 + rnd.nextInt(3) : 77);
                    for (int forced = -1; forced < 5; forced++) { // -1 = un filtro distinto en cada fila
                        byte[] png = png(pixels, s[0], s[1], channels, type, forced);
                        assertArrayEquals(oldDecode(png), Png.decode(png), s[0] + "x" + s[1] + " canales " + channels + " modo " + mode + " filtro " + forced);
                    }
                }
            }
        }
    }

    @Test
    void roundTripOfOurOwnEncoder() throws Exception {
        Random rnd = new Random(3);
        int[] px = new int[90 * 40];
        for (int i = 0; i < px.length; i++) px[i] = rnd.nextInt(5) == 0 ? 0 : rnd.nextInt();
        byte[] png = Png.encode(px, 90, 40);
        assertArrayEquals(oldDecode(png), Png.decode(png));
        assertArrayEquals(px, Png.decode(png));
    }

    @Test
    void unknownFilterStillFails() throws Exception {
        byte[] png = png(new byte[3 * 2 * 4], 3, 2, 4, 6, 0);
        byte[] raw = inflateIdat(png);
        raw[0] = 9;
        assertThrows(IOException.class, () -> Png.decode(wrap(raw, 3, 2, 6)));
    }

    /** PNG de 8 bits con el filtro pedido en cada fila (forced -1: rota los cinco). */
    private static byte[] png(byte[] pixels, int w, int h, int channels, int type, int forced) throws IOException {
        int stride = w * channels;
        byte[] raw = new byte[h * (stride + 1)];
        for (int y = 0; y < h; y++) {
            int f = forced >= 0 ? forced : y % 5, o = y * (stride + 1);
            raw[o] = (byte) f;
            for (int i = 0; i < stride; i++) {
                int x = pixels[y * stride + i] & 0xFF;
                int a = i >= channels ? pixels[y * stride + i - channels] & 0xFF : 0;
                int b = y > 0 ? pixels[(y - 1) * stride + i] & 0xFF : 0;
                int c = i >= channels && y > 0 ? pixels[(y - 1) * stride + i - channels] & 0xFF : 0;
                int pred;
                switch (f) {
                    case 1: pred = a; break;
                    case 2: pred = b; break;
                    case 3: pred = (a + b) / 2; break;
                    case 4: {
                        int pa = Math.abs(b - c), pb = Math.abs(a - c), pc = Math.abs(a + b - 2 * c);
                        pred = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
                        break;
                    }
                    default: pred = 0;
                }
                raw[o + 1 + i] = (byte) (x - pred);
            }
        }
        return wrap(raw, w, h, type);
    }

    private static byte[] wrap(byte[] raw, int w, int h, int type) throws IOException {
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(z, new Deflater())) {
            d.write(raw);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(ihdr);
        d.writeInt(w);
        d.writeInt(h);
        d.write(new byte[]{8, (byte) type, 0, 0, 0});
        chunk(out, "IHDR", ihdr.toByteArray());
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        DataOutputStream d = new DataOutputStream(out);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        d.writeInt(data.length);
        d.write(t);
        d.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        d.writeInt((int) crc.getValue());
    }

    private static byte[] inflateIdat(byte[] png) throws Exception {
        int p = 8 + 25; // firma + IHDR completo (4 + 4 + 13 + 4)
        int len = ((png[p] & 255) << 24) | ((png[p + 1] & 255) << 16) | ((png[p + 2] & 255) << 8) | (png[p + 3] & 255);
        Inflater inf = new Inflater();
        inf.setInput(png, p + 8, len);
        byte[] out = new byte[1 << 16];
        int n = inf.inflate(out);
        inf.end();
        return Arrays.copyOf(out, n);
    }

    /** El decodificador de antes: una fila copiada y un switch por byte. */
    private static int[] oldDecode(byte[] png) throws Exception {
        int[] size = Png.size(png);
        int w = size[0], h = size[1], type = png[25];
        int channels = type == 0 ? 1 : type == 4 ? 2 : type == 2 ? 3 : 4;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        for (int p = 8; p + 12 <= png.length; ) {
            int len = ((png[p] & 255) << 24) | ((png[p + 1] & 255) << 16) | ((png[p + 2] & 255) << 8) | (png[p + 3] & 255);
            String t = new String(png, p + 4, 4, StandardCharsets.US_ASCII);
            if (t.equals("IDAT")) idat.write(png, p + 8, len);
            if (t.equals("IEND")) break;
            p += 12 + len;
        }
        int stride = w * channels;
        byte[] raw = new byte[h * (stride + 1)];
        Inflater inf = new Inflater();
        inf.setInput(idat.toByteArray());
        int n = 0;
        while (n < raw.length && !inf.finished()) n += inf.inflate(raw, n, raw.length - n);
        inf.end();
        byte[] prev = new byte[stride], cur = new byte[stride];
        int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            int filter = raw[y * (stride + 1)];
            System.arraycopy(raw, y * (stride + 1) + 1, cur, 0, stride);
            for (int i = 0; i < stride; i++) {
                int a = i >= channels ? cur[i - channels] & 0xFF : 0, b = prev[i] & 0xFF;
                int c = i >= channels ? prev[i - channels] & 0xFF : 0;
                int add;
                switch (filter) {
                    case 0: add = 0; break;
                    case 1: add = a; break;
                    case 2: add = b; break;
                    case 3: add = (a + b) / 2; break;
                    default: {
                        int pa = Math.abs(b - c), pb = Math.abs(a - c), pc = Math.abs(a + b - 2 * c);
                        add = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
                    }
                }
                cur[i] = (byte) (cur[i] + add);
            }
            for (int x = 0; x < w; x++) {
                int o = x * channels, r, g, bl, al;
                if (channels <= 2) { r = g = bl = cur[o] & 0xFF; al = channels == 2 ? cur[o + 1] & 0xFF : 255; }
                else { r = cur[o] & 0xFF; g = cur[o + 1] & 0xFF; bl = cur[o + 2] & 0xFF; al = channels == 4 ? cur[o + 3] & 0xFF : 255; }
                argb[y * w + x] = al << 24 | r << 16 | g << 8 | bl;
            }
            byte[] t = prev; prev = cur; cur = t;
        }
        return argb;
    }
}
