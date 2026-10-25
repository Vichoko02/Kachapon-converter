package com.kachapon.core.converters;

import com.kachapon.core.*;
import org.w3c.dom.*;

import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/**
 * .kra real de Krita.
 *  Leer: se conserva el árbol de capas y grupos con el modo de fusión, la opacidad y la visibilidad de cada uno.
 *        Por cada capa de pintura, Krita exporta por detrás (--export-sequence) una copia del .kra con todo lo demás
 *        oculto y sus carpetas en fusión normal; las máscaras de transformación y de filtro quedan aplicadas en los
 *        píxeles. Cada capa es una pista: los frames iguales seguidos se juntan en un hold y los vacíos quedan como huecos.
 *  Escribir: documento Krita nativo con una capa de pintura animada por pista (keyframes + capas en bloques 64x64).
 */
public class KritaConverter extends Converter {
    private static final int TILE = 64;
    private static final String IMAGE = "KachaponConverter"; // carpeta interna del documento
    private static final String DOCTYPE_PUBLIC = "-//KDE//DTD krita 2.0//EN", DOCTYPE_SYSTEM = "http://www.calligra.org/DTD/krita-2.0.dtd";

    public KritaConverter(Bridge bridge) {
        super(bridge);
    }

    @Override
    public Manifest extract(String inputPath, Bridge bridge) throws Exception {
        Element image;
        Document doc;
        try (ZipFile zf = openZip(inputPath, "El archivo no es un .kra válido (ZIP).")) {
            ZipEntry docEntry = zf.getEntry("maindoc.xml");
            if (docEntry == null) throw new Exception("El .kra no contiene maindoc.xml.");
            try (InputStream is = zf.getInputStream(docEntry)) {
                doc = xmlBuilder().parse(is);
                image = first(doc.getDocumentElement(), "IMAGE");
            }
        }
        if (image == null) throw new Exception("El .kra no es un documento de Krita (falta IMAGE).");
        Element rate = first(image, "framerate"), range = first(image, "range");
        int fps = rate == null ? 24 : intAttr(rate, "value", 24);
        int from = range == null ? 0 : intAttr(range, "from", 0), to = range == null ? 0 : intAttr(range, "to", 0);
        Manifest m = new Manifest(intAttr(image, "width", 0) + "x" + intAttr(image, "height", 0), fps, Math.max(1, to - from + 1));

        Element top = child(image, "layers");
        if (top == null) throw new Exception("El .kra no tiene capas.");
        List<Element> all = elements(top, "layer");
        List<Element> leaves = new ArrayList<>(); // capas de pintura, de abajo arriba
        tree(top, null, m, leaves);

        File copy = new File(bridge.dir, "krita_capa.kra");
        for (int u = 0; u < leaves.size(); u++) {
            Element leaf = leaves.get(u);
            Manifest.Layer layer = m.layers.get(indexOf(m, leaf.getAttribute("kachapon-id")));
            isolate(all, leaf);
            writeCopy(inputPath, xml(doc, DOCTYPE_PUBLIC, DOCTYPE_SYSTEM), copy);
            // Una capa quieta, sin movimientos de máscaras por encima, es una sola imagen: no hace falta exportar la animación
            boolean still = !"1".equals(leaf.getAttribute("intimeline")) && !moves(leaf);
            File dir = new File(bridge.dir, "krita_export_" + u);
            TreeMap<Integer, File> exported = export(copy, dir, m.duration, !still);

            String track = Bridge.addTrack(m, layer.name);
            layer.track = track;
            byte[] held = null;
            int start = 0;
            for (int t = 0; t <= m.duration; t++) {
                File f = t < m.duration ? exported.get(t) : null;
                byte[] png = f == null ? null : read(f);
                if (png != null) {
                    int[] s = Png.size(png);
                    if (s == null || s[0] != m.width() || s[1] != m.height()) throw new Exception("Krita exportó una imagen inválida para la capa \"" + layer.name + "\".");
                }
                if (png != null && held != null && Arrays.equals(png, held)) continue; // mismo dibujo: se alarga el hold
                if (held != null && !blank(held)) m.frames.add(new FrameRef(track, start, t - start, bridge.writeFrame(track, start, held)));
                held = png;
                start = t;
            }
            for (File f : new HashSet<>(exported.values())) f.delete();
            dir.delete();
            bridge.progress((u + 1) / (double) leaves.size(), "Krita: capa " + (u + 1) + " de " + leaves.size() + " (" + layer.name + ")");
            System.err.println("Krita: capa " + (u + 1) + "/" + leaves.size() + " \"" + layer.name + "\"");
        }
        copy.delete();

        // Las capas sin ningún pixel en toda la animación no aportan nada, y sus carpetas vacías tampoco
        Set<String> used = new HashSet<>();
        for (FrameRef fr : m.frames) used.add(fr.track);
        m.layers.removeIf(l -> !l.folder() && !used.contains(l.track));
        Set<String> keep = new HashSet<>();
        for (Manifest.Layer l : m.layers) for (Manifest.Layer p = l; p != null; p = m.parent(p)) keep.add(p.id);
        m.layers.removeIf(l -> !keep.contains(l.id));
        m.tracks.removeIf(t -> !used.contains(t));
        if (m.tracks.isEmpty()) throw new Exception("El .kra no tiene nada dibujado.");
        return m;
    }

