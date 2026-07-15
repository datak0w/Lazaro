package io.lazaro.cane.ble

/**
 * Secuencia experimental de handshake post-conexión.
 * WeWALK no documenta el protocolo; la app oficial probablemente envía algo similar
 * antes de activar HID/botones. Ajustar según capturas de btsnoop con la app oficial.
 */
object WeWalkHandshake {
    data class Step(
        val charUuid: String,
        val data: ByteArray,
        val label: String,
        val delayAfterMs: Long = 150,
    )

    fun buildSequence(): List<Step> = listOf(
        Step(
            charUuid = WeWalkDevice.CHAR_TX_FE43,
            data = WeWalkProtocol.buildFrame(WeWalkProtocol.CMD_BATTERY),
            label = "Consulta batería P2P",
            delayAfterMs = 250,
        ),
        Step(
            charUuid = WeWalkDevice.CHAR_TX_FE43,
            data = WeWalkProtocol.buildFrame(WeWalkProtocol.CMD_INIT),
            label = "Inicio sesión P2P",
        ),
        Step(
            charUuid = WeWalkDevice.CHAR_TX_FE43,
            data = WeWalkProtocol.buildFrame(WeWalkProtocol.CMD_VERSION),
            label = "Versión firmware",
        ),
        Step(
            charUuid = WeWalkDevice.CHAR_TX_FE43,
            data = WeWalkProtocol.buildFrame(WeWalkProtocol.CMD_SESSION, byteArrayOf(0x02)),
            label = "Activar sesión app",
        ),
        Step(
            charUuid = WeWalkDevice.CHAR_UART_01,
            data = byteArrayOf(0x55),
            label = "Ping UART seguro",
        ),
        Step(
            charUuid = WeWalkDevice.CHAR_UART_01,
            data = WeWalkProtocol.buildFrame(WeWalkProtocol.CMD_INIT),
            label = "Inicio UART",
        ),
    )
}
