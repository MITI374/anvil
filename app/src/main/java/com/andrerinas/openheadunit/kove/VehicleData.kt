package com.andrerinas.openheadunit.kove

import org.json.JSONObject
import java.util.Locale

/**
 * Trip data the Kove TFT reports over Bluetooth (message 10), roughly every few seconds:
 *  - item 1: fuel consumption, "current" / "max" / "average";
 *  - item 2: odometer "total" and trip "subtotal" (km);
 *  - item 3: "tire_pressure", "remaining_oil" (fuel level) and "endurance" (range, km).
 *
 * Consumption is assumed to come in tenths of l/100 km (45 = 4.5 l/100 km); the raw values are
 * logged so a ride can confirm or correct that.
 */
object VehicleData {

    @Volatile var rangeKm: Int? = null
        private set
    @Volatile var consumptionTenths: Int? = null
        private set
    @Volatile var averageTenths: Int? = null
        private set

    /** Bumped on every change, so the overlay only redraws its text when something moved. */
    @Volatile var version = 0
        private set

    fun clear() {
        rangeKm = null
        consumptionTenths = null
        averageTenths = null
        version++
    }

    /** Takes one TFT message; returns true when it carried trip data. */
    fun update(json: JSONObject): Boolean {
        if (json.optInt("msg_id") != 10) return false
        when (json.optInt("item")) {
            1 -> {
                consumptionTenths = json.optInt("current", 0)
                averageTenths = json.optInt("average", 0)
            }
            3 -> {
                // 0 is what the TFT sends right after the ignition goes off: treat it as unknown.
                rangeKm = json.optInt("endurance", 0).takeIf { it > 0 }
            }
            else -> return false
        }
        version++
        return true
    }

    /** Text for the overlay, e.g. "163 km   4.5 l/100", or null while nothing is known yet. */
    fun badgeText(): String? {
        val parts = ArrayList<String>(2)
        rangeKm?.let { parts += "$it km" }
        consumptionTenths?.let { parts += String.format(Locale.US, "%.1f l/100", it / 10f) }
        return if (parts.isEmpty()) null else parts.joinToString("   ")
    }
}
