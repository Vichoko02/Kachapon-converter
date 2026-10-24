import com.kachapon.core.ConversionResult;
import com.kachapon.core.orchestrator.Orchestrator;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

public class TestKraToClip {
    public static void main(String[] args) throws Exception {
        File tmp = new File("build/test_kra_to_clip");
        tmp.mkdirs();

        // 1. Crear .kra de prueba con 2 frames y 2 capas
        File kra = new File(tmp, "test.kra");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(kra))) {
            // maindoc.xml
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<KritaDocument>\n" +
                "  <imagewidth>160</imagewidth>\n" +
                "  <imageheight>120</imageheight>\n" +
                "  <fps>24</fps>\n" +
                "  <duration>2</duration>\n" +
                "  <layers>\n" +
                "    <layer><name>linea</name><file>layers/linea.png</file><width>160</width><height>120</height></layer>\n" +
                "    <layer><name>color</name><file>layers/color.png</file><width>160</width><height>120</height></layer>\n" +
                "  </layers>\n" +
                "</KritaDocument>";
            ZipEntry e1 = new ZipEntry("maindoc.xml");
            zos.putNextEntry(e1); zos.write(xml.getBytes("UTF-8")); zos.closeEntry();

            // Frame 0: linea
            BufferedImage img1 = new BufferedImage(160, 120, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g1 = img1.createGraphics();
            g1.setColor(new Color(0, 0, 0, 255));
            g1.fillRect(10, 10, 140, 100);
            g1.dispose();
            ByteArrayOutputStream buf1 = new ByteArrayOutputStream();
            ImageIO.write(img1, "PNG", buf1);
            ZipEntry e2 = new ZipEntry("layers/linea.png");
            zos.putNextEntry(e2); zos.write(buf1.toByteArray()); zos.closeEntry();

            // Frame 0: color
            BufferedImage img2 = new BufferedImage(160, 120, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img2.createGraphics();
            g2.setColor(new Color(255, 0, 0, 255));
            g2.fillRect(20, 20, 120, 80);
            g2.dispose();
            ByteArrayOutputStream buf2 = new ByteArrayOutputStream();
            ImageIO.write(img2, "PNG", buf2);
            ZipEntry e3 = new ZipEntry("layers/color.png");
            zos.putNextEntry(e3); zos.write(buf2.toByteArray()); zos.closeEntry();

            // Frame 1: linea (mismo dibujo, hold)
            ZipEntry e4 = new ZipEntry("layers/linea.f1.png");
            zos.putNextEntry(e4); zos.write(buf1.toByteArray()); zos.closeEntry();

            // Frame 1: color (mismo dibujo, hold)
            ZipEntry e5 = new ZipEntry("layers/color.f1.png");
            zos.putNextEntry(e5); zos.write(buf2.toByteArray()); zos.closeEntry();
        }
        System.out.println("KRA creado: " + kra.getAbsolutePath());

        // 2. Convertir KRA -> CLIP
        File clip = new File(tmp, "test.clip");
        Orchestrator orch = new Orchestrator("temp_seq");
        ConversionResult r = orch.run(kra.getAbsolutePath(), clip.getAbsolutePath());
        System.out.println("KRA->CLIP: success=" + r.success + " error=" + r.error + " output=" + r.outputPath);

        if (!r.success) {
            System.out.println("FALLO: " + r.error);
            System.exit(1);
        }

        // 3. Verificar si el archivo .clip es nativo CSFCHUNK o ZIP puente
        byte[] header = new byte[8];
        try (FileInputStream fis = new FileInputStream(clip)) {
            fis.read(header);
        }
        String magic = new String(header, java.nio.charset.StandardCharsets.US_ASCII);
        System.out.println("Magic bytes del .clip de salida: \"" + magic + "\"");
        if ("CSFCHUNK".equals(magic)) {
            System.out.println("EXITO: El .clip es nativo CSFCHUNK (Clip Studio puede abrirlo)");
        } else if (new String(header, 0, 2, java.nio.charset.StandardCharsets.US_ASCII).equals("PK")) {
            System.out.println("FALLO: El .clip es un ZIP puente (Clip Studio NO puede abrirlo)");
        } else {
            System.out.println("DESCONOCIDO: Formato no reconocido");
        }
    }
}
