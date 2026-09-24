package com.realisticmarkets.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.realisticmarkets.registry.SecurityRegistry.SettleResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecurityRegistryTest {
    @Test
    void aSerialSettlesExactlyOnce() {
        SecurityRegistry reg = new SecurityRegistry();
        var cd = reg.issue("CD", 3, Map.of("principal", "50000"));
        assertEquals(SecurityRegistry.Status.ISSUED, reg.lookup(cd.serial()).orElseThrow().status());
        assertEquals(SettleResult.OK, reg.settle(cd.serial()));
        assertEquals(SettleResult.ALREADY_SETTLED, reg.settle(cd.serial()), "a copied paper is refused");
        assertEquals(SettleResult.UNKNOWN, reg.settle(UUID.randomUUID()), "a forged paper is refused");
    }

    @Test
    void theRegistryHoldsTheTermsNotThePaper() {
        SecurityRegistry reg = new SecurityRegistry();
        var cd = reg.issue("CD", 3, Map.of("principal", "50000", "term", "7"));
        assertEquals("50000", reg.lookup(cd.serial()).orElseThrow().terms().get("principal"));
    }

    @Test
    void serialsNeverCollide() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID b = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Iterator<UUID> seq = List.of(a, a, b).iterator();
        SecurityRegistry reg = new SecurityRegistry(seq::next);
        assertEquals(a, reg.issue("CD", 0, Map.of()).serial());
        assertEquals(b, reg.issue("CD", 0, Map.of()).serial(), "a repeated serial is skipped");
    }

    @Test
    void registryRoundTrips() throws Exception {
        SecurityRegistry reg = new SecurityRegistry();
        var one = reg.issue("CD", 3, Map.of("principal", "50000", "rate", "0.0045"));
        var two = reg.issue("CD", 4, Map.of());
        reg.settle(one.serial());
        StringWriter w = new StringWriter();
        reg.write(w);
        SecurityRegistry back = SecurityRegistry.read(new StringReader(w.toString()));
        assertEquals(2, back.size());
        assertEquals(reg.lookup(one.serial()), back.lookup(one.serial()));
        assertEquals(reg.lookup(two.serial()), back.lookup(two.serial()));
        assertEquals(SettleResult.ALREADY_SETTLED, back.settle(one.serial()), "settled status survives a reload");
        assertNotEquals(one.serial(), back.issue("CD", 5, Map.of()).serial());
        assertThrows(IllegalArgumentException.class, () -> SecurityRegistry.read(new StringReader("sec\tnot-a-uuid\n")));
        assertTrue(w.toString().startsWith(SecurityRegistry.HEADER));
    }
}
