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
    definition(name: "HomeKit HAP Temperature Sensor", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/homekit-temperature-sensor/homekit-temperature-sensor.groovy") {
        capability "TemperatureMeasurement"
        capability "Battery"
        capability "Refresh"
        attribute "manufacturer", "string"
        attribute "model", "string"
        attribute "firmware", "string"
    }
}
def refresh(){ parent?.componentRefresh(this.device) }
