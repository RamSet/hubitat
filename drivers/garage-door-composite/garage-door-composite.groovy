/*
 * Garage Door Composite (virtual)
 *
 * A clean GarageDoorControl device whose door STATE comes from a trusted external sensor and whose
 * OPEN/CLOSE actuates a SEPARATE opener. Created and driven by the "Garage Door Combiner" app — use it
 * when a garage controller's own position sensor is unreliable (e.g. the GoControl's radio tilt), so you
 * override that lying sensor with a sensor you trust. HomeKit / dashboards bind to THIS device.
 *
 * Author: RamSet
 * Version: 0.1.0
 */
metadata {
    definition(name: "Garage Door Composite", namespace: "RamSet", author: "RamSet",
        importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/drivers/garage-door-composite/garage-door-composite.groovy") {
        capability "GarageDoorControl"   // door: open/closed/opening/closing/unknown + open()/close()
        capability "ContactSensor"       // mirrors door as open/closed for rules/dashboards
        capability "Actuator"
        capability "Refresh"
        attribute "obstruction", "string"   // clear | detected  (a close that didn't complete = something in the way)
    }
}

def installed(){ }
def updated(){ }

// The app owns the sensor + opener wiring; commands just delegate up to it.
def open()    { parent?.childCmd("open") }
def close()   { parent?.childCmd("close") }
def refresh() { parent?.childRefresh() }
