package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MapClockTest {

    private long now = 1_000_000;

    @AfterEach
    void reset() {
        MapClock.wall = System::currentTimeMillis;
        MapClock.live();
        MapClock.setAnim(Long.MIN_VALUE);
    }

    @Test
    void liveBothClocksAreNow() {
        MapClock.wall = () -> now;
        assertFalse(MapClock.replay());
        assertEquals(now, MapClock.data());
        assertEquals(now, MapClock.anim());
    }

    @Test
    void inAReplayTheDataClockIsTheRecordedMomentAndAnimationStaysReal() {
        MapClock.wall = () -> now;
        MapClock.showData(42);
        assertTrue(MapClock.replay());
        assertEquals(42, MapClock.data());
        assertEquals(now, MapClock.anim());
        MapClock.live();
        assertEquals(now, MapClock.data());
    }

    @Test
    void theAnimationTimeOfAReplayDoesNotDependOnItsSpeed() {
        RecFormat.Meta meta = new RecFormat.Meta();
        meta.start = 0;
        meta.end = 10_000_000;
        meta.periodMs = 2000;
        for (int speed : Replay.SPEEDS) {
            Replay r = new Replay(null, meta, List.of(), Map.of(), List.of());
            r.setSpeed(speed);
            long anim = 5000;
            r.play(anim);
            for (int frame = 0; frame < 30; frame++) {   // one second of video at 30 fps
                anim += 1000 / 30;
                r.advance(anim);
            }
            assertEquals(990L * speed, r.time());   // 30 x 33 ms of animation, times the speed
        }
    }
}
