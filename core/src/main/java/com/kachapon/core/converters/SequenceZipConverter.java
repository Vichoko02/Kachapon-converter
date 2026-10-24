package com.kachapon.core.converters;

import com.kachapon.core.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.*;

/** .clip y .ipv: un ZIP con el mismo contenido que el puente (manifest.json + frames/ + timesheet.xdts). */
public class SequenceZipConverter extends Converter {
    private final String ext;
    private final String app;

    public SequenceZipConverter(String ext, String app, Bridge bridge) {
        super(bridge);
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

    @Override
    public void build(Manifest m, Bridge bridge, File output) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(output))) {
            put(zos, "manifest.json", Bridge.GSON.toJson(m).getBytes(StandardCharsets.UTF_8));
            for (FrameRef fr : m.frames) put(zos, fr.path, bridge.readFrame(fr));
            put(zos, "timesheet.xdts", Bridge.timesheet(m).getBytes(StandardCharsets.UTF_8));
        }
    }
}
