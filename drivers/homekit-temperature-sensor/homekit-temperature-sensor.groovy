/*
 * HomeKit Temperature Sensor (HAP Import child)
 *
 * A child of "HomeKit Accessory" representing one HomeKit TemperatureSensor
 * service. Read-only: temperature (converted from HAP °C to the hub scale by
 * the parent) arrives as live events from the parent's HAP session.
 *
 * Author: RamSet
 * Version: 0.1.0
 *
 * Changelog:
 *  v0.1.0 - Initial release.
 * Copyright 2026 RamSet — Apache License 2.0, provided as-is, no warranty.
 */
metadata {
    definition(name: "HomeKit HAP Temperature Sensor", namespace: "RamSet", author: "RamSet", importUrl: "http://10.33.47.84/api/v1/repos/RamSet/hubitat-homekit-import/raw/drivers/homekit-temperature-sensor/homekit-temperature-sensor.groovy?token=8813b22a89d3c96578b1eca8a56c2f8e6c2b3561") {
        capability "TemperatureMeasurement"
        capability "Battery"
        capability "Refresh"
        attribute "manufacturer", "string"
        attribute "model", "string"
        attribute "firmware", "string"
    }
}
def refresh(){ parent?.componentRefresh(this.device) }
