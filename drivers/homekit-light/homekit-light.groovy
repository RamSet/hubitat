/*
 * HomeKit Light (HAP Import child)
 *
 * Child of "HomeKit Accessory" for a HomeKit Lightbulb service (HAP type 43).
 * On/Off, brightness, and (if the bulb exposes them) hue/saturation and color
 * temperature are written back over HAP via the parent. Attributes that the
 * bulb doesn't expose simply never update.
 *
 * NOTE: not yet hardware-tested (first validated device was a garage opener).
 * Author: RamSet
 * Version: 0.1.0
 *
 * Changelog:
 *  v0.1.0 - Initial release.
 * Copyright 2026 RamSet — Apache License 2.0, provided as-is, no warranty.
 */
metadata {
    definition(name: "HomeKit HAP Light", namespace: "RamSet", author: "RamSet", importUrl: "http://10.33.47.84/api/v1/repos/RamSet/hubitat-homekit-import/raw/drivers/homekit-light/homekit-light.groovy?token=8813b22a89d3c96578b1eca8a56c2f8e6c2b3561") {
        capability "Switch"
        capability "SwitchLevel"
        capability "ColorControl"
        capability "ColorTemperature"
        capability "Refresh"
        attribute "manufacturer", "string"
        attribute "model", "string"
        attribute "firmware", "string"
    }
}
def on(){ parent?.componentOn(this.device) }
def off(){ parent?.componentOff(this.device) }
def setLevel(level, duration=null){ parent?.componentSetLevel(this.device, level, duration) }
def setColor(Map c){ parent?.componentSetColor(this.device, c) }
def setHue(h){ parent?.componentSetHue(this.device, h) }
def setSaturation(s){ parent?.componentSetSaturation(this.device, s) }
def setColorTemperature(k, level=null, duration=null){ parent?.componentSetColorTemperature(this.device, k, level, duration) }
def refresh(){ parent?.componentRefresh(this.device) }
