package com.kachapon.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Modos de fusión: el puente usa los nombres de Krita; Clip Studio los guarda como número en Layer.LayerComposite.
 * La tabla de Clip Studio sale de las mediciones de clipparse (docs/CLIP_FORMAT.md, sección 9).
 */
public final class Blend {
    /** Número de Clip Studio para las carpetas "pasar a través" (solo carpetas). */
    public static final int CLIP_PASS_THROUGH = 30;

    private static final Map<String, Integer> TO_CLIP = new HashMap<>();
    private static final Map<Integer, String> FROM_CLIP = new HashMap<>();

    private static void both(String krita, int clip) {
        TO_CLIP.put(krita, clip);
        FROM_CLIP.putIfAbsent(clip, krita);
    }

    static {
        both("normal", 0);
        both("darken", 1);
        both("multiply", 2);
        both("burn", 3);
        both("linear_burn", 4);
        both("subtract", 5);
        both("darker color", 6);
        both("lighten", 7);
        both("screen", 8);
        both("dodge", 9);
        both("add", 11);
        both("lighter color", 13);
        both("overlay", 14);
        both("soft_light_svg", 15);
        both("hard_light", 16);
        both("vivid_light", 17);
        both("linear_light", 18);
        both("pin_light", 19);
        both("hard_mix_photoshop", 20);
        both("diff", 21);
        both("exclusion", 22);
        both("hue", 23);
        both("saturation", 24);
        both("color", 25);
        both("luminize", 26);
        both("divide", 36);
        // Variantes de Krita con el mismo resultado en Clip Studio
        TO_CLIP.put("soft_light", 15);
        TO_CLIP.put("soft_light_ps", 15);
        TO_CLIP.put("hard_mix", 20);
        TO_CLIP.put("hard_mix_softer_photoshop", 20);
        TO_CLIP.put("hue_hsv", 23);
        TO_CLIP.put("saturation_hsv", 24);
        TO_CLIP.put("color_hsv", 25);
        // Los de "emisión" de Clip Studio (10 y 12) solo cambian cómo entra el alfa: vuelven como sus pares normales
        FROM_CLIP.put(10, "dodge");
        FROM_CLIP.put(12, "add");
    }

    private Blend() {}

    /** Modo de Clip Studio para un modo de Krita; los que no existen allí quedan como "normal" y se avisa. */
    public static int toClip(String krita) {
        Integer v = krita == null ? Integer.valueOf(0) : TO_CLIP.get(krita);
        if (v == null) {
            System.err.println("Advertencia: el modo de fusión \"" + krita + "\" no existe en Clip Studio; se usa normal.");
            return 0;
        }
        return v;
    }

    /** Modo de Krita para uno de Clip Studio; los desconocidos quedan como "normal" y se avisa. */
    public static String fromClip(int clip) {
        String v = FROM_CLIP.get(clip);
        if (v == null) {
            System.err.println("Advertencia: el modo de fusión " + clip + " de Clip Studio no tiene equivalente; se usa normal.");
            return "normal";
        }
        return v;
    }
}
