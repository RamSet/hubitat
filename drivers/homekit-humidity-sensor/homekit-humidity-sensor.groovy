/*
 * HomeKit Humidity Sensor (HAP Import child)
 *
 * Child of "HomeKit Accessory" for a HomeKit HumiditySensor service (HAP type
 * 82). Read-only: relative humidity arrives as live events from the parent.
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
    definition(name: "HomeKit HAP Humidity Sensor", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/homekit-humidity-sensor/homekit-humidity-sensor.groovy") {
        capability "RelativeHumidityMeasurement"
        capability "Battery"
        capability "Refresh"
        attribute "manufacturer", "string"
        attribute "model", "string"
        attribute "firmware", "string"
    }
}
def refresh(){ parent?.componentRefresh(this.device) }
