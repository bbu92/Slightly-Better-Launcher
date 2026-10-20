package com.bbu.sbl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

public class SunTest {
    private static long at(String zone, int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of(zone)).toInstant().toEpochMilli();
    }

    /** Istanbul on 20 October 2026: sunrise about 07:36, sunset about 18:33 local time. */
    @Test
    public void istanbulAutumnDay() {
        String z = "Europe/Istanbul";
        assertFalse(Sun.isDay(41.0, 29.0, at(z, 2026, 10, 20, 7, 0)));
        assertTrue(Sun.isDay(41.0, 29.0, at(z, 2026, 10, 20, 8, 15)));
        assertTrue(Sun.isDay(41.0, 29.0, at(z, 2026, 10, 20, 13, 0)));
        assertTrue(Sun.isDay(41.0, 29.0, at(z, 2026, 10, 20, 18, 0)));
        assertFalse(Sun.isDay(41.0, 29.0, at(z, 2026, 10, 20, 19, 15)));
        assertFalse(Sun.isDay(41.0, 29.0, at(z, 2026, 10, 20, 2, 0)));
    }

    /** Midsummer sun in the far north never sets; midwinter there it never rises. */
    @Test
    public void polarDayAndNight() {
        assertTrue(Sun.isDay(70.0, 25.0, at("Europe/Oslo", 2026, 6, 21, 0, 30)));
        assertFalse(Sun.isDay(70.0, 25.0, at("Europe/Oslo", 2026, 12, 21, 12, 0)));
    }

    @Test
    public void sameInstantSameAnswer() {
        long t = at("America/New_York", 2026, 3, 1, 12, 0);
        assertEquals(Sun.isDay(40.7, -74.0, t), Sun.isDay(40.7, -74.0, t));
        assertTrue(Sun.isDay(40.7, -74.0, t));
    }
}
