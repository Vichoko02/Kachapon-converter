package com.kachapon.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Combina imágenes ARGB (sin premultiplicar) con un modo de fusión. Las fórmulas son las de Clip Studio medidas por
 * clipparse (docs/CLIP_FORMAT.md, sección 9); sirven también cuando la capa de abajo es transparente:
 *   ao    = as + ab * (1 - as)
 *   Co*ao = (1 - ab) * as * Cs + ab * as * B(Cb, Cs) + (1 - as) * ab * Cb
 * shortcut: no hay modos de "emisión" (10 y 12 de Clip Studio); se tratan como sus pares normales (9 y 11).
 */
public final class Composite {
    private static final Map<String, Integer> CLIP_MODE = new ConcurrentHashMap<>();

    private Composite() {}

    /** Pone src sobre dst, en su sitio, con el modo de Krita y la opacidad (0..255). */
    public static void blend(int[] dst, int[] src, String mode, int opacity) {
        // Blend.toClip avisa de los modos desconocidos: solo se pregunta una vez por nombre
        int clip = CLIP_MODE.computeIfAbsent(mode == null ? "normal" : mode, Blend::toClip);
        if (clip == 0 || clip == Blend.CLIP_PASS_THROUGH) {
            over(dst, src, opacity / 255f);
            return;
        }
        double op = opacity / 255.0;
        double[] b = new double[3], s = new double[3], g = new double[3];
        for (int i = 0; i < dst.length; i++) {
            int sp = src[i];
            double as = (sp >>> 24) / 255.0 * op;
            if (as <= 0) continue;
            int dp = dst[i];
            double ab = (dp >>> 24) / 255.0;
            channels(dp, b);
            channels(sp, s);
            double ao = as + ab * (1 - as);
            if (ab > 0) pixel(clip, b, s, g);
            int out = to8(ao * 255) << 24;
            for (int c = 0; c < 3; c++) {
                double num = (1 - ab) * as * s[c] + (ab > 0 ? ab * as * g[c] : 0) + (1 - as) * ab * b[c];
                out |= to8(num / ao * 255) << (16 - 8 * c);
            }
            dst[i] = out;
        }
    }

    /** "Normal" sin premultiplicar: dst = src * opacidad (0..1) sobre dst. */
    public static void over(int[] dst, int[] src, float opacity) {
        for (int i = 0; i < dst.length; i++) {
            int s = src[i];
            int sa = Math.round((s >>> 24) * opacity);
            if (sa <= 0) continue;
            int d = dst[i], da = d >>> 24;
            if (sa >= 255 || da == 0) {
                dst[i] = Math.min(sa, 255) << 24 | (s & 0xFFFFFF);
                continue;
            }
            int keep = da * (255 - sa) / 255, oa = sa + keep;
            int r = (((s >> 16) & 0xFF) * sa + ((d >> 16) & 0xFF) * keep) / oa;
            int g = (((s >> 8) & 0xFF) * sa + ((d >> 8) & 0xFF) * keep) / oa;
            int bl = ((s & 0xFF) * sa + (d & 0xFF) * keep) / oa;
            dst[i] = oa << 24 | r << 16 | g << 8 | bl;
        }
    }

    private static void channels(int argb, double[] c) {
        c[0] = ((argb >> 16) & 0xFF) / 255.0;
        c[1] = ((argb >> 8) & 0xFF) / 255.0;
        c[2] = (argb & 0xFF) / 255.0;
    }

