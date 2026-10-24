package com.kachapon.core.converters;

import com.kachapon.core.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

/**
 * .clip real de Clip Studio Paint: "CSFCHUNK" con trozos CHNKExta (datos externos: píxeles en bloques zlib de
 * 256x256 y curvas de animación) y CHNKSQLi (base SQLite con capas, lienzo y línea de tiempo).
 * Cada carpeta de animación pasa a ser una pista; cada keyframe de su curva ImageCelName, un dibujo con su hold.
 * Al escribir, cada pista vuelve a ser una carpeta de animación.
 * Escribe un .clip nativo (ver ClipWriter) y sigue leyendo el ZIP puente de versiones anteriores de KachaponConverter.
 */
public class ClipConverter extends SequenceZipConverter {
    private static final int BLOCK = 256;
    private static final String BEGIN = "BlockDataBeginChunk";

    public ClipConverter(Bridge bridge) {
        super(".clip", "Clip Studio", bridge);
    }

    @Override
    public Manifest extract(String inputPath, Bridge bridge) throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(inputPath, "r")) {
            byte[] magic = new byte[8];
            if (f.length() < 24 || f.read(magic) != 8 || !"CSFCHUNK".equals(new String(magic, StandardCharsets.US_ASCII))) {
                return super.extract(inputPath, bridge);
            }
            return new Native(f).read(bridge);
        }
    }

    @Override
    public void build(Manifest m, Bridge bridge, File output) throws Exception {
        // Cada capa animada vuelve a ser una carpeta de animación y las quietas, capas normales; las carpetas conservan su modo
        List<ClipWriter.Node> roots = nodes(m, bridge, m.allLayers(), null);
        if (roots.isEmpty()) throw new Exception("La animación no tiene frames.");
        List<Drawing> visible = flatten(m, bridge);
        byte[] preview = visible.isEmpty() ? new byte[0] : visible.get(0).png;
        ClipWriter.write(roots, preview, m.fps, m.width(), m.height(), output);
    }

    /** Hijos de parent (null = raíz), de abajo arriba. */
    private static List<ClipWriter.Node> nodes(Manifest m, Bridge bridge, List<Manifest.Layer> all, String parent) throws IOException {
        List<ClipWriter.Node> out = new ArrayList<>();
        for (Manifest.Layer l : all) {
            if (!Objects.equals(l.parent, parent)) continue;
            String blend = l.blend == null ? "normal" : l.blend;
            if (l.folder()) {
                out.add(ClipWriter.Node.folder(l.name, l.visible, l.opacity, blend, l.passthrough, nodes(m, bridge, all, l.id)));
            } else {
                out.add(ClipWriter.Node.paint(l.name, l.visible, l.opacity, blend, flatten(m, bridge, Collections.singletonList(l.track))));
            }
        }
        return out;
    }

    private static final class Native {
        private final RandomAccessFile f;
        private final Map<String, long[]> externals = new HashMap<>(); // id -> {posición, largo}
        private Map<Long, Map<String, Object>> layers, mipmaps, mipmapInfos, offscreens;
        private int width, height;

        Native(RandomAccessFile f) {
            this.f = f;
        }

        Manifest read(Bridge bridge) throws Exception {
            Sqlite db = new Sqlite(readChunks());
            Map<String, Object> canvas = first(db.rows("Canvas"), "Canvas");
            width = (int) Math.round(num(canvas, "CanvasWidth"));
            height = (int) Math.round(num(canvas, "CanvasHeight"));
            layers = byMainId(db.rows("Layer"));
            mipmaps = byMainId(db.rows("Mipmap"));
            mipmapInfos = byMainId(db.rows("MipmapInfo"));
            offscreens = byMainId(db.rows("Offscreen"));

            // shortcut: solo la primera línea de tiempo; un .clip con varias escenas/cortes exporta la primera
            List<Map<String, Object>> timelines = db.rows("TimeLine");
            Map<String, Object> tl = timelines.isEmpty() ? null : timelines.get(0);
            int fps = tl == null ? 24 : (int) Math.round(num(tl, "FrameRate"));
            int start = tl == null ? 0 : (int) Math.round(num(tl, "StartFrame"));
            int end = tl == null ? 1 : (int) Math.round(num(tl, "EndFrame"));
            Manifest m = new Manifest(width + "x" + height, fps, Math.max(1, end - start));

            // Las curvas de las carpetas de animación, por la carpeta a la que pertenecen
            Map<Long, CelCurve> curves = new HashMap<>();
            for (Map<String, Object> track : db.rows("Track")) {
                CelCurve curve = track.get("TrackActionMixer") instanceof byte[]
                        ? CelCurve.parse(external((byte[]) track.get("TrackActionMixer"))) : null;
                Map<String, Object> folder = curve == null ? null : layerByUuid((byte[]) track.get("LayerUuidWithTrack"));
                if (folder != null && lng(folder, "AnimationFolder") != 0) curves.put(lng(folder, "MainId"), curve);
            }
            walk(first(db.rows("Canvas"), "Canvas"), curves, m, fps, start, bridge);

            if (m.frames.isEmpty()) {
                // Ilustración sin animación: la vista previa del lienzo es la imagen completa ya combinada
                Map<String, Object> preview = first(db.rows("CanvasPreview"), "CanvasPreview");
                byte[] png = (byte[]) preview.get("ImageData");
                if (Png.size(png) == null) throw new Exception("El .clip no tiene animación ni una vista previa PNG legible.");
                m = new Manifest(Png.size(png)[0] + "x" + Png.size(png)[1], fps, 1);
                m.tracks.add("imagen");
                m.frames.add(new FrameRef("imagen", 0, 1, bridge.writeFrame("imagen", 0, png)));
            }
            return m;
        }

        /** Recorre las capas desde la raíz, de abajo arriba: carpetas normales, carpetas de animación (con su curva) y capas sueltas. */
        private void walk(Map<String, Object> canvas, Map<Long, CelCurve> curves, Manifest m, int fps, int start, Bridge bridge) throws Exception {
            chain(lng(layers.get(lng(canvas, "CanvasRootFolder")), "LayerFirstChildIndex"), null, new HashSet<>(), curves, m, fps, start, bridge);
        }

        /** Una cadena de hermanos (cada capa apunta a la siguiente, la de arriba); las carpetas normales se abren en su sitio. */
        private void chain(long first, String parent, Set<Long> seen, Map<Long, CelCurve> curves, Manifest m, int fps, int start, Bridge bridge) throws Exception {
            for (long id = first; id != 0 && layers.containsKey(id) && seen.add(id); id = lng(layers.get(id), "LayerNextIndex")) {
                Map<String, Object> l = layers.get(id);
                long type = lng(l, "LayerType");
                if (type == 1584 || type == 800 || type == 256) continue; // papel, información de la obra, raíz
                String name = str(l, "LayerName");
                boolean folder = lng(l, "LayerFolder") != 0, animated = curves.containsKey(id);
                Manifest.Layer info = new Manifest.Layer();
                info.name = name;
                info.parent = parent;
                info.visible = num(l, "LayerVisibility") != 0;
                info.opacity = (int) Math.round(num(l, "LayerOpacity") * 255 / 256);
                long composite = lng(l, "LayerComposite");
                info.passthrough = folder && !animated && composite == Blend.CLIP_PASS_THROUGH;
                info.blend = composite == Blend.CLIP_PASS_THROUGH ? "normal" : Blend.fromClip((int) composite);
                if (folder && !animated) {
                    info.id = "c" + id;
                    m.layers.add(info);
                    chain(lng(l, "LayerFirstChildIndex"), info.id, seen, curves, m, fps, start, bridge);
                    continue;
                }
                if ((type & 4096) != 0 || (!folder && type != 1)) {
                    System.err.println("Advertencia: la capa \"" + name + "\" (tipo " + type + ") no se puede convertir y se omite.");
                    continue;
                }
                String track = Bridge.addTrack(m, name.isEmpty() ? "animacion" : name);
                info.id = track;
                info.track = track;
                if (!folder) { // capa suelta: un solo dibujo durante toda la animación
                    byte[] png = celPng(l);
                    if (png == null) { m.tracks.remove(track); continue; }
                    m.frames.add(new FrameRef(track, 0, m.duration, bridge.writeFrame(track, 0, png)));
                } else {
                    readAnimation(l, curves.get(id), track, m, fps, start, bridge);
                }
                m.layers.add(info);
            }
        }

        /** Los cels de una carpeta de animación puestos donde dice su curva ImageCelName. */
        private void readAnimation(Map<String, Object> folder, CelCurve curve, String track, Manifest m, int fps, int start, Bridge bridge) throws Exception {
            String name = str(folder, "LayerName");
            Map<String, Map<String, Object>> cels = new HashMap<>();
            for (long id = lng(folder, "LayerFirstChildIndex"); id != 0 && layers.containsKey(id); id = lng(layers.get(id), "LayerNextIndex")) {
                cels.putIfAbsent(str(layers.get(id), "LayerName"), layers.get(id));
            }
            Map<Long, byte[]> pngs = new HashMap<>(); // un cel puede repetirse en la línea de tiempo
            for (int k = 0; k < curve.times.length; k++) {
                String celName = curve.cels[k];
                if (celName == null || celName.isEmpty()) continue; // hueco
                int at = Math.max(0, frame(curve.times[k], fps, curve.rate) - start);
                int next = k + 1 < curve.times.length ? frame(curve.times[k + 1], fps, curve.rate) - start : m.duration;
                next = Math.min(next, m.duration);
                if (at >= next) continue;
                Map<String, Object> cel = cels.get(celName);
                if (cel == null) throw new Exception("La línea de tiempo usa el cel \"" + celName + "\", que no está en \"" + name + "\".");
                long celId = lng(cel, "MainId");
                if (!pngs.containsKey(celId)) pngs.put(celId, celPng(cel));
                if (pngs.get(celId) == null) continue; // cel vacío: hueco
                m.frames.add(new FrameRef(track, at, next - at, bridge.writeFrame(track, at, pngs.get(celId))));
            }
        }

        /** Recorre los trozos del archivo; guarda la ubicación de los externos y devuelve la base SQLite. */
        private byte[] readChunks() throws Exception {
            byte[] sqlite = null;
            long pos = 24;
            while (pos + 16 <= f.length()) {
                f.seek(pos);
                byte[] type = new byte[8];
                f.readFully(type);
                long len = f.readLong();
                if (len < 0 || pos + 16 + len > f.length()) throw new Exception("El .clip está dañado o incompleto.");
                String t = new String(type, StandardCharsets.US_ASCII);
                if (t.equals("CHNKExta")) {
                    int idLen = (int) f.readLong();
                    if (idLen < 0 || idLen > 1024) throw new Exception("El .clip está dañado (identificador externo).");
                    byte[] id = new byte[idLen];
                    f.readFully(id);
                    long dataLen = f.readLong();
                    externals.put(new String(id, StandardCharsets.US_ASCII), new long[]{f.getFilePointer(), dataLen});
                } else if (t.equals("CHNKSQLi")) {
                    sqlite = new byte[(int) len];
                    f.readFully(sqlite);
                } else if (t.equals("CHNKFoot")) {
                    break;
                }
                pos += 16 + len;
            }
            if (sqlite == null) throw new Exception("El .clip no contiene su base de datos (CHNKSQLi).");
            return sqlite;
        }

        private byte[] external(byte[] ref) throws Exception {
            long[] e = externals.get(new String(ref, StandardCharsets.US_ASCII));
            if (e == null || e[1] < 0 || e[1] > Integer.MAX_VALUE) throw new Exception("Faltan datos externos en el .clip.");
            byte[] data = new byte[(int) e[1]];
            f.seek(e[0]);
            f.readFully(data);
            return data;
        }

        private Map<String, Object> layerByUuid(byte[] uuid) {
            if (uuid == null) return null;
            StringBuilder hex = new StringBuilder();
            for (byte b : uuid) hex.append(String.format("%02x", b));
            for (Map<String, Object> l : layers.values()) {
                Object u = l.get("LayerUuid");
                if (u instanceof String && ((String) u).replace("-", "").equalsIgnoreCase(hex.toString())) return l;
            }
            return null;
        }

        /** Píxeles del cel: Layer -> Mipmap -> MipmapInfo (100%) -> Offscreen -> bloques zlib (alfa + BGRA). */
        private byte[] celPng(Map<String, Object> cel) throws Exception {
            String name = str(cel, "LayerName");
            if (lng(cel, "LayerFolder") != 0) throw new Exception("El cel \"" + name + "\" es una carpeta; todavía no se combinan carpetas de cel.");
            Map<String, Object> mip = mipmaps.get(lng(cel, "LayerRenderMipmap"));
            Map<String, Object> info = mip == null ? null : mipmapInfos.get(lng(mip, "BaseMipmapInfo"));
            Map<String, Object> off = info == null ? null : offscreens.get(lng(info, "Offscreen"));
            if (off == null || !(off.get("Attribute") instanceof byte[]) || !(off.get("BlockData") instanceof byte[])) {
                throw new Exception("Faltan los píxeles del cel \"" + name + "\".");
            }

            ByteBuffer attr = ByteBuffer.wrap((byte[]) off.get("Attribute"));
            attr.position(16);
            attr.position(20 + 2 * attr.getInt()); // salta el texto "Parameter"
            attr.getInt(); // ancho del bitmap
            attr.getInt(); // alto del bitmap
            int gridWidth = attr.getInt();

            // shortcut: ignora LayerOffsetX/Y (siempre 0 en los cels probados); cels desplazados saldrían en su sitio original
            int[] argb = new int[width * height];
            boolean any = false;
            ByteBuffer b = ByteBuffer.wrap(external((byte[]) off.get("BlockData")));
            // Después de los bloques vienen BlockStatus/BlockCheckSum, con otra estructura: se mira el nombre antes de leer
            while (b.remaining() > 8 && b.getInt(b.position() + 4) == BEGIN.length()) {
                b.getInt(); // largo del bloque
                if (!BEGIN.equals(utf16(b))) break;
                int index = b.getInt();
                int rawSize = b.getInt();
                b.getInt(); // ancho del bloque
                b.getInt(); // alto del bloque
                if (b.getInt() != 0) {
                    int len = b.getInt();
                    b.getInt(); // largo comprimido otra vez, en little-endian
                    byte[] z = new byte[len - 4];
                    b.get(z);
                    if (rawSize != BLOCK * BLOCK * 5) throw new Exception("Formato de píxeles no soportado en el cel \"" + name + "\".");
                    byte[] px = inflate(z, rawSize);
                    int x0 = index % gridWidth * BLOCK, y0 = index / gridWidth * BLOCK;
                    for (int y = 0; y < BLOCK && y0 + y < height; y++) {
                        for (int x = 0; x < BLOCK && x0 + x < width; x++) {
                            int j = y * BLOCK + x, o = BLOCK * BLOCK + 4 * j;
                            argb[(y0 + y) * width + x0 + x] = (px[j] & 0xFF) << 24
                                    | (px[o + 2] & 0xFF) << 16 | (px[o + 1] & 0xFF) << 8 | (px[o] & 0xFF);
                            any |= px[j] != 0;
                        }
                    }
                }
                utf16(b); // BlockDataEndChunk
            }
            return any ? Png.encode(argb, width, height) : null; // null = cel sin pixeles visibles
        }
    }

    /** Curva "ImageCelName" de una pista: en qué tick empieza cada cel. Formato serializado "cmt ...binc". */
    private static final class CelCurve {
        double[] times;
        String[] cels;
        double rate = 60; // ticks por segundo

        static CelCurve parse(byte[] data) throws Exception {
            if (data.length < 8) return null;
            // Los 4 primeros bytes son el largo comprimido; el descomprimido no se guarda
            byte[] b;
            try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(data, 4, data.length - 4))) {
                b = Bridge.readAll(in);
            } catch (ZipException e) {
                throw new Exception("Línea de tiempo dañada en el .clip.");
            }
            if (b.length < 20 || !new String(b, 0, 4, StandardCharsets.US_ASCII).equals("cmt ")) return null;

            ByteBuffer v = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            int count = v.getInt(16), p = 20;
            List<String> s = new ArrayList<>(); // tabla de textos: los campos y tipos se refieren a ella por índice
            for (int i = 0; i < count; i++) {
                int len = b[p] & 0xFF;
                s.add(new String(b, p + 1, len, StandardCharsets.UTF_8));
                p += 1 + len;
            }
            if (!s.contains("ImageCelName")) return null;

            // shortcut: toma la primera curva de la pista; basta mientras ImageCelName sea su único parámetro animado
            CelCurve c = new CelCurve();
            int at = field(b, p, s, "Frame", "Single[]");
            boolean doubles = at < 0;
            if (doubles) at = field(b, p, s, "Frame", "Double[]");
            if (at < 0) return null;
            int n = v.getInt(at);
            c.times = new double[n];
            for (int i = 0; i < n; i++) c.times[i] = doubles ? v.getDouble(at + 4 + 8 * i) : v.getFloat(at + 4 + 4 * i);

            int tags = field(b, p, s, "Tag", "String[]");
            if (tags < 0 || v.getInt(tags) != n) return null;
            c.cels = new String[n];
            for (int i = 0; i < n; i++) {
                int idx = v.getInt(tags + 4 + 4 * i);
                c.cels[i] = idx >= 0 && idx < s.size() ? s.get(idx) : null;
            }
            int rate = field(b, p, s, "Rate", "Double");
            if (rate >= 0 && v.getDouble(rate) > 0) c.rate = v.getDouble(rate);
            return c;
        }

        /** Posición del valor del primer campo [nombre][tipo], o -1. */
        private static int field(byte[] b, int from, List<String> s, String name, String type) {
            int ni = s.indexOf(name), ti = s.indexOf(type);
            if (ni < 0 || ti < 0) return -1;
            ByteBuffer v = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = from; i + 12 <= b.length; i++) {
                if (v.getInt(i) == ni && v.getInt(i + 4) == ti) return i + 8;
            }
            return -1;
        }
    }

    private static int frame(double ticks, int fps, double rate) {
        return (int) Math.round(ticks * fps / rate);
    }

    private static byte[] inflate(byte[] z, int expected) throws Exception {
        if (expected < 0 || expected > 64 << 20) throw new Exception("Bloque comprimido inválido en el .clip.");
        Inflater inf = new Inflater();
        try {
            inf.setInput(z);
            byte[] out = new byte[expected];
            int n = 0;
            while (n < expected && !inf.finished()) {
                int r = inf.inflate(out, n, expected - n);
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                n += r;
            }
            if (n != expected) throw new Exception("Bloque comprimido incompleto en el .clip.");
            return out;
        } catch (DataFormatException e) {
            throw new Exception("Bloque comprimido dañado en el .clip.");
        } finally {
            inf.end();
        }
    }

    private static String utf16(ByteBuffer b) {
        int len = b.getInt();
        byte[] s = new byte[2 * len];
        b.get(s);
        return new String(s, StandardCharsets.UTF_16BE);
    }

    private static Map<Long, Map<String, Object>> byMainId(List<Map<String, Object>> rows) {
        Map<Long, Map<String, Object>> out = new HashMap<>();
        for (Map<String, Object> r : rows) out.put(lng(r, "MainId"), r);
        return out;
    }

    private static Map<String, Object> first(List<Map<String, Object>> rows, String table) throws Exception {
        if (rows.isEmpty()) throw new Exception("El .clip no tiene datos en " + table + ".");
        return rows.get(0);
    }

    private static long lng(Map<String, Object> r, String col) {
        Object v = r.get(col);
        return v instanceof Number ? ((Number) v).longValue() : 0;
    }

    private static double num(Map<String, Object> r, String col) {
        Object v = r.get(col);
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    private static String str(Map<String, Object> r, String col) {
        Object v = r.get(col);
        return v instanceof String ? (String) v : "";
    }
}
