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
            for (int k = 0; k < expected.length; k++) { // el escritor gira 180°, como en ipvWriteRead
                int e = expected[k], r = read[read.length - 1 - k];
                if ((e >>> 24) == 0 && (r >>> 24) == 0) continue;
                for (int sh = 0; sh < 32; sh += 8) worst = Math.max(worst, Math.abs(((e >>> sh) & 0xFF) - ((r >>> sh) & 0xFF)));
            }
            assertTrue(worst <= 3, "tramo " + i + ": diferencia máxima " + worst);
        }
    }
}
