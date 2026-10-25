package com.kachapon.core.converters;

import com.kachapon.core.*;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secuencia PNG: una carpeta "nombre_Nfps" con un PNG por frame (nombre_0001.png, ...), que cualquier programa importa.
 *  Escribir: cada frame de la animación es un archivo; los holds se repiten y los huecos quedan transparentes.
 *  Leer: los PNG de la carpeta en orden; los iguales seguidos forman un hold y los vacíos, huecos.
 */
public class PngSequenceConverter extends Converter {
    private static final Pattern FPS = Pattern.compile("_(\\d{1,3})fps$");
    private static final String TRACK = "secuencia";

    public PngSequenceConverter(Bridge bridge) {
        super(bridge);
    }

    /** Carpeta de salida para un destino "nombre.png": "nombre_Nfps" junto a él. */
    public static File folderFor(File output, int fps) {
        String name = output.getName();
        if (name.toLowerCase(Locale.ROOT).endsWith(".png")) name = name.substring(0, name.length() - 4);
        return new File(output.getParentFile(), name + "_" + fps + "fps");
    }

    @Override
    public Manifest extract(String inputPath, Bridge bridge) throws Exception {
        File in = new File(inputPath);
        File dir = in.isDirectory() ? in : in.getParentFile(); // también vale elegir uno de los PNG
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (files == null || files.length == 0) throw new Exception("La carpeta no contiene imágenes PNG: " + dir);
        // Orden natural: "f2" antes que "f10"
        Arrays.sort(files, Comparator.comparing((File f) -> f.getName().replaceAll("\\d+", "")).thenComparingLong(PngSequenceConverter::number).thenComparing(File::getName));
        Matcher fm = FPS.matcher(dir.getName());
        int fps = fm.find() ? Integer.parseInt(fm.group(1)) : 24;

        byte[] first = read(files[0]);
        int[] size = Png.size(first);
        if (size == null) throw new Exception("No es un PNG válido: " + files[0].getName());
        Manifest m = new Manifest(size[0] + "x" + size[1], fps, files.length);
        m.tracks.add(TRACK);
        byte[] held = null;
        int start = 0;
        for (int t = 0; t <= files.length; t++) {
            byte[] png = t < files.length ? (t == 0 ? first : read(files[t])) : null;
            if (png != null) {
                int[] s = Png.size(png);
                if (s == null) throw new Exception("No es un PNG válido: " + files[t].getName());
                if (s[0] != size[0] || s[1] != size[1]) throw new Exception("Todas las imágenes deben medir " + m.resolution + ": " + files[t].getName());
            }
            if (png != null && held != null && (Arrays.equals(png, held) || Arrays.equals(Png.decode(png), Png.decode(held)))) continue;
            if (held != null && !blank(held)) m.frames.add(new FrameRef(TRACK, start, t - start, bridge.writeFrame(TRACK, start, held)));
            held = png;
            start = t;
        }
        return m;
    }

    @Override
    public void build(Manifest m, Bridge bridge, File output) throws Exception {
        if (!output.mkdirs()) throw new IOException("No se puede crear " + output);
        String base = output.getName().replaceAll("\\.tmp$", "").replaceAll("_\\d+fps$", "");
        int digits = Math.max(4, String.valueOf(m.duration).length());
        try {
            int t = 0;
            for (Drawing d : flatten(m, bridge)) {
                for (int i = 0; i < d.hold; i++, t++) {
                    try (OutputStream os = new FileOutputStream(new File(output, String.format(Locale.ROOT, "%s_%0" + digits + "d.png", base, t + 1)))) {
                        os.write(d.png);
                    }
                    bridge.progress((t + 1) / (double) Math.max(1, m.duration), "Secuencia PNG: frame " + (t + 1) + " de " + m.duration);
                }
            }
        } catch (Exception e) {
            // El orquestador solo borra archivos: la carpeta a medio escribir se limpia aquí
            File[] partial = output.listFiles();
            if (partial != null) for (File f : partial) f.delete();
            output.delete();
            throw e;
        }
    }

    private static long number(File f) {
        Matcher n = Pattern.compile("(\\d+)(?!.*\\d)").matcher(f.getName());
        return n.find() ? Long.parseLong(n.group(1).length() > 18 ? n.group(1).substring(0, 18) : n.group(1)) : -1;
    }

    private static boolean blank(byte[] png) throws IOException {
        for (int c : Png.decode(png)) if (c >>> 24 != 0) return false;
        return true;
    }

    private static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return Bridge.readAll(in);
        }
    }
}
