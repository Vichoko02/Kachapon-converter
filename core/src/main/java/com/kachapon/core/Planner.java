package com.kachapon.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Del árbol de capas del puente a las capas que de verdad hacen falta en cada momento de la animación.
 * Para un formato destino que solo tiene capas planas (como ibisPaint) se resuelve el árbol sin cambiar la imagen:
 *  - las carpetas que no cambian nada se abren; las que mezclan sus hijos aparte, o tienen modo u opacidad, quedan
 *    como una sola imagen con ese modo y esa opacidad;
 *  - las capas seguidas en fusión normal se funden en una (con su opacidad ya aplicada al alfa);
 *  - un modo que el destino no tiene se aplica al fundir con todo lo de debajo;
 *  - las capas ocultas no se escriben.
 * Cada capa lleva una clave que identifica sus píxeles: dos momentos con las mismas claves son el mismo estado, y la
 * imagen de una capa fundida se calcula una sola vez aunque se repita en muchos estados.
 */
public final class Planner {
    /** Una capa del resultado: píxeles con modo de fusión y opacidad (0..255). Los píxeles no se modifican nunca. */
    public static final class Plane {
        public final String key, name, blend;
        public final int opacity;
        private final Supplier<int[]> pixels;

        Plane(String key, String name, String blend, int opacity, Supplier<int[]> pixels) {
            this.key = key;
            this.name = name;
            this.blend = blend;
            this.opacity = opacity;
            this.pixels = pixels;
        }

        boolean normal() {
            return "normal".equals(blend);
        }

        String signature() {
            return key + "@" + blend + "@" + opacity;
        }
    }

    /** Un tramo de la animación con las mismas capas: desde el frame start, durante hold frames. Capas de abajo arriba. */
    public static final class State {
        public final int start, hold;
        public final List<Plane> planes;

        State(int start, int hold, List<Plane> planes) {
            this.start = start;
            this.hold = hold;
            this.planes = planes;
        }
    }

    private final Manifest m;
    private final Bridge bridge;
    private final Predicate<String> supported;
    private final int w, h;
    private final Map<String, List<Manifest.Layer>> kids = new HashMap<>();
    private final Map<String, FrameRef[]> timeline = new HashMap<>();
    private final Map<String, int[]> cache;

