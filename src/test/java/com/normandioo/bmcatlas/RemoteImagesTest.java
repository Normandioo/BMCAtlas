package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

class RemoteImagesTest {

    /** The first bytes of a PNG: signature and the IHDR chunk with the given size. */
    private static byte[] png(int w, int h) {
        return ByteBuffer.allocate(24)
                .put(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'})
                .putInt(w).putInt(h).array();
    }

    @Test
    void refusesPicturesTooBigToDecode() {
        assertTrue(RemoteImages.tooBig(png(30_000, 30_000)));
        assertTrue(RemoteImages.tooBig(png(16, 5_000)));
        assertFalse(RemoteImages.tooBig(png(4096, 4096)));
        assertFalse(RemoteImages.tooBig(png(64, 32)));
    }

    @Test
    void anythingElseIsLeftToTheDecoder() {
        assertFalse(RemoteImages.tooBig(new byte[]{1, 2, 3}));
        assertFalse(RemoteImages.tooBig(new byte[24]));   // not a PNG
    }
}
