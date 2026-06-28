/*
 * HomeKit Switch (HAP Import child)
 *
 * A child of "HomeKit Accessory" representing one HomeKit Switch service.
 * On/Off commands are written back to the accessory over HAP by the parent;
 * state updates arrive as live events from the parent's session.
 *
 * Author: RamSet
 * Version: 0.1.0
 *
 * Changelog:
 *  v0.1.0 - Initial release.
 * Copyright 2026 RamSet — Apache License 2.0, provided as-is, no warranty.
 */
metadata {
    definition(name: "HomeKit Switch", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/homekit-switch/homekit-switch.groovy") {
        capability "Switch"
        capability "Refresh"
        attribute "manufacturer", "string"
        attribute "model", "string"
        attribute "firmware", "string"
    }
}
def on(){ parent?.componentOn(this.device) }
def off(){ parent?.componentOff(this.device) }
def refresh(){ parent?.componentRefresh(this.device) }
