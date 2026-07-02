/*
 * Ecobee HAP Remote Sensor
 *
 * Description:
 *   Child device for an ecobee remote room sensor (temperature, occupancy,
 *   motion, battery). Created and updated automatically by the Ecobee HAP
 *   Thermostat (Local) driver — install that package; this driver is not used
 *   on its own.
 *
 * Author: RamSet
 * Version: 0.10.0
 * Date: 2026-07-01
 *
 * Changelog:
 *  v0.10.0 - Added secondsSinceMotion / secondsSinceOccupancy attributes (an ecobee per-sensor activity
 *           timer read over HAP; the exact semantics are inferred and the value is polled, so it updates
 *           on roughly a 5-minute cadence).
 *  v0.3.0 - Initial release.
 *
 * HPM Metadata:
 * {
 *   "package": "Ecobee HAP Thermostat (Local)",
 *   "namespace": "RamSet",
 *   "author": "RamSet",
 *   "location": "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/ecobee-hap-sensor/ecobee-hap-sensor.groovy",
 *   "description": "Child device for ecobee remote sensors (temperature, occupancy, motion, battery).",
 *   "required": true,
 *   "version": "0.10.0"
 * }
 *
 * Copyright 2026 RamSet
 * Licensed under the Apache License, Version 2.0. Provided as-is, without warranty.
 */

metadata {
    definition(name: "Ecobee HAP Remote Sensor", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/ecobee-hap-sensor/ecobee-hap-sensor.groovy") {
        capability "TemperatureMeasurement"
        capability "MotionSensor"
        capability "PresenceSensor"
        capability "Battery"
        attribute "lowBattery", "string"
        attribute "ecobeeId", "string"
        attribute "secondsSinceMotion", "number"      // ecobee vendor timer (inferred): seconds since last motion; polled ~5-min
        attribute "secondsSinceOccupancy", "number"   // ecobee vendor timer (inferred): seconds since last occupancy; polled ~5-min
    }
}
// Values are pushed by the parent thermostat device on refresh; nothing to do here.
def installed() {}
def updated() {}
