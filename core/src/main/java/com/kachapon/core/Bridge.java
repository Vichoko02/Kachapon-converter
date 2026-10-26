package com.kachapon.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Puente universal (temp_seq/) por el que pasa toda conversión:
 *   manifest.json   resolución, fps, pistas y frames con sus holds
 *   frames/         secuencia PNG RGBA [pista]_[layerId]_[frame].png
 *   timesheet.xdts  hoja de tiempos
 * Solo java.io (no java.nio.file) para funcionar en Android desde API 24.
 */
public class Bridge {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public final File dir;

    /** Quien quiera saber cómo va la conversión: fracción 0..1 del total y qué se está haciendo. */
    public interface Progress {
        void update(double fraction, String stage);
    }

    // Avisos de la conversión que corre en este hilo; los convertidores y Blend son estáticos y no tienen el puente a mano
    private static final ThreadLocal<java.util.Set<String>> WARNINGS = new ThreadLocal<>();

    /** Aviso para la persona (una capa omitida, un modo de fusión sin equivalente): va a la consola y al resultado. */
    public static void warn(String message) {
        System.err.println("Advertencia: " + message);
        java.util.Set<String> w = WARNINGS.get();
        if (w != null) w.add(message);
    }

    /** Junta en 'into' los avisos de este hilo hasta que se llame con null. */
    public static void collectWarnings(java.util.Set<String> into) {
        if (into == null) WARNINGS.remove();
        else WARNINGS.set(into);
    }

    private Progress listener = (fraction, stage) -> { };
    private double from = 0, to = 1;

    public Bridge(String dir) {
        this.dir = new File(dir);
    }

    public void onProgress(Progress p) {
        listener = p == null ? (fraction, stage) -> { } : p;
    }

    /** La fase que empieza ocupa de 'from' a 'to' del avance total. */
    public void phase(double from, double to) {
        this.from = from;
        this.to = to;
    }

    /** Avance dentro de la fase actual (0..1). Los conversores lo llaman en sus bucles largos. */
    public void progress(double fraction, String stage) {
        listener.update(from + (to - from) * Math.max(0, Math.min(1, fraction)), stage);
    }

    /** Borra la carpeta del puente entera (los PNG intermedios ocupan cientos de MB). */
    public void clean() throws IOException {
        if (dir.exists()) delete(dir);
    }

    public void reset() throws IOException {
        if (dir.exists()) delete(dir);
        File frames = new File(dir, "frames");
        if (!frames.mkdirs()) throw new IOException("No se puede crear " + frames);
    }

    /** Guarda el PNG en el puente y devuelve su ruta relativa ("frames/pista_0001.png"). */
    public String writeFrame(String track, int frame, byte[] png) throws IOException {
        String path = "frames/" + frameName(track, frame);
        writeFile(new File(dir, path), png);
        return path;
    }

    public byte[] readFrame(FrameRef fr) throws IOException {
        return readFile(new File(dir, fr.path));
    }

    public void writeManifest(Manifest m) throws IOException {
        writeFile(new File(dir, "manifest.json"), GSON.toJson(m).getBytes(StandardCharsets.UTF_8));
        writeFile(new File(dir, "timesheet.xdts"), timesheet(m).getBytes(StandardCharsets.UTF_8));
    }

    public static String frameName(String track, int frame) {
        // La pista viene del archivo de entrada: sin separadores no puede escribir fuera del puente
        if (track == null || track.isEmpty() || track.matches(".*[/\\\\:].*")) {
            throw new IllegalArgumentException("Nombre de pista inválido: " + track);
        }
        return String.format(Locale.ROOT, "%s_%04d.png", track, frame);
    }

    /** Id de pista único y válido como nombre de archivo, a partir del nombre de una capa; lo añade a m.tracks. */
    public static String addTrack(Manifest m, String name) {
        String base = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (base.isEmpty()) base = "capa";
        String id = base;
        for (int i = 2; taken(m, id); i++) id = base + "_" + i;
        m.tracks.add(id);
        return id;
    }

    /** Windows no distingue mayúsculas: "Color" y "color" serían el mismo archivo. */
    private static boolean taken(Manifest m, String id) {
        for (String t : m.tracks) if (t.equalsIgnoreCase(id)) return true;
        return false;
    }

    public static String timesheet(Manifest m) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<timesheet>\n");
        sb.append("  <fps>").append(m.fps).append("</fps>\n");
        sb.append("  <duration>").append(m.duration).append("</duration>\n");
        for (String t : m.tracks) {
            sb.append("  <track name=\"").append(t.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")).append("\"/>\n");
        }
        return sb.append("</timesheet>\n").toString();
    }

    public static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }

    private static byte[] readFile(File f) throws IOException {
        try (InputStream is = new FileInputStream(f)) {
            return readAll(is);
        }
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(data);
        }
    }

    private static void delete(File f) throws IOException {
        // No seguir enlaces simbólicos: borraríamos archivos fuera del puente
        File canonicalParent = f.getAbsoluteFile().getParentFile().getCanonicalFile();
        boolean isLink = !f.getCanonicalFile().equals(new File(canonicalParent, f.getName()));
        if (f.isDirectory() && !isLink) {
            File[] entries = f.listFiles();
            if (entries == null) throw new IOException("No se puede listar " + f);
            for (File entry : entries) delete(entry);
        }
        if (!f.delete()) throw new IOException("No se puede borrar " + f);
    }
}
