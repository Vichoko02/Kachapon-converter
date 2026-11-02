package com.kachapon.core.orchestrator;

import com.kachapon.core.*;
import com.kachapon.core.converters.*;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/** origen -> puente (secuencia PNG + manifest.json + timesheet.xdts) -> destino. */
public class Orchestrator {
    private static final String CANCELLED = "Conversión cancelada.";

    private final Bridge bridge;
    private final boolean ownsBridge;
    private boolean cancelled;
    private Thread running;

    public Orchestrator(String bridgeDir) {
        this.bridge = new Bridge(bridgeDir);
        this.ownsBridge = false;
    }

    /**
     * Puente propio en la carpeta temporal del sistema, distinto en cada conversión para poder hacer varias a la vez, y que se
     * borra al terminar (con las pruebas, que dan su carpeta, no se borra). En Android java.io.tmpdir es la caché de la app.
     */
    public Orchestrator() {
        this.bridge = new Bridge(new File(System.getProperty("java.io.tmpdir"), "kachapon_" + UUID.randomUUID()).getPath());
        this.ownsBridge = true;
    }

    /** Recibe el avance de la conversión (fracción 0..1 y qué se está haciendo); se llama desde el hilo que convierte. */
    public Orchestrator onProgress(Bridge.Progress progress) {
        bridge.onProgress(progress);
        return this;
    }

    /**
     * Cancela la conversión, también si todavía está en cola, desde cualquier hilo. Si Krita está exportando se le mata en el acto;
     * lo demás se corta al siguiente dibujo. No deja el archivo a medias y el destino que ya existía queda intacto.
     */
    public synchronized void cancel() {
        cancelled = true;
        bridge.cancel();
        if (running != null) running.interrupt();
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    private synchronized boolean started() {
        running = Thread.currentThread();
        return !cancelled;
    }

    private synchronized void ended() {
        running = null;
        if (cancelled) Thread.interrupted(); // el hilo puede ser de un pool: la siguiente conversión no debe heredar el aviso
    }

    /** Origen: un archivo o una carpeta de PNG. Destino "nombre.png": carpeta "nombre_Nfps" con la secuencia. */
    public ConversionResult run(String inputPath, String outputPath) {
        java.util.Set<String> warnings = new java.util.LinkedHashSet<>();
        Bridge.collectWarnings(warnings);
        try {
            ConversionResult r = started() ? convert(inputPath, outputPath) : fail(CANCELLED);
            return new ConversionResult(r.success, r.outputPath, r.error, new java.util.ArrayList<>(warnings));
        } finally {
            ended();
            Bridge.collectWarnings(null);
            if (ownsBridge) {
                try {
                    bridge.clean();
                } catch (IOException ignored) {
                    // es una carpeta temporal: si algo la tiene abierta se queda hasta que el sistema limpie
                }
            }
        }
    }

    private ConversionResult convert(String inputPath, String outputPath) {
        File in = new File(inputPath);
        String srcExt = in.isDirectory() ? ".png" : ext(in.getName());
        String dstExt = ext(new File(outputPath).getName());
        if (!in.exists()) return fail("Archivo origen no existe.");
        if (srcExt.equals(dstExt)) return fail("El formato origen y destino no pueden ser iguales.");

        File out = new File(outputPath).getAbsoluteFile();
        File tmp = new File(out.getPath() + ".tmp");
        try {
            Converter src = converterFor(srcExt, bridge);
            Converter dst = converterFor(dstExt, bridge);
            bridge.reset();
            // Leer un .kra son decenas de exportaciones de Krita y escribir es rápido; con los demás formatos el reparto es parejo
            double split = srcExt.equals(".kra") ? 0.9 : 0.5;
            bridge.phase(0, split);
            bridge.progress(0, "Leyendo " + in.getName());
            Manifest manifest = src.extract(inputPath, bridge);
            bridge.phase(split, 1);
            bridge.progress(0, "Escribiendo " + new File(outputPath).getName());
            bridge.writeManifest(manifest);
            if (dstExt.equals(".png")) {
                out = PngSequenceConverter.folderFor(out, manifest.fps);
                tmp = new File(out.getPath() + ".tmp");
            }

            // Se construye aparte para no destruir un archivo destino existente si algo falla
            out.getParentFile().mkdirs();
            dst.build(manifest, bridge, tmp);
            if (out.exists() && !out.delete()) {
                discard(tmp);
                return fail("Ya existe " + out + " y no se puede reemplazar (si es una carpeta, bórrala o elige otra salida).");
            }
            if (!tmp.renameTo(out)) return fail("No se puede escribir " + out);
            bridge.progress(1, "Listo");
            return new ConversionResult(true, out.getPath(), null);
        } catch (OutOfMemoryError e) {
            // La animación completa se junta en memoria al escribir; sin este aviso la interfaz se quedaba sin respuesta
            discard(tmp);
            return fail("No hay memoria suficiente para esta animación. Prueba con una más corta o de menor resolución, o aumenta la memoria de Java (-Xmx).");
        } catch (Exception e) {
            discard(tmp);
            if (isCancelled()) return fail(CANCELLED);
            return fail(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /**
     * Nombre del archivo convertido: el del origen más la conversión ("escena" de .ipv a .kra: "escena_IbisToKrita.kra"),
     * para que convertir de vuelta no pise el original.
     */
    public static String outputName(String base, String srcExt, String dstExt) {
        return base + "_" + label(srcExt) + "To" + label(dstExt) + dstExt;
    }

    private static String label(String ext) {
        switch (ext.toLowerCase(Locale.ROOT)) {
            case ".kra": return "Krita";
            case ".clip": return "Clip";
            case ".ipv": return "Ibis";
            case ".png": return "PNG";
            default: return ext.replace(".", "").toUpperCase(Locale.ROOT);
        }
    }

    public static Converter converterFor(String ext, Bridge bridge) {
        switch (ext) {
            case ".kra": return new KritaConverter(bridge);
            case ".clip": return new ClipConverter(bridge);
            case ".ipv": return new IbisConverter(bridge);
            case ".png": return new PngSequenceConverter(bridge);
            default: throw new IllegalArgumentException("Formato no soportado: " + ext);
        }
    }

    private static String ext(String path) {
        int i = path.lastIndexOf('.');
        return i < 0 ? "" : path.substring(i).toLowerCase(Locale.ROOT);
    }

    /** Borra el resultado a medio hacer: un archivo o la carpeta de una secuencia (sin subcarpetas). */
    private static void discard(File f) {
        File[] files = f.listFiles();
        if (files != null) for (File x : files) x.delete();
        f.delete();
    }

    private static ConversionResult fail(String error) {
        return new ConversionResult(false, null, error);
    }
}
