/*
 * HomeKit Fan (HAP Import child)
 *
 * Child of "HomeKit Accessory" for a HomeKit Fan (HAP type 40) or Fan v2 (B7)
 * service. On/Off + speed are written back over HAP via the parent. Speed is
 * exposed both as a level (0-100, HAP RotationSpeed) and as a named FanControl
 * speed (off/low/medium/high).
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
    definition(name: "HomeKit Fan", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/homekit-fan/homekit-fan.groovy") {
        capability "Switch"
        capability "SwitchLevel"
        capability "FanControl"
        capability "Refresh"
        attribute "manufacturer", "string"
        attribute "model", "string"
        attribute "firmware", "string"
    }
}
def on(){ parent?.componentOn(this.device) }
def off(){ parent?.componentOff(this.device) }
def setLevel(level, duration=null){ parent?.componentSetLevel(this.device, level, duration) }
def setSpeed(speed){ parent?.componentSetSpeed(this.device, speed) }
def cycleSpeed(){ def n=["off":"low","low":"medium","medium":"high","high":"off"][device.currentValue("speed")] ?: "low"; setSpeed(n) }
def refresh(){ parent?.componentRefresh(this.device) }
