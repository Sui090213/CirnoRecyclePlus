package nep.timeline.cirno.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** SHA-256 本地校验工具的纯 JVM 测试. */
public class Sha256Test {
    @Test
    public void hashesKnownValues() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Sha256.INSTANCE.text(""));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Sha256.INSTANCE.text("abc"));
    }

    @Test
    public void matchesIsConstantTimeCaseInsensitive() {
        String hash = Sha256.INSTANCE.text("hello");
        assertTrue(Sha256.INSTANCE.matches(hash, hash.toUpperCase()));
        assertFalse(Sha256.INSTANCE.matches(hash, Sha256.INSTANCE.text("hello!")));
        assertFalse(Sha256.INSTANCE.matches(null, hash));
        assertFalse(Sha256.INSTANCE.matches(hash, ""));
    }
}
