package com.kachapon.core;

public class ConversionResult {
    public final boolean success;
    public final String outputPath;
    public final String error;

    public ConversionResult(boolean success, String outputPath, String error) {
        this.success = success;
        this.outputPath = outputPath;
        this.error = error;
    }
}
