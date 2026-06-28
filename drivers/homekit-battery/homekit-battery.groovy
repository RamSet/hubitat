/*
 * HomeKit Battery (HAP Import child)
 *
 * Child of "HomeKit Accessory" for a HomeKit BatteryService (HAP type 96).
 * Read-only: battery level arrives as live events from the parent. Note: many
 * accessories expose Battery as a service alongside a sensor, so this may appear
 * as a separate child next to the sensor it belongs to.
 *
 * NOTE: not yet hardware-tested.
 * Author: RamSet
 * Version: 0.1.0
 *
 * Changelog:
 *  v0.1.0 - Initial release.
 * Copyright 2026 RamSet — Apache License 2.0, provided as-is, no warranty.
 */
metadata {
    definition(name: "HomeKit HAP Battery", namespace: "RamSet", author: "RamSet", importUrl: "http://10.33.47.84/api/v1/repos/RamSet/hubitat-homekit-import/raw/drivers/homekit-battery/homekit-battery.groovy?token=8813b22a89d3c96578b1eca8a56c2f8e6c2b3561") {
        capability "Battery"
        capability "Refresh"
        attribute "manufacturer", "string"
        attribute "model", "string"
        attribute "firmware", "string"
    }
}
def refresh(){ parent?.componentRefresh(this.device) }
