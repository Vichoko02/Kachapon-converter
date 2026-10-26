package com.kachapon.core.converters;

import com.kachapon.core.Bridge;
import com.kachapon.core.FrameRef;
import com.kachapon.core.Manifest;
import com.kachapon.core.Planner;
import com.kachapon.core.Png;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

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
        // por tramo: 3 capas + su carpeta de frame; más la capa especial -1
        assertEquals(2 * 4 + 1, layers.size());
        int multiply = 0, folders = 0;
        for (int[] l : layers) {
            if (l[1] == 1) {
                folders++;
                assertEquals(-5, l[4], "las carpetas de frame cuelgan de la raíz");
            } else if (l[0] != -1 && l[2] == 2) {
                multiply++;
                assertEquals(50, l[3], "opacidad 128/255");
            }
        }
        assertEquals(2, folders);
        assertEquals(2, multiply, "la sombra, una vez por tramo; la oculta no se escribe");
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
        assertEquals(2, got.frames.size());
        assertEquals(3, got.frames.get(0).hold);
        assertEquals(3, got.frames.get(1).hold);
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
        assertEquals(states.size(), got.frames.size());
        for (int i = 0; i < states.size(); i++) {
            int[] expected = planner.compose(states.get(i));
            int[] read = Png.decode(check.readFrame(got.frames.get(i)));
            int worst = 0;
            for (int k = 0; k < expected.length; k++) {
                int e = expected[k], r = read[k];
                if ((e >>> 24) == 0 && (r >>> 24) == 0) continue;
                for (int sh = 0; sh < 32; sh += 8) worst = Math.max(worst, Math.abs(((e >>> sh) & 0xFF) - ((r >>> sh) & 0xFF)));
            }
            assertTrue(worst <= 3, "tramo " + i + ": diferencia máxima " + worst);
        }
    }

    /** Con capas animadas separadas hay una capa más por frame que fundiéndolas con lo de arriba. */
    @Test
    void animatedLayersCanStaySeparateOrFuse() throws Exception {
        Manifest m = scene();
        draw(m, "marco", 0, 6, 0xFFFFFFFF); // otra capa normal encima de la animada
        m.tracks.add("marco");
        m.layers.add(layer("marco", "normal", 255, true));
        File separate = new File(tmp, "separadas.ipv"), fused = new File(tmp, "fundidas.ipv");
        new IbisConverter(bridge, true).build(m, bridge, separate);
        new IbisConverter(bridge, false).build(m, bridge, fused);
        // por tramo: fondo, sombra, linea y marco frente a fondo, sombra y linea+marco
        assertEquals(2 * (4 + 1) + 1, IbisConverter.describe(separate.getPath()).size());
        assertEquals(2 * (3 + 1) + 1, IbisConverter.describe(fused.getPath()).size());
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

    @Test
    void defaultAndLegacyWriteTheirOwnVersions() throws Exception {
        Manifest m = scene();
        File normal = new File(tmp, "normal.ipv"), legacy = new File(tmp, "legacy.ipv");
        new IbisConverter(bridge).build(m, bridge, normal);
        new IbisConverter(bridge, true, true).build(m, bridge, legacy);
        assertEquals(java.util.Arrays.toString(new int[]{1410, 140100, 140100, 0x10582, 45, 3, 776, 1410, 72}), java.util.Arrays.toString(versions(normal)));
        assertEquals(java.util.Arrays.toString(new int[]{1100, 110000, 110000, 0x1044C, 41, 1, 722, 1100, 40}), java.util.Arrays.toString(versions(legacy)));
        assertTrue(IbisConverter.describe(legacy.getPath()).stream().anyMatch(l -> l[2] != 0), "legacy conserva los modos de fusión");
    }

    /** El archivo legacy se lee de vuelta con la misma imagen que la escena compuesta con sus modos de fusión. */
    @Test
    void legacyFileReadsBackLikeTheScene() throws Exception {
        Manifest m = scene();
        File ipv = new File(tmp, "legacy.ipv");
        new IbisConverter(bridge, true, true).build(m, bridge, ipv);
        Planner planner = new Planner(m, bridge, null);

        Bridge check = new Bridge(new File(tmp, "check").getPath());
        check.reset();
        Manifest got = new IbisConverter(check).extract(ipv.getPath(), check);
        List<Planner.State> states = planner.states();
        assertEquals(states.size(), got.frames.size());
        for (int i = 0; i < states.size(); i++) {
            int[] expected = planner.compose(states.get(i));
            int[] read = Png.decode(check.readFrame(got.frames.get(i)));
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
