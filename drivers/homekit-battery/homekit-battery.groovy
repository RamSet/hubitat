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
    definition(name: "HomeKit Battery", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/homekit-battery/homekit-battery.groovy") {
        capability "Battery"
        capability "Refresh"
        attribute "manufacturer", "string"
        attribute "model", "string"
        attribute "firmware", "string"
    }
}
def refresh(){ parent?.componentRefresh(this.device) }