    /**
     * Recorre las capas de abajo arriba (maindoc.xml las lista de arriba abajo) y apunta cada capa de pintura y
     * cada grupo en m.layers con su modo de fusión, opacidad y visibilidad. Las capas de pintura quedan en leaves;
     * lo que Krita tiene y el puente no (capas de relleno, de ajuste, clones, vectores) se avisa y se omite.
     */
    private static void tree(Element layers, String parent, Manifest m, List<Element> leaves) {
        List<Element> kids = children(layers, "layer");
        Collections.reverse(kids);
        for (Element el : kids) {
            String type = el.getAttribute("nodetype");
            if (type.equals("referenceimages")) continue;
            if (!type.equals("paintlayer") && !type.equals("grouplayer")) {
                System.err.println("Advertencia: la capa \"" + el.getAttribute("name") + "\" es de tipo " + type + " y se omite.");
                continue;
            }
            Manifest.Layer l = new Manifest.Layer();
            l.id = "n" + m.layers.size();
            l.name = el.getAttribute("name");
            l.parent = parent;
            l.visible = !"0".equals(el.getAttribute("visible"));
            l.opacity = Math.max(0, Math.min(255, intAttr(el, "opacity", 255)));
            l.blend = el.getAttribute("compositeop").isEmpty() ? "normal" : el.getAttribute("compositeop");
            el.setAttribute("kachapon-id", l.id);
            m.layers.add(l);
            if (type.equals("paintlayer")) {
                leaves.add(el);
                if ("1".equals(el.getAttribute("inheritalpha"))) {
                    System.err.println("Advertencia: la capa \"" + l.name + "\" usa 'heredar alfa' y se exporta sin él.");
                }
            } else {
                l.passthrough = "1".equals(el.getAttribute("passthrough"));
                Element inner = child(el, "layers");
                if (inner != null) tree(inner, l.id, m, leaves);
            }
        }
    }

    private static int indexOf(Manifest m, String id) {
        for (int i = 0; i < m.layers.size(); i++) if (m.layers.get(i).id.equals(id)) return i;
        throw new IllegalStateException(id);
    }

    /** Si la capa o alguna de sus carpetas tiene una máscara de transformación (que puede mover la imagen con el tiempo). */
    private static boolean moves(Element leaf) {
        Element masks = child(leaf, "masks");
        if (masks != null) for (Element mask : children(masks, "mask")) if (isTransform(mask)) return true;
        for (Node n = leaf.getParentNode(); n != null; n = n.getParentNode()) {
            if (!(n instanceof Element) || !((Element) n).getTagName().equals("layers")) continue;
            for (Element mask : children((Element) n, "mask")) if (isTransform(mask)) return true;
        }
        return false;
    }

