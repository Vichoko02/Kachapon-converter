package com.kachapon.core.converters;

import com.kachapon.core.Bridge;
import com.kachapon.core.FrameRef;
import com.kachapon.core.Manifest;
import com.kachapon.core.Planner;
import com.kachapon.core.Png;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IbisWriterTest {
    @TempDir
    File tmp;

    private Bridge bridge;

    private void draw(Manifest m, String track, int frame, int hold, int argb) throws IOException {
        BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(5, 5, argb);
        img.setRGB(30, 20, argb);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", png);
        m.frames.add(new FrameRef(track, frame, hold, bridge.writeFrame(track, frame, png.toByteArray())));
    }

    private static Manifest.Layer layer(String track, String blend, int opacity, boolean visible) {
        Manifest.Layer l = new Manifest.Layer(track, track, visible);
        l.blend = blend;
        l.opacity = opacity;
        return l;
    }

    /** Fondo, sombra en multiplicar al 50 %, línea animada y una capa oculta: dos tramos, tres capas cada uno. */
    private Manifest scene() throws IOException {
        bridge = new Bridge(new File(tmp, "b").getPath());
        bridge.reset();
        Manifest m = new Manifest("40x30", 12, 6);
        for (String t : new String[]{"fondo", "sombra", "linea", "oculta"}) m.tracks.add(t);
        draw(m, "fondo", 0, 6, 0xFF336699);
        draw(m, "sombra", 0, 6, 0xFF999999);
        draw(m, "linea", 0, 3, 0xFF000000);
        draw(m, "linea", 3, 3, 0xFFFF0000);
        draw(m, "oculta", 0, 6, 0xFF00FF00);
        m.layers.add(layer("fondo", "normal", 255, true));
        m.layers.add(layer("sombra", "multiply", 128, true));
        m.layers.add(layer("linea", "normal", 255, true));
        m.layers.add(layer("oculta", "multiply", 255, false));
        return m;
    }

    @Test
    void writesOneLayerPerPlaneWithBlendAndOpacity() throws Exception {
        Manifest m = scene();
        File ipv = new File(tmp, "escena.ipv");
        new IbisConverter(bridge).build(m, bridge, ipv);

        List<int[]> layers = IbisConverter.describe(ipv.getPath());
        // por tramo: 4 capas (también la oculta) + su carpeta de frame; más la capa especial -1
        assertEquals(2 * 5 + 1, layers.size());
        int multiply = 0, folders = 0;
        for (int[] l : layers) {
            if (l[1] == 1) {
                folders++;
                assertEquals(-5, l[4], "las carpetas de frame cuelgan de la raíz");
            } else if (l[0] != -1 && l[2] == 2 && l[3] != 100) {
                multiply++;
                assertEquals(50, l[3], "opacidad 128/255");
            }
        }
        assertEquals(2, folders);
        assertEquals(2, multiply, "la sombra, una vez por tramo");
    }

    @Test
    void sameSceneReadsBackWithEveryFrame() throws Exception {
        Manifest m = scene();
        File ipv = new File(tmp, "escena.ipv");
        new IbisConverter(bridge).build(m, bridge, ipv);

        Bridge check = new Bridge(new File(tmp, "check").getPath());
        check.reset();
        Manifest got = new IbisConverter(check).extract(ipv.getPath(), check);
        assertEquals(6, got.duration);
        assertTrue(got.tracks.size() > 1, "las capas se conservan al leer: " + got.tracks);
        List<Planner.State> read = new Planner(got, check, null).states();
        assertEquals(2, read.size());
        assertEquals(3, read.get(0).hold);
        assertEquals(3, read.get(1).hold);
    }

    /** Escribir y leer devuelve la misma imagen en cada tramo: los modos de fusión y la opacidad se aplican igual al leer. */
    @Test
    void blendAndOpacitySurviveTheRoundTrip() throws Exception {
        Manifest m = scene();
        File ipv = new File(tmp, "escena.ipv");
        new IbisConverter(bridge).build(m, bridge, ipv);
        Planner planner = new Planner(m, bridge, null);

        Bridge check = new Bridge(new File(tmp, "check").getPath());
        check.reset();
        Manifest got = new IbisConverter(check).extract(ipv.getPath(), check);
        List<Planner.State> states = planner.states();
        Planner reread = new Planner(got, check, null);
        for (int i = 0; i < states.size(); i++) {
            int[] expected = planner.compose(states.get(i));
            int[] read = composeAt(reread, states.get(i).start);
            int worst = 0;
            for (int k = 0; k < expected.length; k++) {
                int e = expected[k], r = read[k];
                if ((e >>> 24) == 0 && (r >>> 24) == 0) continue;
                for (int sh = 0; sh < 32; sh += 8) worst = Math.max(worst, Math.abs(((e >>> sh) & 0xFF) - ((r >>> sh) & 0xFF)));
            }
            assertTrue(worst <= 3, "tramo " + i + ": diferencia máxima " + worst);
        }
    }

    /** {versión de formato, NeedVersionForPlay, NeedVersionForEdit, inicio de edición, cabecera RPNG, banderas RPNG, largo de la cola, tercera versión, largo de la animación}. */
    private static int[] versions(File ipv) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(java.nio.file.Files.readAllBytes(ipv.toPath()));
        int meta = -1, metaLen = 0, edit = 0, header = 0, flags = 0;
        for (int pos = 0; pos + 12 <= b.limit(); ) {
            int type = b.getInt(pos), len = b.getInt(pos + 4);
            if (type == 0x1000200) edit = b.getInt(pos + 8);
            if (type == 0x1000600) { meta = pos + 8; metaLen = len; }
            if (type == 0x1000500 && header == 0 && b.getInt(pos + 8 + 18) > 0 && b.getInt(pos + 8 + 22) == 0x52504e47) {
                header = b.getInt(pos + 8 + 26);
                flags = b.getInt(pos + 8 + 31);
            }
            pos += 12 + len;
        }
        // La cola va tras la lista de capas: el entero anterior a la primera capa dice cuántos registros hay
        int body = meta, first = -1;
        for (int p = body + 4; p + 12 <= b.limit(); p++) {
            int t = b.getInt(p), l = b.getInt(p + 4);
            if ((t == 0x3000402 || t == 0x3000403) && l >= 0 && p + 12 + l <= b.limit() && b.getInt(p + 8 + l) == -(8 + l)) { first = p; break; }
        }
        int count = b.getInt(first - 4), p = first;
        for (int i = 0; i < count; i++) p += 12 + b.getInt(p + 4);
        return new int[]{b.getInt(body), b.getInt(p + 60), b.getInt(p + 64), edit, header, flags, body + metaLen - p, b.getInt(p + 606), b.getInt(p + 656)};
    }

    /** El árbol entero vuelve igual: carpetas anidadas, capa oculta, modos y opacidad, sin fundir nada. */
    @Test
    void wholeTreeSurvivesTheRoundTrip() throws Exception {
        Manifest m = scene();
        Manifest.Layer outer = new Manifest.Layer(), inner = new Manifest.Layer();
        outer.id = "a"; outer.name = "exterior"; outer.opacity = 200; outer.blend = "multiply";
        inner.id = "b"; inner.name = "interior"; inner.parent = "a"; inner.visible = false;
        m.layers.get(1).parent = "a";
        m.layers.get(3).parent = "b";
        m.layers.add(1, outer);
        m.layers.add(2, inner);
        File ipv = new File(tmp, "arbol.ipv");
        new IbisConverter(bridge).build(m, bridge, ipv);

        Bridge check = new Bridge(new File(tmp, "check").getPath());
        check.reset();
        Manifest got = new IbisConverter(check).extract(ipv.getPath(), check);
        assertEquals(4, got.tracks.size());
        assertEquals(m.frames.size(), got.frames.size(), "un dibujo con su hold, no uno por tramo");
        List<String> expected = new java.util.ArrayList<>(), read = new java.util.ArrayList<>();
        for (Manifest.Layer l : m.layers) expected.add(describe(m, l));
        for (Manifest.Layer l : got.layers) read.add(describe(got, l));
        java.util.Collections.sort(expected);
        java.util.Collections.sort(read);
        assertEquals(expected, read);
        Planner a = new Planner(m, bridge, null), b = new Planner(got, check, null);
        for (int t : new int[]{0, 3}) assertArrayEquals(composeAt(a, t), composeAt(b, t), "frame " + t);
    }

    private static String describe(Manifest m, Manifest.Layer l) {
        StringBuilder path = new StringBuilder(l.name);
        for (Manifest.Layer p = m.parent(l); p != null; p = m.parent(p)) path.insert(0, p.name + "/");
        return path + " " + l.visible + " " + l.opacity + " " + l.blend;
    }

    /** La imagen combinada de lo leído en el frame t (un tramo vacío es transparente). */
    private static int[] composeAt(Planner p, int t) {
        for (Planner.State s : p.states()) if (s.start <= t && t < s.start + s.hold) return p.compose(s);
        throw new AssertionError("sin tramo para el frame " + t);
    }

    /** El PNG de la miniatura: el bloque de imagen de la capa -1 en formato 0. */
    private static byte[] thumbnail(File ipv) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(java.nio.file.Files.readAllBytes(ipv.toPath()));
        byte[] thumb = null;
        for (int p = 0; p + 12 <= b.limit(); p += 12 + b.getInt(p + 4)) {
            if (b.getInt(p) == 0x1000500 && b.getInt(p + 16) == -1 && b.get(p + 24) == 0) {
                thumb = new byte[b.getInt(p + 26)];
                b.position(p + 30);
                b.get(thumb);
            }
        }
        return thumb;
    }

    /** La miniatura se guarda con la misma orientación que las capas: es la escena del frame 0 pasada por oriented. */
    @Test
    void thumbnailIsOrientedLikeTheLayers() throws Exception {
        Manifest m = scene();
        File ipv = new File(tmp, "giro.ipv");
        new IbisConverter(bridge).build(m, bridge, ipv);
        Planner planner = new Planner(m, bridge, null);
        assertArrayEquals(IbisConverter.oriented(planner.compose(planner.states().get(0))), Png.decode(thumbnail(ipv)));
    }

    /** Una ida y vuelta no ve la orientación, pero en una obra real la miniatura la compuso ibisPaint y tiene que coincidir con sus capas. */
    @Test
    void realThumbnailMatchesItsLayers() throws Exception {
        String sample = System.getenv("KACHAPON_IPV_SAMPLE");
        Assumptions.assumeTrue(sample != null && new File(sample).exists(), "sin KACHAPON_IPV_SAMPLE");
        int[] thumb = Png.decode(thumbnail(new File(sample)));
        Bridge b = new Bridge(new File(tmp, "real").getPath());
        b.reset();
        Planner planner = new Planner(new IbisConverter(b).extract(sample, b), b, null);
        double best = 100;
        for (Planner.State s : planner.states()) {
            if (!s.planes.isEmpty()) best = Math.min(best, percentDifferent(IbisConverter.oriented(planner.compose(s)), thumb));
        }
        assertTrue(best < 0.01, "la miniatura no coincide con ningún frame: " + best + " %");
    }

    /** Sobre blanco, porque la miniatura puede no tener alfa: píxeles con algún canal a más de 8 niveles, sobre los que no son blancos. */
    private static double percentDifferent(int[] x, int[] y) {
        long bad = 0, seen = 0;
        for (int i = 0; i < x.length; i++) {
            int p = overWhite(x[i]), q = overWhite(y[i]);
            if (p == 0xFFFFFF && q == 0xFFFFFF) continue;
            seen++;
            for (int sh = 0; sh < 24; sh += 8) {
                if (Math.abs(((p >> sh) & 0xFF) - ((q >> sh) & 0xFF)) > 8) {
                    bad++;
                    break;
                }
            }
        }
        return 100.0 * bad / Math.max(1, seen);
    }

    private static int overWhite(int argb) {
        int a = argb >>> 24, out = 0;
        for (int sh = 0; sh < 24; sh += 8) out |= ((((argb >> sh) & 0xFF) * a + 255 * (255 - a)) / 255) << sh;
        return out;
    }

    @Test
    void writesTheLegacyVersions() throws Exception {
        Manifest m = scene();
        File legacy = new File(tmp, "legacy.ipv");
        new IbisConverter(bridge).build(m, bridge, legacy);
        assertEquals(java.util.Arrays.toString(new int[]{1100, 110000, 110000, 0x1044C, 41, 1, 722, 1100, 40}), java.util.Arrays.toString(versions(legacy)));
        assertTrue(IbisConverter.describe(legacy.getPath()).stream().anyMatch(l -> l[2] != 0), "legacy conserva los modos de fusión");
    }

    /** El archivo legacy se lee de vuelta con la misma imagen que la escena compuesta con sus modos de fusión. */
    @Test
    void legacyFileReadsBackLikeTheScene() throws Exception {
        Manifest m = scene();
        File ipv = new File(tmp, "legacy.ipv");
        new IbisConverter(bridge).build(m, bridge, ipv);
        Planner planner = new Planner(m, bridge, null);

        Bridge check = new Bridge(new File(tmp, "check").getPath());
        check.reset();
        Manifest got = new IbisConverter(check).extract(ipv.getPath(), check);
        List<Planner.State> states = planner.states();
        Planner reread = new Planner(got, check, null);
        for (int i = 0; i < states.size(); i++) {
            int[] expected = planner.compose(states.get(i));
            int[] read = composeAt(reread, states.get(i).start);
            int worst = 0;
            for (int k = 0; k < expected.length; k++) {
                int e = expected[k], r = read[k];
                if ((e >>> 24) == 0 && (r >>> 24) == 0) continue;
                for (int sh = 0; sh < 32; sh += 8) worst = Math.max(worst, Math.abs(((e >>> sh) & 0xFF) - ((r >>> sh) & 0xFF)));
            }
            assertTrue(worst <= 3, "tramo " + i + ": diferencia máxima " + worst);
        }
    }
}
