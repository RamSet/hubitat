/*
 * HomeKit Garage Door (HAP Import child)
 *
 * A child of "HomeKit Accessory" representing one HomeKit GarageDoorOpener
 * service (HAP type 41). open()/close() write TargetDoorState back to the
 * accessory over HAP via the parent; the door state arrives as live events
 * from the parent's session.
 *
 * Author: RamSet
 * Version: 0.2.0
 *
 * Changelog:
 *  v0.2.0 - Added obstruction (ObstructionDetected) attribute + accessory info attributes.
 *  v0.1.0 - Initial release. Validated on a Meross MSG100.
 *
 * Copyright 2026 RamSet — Apache License 2.0, provided as-is, no warranty.
 */
metadata {
    definition(name: "HomeKit HAP Garage Door", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/homekit-garage-door/homekit-garage-door.groovy") {
        capability "GarageDoorControl"
        capability "Refresh"
        attribute "obstruction", "enum", ["obstructed","clear"]   // HomeKit ObstructionDetected
        attribute "manufacturer", "string"
        attribute "model", "string"
        attribute "firmware", "string"
    }
}
def open(){ parent?.componentOpen(this.device) }
def close(){ parent?.componentClose(this.device) }
def refresh(){ parent?.componentRefresh(this.device) }
