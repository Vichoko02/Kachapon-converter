package com.kachapon.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Lector mínimo de SQLite en memoria (solo lectura, recorre tablas completas) para la base que va dentro de un .clip.
 * Sin JDBC para que core funcione igual en Android.
 * shortcut: no usa índices; basta para .clip.
 */
public final class Sqlite {
    private final byte[] db;
    private final int pageSize;
    private final int usable;
    private final Map<String, Integer> roots = new HashMap<>();
    private final Map<String, String[]> columns = new HashMap<>();
    private final List<Table> schema = new ArrayList<>(); // en el orden del archivo, sin filas

    public Sqlite(byte[] db) throws IOException {
        if (db.length < 100 || !new String(db, 0, 15, StandardCharsets.US_ASCII).equals("SQLite format 3")) {
            throw new IOException("La base de datos interna no es SQLite.");
        }
        if (u32(db, 56) != 1) throw new IOException("Codificación de texto SQLite no soportada.");
        this.db = db;
        int ps = u16(db, 16);
        pageSize = ps == 1 ? 65536 : ps;
        usable = pageSize - (db[20] & 0xFF);
        List<Object[]> master = new ArrayList<>();
        scan(1, master, 0);
        for (Object[] r : master) {
            if (!"table".equals(r[0]) || r[4] == null) continue;
            roots.put((String) r[1], ((Long) r[3]).intValue());
            columns.put((String) r[1], columnNames((String) r[4]));
            schema.add(new Table((String) r[1], (String) r[4]));
        }
    }

    /** Una tabla en memoria: columnas con su tipo declarado y filas (la clave INTEGER PRIMARY KEY lleva el rowid). */
    public static final class Table {
        public final String name, sql;
        public final String[] cols, types;
        public final int alias; // columna INTEGER PRIMARY KEY (= rowid) o -1
        public final List<Object[]> rows = new ArrayList<>();

        Table(String name, String sql) {
            this.name = name;
            this.sql = sql;
            String[] defs = sql.substring(sql.indexOf('(') + 1, sql.lastIndexOf(')')).split(",");
            cols = new String[defs.length];
            types = new String[defs.length];
            int a = -1;
            for (int i = 0; i < defs.length; i++) {
                String[] w = defs[i].trim().split("\\s+");
                cols[i] = w[0].replaceAll("[\"`\\[\\]]", "");
                types[i] = w.length > 1 ? w[1].toUpperCase(Locale.ROOT) : "";
                if (defs[i].toUpperCase(Locale.ROOT).contains("INTEGER PRIMARY KEY")) a = i;
            }
            alias = a;
        }

        public int col(String c) {
            for (int i = 0; i < cols.length; i++) if (cols[i].equals(c)) return i;
            throw new IllegalArgumentException("Falta la columna " + name + "." + c);
        }

        /** Asigna respetando el tipo declarado de la columna (REAL guarda Double, INTEGER guarda Long). */
        public void set(Object[] row, String c, Object v) {
            int i = col(c);
            if (v instanceof Number && types[i].startsWith("INT")) v = ((Number) v).longValue();
            else if (v instanceof Number && (types[i].equals("REAL") || types[i].startsWith("FLOA") || types[i].startsWith("DOUB"))) v = ((Number) v).doubleValue();
            row[i] = v;
        }

        public Object get(Object[] row, String c) {
            return row[col(c)];
        }
    }

    /** Todas las tablas con sus filas, en el orden del archivo (incluida sqlite_sequence). */
    public List<Table> tables() throws IOException {
        List<Table> out = new ArrayList<>();
        for (Table t : schema) {
            Table c = new Table(t.name, t.sql);
            c.rows.addAll(table(t.name));
            out.add(c);
        }
        return out;
    }

    /** Las filas de la tabla como arreglos en el orden de las columnas; la clave INTEGER PRIMARY KEY lleva el rowid. */
    private List<Object[]> table(String name) throws IOException {
        Integer root = roots.get(name);
        if (root == null) throw new IOException("Falta la tabla " + name);
        Table t = null;
        for (Table x : schema) if (x.name.equals(name)) t = x;
        List<Object[]> raw = new ArrayList<>();
        scan(root, raw, 0);
        List<Object[]> out = new ArrayList<>();
        for (Object[] r : raw) {
            Object[] row = new Object[t.cols.length];
            System.arraycopy(r, 0, row, 0, Math.min(row.length, r.length - 1)); // el último elemento es el rowid
            if (t.alias >= 0) row[t.alias] = r[r.length - 1];
            out.add(row);
        }
        return out;
    }

    /** Cabecera de 100 bytes del archivo. */
    public byte[] header() {
        return Arrays.copyOf(db, 100);
    }

