/*
 * HomeKit HAP Security System (HAP Import child)
 *
 * A child of "HomeKit HAP Accessory" representing one HomeKit SecuritySystem
 * service (HAP type 7E) — e.g. the arm/away state of a bridge like a Eufy
 * Homebase. armHome/armAway/armNight/disarm write TargetState back over HAP via
 * the parent; the current state (incl. triggered) arrives as live events.
 *
 *   securitySystem: disarmed / armed home / armed away / armed night / triggered
 *   alarmState:     clear / triggered
 *
 * Kept as a lightweight Actuator with explicit commands (not Hubitat's
 * SecurityKeypad capability, which drags in code-management it doesn't have),
 * so it drops cleanly into Rule Machine / dashboards for home-away control.
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
    definition(name: "HomeKit HAP Security System", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/homekit-security-system/homekit-security-system.groovy") {
        capability "Actuator"
        capability "Refresh"
        command "armHome"
        command "armAway"
        command "armNight"
        command "disarm"
        attribute "securitySystem", "enum", ["disarmed","armed home","armed away","armed night","triggered","unknown"]
        attribute "alarmState", "enum", ["clear","triggered"]
        attribute "manufacturer", "string"
        attribute "model", "string"
        attribute "firmware", "string"
    }
}
def armHome(){ parent?.componentArmHome(this.device) }
def armAway(){ parent?.componentArmAway(this.device) }
def armNight(){ parent?.componentArmNight(this.device) }
def disarm(){ parent?.componentDisarm(this.device) }
def refresh(){ parent?.componentRefresh(this.device) }
