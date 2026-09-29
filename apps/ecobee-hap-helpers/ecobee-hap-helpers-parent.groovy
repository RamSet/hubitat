/**
 *  Local Ecobee Helpers  (parent app)
 *
 *  Offline helper framework for the local Ecobee HAP Thermostat. Add as many
 *  child helpers as you need (Room Vent, Open-Contact Pause, Humidity). No cloud.
 *  Inspired by SANdood Ecobee Suite helpers, rebuilt from scratch.
 *
 *  Namespace: RamSet
 *
 *  Author: RamSet
 *  Version: 1.1.0 (2026-09-28)
 *  Version history:
 *    1.1.0 - Current-status block at the top of the page: the thermostat plus one line per helper,
 *            each helper reporting what it is doing right now (statusSummary()).
 *    1.0.0 - Initial release. Parent framework for Room Vent, Open-Contact Pause and Humidity helpers.
 *
 *  DISCLAIMER: Provided as-is, without warranty of any kind. You are solely
 *  responsible for the safe operation of your HVAC system and connected devices.
 *  Use at your own risk.
 */
definition(
    name:        "Local Ecobee Helpers",
    namespace:   "RamSet",
    author:      "RamSet",
    description: "Offline helper apps for the local Ecobee HAP Thermostat (vents, open-contact pause, humidity).",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/apps/ecobee-hap-helpers/ecobee-hap-helpers-parent.groovy",
    singleInstance: false
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Local Ecobee Helpers", install: true, uninstall: true) {
        section("<b>Current status</b>") {
            paragraph currentStatus()
        }
        section("Thermostat") {
            input "thermostat", "capability.thermostat", title: "Local Ecobee HAP Thermostat", required: true, submitOnChange: true
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

// shared accessor used by child apps (settings.thermostat avoids getter self-recursion)
def getThermostat() { return settings.thermostat }

// Live readout for the page: the thermostat, then one line per helper. Each helper reports
// its own summary (statusSummary()); a helper still on older code shows as unavailable.
private String currentStatus() {
    def rows = []
    def t = settings.thermostat
    if (t) {
        String op = t.currentValue('thermostatOperatingState') ?: 'unknown'
        rows << row("Thermostat — ${t.displayName}",
                    pill(op, opColor(op)) +
                    " <small>mode ${t.currentValue('thermostatMode')} · heat ${t.currentValue('heatingSetpoint')}° · " +
                    "cool ${t.currentValue('coolingSetpoint')}° · humidity ${t.currentValue('humidity')}% " +
                    "(target ${t.currentValue('humiditySetpoint')}%)</small>")
    } else {
        rows << row("Thermostat", pill("not selected — every helper is idle until it is", "red"))
    }
    def kids = getChildApps()
    if (!kids) rows << row("Helpers", pill("none added yet", "grey"))
    kids?.each { ch ->
        String kind = "Helper"
        String html
        try {
            def sm = ch.statusSummary()
            kind = sm?.kind ?: kind
            html = sm?.html ?: pill("no status", "grey")
        } catch (ignored) {
            html = pill("status unavailable — update this helper's code", "grey")
        }
        rows << row("${kind} — ${ch.label}", html)
    }
    return rows.join("<br>")
}

private String opColor(String op) {
    if (op in ['heating', 'pending heat']) return 'amber'
    if (op in ['cooling', 'pending cool']) return 'blue'
    return 'grey'
}

// --- status helpers (same look as the Blinds Dusk Automation status block) ---
private String row(String label, String value) {
    "<b>${label}:</b> ${value}"
}

private String pill(String text, String color) {
    def bg = [green:'#2e7d32', red:'#c62828', amber:'#ef6c00',
              blue:'#1565c0', indigo:'#4527a0', grey:'#616161'][color] ?: '#616161'
    "<span style='background:${bg};color:#fff;padding:2px 8px;border-radius:10px;font-size:0.85em;white-space:nowrap'>${text}</span>"
}

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
