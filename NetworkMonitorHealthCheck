/**
 *  Network Monitor HealthCheck (HTTP)
 *
 *  This driver monitors both Internet and LAN connectivity using HTTP requests.
 *  It implements the HealthCheck capability with a user-defined checkInterval.
 *
 *  The driver sends an HTTP GET request to both a defined Internet and LAN host.
 *  If a response is received, the corresponding attribute is updated to "online."
 *  If no response is received, the attribute remains "offline."
 *
 *  Additionally, it optionally monitors Custom Host connectivity.
 *  If the Custom Host check is enabled, it sends an HTTP GET request to the defined custom host.
 *  If the Custom Host check is disabled, the custom host attribute is set to "disabled."
 *
 *  Author: RamSet
 *  Date: 2025-04-01
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
        input("lanHost", "text", title: "LAN Host", required: true, defaultValue: "http://192.168.1.1")
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

// Called when the driver settings are updated
def updated() {
    log.info "Network Monitor HealthCheck (HTTP) updated"
    unschedule()
    initialize()
}

// Sets up the check interval and starts the first connectivity check
def initialize() {
    if (settings.checkInterval) {
        sendEvent(name: "checkInterval", value: settings.checkInterval, unit: "sec")
    }
    checkConnectivity()
}

// Manually trigger a network check via button or command
def checkNow() {
    log.info "Manual network check triggered"
    checkConnectivity()
}

// Runs connectivity checks for Internet, LAN, and Custom hosts
def checkConnectivity() {
    // Check the Internet host
    checkHost("internet", settings.internetHost?.trim() ?: "https://www.google.com")

    // Check the LAN host
    checkHost("lan", settings.lanHost?.trim() ?: "http://192.168.1.1")

    // Check the custom host if enabled
    if (settings.checkCustom) {
        def customHost = settings.customHost?.trim()
        if (customHost) {
            checkHost("custom", customHost)
        } else {
            log.warn "Custom host check enabled but no host specified."
            sendEvent(name: "custom", value: "offline", descriptionText: "Custom host not specified")
        }
    } else {
        // Mark custom host check as disabled if not enabled
        sendEvent(name: "custom", value: "disabled", descriptionText: "Custom host check is disabled")
    }

    // Schedule the next check after the user-defined interval
    if (settings.checkInterval) {
        runIn(settings.checkInterval as Integer, checkConnectivity)
    }
}

// Helper method to check a specific host and update its status
def checkHost(attrName, url) {
    // Set attribute to offline until a response is confirmed
    sendEvent(name: attrName, value: "offline", descriptionText: "No response yet")

    try {
        // Send HTTP GET request to host
        httpGet([uri: url, ignoreSSLIssues: true]) { response ->
            def statusCode = response?.getStatus()
            log.debug "Host ${url} responded with status ${statusCode}"

            // Mark host as online with the status code
            sendEvent(name: attrName, value: "online", descriptionText: "Online - HTTP ${statusCode}")
        }
    } catch (Exception e) {
        def errorMessage = e.message ?: "Unknown error"

        // Attempt to extract HTTP code from error message (e.g., 403, 404)
        def codeMatch = errorMessage =~ /status code: (\d{3})/
        if (codeMatch) {
            def code = codeMatch[0][1]
            log.debug "Host ${url} returned error code ${code}, considering it online"
            sendEvent(name: attrName, value: "online", descriptionText: "Online - HTTP ${code}")
        } else {
            // If no status code, treat it as unreachable
            log.warn "Host ${url} is unreachable: ${errorMessage}"
            sendEvent(name: attrName, value: "offline", descriptionText: "Offline - ${errorMessage}")
        }
    }
}
