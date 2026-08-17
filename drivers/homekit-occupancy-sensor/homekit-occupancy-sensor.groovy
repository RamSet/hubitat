/*
 * HomeKit Occupancy Sensor (HAP Import child)
 *
 * Child of "HomeKit Accessory" for a HomeKit OccupancySensor service (HAP type
 * 86), mapped to Hubitat PresenceSensor. Read-only: present/not present arrives
 * as live events from the parent's session.
 *
 * NOTE: not yet hardware-tested.
 * Author: RamSet
 * Version: 0.2.0
 *
 * Changelog:
 *  v0.2.0 - Also expose MotionSensor (mirrors occupancy -> motion active/inactive), so the device is selectable
 *           in Room Lighting and motion rules, not just presence automations. Events come from the parent.
 *  v0.1.0 - Initial release.
 * Copyright 2026 RamSet — Apache License 2.0, provided as-is, no warranty.
 */
metadata {
    definition(name: "HomeKit HAP Occupancy Sensor", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/drivers/homekit-occupancy-sensor/homekit-occupancy-sensor.groovy") {
        capability "PresenceSensor"
        capability "MotionSensor"      // occupancy also mirrored to motion (active while occupied) so it's selectable in Room Lighting / motion rules
        capability "Battery"
        capability "Refresh"
        attribute "manufacturer", "string"
        attribute "model", "string"
        attribute "firmware", "string"
    }
}
def refresh(){ parent?.componentRefresh(this.device) }
