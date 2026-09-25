/**
 *  Network Monitor HealthCheck (HTTP)
 *
 *  This driver monitors Internet, LAN, and optionally a Custom Host's connectivity via HTTP requests.
 *  It updates attributes based on reachability and supports a manual check command.
 *
 *  Version: 1.5.0
 *  Author: RamSet
 *  Last Updated: 2026-09-21
 *
 *  CHANGELOG:
 *  v1.5.0
 *   - New optional "Check Interval While Offline" setting: while any checked host is
 *     unreachable, checks run at this shorter interval so recovery is detected sooner.
 *     Leave it blank to keep a single interval.
 *
 *  v1.4.2
 *   - Fixed a crash on fresh install: used app.updateSetting() (an app-only call)
 *     inside a driver; now uses device.updateSetting().
 *   - Warnings now appear at the "info" log level (were hidden unless set to warn/debug).
 *   - Removed a stray event for the undeclared "checkInterval" attribute.
 *
 *  v1.4.1
 *   - Fixed runIn() interval issues by forcing Integer cast and logging scheduled checks.
 *   - Replaced logLevel references with settings.logLevel.
 *   - Added clearer logging for initialize and connectivity execution.
 *
 *  v1.4
 *   - Added toggle to treat "connection refused" as online.
 *   - Only unreachable hosts are considered offline if toggle is enabled.
 *
 *  v1.3
 *   - Added logging preferences (info, debug, warn, or off).
 *   - Prevented default LAN Host value from being used when checks are off.
 *   - Fixed behavior where LAN Host was required even if LAN check was disabled.
 *
 *  v1.2
 *   - Added option to disable LAN check just like custom check.
 *   - Avoid repeated event triggers unless value actually changes.
 *   - Retained previous changelog entries and enhancements.
 *
 *  v1.1
 *   - Improved error handling for unreachable hosts.
 *   - Responses with any status code are considered online unless no code is returned.
 *   - Custom host check now works properly with empty/invalid URLs.
 *
 *  v1.0 (2025-04-01)
 *   - Initial release with Internet, LAN, and optional custom host checks.
 */

metadata {
    definition(
        name: "Network Monitor HealthCheck (HTTP)",
        namespace: "RamSet",
        author: "RamSet",
        importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/drivers/NetworkMonitorHealthCheck/NetworkMonitorHealthCheck.groovy"
    ) {
        capability "Sensor"
        capability "Actuator"
        attribute "internet", "string"
        attribute "lan", "string"
        attribute "custom", "string"
        command "checkNow"
    }

    preferences {
        input("internetHost", "text", title: "Internet Host (don't forget to add http:// or https://)", required: true, defaultValue: "https://www.google.com")
        input("checkLAN", "bool", title: "Check LAN Host?", defaultValue: true)
        input("lanHost", "text", title: "LAN Host (don't forget to add http:// or https://)", required: false, defaultValue: "")
        input("checkCustom", "bool", title: "Check Custom Host? (don't forget to add http:// or https://)", defaultValue: true)
        input("customHost", "text", title: "Custom Host", required: false, defaultValue: "")
        input("checkInterval", "number", title: "Check Interval (seconds)", required: true, defaultValue: 300)
        input("offlineCheckInterval", "number", title: "Check Interval While Offline (seconds)", description: "Used while any checked host is unreachable, so recovery is detected sooner. Leave blank to use the normal interval.", required: false, range: "10..86400")
        input("treatRefusedAsOnline", "bool", title: "Treat 'Connection Refused' as Online?", description: "If enabled, only unreachable hosts are considered offline. All other responses, including connection refused, are considered online.", defaultValue: false)
        input("logLevel", "enum", title: "Logging Level", options: ["info", "debug", "warn", "off"], defaultValue: "info")
    }
}

def installed() {
    logInfo "Installed"
    initialize()
}

def updated() {
    logInfo "Updated"
    unschedule()
    initialize()
}

