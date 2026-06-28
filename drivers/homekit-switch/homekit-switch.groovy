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
    definition(name: "HomeKit HAP Switch", namespace: "RamSet", author: "RamSet", importUrl: "http://10.33.47.84/api/v1/repos/RamSet/hubitat-homekit-import/raw/drivers/homekit-switch/homekit-switch.groovy?token=8813b22a89d3c96578b1eca8a56c2f8e6c2b3561") {
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
