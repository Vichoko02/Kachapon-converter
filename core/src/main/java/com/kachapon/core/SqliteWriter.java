package com.kachapon.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Escribe una base SQLite 3 de páginas de 4096 bytes a partir de tablas en memoria (el recíproco de Sqlite).
 * shortcut: solo tablas (sin índices, triggers ni vistas); basta para el .clip, que SQLite y Clip Studio abren igual.
 */
public final class SqliteWriter {
    private static final int PAGE = 4096;
    private final List<byte[]> pages = new ArrayList<>();

    private static final class Entry {
        final long rowid;
        final byte[] payload;

        Entry(long rowid, byte[] payload) {
            this.rowid = rowid;
            this.payload = payload;
        }
    }

    private SqliteWriter() {}

    /** header: cabecera de 100 bytes de la base original (de ella salen el esquema y las opciones). */
    public static byte[] write(byte[] header, List<Sqlite.Table> tables) throws IOException {
        if (header[16] != 0x10 || header[17] != 0) throw new IOException("Solo se escriben bases de 4096 bytes por página.");
        SqliteWriter w = new SqliteWriter();
        w.pages.add(new byte[PAGE]); // la página 1 es la raíz de sqlite_master y se escribe al final
        List<Entry> master = new ArrayList<>();
        long id = 1;
        for (Sqlite.Table t : tables) {
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < t.rows.size(); i++) {
                Object[] row = t.rows.get(i).clone();
                long rowid = i + 1;
                if (t.alias >= 0) {
                    rowid = ((Number) row[t.alias]).longValue();
                    row[t.alias] = null; // la clave INTEGER PRIMARY KEY se guarda como el rowid
                }
                entries.add(new Entry(rowid, record(row)));
            }
            entries.sort(Comparator.comparingLong(e -> e.rowid));
            master.add(new Entry(id++, record("table", t.name, t.name, (long) w.tree(entries, 0), t.sql)));
        }
        w.tree(master, 1);

