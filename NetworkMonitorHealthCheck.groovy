/**
 *  Network Monitor HealthCheck (HTTP)
 *
 *  This driver monitors Internet, LAN, and optionally Custom host connectivity using HTTP requests.
 *  It implements the HealthCheck capability with a user-defined checkInterval.
 *
 *  Author: RamSet
 *  Version: 1.3.1
 *  Last Updated: 2025-04-21
 *
 *  Changelog:
 *  v1.3.1 - Fixes:
 *    - LAN host is no longer required or defaulted if LAN checks are disabled.
 *    - Prevents saving errors when 'Check LAN Host?' is off and field is empty.
 *
 *  v1.3 - Additions:
 *    - Logging level preference added (info, debug, warn, off)
 *    - Attributes no longer reset to 'offline' unless value changes (reduces event noise)
 *    - LAN check can now be disabled, like Custom host check
 *
 *  v1.2 - Improvements:
 *    - Improved offline/online detection
 *    - Only considers host 'offline' if unreachable or no HTTP code is received
 *
 *  v1.1 - Added importURL for Hubitat Package Manager compatibility
 *
 *  v1.0 - Initial release
 */

metadata {
    definition(
        name: "Network Monitor HealthCheck (HTTP)",
        namespace: "Mezel",
        author: "RamSet",
        importURL: "https://raw.githubusercontent.com/RamSet/hubitat/main/NetworkMonitorHealthCheck.groovy"
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
        input("lanHost", "text", title: "LAN Host", required: false, defaultValue: "")
        input("checkCustom", "bool", title: "Check Custom Host?", defaultValue: true)
        input("customHost", "text", title: "Custom Host", required: false, defaultValue: "")
        input("checkInterval", "number", title: "Check Interval (seconds)", required: true, defaultValue: 300)
        input("logLevel", "enum", title: "Logging Level", options: ["info", "debug", "warn", "off"], defaultValue: "info")
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
    // Set checkInterval event
    if (settings.checkInterval) {
        sendEvent(name: "checkInterval", value: settings.checkInterval, unit: "sec")
    }

    // Default undefined toggles to true for backward compatibility
    if (settings.checkLAN == null) app.updateSetting("checkLAN", [type: "bool", value: "true"])
    if (settings.checkCustom == null) app.updateSetting("checkCustom", [type: "bool", value: "true"])

    checkConnectivity()
}

def checkNow() {
    logInfo "Manual network check triggered"
    checkConnectivity()
}

def checkConnectivity() {
    checkHost("internet", settings.internetHost?.trim())

    if (settings.checkLAN) {
        def lan = settings.lanHost?.trim()
        if (lan) {
            checkHost("lan", lan)
        } else {
            logWarn "LAN check enabled but no host specified"
            sendEvent(name: "lan", value: "offline", descriptionText: "LAN host not specified")
        }
    } else {
        sendEvent(name: "lan", value: "disabled", descriptionText: "LAN check is disabled")
    }

    if (settings.checkCustom) {
        def custom = settings.customHost?.trim()
        if (custom) {
            checkHost("custom", custom)
        } else {
            logWarn "Custom host check enabled but no host specified"
            sendEvent(name: "custom", value: "offline", descriptionText: "Custom host not specified")
        }
    } else {
        sendEvent(name: "custom", value: "disabled", descriptionText: "Custom host check is disabled")
    }

    if (settings.checkInterval) {
        runIn(settings.checkInterval as Integer, checkConnectivity)
    }
}

def checkHost(attrName, url) {
    if (!url) return

    try {
        httpGet([uri: url, ignoreSSLIssues: true]) { response ->
            def statusCode = response?.getStatus()
            logDebug "Host ${url} responded with status ${statusCode}"
            updateIfChanged(attrName, "online", "Online - HTTP ${statusCode}")
        }
    } catch (Exception e) {
        def errorMessage = e.message ?: "Unknown error"
        def codeMatch = errorMessage =~ /status code: (\d{3})/
        if (codeMatch) {
            def code = codeMatch[0][1]
            logDebug "Host ${url} returned HTTP error code ${code}, considered online"
            updateIfChanged(attrName, "online", "Online - HTTP ${code}")
        } else {
            logWarn "Host ${url} is unreachable: ${errorMessage}"
            updateIfChanged(attrName, "offline", "Offline - ${errorMessage}")
        }
    }
}

def updateIfChanged(attrName, newValue, desc) {
    def current = device.currentValue(attrName)
    if (current != newValue) {
        sendEvent(name: attrName, value: newValue, descriptionText: desc)
        logInfo "${attrName.toUpperCase()} changed to '${newValue}' - ${desc}"
    } else {
        logDebug "${attrName.toUpperCase()} unchanged (${newValue})"
    }
}

// Logging helpers
private logInfo(msg)  { if (settings.logLevel in ["info", "debug"])  log.info msg }
private logDebug(msg) { if (settings.logLevel == "debug")           log.debug msg }
private logWarn(msg)  { if (settings.logLevel != "off")             log.warn msg }