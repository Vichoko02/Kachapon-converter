package com.kachapon.core;

/** Un dibujo de la secuencia: pista (capa animada), frame donde empieza, cuántos frames dura y su PNG en el puente. */
public class FrameRef {
    public final String track;
    public final int frame;
    public final int hold;
    public final String path;

    public FrameRef(String track, int frame, int hold, String path) {
        this.track = track;
        this.frame = frame;
        this.hold = hold;
        this.path = path;
    }
}
