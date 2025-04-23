/**
 *  Network Monitor HealthCheck (HTTP)
 *
 *  Monitors Internet, LAN, and optionally Custom Host connectivity using HTTP requests.
 *  Includes HealthCheck capability, with status attributes for each check.
 *
 *  Author: RamSet
 *  Version: 1.3
 *  Date: 2025-04-23
 *
 *  Changelog:
 *  v1.3
 *  - Added logging preferences (info, debug, warn, off).
 *  - LAN check made optional via `checkLAN` toggle.
 *  - Fixed boolean preference default issue after update (preserves user settings).
 *  - Prevents unnecessary events if values haven't changed.
 *  - Description text shows actual HTTP status code or error.
 * 
 *  v1.2
 *  - Added optional toggle for LAN checks (checkLAN).
 *  - Avoids sending "offline" event if value hasn't changed, to prevent RM false triggers.
 *  - Ensured preferences default properly during initialization.
 *
 *  v1.1
 *  - Handles host unreachable errors more clearly.
 *  - Any HTTP code is now considered a successful response unless no code is returned.
 *  - Custom host check can now be disabled.
 */

metadata {
    definition(
        name: "Network Monitor HealthCheck (HTTP)",
        namespace: "Mezel",
        author: "RamSet",
        importURL: "https://raw.githubusercontent.com/RamSet/hubitat/main/NetworkMonitorHealthCheck.groovyNetworkMonitorHealthCheck.groovy"
    ) {
        capability "Sensor"
        capability "Actuator"
        attribute "internet", "string"
        attribute "lan", "string"
        attribute "custom", "string"
        command "checkNow"
    }

    preferences {
        input("internetHost", "text", title: "Internet Host", required: true, defaultValue: "https://www.google.com")
        input("checkLAN", "bool", title: "Check LAN Host?", defaultValue: true)
        input("lanHost", "text", title: "LAN Host", required: true, defaultValue: "http://192.168.1.1")
        input("checkCustom", "bool", title: "Check Custom Host?", defaultValue: true)
        input("customHost", "text", title: "Custom Host", required: false, defaultValue: "")
        input("checkInterval", "number", title: "Check Interval (seconds)", required: true, defaultValue: 300)
        input("logLevel", "enum", title: "Logging Level", defaultValue: "info", options: ["off", "info", "debug", "warn"])
    }
}

def installed() {
    logInfo "Network Monitor HealthCheck (HTTP) installed"
    initialize()
}

def updated() {
    logInfo "Network Monitor HealthCheck (HTTP) updated"
    unschedule()
    initialize()
}

def initialize() {
    // Ensure undefined boolean preferences are treated as true
    if (settings.checkLAN == null) device.updateSetting("checkLAN", [value: "true", type: "bool"])
    if (settings.checkCustom == null) device.updateSetting("checkCustom", [value: "true", type: "bool"])

    // Set HealthCheck interval
    if (settings.checkInterval) {
        sendEvent(name: "checkInterval", value: settings.checkInterval, unit: "sec")
    }

    checkConnectivity()
}

def checkNow() {
    logInfo "Manual network check triggered"
    checkConnectivity()
}

def checkConnectivity() {
    // Internet check is always required
    checkHost("internet", settings.internetHost?.trim() ?: "https://www.google.com")

    // LAN check only if enabled
    if (settings.checkLAN) {
        checkHost("lan", settings.lanHost?.trim() ?: "http://192.168.1.1")
    } else {
        updateAttribute("lan", "disabled", "LAN host check is disabled")
    }

    // Custom host check only if enabled
    if (settings.checkCustom) {
        def customHost = settings.customHost?.trim()
        if (customHost) {
            checkHost("custom", customHost)
        } else {
            logWarn "Custom host check enabled but no host specified"
            updateAttribute("custom", "offline", "Custom host not specified")
        }
    } else {
        updateAttribute("custom", "disabled", "Custom host check is disabled")
    }

    // Reschedule next check
    if (settings.checkInterval) {
        runIn(settings.checkInterval as Integer, checkConnectivity)
    }
}

def checkHost(attrName, url) {
    try {
        httpGet([uri: url, ignoreSSLIssues: true]) { response ->
            def statusCode = response?.getStatus()
            logDebug "Host ${url} responded with status ${statusCode}"
            updateAttribute(attrName, "online", "Online - HTTP ${statusCode}")
        }
    } catch (Exception e) {
        def msg = e.message ?: "Unknown error"
        def codeMatch = msg =~ /status code: (\d{3})/
        if (codeMatch) {
            def code = codeMatch[0][1]
            logDebug "Host ${url} returned error code ${code}, treating as online"
            updateAttribute(attrName, "online", "Online - HTTP ${code}")
        } else {
            logWarn "Host ${url} is unreachable: ${msg}"
            updateAttribute(attrName, "offline", "Offline - ${msg}")
        }
    }
}

private updateAttribute(attr, value, description = null) {
    if (device.currentValue(attr) != value) {
        sendEvent(name: attr, value: value, descriptionText: description ?: value)
        logInfo "Attribute '${attr}' set to '${value}'${description ? " (${description})" : ""}"
    }
}

// Logging helpers
private logInfo(msg)  { if (settings.logLevel in ["info", "debug"])  log.info  msg }
private logDebug(msg) { if (settings.logLevel == "debug")            log.debug msg }
private logWarn(msg)  { if (settings.logLevel in ["warn", "debug"])  log.warn  msg }