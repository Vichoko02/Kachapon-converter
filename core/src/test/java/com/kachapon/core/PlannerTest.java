package com.kachapon.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {
    @TempDir
    File tmp;

    private Bridge bridge;

    private Manifest manifest(int duration, String... tracks) throws IOException {
        bridge = new Bridge(new File(tmp, "b").getPath());
        bridge.reset();
        Manifest m = new Manifest("8x4", 12, duration);
        for (String t : tracks) m.tracks.add(t);
        return m;
    }

    /** Un dibujo de la pista con un píxel de ese color. */
    private void draw(Manifest m, String track, int frame, int hold, int argb) throws IOException {
        BufferedImage img = new BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(1, 1, argb);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", png);
        m.frames.add(new FrameRef(track, frame, hold, bridge.writeFrame(track, frame, png.toByteArray())));
    }

    private static Manifest.Layer layer(String track, String blend, int opacity, boolean visible, String parent) {
        Manifest.Layer l = new Manifest.Layer(track, track, visible);
        l.blend = blend;
        l.opacity = opacity;
        l.parent = parent;
        return l;
    }

    private static Manifest.Layer folder(String id, String blend, int opacity, boolean passthrough) {
        Manifest.Layer l = new Manifest.Layer();
        l.id = id;
        l.name = id;
        l.blend = blend;
        l.opacity = opacity;
        l.passthrough = passthrough;
        return l;
    }

    @Test
    void normalLayersFuseIntoOne() throws Exception {
        Manifest m = manifest(4, "a", "b", "c");
        for (String t : m.tracks) draw(m, t, 0, 4, 0xFFFF0000);
        List<Planner.State> states = new Planner(m, bridge, null).states();
        assertEquals(1, states.size());
        assertEquals(4, states.get(0).hold);
        assertEquals(1, states.get(0).planes.size());
    }

    @Test
    void blendModeSeparatesNormalRuns() throws Exception {
        Manifest m = manifest(2, "a", "b", "c", "d");
        for (String t : m.tracks) draw(m, t, 0, 2, 0xFFFF0000);
        m.layers.add(layer("a", "normal", 255, true, null));
        m.layers.add(layer("b", "normal", 255, true, null));
        m.layers.add(layer("c", "multiply", 128, true, null));
        m.layers.add(layer("d", "normal", 255, true, null));
        List<Planner.Plane> planes = new Planner(m, bridge, null).states().get(0).planes;
        assertEquals(3, planes.size(), "a+b fundidas, c con su modo, d");
        assertEquals("normal", planes.get(0).blend);
        assertEquals("multiply", planes.get(1).blend);
        assertEquals(128, planes.get(1).opacity);
        assertEquals("normal", planes.get(2).blend);
    }

    @Test
    void hiddenLayersAreDropped() throws Exception {
        Manifest m = manifest(2, "a", "b");
        draw(m, "a", 0, 2, 0xFFFF0000);
        draw(m, "b", 0, 2, 0xFF00FF00);
        m.layers.add(layer("a", "normal", 255, true, null));
        m.layers.add(layer("b", "multiply", 255, false, null));
        List<Planner.Plane> planes = new Planner(m, bridge, null).states().get(0).planes;
        assertEquals(1, planes.size());
    }

    @Test
    void foldersOpenOrBecomeOneImage() throws Exception {
        Manifest m = manifest(2, "a", "b", "c");
        for (String t : m.tracks) draw(m, t, 0, 2, 0xFFFF0000);
        // g1 no cambia nada: se abre; g2 tiene un modo: queda como una sola imagen con ese modo y esa opacidad
        m.layers.add(layer("a", "normal", 255, true, null));
        m.layers.add(folder("g1", "normal", 255, false));
        m.layers.add(layer("b", "normal", 255, true, "g1"));
        m.layers.add(folder("g2", "multiply", 100, false));
        m.layers.add(layer("c", "normal", 255, true, "g2"));
        List<Planner.Plane> planes = new Planner(m, bridge, null).states().get(0).planes;
        assertEquals(2, planes.size(), "a+b fundidas y la carpeta g2 aparte");
        assertEquals("multiply", planes.get(1).blend);
        assertEquals(100, planes.get(1).opacity);
    }

    @Test
    void normalFolderWithOpacityIsBakedIntoTheRun() throws Exception {
        Manifest m = manifest(2, "a", "b");
        for (String t : m.tracks) draw(m, t, 0, 2, 0xFFFF0000);
        m.layers.add(layer("a", "normal", 255, true, null));
        m.layers.add(folder("g", "normal", 100, false));
        m.layers.add(layer("b", "normal", 255, true, "g"));
        List<Planner.Plane> planes = new Planner(m, bridge, null).states().get(0).planes;
        assertEquals(1, planes.size(), "en fusión normal la opacidad se aplica al alfa y todo se funde");
        assertEquals(255, planes.get(0).opacity);
    }

    @Test
    void statesFollowTheAnimatedLayer() throws Exception {
        Manifest m = manifest(6, "fondo", "sombra", "linea");
        draw(m, "fondo", 0, 6, 0xFF336699);
        draw(m, "sombra", 0, 6, 0xFF999999);
        draw(m, "linea", 0, 2, 0xFF000000);
        draw(m, "linea", 2, 4, 0xFFFF0000);
        m.layers.add(layer("fondo", "normal", 255, true, null));
        m.layers.add(layer("sombra", "multiply", 255, true, null));
        m.layers.add(layer("linea", "normal", 255, true, null));
        Planner p = new Planner(m, bridge, null);
        List<Planner.State> states = p.states();
        assertEquals(2, states.size());
        assertEquals(0, states.get(0).start);
        assertEquals(2, states.get(0).hold);
        assertEquals(2, states.get(1).start);
        assertEquals(4, states.get(1).hold);
        assertEquals(3, states.get(0).planes.size());
        // el fondo y la sombra son los mismos en los dos tramos: misma clave, así que se calculan una sola vez
        assertEquals(states.get(0).planes.get(0).key, states.get(1).planes.get(0).key);
        assertEquals(states.get(0).planes.get(1).key, states.get(1).planes.get(1).key);
        assertNotEquals(states.get(0).planes.get(2).key, states.get(1).planes.get(2).key);
    }

    @Test
    void bottomBlendLayerActsAsNormal() throws Exception {
        Manifest m = manifest(2, "a", "b");
        draw(m, "a", 0, 2, 0xFFFF0000);
        draw(m, "b", 0, 2, 0xFF00FF00);
        m.layers.add(layer("a", "overlay", 255, true, null));
        m.layers.add(layer("b", "normal", 255, true, null));
        assertEquals(1, new Planner(m, bridge, null).states().get(0).planes.size(), "sin nada debajo, overlay da lo mismo que normal");
    }

    @Test
    void unsupportedBlendIsFoldedIntoWhatIsBelow() throws Exception {
        Manifest m = manifest(2, "a", "b");
        draw(m, "a", 0, 2, 0xFFFF0000);
        draw(m, "b", 0, 2, 0xFF00FF00);
        m.layers.add(layer("a", "normal", 255, true, null));
        m.layers.add(layer("b", "luminize", 255, true, null));
        assertEquals(2, new Planner(m, bridge, null).states().get(0).planes.size());
        assertEquals(1, new Planner(m, bridge, mode -> !mode.equals("luminize")).states().get(0).planes.size());
    }

    @Test
    void composeAppliesOpacityAndBlend() throws Exception {
        Manifest m = manifest(1, "a", "b");
        draw(m, "a", 0, 1, 0xFF808080);
        draw(m, "b", 0, 1, 0xFF808080);
        m.layers.add(layer("a", "normal", 255, true, null));
        m.layers.add(layer("b", "multiply", 255, true, null));
        Planner p = new Planner(m, bridge, null);
        int v = p.compose(p.states().get(0))[1 * 8 + 1];
        assertEquals(0xFF, v >>> 24, "opaco");
        assertTrue(Math.abs(((v >> 16) & 0xFF) - 0x40) <= 1, "multiplicar 0,5 por 0,5 da 0,25");
    }
}
