package com.andrerinas.openheadunit.kove

import org.json.JSONObject

/**
 * Trip data the Kove TFT reports over Bluetooth (message 10), about once a second:
 *  - item 1: road speed in km/h, "current" / "max" / "average". Not fuel consumption: checked
 *    against the odometer on a ride, "current" = 1.01 x speed. The TFT sends no fuel flow at all;
 *  - item 2: odometer "total" and trip "subtotal", in 0.1 km;
 *  - item 3: "tire_pressure", "remaining_oil" and "endurance" (range, km). The TFT computes the
 *    range itself and smooths it hard: at most 1 km per update while riding;
 *  - item 4: "tag", a counter that goes up once a second.
 * Only the range is shown.
 */
object VehicleData {

    @Volatile var rangeKm: Int? = null
        private set

    /** Bumped on every change, so the overlay only redraws its text when something moved. */
    @Volatile var version = 0
        private set

    fun clear() {
        rangeKm = null
        version++
    }

    /** Takes one TFT message; returns true when it carried the range. */
    fun update(json: JSONObject): Boolean {
        if (json.optInt("msg_id") != 10 || json.optInt("item") != 3) return false
        // 0 is what the TFT sends around ignition on and off: treat it as unknown.
        val range = json.optInt("endurance", 0).takeIf { it > 0 }
        if (range != rangeKm) {
            rangeKm = range
            version++
        }
        return true
    }

    /** Text for the overlay, or null while the range is unknown. */
    fun badgeText(): String? = rangeKm?.let { "$it km" }
}
