package com.kachapon.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;

/**
 * "binc" ("cmt "), el formato serializado de Clip Studio para las curvas de la línea de tiempo: un árbol de nodos
 * con una tabla de textos compartida. Reescribir un árbol leído da los mismos bytes que Clip Studio (lo comprueba
 * CoreTest), así que se puede editar el árbol y volver a serializarlo.
 *   cabecera "cmt " + versión + "binc", CRC32 del resto, nº de textos, textos (u8 largo + UTF-8), nodo raíz
 *   nodo: [tabla de saltos de 12 bytes, solo versión 0110] nombre, tipo, valor, atributos, hijos
 * La tabla de textos empieza por los nombres de tipo y sigue por orden de primer uso al escribir.
 */
public final class Binc {
    private static final int NONE = -1;
    private static final Map<String, Integer> SIZES = new HashMap<>();
    private static final String[] COMMON = {"null", "Byte", "SByte", "UInt16", "Int16", "UInt32", "Int32", "Single", "Double", "String"};

    static {
        String[] names = {"Byte", "SByte", "UInt16", "Int16", "UInt32", "Int32", "Single", "Double", "Float2", "Float3", "Double2", "Double3", "Quat", "Matrix44"};
        int[] sizes = {1, 1, 2, 2, 4, 4, 4, 8, 8, 12, 16, 24, 16, 64};
        for (int i = 0; i < names.length; i++) SIZES.put(names[i], sizes[i]);
    }

    public static final class Node {
        public final String name, type;
        public Object value; // null (contenedor), byte[] (escalar), String, List<byte[]> o List<String> (arreglos)
        public final List<String[]> attrs = new ArrayList<>();
        public final List<Node> kids = new ArrayList<>();

        public Node(String name, String type, Object value) {
            this.name = name;
            this.type = type;
            this.value = value;
        }

        public Node find(String n) {
            if (name.equals(n)) return this;
            for (Node k : kids) {
                Node r = k.find(n);
                if (r != null) return r;
            }
            return null;
        }
    }

    /** Árbol leído y la versión ("0100": Single, "0110": Double con tabla de saltos). */
    public static final class Doc {
        public final String version;
        public final Node root;

        Doc(String version, Node root) {
            this.version = version;
            this.root = root;
        }

        public boolean isDouble() {
            return version.equals("0110");
        }
    }

    private Binc() {}

    public static Doc parse(byte[] blob) throws IOException {
        try {
            if (blob.length < 20 || !new String(blob, 0, 4, StandardCharsets.US_ASCII).equals("cmt ")) throw new IOException("no es binc");
            String version = new String(blob, 4, 4, StandardCharsets.US_ASCII);
            ByteBuffer b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
            int n = b.getInt(16), p = 20;
            List<String> table = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int len = blob[p] & 0xFF;
                table.add(new String(blob, p + 1, len, StandardCharsets.UTF_8));
                p += 1 + len;
            }
            b.position(p);
            Node root = node(b, table, version.equals("0110"));
            if (b.hasRemaining()) throw new IOException("sobran datos");
            return new Doc(version, root);
        } catch (RuntimeException e) { // índices o largos fuera de rango en un archivo dañado
            throw new IOException("Línea de tiempo dañada en el .clip.");
        }
    }

    private static String text(ByteBuffer b, List<String> table) {
        int i = b.getInt();
        return i == NONE ? "" : table.get(i);
    }

    private static Node node(ByteBuffer b, List<String> table, boolean jumps) throws IOException {
        if (jumps) b.position(b.position() + 12);
        String name = text(b, table), type = text(b, table);
        Object value = null;
        if (type.endsWith("[]")) {
            String el = type.substring(0, type.length() - 2);
            int count = b.getInt();
            if (el.equals("String")) {
                List<String> l = new ArrayList<>();
                for (int i = 0; i < count; i++) l.add(text(b, table));
                value = l;
            } else {
                List<byte[]> l = new ArrayList<>();
                for (int i = 0; i < count; i++) l.add(bytes(b, size(el)));
                value = l;
            }
        } else if (type.equals("String")) {
            value = text(b, table);
        } else if (!type.equals("null")) {
            value = bytes(b, size(type));
        }
        Node nd = new Node(name, type, value);
        for (int i = b.getInt(); i > 0; i--) nd.attrs.add(new String[]{text(b, table), text(b, table)});
        for (int i = b.getInt(); i > 0; i--) nd.kids.add(node(b, table, jumps));
        return nd;
    }

    private static int size(String type) throws IOException {
        Integer s = SIZES.get(type);
        if (s == null) throw new IOException("Tipo desconocido en la línea de tiempo: " + type);
        return s;
    }

    private static byte[] bytes(ByteBuffer b, int n) {
        byte[] out = new byte[n];
        b.get(out);
        return out;
    }

    public static byte[] serialize(Doc doc) throws IOException {
        boolean dbl = doc.isDouble();
        List<String> table = new ArrayList<>(Arrays.asList(COMMON));
        table.addAll(Arrays.asList(dbl ? new String[]{"Double2", "Double3"} : new String[]{"Float2", "Float3"}));
        table.addAll(Arrays.asList("Quat", "Matrix44"));
        for (String t : new String[]{dbl ? "Double" : "Single", "Byte", "Int32", "String", dbl ? "Double2" : "Float2", dbl ? "Double3" : "Float3", "Quat", "Matrix44"}) table.add(t + "[]");
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        write(doc.root, table, dbl, data);

        ByteArrayOutputStream rest = new ByteArrayOutputStream();
        rest.write(le(table.size()));
        for (String s : table) {
            byte[] u = s.getBytes(StandardCharsets.UTF_8);
            rest.write(u.length);
            rest.write(u);
        }
        data.writeTo(rest);
        byte[] r = rest.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(r);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("cmt " + doc.version + "binc").getBytes(StandardCharsets.US_ASCII));
        out.write(le((int) crc.getValue()));
        out.write(r);
        return out.toByteArray();
    }

    private static int intern(List<String> table, String s) {
        int i = table.indexOf(s);
        if (i < 0) {
            table.add(s);
            i = table.size() - 1;
        }
        return i;
    }

    private static void write(Node nd, List<String> table, boolean jumps, ByteArrayOutputStream out) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(le(intern(table, nd.name)));
        body.write(le(intern(table, nd.type)));
        if (nd.value instanceof byte[]) {
            body.write((byte[]) nd.value);
        } else if (nd.value instanceof String) {
            body.write(le(intern(table, (String) nd.value)));
        } else if (nd.value instanceof List) {
            List<?> l = (List<?>) nd.value;
            body.write(le(l.size()));
            for (Object o : l) body.write(o instanceof String ? le(intern(table, (String) o)) : (byte[]) o);
        }
        ByteArrayOutputStream attrs = new ByteArrayOutputStream();
        attrs.write(le(nd.attrs.size()));
        for (String[] a : nd.attrs) {
            attrs.write(le(intern(table, a[0])));
            attrs.write(le(intern(table, a[1])));
        }
        if (jumps) { // desplazamientos desde el inicio del nodo: tipo, atributos, hijos
            out.write(le(16));
            out.write(le(12 + body.size()));
            out.write(le(12 + body.size() + attrs.size()));
        }
        body.writeTo(out);
        attrs.writeTo(out);
        out.write(le(nd.kids.size()));
        for (Node k : nd.kids) write(k, table, jumps, out);
    }

    private static byte[] le(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }
}
