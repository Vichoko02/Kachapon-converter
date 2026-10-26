package com.kachapon.core.converters;

import com.kachapon.core.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.Adler32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Escribe un .clip nativo que Clip Studio abre (probado con Clip Studio Paint EX): parte de un documento de animación
 * vacío creado por Clip Studio (clip_template.clip), le quita los cels de ejemplo y por cada capa animada crea una
 * carpeta de animación (copia de la de la plantilla, con su pista en la línea de tiempo) con un cel por dibujo, sus
 * pixeles, mipmaps y la curva ImageCelName.
 *   CSFCHUNK: CHNKHead, CHNKExta (pixeles y curvas, referidos por ExternalChunk), CHNKSQLi (la base) y CHNKFoot.
 * shortcut: las carpetas van sueltas bajo la raíz (sin las carpetas normales del origen) y los huecos son cels vacíos.
 */
final class ClipWriter {
    private static final int BLOCK = 256, RAW = BLOCK * BLOCK * 5;
    private static final String TEMPLATE = "/com/kachapon/core/clip_template.clip";

    private final List<Sqlite.Table> tables;
    private final Map<String, Sqlite.Table> byName = new HashMap<>();
    private final Map<String, Long> nextId = new HashMap<>();
    private final Map<String, byte[]> exts = new LinkedHashMap<>(); // id externo -> datos
    private final byte[] dbHeader;
    private byte[] head;
    private final Map<String, byte[]> old = new HashMap<>();

    private ClipWriter(Sqlite sq) throws IOException {
        tables = sq.tables();
        dbHeader = sq.header();
        for (Sqlite.Table t : tables) byName.put(t.name, t);
    }

    /**
     * Una capa o carpeta del documento. Con hijos es una carpeta; con dibujos, una capa de pintura: de un solo dibujo
     * es una capa normal y de varios, una carpeta de animación con un cel por dibujo.
     */
    static final class Node {
        final String name;
        final boolean visible, passthrough;
        final int opacity, composite; // opacity 0..255, composite = LayerComposite de Clip Studio
        final List<Node> children;    // de abajo arriba
        final List<Converter.Drawing> drawings;
        boolean forceAnimated;

        private Node(String name, boolean visible, int opacity, int composite, boolean passthrough, List<Node> children, List<Converter.Drawing> drawings) {
            this.name = name;
            this.visible = visible;
            this.opacity = opacity;
            this.composite = composite;
            this.passthrough = passthrough;
            this.children = children;
            this.drawings = drawings;
        }

        static Node folder(String name, boolean visible, int opacity, String blend, boolean passthrough, List<Node> children) {
            return new Node(name, visible, opacity, passthrough ? Blend.CLIP_PASS_THROUGH : Blend.toClip(blend), passthrough, children, null);
        }

        static Node paint(String name, boolean visible, int opacity, String blend, List<Converter.Drawing> drawings) {
            return new Node(name, visible, opacity, Blend.toClip(blend), false, null, drawings);
        }

        boolean animated() {
            return drawings != null && (drawings.size() > 1 || forceAnimated);
        }
    }

