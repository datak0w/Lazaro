package io.lazaro.cane.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeWalkObstacleParserTest {

    @Test
    fun ignoresP2pButtonsAndBattery() {
        assertFalse(
            WeWalkObstacleParser.isCaptureCandidate(
                WeWalkDevice.CHAR_RX_FE42,
                "02 01",
            ),
        )
        assertNull(
            WeWalkObstacleParser.parse(WeWalkDevice.CHAR_RX_FE42, "02 01"),
        )
    }

    @Test
    fun fe45HeartbeatNotObstacle() {
        val heartbeat = "08 00 A5 5A 20 01 00 01 DB 23"
        val hb = WeWalkObstacleParser.parseFe45(heartbeat)!!
        assertEquals(WeWalkObstacleParser.Fe45Frame.Kind.HEARTBEAT, hb.kind)
        assertNull(WeWalkObstacleParser.parse(WeWalkDevice.CHAR_FE45, heartbeat))
    }

    @Test
    fun fe45StatusClearDistanceNotAnnounced() {
        // Captura #1 idle: dist=241 (F1 00)
        val clear =
            "6D 00 A5 5A 1F 66 00 0F C5 FF F1 00 00 00 53 00 53 00 38 00 00 00 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "48 00 00 00 3F 00 00 00 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "00 00 00 00 00 00 01 00 00 00 40 00 00 00 03 00 00 00 2C 00 00 00 01 FC " +
                "9A 26 E1 80 00 00 00 EE 4E 00 DB 87"
        val st = WeWalkObstacleParser.parseFe45(clear)!!
        assertEquals(WeWalkObstacleParser.Fe45Frame.Kind.STATUS, st.kind)
        assertEquals(241, st.distanceCm)
        assertEquals(83, st.batteryHint)
        assertFalse(WeWalkObstacleParser.isObstacleDistance(241))
        assertNull(WeWalkObstacleParser.parse(WeWalkDevice.CHAR_FE45, clear))
    }

    @Test
    fun fe45StatusNearObstacleParsesDistance() {
        // Captura #2: dist=192 (C0 00) al acercar
        val near =
            "6D 00 A5 5A 1F 66 00 0F B9 FF C0 00 00 00 52 00 52 00 38 00 00 01 " +
                "45 4F 00 00 0B 46 00 00 00 01 00 00 00 64 00 00 0B 4C 00 00 00 00 00 00 " +
                "00 49 00 00 00 40 01 00 00 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "00 00 00 00 00 00 00 00 00 01 01 00 00 00 40 00 00 00 03 00 00 00 2C 00 " +
                "00 00 01 FC 9A 26 E1 80 00 00 07 73 D6 01 27 70"
        val st = WeWalkObstacleParser.parseFe45(near)!!
        assertEquals(192, st.distanceCm)
        assertTrue(WeWalkObstacleParser.isObstacleDistance(192))
        val reading = WeWalkObstacleParser.parse(WeWalkDevice.CHAR_FE45, near)
        assertNotNull(reading)
        assertEquals(192, reading!!.distanceCm)
        assertEquals(
            "Obstáculo enfrente, a dos metros.",
            WeWalkObstacleParser.announceFrontalPhrase(192),
        )
        assertEquals(
            "Obstáculo enfrente, a medio metro.",
            WeWalkObstacleParser.announceFrontalPhrase(60),
        )
        assertEquals(
            "Obstáculo enfrente, a un metro.",
            WeWalkObstacleParser.announceFrontalPhrase(100),
        )
        assertEquals(
            "Obstáculo enfrente, a un metro y medio.",
            WeWalkObstacleParser.announceFrontalPhrase(150),
        )
    }

    @Test
    fun ignoresImuNotify13() {
        assertFalse(
            WeWalkObstacleParser.isCaptureCandidate(
                WeWalkDevice.CHAR_NOTIFY_13,
                "B7 41 00 00 90 C1 F9 3C",
            ),
        )
    }
}
