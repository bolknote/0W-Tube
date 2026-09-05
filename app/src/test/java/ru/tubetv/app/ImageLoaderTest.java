package ru.tubetv.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class ImageLoaderTest {
    @Test public void boundsExtremeAspectRatioMemory() {
        for (int[] size : new int[][]{{100_000, 100}, {100, 100_000}, {8000, 8000}}) {
            int sample = ImageLoader.sampleSize(size[0], size[1], 360, 210);
            assertTrue((long) (size[0] / sample) * (size[1] / sample) <= 360L * 210 * 4);
        }
    }

    @Test public void keepsSmallThumbnailAtOriginalSize() {
        assertEquals(1, ImageLoader.sampleSize(320, 180, 360, 210));
    }
}
