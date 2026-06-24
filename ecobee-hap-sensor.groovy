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
 * Version: 0.9.0
 * Date: 2026-06-24
 *
 * Changelog:
 *  v0.3.0 - Initial release.
 *
 * HPM Metadata:
 * {
 *   "package": "Ecobee HAP Thermostat (Local)",
 *   "namespace": "RamSet",
 *   "author": "RamSet",
 *   "location": "https://raw.githubusercontent.com/RamSet/hubitat/main/ecobee-hap-sensor.groovy",
 *   "description": "Child device for ecobee remote sensors (temperature, occupancy, motion, battery).",
 *   "required": true,
 *   "version": "0.9.0"
 * }
 *
 * Copyright 2026 RamSet
 * Licensed under the Apache License, Version 2.0. Provided as-is, without warranty.
 */

metadata {
    definition(name: "Ecobee HAP Remote Sensor", namespace: "RamSet", author: "RamSet") {
        capability "TemperatureMeasurement"
        capability "MotionSensor"
        capability "PresenceSensor"
        capability "Battery"
        attribute "lowBattery", "string"
        attribute "ecobeeId", "string"
    }
}
// Values are pushed by the parent thermostat device on refresh; nothing to do here.
def installed() {}
def updated() {}
