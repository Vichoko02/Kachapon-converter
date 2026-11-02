package com.kachapon.core;

import com.kachapon.core.orchestrator.Orchestrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchestratorTest {
    @TempDir
    File tmp;

    /** Un .clip de prueba con 'drawings' dibujos de 2 frames cada uno (duración = 2 x drawings). */
    private File clip(String name, int drawings, int argb) throws Exception {
        Bridge b = new Bridge(new File(tmp, "src_" + name).getPath());
        b.reset();
        Manifest m = new Manifest("40x30", 12, drawings * 2);
        m.tracks.add("linea");
        for (int i = 0; i < drawings; i++) {
            BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(1 + i, 1, argb);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", png);
            m.frames.add(new FrameRef("linea", i * 2, 2, b.writeFrame("linea", i * 2, png.toByteArray())));
        }
        File out = new File(tmp, name + ".clip");
        Orchestrator.converterFor(".clip").build(m, b, out);
        return out;
    }

    @Test
    void progressNeverGoesBackAndEndsAtOne() throws Exception {
        File in = clip("a", 5, 0xFFFF0000);
        List<Double> seen = new ArrayList<>();
        List<String> stages = new ArrayList<>();
        ConversionResult r = new Orchestrator()
                .onProgress((fraction, stage) -> { seen.add(fraction); stages.add(stage); })
                .run(in.getPath(), new File(tmp, "salida.png").getPath());
        assertTrue(r.success, r.error);
        assertTrue(seen.size() >= 4, "debe avisar varias veces: " + seen.size());
        for (int i = 1; i < seen.size(); i++) assertTrue(seen.get(i) >= seen.get(i - 1), "retrocede en " + i + ": " + seen);
        assertEquals(1.0, seen.get(seen.size() - 1), 1e-9);
        assertEquals("Listo", stages.get(stages.size() - 1));
    }

    /** Tres conversiones a la vez con Orchestrator(): cada una con su puente, todas terminan bien y no queda ningún puente. */
    @Test
    void severalConversionsAtOnceDoNotClashAndCleanUp() throws Exception {
        File[] inputs = {clip("uno", 3, 0xFFFF0000), clip("dos", 5, 0xFF00FF00), clip("tres", 7, 0xFF0000FF)};
        int[] frames = {6, 10, 14};
        File scratch = new File(tmp, "temporal");
        assertTrue(scratch.mkdirs());
        String previous = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", scratch.getPath()); // los puentes se crean aquí, aislados de otras ejecuciones
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ConversionResult>> jobs = new ArrayList<>();
            List<Orchestrator> made = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < 3; i++) {
                File input = inputs[i], output = new File(tmp, "out" + i + ".png");
                Orchestrator o = new Orchestrator(); // se crea ya con la propiedad cambiada
                made.add(o);
                jobs.add(pool.submit(() -> {
                    go.await();
                    return o.run(input.getPath(), output.getPath());
                }));
            }
            go.countDown();
            for (int i = 0; i < 3; i++) {
                ConversionResult r = jobs.get(i).get();
                assertTrue(r.success, "conversión " + i + ": " + r.error);
                assertEquals(frames[i], new File(r.outputPath).listFiles().length, "frames de la conversión " + i);
            }
        } finally {
            pool.shutdownNow();
            System.setProperty("java.io.tmpdir", previous);
        }
        String[] left = scratch.list((d, n) -> n.startsWith("kachapon_"));
        assertEquals(0, left == null ? 0 : left.length, "no debe quedar ningún puente temporal");
    }

    @Test
    void failedConversionAlsoCleansItsBridge() throws Exception {
        File scratch = new File(tmp, "temporal2");
        assertTrue(scratch.mkdirs());
        String previous = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", scratch.getPath());
        try {
            Orchestrator o = new Orchestrator();
            File bad = new File(tmp, "malo.clip");
            java.nio.file.Files.write(bad.toPath(), "no soy un clip".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ConversionResult r = o.run(bad.getPath(), new File(tmp, "x.kra").getPath());
            assertTrue(!r.success);
        } finally {
            System.setProperty("java.io.tmpdir", previous);
        }
        String[] left = scratch.list((d, n) -> n.startsWith("kachapon_"));
        assertEquals(0, left == null ? 0 : left.length);
    }

    /** Cancelar a mitad: falla como cancelada, no deja el temporal, el destino anterior queda intacto y el hilo sigue sirviendo. */
    @Test
    void cancelStopsTheConversionAndKeepsTheOldFile() throws Exception {
        File in = clip("c", 5, 0xFFFF0000), out = new File(tmp, "cancelada.kra");
        byte[] old = "archivo anterior".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.write(out.toPath(), old);
        Orchestrator o = new Orchestrator();
        ConversionResult r = o.onProgress((fraction, stage) -> o.cancel()).run(in.getPath(), out.getPath());
        assertTrue(!r.success);
        assertTrue(o.isCancelled());
        assertEquals("Conversión cancelada.", r.error);
        org.junit.jupiter.api.Assertions.assertArrayEquals(old, java.nio.file.Files.readAllBytes(out.toPath()));
        assertTrue(!new File(out.getPath() + ".tmp").exists());
        assertTrue(!Thread.currentThread().isInterrupted(), "la siguiente conversión del mismo hilo no debe heredar el aviso");
    }

    /** Cancelar mientras espera en la cola: ni siquiera empieza. */
    @Test
    void cancelBeforeStartingDoesNothing() throws Exception {
        File in = clip("d", 2, 0xFF00FF00), out = new File(tmp, "nunca.kra");
        Orchestrator o = new Orchestrator();
        o.cancel();
        ConversionResult r = o.run(in.getPath(), out.getPath());
        assertTrue(!r.success);
        assertTrue(!out.exists());
    }

    /** Versión de formato que lleva el MetaInfo de un .ipv (1100: el formato de ibisPaint 11). */
    private static int ipvFormatVersion(File ipv) throws IOException {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(java.nio.file.Files.readAllBytes(ipv.toPath()));
        for (int pos = 0; pos + 12 <= b.limit(); pos += 12 + b.getInt(pos + 4)) {
            if (b.getInt(pos) == 0x1000600) return b.getInt(pos + 8);
        }
        throw new IOException("sin MetaInfo");
    }

    /** El nombre lleva la conversión, así convertir de vuelta no pisa el original. */
    @Test
    void outputNameKeepsTheConversion() {
        assertEquals("escena_IbisToKrita.kra", Orchestrator.outputName("escena", ".ipv", ".kra"));
        assertEquals("escena_IbisToKrita_KritaToClip.clip", Orchestrator.outputName("escena_IbisToKrita", ".KRA", ".clip"));
        assertEquals("anim_ClipToPNG.png", Orchestrator.outputName("anim", ".clip", ".png"));
    }

    /** Toda conversión a .ipv sale en el formato de ibisPaint 11, también desde Android (que no elige nada). */
    @Test
    void ibisIsAlwaysLegacy() throws Exception {
        File in = clip("legacy", 3, 0xFFFF0000), out = new File(tmp, "legacy.ipv");
        assertTrue(new Orchestrator().run(in.getPath(), out.getPath()).success);
        assertEquals(1100, ipvFormatVersion(out));
    }
}