    /** supported dice si el destino tiene ese modo de fusión (null = los tiene todos). */
    public Planner(Manifest m, Bridge bridge, Predicate<String> supported) {
        this.m = m;
        this.bridge = bridge;
        this.supported = supported == null ? mode -> true : supported;
        this.w = m.width();
        this.h = m.height();
        for (Manifest.Layer l : m.allLayers()) kids.computeIfAbsent(l.parent == null ? "" : l.parent, k -> new ArrayList<>()).add(l);
        for (String t : m.tracks) timeline.put(t, new FrameRef[Math.max(0, m.duration)]);
        for (FrameRef f : m.frames) {
            FrameRef[] line = timeline.get(f.track);
            if (line != null) for (int t = Math.max(0, f.frame); t < Math.min(m.duration, f.frame + f.hold); t++) line[t] = f;
        }
        // Las imágenes recientes se guardan para no volver a decodificarlas ni a fundirlas; el límite sale del tamaño del lienzo
        int capacity = (int) Math.max(6, 300_000_000L / (4L * Math.max(1, w) * Math.max(1, h)));
        this.cache = new LinkedHashMap<String, int[]>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, int[]> eldest) {
                return size() > capacity;
            }
        };
    }

    /** Los tramos de la animación con las mismas capas, en orden. */
    public List<State> states() {
        List<State> out = new ArrayList<>();
        String last = null;
        for (int t = 0; t < m.duration; t++) {
            List<Plane> planes = simplify(eval("", t));
            StringBuilder sig = new StringBuilder();
            for (Plane p : planes) sig.append(p.signature()).append('|');
            if (sig.toString().equals(last)) {
                State s = out.remove(out.size() - 1);
                out.add(new State(s.start, s.hold + 1, s.planes));
            } else {
                out.add(new State(t, 1, planes));
            }
            last = sig.toString();
        }
        return out;
    }

    /** La imagen del estado con todas sus capas combinadas. */
    public int[] compose(State s) {
        return compose(s.planes);
    }

    /** Píxeles de una capa del resultado (no se deben modificar). */
    public int[] pixels(Plane p) {
        int[] px = cache.get(p.key);
        if (px == null) {
            px = p.pixels.get();
            cache.put(p.key, px);
        }
        return px;
    }

    private int[] compose(List<Plane> planes) {
        int[] dst = new int[w * h];
        for (Plane p : planes) Composite.blend(dst, pixels(p), p.blend, p.opacity);
        return dst;
    }

    /** Capas visibles dentro de una carpeta (o de la raíz, parent ""), de abajo arriba, con las carpetas ya resueltas. */
    private List<Plane> eval(String parent, int t) {
        List<Plane> out = new ArrayList<>();
        for (Manifest.Layer l : kids.getOrDefault(parent, Collections.<Manifest.Layer>emptyList())) {
            if (!l.visible) continue;
            String blend = l.blend == null ? "normal" : l.blend;
            if (l.folder()) {
                List<Plane> sub = eval(l.id, t);
                if (sub.isEmpty()) continue;
                // Una carpeta sin modo ni opacidad propios se abre si sus hijos se mezclan igual dentro que fuera
                boolean plain = blend.equals("normal") && l.opacity >= 255;
                boolean same = l.passthrough || allNormal(sub);
                if (plain && same) {
                    out.addAll(sub);
                } else {
                    List<Plane> inside = sub;
                    out.add(new Plane(group("G", inside), l.name, l.passthrough ? "normal" : blend, l.opacity, () -> compose(inside)));
                }
                continue;
            }
            FrameRef fr = timeline.get(l.track) == null ? null : timeline.get(l.track)[t];
            if (fr == null) continue;
            out.add(new Plane("L:" + fr.path, l.name, blend, l.opacity, () -> decode(fr)));
        }
        return out;
    }

    private static boolean allNormal(List<Plane> planes) {
        for (Plane p : planes) if (!p.normal()) return false;
        return true;
    }

    private static String group(String kind, List<Plane> planes) {
        StringBuilder sb = new StringBuilder(kind).append('[');
        for (Plane p : planes) sb.append(p.signature()).append(',');
        return sb.append(']').toString();
    }

    /** Funde las capas seguidas en fusión normal y deja aparte las que tienen un modo que se debe conservar. */
    private List<Plane> simplify(List<Plane> planes) {
        List<Plane> out = new ArrayList<>(), run = new ArrayList<>();
        for (Plane p : planes) {
            if (!p.normal() && !supported.test(p.blend)) {
                // El destino no tiene este modo: se aplica ahora, sobre todo lo que hay debajo
                flush(run, out);
                List<Plane> below = new ArrayList<>(out);
                below.add(p);
                out.clear();
                out.add(merge(below));
            } else if (p.normal() || (out.isEmpty() && run.isEmpty())) {
                // Sin nada debajo, cualquier modo da lo mismo que normal: la capa de abajo es transparente
                run.add(p);
            } else {
                flush(run, out);
                out.add(p);
            }
        }
        flush(run, out);
        return out;
    }

    private void flush(List<Plane> run, List<Plane> out) {
        if (run.isEmpty()) return;
        if (run.size() == 1 && run.get(0).normal() && run.get(0).opacity >= 255) out.add(run.get(0));
        else out.add(merge(run));
        run.clear();
    }

    /** Una sola capa normal con estas capas ya combinadas y su opacidad aplicada. */
    private Plane merge(List<Plane> planes) {
        List<Plane> copy = new ArrayList<>(planes);
        String name = copy.size() == 1 ? copy.get(0).name : "fusión";
        return new Plane(group("M", copy), name, "normal", 255, () -> compose(copy));
    }

    private int[] decode(FrameRef fr) {
        try {
            int[] px = Png.decode(bridge.readFrame(fr));
            if (px.length != w * h) throw new IllegalArgumentException("El dibujo " + fr.path + " no mide " + w + "x" + h + ".");
            return px;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