def initialize() {
    logInfo "Initializing network monitor..."

    if (settings.checkLAN == null) device.updateSetting("checkLAN", [type: "bool", value: true])
    if (settings.checkCustom == null) device.updateSetting("checkCustom", [type: "bool", value: true])
    if (settings.treatRefusedAsOnline == null) device.updateSetting("treatRefusedAsOnline", [type: "bool", value: false])

    checkConnectivity()
}

def checkNow() {
    logInfo "Manual network check triggered"
    checkConnectivity()
}

def checkConnectivity() {
    logInfo "Running connectivity check..."

    // A host counts as down only when it was actually checked and unreachable — a missing
    // host setting is a configuration problem, not an outage, and must not pin the fast interval.
    boolean anyOffline = !checkHost("internet", settings.internetHost?.trim() ?: "https://www.google.com")

    if (settings.checkLAN) {
        def lan = settings.lanHost?.trim()
        if (lan) {
            if (!checkHost("lan", lan)) anyOffline = true
        } else {
            logWarn "LAN check enabled but no host specified"
            sendEventIfChanged("lan", "offline", "LAN host not specified")
        }
    } else {
        sendEventIfChanged("lan", "disabled", "LAN check is disabled")
    }

    if (settings.checkCustom) {
        def custom = settings.customHost?.trim()
        if (custom) {
            if (!checkHost("custom", custom)) anyOffline = true
        } else {
            logWarn "Custom check enabled but no host specified"
            sendEventIfChanged("custom", "offline", "Custom host not specified")
        }
    } else {
        sendEventIfChanged("custom", "disabled", "Custom check is disabled")
    }

    Integer interval = settings.checkInterval ? (settings.checkInterval as Integer) : null
    if (anyOffline && settings.offlineCheckInterval) interval = settings.offlineCheckInterval as Integer
    if (interval) {
        logInfo "Scheduling next check in ${interval} seconds${anyOffline && settings.offlineCheckInterval ? ' (offline interval)' : ''}"
        runIn(interval, checkConnectivity)
    }
}

// Returns true when the host is treated as online.
boolean checkHost(attr, url, triedHttps = false) {
    try {
        httpGet([uri: url, ignoreSSLIssues: true]) { resp ->
            def statusCode = resp?.getStatus()
            sendEventIfChanged(attr, "online", "Online - HTTP ${statusCode}")
            logInfo "${attr.toUpperCase()} check OK: ${statusCode} from ${url}"
        }
        return true
    } catch (e) {
        def msg = e?.message ?: e?.toString() ?: "Unknown error"

        if (msg.contains("ClientProtocolException") && !triedHttps && url.toLowerCase().startsWith("http://")) {
            def httpsUrl = url.replaceFirst("(?i)^http://", "https://")
            logWarn "${attr.toUpperCase()} HTTP failed, retrying as HTTPS: ${httpsUrl}"
            return checkHost(attr, httpsUrl, true)
        }

        def codeMatch = msg =~ /status code: (\d{3})/
        if (codeMatch) {
            def code = codeMatch[0][1]
            sendEventIfChanged(attr, "online", "Online - HTTP ${code}")
            logInfo "${attr.toUpperCase()} treated as online: HTTP ${code} from ${url}"
            return true
        } else if (msg.toLowerCase().contains("connection refused") && settings.treatRefusedAsOnline) {
            sendEventIfChanged(attr, "online", "Online - Connection refused")
            logInfo "${attr.toUpperCase()} refused connection but treated as online (${url})"
            return true
        } else {
            sendEventIfChanged(attr, "offline", "Offline - ${msg}")
            logWarn "${attr.toUpperCase()} unreachable: ${msg} (${url})"
            return false
        }
    }
}

def sendEventIfChanged(name, value, desc) {
    def current = device.currentValue(name)
    if (current != value) {
        sendEvent(name: name, value: value, descriptionText: desc)
        logInfo "Updated ${name} to ${value} (${desc})"
    } else {
        logDebug "No change for ${name}, remains ${value}"
    }
}

def logInfo(msg) {
    if (settings.logLevel in ["info", "debug"]) log.info msg
}

def logDebug(msg) {
    if (settings.logLevel == "debug") log.debug msg
}

def logWarn(msg) {
    if (settings.logLevel in ["info", "warn", "debug"]) log.warn msg
}