    private static boolean isTransform(Element mask) {
        return mask.getAttribute("nodetype").equals("transformmask") && !"0".equals(mask.getAttribute("visible"));
    }

    /**
     * Deja visible solo esta capa y sus carpetas, todas en fusión normal y opacidad completa: así la exportación
     * trae los píxeles de la capa con sus máscaras aplicadas, y el modo de fusión y la opacidad se escriben aparte.
     */
    private static void isolate(List<Element> all, Element leaf) {
        Set<Node> ancestors = new HashSet<>();
        for (Node n = leaf; n != null; n = n.getParentNode()) ancestors.add(n);
        for (Element el : all) {
            boolean on = ancestors.contains(el);
            el.setAttribute("visible", on ? "1" : "0");
            if (on) {
                el.setAttribute("opacity", "255");
                el.setAttribute("compositeop", "normal");
            }
        }
    }

    /** Copia del .kra con otro maindoc.xml; mimetype va primero y sin comprimir, como lo exige Krita. */
    private static void writeCopy(String input, byte[] maindoc, File out) throws IOException {
        try (ZipFile zf = new ZipFile(input); ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            zos.setLevel(Deflater.BEST_SPEED); // la copia solo vive lo que tarda Krita en exportarla
            ZipEntry mime = zf.getEntry("mimetype");
            if (mime != null) {
                byte[] data;
                try (InputStream in = zf.getInputStream(mime)) {
                    data = Bridge.readAll(in);
                }
                ZipEntry e = new ZipEntry("mimetype");
                e.setMethod(ZipEntry.STORED);
                e.setSize(data.length);
                CRC32 crc = new CRC32();
                crc.update(data);
                e.setCrc(crc.getValue());
                zos.putNextEntry(e);
                zos.write(data);
                zos.closeEntry();
            }
            byte[] buf = new byte[1 << 16];
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.getName().equals("mimetype")) continue;
                zos.putNextEntry(new ZipEntry(e.getName()));
                if (e.getName().equals("maindoc.xml")) {
                    zos.write(maindoc);
                } else {
                    try (InputStream in = zf.getInputStream(e)) {
                        for (int n; (n = in.read(buf)) > 0; ) zos.write(buf, 0, n);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    /** Frame (desde 0) -> PNG exportado por Krita. Sin sequence es una sola imagen que dura toda la animación. */
    private static TreeMap<Integer, File> export(File kra, File dir, int duration, boolean sequence) throws Exception {
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("No se puede crear " + dir);
        if (!sequence) return stillFrames(kra, dir, duration, 0);
        for (int attempt = 0; ; attempt++) {
            int code = krita(kra.getPath(), "--export-sequence", "--export-filename", new File(dir, "frame.png").getPath());
            TreeMap<Integer, File> byNumber = new TreeMap<>();
            File[] files = dir.listFiles();
            for (File f : files == null ? new File[0] : files) {
                String digits = f.getName().replaceAll("\\D", "");
                if (f.getName().endsWith(".png") && !digits.isEmpty()) byNumber.put(Integer.parseInt(digits), f);
            }
            if (byNumber.isEmpty()) return stillFrames(kra, dir, duration, code); // el documento no tiene animación
            // Krita a veces termina antes de exportar todos los frames: se reintenta una vez
            if (byNumber.size() == duration && byNumber.lastKey() - byNumber.firstKey() + 1 == duration) {
                TreeMap<Integer, File> out = new TreeMap<>();
                int first = byNumber.firstKey();
                for (Map.Entry<Integer, File> e : byNumber.entrySet()) out.put(e.getKey() - first, e.getValue());
                return out;
            }
            for (File f : byNumber.values()) f.delete();
            if (attempt > 0) throw new Exception("Krita exportó " + byNumber.size() + " de " + duration + " frames (código " + code + ").");
        }
    }

    private static TreeMap<Integer, File> stillFrames(File kra, File dir, int duration, int previousCode) throws Exception {
        File still = new File(dir, "still.png");
        int code = krita(kra.getPath(), "--export", "--export-filename", still.getPath());
        if (!still.exists()) throw new Exception("Krita no pudo exportar el .kra (código " + (code != 0 ? code : previousCode) + ").");
        TreeMap<Integer, File> out = new TreeMap<>();
        for (int t = 0; t < duration; t++) out.put(t, still);
        return out;
    }

    @Override
    public void build(Manifest m, Bridge bridge, File output) throws Exception {
        Document doc = xmlBuilder().newDocument();
        Element root = add(doc, "DOC", "xmlns", "http://www.calligra.org/DTD/krita", "editor", "KachaponConverter",
                "syntaxVersion", "2.0", "kritaVersion", "5.2.0");
        Element image = add(root, "IMAGE", "mime", "application/x-kra", "name", IMAGE, "description", "",
                "width", m.width(), "height", m.height(), "x-res", 72, "y-res", 72,
                "colorspacename", "RGBA", "profile", "sRGB-elle-V2-srgbtrc.icc");
        Element layers = add(image, "layers");
        Element anim = add(image, "animation");
        add(anim, "framerate", "type", "value", "value", m.fps);
        add(anim, "range", "type", "timerange", "from", 0, "to", Math.max(0, m.duration - 1));
        add(anim, "currentTime", "type", "value", "value", 0);

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(output))) {
            // Krita reconoce el formato por "mimetype": primera entrada y sin comprimir
            byte[] mime = "application/x-krita".getBytes(StandardCharsets.US_ASCII);
            ZipEntry mt = new ZipEntry("mimetype");
            mt.setMethod(ZipEntry.STORED);
            mt.setSize(mime.length);
            CRC32 crc = new CRC32();
            crc.update(mime);
            mt.setCrc(crc.getValue());
            zos.putNextEntry(mt);
            zos.write(mime);
            zos.closeEntry();

            // Capas y carpetas con su modo de fusión; maindoc.xml las lista de arriba abajo
            List<Manifest.Layer> nodes = m.allLayers();
            int[] counter = {1};
            writeLayers(zos, layers, null, nodes, m, bridge, counter);
            List<Drawing> visible = flatten(m, bridge);
            if (!visible.isEmpty()) put(zos, "mergedimage.png", visible.get(0).png);
            put(zos, "maindoc.xml", xml(doc, DOCTYPE_PUBLIC, DOCTYPE_SYSTEM));
        }
    }

    /** Escribe los hijos de parent (null = raíz) en maindoc.xml; cada capa de pintura lleva su animación en keyframes. */
    private void writeLayers(ZipOutputStream zos, Element into, String parent, List<Manifest.Layer> nodes, Manifest m,
                             Bridge bridge, int[] counter) throws Exception {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            Manifest.Layer l = nodes.get(i);
            if (!Objects.equals(l.parent, parent)) continue;
            String file = "layer" + (++counter[0]);
            String uuid = "{" + UUID.randomUUID() + "}", blend = l.blend == null ? "normal" : l.blend;
            if (l.folder()) {
                Element group = add(into, "layer", "name", l.name, "filename", file, "nodetype", "grouplayer", "uuid", uuid,
                        "x", 0, "y", 0, "visible", l.visible ? 1 : 0, "opacity", l.opacity, "compositeop", blend,
                        "passthrough", l.passthrough ? 1 : 0, "colorspacename", "RGBA", "channelflags", "", "channellockflags", "",
                        "locked", 0, "collapsed", 0, "intimeline", 0, "onionskin", 0, "colorlabel", 0);
                writeLayers(zos, add(group, "layers"), l.id, nodes, m, bridge, counter);
                continue;
            }
            add(into, "layer", "name", l.name, "filename", file, "keyframes", file + ".keyframes.xml",
                    "nodetype", "paintlayer", "uuid", uuid, "x", 0, "y", 0,
                    "visible", l.visible ? 1 : 0, "opacity", l.opacity, "compositeop", blend, "colorspacename", "RGBA",
                    "channelflags", "", "channellockflags", "", "locked", 0, "collapsed", 0,
                    "intimeline", 1, "onionskin", 0, "colorlabel", 0);

            List<FrameRef> frames = new ArrayList<>();
            for (FrameRef fr : m.frames) if (fr.track.equals(l.track)) frames.add(fr);
            frames.sort(Comparator.comparingInt(fr -> fr.frame));

            // Krita mantiene cada keyframe hasta el siguiente: los huecos llevan un keyframe vacío
            Document kd = xmlBuilder().newDocument();
            Element channel = add(add(kd, "keyframes", "xmlns", "http://www.calligra.org/DTD/krita-keyframes"),
                    "channel", "name", "content");
            int end = 0, n = 0;
            for (FrameRef fr : frames) {
                if (fr.frame > end || (n == 0 && fr.frame > 0)) keyframe(zos, channel, file, n++, end, null, m);
                keyframe(zos, channel, file, n++, fr.frame, bridge.readFrame(fr), m);
                end = fr.frame + fr.hold;
            }
            if (n == 0 || end < m.duration) keyframe(zos, channel, file, n, end, null, m);
            put(zos, IMAGE + "/layers/" + file + ".keyframes.xml", xml(kd, "-//KDE//DTD krita-keyframes 1.0//EN", "http://www.calligra.org/DTD/krita-keyframes-1.0.dtd"));
            bridge.progress(counter[0] / (double) Math.max(1, m.layers.size()), "Krita: escribiendo " + l.name);
        }
    }

