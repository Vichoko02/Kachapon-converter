package com.kachapon.core.converters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Lector directo de los archivos de capa de un .kra ("layerN", "layerN.fK"): bloques de 64x64 RGBA8, con o sin LZF. */
final class KraTiles {
    private static final int TILE = 64, TILE_BYTES = TILE * TILE * 4;

    private KraTiles() {
    }

    /**
     * Los píxeles de la capa como ARGB sobre un lienzo w x h, con la capa colocada en (dx, dy); null si no tiene ningún píxel visible.
     * Lanza IOException si el archivo no es del formato esperado (otro tamaño de bloque o de píxel, datos cortados).
     */
    static int[] decode(byte[] file, int w, int h, int dx, int dy) throws IOException {
        int[] pos = {0};
        int tiles = -1;
        for (int i = 0; i < 5; i++) {
            String line = line(file, pos);
            if (line.startsWith("VERSION ") && !line.equals("VERSION 2")) throw new IOException("versión de capa " + line);
            if (line.startsWith("TILEWIDTH ") && !line.equals("TILEWIDTH " + TILE)) throw new IOException(line);
            if (line.startsWith("TILEHEIGHT ") && !line.equals("TILEHEIGHT " + TILE)) throw new IOException(line);
            if (line.startsWith("PIXELSIZE ") && !line.equals("PIXELSIZE 4")) throw new IOException(line);
            if (line.startsWith("DATA ")) tiles = Integer.parseInt(line.substring(5).trim());
        }
        if (tiles < 0) throw new IOException("la capa no dice cuántos bloques tiene");

        int[] out = new int[w * h];
        boolean any = false;
        byte[] planar = new byte[TILE_BYTES];
        for (int t = 0; t < tiles; t++) {
            String[] head = line(file, pos).split(",");
            if (head.length < 4) throw new IOException("bloque mal formado");
            int tx = Integer.parseInt(head[0]) + dx, ty = Integer.parseInt(head[1]) + dy, size = Integer.parseInt(head[3]);
            int start = pos[0];
            if (size < 1 || start + size > file.length) throw new IOException("bloque cortado");
            pos[0] = start + size;
            boolean compressed = file[start] == 1;
            byte[] px;
            int at;
            if (compressed) {
                lzf(file, start + 1, size - 1, planar);
                px = planar;
                at = 0;
            } else {
                if (size - 1 != TILE_BYTES) throw new IOException("bloque sin comprimir de " + (size - 1) + " bytes");
                px = file;
                at = start + 1;
            }
            for (int y = 0; y < TILE; y++) {
                int oy = ty + y;
                if (oy < 0 || oy >= h) continue;
                for (int x = 0; x < TILE; x++) {
                    int ox = tx + x;
                    if (ox < 0 || ox >= w) continue;
                    int i = y * TILE + x, b, g, r, a;
                    if (compressed) { // por planos: B, G, R, A
                        b = px[i] & 0xFF; g = px[4096 + i] & 0xFF; r = px[8192 + i] & 0xFF; a = px[12288 + i] & 0xFF;
                    } else { // intercalado: BGRA por píxel
                        int j = at + 4 * i;
                        b = px[j] & 0xFF; g = px[j + 1] & 0xFF; r = px[j + 2] & 0xFF; a = px[j + 3] & 0xFF;
                    }
                    out[oy * w + ox] = a << 24 | r << 16 | g << 8 | b;
                    if (a != 0) any = true;
                }
            }
        }
        return any ? out : null;
    }

    private static String line(byte[] f, int[] pos) throws IOException {
        int s = pos[0], e = s;
        while (e < f.length && f[e] != '\n') e++;
        if (e >= f.length) throw new IOException("cabecera de capa cortada");
        pos[0] = e + 1;
        return new String(f, s, e - s, StandardCharsets.US_ASCII);
    }

    /** Descompresión LZF (liblzf), la que usa Krita para sus bloques. */
    static void lzf(byte[] in, int off, int len, byte[] out) throws IOException {
        int ip = off, end = off + len, op = 0;
        try {
            while (ip < end) {
                int ctrl = in[ip++] & 0xFF;
                if (ctrl < 32) { // tramo literal de ctrl+1 bytes
                    int n = ctrl + 1;
                    System.arraycopy(in, ip, out, op, n);
                    ip += n;
                    op += n;
                } else { // copia de algo ya descomprimido; puede solaparse consigo misma
                    int n = ctrl >> 5;
                    if (n == 7) n += in[ip++] & 0xFF;
                    int ref = op - ((ctrl & 0x1F) << 8) - 1 - (in[ip++] & 0xFF);
                    if (ref < 0) throw new IOException("LZF: referencia fuera de rango");
                    for (int k = 0; k < n + 2; k++) out[op++] = out[ref++];
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IOException("LZF: datos corruptos");
        }
        if (op != out.length) throw new IOException("LZF: descomprimió " + op + " bytes y se esperaban " + out.length);
    }
}
