/**
 *  Local Ecobee Helpers  (parent app)
 *
 *  Offline helper framework for the local Ecobee HAP Thermostat. Add as many
 *  child helpers as you need (Room Vent, Open-Contact Pause, Humidity). No cloud.
 *  Inspired by SANdood Ecobee Suite helpers, rebuilt from scratch.
 *
 *  Namespace: RamSet
 */
definition(
    name:        "Local Ecobee Helpers",
    namespace:   "RamSet",
    author:      "RamSet",
    description: "Offline helper apps for the local Ecobee HAP Thermostat (vents, open-contact pause, humidity).",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   "",
    singleInstance: false
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Local Ecobee Helpers", install: true, uninstall: true) {
        section("Thermostat") {
            input "thermostat", "capability.thermostat", title: "Local Ecobee HAP Thermostat", required: true, submitOnChange: true
            if (thermostat) {
                paragraph "State: <b>${thermostat.currentValue('thermostatOperatingState')}</b> | " +
                          "mode: <b>${thermostat.currentValue('thermostatMode')}</b> | " +
                          "heat sp: ${thermostat.currentValue('heatingSetpoint')}° | " +
                          "cool sp: ${thermostat.currentValue('coolingSetpoint')}° | " +
                          "humidity: ${thermostat.currentValue('humidity')}% (target ${thermostat.currentValue('humiditySetpoint')}%)"
            }
        }
        section("Helpers — add as many as you need") {
            app(name: "roomVents",    appName: "Local Ecobee Room Vent",           namespace: "RamSet", title: "Add a Room Vent…",            multiple: true)
            app(name: "contactPause", appName: "Local Ecobee Open-Contact Pause", namespace: "RamSet", title: "Add an Open-Contact Pause…", multiple: true)
            app(name: "humidity",     appName: "Local Ecobee Humidity",            namespace: "RamSet", title: "Add a Humidity helper…",      multiple: true)
        }
        section("System-wide airflow safety") {
            def rpt = belowFloorReport()
            if (rpt.below) {
                paragraph "<span style='color:red;font-weight:bold'>WARNING: ${rpt.below.size()} of ${rpt.vents} vent(s) are set below the 10% safe floor: ${rpt.below.join(', ')}. " +
                          "Verify your air handler tolerates this — too many closed vents at once can damage the system.</span>"
            } else if (rpt.vents) {
                paragraph "All ${rpt.vents} room vent(s) are at or above the 10% minimum floor."
            } else {
                paragraph "No room vents configured yet."
            }
        }
    }
}

def installed() { log.info "Local Ecobee Helpers installed" }
def updated()   { log.info "Local Ecobee Helpers updated" }

// shared accessor used by child apps
def getThermostat() { return thermostat }

// aggregate the per-vent floors reported by Room Vent children
Map belowFloorReport() {
    def below = []
    int vents = 0
    getChildApps().each { ch ->
        try {
            def r = ch.ventReport()         // only Room Vent children return a map
            if (r) {
                vents += (r.ventCount ?: 0) as int
                if (r.floor != null && (r.floor as int) < 10) below << "${r.name} (${r.floor}%)"
            }
        } catch (ignored) { /* not a Room Vent child */ }
    }
    return [below: below, vents: vents]
}