    /** Escribe el árbol (hermanos de abajo arriba); preview es la imagen combinada que Clip Studio muestra como miniatura. */
    static void write(List<Node> roots, byte[] preview, int fps, int w, int h, File output) throws Exception {
        byte[] template;
        try (InputStream in = ClipWriter.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) throw new Exception("Falta la plantilla de .clip dentro del programa (clip_template.clip).");
            template = Bridge.readAll(in);
        }
        ClipWriter cw = new ClipWriter(sqliteOf(template));
        cw.readContainer(template);
        cw.build(roots, preview, fps, w, h);
        cw.save(output);
    }

    private void readContainer(byte[] f) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(f);
        for (int pos = 24; pos + 16 <= f.length; ) {
            String type = new String(f, pos, 8, StandardCharsets.US_ASCII);
            int len = (int) b.getLong(pos + 8), body = pos + 16;
            if (type.equals("CHNKHead")) {
                head = Arrays.copyOfRange(f, body, body + len);
            } else if (type.equals("CHNKExta")) {
                int idLen = (int) b.getLong(body), dataLen = (int) b.getLong(body + 8 + idLen);
                old.put(new String(f, body + 8, idLen, StandardCharsets.US_ASCII), Arrays.copyOfRange(f, body + 16 + idLen, body + 16 + idLen + dataLen));
            } else if (type.equals("CHNKFoot")) {
                break;
            }
            pos = body + len;
        }
        if (head == null) throw new IOException("La plantilla de .clip está dañada.");
    }

    private static Sqlite sqliteOf(byte[] f) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(f);
        for (int pos = 24; pos + 16 <= f.length; ) {
            int len = (int) b.getLong(pos + 8);
            if (new String(f, pos, 8, StandardCharsets.US_ASCII).equals("CHNKSQLi")) return new Sqlite(Arrays.copyOfRange(f, pos + 16, pos + 16 + len));
            pos += 16 + len;
        }
        throw new IOException("La plantilla de .clip no tiene base de datos.");
    }

    private Sqlite.Table t(String name) {
        return byName.get(name);
    }

    /** Siguiente MainId libre de la tabla. */
    private long next(String table) {
        Long n = nextId.get(table);
        if (n == null) {
            long max = 0;
            Sqlite.Table tb = t(table);
            for (Object[] r : tb.rows) max = Math.max(max, ((Number) tb.get(r, "MainId")).longValue());
            n = max + 1;
        }
        nextId.put(table, n + 1);
        return n;
    }

    /** INSERT con rowid autoincremental (y actualiza sqlite_sequence). */
    private void insert(Sqlite.Table tb, Object[] row) {
        Sqlite.Table seq = t("sqlite_sequence");
        Object[] s = null;
        for (Object[] r : seq.rows) if (tb.name.equals(r[0])) s = r;
        long id = s == null ? 0 : ((Number) s[1]).longValue();
        for (Object[] r : tb.rows) id = Math.max(id, ((Number) r[tb.alias]).longValue());
        row[tb.alias] = ++id;
        tb.rows.add(row);
        if (s == null) seq.rows.add(new Object[]{tb.name, id});
        else s[1] = id;
    }

    private Object[] blank(Sqlite.Table tb, Object... values) {
        Object[] row = new Object[tb.cols.length];
        for (int i = 0; i < values.length; i += 2) tb.set(row, (String) values[i], values[i + 1]);
        return row;
    }

    // Estado de build(): tablas, filas de la plantilla que se clonan y lo que se va creando
    private Sqlite.Table layer, mip, info, off, thumb, track;
    private int[][] sizes;
    private int fps;
    private Object[] tplCel, tplThumb, tplFolder, tplFolderThumb, trk, lastTrack;
    private long templateFolder, firstCel;
    private boolean templateFolderUsed;
    private int end;
    private final Map<String, byte[]> tplMixers = new HashMap<>();
    private final Set<Long> keepers = new LinkedHashSet<>();
    private final Set<Object[]> ours = new HashSet<>();

    private void build(List<Node> roots, byte[] preview, int fps, int w, int h) throws Exception {
        layer = t("Layer");
        mip = t("Mipmap");
        info = t("MipmapInfo");
        off = t("Offscreen");
        thumb = t("LayerThumbnail");
        track = t("Track");
        Sqlite.Table canvas = t("Canvas");
        this.fps = fps;
        Object[] cv = canvas.rows.get(0);
        long root = ((Number) canvas.get(cv, "CanvasRootFolder")).longValue();
        for (Object[] r : track.rows) if (((Number) track.get(r, "TrackKind")).longValue() == 2000) trk = r;
        if (trk == null) throw new IOException("La plantilla de .clip no tiene pista de animación.");
        String folderUuid = hex((byte[]) track.get(trk, "LayerUuidWithTrack"));
        long paper = -1;
        templateFolder = -1;
        for (Object[] r : layer.rows) {
            long id = ((Number) layer.get(r, "MainId")).longValue();
            if (String.valueOf(layer.get(r, "LayerUuid")).replace("-", "").equals(folderUuid)) {
                templateFolder = id;
                tplFolder = r;
            }
            if (((Number) layer.get(r, "LayerType")).longValue() == 1584) paper = id;
            if (tplCel == null && ((Number) layer.get(r, "LayerFolder")).longValue() == 0 && ((Number) layer.get(r, "LayerType")).longValue() == 1) tplCel = r;
        }
        if (templateFolder < 0 || paper < 0 || tplCel == null) throw new IOException("La plantilla de .clip no tiene carpeta de animación.");
        long tplId = ((Number) layer.get(tplCel, "MainId")).longValue();
        for (Object[] r : thumb.rows) {
            long id = ((Number) thumb.get(r, "LayerId")).longValue();
            if (id == tplId) tplThumb = r;
            if (id == templateFolder) tplFolderThumb = r;
        }
        // Las curvas de la plantilla, antes de que la primera pista las reemplace
        for (String col : new String[]{"TrackActionMixer", "TrackActionMixer2"}) tplMixers.put(col, old.get(new String((byte[]) track.get(trk, col), StandardCharsets.US_ASCII)));
        keepers.addAll(Arrays.asList(root, paper, templateFolder));

        // Fuera los cels y la capa de información de la plantilla; se conservan raíz, papel y carpeta
        for (Sqlite.Table tb : new Sqlite.Table[]{mip, info, thumb, off}) tb.rows.removeIf(r -> !keepers.contains(((Number) tb.get(r, "LayerId")).longValue()));
        layer.rows.removeIf(r -> !keepers.contains(((Number) layer.get(r, "MainId")).longValue()));
        for (Object[] r : off.rows) { // los pixeles externos de las capas conservadas se copian tal cual
            String id = new String((byte[]) off.get(r, "BlockData"), StandardCharsets.US_ASCII);
            if (old.containsKey(id)) exts.put(id, old.get(id));
        }
        t("LayerComp").rows.clear();
        Sqlite.Table lcm = t("LayerCompManager");
        for (Object[] r : lcm.rows) {
            lcm.set(r, "FirstLayerCompIndex", 0);
            lcm.set(r, "LastStateLayerCompIndex", 0);
            lcm.set(r, "AppliedLayerCompIndex", -1);
        }
        setLayer(paper, "LayerNextIndex", 0);
        setLayer(templateFolder, "LayerNextIndex", 0);
        setLayer(templateFolder, "LayerFirstChildIndex", 0);

        sizes = levels(w, h);
        lastTrack = trk;
        // La plantilla trae una carpeta de animación y la pista de la línea de tiempo la necesita: si no hay ninguna animada, la primera capa lo será
        Node first = firstPaint(roots);
        if (first == null) throw new IOException("La animación no tiene dibujos.");
        if (!anyAnimated(roots)) first.forceAnimated = true;

        long below = paper;
        for (Node n : roots) {
            long id = emit(n);
            setLayer(below, "LayerNextIndex", id);
            below = id;
        }

        for (Object[] r : canvas.rows) {
            canvas.set(r, "CanvasWidth", w);
            canvas.set(r, "CanvasHeight", h);
            canvas.set(r, "CanvasCurrentLayer", firstCel);
            for (String c : new String[]{"CropFrameWidth", "CropFrameInnerWidth"}) canvas.set(r, c, w);
            for (String c : new String[]{"CropFrameHeight", "CropFrameInnerHeight"}) canvas.set(r, c, h);
        }
        for (Object[] r : thumb.rows) {
            thumb.set(r, "ThumbnailCanvasWidth", w);
            thumb.set(r, "ThumbnailCanvasHeight", h);
        }
        // Las capas conservadas no tienen pixeles: se rehace su cadena de mipmaps para el tamaño nuevo
        for (long lid : keepers) {
            Set<Long> gone = new HashSet<>();
            for (Object[] r : info.rows) if (((Number) info.get(r, "LayerId")).longValue() == lid) gone.add(((Number) info.get(r, "Offscreen")).longValue());
            off.rows.removeIf(r -> gone.contains(((Number) off.get(r, "MainId")).longValue()));
            info.rows.removeIf(r -> ((Number) info.get(r, "LayerId")).longValue() == lid);
            long[] infos = new long[sizes.length];
            for (int i = 0; i < sizes.length; i++) infos[i] = next("MipmapInfo");
            for (int i = 0; i < sizes.length; i++) {
                long o = next("Offscreen");
                int gw = (sizes[i][0] + BLOCK - 1) / BLOCK, gh = (sizes[i][1] + BLOCK - 1) / BLOCK;
                int[] blocks = new int[gw * gh];
                Arrays.fill(blocks, 104);
                insert(off, blank(off, "MainId", o, "CanvasId", 1, "LayerId", lid, "Attribute", attribute(sizes[i][0], sizes[i][1], gw, gh, blocks), "BlockData", extId())); // sin bloque externo: capa vacía
                insert(info, blank(info, "MainId", infos[i], "CanvasId", 1, "LayerId", lid, "ThisScale", 100.0 / (1 << i), "Offscreen", o,
                        "NextIndex", i < sizes.length - 1 ? infos[i + 1] : 0));
            }
            for (Object[] r : mip.rows) {
                if (((Number) mip.get(r, "LayerId")).longValue() != lid) continue;
                mip.set(r, "MipmapCount", sizes.length);
                mip.set(r, "BaseMipmapInfo", infos[0]);
            }
        }
        for (Object[] r : t("Project").rows) {
            t("Project").set(r, "DefaultPageWidth", w);
            t("Project").set(r, "DefaultPageHeight", h);
            t("Project").set(r, "DefaultPageCropWidth", w);
            t("Project").set(r, "DefaultPageCropHeight", h);
        }

        for (Object[] r : track.rows) { // cámara, papel...: conservan sus datos
            if (ours.contains(r)) continue;
            for (String col : new String[]{"TrackActionMixer", "TrackActionMixer2"}) {
                if (!(track.get(r, col) instanceof byte[])) continue;
                String id = new String((byte[]) track.get(r, col), StandardCharsets.US_ASCII);
                if (old.containsKey(id)) exts.put(id, old.get(id));
            }
        }
        for (Object[] r : t("TimeLine").rows) {
            Sqlite.Table tl = t("TimeLine");
            tl.set(r, "FrameRate", fps);
            tl.set(r, "StartFrame", 0);
            tl.set(r, "EndFrame", end);
            tl.set(r, "BiggestEndFrame", end);
            tl.set(r, "SmallestStartFrame", 0);
            tl.set(r, "CurrentFrame", 0);
        }
        for (Object[] r : t("CanvasPreview").rows) {
            Sqlite.Table cp = t("CanvasPreview");
            cp.set(r, "ImageWidth", w);
            cp.set(r, "ImageHeight", h);
            cp.set(r, "ImageData", preview);
        }
        // Clip Studio lleva en ElemScheme el último id de cada tabla: con más filas que la plantilla hay que subirlo
        Sqlite.Table scheme = t("ElemScheme");
        for (Object[] r : scheme.rows) {
            Sqlite.Table tb = t(String.valueOf(scheme.get(r, "TableName")));
            if (tb == null || !Arrays.asList(tb.cols).contains("MainId")) continue;
            long max = ((Number) scheme.get(r, "MaxIndex")).longValue();
            for (Object[] row : tb.rows) max = Math.max(max, ((Number) tb.get(row, "MainId")).longValue());
            scheme.set(r, "MaxIndex", max);
        }
    }

    private static Node firstPaint(List<Node> nodes) {
        for (Node n : nodes) {
            Node found = n.children != null ? firstPaint(n.children) : n;
            if (found != null) return found;
        }
        return null;
    }

    private static boolean anyAnimated(List<Node> nodes) {
        for (Node n : nodes) if (n.children != null ? anyAnimated(n.children) : n.animated()) return true;
        return false;
    }

    /** Crea las filas del nodo (y de sus hijos) y devuelve el id de la capa o carpeta de más arriba. */
    private long emit(Node n) throws Exception {
        if (n.children != null) {
            long fid = newFolder(n, false);
            long prev = 0;
            for (Node c : n.children) {
                long cid = emit(c);
                setLayer(prev == 0 ? fid : prev, prev == 0 ? "LayerFirstChildIndex" : "LayerNextIndex", cid);
                prev = cid;
            }
            return fid;
        }
        if (!n.animated()) {
            long lid = cel(n.name, n.drawings.get(0).png);
            style(lid, n);
            return lid;
        }
        long fid = newFolder(n, true);
        Object[] trow = trackOf(fid);
        List<String> names = new ArrayList<>();
        List<Double> starts = new ArrayList<>();
        int time = 0;
        long prev = 0;
        for (int k = 0; k < n.drawings.size(); k++) {
            Converter.Drawing d = n.drawings.get(k);
            names.add(String.valueOf(k + 1));
            starts.add(time * 60.0 / fps);
            time += d.hold;
            long lid = cel(names.get(k), d.png);
            setLayer(prev == 0 ? fid : prev, prev == 0 ? "LayerFirstChildIndex" : "LayerNextIndex", lid);
            prev = lid;
        }
        end = Math.max(end, time);
        for (String col : new String[]{"TrackActionMixer", "TrackActionMixer2"}) {
            byte[] data = timeline(tplMixers.get(col), names, starts, time * 60.0 / fps);
            String neu = new String(extId(), StandardCharsets.US_ASCII);
            exts.put(neu, data);
            track.set(trow, col, neu.getBytes(StandardCharsets.US_ASCII));
            track.set(trow, col + "Size", fullSize);
        }
        track.set(trow, "TrackValueMap", valueMap(names.get(0)));
        return fid;
    }

    private final Map<Long, Object[]> tracksOf = new HashMap<>();

    private Object[] trackOf(long folderId) {
        return tracksOf.get(folderId);
    }

    /** Una carpeta nueva (de animación o normal) con su nombre, modo de fusión, opacidad y visibilidad. */
    private long newFolder(Node n, boolean animation) throws IOException {
        long fid;
        if (animation && !templateFolderUsed) { // la primera animación usa la carpeta y la pista de la plantilla
            templateFolderUsed = true;
            fid = templateFolder;
            ours.add(trk);
            tracksOf.put(fid, trk);
        } else {
            fid = next("Layer");
            long fmip = next("Mipmap"), fthumb = next("LayerThumbnail"), fthumbOff = next("Offscreen");
            Object[] row = tplFolder.clone();
            layer.set(row, "MainId", fid);
            layer.set(row, "LayerUuid", uuid());
            layer.set(row, "LayerRenderMipmap", fmip);
            layer.set(row, "LayerRenderThumbnail", fthumb);
            layer.set(row, "LayerSelect", 0);
            layer.set(row, "LayerNextIndex", 0);
            layer.set(row, "LayerFirstChildIndex", 0);
            layer.set(row, "AnimationFolder", animation ? 1 : null);
            insert(layer, row);
            insert(mip, blank(mip, "MainId", fmip, "CanvasId", 1, "LayerId", fid, "MipmapCount", sizes.length, "BaseMipmapInfo", 0));
            offscreen(off, fid, new int[512 * 512], 512, 512, fthumbOff);
            Object[] tr = tplFolderThumb.clone();
            thumb.set(tr, "MainId", fthumb);
            thumb.set(tr, "LayerId", fid);
            thumb.set(tr, "ThumbnailOffscreen", fthumbOff);
            insert(thumb, tr);
            keepers.add(fid); // su cadena de mipmaps vacía se crea al final, como la de las capas de la plantilla
            if (animation) {
                Object[] trow = trk.clone();
                track.set(trow, "MainId", next("Track"));
                byte[] tu = new byte[16];
                new Random().nextBytes(tu);
                track.set(trow, "TrackUuid", tu);
                track.set(trow, "LayerUuidWithTrack", unhex(String.valueOf(layer.get(row, "LayerUuid")).replace("-", "")));
                track.set(trow, "TrackNextIndex", 0);
                insert(track, trow);
                track.set(lastTrack, "TrackNextIndex", track.get(trow, "MainId"));
                lastTrack = trow;
                ours.add(trow);
                tracksOf.put(fid, trow);
            }
        }
        style(fid, n);
        return fid;
    }

    /** Un cel o capa de pintura con sus pixeles, mipmaps y miniatura. */
    private long cel(String name, byte[] png) throws IOException {
        long lid = next("Layer"), mipId = next("Mipmap"), thumbOff, thumbId;
        long[] infos = new long[sizes.length], offs = new long[sizes.length];
        for (int i = 0; i < sizes.length; i++) infos[i] = next("MipmapInfo");
        for (int i = 0; i < sizes.length; i++) offs[i] = next("Offscreen");
        thumbOff = next("Offscreen");
        thumbId = next("LayerThumbnail");
        if (firstCel == 0) firstCel = lid;

        Object[] row = tplCel.clone();
        layer.set(row, "MainId", lid);
        layer.set(row, "LayerName", name);
        layer.set(row, "LayerNextIndex", 0);
        layer.set(row, "LayerFirstChildIndex", 0);
        layer.set(row, "LayerUuid", uuid());
        layer.set(row, "LayerRenderMipmap", mipId);
        layer.set(row, "LayerRenderThumbnail", thumbId);
        layer.set(row, "LayerSelect", 0);
        insert(layer, row);
        insert(mip, blank(mip, "MainId", mipId, "CanvasId", 1, "LayerId", lid, "MipmapCount", sizes.length, "BaseMipmapInfo", infos[0]));
        int[] level = Png.decode(png);
        for (int i = 0; i < sizes.length; i++) {
            insert(info, blank(info, "MainId", infos[i], "CanvasId", 1, "LayerId", lid, "ThisScale", 100.0 / (1 << i), "Offscreen", offs[i],
                    "NextIndex", i < sizes.length - 1 ? infos[i + 1] : 0));
            if (i > 0) level = shrink(level, sizes[i - 1][0], sizes[i - 1][1], sizes[i][0], sizes[i][1]);
            offscreen(off, lid, level, sizes[i][0], sizes[i][1], offs[i]);
        }
        offscreen(off, lid, new int[512 * 512], 512, 512, thumbOff);
        Object[] tr = tplThumb.clone();
        thumb.set(tr, "MainId", thumbId);
        thumb.set(tr, "LayerId", lid);
        thumb.set(tr, "ThumbnailOffscreen", thumbOff);
        insert(thumb, tr);
        return lid;
    }

    /** Nombre, visibilidad, opacidad (0..256 en Clip Studio) y modo de fusión de una capa ya creada. */
    private void style(long id, Node n) {
        for (Object[] r : layer.rows) {
            if (((Number) layer.get(r, "MainId")).longValue() != id) continue;
            layer.set(r, "LayerName", n.name);
            layer.set(r, "LayerVisibility", n.visible ? 1 : 0);
            layer.set(r, "LayerOpacity", Math.round(n.opacity * 256f / 255f));
            layer.set(r, "LayerComposite", n.composite);
        }
    }

    private static String uuid() {
        String u = UUID.randomUUID().toString().replace("-", "");
        return u.substring(0, 10) + "-" + u.substring(10, 14) + "-" + u.substring(14, 18) + "-" + u.substring(18, 22) + "-" + u.substring(22);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    private void setLayer(long id, String col, long v) {
        for (Object[] r : layer.rows) if (((Number) layer.get(r, "MainId")).longValue() == id) layer.set(r, col, v);
    }

    private void save(File output) throws IOException {
        Sqlite.Table chunks = t("ExternalChunk");
        chunks.rows.clear();
        long pos = 24 + 16 + head.length;
        for (Map.Entry<String, byte[]> e : exts.entrySet()) {
            chunks.rows.add(new Object[]{e.getKey(), pos}); // ExternalID va como TEXTO: con BLOB Clip Studio no lo encuentra
            pos += 16 + 8 + e.getKey().length() + 8 + e.getValue().length;
        }
        byte[] db = SqliteWriter.write(dbHeader, tables);
        ByteBuffer.wrap(head).putLong(8, pos); // el Head apunta al trozo SQLite
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
            out.writeBytes("CSFCHUNK");
            out.writeLong(pos + 16 + db.length + 16);
            out.writeLong(24);
            chunk(out, "CHNKHead", head);
            for (Map.Entry<String, byte[]> e : exts.entrySet()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                DataOutputStream d = new DataOutputStream(body);
                d.writeLong(e.getKey().length());
                d.writeBytes(e.getKey());
                d.writeLong(e.getValue().length);
                d.write(e.getValue());
                chunk(out, "CHNKExta", body.toByteArray());
            }
            chunk(out, "CHNKSQLi", db);
            chunk(out, "CHNKFoot", new byte[0]);
        }
    }

    private static void chunk(DataOutputStream out, String type, byte[] body) throws IOException {
        out.writeBytes(type);
        out.writeLong(body.length);
        out.write(body);
    }

    private static byte[] extId() {
        return ("extrnlid" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT)).getBytes(StandardCharsets.US_ASCII);
    }

    /** Tamaños de los mipmaps como los crea Clip Studio: mitad a mitad mientras el lado mayor siga >= 64. */
    private static int[][] levels(int w, int h) {
        List<int[]> out = new ArrayList<>();
        out.add(new int[]{w, h});
        while (Math.max(out.get(out.size() - 1)[0] / 2, out.get(out.size() - 1)[1] / 2) >= 64) {
            int[] p = out.get(out.size() - 1);
            out.add(new int[]{p[0] / 2, p[1] / 2});
        }
        return out.toArray(new int[0][]);
    }

    /** Promedio por áreas (con alfa premultiplicado) para el mipmap siguiente. */
    static int[] shrink(int[] src, int sw, int sh, int dw, int dh) {
        int[] out = new int[dw * dh];
        int[] xs = new int[dw], xe = new int[dw]; // columnas de origen [xs, xe) de cada columna de destino, calculadas una vez
        for (int x = 0; x < dw; x++) {
            xs[x] = (int) ((long) x * sw / dw);
            xe[x] = Math.min(Math.max(xs[x] + 1, (int) (((long) (x + 1) * sw + dw - 1) / dw)), sw);
        }
        for (int y = 0; y < dh; y++) {
            int y0 = (int) ((long) y * sh / dh);
            int yEnd = Math.min(Math.max(y0 + 1, (int) (((long) (y + 1) * sh + dh - 1) / dh)), sh);
            for (int x = 0; x < dw; x++) {
                int x0 = xs[x], xEnd = xe[x];
                long a = 0, r = 0, g = 0, b = 0, n = (long) (yEnd - y0) * (xEnd - x0);
                for (int yy = y0; yy < yEnd; yy++) {
                    for (int xx = x0; xx < xEnd; xx++) {
                        int c = src[yy * sw + xx], al = c >>> 24;
                        a += al;
                        r += (long) ((c >> 16) & 0xFF) * al;
                        g += (long) ((c >> 8) & 0xFF) * al;
                        b += (long) (c & 0xFF) * al;
                    }
                }
                if (a == 0) continue;
                out[y * dw + x] = (int) (a / n) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
            }
        }
        return out;
    }

    // ---------- pixeles ----------

    private void offscreen(Sqlite.Table off, long layerId, int[] argb, int w, int h, long mainId) throws IOException {
        int gw = (w + BLOCK - 1) / BLOCK, gh = (h + BLOCK - 1) / BLOCK, n = gw * gh;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] sizes = new int[n], sums = new int[n];
        for (int idx = 0; idx < n; idx++) {
            byte[] blk = block(argb, w, h, idx, idx % gw * BLOCK, idx / gw * BLOCK, sums);
            sizes[idx] = blk.length;
            data.write(blk);
        }
        data.write(u16s("BlockStatus"));
        data.write(ints(12, n, 4));
        int[] ones = new int[n];
        Arrays.fill(ones, 1);
        data.write(ints(ones));
        data.write(u16s("BlockCheckSum"));
        data.write(ints(12, n, 4));
        data.write(ints(sums));
        byte[] id = extId();
        exts.put(new String(id, StandardCharsets.US_ASCII), data.toByteArray());
        insert(off, blank(off, "MainId", mainId, "CanvasId", 1, "LayerId", layerId, "Attribute", attribute(w, h, gw, gh, sizes), "BlockData", id));
    }

    /** Bloque de 256x256: plano de alfa + BGRA, zlib; los bloques sin pixeles son un registro vacío de 104 bytes. */
    private static byte[] block(int[] argb, int w, int h, int idx, int bx, int by, int[] sums) throws IOException {
        byte[] raw = new byte[RAW];
        boolean empty = true;
        for (int y = 0; y < BLOCK && by + y < h; y++) {
            for (int x = 0; x < BLOCK && bx + x < w; x++) {
                int c = argb[(by + y) * w + bx + x], a = c >>> 24, j = y * BLOCK + x;
                if (a == 0) continue;
                empty = false;
                raw[j] = (byte) a;
                raw[BLOCK * BLOCK + 4 * j] = (byte) c;
                raw[BLOCK * BLOCK + 4 * j + 1] = (byte) (c >> 8);
                raw[BLOCK * BLOCK + 4 * j + 2] = (byte) (c >> 16);
                raw[BLOCK * BLOCK + 4 * j + 3] = (byte) a;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] begin = u16s("BlockDataBeginChunk"), end = u16s("BlockDataEndChunk");
        if (empty) {
            out.write(ints(104));
            out.write(begin);
            out.write(ints(idx, RAW, BLOCK, BLOCK, 0));
            out.write(end);
            return out.toByteArray();
        }
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(z, new Deflater())) {
            d.write(raw);
        }
        byte[] zb = z.toByteArray();
        byte[] full = ByteBuffer.allocate(4 + zb.length).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(zb.length).put(zb).array(); // el largo comprimido va en little-endian
        Adler32 ad = new Adler32();
        ad.update(full);
        sums[idx] = (int) ad.getValue();
        out.write(ints(108 + full.length));
        out.write(begin);
        out.write(ints(idx, RAW, BLOCK, BLOCK, 1, full.length));
        out.write(full);
        out.write(end);
        return out.toByteArray();
    }

    /** Atributos del bitmap: parámetros, color inicial y tamaño de cada bloque. */
    private static byte[] attribute(int w, int h, int gw, int gh, int[] sizes) throws IOException {
        ByteArrayOutputStream param = new ByteArrayOutputStream(), init = new ByteArrayOutputStream(), bs = new ByteArrayOutputStream();
        param.write(u16s("Parameter"));
        param.write(ints(w, h, gw, gh, 33, 1, 4, 5, 65536, 4, 1024, 1, 256, 65536, 256, 256, 8, 8, 0, 0));
        init.write(u16s("InitColor"));
        init.write(ints(20, 0, 0, 0, 4));
        bs.write(u16s("BlockSize"));
        bs.write(ints(12, sizes.length, 4));
        bs.write(ints(sizes));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ints(16, param.size(), init.size(), bs.size()));
        param.writeTo(out);
        init.writeTo(out);
        bs.writeTo(out);
        return out.toByteArray();
    }

    private static byte[] u16s(String s) {
        return ByteBuffer.allocate(4 + 2 * s.length()).putInt(s.length()).put(s.getBytes(StandardCharsets.UTF_16BE)).array();
    }

    private static byte[] ints(int... v) {
        ByteBuffer b = ByteBuffer.allocate(4 * v.length);
        for (int i : v) b.putInt(i);
        return b.array();
    }

    // ---------- línea de tiempo ----------

    private int fullSize; // tamaño sin comprimir de la última curva escrita (va en Track.*Size)

    /** Rehace la curva ImageCelName de la plantilla con estos cels: [u32 LE largo comprimido][zlib de binc]. */
    private byte[] timeline(byte[] raw, List<String> cels, List<Double> starts, double end) throws IOException {
        if (raw == null || raw.length < 8) throw new IOException("La plantilla de .clip no tiene su línea de tiempo.");
        byte[] blob;
        try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(raw, 4, raw.length - 4))) {
            blob = Bridge.readAll(in);
        }
        Binc.Doc doc = Binc.parse(blob);
        Binc.Node anim = doc.root.find("AnimInfo"), param = null, curve = null;
        for (Binc.Node k : anim.kids) {
            if (k.name.equals("Param")) param = k;
            if (k.name.equals("FCurve")) curve = k;
        }
        if (param == null || curve == null) throw new IOException("La plantilla de .clip no tiene una curva de cels.");
        param.attrs.clear();
        param.attrs.add(new String[]{"Name", "ImageCelName"});
        param.attrs.add(new String[]{"Tag", cels.get(0)});
        int k = cels.size();
        for (Binc.Node kid : curve.kids) {
            switch (kid.name) {
                case "Frame": kid.value = numbers(doc, starts); break;
                case "Value": {
                    List<Double> v = new ArrayList<>();
                    for (int i = 0; i < k; i++) v.add((double) i);
                    kid.value = numbers(doc, v);
                    break;
                }
                case "LeftSlope":
                case "RightSlope": kid.value = numbers(doc, Collections.nCopies(k, 0.0)); break;
                case "Interp": kid.value = new ArrayList<>(Collections.nCopies(k, "Constant")); break;
                case "ReviseConstant": {
                    List<byte[]> v = new ArrayList<>();
                    for (int i = 0; i < k; i++) v.add(new byte[]{1});
                    kid.value = v;
                    break;
                }
                case "Tag": kid.value = new ArrayList<>(cels); break;
                default:
            }
        }
        setEnd(doc.root, end);
        byte[] full = Binc.serialize(doc);
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(z)) {
            d.write(full);
        }
        fullSize = full.length;
        return ByteBuffer.allocate(4 + z.size()).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(z.size()).put(z.toByteArray()).array();
    }

    private static List<byte[]> numbers(Binc.Doc doc, List<Double> values) {
        List<byte[]> out = new ArrayList<>();
        for (double v : values) {
            ByteBuffer b = ByteBuffer.allocate(doc.isDouble() ? 8 : 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (doc.isDouble()) b.putDouble(v);
            else b.putFloat((float) v);
            out.add(b.array());
        }
        return out;
    }

    private static void setEnd(Binc.Node n, double end) {
        if (n.name.equals("End") && n.type.equals("Double")) n.value = ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(end).array();
        for (Binc.Node k : n.kids) setEnd(k, end);
    }

    /** Track.TrackValueMap: la curva ImageCelName con el cel actual (el primero) y su posición (0). */
    private static byte[] valueMap(String cel) throws IOException {
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        tail.write(u16s("ImageCelName"));
        tail.write(u16s(cel));
        tail.write(ints(2, 0));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ints(8, 1, 4 + tail.size()));
        tail.writeTo(out);
        return out.toByteArray();
    }
}
