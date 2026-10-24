package com.kachapon.core.converters;

import com.kachapon.core.*;

import java.io.*;
import java.util.Arrays;
import java.util.zip.*;

/** Un formato de archivo: sabe pasar a la secuencia del puente y volver de ella. */
public abstract class Converter {
    protected final Bridge bridge;

    protected Converter(Bridge bridge) {
        this.bridge = bridge;
    }

    /** Lee el archivo y deja su secuencia de imágenes en el puente. */
    public abstract Manifest extract(String inputPath, Bridge bridge) throws Exception;

    /** Empaqueta la secuencia del puente en este formato. */
    public abstract void build(Manifest manifest, Bridge bridge, File output) throws Exception;

    /** Un dibujo de la animación ya combinada: PNG del lienzo completo y cuántos frames dura. */
    protected static final class Drawing {
        final byte[] png;
        int hold;

        Drawing(byte[] png, int hold) {
            this.png = png;
            this.hold = hold;
        }
    }

    /** La animación visible como una sola pista (ver flatten con pistas); las pistas ocultas no se combinan. */
    protected static java.util.List<Drawing> flatten(Manifest m, Bridge bridge) throws IOException {
        java.util.List<String> visible = new java.util.ArrayList<>();
        for (String track : m.tracks) if (m.visible(track)) visible.add(track);
        return flatten(m, bridge, visible);
    }

    /**
     * Estas pistas como una sola: en cada frame se combinan de abajo arriba (en el orden de la lista), los huecos
     * quedan transparentes y los frames iguales seguidos se juntan en un hold.
     */
    protected static java.util.List<Drawing> flatten(Manifest m, Bridge bridge, java.util.List<String> tracks) throws IOException {
        java.util.List<Drawing> out = new java.util.ArrayList<>();
        int w = m.width(), h = m.height();
        // Frame -> dibujo que se ve, por pista; evita recorrer todos los dibujos en cada frame
        java.util.Map<String, FrameRef[]> at = new java.util.HashMap<>();
        for (String track : tracks) at.put(track, new FrameRef[m.duration]);
        for (FrameRef f : m.frames) {
            FrameRef[] line = at.get(f.track);
            if (line != null) for (int t = Math.max(0, f.frame); t < Math.min(m.duration, f.frame + f.hold); t++) line[t] = f;
        }
        byte[] empty = null;
        for (int t = 0; t < m.duration; t++) {
            int[] canvas = null;
            byte[] single = null;
            for (String track : tracks) {
                FrameRef fr = at.get(track)[t];
                if (fr == null) continue;
                byte[] png = bridge.readFrame(fr);
                if (single == null) {
                    single = png;
                } else {
                    if (canvas == null) canvas = Png.decode(single);
                    over(canvas, Png.decode(png), 1f);
                }
            }
            byte[] png = canvas != null ? Png.encode(canvas, w, h) : single;
            if (png == null) {
                if (empty == null) empty = Png.encode(new int[w * h], w, h);
                png = empty;
            }
            Drawing last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && Arrays.equals(last.png, png)) last.hold++;
            else out.add(new Drawing(png, 1));
        }
        return out;
    }

    protected static ZipFile openZip(String path, String notZipMessage) throws Exception {
        try {
            return new ZipFile(path);
        } catch (ZipException e) {
            throw new Exception(notZipMessage);
        }
    }

    /** Bytes de un PNG dentro del ZIP, con un error claro si falta o no es PNG. */
    protected static byte[] readPng(ZipFile zf, String path) throws Exception {
        ZipEntry entry = path == null ? null : zf.getEntry(path);
        if (entry == null) throw new Exception("Falta la imagen en el archivo: " + path);
        byte[] png;
        try (InputStream is = zf.getInputStream(entry)) {
            png = Bridge.readAll(is);
        }
        if (Png.size(png) == null) throw new Exception("La imagen no es un PNG válido: " + path);
        return png;
    }

    protected static void put(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    /** "Normal" sin premultiplicar: dst = src * opacidad sobre dst. */
    protected static void over(int[] dst, int[] src, float opacity) {
        for (int i = 0; i < dst.length; i++) {
            int s = src[i];
            int sa = Math.round((s >>> 24) * opacity);
            if (sa <= 0) continue;
            int d = dst[i], da = d >>> 24;
            if (sa >= 255 || da == 0) {
                dst[i] = Math.min(sa, 255) << 24 | (s & 0xFFFFFF);
                continue;
            }
            int keep = da * (255 - sa) / 255, oa = sa + keep;
            int r = (((s >> 16) & 0xFF) * sa + ((d >> 16) & 0xFF) * keep) / oa;
            int g = (((s >> 8) & 0xFF) * sa + ((d >> 8) & 0xFF) * keep) / oa;
            int bl = ((s & 0xFF) * sa + (d & 0xFF) * keep) / oa;
            dst[i] = oa << 24 | r << 16 | g << 8 | bl;
        }
    }
}
