package com.kachapon.core.converters;

import com.kachapon.core.*;

import java.io.*;
import java.util.Arrays;
import java.util.zip.*;

/** Un formato de archivo: sabe pasar a la secuencia del puente y volver de ella. */
public abstract class Converter {
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

    /**
     * La animación visible como una sola pista: en cada tramo se combinan las capas visibles como en el documento
     * (carpetas, modos de fusión y opacidad), los huecos quedan transparentes y los frames iguales seguidos se juntan en un hold.
     */
    protected static java.util.List<Drawing> flatten(Manifest m, Bridge bridge) throws IOException {
        java.util.List<Drawing> out = new java.util.ArrayList<>();
        int w = m.width(), h = m.height();
        byte[] empty = null;
        Planner planner = new Planner(m, bridge);
        try {
            for (Planner.State s : planner.states()) {
                byte[] png;
                if (s.planes.isEmpty()) {
                    if (empty == null) empty = Png.encode(new int[w * h], w, h);
                    png = empty;
                } else {
                    png = Png.encode(planner.compose(s), w, h);
                }
                Drawing last = out.isEmpty() ? null : out.get(out.size() - 1);
                if (last != null && Arrays.equals(last.png, png)) last.hold += s.hold;
                else out.add(new Drawing(png, s.hold));
            }
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        return out;
    }

    /**
     * La imagen del primer tramo de la animación, que es la miniatura de los archivos. No combina el resto de frames (hacerlo solo para
     * la miniatura costaba ~100 s en una escena grande). PNG vacío si no hay nada que ver y sin bytes si la animación no tiene frames.
     */
    protected static byte[] firstFrame(Manifest m, Bridge bridge) throws IOException {
        Planner planner = new Planner(m, bridge);
        try {
            java.util.List<Planner.State> states = planner.states();
            if (states.isEmpty()) return new byte[0];
            return Png.encode(planner.compose(states.get(0)), m.width(), m.height());
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Los dibujos de una pista seguidos desde el frame 0: los huecos son un PNG transparente y los frames iguales
     * seguidos se juntan en un hold. Los PNG del puente se usan tal cual, sin decodificar.
     */
    protected static java.util.List<Drawing> drawings(Manifest m, Bridge bridge, String track) throws IOException {
        java.util.List<Drawing> out = new java.util.ArrayList<>();
        FrameRef[] at = new FrameRef[m.duration]; // frame -> dibujo que se ve
        for (FrameRef f : m.frames) {
            if (f.track.equals(track)) for (int t = Math.max(0, f.frame); t < Math.min(m.duration, f.frame + f.hold); t++) at[t] = f;
        }
        byte[] empty = null;
        for (int t = 0; t < m.duration; t++) {
            byte[] png = at[t] == null ? null : bridge.readFrame(at[t]);
            if (png == null) {
                if (empty == null) empty = Png.encode(new int[m.width() * m.height()], m.width(), m.height());
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
}
