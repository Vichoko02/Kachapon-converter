package com.kachapon.desktop;

import com.kachapon.core.orchestrator.Orchestrator;
import com.kachapon.core.ConversionResult;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainApp extends Application {

    /** Conversiones que corren a la vez; las demás esperan en cola. Krita se serializa por dentro, el resto va en paralelo. */
    private static final int MAX_AT_ONCE = 3;

    private TextField inputField;
    private Label dropTitle;
    private TextField outputDirField;
    private Label statusLabel;
    private Button convertBtn;
    private CheckBox legacyCheck;
    private VBox jobsBox;
    private final ExecutorService pool = Executors.newFixedThreadPool(MAX_AT_ONCE, r -> {
        Thread t = new Thread(r, "conversion");
        t.setDaemon(true); // cerrar la ventana no debe dejar el programa colgado esperando conversiones
        return t;
    });
    private final java.util.Set<String> activeOutputs = new java.util.HashSet<>(); // solo desde el hilo de JavaFX
    private final java.util.Map<String, CheckBox> srcChecks = new java.util.HashMap<>();
    private final java.util.Map<String, CheckBox> dstChecks = new java.util.HashMap<>();

    private static final String[][] FORMATS = {
            {".kra", "Krita (.kra)"},
            {".clip", "Clip Studio (.clip)"},
            {".ipv", "ibisPaint (.ipv)"},
            {".png", "Secuencia PNG"},
    };

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("KachaponConverter — Conversión de Animación Indie");

        VBox root = new VBox(14);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: #f5f7fa;");

        // Título
        Label title = new Label("KachaponConverter");
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 24));
        title.setTextFill(Color.web("#1f2937"));
        Label subtitle = new Label("Convierte entre Krita, Clip Studio e ibisPaint");
        subtitle.setFont(Font.font("Segoe UI", 13));
        subtitle.setTextFill(Color.web("#6b7280"));

        // Origen
        VBox srcBox = new VBox(8);
        srcBox.setPadding(new Insets(14));
        srcBox.setStyle("-fx-background-color: white; -fx-background-radius: 10; -fx-border-color: #e5e7eb; -fx-border-radius: 10;");
        Label srcTitle = new Label("Archivo origen (arrastra aquí o examinar)");
        srcTitle.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 13));
        srcTitle.setTextFill(Color.web("#374151"));

        // Zona de arrastrar y soltar (también clicable para examinar)
        SVGPath dropIcon = new SVGPath();
        dropIcon.setContent("M12 3l-6 6h4v6h4V9h4zM4 17v4h16v-4h-2v2H6v-2z");
        dropIcon.getStyleClass().add("drop-icon");
        dropIcon.setScaleX(1.6);
        dropIcon.setScaleY(1.6);
        dropTitle = new Label("Arrastra tu archivo aquí");
        dropTitle.getStyleClass().add("drop-title");
        Label dropSub = new Label("o haz clic para examinar  ·  .kra  .clip  .ipv  ·  o un PNG de una secuencia");
        dropSub.getStyleClass().add("drop-sub");

        VBox dropZone = new VBox(10, dropIcon, dropTitle, dropSub);
        dropZone.setAlignment(Pos.CENTER);
        dropZone.setMinHeight(150);
        dropZone.getStyleClass().add("drop-zone");
        dropZone.setOnMouseClicked(e -> chooseInput());
        dropZone.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) event.acceptTransferModes(TransferMode.COPY);
            event.consume();
        });
        dropZone.setOnDragEntered(event -> {
            if (event.getDragboard().hasFiles()) dropZone.getStyleClass().add("drag-over");
            event.consume();
        });
        dropZone.setOnDragExited(event -> {
            dropZone.getStyleClass().remove("drag-over");
            event.consume();
        });
        dropZone.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean ok = db.hasFiles() && loadInput(db.getFiles().get(0));
            event.setDropCompleted(ok);
            event.consume();
        });

        inputField = new TextField();
        inputField.setPromptText("Ningún archivo seleccionado");
        inputField.setStyle("-fx-background-color: #f9fafb; -fx-border-color: #d1d5db; -fx-border-radius: 6; -fx-padding: 8 10;");

        srcBox.getChildren().addAll(srcTitle, dropZone, inputField);

        // Checks origen (informativos, autodetectados)
        VBox srcChecksBox = new VBox(6);
        srcChecksBox.setPadding(new Insets(10, 0, 0, 0));
        Label srcCheckLabel = new Label("Origen detectado:");
        srcCheckLabel.setFont(Font.font("Segoe UI", 11));
        srcCheckLabel.setTextFill(Color.web("#9ca3af"));
        srcChecksBox.getChildren().add(srcCheckLabel);
        HBox srcCheckRow = new HBox(12);
        for (String[] f : FORMATS) {
            CheckBox c = new CheckBox(f[1]);
            c.setDisable(true);
            c.setStyle("-fx-text-fill: #6b7280;");
            srcChecks.put(f[0], c);
            srcCheckRow.getChildren().add(c);
        }
        srcChecksBox.getChildren().add(srcCheckRow);

        // Carpeta de salida
        VBox dirBox = new VBox(8);
        dirBox.setPadding(new Insets(14));
        dirBox.setStyle("-fx-background-color: white; -fx-background-radius: 10; -fx-border-color: #e5e7eb; -fx-border-radius: 10;");
        Label dirTitle = new Label("Carpeta donde se crearán los archivos");
        dirTitle.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 13));
        dirTitle.setTextFill(Color.web("#374151"));

        HBox dirRow = new HBox(10);
        dirRow.setAlignment(Pos.CENTER_LEFT);
        outputDirField = new TextField();
        outputDirField.setPromptText("Seleccionar carpeta de salida");
        outputDirField.setPrefWidth(340);
        outputDirField.setStyle("-fx-background-color: #f9fafb; -fx-border-color: #d1d5db; -fx-border-radius: 6; -fx-padding: 8 10;");
        Button btnDir = new Button("Examinar...");
        styleButton(btnDir, false);
        btnDir.setOnAction(e -> chooseOutputDir());
        dirRow.getChildren().addAll(outputDirField, btnDir);
        dirBox.getChildren().addAll(dirTitle, dirRow);

        // Checks destino
        VBox dstChecksBox = new VBox(8);
        dstChecksBox.setPadding(new Insets(14));
        dstChecksBox.setStyle("-fx-background-color: white; -fx-background-radius: 10; -fx-border-color: #e5e7eb; -fx-border-radius: 10;");
        Label dstTitle = new Label("Formato destino (se detecta solo al elegir archivo)");
        dstTitle.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 13));
        dstTitle.setTextFill(Color.web("#374151"));

        HBox dstCheckRow = new HBox(12);
        for (String[] f : FORMATS) {
            CheckBox c = new CheckBox(f[1]);
            c.setStyle("-fx-text-fill: #374151;");
            dstChecks.put(f[0], c);
            dstCheckRow.getChildren().add(c);
        }
        // Solo cuenta si el destino es .ipv; se decide al pulsar Convertir, así cada conversión puede llevar el suyo
        legacyCheck = new CheckBox("ibisPaint: formato compatible con versiones antiguas (legacy)");
        legacyCheck.setSelected(Boolean.getBoolean("kachapon.ibis.legacy"));
        legacyCheck.setStyle("-fx-text-fill: #374151;");
        dstChecksBox.getChildren().addAll(dstTitle, dstCheckRow, legacyCheck);

        // Botón convertir
        convertBtn = new Button("Convertir archivo");
        styleButton(convertBtn, true);
        convertBtn.setPrefWidth(220);
        convertBtn.setOnAction(e -> convert());

        // Estado
        statusLabel = new Label("Listo");
        statusLabel.setFont(Font.font("Segoe UI", 12));
        statusLabel.setTextFill(Color.web("#6b7280"));
        statusLabel.setPadding(new Insets(8, 0, 0, 0));

        // Conversiones en curso y terminadas, la más reciente arriba
        Label jobsTitle = new Label("Conversiones");
        jobsTitle.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 13));
        jobsTitle.setTextFill(Color.web("#374151"));
        jobsBox = new VBox(8);

        root.getChildren().addAll(title, subtitle, srcBox, srcChecksBox, dirBox, dstChecksBox, convertBtn, statusLabel, jobsTitle, jobsBox);

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: #f5f7fa; -fx-background-color: #f5f7fa;");
        Scene scene = new Scene(scroll, 660, 820);
        scene.getStylesheets().add(getClass().getResource("app.css").toExternalForm());
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void styleButton(Button b, boolean primary) {
        b.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 13));
        b.setPrefHeight(40);
        if (primary) {
            b.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-background-radius: 8; -fx-cursor: hand;");
            b.setOnMouseEntered(e -> b.setStyle("-fx-background-color: #1d4ed8; -fx-text-fill: white; -fx-background-radius: 8; -fx-cursor: hand;"));
            b.setOnMouseExited(e -> b.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-background-radius: 8; -fx-cursor: hand;"));
        } else {
            b.setStyle("-fx-background-color: #f3f4f6; -fx-text-fill: #374151; -fx-background-radius: 6; -fx-border-color: #d1d5db; -fx-border-radius: 6; -fx-cursor: hand;");
            b.setOnMouseEntered(e -> b.setStyle("-fx-background-color: #e5e7eb; -fx-text-fill: #111827; -fx-background-radius: 6; -fx-border-color: #9ca3af; -fx-cursor: hand;"));
            b.setOnMouseExited(e -> b.setStyle("-fx-background-color: #f3f4f6; -fx-text-fill: #374151; -fx-background-radius: 6; -fx-border-color: #d1d5db; -fx-border-radius: 6; -fx-cursor: hand;"));
        }
    }

    private void chooseInput() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Seleccionar archivo origen");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Animación o secuencia PNG", "*.ipv", "*.clip", "*.kra", "*.png"));
        File f = fc.showOpenDialog(null);
        if (f != null) loadInput(f);
    }

    private boolean loadInput(File f) {
        String ext = getExt(f.getName()).toLowerCase();
        if (!srcChecks.containsKey(ext)) {
            statusLabel.setText("Formato no soportado: " + f.getName());
            return false;
        }
        inputField.setText(f.getAbsolutePath());
        dropTitle.setText(f.getName());
        markSingle(srcChecks, ext);
        clear(dstChecks);
        statusLabel.setText("Origen detectado: " + ext.toUpperCase());
        return true;
    }

    private void chooseOutputDir() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Seleccionar carpeta de salida");
        File d = dc.showDialog(null);
        if (d == null) return;
        outputDirField.setText(d.getAbsolutePath());
    }

    private String resolveOutput() {
        String dir = outputDirField.getText().trim();
        if (dir.isEmpty() || inputField.getText().isEmpty()) return "";
        File in = new File(inputField.getText());
        String base = in.getName();
        int i = base.lastIndexOf('.');
        if (i > 0) base = base.substring(0, i);
        // Un PNG representa toda su carpeta: el nombre sale de la carpeta, sin el sufijo de fps
        if (getExt(in.getName()).equalsIgnoreCase(".png")) base = in.getParentFile().getName().replaceAll("_\\d+fps$", "");
        String ext = selectedExt(dstChecks);
        if (ext.isEmpty()) ext = ".clip";
        return dir + File.separator + base + ext;
    }

    private String selectedExt(java.util.Map<String, CheckBox> map) {
        for (java.util.Map.Entry<String, CheckBox> e : map.entrySet()) {
            if (e.getValue().isSelected()) return e.getKey();
        }
        return "";
    }

    private void markSingle(java.util.Map<String, CheckBox> map, String ext) {
        for (java.util.Map.Entry<String, CheckBox> e : map.entrySet()) {
            e.getValue().setSelected(e.getKey().equals(ext));
        }
    }

    private void clear(java.util.Map<String, CheckBox> map) {
        for (CheckBox c : map.values()) c.setSelected(false);
    }

    private String getExt(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i < 0 ? "" : fileName.substring(i);
    }

    private void convert() {
        String input = inputField.getText().trim();
        if (input.isEmpty()) {
            showAlert("Selecciona un archivo origen.");
            return;
        }
        String output = resolveOutput();
        if (output.isEmpty()) {
            showAlert("Define una carpeta de salida.");
            return;
        }

        String srcExt = getExt(input).toLowerCase();
        String dstExt = getExt(output).toLowerCase();
        if (srcExt.equals(dstExt)) {
            showAlert("El formato origen y destino no pueden ser iguales.");
            return;
        }

        // Dos conversiones no pueden escribir el mismo archivo
        String key = new File(output).getAbsolutePath().toLowerCase();
        if (!activeOutputs.add(key)) {
            showAlert("Ya hay una conversión en curso que escribe " + output);
            return;
        }

        markSingle(srcChecks, srcExt);
        markSingle(dstChecks, dstExt);
        statusLabel.setText("Conversión añadida: " + new File(input).getName());
        statusLabel.setTextFill(Color.web("#6b7280"));

        // La tarjeta de esta conversión: nombre, barra de progreso y qué está haciendo
        Label name = new Label(new File(input).getName() + "  →  " + new File(output).getName());
        name.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 12));
        name.setTextFill(Color.web("#1f2937"));
        ProgressBar bar = new ProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS); // en cola hasta que llegue el primer aviso
        bar.setMaxWidth(Double.MAX_VALUE);
        Label stage = new Label("En cola");
        stage.setFont(Font.font("Segoe UI", 11));
        stage.setTextFill(Color.web("#6b7280"));
        VBox card = new VBox(6, name, bar, stage);
        card.setPadding(new Insets(10, 12, 10, 12));
        card.setStyle("-fx-background-color: white; -fx-background-radius: 8; -fx-border-color: #e5e7eb; -fx-border-radius: 8;");
        jobsBox.getChildren().add(0, card);

        boolean legacy = legacyCheck.isSelected(); // se lee ahora: la casilla puede cambiar mientras la conversión corre
        pool.submit(() -> {
            ConversionResult res = null;
            Throwable crash = null;
            try {
                res = new Orchestrator()
                        .legacyIbis(legacy)
                        .onProgress((fraction, what) -> javafx.application.Platform.runLater(() -> {
                            bar.setProgress(fraction);
                            stage.setText(Math.round(fraction * 100) + " %  ·  " + what);
                        }))
                        .run(input, output);
            } catch (Throwable ex) { // también los Error: si no, la tarjeta se quedaría "en curso" para siempre
                crash = ex;
            }
            ConversionResult done = res;
            Throwable failure = crash;
            javafx.application.Platform.runLater(() -> {
                activeOutputs.remove(key);
                if (failure != null) {
                    bar.setProgress(0);
                    stage.setText("Excepción: " + (failure.getMessage() != null ? failure.getMessage() : failure.toString()));
                    stage.setTextFill(Color.web("#dc2626"));
                } else if (done.success) {
                    bar.setProgress(1);
                    stage.setText("Listo: " + done.outputPath);
                    stage.setTextFill(Color.web("#15803d"));
                } else {
                    bar.setProgress(0);
                    stage.setText("Falló: " + done.error);
                    stage.setTextFill(Color.web("#dc2626"));
                }
                stage.setWrapText(true);
            });
        });
    }

    private void showAlert(String msg) {
        javafx.application.Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.INFORMATION);
            a.setTitle("KachaponConverter");
            a.setHeaderText(null);
            a.setContentText(msg);
            a.showAndWait();
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}
