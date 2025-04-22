/**
 *  Network Monitor HealthCheck (HTTP)
 *
 *  This driver monitors Internet, LAN, and optionally Custom host connectivity using HTTP requests.
 *  It implements the HealthCheck capability with a user-defined checkInterval.
 *
 *  Author: RamSet
 *  Version: 1.2
 *  Date: 2025-04-22
 *
 *  Change Log:
 *  - v1.1: Added support for custom host check with toggle
 *  - v1.2:
 *    - Made LAN check optional via toggle (same as custom host)
 *    - Improved logic to treat any HTTP status code (e.g., 403, 404) as online
 *    - Only considers host offline when no HTTP code is returned (i.e., unreachable)
 *    - Avoids triggering sendEvent unless the value has changed (prevents false alarms in Rule Machine)
 */

metadata {
    definition(
        name: "Network Monitor HealthCheck (HTTP)",
        namespace: "Mezel",
        author: "RamSet",
        importURL: "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/NetworkMonitorHealthCheck.groovy"
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
        input("lanHost", "text", title: "LAN Host", required: false, defaultValue: "http://192.168.1.1")
        input("checkCustom", "bool", title: "Check Custom Host?", defaultValue: true)
        input("customHost", "text", title: "Custom Host", required: false, defaultValue: "")
        input("checkInterval", "number", title: "Check Interval (seconds)", required: true, defaultValue: 300)
    }
}

def installed() {
    log.info "Network Monitor HealthCheck (HTTP) installed"
    initialize()
}

def updated() {
    log.info "Network Monitor HealthCheck (HTTP) updated"
    unschedule()
    initialize()
}

def initialize() {
    if (settings.checkInterval) {
        sendEvent(name: "checkInterval", value: settings.checkInterval, unit: "sec")
    }
    checkConnectivity()
}

def checkNow() {
    log.info "Manual network check triggered"
    checkConnectivity()
}

def checkConnectivity() {
    // Internet
    checkHost("internet", settings.internetHost?.trim() ?: "https://www.google.com")

    // LAN
    if (settings.checkLAN) {
        def lanHost = settings.lanHost?.trim()
        if (lanHost) {
            checkHost("lan", lanHost)
        } else {
            log.warn "LAN check enabled but no LAN host specified"
            updateAttr("lan", "offline", "LAN host not specified")
        }
    } else {
        updateAttr("lan", "disabled", "LAN host check is disabled")
    }

    // Custom
    if (settings.checkCustom) {
        def customHost = settings.customHost?.trim()
        if (customHost) {
            checkHost("custom", customHost)
        } else {
            log.warn "Custom check enabled but no host specified"
            updateAttr("custom", "offline", "Custom host not specified")
        }
    } else {
        updateAttr("custom", "disabled", "Custom host check is disabled")
    }

    // Schedule next check
    if (settings.checkInterval) {
        runIn(settings.checkInterval as Integer, checkConnectivity)
    }
}

def checkHost(attrName, url) {
    try {
        httpGet([uri: url, ignoreSSLIssues: true]) { response ->
            def code = response?.getStatus()
            log.debug "Host ${url} responded with HTTP ${code}"
            updateAttr(attrName, "online", "Online - HTTP ${code}")
        }
    } catch (Exception e) {
        def msg = e.message ?: "Unknown error"
        def codeMatch = msg =~ /status code: (\d{3})/
        if (codeMatch) {
            def code = codeMatch[0][1]
            log.debug "Host ${url} returned HTTP error ${code}, considering it online"
            updateAttr(attrName, "online", "Online - HTTP ${code}")
        } else {
            log.warn "Host ${url} is unreachable: ${msg}"
            updateAttr(attrName, "offline", "Offline - ${msg}")
        }
    }
}

// Only update attribute if value has changed
def updateAttr(name, value, description) {
    def current = device.currentValue(name)
    if (current != value) {
        sendEvent(name: name, value: value, descriptionText: description)
    } else {
        log.debug "No change for ${name} (still ${value})"
    }
}