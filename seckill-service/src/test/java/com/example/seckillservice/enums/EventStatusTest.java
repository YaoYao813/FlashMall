package com.example.seckillservice.enums;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventStatusTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 11, 10, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 11, 11, 12, 0);

    @Test
    void beforeStartIsPending() {
        assertEquals(EventStatus.PENDING, EventStatus.of(START, END, START.minusNanos(1)));
    }

    @Test
    void exactlyStartIsRunning() {
        assertEquals(EventStatus.RUNNING, EventStatus.of(START, END, START));
    }

    @Test
    void betweenStartAndEndIsRunning() {
        assertEquals(EventStatus.RUNNING, EventStatus.of(START, END, START.plusMinutes(30)));
    }

    @Test
    void exactlyEndIsRunning() {
        assertEquals(EventStatus.RUNNING, EventStatus.of(START, END, END));
    }

    @Test
    void afterEndIsEnded() {
        assertEquals(EventStatus.ENDED, EventStatus.of(START, END, END.plusNanos(1)));
    }

    @Test
    void nullTimesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> EventStatus.of(null, END, START));
        assertThrows(IllegalArgumentException.class,
                () -> EventStatus.of(START, null, START));
    }

    @Test
    void codeMatchesContract() {
        assertEquals("pending", EventStatus.PENDING.getCode());
        assertEquals("running", EventStatus.RUNNING.getCode());
        assertEquals("ended", EventStatus.ENDED.getCode());
    }

    @Test
    void fromCodeParsesCaseInsensitively() {
        assertEquals(Optional.of(EventStatus.RUNNING), EventStatus.fromCode("RUNNING"));
        assertEquals(Optional.of(EventStatus.ENDED), EventStatus.fromCode("ended"));
    }

    @Test
    void fromCodeReturnsEmptyForUnknownOrNull() {
        assertFalse(EventStatus.fromCode("all").isPresent());
        assertFalse(EventStatus.fromCode("bogus").isPresent());
        assertFalse(EventStatus.fromCode(null).isPresent());
    }

    @Test
    void fromCodeRoundTripsEveryValue() {
        for (EventStatus status : EventStatus.values()) {
            Optional<EventStatus> parsed = EventStatus.fromCode(status.getCode());
            assertTrue(parsed.isPresent());
            assertEquals(status, parsed.get());
        }
    }
}
