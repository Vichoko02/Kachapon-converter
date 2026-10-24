package com.kachapon.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Descripción de la secuencia en el puente: se guarda tal cual como manifest.json.
 * Cada pista es una capa con su propia línea de tiempo (sus dibujos y holds); se combinan de abajo arriba en el orden
 * de tracks. layers describe el árbol de capas y carpetas del documento original; sin él, las pistas son capas
 * normales sueltas.
 */
public class Manifest {
    public String resolution; // "ANCHOxALTO"
    public int fps;
    public int duration;
    public List<String> tracks = new ArrayList<>();
    public List<FrameRef> frames = new ArrayList<>();
    /** Árbol de capas y carpetas; entre hermanos va de abajo arriba. Opcional. */
    public List<Layer> layers = new ArrayList<>();

    public Manifest() {}

    public Manifest(String resolution, int fps, int duration) {
        this.resolution = resolution;
        this.fps = fps;
        this.duration = duration;
    }

    public int width() { return dim(0); }

    public int height() { return dim(1); }

    private int dim(int i) {
        try {
            return Integer.parseInt(resolution.split("x")[i]);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Resolución inválida: " + resolution);
        }
    }

    /** La capa de una pista, o null si el documento no la describe. */
    public Layer layer(String track) {
        if (layers != null) for (Layer l : layers) if (track.equals(l.track)) return l;
        return null;
    }

    /**
     * layers con una capa normal suelta por cada pista que el árbol no describe. Si faltan pistas, las capas van
     * en el orden de tracks (abajo arriba) y las carpetas encima.
     */
    public List<Layer> allLayers() {
        List<Layer> out = new ArrayList<>();
        boolean complete = true;
        for (String t : tracks) if (layer(t) == null) complete = false;
        if (complete) return new ArrayList<>(layers);
        for (String t : tracks) out.add(layer(t) != null ? layer(t) : new Layer(t, t, true));
        if (layers != null) for (Layer l : layers) if (l.folder()) out.add(l);
        return out;
    }

    /** Si la pista se ve: su propia visibilidad y la de todas sus carpetas. */
    public boolean visible(String track) {
        for (Layer l = layer(track); l != null; l = parent(l)) if (!l.visible) return false;
        return true;
    }

    public String name(String track) {
        Layer l = layer(track);
        return l == null || l.name == null || l.name.isEmpty() ? track : l.name;
    }

    public Layer parent(Layer l) {
        if (l.parent == null || layers == null) return null;
        for (Layer p : layers) if (l.parent.equals(p.id)) return p;
        return null;
    }

    /**
     * Una capa de pintura (track != null) o una carpeta (track == null).
     * blend usa los nombres de Krita ("normal", "multiply", "overlay", ...); opacity va de 0 a 255.
     */
    public static class Layer {
        public String id;
        public String track;
        public String name;
        public String parent;
        public boolean visible = true;
        public int opacity = 255;
        public String blend = "normal";
        /** Solo carpetas: sus hijos se mezclan con lo que hay debajo de la carpeta, no entre ellos primero. */
        public boolean passthrough;

        public Layer() {}

        public Layer(String track, String name, boolean visible) {
            this.id = track;
            this.track = track;
            this.name = name;
            this.visible = visible;
        }

        public boolean folder() { return track == null; }
    }
}
