package com.kachapon.core.converters;

import com.kachapon.core.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.*;

/** Base de .clip y .ipv: lee el ZIP con el contenido del puente (manifest.json + frames/) que escribían las primeras versiones del programa. */
public abstract class SequenceZipConverter extends Converter {
    private final String ext;
    private final String app;

    protected SequenceZipConverter(String ext, String app) {
        this.ext = ext;
        this.app = app;
    }

    @Override
    public Manifest extract(String inputPath, Bridge bridge) throws Exception {
        try (ZipFile zf = openZip(inputPath, "El archivo " + ext + " no es un puente KachaponConverter (ZIP). "
                + "Leer un " + ext + " nativo requiere " + app + ".")) {
            ZipEntry entry = zf.stream().filter(e -> e.getName().endsWith("manifest.json")).findFirst()
                    .orElseThrow(() -> new Exception("El archivo " + ext + " no contiene manifest.json del puente."));
            Manifest m;
            try (Reader r = new InputStreamReader(zf.getInputStream(entry), StandardCharsets.UTF_8)) {
                m = Bridge.GSON.fromJson(r, Manifest.class);
            }
            if (m == null || m.frames == null || m.tracks == null) throw new Exception("manifest.json incompleto.");
            m.width(); // valida la resolución antes de copiar nada

            List<FrameRef> frames = new ArrayList<>();
            for (FrameRef fr : m.frames) {
                frames.add(new FrameRef(fr.track, fr.frame, fr.hold, bridge.writeFrame(fr.track, fr.frame, readPng(zf, fr.path))));
            }
            m.frames = frames;
            return m;
        }
    }
}
