package com.kachapon.core;

import java.util.Collections;
import java.util.List;

public class ConversionResult {
    public final boolean success;
    public final String outputPath;
    public final String error;
    /** Avisos para la persona (capas omitidas, modos de fusión sin equivalente); vacío si no hubo. */
    public final List<String> warnings;

    public ConversionResult(boolean success, String outputPath, String error) {
        this(success, outputPath, error, Collections.emptyList());
    }

    public ConversionResult(boolean success, String outputPath, String error, List<String> warnings) {
        this.success = success;
        this.outputPath = outputPath;
        this.error = error;
        this.warnings = warnings;
    }
}
