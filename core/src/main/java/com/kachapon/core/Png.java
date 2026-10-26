package com.kachapon.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

// Sin java.awt / ImageIO para que core funcione también en Android.
public final class Png {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private Png() {}

    /** {ancho, alto} leídos de la cabecera IHDR, o null si los bytes no son un PNG. */
    public static int[] size(byte[] png) {
        if (png == null || png.length < 24) return null;
        for (int i = 0; i < SIGNATURE.length; i++) if (png[i] != SIGNATURE[i]) return null;
        if (png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') return null;
        return new int[]{readInt(png, 16), readInt(png, 20)};
    }

    /**
     * Píxeles ARGB de un PNG de 8 bits por canal (gris, gris+alfa, RGB o RGBA, sin entrelazar).
     * shortcut: sin paleta, 16 bits ni entrelazado; los PNG del puente salen de encode(), Krita o Clip Studio.
     */
    public static int[] decode(byte[] png) throws IOException {
        int[] size = size(png);
        if (size == null) throw new IOException("La imagen no es un PNG válido.");
        int w = size[0], h = size[1];
        int depth = png[24], type = png[25], interlace = png[28];
        int channels = type == 0 ? 1 : type == 4 ? 2 : type == 2 ? 3 : type == 6 ? 4 : 0;
        if (depth != 8 || channels == 0 || interlace != 0) {
            throw new IOException("PNG no soportado (profundidad " + depth + ", tipo " + type + ", entrelazado " + interlace + ").");
        }
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        for (int p = 8; p + 12 <= png.length; ) {
            int len = readInt(png, p);
            String t = new String(png, p + 4, 4, StandardCharsets.US_ASCII);
            if (len < 0 || p + 12 + len > png.length) throw new IOException("PNG dañado.");
            if (t.equals("IDAT")) idat.write(png, p + 8, len);
            if (t.equals("IEND")) break;
            p += 12 + len;
        }
        int stride = w * channels;
        byte[] raw = new byte[h * (stride + 1)];
        Inflater inf = new Inflater();
        try {
            inf.setInput(idat.toByteArray());
            int n = 0;
            while (n < raw.length && !inf.finished()) {
                int r = inf.inflate(raw, n, raw.length - n);
                if (r == 0 && inf.needsInput()) break;
                n += r;
            }
            if (n != raw.length) throw new IOException("PNG incompleto.");
        } catch (DataFormatException e) {
            throw new IOException("PNG dañado.");
        } finally {
            inf.end();
        }

        // El filtro de cada fila se deshace en sitio, con un bucle por tipo de filtro
        byte[] zero = new byte[stride];
        for (int y = 0; y < h; y++) {
            int row = y * (stride + 1) + 1;
            unfilter(raw, row, y == 0 ? zero : raw, y == 0 ? 0 : row - stride - 1, stride, channels, raw[row - 1]);
        }
        int[] argb = new int[w * h];
        int d = 0;
        for (int y = 0; y < h; y++) {
            int o = y * (stride + 1) + 1;
            if (channels == 4) {
                for (int x = 0; x < w; x++, o += 4) {
                    argb[d++] = (raw[o + 3] & 0xFF) << 24 | (raw[o] & 0xFF) << 16 | (raw[o + 1] & 0xFF) << 8 | (raw[o + 2] & 0xFF);
                }
            } else {
                for (int x = 0; x < w; x++, o += channels) {
                    int r, g, bl, al;
                    if (channels <= 2) { r = g = bl = raw[o] & 0xFF; al = channels == 2 ? raw[o + 1] & 0xFF : 255; }
                    else { r = raw[o] & 0xFF; g = raw[o + 1] & 0xFF; bl = raw[o + 2] & 0xFF; al = 255; }
                    argb[d++] = al << 24 | r << 16 | g << 8 | bl;
                }
            }
        }
        return argb;
    }

    /** Deshace el filtro PNG de una fila (stride bytes desde row) usando la fila anterior (desde po en prev; ceros en la primera). */
    private static void unfilter(byte[] raw, int row, byte[] prev, int po, int stride, int bpp, int filter) throws IOException {
        int end = row + stride, first = Math.min(row + bpp, end);
        switch (filter) {
            case 0: break;
            case 1: for (int i = first; i < end; i++) raw[i] += raw[i - bpp]; break;
            case 2: for (int i = row, j = po; i < end; i++, j++) raw[i] += prev[j]; break;
            case 3:
                for (int i = row, j = po; i < first; i++, j++) raw[i] += (byte) ((prev[j] & 0xFF) >> 1);
                for (int i = first, j = po + (first - row); i < end; i++, j++) raw[i] += (byte) (((raw[i - bpp] & 0xFF) + (prev[j] & 0xFF)) >> 1);
                break;
            case 4:
                for (int i = row, j = po; i < first; i++, j++) raw[i] += prev[j]; // sin vecino a la izquierda, Paeth es "arriba"
                for (int i = first, j = po + (first - row); i < end; i++, j++) {
                    int a = raw[i - bpp] & 0xFF, b = prev[j] & 0xFF, c = prev[j - bpp] & 0xFF;
                    int pa = Math.abs(b - c), pb = Math.abs(a - c), pc = Math.abs(a + b - 2 * c);
                    raw[i] += (byte) (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
                }
                break;
            default: throw new IOException("PNG dañado (filtro " + filter + ").");
        }
    }

    /** PNG RGBA de 8 bits a partir de píxeles ARGB (sin premultiplicar). */
    public static byte[] encode(int[] argb, int width, int height) throws IOException {
        byte[] raw = new byte[height * (1 + width * 4)];
        int p = 0;
        for (int y = 0; y < height; y++) {
            raw[p++] = 0; // filtro "None"
            for (int x = 0; x < width; x++) {
                int c = argb[y * width + x];
                raw[p++] = (byte) (c >> 16);
                raw[p++] = (byte) (c >> 8);
                raw[p++] = (byte) c;
                raw[p++] = (byte) (c >>> 24);
            }
        }
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(idat)) {
            z.write(raw);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SIGNATURE);
        chunk(out, "IHDR", new byte[]{
                (byte) (width >> 24), (byte) (width >> 16), (byte) (width >> 8), (byte) width,
                (byte) (height >> 24), (byte) (height >> 16), (byte) (height >> 8), (byte) height,
                8, 6, 0, 0, 0});
        chunk(out, "IDAT", idat.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(data.length);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        d.write(t);
        d.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        d.writeInt((int) crc.getValue());
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}
