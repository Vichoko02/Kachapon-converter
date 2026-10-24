package com.kachapon.core.orchestrator;

import com.kachapon.core.*;
import com.kachapon.core.converters.*;

import java.io.File;
import java.util.Locale;

/** origen -> puente (secuencia PNG + manifest.json + timesheet.xdts) -> destino. */
public class Orchestrator {
    private final Bridge bridge;

    public Orchestrator(String bridgeDir) {
        this.bridge = new Bridge(bridgeDir);
    }

    /** Puente en la carpeta temporal del sistema: no depende del directorio desde el que se abrió el programa. */
    public Orchestrator() {
        this(new File(System.getProperty("java.io.tmpdir"), "kachapon_seq").getPath());
    }

    /** Origen: un archivo o una carpeta de PNG. Destino "nombre.png": carpeta "nombre_Nfps" con la secuencia. */
    public ConversionResult run(String inputPath, String outputPath) {
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
            Manifest manifest = src.extract(inputPath, bridge);
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
            return new ConversionResult(true, out.getPath(), null);
        } catch (OutOfMemoryError e) {
            // La animación completa se junta en memoria al escribir; sin este aviso la interfaz se quedaba sin respuesta
            discard(tmp);
            return fail("No hay memoria suficiente para esta animación. Prueba con una más corta o de menor resolución, o aumenta la memoria de Java (-Xmx).");
        } catch (Exception e) {
            discard(tmp);
            return fail(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    public static Converter converterFor(String ext, Bridge bridge) {
        switch (ext) {
            case ".kra": return new KritaConverter(bridge);
            case ".clip": return new ClipConverter(bridge);
            case ".ipv": return new IbisConverter(bridge, true, Boolean.getBoolean("kachapon.ibis.legacy")); // -Dkachapon.ibis.legacy=true: formato de ibisPaint 11
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