    /** Redondeo al par, como las mediciones de clipparse, limitado a 0..255. */
    private static int to8(double v) {
        return v <= 0 ? 0 : v >= 255 ? 255 : (int) Math.rint(v);
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    /** B(Cb, Cs) de un píxel, con los números de modo de Clip Studio. */
    private static void pixel(int mode, double[] b, double[] s, double[] out) {
        switch (mode) {
            case 6: { // color más oscuro
                boolean src = luma(s) < luma(b);
                for (int i = 0; i < 3; i++) out[i] = src ? s[i] : b[i];
                return;
            }
            case 13: { // color más claro
                boolean src = luma(s) > luma(b);
                for (int i = 0; i < 3; i++) out[i] = src ? s[i] : b[i];
                return;
            }
            case 23: { // tono
                double[] t = s.clone();
                setSat(t, sat(b));
                setLuma(t, luma(b));
                for (int i = 0; i < 3; i++) out[i] = clamp(t[i]);
                return;
            }
            case 24: { // saturación
                double[] t = b.clone();
                setSat(t, sat(s));
                setLuma(t, luma(b));
                for (int i = 0; i < 3; i++) out[i] = clamp(t[i]);
                return;
            }
            case 25: { // color
                double[] t = s.clone();
                setLuma(t, luma(b));
                for (int i = 0; i < 3; i++) out[i] = clamp(t[i]);
                return;
            }
            case 26: { // luminosidad
                double[] t = b.clone();
                setLuma(t, luma(s));
                for (int i = 0; i < 3; i++) out[i] = clamp(t[i]);
                return;
            }
            default:
                for (int i = 0; i < 3; i++) out[i] = clamp(separable(mode, b[i], s[i]));
        }
    }

    private static double separable(int mode, double b, double s) {
        switch (mode) {
            case 1: return Math.min(b, s);
            case 2: return b * s;
            case 3: return burn(b, s);
            case 4: return b + s - 1;
            case 5: return b - s;
            case 7: return Math.max(b, s);
            case 8: return b + s - b * s;
            case 9: case 10: return dodge(b, s);
            case 11: case 12: return b + s;
            case 14: return hardLight(s, b); // sobreponer
            case 15: return softLight(b, s);
            case 16: return hardLight(b, s);
            case 17: return s <= 0.5 ? burn(b, 2 * s) : dodge(b, 2 * (s - 0.5));
            case 18: return b + 2 * s - 1;
            case 19: return s <= 0.5 ? Math.min(b, 2 * s) : Math.max(b, 2 * s - 1);
            case 20: return b + s >= 1 ? 1 : 0;
            case 21: return Math.abs(b - s);
            case 22: return b + s - 2 * b * s;
            case 36: return b / Math.max(s, 1e-9);
            default: return s;
        }
    }

    private static double hardLight(double b, double s) {
        return s <= 0.5 ? 2 * b * s : 1 - 2 * (1 - b) * (1 - s);
    }

    private static double softLight(double b, double s) {
        double d = b <= 0.25 ? ((16 * b - 12) * b + 4) * b : Math.sqrt(Math.max(b, 0));
        return s <= 0.5 ? b - (1 - 2 * s) * b * (1 - b) : b + (2 * s - 1) * (d - b);
    }

    private static double dodge(double b, double s) {
        return s >= 1 ? 1 : Math.min(1, b / Math.max(1 - s, 1e-9));
    }

    private static double burn(double b, double s) {
        return s <= 0 ? 0 : 1 - Math.min(1, (1 - b) / Math.max(s, 1e-9));
    }

    // Luminosidad con los pesos medidos en Clip Studio (no son los de Rec.709)
    private static double luma(double[] c) {
        return 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2];
    }

    private static double sat(double[] c) {
        return Math.max(c[0], Math.max(c[1], c[2])) - Math.min(c[0], Math.min(c[1], c[2]));
    }

    private static void setLuma(double[] c, double l) {
        double d = l - luma(c);
        for (int i = 0; i < 3; i++) c[i] += d;
        double lv = luma(c), lo = Math.min(c[0], Math.min(c[1], c[2])), hi = Math.max(c[0], Math.max(c[1], c[2]));
        if (lo < 0) for (int i = 0; i < 3; i++) c[i] = lv + (c[i] - lv) * lv / Math.max(lv - lo, 1e-9);
        if (hi > 1) for (int i = 0; i < 3; i++) c[i] = lv + (c[i] - lv) * (1 - lv) / Math.max(hi - lv, 1e-9);
    }

    private static void setSat(double[] c, double s) {
        double mn = Math.min(c[0], Math.min(c[1], c[2])), mx = Math.max(c[0], Math.max(c[1], c[2]));
        if (mx > mn) for (int i = 0; i < 3; i++) c[i] = (c[i] - mn) * s / (mx - mn);
        else c[0] = c[1] = c[2] = 0;
    }
}