    /** Un keyframe y su dibujo ("layerN", "layerN.f1", ...); png null = cuadro vacío. */
    private static void keyframe(ZipOutputStream zos, Element channel, String layer, int n, int time, byte[] png, Manifest m) throws Exception {
        String file = n == 0 ? layer : layer + ".f" + n;
        add(channel, "keyframe", "time", time, "frame", file, "color-label", 0);
        put(zos, IMAGE + "/layers/" + file, tiles(png == null ? null : Png.decode(png), m.width(), m.height(), png));
        put(zos, IMAGE + "/layers/" + file + ".defaultpixel", new byte[4]);
    }

    /** Capa en el formato de Krita: bloques de 64x64 BGRA sin comprimir (los comprimidos irían por planos). */
    private static byte[] tiles(int[] argb, int w, int h, byte[] png) throws IOException {
        if (argb != null) {
            int[] s = Png.size(png);
            if (s[0] != w || s[1] != h) throw new IOException("Un dibujo mide " + s[0] + "x" + s[1] + " y el documento " + w + "x" + h + ".");
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int count = 0;
        byte[] tile = new byte[TILE * TILE * 4];
        for (int ty = 0; argb != null && ty < h; ty += TILE) {
            for (int tx = 0; tx < w; tx += TILE) {
                Arrays.fill(tile, (byte) 0);
                boolean empty = true;
                for (int y = 0; y < TILE && ty + y < h; y++) {
                    for (int x = 0; x < TILE && tx + x < w; x++) {
                        int c = argb[(ty + y) * w + tx + x], j = 4 * (y * TILE + x);
                        tile[j] = (byte) c;               // B
                        tile[j + 1] = (byte) (c >> 8);    // G
                        tile[j + 2] = (byte) (c >> 16);   // R
                        tile[j + 3] = (byte) (c >>> 24);  // A
                        if (c >>> 24 != 0) empty = false;
                    }
                }
                if (empty) continue; // los bloques que faltan son transparentes (defaultpixel)
                body.write((tx + "," + ty + ",LZF," + (tile.length + 1) + "\n").getBytes(StandardCharsets.US_ASCII));
                body.write(0); // 0 = datos sin comprimir
                body.write(tile);
                count++;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("VERSION 2\nTILEWIDTH " + TILE + "\nTILEHEIGHT " + TILE + "\nPIXELSIZE 4\nDATA " + count + "\n")
                .getBytes(StandardCharsets.US_ASCII));
        body.writeTo(out);
        return out.toByteArray();
    }

    /** Ejecuta Krita sin ventana para exportar; falla con un mensaje claro si no está instalado o no termina. */
    // Una sola exportación de Krita a la vez: varias conversiones pueden compartir el programa sin pisarse
    private static final java.util.concurrent.Semaphore KRITA = new java.util.concurrent.Semaphore(1);

    private static int krita(String... args) throws Exception {
        KRITA.acquire();
        try {
            return runKrita(args);
        } finally {
            KRITA.release();
        }
    }

    private static int runKrita(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(kritaExecutable());
        cmd.addAll(Arrays.asList(args));
        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new Exception("Para leer .kra se necesita Krita instalado (o la variable KRITA_PATH con la ruta a krita.exe).");
        }
        Thread drain = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                Bridge.readAll(in);
            } catch (IOException ignored) {
                // la salida de Krita solo se descarta para que no se bloquee
            }
        });
        drain.start();
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new Exception("Krita tardó demasiado en exportar el .kra.");
        }
        drain.join();
        return p.exitValue();
    }

    private static String kritaExecutable() {
        String env = System.getenv("KRITA_PATH");
        if (env != null && !env.isEmpty()) return env;
        File windows = new File("C:\\Program Files\\Krita (x64)\\bin\\krita.exe");
        return windows.exists() ? windows.getPath() : "krita";
    }

    private static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return Bridge.readAll(in);
        }
    }

    private static boolean blank(byte[] png) throws IOException {
        for (int c : Png.decode(png)) if (c >>> 24 != 0) return false;
        return true;
    }

    private static String trackName(String name) {
        String clean = name == null ? "" : name.trim();
        return clean.isEmpty() ? "krita" : clean;
    }

    /** Krita rechaza el documento ("no se puede analizar") si falta el DOCTYPE. */
    private static byte[] xml(Document doc, String publicId, String systemId) throws TransformerException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, publicId);
        t.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, systemId);
        t.transform(new DOMSource(doc), new StreamResult(out));
        return out.toByteArray();
    }

    private static DocumentBuilder xmlBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            // maindoc.xml viene del archivo de entrada: sin DOCTYPE externo no hay entidades externas (XXE)
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (ParserConfigurationException ignored) {
            // El parser de Android no admite estas opciones, pero tampoco resuelve entidades externas
        }
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }

    private static Element first(Element parent, String tag) {
        if (parent.getTagName().equals(tag)) return parent;
        NodeList nl = parent.getElementsByTagName(tag);
        return nl.getLength() > 0 ? (Element) nl.item(0) : null;
    }

    /** Hijos directos con esa etiqueta, en orden. */
    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && ((Element) n).getTagName().equals(tag)) out.add((Element) n);
        }
        return out;
    }

    private static Element child(Element parent, String tag) {
        List<Element> c = children(parent, tag);
        return c.isEmpty() ? null : c.get(0);
    }

    /** Todos los descendientes con esa etiqueta. */
    private static List<Element> elements(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getElementsByTagName(tag);
        for (int i = 0; i < nl.getLength(); i++) out.add((Element) nl.item(i));
        return out;
    }

    private static int intAttr(Element e, String attr, int def) {
        try {
            return (int) Math.round(Double.parseDouble(e.getAttribute(attr)));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    /** Añade un elemento con atributos (pares nombre, valor). */
    private static Element add(Node parent, String tag, Object... attrs) {
        Document doc = parent instanceof Document ? (Document) parent : parent.getOwnerDocument();
        Element el = doc.createElement(tag);
        for (int i = 0; i < attrs.length; i += 2) el.setAttribute((String) attrs[i], String.valueOf(attrs[i + 1]));
        parent.appendChild(el);
        return el;
    }
}
