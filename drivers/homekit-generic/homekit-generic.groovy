/*
 * HomeKit Generic (HAP Import child — raw fallback)
 *
 * Child of "HomeKit Accessory" for a HomeKit service type that isn't mapped to a
 * dedicated Hubitat capability yet. Rather than drop the device, it surfaces
 * EVERY readable characteristic of the service as a single JSON "characteristics"
 * attribute (keyed by "iid<n>(<typeCode>)"), updated live. setCharacteristic
 * lets you write any characteristic by iid for experimentation.
 *
 * This is what makes "just send a Dump Accessories log" work: an unknown device
 * still shows its data, and the typeCodes tell us what to add a real driver for.
 *
 * Author: RamSet
 * Version: 0.1.0
 *
 * Changelog:
 *  v0.1.0 - Initial release.
 * Copyright 2026 RamSet — Apache License 2.0, provided as-is, no warranty.
 */
metadata {
    definition(name: "HomeKit HAP Generic", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/homekit-generic/homekit-generic.groovy") {
        capability "Refresh"
        command "setCharacteristic", [[name:"iid*",type:"NUMBER",description:"characteristic iid (see the 'characteristics' attribute)"],[name:"value*",type:"STRING",description:"number, true/false, or text"]]
        attribute "characteristics", "string"   // JSON of all readable characteristics in this service
        attribute "serviceType", "string"       // HAP service type code (what to map a real driver for)
        attribute "manufacturer", "string"
        attribute "model", "string"
        attribute "firmware", "string"
    }
}
def setCharacteristic(iid, value){ parent?.componentRawWrite(this.device, iid, value) }
def refresh(){ parent?.componentRefresh(this.device) }
