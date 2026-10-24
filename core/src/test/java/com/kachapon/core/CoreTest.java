package com.kachapon.core;

import com.kachapon.core.orchestrator.Orchestrator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    @TempDir
    File tmp;

    /** .ipv nativo: lo escribimos y lo volvemos a leer igual (holds, huecos, alfa, imágenes que no tocan el borde). */
    @Test
    void ipvWriteRead() throws Exception {
        Bridge ref = bridge("iref");
        Manifest expected = new Manifest("70x3", 7, 9); // más ancho que el PNG mínimo de 8: prueba el recorte y el relleno
        expected.tracks.add("linea");
        addFrame(ref, expected, "linea", 0, 2, 0xFFFF0000);
        addFrame(ref, expected, "linea", 2, 3, 0x800000FF);
        addFrame(ref, expected, "linea", 7, 1, 0xFF00FF00); // tras un hueco; el último frame queda vacío
        File ipv = new File(tmp, "anim.ipv");
        Orchestrator.converterFor(".ipv", ref).build(expected, ref, ipv);

        Bridge check = bridge("icheck");
        Manifest got = Orchestrator.converterFor(".ipv", check).extract(ipv.getPath(), check);
        assertEquals(expected.resolution, got.resolution);
        assertEquals(expected.fps, got.fps);
        assertEquals(expected.duration, got.duration);
        assertEquals(expected.frames.size(), got.frames.size());
        for (int i = 0; i < expected.frames.size(); i++) {
            FrameRef e = expected.frames.get(i), g = got.frames.get(i);
            assertEquals(e.frame, g.frame);
            assertEquals(e.hold, g.hold);
            assertArrayEquals(Png.decode(ref.readFrame(e)), Png.decode(check.readFrame(g)), "imagen " + e.path);
        }
    }

    /** .clip nativo: lo escribimos y lo volvemos a leer igual; 600x300 abarca varios bloques de 256 y varios mipmaps. */
    @Test
    void clipWriteRead() throws Exception {
        Bridge ref = bridge("cref");
        Manifest expected = new Manifest("600x300", 12, 9);
        expected.tracks.add("linea");
        // Crear 3 frames con una sola capa "default" (modo legacy)
        addFrame(ref, expected, "linea", 0, 2, 0xFFFF0000);
        addFrame(ref, expected, "linea", 2, 3, 0x800000FF);
        addFrame(ref, expected, "linea", 7, 1, 0xFF00FF00); // tras un hueco; el último frame queda vacío
        File clip = new File(tmp, "anim.clip");
        Orchestrator.converterFor(".clip", ref).build(expected, ref, clip);
        byte[] head = Files.readAllBytes(clip.toPath());
        assertEquals("CSFCHUNK", new String(head, 0, 8, StandardCharsets.US_ASCII), "debe ser un .clip nativo, no el ZIP puente");

        Bridge check = bridge("ccheck");
        Manifest got = Orchestrator.converterFor(".clip", check).extract(clip.getPath(), check);
        assertEquals(expected.resolution, got.resolution);
        assertEquals(expected.fps, got.fps);
        assertEquals(expected.duration, got.duration);
        assertSameFrames(ref, expected, check, got);
    }

    /** Varias capas animadas con holds distintos y una oculta: cada una es su carpeta de animación y vuelve igual. */
    @Test
    void clipKeepsLayers() throws Exception {
        Bridge ref = bridge("lref");
        Manifest expected = layered(ref);
        File clip = new File(tmp, "capas.clip");
        Orchestrator.converterFor(".clip", ref).build(expected, ref, clip);

        Bridge check = bridge("lcheck");
        Manifest got = Orchestrator.converterFor(".clip", check).extract(clip.getPath(), check);
        assertEquals(expected.tracks, got.tracks, "las capas, de abajo arriba");
        for (String t : expected.tracks) {
            assertEquals(expected.visible(t), got.visible(t), "visibilidad de " + t);
            assertEquals(expected.name(t), got.name(t));
        }
        assertEquals("multiply", got.layer("fondo").blend);
        assertEquals(128, got.layer("fondo").opacity);
        assertSameFrames(ref, expected, check, got);
    }

    /** Carpetas con modo de fusión y capas dentro de ellas: el árbol, los modos y la opacidad llegan al .clip y vuelven. */
    @Test
    void clipKeepsFolders() throws Exception {
        Bridge ref = bridge("fref");
        Manifest m = new Manifest("300x40", 12, 6);
        for (String t : new String[]{"fondo", "linea", "sombra"}) m.tracks.add(t);
        addFrame(ref, m, "fondo", 0, 6, 0xFF336699);
        addFrame(ref, m, "linea", 0, 3, 0xFF000000);
        addFrame(ref, m, "linea", 3, 3, 0xFFFF0000);
        addFrame(ref, m, "sombra", 0, 6, 0xFF222222);
        Manifest.Layer fondo = new Manifest.Layer("fondo", "fondo", true);
        Manifest.Layer group = new Manifest.Layer();
        group.id = "g1";
        group.name = "personaje";
        group.blend = "overlay";
        group.opacity = 200;
        Manifest.Layer linea = new Manifest.Layer("linea", "linea", true);
        linea.parent = "g1";
        Manifest.Layer sombra = new Manifest.Layer("sombra", "sombra", true);
        sombra.parent = "g1";
        sombra.blend = "multiply";
        sombra.opacity = 100;
        m.layers.addAll(java.util.Arrays.asList(fondo, group, linea, sombra));
        File clip = new File(tmp, "carpetas.clip");
        Orchestrator.converterFor(".clip", ref).build(m, ref, clip);

        Bridge check = bridge("fcheck");
        Manifest got = Orchestrator.converterFor(".clip", check).extract(clip.getPath(), check);
        assertEquals(m.tracks, got.tracks);
        Manifest.Layer g = null;
        for (Manifest.Layer l : got.layers) if (l.folder()) g = l;
        assertNotNull(g, "falta la carpeta");
        assertEquals("personaje", g.name);
        assertEquals("overlay", g.blend);
        assertEquals(200, g.opacity);
        assertEquals(g.id, got.layer("linea").parent);
        assertEquals(g.id, got.layer("sombra").parent);
        assertNull(got.layer("fondo").parent);
        assertEquals("multiply", got.layer("sombra").blend);
        assertEquals(100, got.layer("sombra").opacity);
        assertSameFrames(ref, m, check, got);
    }

    /** Fondo fijo, línea que cambia cada 2 frames y una capa oculta con un hueco. */
    private Manifest layered(Bridge b) throws IOException {
        Manifest m = new Manifest("300x40", 12, 6);
        for (String t : new String[]{"fondo", "linea", "boceto"}) m.tracks.add(t);
        m.layers.add(new Manifest.Layer("boceto", "boceto", false));
        Manifest.Layer fondo = new Manifest.Layer("fondo", "fondo", true);
        fondo.opacity = 128;
        fondo.blend = "multiply";
        m.layers.add(fondo);
        addFrame(b, m, "fondo", 0, 6, 0xFF336699);
        addFrame(b, m, "linea", 0, 2, 0xFF000000);
        addFrame(b, m, "linea", 2, 2, 0xFFFF0000);
        addFrame(b, m, "linea", 4, 2, 0x80000000);
        addFrame(b, m, "boceto", 1, 2, 0xFF00FF00);
        return m;
    }

    /** Mismas pistas con los mismos dibujos, frames y holds (el orden dentro de cada pista no importa). */
    private static void assertSameFrames(Bridge ref, Manifest expected, Bridge check, Manifest got) throws IOException {
        assertEquals(expected.frames.size(), got.frames.size(), "número de dibujos");
        for (FrameRef e : expected.frames) {
            FrameRef g = null;
            for (FrameRef x : got.frames) if (x.track.equals(e.track) && x.frame == e.frame) g = x;
            assertNotNull(g, "falta el dibujo de " + e.track + " en el frame " + e.frame);
            assertEquals(e.hold, g.hold, "hold de " + e.track + " en el frame " + e.frame);
            assertArrayEquals(Png.decode(ref.readFrame(e)), Png.decode(check.readFrame(g)), "imagen " + e.path);
        }
    }

    /** .ipv real de ibisPaint: no se incluye en el repo, se indica con KACHAPON_IPV_SAMPLE=ruta. */
    @Test
    void readsRealIbisPaintFile() throws Exception {
        String sample = System.getenv("KACHAPON_IPV_SAMPLE");
        Assumptions.assumeTrue(sample != null && new File(sample).exists(), "sin KACHAPON_IPV_SAMPLE");

        Bridge b = bridge("ipv");
        Manifest m = Orchestrator.converterFor(".ipv", b).extract(sample, b);
        assertTrue(m.fps > 0);
        assertTrue(m.frames.size() > 1, "la muestra debe ser una animación");
        int end = 0;
        for (FrameRef fr : m.frames) {
            assertEquals(end, fr.frame, "los dibujos deben ir seguidos");
            end = fr.frame + fr.hold;
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(b.readFrame(fr)));
            assertEquals(m.width(), img.getWidth());
            assertEquals(m.height(), img.getHeight());
        }
        assertEquals(m.duration, end);

        File out = new File(tmp, "real_ipv.clip");
        ConversionResult r = new Orchestrator(new File(tmp, "bridge").getPath()).run(sample, out.getPath());
        assertTrue(r.success, r.error);
    }

    /** .kra real: lo escribimos nosotros y lo lee Krita por detrás. Holds, huecos y alfa deben volver iguales. */
    @Test
    void kritaOpensWhatWeWrite() throws Exception {
        Assumptions.assumeTrue(new File("C:\\Program Files\\Krita (x64)\\bin\\krita.exe").exists() || System.getenv("KRITA_PATH") != null,
                "sin Krita instalado");
        Bridge ref = bridge("kref");
        Manifest expected = new Manifest("4x3", 12, 9);
        expected.tracks.add("linea");
        addFrame(ref, expected, "linea", 0, 2, 0xFFFF0000);
        addFrame(ref, expected, "linea", 2, 3, 0x800000FF); // semitransparente
        addFrame(ref, expected, "linea", 7, 1, 0xFF00FF00); // tras un hueco de 2 frames
        File kra = new File(tmp, "anim.kra");
        Orchestrator.converterFor(".kra", ref).build(expected, ref, kra);

        Bridge check = bridge("kcheck");
        Manifest got = Orchestrator.converterFor(".kra", check).extract(kra.getPath(), check);
        assertEquals(expected.resolution, got.resolution);
        assertEquals(expected.fps, got.fps);
        assertEquals(expected.duration, got.duration);
        assertSameFrames(ref, expected, check, got);
    }

    /** Capas por separado a través de Krita: cada capa sale con sus holds, su orden y su visibilidad. */
    @Test
    void kritaKeepsLayers() throws Exception {
        Assumptions.assumeTrue(new File("C:\\Program Files\\Krita (x64)\\bin\\krita.exe").exists() || System.getenv("KRITA_PATH") != null,
                "sin Krita instalado");
        Bridge ref = bridge("klref");
        Manifest expected = layered(ref);
        File kra = new File(tmp, "capas.kra");
        Orchestrator.converterFor(".kra", ref).build(expected, ref, kra);

        Bridge check = bridge("kl");
        Manifest got = Orchestrator.converterFor(".kra", check).extract(kra.getPath(), check);
        assertEquals(expected.tracks, got.tracks, "las capas, de abajo arriba");
        for (String t : expected.tracks) assertEquals(expected.visible(t), got.visible(t), "visibilidad de " + t);
        assertSameFrames(ref, expected, check, got);
    }

    /** Secuencia PNG: un archivo por frame (holds repetidos, huecos transparentes) y de vuelta a los mismos dibujos. */
    @Test
    void pngSequenceRoundTrip() throws Exception {
        Bridge ref = bridge("sref");
        Manifest expected = new Manifest("4x3", 12, 9);
        expected.tracks.add("linea");
        addFrame(ref, expected, "linea", 0, 2, 0xFFFF0000);
        addFrame(ref, expected, "linea", 2, 3, 0x800000FF);
        addFrame(ref, expected, "linea", 7, 1, 0xFF00FF00); // tras un hueco; el último frame queda vacío
        File clip = new File(tmp, "anim.clip");
        Orchestrator.converterFor(".clip", ref).build(expected, ref, clip);

        ConversionResult r = new Orchestrator(new File(tmp, "b1").getPath()).run(clip.getPath(), new File(tmp, "anim.png").getPath());
        assertTrue(r.success, r.error);
        File dir = new File(r.outputPath);
        assertEquals("anim_12fps", dir.getName());
        assertEquals(9, dir.listFiles().length);
        assertEquals("anim_0001.png", dir.list((d, n) -> n.endsWith("0001.png"))[0]);

        Bridge check = bridge("scheck");
        Manifest got = Orchestrator.converterFor(".png", check).extract(dir.getPath(), check);
        assertEquals(12, got.fps);
        assertEquals(9, got.duration);
        assertEquals(expected.frames.size(), got.frames.size());
for (int i = 0; i < expected.frames.size(); i++) {
            FrameRef e = expected.frames.get(i), g = got.frames.get(i);
            assertEquals(e.frame, g.frame);
            assertEquals(e.hold, g.hold);
            assertArrayEquals(Png.decode(ref.readFrame(e)), Png.decode(check.readFrame(g)), "imagen " + e.path);
        }
    }

    @Test
    void pngRoundTrip() throws Exception {
        int[] px = {0xFFFF0000, 0x00000000, 0x80123456, 0xFF00FF00, 0x7F000000, 0xFFFFFFFF};
        assertArrayEquals(px, Png.decode(Png.encode(px, 3, 2)));
        // y un PNG de otro codificador (filtros distintos)
        BufferedImage img = new BufferedImage(5, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 5; x++) img.setRGB(x, y, (x * 50) << 24 | y * 60 << 8 | x * 40);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", png);
        assertArrayEquals(img.getRGB(0, 0, 5, 4, null, 0, 5), Png.decode(png.toByteArray()));
    }

    @Test
    void badInputFailsCleanlyAndKeepsExistingOutput() throws Exception {
        File bad = new File(tmp, "bad.clip");
        Files.write(bad.toPath(), "no soy un zip".getBytes(StandardCharsets.UTF_8));
        File existing = new File(tmp, "keep.kra");
        Files.write(existing.toPath(), "original".getBytes(StandardCharsets.UTF_8));

        ConversionResult r = new Orchestrator(new File(tmp, "bridge").getPath()).run(bad.getPath(), existing.getPath());
        assertFalse(r.success);
        assertTrue(r.error.contains("Clip Studio"), r.error);
        assertEquals("original", new String(Files.readAllBytes(existing.toPath()), StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class, () -> Bridge.frameName("../fuera", 0));
        assertNull(Png.size("no es png, pero tiene más de 24 bytes".getBytes(StandardCharsets.UTF_8)));
    }

    /** .clip real de Clip Studio: no se incluye en el repo, se indica con KACHAPON_CLIP_SAMPLE=ruta. */
    @Test
    void readsRealClipStudioFile() throws Exception {
        String sample = System.getenv("KACHAPON_CLIP_SAMPLE");
        Assumptions.assumeTrue(sample != null && new File(sample).exists(), "sin KACHAPON_CLIP_SAMPLE");

        Bridge b = bridge("clip");
        Manifest m = Orchestrator.converterFor(".clip", b).extract(sample, b);
        assertTrue(m.frames.size() > 1, "la muestra debe ser una animación, no caer en la vista previa");
        for (FrameRef fr : m.frames) {
            assertTrue(m.tracks.contains(fr.track));
            assertTrue(fr.hold >= 1 && fr.frame + fr.hold <= m.duration, "hold fuera de rango en " + fr.path);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(b.readFrame(fr)));
            assertEquals(m.width(), img.getWidth());
            assertEquals(m.height(), img.getHeight());
        }

        // Y llega entero al otro formato
        File out = new File(tmp, "real.kra");
        ConversionResult r = new Orchestrator(new File(tmp, "bridge").getPath()).run(sample, out.getPath());
        assertTrue(r.success, r.error);
    }

    /** .kra real de Krita (KACHAPON_KRA_SAMPLE=ruta): llega a .clip con todas sus capas y dibujos. */
    @Test
    void realKritaFileReachesClipWithLayers() throws Exception {
        String sample = System.getenv("KACHAPON_KRA_SAMPLE");
        Assumptions.assumeTrue(sample != null && new File(sample).exists(), "sin KACHAPON_KRA_SAMPLE");

        Bridge b = bridge("kra");
        Manifest m = Orchestrator.converterFor(".kra", b).extract(sample, b);
        assertTrue(m.tracks.size() > 1, "debe salir por capas");
        File clip = new File(tmp, "real_kra.clip");
        Orchestrator.converterFor(".clip", b).build(m, b, clip);

        Bridge check = bridge("kraclip");
        Manifest got = Orchestrator.converterFor(".clip", check).extract(clip.getPath(), check);
        assertEquals(m.tracks, got.tracks);
        assertSameFrames(b, m, check, got);
    }

    private void addFrame(Bridge b, Manifest m, String track, int frame, int hold, int argb) throws IOException {
        BufferedImage img = new BufferedImage(m.width(), m.height(), BufferedImage.TYPE_INT_ARGB);
        img.setRGB(1, 1, argb);
        img.setRGB(m.width() - 2, m.height() - 1, argb); // dos puntos separados: el recorte no es trivial
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", png);
        m.frames.add(new FrameRef(track, frame, hold, b.writeFrame(track, frame, png.toByteArray())));
    }

    private Bridge bridge(String name) throws IOException {
        Bridge b = new Bridge(new File(tmp, name).getPath());
        b.reset();
        return b;
    }
}
