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
 * Escribe .ipv nativos (ver build) y sigue leyendo el ZIP puente de versiones anteriores de KachaponConverter.
 */
public class IbisConverter extends SequenceZipConverter {
    private static final int CANVAS = 0x1000100, IMAGE = 0x1000500, META = 0x1000600;
    private static final int LAYER = 0x3000402, FOLDER = 0x3000403, ANIMATION = 0x1000604;
    private static final int ROOT = -5;

    // Versión del formato que se escribe: la de ibisPaint 11.0.0, la primera con animación, para que más versiones de la app abran
    // el archivo (docs/IBIS_VERSIONES.md): lo que impide abrirlo en una app vieja son NeedVersionForPlay/Edit, no el resto.
    private static final int FORMAT_VERSION = 1100, TEMPLATE_VERSION = 1410; // TEMPLATE_VERSION: la de la plantilla META_TAIL
    private static final int NEED_VERSION = 110000; // NeedVersionForPlay y NeedVersionForEdit: app x 10000
    private static final int TAIL_NEED_PLAY = 60, TAIL_NEED_EDIT = 64, TAIL_FORMAT_VERSION = 606; // las tres versiones de la cola

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

    /**
     * Escribe un .ipv nativo que ibisPaint importa: lienzo, una sesión de edición vacía (no hace falta historial), una
     * carpeta de frame por tramo de la animación con el árbol del documento (capas, carpetas, ocultas, modos y opacidad),
     * la imagen combinada para la miniatura y el MetaInfo. Una capa sin dibujo en un tramo no se escribe en ese tramo.
     * Nada se funde, así que una escena grande pesa mucho: ibisPaint repite cada capa en cada frame.
     */
    @Override
    public void build(Manifest m, Bridge bridge, File output) throws Exception {
        if (m.duration <= 0) throw new Exception("La animación no tiene frames.");
        TreeSet<Integer> cuts = new TreeSet<>(Collections.singleton(0));
        for (FrameRef f : m.frames) {
            if (f.frame > 0 && f.frame < m.duration) cuts.add(f.frame);
            if (f.frame + f.hold < m.duration) cuts.add(f.frame + f.hold);
        }
        Planner planner = new Planner(m, bridge, null);
        int w = m.width(), h = m.height();
        double now = System.currentTimeMillis() / 1000.0;
        String art = artId();
        String title = output.getName().replaceAll("\\.tmp$", "").replaceAll("(?i)\\.ipv$", "");

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream c = new DataOutputStream(b);
            c.writeDouble(now); c.writeInt(w); c.writeInt(h); str(c, art); c.writeByte(1);
            chunk(out, CANVAS, b);

            c.writeInt(0x10000 | FORMAT_VERSION); c.writeDouble(now); str(c, "ibisPaint X"); str(c, "ver.11.0.0"); str(c, "KachaponConverter");
            c.write(new byte[8]); c.writeInt(1); c.writeInt(1); c.writeFloat(0.05f); c.writeFloat(0.05f); c.writeInt(2); c.writeInt(1);
            chunk(out, START_EDIT, b);
            c.writeDouble(now); c.writeDouble(1); c.writeShort(0);
            chunk(out, END_EDIT, b);

            ByteArrayOutputStream layers = new ByteArrayOutputStream();
            DataOutputStream l = new DataOutputStream(layers);
            Tree tree = new Tree(m, bridge, out, l, now);
            int lastFolder = 0, stateNo = 0;
            for (int start : cuts) {
                Integer next = cuts.higher(start);
                int hold = (next == null ? m.duration : next) - start;
                bridge.progress(stateNo++ / (double) cuts.size(), "ibisPaint: carpeta de frame " + stateNo + " de " + cuts.size());
                lastFolder = tree.frame(start, hold);
            }
            int count = tree.count;
            // Capa especial -1 (sin padre, 50 %) con imagen vacía, y el lienzo combinado en PNG normal para la miniatura
            imageChunk(out, now, -1, 1, 4, new byte[0]);
            imageChunk(out, now, -1, 0, 0, Png.encode(rotated(composeFirst(planner, w, h)), w, h));
            layerSub(l, false, true, -1, 0.5f, 0, "", Integer.MIN_VALUE, 0, Integer.MIN_VALUE, -1);

            c.writeInt(FORMAT_VERSION); str(c, art); str(c, title); str(c, ""); str(c, ""); c.writeInt(0); c.writeInt(0); str(c, ""); str(c, "");
            c.writeDouble(now); c.writeDouble(now); c.writeDouble(now); c.writeLong(0); str(c, ""); c.writeInt(w); c.writeInt(h);
            str(c, ""); c.writeInt(1); str(c, ""); c.writeInt(lastFolder);
            c.writeInt(count);
            layers.writeTo(c);
            byte[] tail = legacyTail(metaTail());
            ByteBuffer t = ByteBuffer.wrap(tail);
            t.putInt(TAIL_WIDTH, w).putInt(TAIL_HEIGHT, h).putInt(TAIL_FPS, m.fps).putInt(TAIL_CURRENT_FRAME, lastFolder);
            t.putInt(TAIL_NEED_PLAY, NEED_VERSION).putInt(TAIL_NEED_EDIT, NEED_VERSION).putInt(TAIL_FORMAT_VERSION, FORMAT_VERSION);
            c.write(tail);
            chunk(out, META, b);
        }
    }

    private static int[] composeFirst(Planner planner, int w, int h) {
        List<Planner.State> states = planner.states();
        return states.get(0).planes.isEmpty() ? new int[w * h] : planner.compose(states.get(0));
    }

    /** Escribe el árbol del documento dentro de cada carpeta de frame: hijos primero y después su carpeta, como guarda ibisPaint. */
    private static final class Tree {
        private final Manifest m;
        private final Bridge bridge;
        private final DataOutputStream images, records;
        private final double now;
        private final Map<String, List<Manifest.Layer>> kids = new HashMap<>();
        private final Map<String, FrameRef[]> at = new HashMap<>();
        private final Map<String, byte[]> rpngs = new HashMap<>(); // ya comprimido: el mismo dibujo se repite en muchos frames
        private byte[] blank;
        int count = 1, id = 2, top;

        Tree(Manifest m, Bridge bridge, DataOutputStream images, DataOutputStream records, double now) {
            this.m = m;
            this.bridge = bridge;
            this.images = images;
            this.records = records;
            this.now = now;
            for (Manifest.Layer l : m.allLayers()) kids.computeIfAbsent(l.parent == null ? "" : l.parent, k -> new ArrayList<>()).add(l);
            for (String t : m.tracks) at.put(t, new FrameRef[m.duration]);
            for (FrameRef f : m.frames) {
                FrameRef[] line = at.get(f.track);
                if (line != null) for (int t = Math.max(0, f.frame); t < Math.min(m.duration, f.frame + f.hold); t++) line[t] = f;
            }
        }

        /** Una carpeta de frame con lo que hay en el frame t; devuelve su id. */
        int frame(int t, int hold) throws IOException {
            int folderId = id++;
            top = 0;
            children("", folderId, t);
            if (top == 0) { // hueco: una capa transparente para que el frame exista
                if (blank == null) blank = rpng(new int[m.width() * m.height()], m.width(), m.height());
                paint(blank, true, 1f, 0, "", folderId);
            }
            layerSub(records, true, true, folderId, 1f, 0, "", ROOT, 0x200, top, hold);
            count++;
            return folderId;
        }

        private void children(String parent, int parentId, int t) throws IOException {
            for (Manifest.Layer l : kids.getOrDefault(parent, Collections.emptyList())) {
                float alpha = l.opacity / 255f;
                int mode = mode(l);
                if (l.folder()) {
                    if (!shows(l, t)) continue;
                    if (l.passthrough) Bridge.warn("la carpeta \"" + l.name + "\" es de paso: ibisPaint no lo tiene y va como carpeta normal.");
                    int folderId = id++;
                    children(l.id, folderId, t);
                    layerSub(records, true, l.visible, folderId, alpha, mode, l.name, parentId, 0, Integer.MIN_VALUE, -1);
                    count++;
                } else {
                    FrameRef[] line = at.get(l.track);
                    FrameRef f = line == null ? null : line[t];
                    if (f == null) continue;
                    byte[] data = rpngs.get(f.path);
                    if (data == null) {
                        data = rpng(rotated(Png.decode(bridge.readFrame(f))), m.width(), m.height());
                        rpngs.put(f.path, data);
                    }
                    paint(data, l.visible, alpha, mode, l.name, parentId);
                }
            }
        }

        private void paint(byte[] data, boolean visible, float alpha, int mode, String name, int parentId) throws IOException {
            int layerId = id++;
            imageChunk(images, now, layerId, 1, 4, data);
            layerSub(records, false, visible, layerId, alpha, mode, name == null ? "" : name, parentId, 0, Integer.MIN_VALUE, -1);
            top = layerId; // la capa actual del frame es la última escrita, la de más arriba
            count++;
        }

        private boolean shows(Manifest.Layer folder, int t) {
            for (Manifest.Layer l : kids.getOrDefault(folder.id, Collections.emptyList())) {
                if (l.folder() ? shows(l, t) : at.containsKey(l.track) && at.get(l.track)[t] != null) return true;
            }
            return false;
        }

        private static int mode(Manifest.Layer l) {
            int mode = Blend.toIbis(l.blend == null ? "normal" : l.blend);
            if (mode >= 0) return mode;
            Bridge.warn("el modo de fusión \"" + l.blend + "\" no existe en ibisPaint; se usa normal.");
            return 0;
        }
    }

    // Final del MetaInfo (paletas, ajustes y animación) tomado de un .ipv real de ibisPaint 14.1.2 sin sus textos
    // (usuario, título, descripción, UUID). shortcut: plantilla fija; si ibisPaint cambia el formato habrá que regenerarla.
    private static final String META_TAIL =
            "00000000000000010000000000000000000000030000000200000000000000ff3f800000000000000000ffff00000000000200000000000000000000" +
            "000223440002234400000004010006010000000e0000000000010000000000000000ffffffea010006010000000e0001000000030000000200000000" +
            "ffffffea010006010000000e0002000000080000000240c00000ffffffea010006010000000e000300000003000000003f800000ffffffea0000ffff" +
            "3f80000000f064640000000100000001300009000000003100000001ff000000000000000130000b000000001043838e7d438b745841df1a5041b4f8" +
            "a0ffffffe80000000000000000ffffffc70000ffffffff000000000603000900000000050000000000fffffff3030009000000001101000000033f00" +
            "00003f0000007fc00000ffffffe7030009000000001502000000043f0000003f000000421000007fc00000ffffffe303000900000000150300000004" +
            "3f0000003f000000421000007fc00000ffffffe3030009000000003d040000000e3e8000003e8000003f4000003e8000003f4000003f4000003e8000" +
            "003f4000004040000040400000000000007fc000000000000000000000ffffffbb0300090000000081050000001f3ee666663e8000003f4000003eaa" +
            "aaaa3f4000003f2aaaab3ee666663f4000003e8000003f2aaaab3e8000003eaaaaaa0000000000000000404000004040000040400000000000000000" +
            "0000000000000000000000000000000000007fc0000000000000000000000000000000000000000000000000000000000000ffffff77000000000000" +
            "000000000000000005820000028000000280fffffffd000000000000000001ffffffff00000000000000280000000001000000010100060400000048" +
            "00000001000000070000000000000001000000010000003200000032ff0000ff00ff00ff000001800000000000000000000000003e4ccccd3f800000" +
            "000000010000000080000000ffffffb000000000000000000000000000000000000000000000000000000000000000000000000000000000";
    private static final int TAIL_WIDTH = 610, TAIL_HEIGHT = 614, TAIL_FPS = 664, TAIL_CURRENT_FRAME = 696;
    private static final int START_EDIT = 0x1000200, END_EDIT = 0x1000300;

    /**
     * La cola de MetaInfo como la escribe ibisPaint 14.0.0 (docs/IBIS_VERSIONES.md): sin lo que agregó la 14.1, que son 32 bytes al final
     * del subchunk de animación (el largo pasa de 72 a 40 y su cierre de -80 a -48) y 22 bytes al final de MetaInfo. Los offsets que se
     * parchean (ancho, alto, fps, frame actual) quedan antes de los recortes y no se mueven. Una app más vieja que la 14.1 ignora lo que
     * sobra, pero una cola igual a la de una obra real de la 14.0.0 es lo más seguro.
     */
    private static byte[] legacyTail(byte[] tail) {
        ByteBuffer t = ByteBuffer.wrap(tail);
        int anim = 652, body = anim + 8, end = body + 72, keep = 40;
        if (tail.length != 776 || t.getInt(anim) != ANIMATION || t.getInt(anim + 4) != 72 || t.getInt(end) != -(8 + 72)
                || t.getInt(TAIL_FORMAT_VERSION) != TEMPLATE_VERSION || t.getInt(TAIL_NEED_PLAY) != 140100) {
            throw new IllegalStateException("La plantilla de MetaInfo no es la esperada");
        }
        byte[] out = new byte[tail.length - (72 - keep) - 22];
        System.arraycopy(tail, 0, out, 0, body + keep);                                  // hasta el final de los 40 bytes que se conservan
        System.arraycopy(tail, end, out, body + keep, out.length - (body + keep));      // cierre del subchunk y lo que sigue, sin los últimos 22 bytes
        ByteBuffer o = ByteBuffer.wrap(out);
        o.putInt(anim + 4, keep).putInt(body + keep, -(8 + keep));
        return out;
    }

    private static byte[] metaTail() {
        byte[] b = new byte[META_TAIL.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(META_TAIL.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    private static String artId() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    /** Escribe el bloque (tipo, largo, cuerpo, -(8+largo)) y vacía el buffer para el siguiente. */
    private static void chunk(DataOutputStream out, int type, ByteArrayOutputStream body) throws IOException {
        out.writeInt(type);
        out.writeInt(body.size());
        body.writeTo(out);
        out.writeInt(-(8 + body.size()));
        body.reset();
    }

    private static void imageChunk(DataOutputStream out, double now, int layerId, int version, int format, byte[] data) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(b);
        c.writeDouble(now); c.writeInt(layerId); c.writeInt(version); c.writeByte(format); c.writeByte(0);
        c.writeInt(data.length); c.write(data);
        chunk(out, IMAGE, b);
    }

    /** Capa o carpeta del MetaInfo; blend es el número de modo de fusión de ibisPaint (0 = normal) y alpha la opacidad (0..1). */
    private static void layerSub(DataOutputStream out, boolean folder, boolean visible, int id, float alpha, int blend, String name, int parent,
                                 int flag2, int frameCurrent, int duration) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(b);
        c.writeInt(id); c.writeByte(visible ? 1 : 0); c.writeFloat(alpha); c.writeInt(blend); c.writeInt(-1);
        c.writeInt(Integer.MIN_VALUE); str(c, name); c.writeInt(-1); c.writeInt(flag2); c.writeInt(parent);
        c.writeInt(0); c.writeInt(frameCurrent); c.writeInt(duration);
        chunk(out, folder ? FOLDER : LAYER, b);
    }

    private static void str(DataOutputStream c, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        c.writeShort(b.length);
        c.write(b);
    }

    /** Inverso de place(): filas de abajo arriba, tramos saltado/visible sobre el rectángulo usado y PNG consumido al revés. */
    static byte[] rpng(int[] argb, int w, int h) throws IOException {
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (argb[(h - 1 - y) * w + x] >>> 24 == 0) continue;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        }
        boolean empty = maxX < 0;
        int rx = empty ? 0 : minX, ry = empty ? 0 : minY;
        int rw = empty ? 1 : maxX - minX + 1, rh = empty ? 1 : maxY - minY + 1;

        int[] runs = new int[16];
        int nRuns = 0;
        int[] visible = new int[rw * rh];
        int count = 0;
        boolean cls = false;
        int n = 0;
        for (int o = 0; o < rw * rh; o++) {
            int x = rx + o % rw, y = ry + o / rw;
            int c = argb[(h - 1 - y) * w + x];
            boolean v = !empty && c >>> 24 != 0;
            if (v != cls) {
                if (nRuns == runs.length) runs = Arrays.copyOf(runs, nRuns * 2);
                runs[nRuns++] = n;
                n = 0;
                cls = v;
            }
            if (n == 0xFFFF) { // los tramos son u16: se parte con un tramo vacío de la otra clase
                if (nRuns + 2 > runs.length) runs = Arrays.copyOf(runs, runs.length * 2);
                runs[nRuns++] = n;
                runs[nRuns++] = 0;
                n = 0;
            }
            n++;
            if (v) visible[count++] = c;
        }
        if (nRuns + 2 > runs.length) runs = Arrays.copyOf(runs, runs.length * 2);
        runs[nRuns++] = n;
        if (nRuns % 2 == 1) runs[nRuns++] = 0;
        if (count == 0) { // ibisPaint necesita al menos un píxel guardado
            nRuns = 0;
            runs[nRuns++] = 0;
            runs[nRuns++] = 1;
            visible[0] = 0;
            count = 1;
            rw = rh = 1;
        }

        int pw = 8;
        while ((long) pw * pw < count) pw += 8;
        int ph = (count + pw - 1) / pw, pad = pw * ph - count;
        int[] grid = new int[pw * ph];
        int row = pad / pw, col = pw - 1 - pad % pw;
        for (int k = count - 1; k >= 0; k--) { // el último visible va al primer hueco consumido
            while (col < 0) {
                row++;
                col = pw - 1;
            }
            grid[row * pw + col--] = visible[k];
        }
        byte[] png = Png.encode(grid, pw, ph);

        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(b);
        // La cabecera antigua de 41 bytes, sin color de transparencia (la de 45 lo agrega con la bandera 2); el byte de versión debe ser 0
        c.writeInt(0x52504e47); c.writeInt(41); c.writeByte(0);
        c.writeInt(1); c.writeInt(w); c.writeInt(h); c.writeInt(rw); c.writeInt(rh); c.writeInt(rx); c.writeInt(ry);
        c.writeInt(pw); c.writeInt(ph); c.writeInt(pad);
        c.writeInt(nRuns * 2);
        for (int i = 0; i < nRuns; i++) c.writeShort(runs[i]);
        c.writeInt(png.length);
        c.write(png);
        return b.toByteArray();
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
            return rotated(place(src, dims[0], dims[1], pad, r, rx, ry, rw, rh, w, h));
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

    /**
     * ibisPaint guarda sus imágenes giradas 180° respecto a como las muestra: la miniatura de una obra real (la que
     * compone la propia app) y sus capas salen cabeza abajo si se leen tal cual, y lo que se escribe sin girar se ve al
     * revés en la app (comprobado en el teléfono). Por eso se gira al leer y al escribir, capas y miniatura.
     */
    static int[] rotated(int[] argb) {
        int[] out = new int[argb.length];
        for (int i = 0; i < out.length; i++) out[i] = argb[out.length - 1 - i];
        return out;
    }

    private static boolean isBlank(int[] argb) {
        for (int c : argb) if (c >>> 24 != 0) return false;
        return true;
    }
}
