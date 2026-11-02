package com.kachapon.core.converters;

import com.kachapon.core.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * .ipv real de ibisPaint (formato deducido de libibispaint.so 14.1.2).
 * El archivo es una lista de chunks big-endian: u32 tipo, i32 largo, cuerpo, i32 -(8+largo).
 *  - AddCanvas (0x1000100): tamaño del lienzo.
 *  - Image (0x1000500): imagen final de una capa, en "RPNG" (tramos alternos transparente/visible + PNG con
 *    los píxeles visibles), guardada de abajo hacia arriba.
 *  - MetaInfo (0x1000600): lista de capas/carpetas (0x3000402 / 0x3000403) y ajustes de animación (0x1000604).
 * Cada carpeta de frame es un dibujo; sus capas se combinan en una sola imagen por frame.
 * Escribe .ipv nativos (ver IbisWriter) y sigue leyendo el ZIP puente de versiones anteriores de KachaponConverter.
 */
public class IbisConverter extends SequenceZipConverter {
    static final int CANVAS = 0x1000100, IMAGE = 0x1000500, META = 0x1000600;
    static final int LAYER = 0x3000402, FOLDER = 0x3000403, ANIMATION = 0x1000604;
    static final int ROOT = -5;

    /**
     * Si las imágenes del .ipv van giradas 180° respecto a como las muestra la app (capas y miniatura, al leer y al escribir).
     * El lector y el escritor giran por igual, así que una ida y vuelta nunca detecta un valor equivocado. Lo que lo decide es
     * mirar el resultado en la app. Evidencia y dudas por versión: docs/IBIS_VERSIONES.md, "Orientación".
     */
    static final boolean STORED_ROTATED_180 = true;

    public IbisConverter(Bridge bridge) {
        super(".ipv", "ibisPaint", bridge);
    }

