/*
 * HomeKit Window Shade (HAP Import child)
 *
 * Child of "HomeKit Accessory" for a HomeKit WindowCovering service (HAP type
 * 8C). open/close/setPosition write TargetPosition over HAP via the parent
 * (HAP position: 0=closed, 100=open); current position + shade state arrive as
 * live events.
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
    definition(name: "HomeKit Window Shade", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/homekit-window-shade/homekit-window-shade.groovy") {
        capability "WindowShade"
        capability "Battery"
        capability "Refresh"
        attribute "manufacturer", "string"
        attribute "model", "string"
        attribute "firmware", "string"
    }
}
def open(){ parent?.componentOpen(this.device) }
def close(){ parent?.componentClose(this.device) }
def setPosition(p){ parent?.componentSetPosition(this.device, p) }
def startPositionChange(direction){ if(direction=="open") open() else close() }
def stopPositionChange(){ /* HAP exposes target position, not a stop command — no-op */ }
def refresh(){ parent?.componentRefresh(this.device) }
