package com.kachapon.android;

import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.kachapon.core.*;
import com.kachapon.core.orchestrator.Orchestrator;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;

import static org.junit.Assert.*;

/** Las 6 conversiones ejecutadas en ART: comprueba que core no usa nada que Android no tenga. */
@RunWith(AndroidJUnit4.class)
public class CoreOnDeviceTest {
    private static final String[] FORMATS = {".kra", ".clip", ".ipv"};

    @Test
    public void everyDirectionWorksOnAndroid() throws Exception {
        File cache = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        Bridge ref = new Bridge(new File(cache, "ref").getPath());
        ref.reset();
        Manifest expected = new Manifest("4x3", 12, 4);
        expected.tracks.add("lineart");
        addFrame(ref, expected, 0, 2, 0xFFFF0000);
        addFrame(ref, expected, 2, 2, 0xFF00FF00);

        for (String src : FORMATS) {
            File in = new File(cache, "sample" + src);
            Orchestrator.converterFor(src).build(expected, ref, in);
            for (String dst : FORMATS) {
                if (src.equals(dst)) continue;
                File out = new File(cache, "out/r" + dst);
                ConversionResult r = new Orchestrator(new File(cache, "temp_seq").getPath()).run(in.getPath(), out.getPath());
                assertTrue(src + " -> " + dst + ": " + r.error, r.success);

                Bridge check = new Bridge(new File(cache, "check").getPath());
                check.reset();
                Manifest got = Orchestrator.converterFor(dst).extract(out.getPath(), check);
                assertEquals(expected.frames.size(), got.frames.size());
                for (int i = 0; i < got.frames.size(); i++) {
                    assertEquals(expected.frames.get(i).hold, got.frames.get(i).hold);
                    assertArrayEquals(ref.readFrame(expected.frames.get(i)), check.readFrame(got.frames.get(i)));
                }
            }
        }
    }

    private static void addFrame(Bridge b, Manifest m, int frame, int hold, int argb) throws Exception {
        Bitmap bmp = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888);
        bmp.setPixel(1, 1, argb);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, png);
        m.frames.add(new FrameRef("lineart", frame, hold, b.writeFrame("lineart", frame, png.toByteArray())));
    }
}