    /** Todas las filas de la tabla, columna -> valor (Long, Double, String, byte[] o null). */
    public List<Map<String, Object>> rows(String table) throws IOException {
        String[] cols = columns.get(table);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : table(table)) {
            Map<String, Object> m = new HashMap<>();
            for (int i = 0; i < cols.length; i++) m.put(cols[i], r[i]);
            out.add(m);
        }
        return out;
    }

    private void scan(int page, List<Object[]> out, int depth) throws IOException {
        if (page < 1 || (long) page * pageSize > db.length || depth > 64) throw new IOException("SQLite dañado (página " + page + ").");
        int base = (page - 1) * pageSize;
        int h = base + (page == 1 ? 100 : 0);
        int type = db[h] & 0xFF;
        int n = u16(db, h + 3);
        int ptrs = h + (type == 5 ? 12 : 8);
        for (int i = 0; i < n; i++) {
            int cell = base + u16(db, ptrs + 2 * i);
            if (type == 5) {
                scan((int) u32(db, cell), out, depth + 1);
            } else if (type == 13) {
                int[] p = {cell};
                long size = varint(p);
                long rowid = varint(p);
                Object[] rec = record(payload(p[0], (int) size));
                Object[] withId = Arrays.copyOf(rec, rec.length + 1);
                withId[rec.length] = rowid;
                out.add(withId);
            } else {
                throw new IOException("SQLite: tipo de página inesperado " + type);
            }
        }
        if (type == 5) scan((int) u32(db, h + 8), out, depth + 1);
    }

    /** Contenido de la celda, siguiendo páginas de desbordamiento si no cabe en la hoja. */
    private byte[] payload(int at, int size) throws IOException {
        int maxLocal = usable - 35;
        int local = size;
        if (size > maxLocal) {
            int min = (usable - 12) * 32 / 255 - 23;
            int k = min + (size - min) % (usable - 4);
            local = k <= maxLocal ? k : min;
        }
        byte[] out = new byte[size];
        System.arraycopy(db, at, out, 0, local);
        int done = local;
        long next = local < size ? u32(db, at + local) : 0;
        while (done < size) {
            if (next < 1 || next * pageSize > db.length) throw new IOException("SQLite dañado (desbordamiento).");
            int off = (int) (next - 1) * pageSize;
            int len = Math.min(size - done, usable - 4);
            System.arraycopy(db, off + 4, out, done, len);
            done += len;
            next = u32(db, off);
        }
        return out;
    }

    private static Object[] record(byte[] rec) {
        int[] p = {0};
        int headerLen = (int) varint(rec, p);
        int body = headerLen;
        List<Object> vals = new ArrayList<>();
        while (p[0] < headerLen) {
            long t = varint(rec, p);
            int len;
            Object v;
            if (t == 0) { len = 0; v = null; }
            else if (t <= 6) { len = new int[]{0, 1, 2, 3, 4, 6, 8}[(int) t]; v = signed(rec, body, len); }
            else if (t == 7) { len = 8; v = Double.longBitsToDouble(signed(rec, body, 8)); }
            else if (t == 8 || t == 9) { len = 0; v = t - 8; }
            else if (t % 2 == 0) { len = (int) (t - 12) / 2; v = Arrays.copyOfRange(rec, body, body + len); }
            else { len = (int) (t - 13) / 2; v = new String(rec, body, len, StandardCharsets.UTF_8); }
            vals.add(v);
            body += len;
        }
        return vals.toArray();
    }

    private static String[] columnNames(String sql) {
        String defs = sql.substring(sql.indexOf('(') + 1, sql.lastIndexOf(')'));
        String[] parts = defs.split(",");
        String[] names = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            names[i] = parts[i].trim().split("\\s+")[0].replaceAll("[\"`\\[\\]]", "");
        }
        return names;
    }

    private long varint(int[] p) {
        return varint(db, p);
    }

    private static long varint(byte[] b, int[] p) {
        long v = 0;
        for (int i = 0; i < 9; i++) {
            int c = b[p[0]++] & 0xFF;
            if (i == 8) return (v << 8) | c;
            v = (v << 7) | (c & 0x7F);
            if (c < 0x80) return v;
        }
        return v;
    }

    private static long signed(byte[] b, int off, int len) {
        long v = len > 0 ? b[off] : 0; // el primer byte conserva el signo
        for (int i = 1; i < len; i++) v = (v << 8) | (b[off + i] & 0xFF);
        return v;
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long u32(byte[] b, int off) {
        return ((long) u16(b, off) << 16) | u16(b, off + 2);
    }
}
