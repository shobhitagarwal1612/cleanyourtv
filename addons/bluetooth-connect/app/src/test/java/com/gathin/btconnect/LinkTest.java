package com.gathin.btconnect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LinkTest {
    @Test public void unknownClassIsShown() {
        assertTrue(Link.isAudio(null));
    }

    @Test public void nullGuardsReturnSafely() {
        assertEquals(-1, Link.batteryLevel(null));
        assertFalse(Link.set(null, null, true));
        assertFalse(Link.isDeviceConnected(null));
    }
}