    @Override
    public Manifest extract(String inputPath, Bridge bridge) throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(inputPath, "r")) {
            if (f.length() < 12 || f.readInt() != CANVAS) return super.extract(inputPath, bridge);
            return new Native(f).read(bridge);
        }
    }

    @Override
    public void build(Manifest m, Bridge bridge, File output) throws Exception {
        IbisWriter.write(m, bridge, output);
    }

    private static final class Layer {
        int id, blend, parent = Integer.MIN_VALUE, duration = 1;
        String mode = "normal"; // el modo de fusión como nombre de Krita
        String name = "";
        boolean folder, visible, frameFolder, trackFolder;
        float alpha;
        final List<Layer> children = new ArrayList<>();
    }

    private static final class Native {
        private final RandomAccessFile f;
        private final Map<Integer, long[]> images = new HashMap<>(); // id de capa -> {posición, largo} del cuerpo
        private int width, height;

        Native(RandomAccessFile f) {
            this.f = f;
        }

        Manifest read(Bridge bridge) throws Exception {
            byte[] meta = readChunks();
            if (width <= 0 || height <= 0) throw new Exception("El .ipv no indica el tamaño del lienzo.");
            if (meta == null) throw new Exception("El .ipv no contiene la información de capas (MetaInfo).");
            List<Layer> layers = layers(meta);
            int fps = fps(meta);

            Map<Integer, Layer> byId = new HashMap<>();
            for (Layer l : layers) byId.put(l.id, l);
            List<Layer> roots = new ArrayList<>();
            for (Layer l : layers) {
                Layer p = byId.get(l.parent);
                if (p != null && p != l) p.children.add(l);
                else if (l.parent == ROOT) roots.add(l);
            }
            // Los frames están en la raíz o dentro de carpetas de pista, en el orden de la lista
            List<Layer> frames = new ArrayList<>();
            for (Layer l : layers) {
                Layer p = byId.get(l.parent);
                if (l.frameFolder && (l.parent == ROOT || (p != null && p.trackFolder))) frames.add(l);
            }

            // Cada capa de una carpeta de frame es una pista y cada carpeta normal, una carpeta: entre frames se reconocen por su
            // nombre (ver place). Las que están fuera de los frames duran toda la animación.
            List<Slot> below = new ArrayList<>(), slots = new ArrayList<>(), above = new ArrayList<>();
            int t = 0, done = 0;
            for (Layer root : roots) {
                if (!root.frameFolder && !root.trackFolder) {
                    place(Collections.singletonList(root), done == 0 ? below : above, true, 0, -1);
                    continue;
                }
                for (Layer frame : root.frameFolder ? Collections.singletonList(root) : root.children) {
                    if (!frame.frameFolder) continue;
                    bridge.progress(done++ / (double) Math.max(1, frames.size()), "ibisPaint: leyendo frame " + done + " de " + frames.size());
                    int hold = Math.max(1, frame.duration);
                    place(frame.children, slots, frame.visible, t, hold);
                    t += hold;
                }
            }
            Manifest m = new Manifest(width + "x" + height, fps > 0 ? fps : 24, Math.max(1, t));
            for (List<Slot> group : Arrays.asList(below, slots, above)) emit(group, null, m, bridge);
            return m;
        }

        /** Una capa o carpeta a lo largo de los frames. */
        private static final class Slot {
            final String key;
            final Layer def;
            final boolean visible;
            final List<Slot> kids = new ArrayList<>();
            final List<Object[]> draws = new ArrayList<>(); // {inicio, duración, PNG o null}; en PNG porque sin comprimir no caben
            byte[] lastRaw;

            Slot(String key, Layer def, boolean visible) {
                this.key = key;
                this.def = def;
                this.visible = visible;
            }
        }

        /**
         * Reparte las capas de un frame entre las de los frames anteriores: se reconocen por nombre y, si se repite, por cuál
         * de las repetidas es (por su posición si no tiene nombre). Visibilidad, opacidad y modo van en la clave, porque una
         * pista no puede mezclar capas que se combinan distinto. Se une a una existente solo si queda encima de la capa anterior
         * del frame; si no, abre una nueva justo encima: así se respeta el orden de cada frame aunque no coincidan entre sí.
         */
        private void place(List<Layer> items, List<Slot> into, boolean inherited, int start, int hold) throws Exception {
            Map<String, Integer> seen = new HashMap<>();
            Set<Slot> used = new HashSet<>();
            int prev = -1;
            for (int k = 0; k < items.size(); k++) {
                Layer c = items.get(k);
                boolean visible = c.visible && inherited;
                String base = (c.folder ? "f" : "c") + (c.name.isEmpty() ? "#" + k : "n" + c.name + "#" + seen.merge(c.name, 1, Integer::sum))
                        + "|" + visible + "," + Math.round(c.alpha * 255) + "," + c.mode;
                Slot slot = null;
                for (int n = 1; slot == null; n++) {
                    String key = n == 1 ? base : base + "~" + n;
                    Slot existing = null;
                    for (Slot s : into) if (s.key.equals(key)) existing = s;
                    if (existing == null) {
                        slot = new Slot(key, c, visible);
                        into.add(prev + 1, slot);
                    } else if (into.indexOf(existing) > prev && !used.contains(existing)) {
                        slot = existing;
                    }
                }
                used.add(slot);
                prev = into.indexOf(slot);
                if (c.folder) {
                    place(c.children, slot.kids, true, start, hold);
                } else if (images.containsKey(c.id)) {
                    byte[] raw = rawImage(c.id);
                    Object[] last = slot.draws.isEmpty() ? null : slot.draws.get(slot.draws.size() - 1);
                    if (hold > 0 && last != null && Arrays.equals(raw, slot.lastRaw) && (Integer) last[0] + (Integer) last[1] == start) {
                        last[1] = (Integer) last[1] + hold; // la misma imagen: se alarga sin decodificarla
                    } else {
                        slot.draws.add(new Object[]{start, hold, pngOrNull(layerImage(c.id))});
                    }
                    slot.lastRaw = raw;
                }
            }
        }

        /** Pasa las capas al manifiesto; devuelve si quedó algo (las capas y carpetas sin nada dibujado no aportan). */
        private boolean emit(List<Slot> group, String parent, Manifest m, Bridge bridge) throws IOException {
            boolean any = false;
            for (Slot slot : group) {
                Layer l = slot.def;
                String name = l.name.isEmpty() ? (l.folder ? "carpeta" : "capa") : l.name;
                if (l.folder) {
                    Manifest.Layer folder = new Manifest.Layer();
                    folder.id = "f" + m.layers.size();
                    folder.name = name;
                    folder.parent = parent;
                    folder.visible = slot.visible;
                    folder.opacity = Math.round(l.alpha * 255);
                    folder.blend = l.mode;
                    m.layers.add(folder);
                    if (emit(slot.kids, folder.id, m, bridge)) any = true;
                    else m.layers.remove(folder);
                    continue;
                }
                String track = Bridge.addTrack(m, name);
                byte[] previous = null;
                FrameRef last = null;
                for (Object[] d : slot.draws) {
                    int start = (Integer) d[0], hold = (Integer) d[1] < 0 ? m.duration : (Integer) d[1];
                    byte[] png = (byte[]) d[2];
                    if (png != null && last != null && last.frame + last.hold == start && Arrays.equals(png, previous)) {
                        m.frames.set(m.frames.size() - 1, last = new FrameRef(track, last.frame, last.hold + hold, last.path)); // mismo dibujo: hold
                    } else if (png != null) {
                        m.frames.add(last = new FrameRef(track, start, hold, bridge.writeFrame(track, start, png)));
                    }
                    previous = png;
                }
                if (last == null) {
                    m.tracks.remove(track);
                    continue;
                }
                Manifest.Layer ml = new Manifest.Layer(track, name, slot.visible);
                ml.parent = parent;
                ml.opacity = Math.round(l.alpha * 255);
                ml.blend = l.mode;
                m.layers.add(ml);
                any = true;
            }
            return any;
        }

        /** Los datos de la imagen de una capa, sin la fecha ni el id del bloque (para saber si dos capas tienen la misma). */
        private byte[] rawImage(int id) throws IOException {
            long[] at = images.get(id);
            byte[] body = new byte[(int) Math.max(0, at[1] - 18)];
            f.seek(at[0] + 18);
            f.readFully(body);
            return body;
        }

        private byte[] pngOrNull(int[] px) throws IOException {
            return px == null || isBlank(px) ? null : Png.encode(px, width, height);
        }

        /** Recorre los chunks del archivo; guarda dónde está cada imagen de capa y devuelve el MetaInfo. */
        private byte[] readChunks() throws Exception {
            byte[] meta = null;
            long pos = 0, len = f.length();
            while (pos + 12 <= len) {
                f.seek(pos);
                int type = f.readInt(), l = f.readInt();
                if (l < 0 || pos + 12 + l > len) throw new Exception("El .ipv está dañado o incompleto.");
                f.seek(pos + 8 + l);
                if (f.readInt() != -(8 + l)) throw new Exception("El .ipv está dañado (fin de bloque inválido).");
                f.seek(pos + 8);
                if (type == CANVAS && l >= 16) {
                    f.readDouble(); // fecha
                    width = f.readInt();
                    height = f.readInt();
                } else if (type == IMAGE && l >= 26) {
                    f.readDouble(); // fecha
                    images.put(f.readInt(), new long[]{pos + 8, l}); // la última versión de cada capa gana
                } else if (type == META) {
                    meta = new byte[l];
                    f.readFully(meta);
                }
                pos += 12 + l;
            }
            return meta;
        }

        /** RPNG -> píxeles ARGB del lienzo, ya derechos. Ver glape::ImageIO::loadRlePng. */
        private int[] layerImage(int id) throws Exception {
            long[] at = images.get(id);
            byte[] body = new byte[(int) at[1]];
            f.seek(at[0]);
            f.readFully(body);
            ByteBuffer b = ByteBuffer.wrap(body);
            if (body.length < 26) throw new Exception("Imagen de capa vacía en el .ipv (capa " + id + ").");
            b.position(18); // fecha (8), id, versión, 2 bytes de formato
            int size = b.getInt();
            if (size <= 0 || size > body.length - 22 || b.getInt() != 0x52504e47) throw new Exception("Imagen de capa no soportada en el .ipv (capa " + id + ").");
            int headerSize = b.getInt();
            b.get(); // 0
            b.getInt(); // flags
            int w = b.getInt(), h = b.getInt(), rw = b.getInt(), rh = b.getInt(), rx = b.getInt(), ry = b.getInt();
            int pngW = b.getInt(), pngH = b.getInt(), pad = b.getInt();
            b.position(b.position() + Math.max(0, headerSize - 41)); // color de transparencia y extras
            short[] runs = new short[0];
            if (pngW != 0) {
                int n = b.getInt();
                runs = new short[n / 2];
                b.asShortBuffer().get(runs);
                b.position(b.position() + n);
            }
            byte[] png = new byte[b.getInt()];
            b.get(png);
            if (w != width || h != height) throw new Exception("La capa " + id + " no tiene el tamaño del lienzo.");
            if (rx < 0 || ry < 0 || rw < 0 || rh < 0 || rx + rw > w || ry + rh > h) throw new Exception("Rectángulo inválido en la capa " + id + ".");

            int[] src = Png.decode(png);
            int[] dims = Png.size(png);
            // shortcut: sin tabla no se vio en archivos reales; se trata como un único tramo visible
            int[] r = runs.length == 0 ? new int[]{0, rw * rh} : new int[runs.length];
            for (int i = 0; i < runs.length; i++) r[i] = runs[i] & 0xFFFF;
            return oriented(place(src, dims[0], dims[1], pad, r, rx, ry, rw, rh, w, h));
        }

        /**
         * Los tramos alternan saltado/visible y cubren el rectángulo fila a fila. El decodificador de ibisPaint
         * los recorre del último al primero y consume cada fila del PNG de derecha a izquierda tras saltar 'pad'
         * píxeles; la imagen está guardada de abajo hacia arriba.
         */
        private static int[] place(int[] src, int pw, int ph, int pad, int[] runs, int rx, int ry, int rw, int rh, int w, int h) throws Exception {
            int visible = 0, total = 0;
            for (int k = 0; k < runs.length; k++) {
                total += runs[k];
                if (k % 2 == 1) visible += runs[k];
            }
            if (total != rw * rh || pad < 0 || visible + pad > pw * ph) throw new Exception("Tabla de tramos inválida en el .ipv.");
            int[] out = new int[w * h];
            int row = pad / pw, col = pw - 1 - pad % pw; // siguiente píxel del PNG a consumir (hacia atrás dentro de la fila)
            int o = total;
            for (int k = runs.length - 1; k >= 0; k--) {
                if (k % 2 == 0) {
                    o -= runs[k];
                    continue;
                }
                for (int i = 0; i < runs[k]; i++) {
                    o--;
                    while (col < 0) {
                        row++;
                        col = pw - 1;
                    }
                    int x = rx + o % rw, y = ry + o / rw;
                    out[(h - 1 - y) * w + x] = src[row * pw + col];
                    col--;
                }
            }
            return out;
        }
    }

    /** Para las pruebas: las capas del .ipv como {id, carpeta (0/1), modo de fusión, opacidad en %, padre}. */
    static List<int[]> describe(String path) throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(path, "r")) {
            byte[] meta = new Native(f).readChunks();
            if (meta == null) throw new Exception("El .ipv no contiene la información de capas (MetaInfo).");
            List<int[]> out = new ArrayList<>();
            for (Layer l : layers(meta)) out.add(new int[]{l.id, l.folder ? 1 : 0, l.blend, Math.round(l.alpha * 100), l.parent});
            return out;
        }
    }

    /** Capas y carpetas: contador seguido de subchunks LAYER/FOLDER dentro del MetaInfo. */
    private static List<Layer> layers(byte[] meta) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(meta);
        int start = find(meta, LAYER, FOLDER);
        if (start < 4) throw new Exception("El .ipv no contiene la lista de capas.");
        int count = b.getInt(start - 4);
        List<Layer> out = new ArrayList<>();
        int p = start;
        for (int i = 0; i < count; i++) {
            int type = b.getInt(p), l = b.getInt(p + 4);
            if ((type != LAYER && type != FOLDER) || !framed(meta, p)) throw new Exception("Lista de capas dañada en el .ipv.");
            ByteBuffer r = ByteBuffer.wrap(meta, p + 8, l).slice();
            Layer x = new Layer();
            x.folder = type == FOLDER;
            x.id = r.getInt();
            int flag = r.get() & 0xFF;
            x.alpha = r.getFloat();
            x.blend = r.getInt(); // modo de fusión
            r.getInt();
            if (r.remaining() >= 4) r.getInt(); // capa de origen
            if (r.remaining() >= 2) { // nombre (UTF-8 con su largo delante)
                int n = r.getShort() & 0xFFFF;
                byte[] name = new byte[Math.min(n, r.remaining())];
                r.get(name);
                x.name = new String(name, StandardCharsets.UTF_8);
            }
            if (r.remaining() >= 4) r.getInt();
            int flag2 = r.remaining() >= 4 ? r.getInt() : 0;
            if (r.remaining() >= 4) x.parent = r.getInt();
            if (r.remaining() >= 4) r.getInt(); // capa de ajuste (0 = ninguna)
            if (r.remaining() >= 4) r.getInt(); // capa actual del frame
            if (r.remaining() >= 4) x.duration = r.getInt();
            x.mode = Blend.fromIbis(x.blend);
            x.visible = (flag & 1) != 0;
            x.frameFolder = x.folder && (flag2 & 0x200) != 0;
            x.trackFolder = x.folder && (flag2 & 0x10000) != 0;
            out.add(x);
            p += 12 + l;
        }
        return out;
    }

    private static int fps(byte[] meta) {
        int at = find(meta, ANIMATION);
        return at < 0 || ByteBuffer.wrap(meta).getInt(at + 4) < 8 ? 0 : ByteBuffer.wrap(meta).getInt(at + 12);
    }

    /** Posición del primer subchunk bien cerrado de alguno de esos tipos, o -1. */
    private static int find(byte[] meta, int... types) {
        ByteBuffer b = ByteBuffer.wrap(meta);
        for (int i = 0; i + 12 <= meta.length; i++) {
            int t = b.getInt(i);
            for (int type : types) if (t == type && framed(meta, i)) return i;
        }
        return -1;
    }

    private static boolean framed(byte[] meta, int p) {
        ByteBuffer b = ByteBuffer.wrap(meta);
        int l = b.getInt(p + 4);
        return l >= 0 && p + 12L + l <= meta.length && b.getInt(p + 8 + l) == -(8 + l);
    }

    /** De lo que se muestra a lo que se guarda, y al revés: es la misma operación. */
    static int[] oriented(int[] argb) {
        if (!STORED_ROTATED_180) return argb;
        int[] out = new int[argb.length];
        for (int i = 0; i < out.length; i++) out[i] = argb[out.length - 1 - i];
        return out;
    }

    private static boolean isBlank(int[] argb) {
        for (int c : argb) if (c >>> 24 != 0) return false;
        return true;
    }
}
