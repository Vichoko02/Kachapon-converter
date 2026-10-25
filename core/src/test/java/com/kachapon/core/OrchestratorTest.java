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
        Orchestrator.converterFor(".clip", b).build(m, b, out);
        return out;
    }

    @Test
    void progressNeverGoesBackAndEndsAtOne() throws Exception {
        File in = clip("a", 5, 0xFFFF0000);
        List<Double> seen = new ArrayList<>();
        List<String> stages = new ArrayList<>();
        ConversionResult r = new Orchestrator(new File(tmp, "bridge").getPath())
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

    /** Versión de formato que lleva el MetaInfo de un .ipv: 1410 normal, 1100 legacy. */
    private static int ipvFormatVersion(File ipv) throws IOException {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(java.nio.file.Files.readAllBytes(ipv.toPath()));
        for (int pos = 0; pos + 12 <= b.limit(); pos += 12 + b.getInt(pos + 4)) {
            if (b.getInt(pos) == 0x1000600) return b.getInt(pos + 8);
        }
        throw new IOException("sin MetaInfo");
    }

    /** La elección del formato de ibisPaint es de cada conversión: dos a la vez pueden salir una legacy y otra normal. */
    @Test
    void ibisLegacyIsChosenPerConversion() throws Exception {
        File in = clip("legacy", 3, 0xFFFF0000);
        File normal = new File(tmp, "normal.ipv"), legacy = new File(tmp, "legacy.ipv");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ConversionResult> a = pool.submit(() -> new Orchestrator().legacyIbis(false).run(in.getPath(), normal.getPath()));
            Future<ConversionResult> b = pool.submit(() -> new Orchestrator().legacyIbis(true).run(in.getPath(), legacy.getPath()));
            assertTrue(a.get().success);
            assertTrue(b.get().success);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1410, ipvFormatVersion(normal));
        assertEquals(1100, ipvFormatVersion(legacy));
    }
}
