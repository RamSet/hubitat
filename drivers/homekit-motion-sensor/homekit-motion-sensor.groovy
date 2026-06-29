/*
 * HomeKit Motion Sensor (HAP Import child)
 *
 * Child of "HomeKit Accessory" for a HomeKit MotionSensor service (HAP type 85).
 * Read-only: active/inactive arrives as live events from the parent's session.
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
    definition(name: "HomeKit HAP Motion Sensor", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/homekit-motion-sensor/homekit-motion-sensor.groovy") {
        capability "MotionSensor"
        capability "Battery"
        capability "Refresh"
        attribute "manufacturer", "string"
        attribute "model", "string"
        attribute "firmware", "string"
    }
}
def refresh(){ parent?.componentRefresh(this.device) }
