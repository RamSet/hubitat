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
 * Version: 0.13.0
 * Date: 2026-07-15
 *
 * Changelog:
 *  v0.13.0 - Added timeSinceMotion / timeSinceOccupancy: a human-readable form of the secondsSince* activity
 *           timers (e.g. "3h 50m", "13d 4h", "1mo 2d"), so a sensor's activity age reads at a glance while the
 *           numeric secondsSince* attributes stay available for rules. Shows "unknown" when the ecobee reports
 *           its -1 sentinel. Populated by the parent thermostat driver 0.19.1+.
 *  v0.12.0 - Removed the Battery capability + lowBattery attribute. ecobee SmartSensor battery over HomeKit
 *           reads 100% / not-low until the sensor dies (confirmed on multiple ecobees) — misleading, not useful.
 *           The thermostat's alert flags a low/lost sensor reliably. Commented out (not deleted) for easy restore.
 *  v0.11.0 - Added ContactSensor capability so ecobee door/window SmartSensors (model EBDWC01) report
 *           open/closed. These expose contact + motion + occupancy + battery; the parent now creates a child
 *           for them (previously only temperature-bearing sensors got a child). Also added the generic
 *           "Sensor" capability so apps that filter device selection by it can pick these up.
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
 *   "location": "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/drivers/ecobee-hap-sensor/ecobee-hap-sensor.groovy",
 *   "description": "Child device for ecobee remote sensors (temperature, occupancy, motion, battery).",
 *   "required": true,
 *   "version": "0.12.0"
 * }
 *
 * Copyright 2026 RamSet
 * Licensed under the Apache License, Version 2.0. Provided as-is, without warranty.
 */

metadata {
    definition(name: "Ecobee HAP Remote Sensor", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/drivers/ecobee-hap-sensor/ecobee-hap-sensor.groovy") {
        capability "TemperatureMeasurement"
        capability "MotionSensor"
        capability "PresenceSensor"
        capability "ContactSensor"
        // capability "Battery"   // REMOVED (0.12.0): ecobee SmartSensor battery over HAP reads 100% right up until the sensor dies (confirmed on multiple ecobees) — misleading, not informative. The thermostat's alert flags a low/lost sensor reliably. Uncomment (with the parent's battery emits) if a future ecobee firmware reports battery accurately.
        capability "Sensor"   // generic tag so apps that filter device selection by "Sensor" can pick these
        // attribute "lowBattery", "string"   // REMOVED with Battery: StatusLowBattery over HAP is also stuck at "not low" until death, same unreliability. Use the thermostat's alert.
        attribute "ecobeeId", "string"
        attribute "secondsSinceMotion", "number"      // ecobee vendor timer (inferred): seconds since last motion; polled ~5-min
        attribute "secondsSinceOccupancy", "number"   // ecobee vendor timer (inferred): seconds since last occupancy; polled ~5-min
        attribute "timeSinceMotion", "string"         // human-readable form of secondsSinceMotion (e.g. "3h 50m", "13d 4h"); "unknown" if the ecobee reports its -1 sentinel
        attribute "timeSinceOccupancy", "string"      // human-readable form of secondsSinceOccupancy
    }
}
// Values are pushed by the parent thermostat device on refresh; nothing to do here.
def installed() {}
def updated() {}
