package com.ashwin.visionai

/**
 * Message formats the glasses' own firmware sends over the TCP socket on
 * port 8080, reverse engineered from the manufacturer's app (SHG Technologies
 * "Smart Vision Glasses", gen_5 connection layer). Not officially documented
 * anywhere, so treat as best-effort: if a firmware update changes these
 * strings, update them here.
 */
object GlassesProtocol {
    const val SOCKET_PORT = 8080

    const val SINGLE_TAP = "A1STZ"
    const val DOUBLE_TAP = "A1DTZ"
    const val LONG_TAP = "A1LHZ"
    const val PROLONG_TAP_1 = "APT1Z"   // ~1s hold -> dedicated action/capture button (confirmed: SwipeManager sets is_action_btn_on + GetImage.set_resolution)
    const val PROLONG_TAP_2 = "APT2Z"   // ~5s hold -> Emergency SOS call (confirmed: calls CallSos.make_emergency_call)
    const val WALKING_PRESS = "APTLZ"
    const val FORWARD_SWIPE = "A1FSZ"
    const val BACKWARD_SWIPE = "A1BSZ"

    private val batteryRegex = Regex("""BAT:(\d+)""")

    sealed class Event {
        data class Tap(val kind: String) : Event()
        data class Battery(val percent: Int) : Event()
        data class Charging(val isCharging: Boolean) : Event()
        data class DeviceIp(val ip: String) : Event()
        object PowerOff : Event()
        data class Unknown(val raw: String) : Event()
    }

    /**
     * Matching strategy below mirrors the decompiled app exactly:
     * ClientHandler.handleHeader uses .equals() for FORWARD_SWIPE/BACKWARD_SWIPE,
     * while SwipeManager.select_swipe_mode uses .contains() for every other
     * gesture code (single/double/long/hold_1s/hold_5s/walking). The IP/
     * battery/charging checks in ClientHandler.run() use .contains("192"),
     * .contains("BAT"), .contains("C:1"), .contains("C:0") - reproduced as-is.
     */
    fun parse(header: String): Event {
        val h = header.trim()
        return when {
            h.contains("192") || h.contains(".") && h.count { it == '.' } == 3 ->
                Event.DeviceIp(h.trim('/', '#'))
            h.contains("BAT") -> batteryRegex.find(h)?.groupValues?.get(1)?.toIntOrNull()
                ?.let { Event.Battery(it) } ?: Event.Unknown(h)
            h.contains("C:1") -> Event.Charging(true)
            h.contains("C:0") -> Event.Charging(false)
            h == FORWARD_SWIPE -> Event.Tap("forward")
            h == BACKWARD_SWIPE -> Event.Tap("backward")
            h.contains(DOUBLE_TAP) -> Event.Tap("double")
            h.contains(SINGLE_TAP) -> Event.Tap("single")
            h.contains(PROLONG_TAP_1) -> Event.Tap("hold_1s")
            h.contains(PROLONG_TAP_2) -> Event.Tap("hold_5s")
            h.contains(WALKING_PRESS) -> Event.Tap("walking")
            h.contains(LONG_TAP) -> Event.Tap("long")
            else -> Event.Unknown(h)
        }
    }
}
