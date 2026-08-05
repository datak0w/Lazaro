package io.lazaro.cane.ble

import io.lazaro.cane.CaneButtonAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeWalkP2pButtonsTest {

    @Test
    fun `mapa WeWALK 2 captura A34`() {
        assertEquals(CaneButtonAction.LISTEN, WeWalkP2pButtons.resolvePayload("02 01"))
        assertEquals(CaneButtonAction.WHERE_AM_I, WeWalkP2pButtons.resolvePayload("01 01"))
        assertEquals(CaneButtonAction.CANCEL, WeWalkP2pButtons.resolvePayload("00 01"))
        assertEquals(CaneButtonAction.VOLUME_UP, WeWalkP2pButtons.resolvePayload("05 01"))
        assertEquals(CaneButtonAction.VOLUME_DOWN, WeWalkP2pButtons.resolvePayload("04 01"))
    }

    @Test
    fun `acepta edge 02 como pulsacion`() {
        assertEquals(CaneButtonAction.LISTEN, WeWalkP2pButtons.resolvePayload("02 02"))
        assertNull(WeWalkP2pButtons.resolvePayload("06 02")) // ID desconocido
    }

    @Test
    fun `ignora basura`() {
        assertNull(WeWalkP2pButtons.resolvePayload("00 00"))
        assertNull(WeWalkP2pButtons.resolvePayload("AA 01 C5 BB"))
        assertNull(WeWalkP2pButtons.resolvePayload(""))
    }

    @Test
    fun `detecta edge 02 como candidato`() {
        assertEquals(true, WeWalkP2pButtons.isP2pPress(WeWalkDevice.CHAR_RX_FE42, "03 02"))
        assertEquals("03" to "02", WeWalkP2pButtons.parseEdge("03 02"))
    }

    @Test
    fun `solo fe42`() {
        val ok = CaneBleEvent(
            charUuid = WeWalkDevice.CHAR_RX_FE42,
            hexPayload = "02 01",
            channelLabel = "RX P2P",
        )
        assertEquals(CaneButtonAction.LISTEN, WeWalkP2pButtons.resolve(ok))

        val other = CaneBleEvent(
            charUuid = WeWalkDevice.CHAR_HID_REPORT,
            hexPayload = "00 01",
            channelLabel = "HID",
        )
        assertNull(WeWalkP2pButtons.resolve(other))
    }
}
