/*
 * HomeKit Lock (HAP Import child)
 *
 * Child of "HomeKit Accessory" for a HomeKit LockMechanism service (HAP type
 * 45). lock()/unlock() write LockTargetState over HAP via the parent; the lock
 * state arrives from LockCurrentState (0=unlocked, 1=locked, 2=jammed->unknown).
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
    definition(name: "HomeKit Lock", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/homekit-lock/homekit-lock.groovy") {
        capability "Lock"
        capability "Battery"
        capability "Refresh"
        attribute "manufacturer", "string"
        attribute "model", "string"
        attribute "firmware", "string"
    }
}
def lock(){ parent?.componentLock(this.device) }
def unlock(){ parent?.componentUnlock(this.device) }
def refresh(){ parent?.componentRefresh(this.device) }
