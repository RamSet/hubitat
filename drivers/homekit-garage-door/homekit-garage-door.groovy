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
    definition(name: "HomeKit HAP Garage Door", namespace: "RamSet", author: "RamSet", importUrl: "http://10.33.47.84/api/v1/repos/RamSet/hubitat-homekit-import/raw/drivers/homekit-garage-door/homekit-garage-door.groovy?token=8813b22a89d3c96578b1eca8a56c2f8e6c2b3561") {
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
