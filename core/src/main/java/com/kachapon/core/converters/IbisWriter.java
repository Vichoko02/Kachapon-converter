package com.kachapon.core.converters;

import com.kachapon.core.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.kachapon.core.converters.IbisConverter.ANIMATION;
import static com.kachapon.core.converters.IbisConverter.CANVAS;
import static com.kachapon.core.converters.IbisConverter.FOLDER;
import static com.kachapon.core.converters.IbisConverter.IMAGE;
import static com.kachapon.core.converters.IbisConverter.LAYER;
import static com.kachapon.core.converters.IbisConverter.META;
import static com.kachapon.core.converters.IbisConverter.ROOT;
import static com.kachapon.core.converters.IbisConverter.oriented;

/** Escribe el .ipv nativo; el formato y la lectura están en IbisConverter. */
final class IbisWriter {
    // Versión del formato que se escribe: la de ibisPaint 11.0.0, la primera con animación, para que más versiones de la app abran
    // el archivo (docs/IBIS_VERSIONES.md): lo que impide abrirlo en una app vieja son NeedVersionForPlay/Edit, no el resto.
    private static final int FORMAT_VERSION = 1100, TEMPLATE_VERSION = 1410; // TEMPLATE_VERSION: la de la plantilla META_TAIL
    private static final int NEED_VERSION = 110000; // NeedVersionForPlay y NeedVersionForEdit: app x 10000
    private static final int TAIL_NEED_PLAY = 60, TAIL_NEED_EDIT = 64, TAIL_FORMAT_VERSION = 606; // las tres versiones de la cola

    private IbisWriter() {
    }

    /**
     * Escribe un .ipv nativo que ibisPaint importa: lienzo, una sesión de edición vacía (no hace falta historial), una
     * carpeta de frame por tramo de la animación con el árbol del documento (capas, carpetas, ocultas, modos y opacidad),
     * la imagen combinada para la miniatura y el MetaInfo. Una capa sin dibujo en un tramo no se escribe en ese tramo.
     * Nada se funde, así que una escena grande pesa mucho: ibisPaint repite cada capa en cada frame.
     */
    static void write(Manifest m, Bridge bridge, File output) throws Exception {
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
            imageChunk(out, now, -1, 0, 0, Png.encode(oriented(composeFirst(planner, w, h)), w, h));
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
                        data = rpng(oriented(Png.decode(bridge.readFrame(f))), m.width(), m.height());
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
}
