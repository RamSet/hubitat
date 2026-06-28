/*
 * HomeKit Light Sensor (HAP Import child)
 *
 * Child of "HomeKit Accessory" for a HomeKit LightSensor service (HAP type 84),
 * mapped to Hubitat IlluminanceMeasurement. Read-only: lux arrives as live
 * events from the parent.
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
    definition(name: "HomeKit Light Sensor", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/homekit-light-sensor/homekit-light-sensor.groovy") {
        capability "IlluminanceMeasurement"
        capability "Battery"
        capability "Refresh"
        attribute "manufacturer", "string"
        attribute "model", "string"
        attribute "firmware", "string"
    }
}
def refresh(){ parent?.componentRefresh(this.device) }
