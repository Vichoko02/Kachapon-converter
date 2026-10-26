package com.kachapon.android;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.kachapon.core.ConversionResult;
import com.kachapon.core.orchestrator.Orchestrator;

import java.io.*;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// core trabaja con rutas: el archivo elegido se copia a la caché, se convierte allí y el resultado se copia al destino elegido.
public class MainActivity extends AppCompatActivity {
    private static final String[] FORMATS = {".kra", ".clip", ".ipv"};

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private File input;
    private String inputBase;

    private Button pickBtn, convertBtn;
    private TextView fileText, statusText;
    private RadioGroup formatGroup;

    private final ActivityResultLauncher<String[]> pickInput =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onInputPicked);
    private final ActivityResultLauncher<String> pickOutput =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"), this::onOutputPicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        pickBtn = findViewById(R.id.pick_btn);
        convertBtn = findViewById(R.id.convert_btn);
        fileText = findViewById(R.id.file_text);
        statusText = findViewById(R.id.status_text);
        formatGroup = findViewById(R.id.format_group);

        pickBtn.setOnClickListener(v -> pickInput.launch(new String[]{"*/*"}));
        convertBtn.setOnClickListener(v -> pickOutput.launch(inputBase + selectedExt()));
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    private void onInputPicked(Uri uri) {
        if (uri == null) return;
        String name = displayName(uri);
        String ext = ext(name);
        if (!Arrays.asList(FORMATS).contains(ext)) {
            statusText.setText("Formato no soportado: " + name);
            return;
        }
        setBusy(true, "Leyendo " + name + "…");
        worker.execute(() -> {
            try {
                File dir = new File(getCacheDir(), "input");
                dir.mkdirs();
                File copy = new File(dir, "origen" + ext);
                try (InputStream in = open(uri)) {
                    copy(in, new FileOutputStream(copy));
                }
                runOnUiThread(() -> {
                    input = copy;
                    inputBase = name.substring(0, name.length() - ext.length());
                    fileText.setText(name);
                    onlyOtherFormats(ext);
                    setBusy(false, "Origen detectado: " + ext.toUpperCase(Locale.ROOT));
                });
            } catch (IOException e) {
                runOnUiThread(() -> setBusy(false, "No se pudo leer el archivo: " + e.getMessage()));
            }
        });
    }

    private void onOutputPicked(Uri uri) {
        if (uri == null) return;
        String dstExt = selectedExt();
        setBusy(true, "Convirtiendo…");
        worker.execute(() -> {
            String msg = convert(uri, dstExt);
            runOnUiThread(() -> setBusy(false, msg));
        });
    }

    private String convert(Uri uri, String dstExt) {
        File out = new File(getCacheDir(), "output/resultado" + dstExt);
        // Puente propio en la caché (java.io.tmpdir en Android) que se borra al terminar: los PNG intermedios ocupan cientos de MB
        ConversionResult r = new Orchestrator().run(input.getPath(), out.getPath());
        String msg = "Error: " + r.error;
        boolean saved = false;
        if (r.success) {
            try {
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) throw new IOException("destino no disponible");
                copy(new FileInputStream(out), os);
                saved = true;
                msg = "Archivo guardado ✓" + (r.warnings.isEmpty() ? "" : "\nAvisos:\n• " + android.text.TextUtils.join("\n• ", r.warnings));
            } catch (IOException e) {
                msg = "No se pudo guardar: " + e.getMessage();
            }
        }
        out.delete();
        if (!saved) deleteQuietly(uri);
        return msg;
    }

    /** Desactiva el formato del origen como destino y, si estaba elegido, elige otro. */
    private void onlyOtherFormats(String srcExt) {
        for (int i = 0; i < formatGroup.getChildCount(); i++) {
            RadioButton b = (RadioButton) formatGroup.getChildAt(i);
            b.setEnabled(!srcExt.equals(b.getTag()));
        }
        if (srcExt.equals(selectedExt())) {
            for (int i = 0; i < formatGroup.getChildCount(); i++) {
                View b = formatGroup.getChildAt(i);
                if (b.isEnabled()) {
                    formatGroup.check(b.getId());
                    break;
                }
            }
        }
    }

    private void setBusy(boolean busy, String status) {
        pickBtn.setEnabled(!busy);
        convertBtn.setEnabled(!busy && input != null);
        statusText.setText(status);
    }

    private String selectedExt() {
        return (String) findViewById(formatGroup.getCheckedRadioButtonId()).getTag();
    }

    private InputStream open(Uri uri) throws IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("origen no disponible");
        return in;
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        }
        return uri.getLastPathSegment();
    }

    /** Si no se pudo guardar, borra el archivo vacío que creó el selector de destino. */
    private void deleteQuietly(Uri uri) {
        try {
            DocumentsContract.deleteDocument(getContentResolver(), uri);
        } catch (Exception ignored) {
            // No todos los proveedores permiten borrar; el archivo vacío queda
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        try (InputStream i = in; OutputStream o = out) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = i.read(buf)) != -1) o.write(buf, 0, n);
        }
    }

    private static String ext(String name) {
        int i = name == null ? -1 : name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i).toLowerCase(Locale.ROOT);
    }
}
