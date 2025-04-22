/**
 *  Network Monitor HealthCheck (HTTP) - v1.1
 *
 *  This driver monitors Internet, LAN, and optionally Custom Host connectivity using HTTP requests.
 *  It updates status attributes for each check and supports manual or scheduled health checks.
 *
 *  Author: RamSet
 *  Date: 2025-04-01
 *
 *  Changelog:
 *  - Considers any host online if it returns an HTTP status code (even 403, 404, etc.).
 *  - Treats hosts as offline only if unreachable (e.g., "No route to host").
 *  - Made LAN check optional with toggle, similar to Custom Host.
 *  - Improved logging and added detailed comments.
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
        input("checkLan", "bool", title: "Check LAN Host?", defaultValue: true)
        input("lanHost", "text", title: "LAN Host", required: false, defaultValue: "http://192.168.1.1")
        input("checkCustom", "bool", title: "Check Custom Host?", defaultValue: true)
        input("customHost", "text", title: "Custom Host", required: false, defaultValue: "")
        input("checkInterval", "number", title: "Check Interval (seconds)", required: true, defaultValue: 300)
    }
}

// Called when the driver is first installed
def installed() {
    log.info "Network Monitor HealthCheck (HTTP) installed"
    initialize()
}

// Called when preferences are updated
def updated() {
    log.info "Network Monitor HealthCheck (HTTP) updated"
    unschedule()
    initialize()
}

// Sets up scheduled checking and performs the first check
def initialize() {
    if (settings.checkInterval) {
        sendEvent(name: "checkInterval", value: settings.checkInterval, unit: "sec")
    }
    checkConnectivity()
}

// Manually trigger a connectivity check
def checkNow() {
    log.info "Manual network check triggered"
    checkConnectivity()
}

// Runs checks for Internet, LAN (if enabled), and Custom Host (if enabled)
def checkConnectivity() {
    // Check Internet Host (always required)
    checkHost("internet", settings.internetHost?.trim() ?: "https://www.google.com")

    // Check LAN Host (if enabled)
    if (settings.checkLan) {
        def lanHost = settings.lanHost?.trim()
        if (lanHost) {
            checkHost("lan", lanHost)
        } else {
            log.warn "LAN check is enabled but no host is specified"
            sendEvent(name: "lan", value: "offline", descriptionText: "LAN host not specified")
        }
    } else {
        sendEvent(name: "lan", value: "disabled", descriptionText: "LAN host check is disabled")
    }

    // Check Custom Host (if enabled)
    if (settings.checkCustom) {
        def customHost = settings.customHost?.trim()
        if (customHost) {
            checkHost("custom", customHost)
        } else {
            log.warn "Custom host check enabled but no host specified"
            sendEvent(name: "custom", value: "offline", descriptionText: "Custom host not specified")
        }
    } else {
        sendEvent(name: "custom", value: "disabled", descriptionText: "Custom host check is disabled")
    }

    // Schedule next check
    if (settings.checkInterval) {
        runIn(settings.checkInterval as Integer, checkConnectivity)
    }
}

// Performs a GET request to the specified URL and updates the corresponding attribute
def checkHost(attrName, url) {
    // Default to offline until confirmed online
    sendEvent(name: attrName, value: "offline", descriptionText: "No response yet")

    try {
        // HTTP GET with relaxed SSL rules
        httpGet([uri: url, ignoreSSLIssues: true]) { response ->
            def statusCode = response?.getStatus()
            log.debug "Host ${url} responded with status ${statusCode}"

            // Consider the host online if it returns any status code
            sendEvent(name: attrName, value: "online", descriptionText: "Online - HTTP ${statusCode}")
        }
    } catch (Exception e) {
        def errorMessage = e.message ?: "Unknown error"

        // Try extracting an HTTP code from the error message
        def codeMatch = errorMessage =~ /status code: (\d{3})/
        if (codeMatch) {
            def code = codeMatch[0][1]
            log.debug "Host ${url} returned HTTP error ${code}, considered online"
            sendEvent(name: attrName, value: "online", descriptionText: "Online - HTTP ${code}")
        } else {
            // No status code = unreachable = offline
            log.warn "Host ${url} is unreachable: ${errorMessage}"
            sendEvent(name: attrName, value: "offline", descriptionText: "Offline - ${errorMessage}")
        }
    }
}