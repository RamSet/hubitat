/*
 * HomeKit Occupancy Sensor (HAP Import child)
 *
 * Child of "HomeKit Accessory" for a HomeKit OccupancySensor service (HAP type
 * 86), mapped to Hubitat PresenceSensor. Read-only: present/not present arrives
 * as live events from the parent's session.
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
    definition(name: "HomeKit Occupancy Sensor", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/homekit-occupancy-sensor/homekit-occupancy-sensor.groovy") {
        capability "PresenceSensor"
        capability "Battery"
        capability "Refresh"
        attribute "manufacturer", "string"
        attribute "model", "string"
        attribute "firmware", "string"
    }
}
def refresh(){ parent?.componentRefresh(this.device) }
