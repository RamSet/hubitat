/*
 * HomeKit Thermostat (HAP Import child)
 *
 * Child of "HomeKit Accessory" for a generic HomeKit Thermostat service (HAP
 * type 4A): mode, current/target temperature, heating/cooling setpoints, and
 * humidity (if exposed), written back over HAP via the parent.
 *
 * Scope: the STANDARD HomeKit thermostat characteristics only. Vendor extras
 * (e.g. ecobee comfort profiles, humidifier, fan-min-on-time) are NOT standard
 * HAP and are not covered here — the ecobee has its own dedicated driver.
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
    definition(name: "HomeKit HAP Thermostat", namespace: "RamSet", author: "RamSet", importUrl: "http://10.33.47.84/api/v1/repos/RamSet/hubitat-homekit-import/raw/drivers/homekit-thermostat/homekit-thermostat.groovy?token=8813b22a89d3c96578b1eca8a56c2f8e6c2b3561") {
        capability "Thermostat"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Battery"
        capability "Refresh"
        attribute "manufacturer", "string"
        attribute "model", "string"
        attribute "firmware", "string"
    }
}
def setThermostatMode(m){ parent?.componentSetThermostatMode(this.device, m) }
def setHeatingSetpoint(t){ parent?.componentSetHeatingSetpoint(this.device, t) }
def setCoolingSetpoint(t){ parent?.componentSetCoolingSetpoint(this.device, t) }
def off(){ setThermostatMode("off") }
def heat(){ setThermostatMode("heat") }
def cool(){ setThermostatMode("cool") }
def auto(){ setThermostatMode("auto") }
def emergencyHeat(){ setThermostatMode("heat") }   // HAP has no emergency-heat mode; fall back to heat
// HomeKit Thermostat service (4A) has no fan characteristics — these are required by the capability but no-op
def setThermostatFanMode(m){ }
def fanAuto(){ }
def fanOn(){ }
def fanCirculate(){ }
def setSchedule(s){ }
def refresh(){ parent?.componentRefresh(this.device) }
