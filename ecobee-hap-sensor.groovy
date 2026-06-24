metadata {
    definition(name: "Ecobee HAP Remote Sensor", namespace: "RamSet", author: "RamSet") {
        capability "TemperatureMeasurement"
        capability "MotionSensor"
        capability "PresenceSensor"
        capability "Battery"
        attribute "lowBattery", "string"
        attribute "ecobeeId", "string"
    }
}
// Values are pushed by the parent thermostat device on refresh; nothing to do here.
def installed() {}
def updated() {}