        ByteBuffer h = ByteBuffer.wrap(w.pages.get(0));
        h.put(header, 0, 100);
        int counter = ByteBuffer.wrap(header).getInt(24) + 1;
        h.putInt(24, counter).putInt(28, w.pages.size()).putInt(32, 0).putInt(36, 0).putInt(92, counter);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : w.pages) out.write(p);
        return out.toByteArray();
    }

    private int alloc() {
        pages.add(new byte[PAGE]);
        return pages.size();
    }

    /** Árbol B de una tabla; devuelve la página raíz (la indicada en 'root' si no es 0). */
    private int tree(List<Entry> entries, int root) {
        int hdr = root == 1 ? 100 : 0;
        List<byte[]> cells = new ArrayList<>();
        for (Entry e : entries) cells.add(leafCell(e));
        List<List<Integer>> groups = new ArrayList<>(); // índices de celdas por página
        List<Integer> cur = new ArrayList<>();
        int used = 0;
        for (int i = 0; i < cells.size(); i++) {
            int cost = cells.get(i).length + 2;
            if (!cur.isEmpty() && used + cost > PAGE - hdr - 8) {
                groups.add(cur);
                cur = new ArrayList<>();
                used = 0;
            }
            cur.add(i);
            used += cost;
        }
        groups.add(cur); // una tabla vacía es una hoja sin celdas

        List<long[]> level = new ArrayList<>(); // {página, mayor rowid}
        for (List<Integer> g : groups) {
            boolean top = groups.size() == 1 && root > 0; // solo la raíz de sqlite_master lleva la cabecera de 100 bytes
            int page = top ? root : alloc();
            List<byte[]> gc = new ArrayList<>();
            for (int i : g) gc.add(cells.get(i));
            fill(page, top ? hdr : 0, (byte) 13, gc, 0);
            level.add(new long[]{page, g.isEmpty() ? 0 : entries.get(g.get(g.size() - 1)).rowid});
        }
        while (level.size() > 1) {
            List<List<long[]>> parents = new ArrayList<>();
            List<long[]> group = new ArrayList<>();
            int sum = 0;
            for (long[] child : level) {
                int cost = group.isEmpty() ? 0 : 4 + varint(group.get(group.size() - 1)[1]).length + 2;
                if (!group.isEmpty() && sum + cost > PAGE - hdr - 12) {
                    parents.add(group);
                    group = new ArrayList<>();
                    sum = 0;
                    cost = 0;
                }
                group.add(child);
                sum += cost;
            }
            if (group.size() == 1 && !parents.isEmpty()) { // un padre con un solo hijo no es válido: toma uno del anterior
                List<long[]> prev = parents.get(parents.size() - 1);
                group.add(0, prev.remove(prev.size() - 1));
            }
            parents.add(group);
            List<long[]> next = new ArrayList<>();
            for (List<long[]> g : parents) {
                boolean top = parents.size() == 1 && root > 0;
                int page = top ? root : alloc();
                List<byte[]> pc = new ArrayList<>();
                for (int i = 0; i < g.size() - 1; i++) {
                    ByteArrayOutputStream c = new ByteArrayOutputStream();
                    c.write(ByteBuffer.allocate(4).putInt((int) g.get(i)[0]).array(), 0, 4);
                    byte[] key = varint(g.get(i)[1]);
                    c.write(key, 0, key.length);
                    pc.add(c.toByteArray());
                }
                fill(page, top ? hdr : 0, (byte) 5, pc, (int) g.get(g.size() - 1)[0]);
                next.add(new long[]{page, g.get(g.size() - 1)[1]});
            }
            level = next;
        }
        return (int) level.get(0)[0];
    }

    /** Escribe una página: cabecera, arreglo de punteros y las celdas pegadas al final. */
    private void fill(int page, int hdr, byte type, List<byte[]> cells, int rightmost) {
        ByteBuffer b = ByteBuffer.wrap(pages.get(page - 1));
        int head = type == 5 ? 12 : 8;
        int end = PAGE;
        for (int i = 0; i < cells.size(); i++) {
            end -= cells.get(i).length;
            b.position(end);
            b.put(cells.get(i));
            b.putShort(hdr + head + 2 * i, (short) end);
        }
        b.put(hdr, type);
        b.putShort(hdr + 3, (short) cells.size());
        b.putShort(hdr + 5, (short) end);
        if (type == 5) b.putInt(hdr + 8, rightmost);
    }

    /** Celda de hoja: largo, rowid, parte local del contenido y, si no cabe, la primera página de desbordamiento. */
    private byte[] leafCell(Entry e) {
        byte[] p = e.payload;
        int maxLocal = PAGE - 35, local = p.length;
        if (p.length > maxLocal) {
            int min = (PAGE - 12) * 32 / 255 - 23;
            int k = min + (p.length - min) % (PAGE - 4);
            local = k <= maxLocal ? k : min;
        }
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        byte[] size = varint(p.length), id = varint(e.rowid);
        c.write(size, 0, size.length);
        c.write(id, 0, id.length);
        c.write(p, 0, local);
        if (local < p.length) {
            int chunks = (p.length - local + PAGE - 5) / (PAGE - 4), first = pages.size() + 1;
            for (int i = 0; i < chunks; i++) {
                int page = alloc(), from = local + i * (PAGE - 4), n = Math.min(PAGE - 4, p.length - from);
                ByteBuffer b = ByteBuffer.wrap(pages.get(page - 1));
                b.putInt(i + 1 < chunks ? page + 1 : 0);
                b.put(p, from, n);
            }
            c.write(ByteBuffer.allocate(4).putInt(first).array(), 0, 4);
        }
        return c.toByteArray();
    }

    /** Un registro: cabecera de tipos y los valores (null, Long, Double, String o byte[]). */
    static byte[] record(Object... values) {
        ByteArrayOutputStream types = new ByteArrayOutputStream(), body = new ByteArrayOutputStream();
        for (Object v : values) {
            byte[] t;
            if (v == null) {
                t = varint(0);
            } else if (v instanceof Number && !(v instanceof Double) && !(v instanceof Float)) {
                long n = ((Number) v).longValue();
                int len = n == 0 || n == 1 ? 0 : n >= -128 && n < 128 ? 1 : n >= -32768 && n < 32768 ? 2 : n >= -(1 << 23) && n < 1 << 23 ? 3
                        : n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE ? 4 : n >= -(1L << 47) && n < 1L << 47 ? 6 : 8;
                t = varint(len == 0 ? 8 + n : len == 6 ? 5 : len == 8 ? 6 : len);
                for (int i = len - 1; i >= 0; i--) body.write((int) (n >> (8 * i)));
            } else if (v instanceof Number) {
                t = varint(7);
                byte[] d = ByteBuffer.allocate(8).putDouble(((Number) v).doubleValue()).array();
                body.write(d, 0, 8);
            } else if (v instanceof String) {
                byte[] s = ((String) v).getBytes(StandardCharsets.UTF_8);
                t = varint(13 + 2L * s.length);
                body.write(s, 0, s.length);
            } else {
                byte[] s = (byte[]) v;
                t = varint(12 + 2L * s.length);
                body.write(s, 0, s.length);
            }
            types.write(t, 0, t.length);
        }
        int header = types.size() + 1;
        if (header > 127) header = types.size() + 2; // el propio largo ocupa 2 bytes
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] hv = varint(header);
        out.write(hv, 0, hv.length);
        out.write(types.toByteArray(), 0, types.size());
        out.write(body.toByteArray(), 0, body.size());
        return out.toByteArray();
    }

    static byte[] varint(long v) {
        if ((v >>> 56) != 0) { // 9 bytes: los 8 primeros llevan 7 bits y el último 8
            byte[] b = new byte[9];
            b[8] = (byte) v;
            v >>>= 8;
            for (int i = 7; i >= 0; i--) {
                b[i] = (byte) ((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            return b;
        }
        byte[] tmp = new byte[9];
        int n = 0;
        do {
            tmp[n++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        } while (v != 0);
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = tmp[n - 1 - i];
        out[n - 1] &= 0x7F;
        return out;
    }
}
