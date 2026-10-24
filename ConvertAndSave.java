import com.kachapon.core.orchestrator.Orchestrator;
import com.kachapon.core.ConversionResult;
import java.io.File;

public class ConvertAndSave {
    public static void main(String[] args) throws Exception {
        String clipInput = "C:\\Users\\Vicen\\Downloads\\LunariFall.clip";
        String kraInput = "C:\\Users\\Vicen\\Downloads\\escena 1.kra";
        String outDir = "C:\\Users\\Vicen\\Downloads\\";

        // Clip -> Kra
        File clipOut = new File(outDir, "LunariFall_converted.kra");
        ConversionResult r1 = new Orchestrator("temp_seq").run(clipInput, clipOut.getPath());
        System.out.println("CLIP -> KRA: " + (r1.success ? "OK " + clipOut.length() + " bytes" : "FAIL " + r1.error));

        // Kra -> Clip
        File kraOut = new File(outDir, "escena1_converted.clip");
        ConversionResult r2 = new Orchestrator("temp_seq2").run(kraInput, kraOut.getPath());
        System.out.println("KRA -> CLIP: " + (r2.success ? "OK " + kraOut.length() + " bytes" : "FAIL " + r2.error));
    }
}