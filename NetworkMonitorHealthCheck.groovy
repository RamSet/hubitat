/**
 *  Network Monitor HealthCheck (HTTP)
 *
 *  This driver monitors Internet, LAN, and optionally a Custom Host's connectivity via HTTP requests.
 *  It updates attributes based on reachability and supports a manual check command.
 *
 *  Version: 1.4.1
 *  Author: RamSet
 *  Last Updated: 2025-06-21
 *
 *  CHANGELOG:
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
        input("internetHost", "text", title: "Internet Host (don't forget to add http:// or https://)", required: true, defaultValue: "https://www.google.com")
        input("checkLAN", "bool", title: "Check LAN Host?", defaultValue: true)
        input("lanHost", "text", title: "LAN Host (don't forget to add http:// or https://)", required: false, defaultValue: "")
        input("checkCustom", "bool", title: "Check Custom Host? (don't forget to add http:// or https://)", defaultValue: true)
        input("customHost", "text", title: "Custom Host", required: false, defaultValue: "")
        input("checkInterval", "number", title: "Check Interval (seconds)", required: true, defaultValue: 300)
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

    if (settings.checkInterval) {
        sendEvent(name: "checkInterval", value: settings.checkInterval, unit: "sec")
    }

    if (settings.checkLAN == null) app.updateSetting("checkLAN", [type: "bool", value: true])
    if (settings.checkCustom == null) app.updateSetting("checkCustom", [type: "bool", value: true])
    if (settings.treatRefusedAsOnline == null) app.updateSetting("treatRefusedAsOnline", [type: "bool", value: false])

    checkConnectivity()
}

def checkNow() {
    logInfo "Manual network check triggered"
    checkConnectivity()
}

def checkConnectivity() {
    logInfo "Running connectivity check..."

    checkHost("internet", settings.internetHost?.trim() ?: "https://www.google.com")

    if (settings.checkLAN) {
        def lan = settings.lanHost?.trim()
        if (lan) {
            checkHost("lan", lan)
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
            checkHost("custom", custom)
        } else {
            logWarn "Custom check enabled but no host specified"
            sendEventIfChanged("custom", "offline", "Custom host not specified")
        }
    } else {
        sendEventIfChanged("custom", "disabled", "Custom check is disabled")
    }

    if (settings.checkInterval) {
        def interval = (settings.checkInterval ?: 300) as Integer
        logInfo "Scheduling next check in ${interval} seconds"
        runIn(interval, checkConnectivity)
    }
}

def checkHost(attr, url, triedHttps = false) {
    try {
        httpGet([uri: url, ignoreSSLIssues: true]) { resp ->
            def statusCode = resp?.getStatus()
            sendEventIfChanged(attr, "online", "Online - HTTP ${statusCode}")
            logInfo "${attr.toUpperCase()} check OK: ${statusCode} from ${url}"
        }
    } catch (e) {
        def msg = e?.message ?: e?.toString() ?: "Unknown error"

        if (msg.contains("ClientProtocolException") && !triedHttps && url.toLowerCase().startsWith("http://")) {
            def httpsUrl = url.replaceFirst("(?i)^http://", "https://")
            logWarn "${attr.toUpperCase()} HTTP failed, retrying as HTTPS: ${httpsUrl}"
            checkHost(attr, httpsUrl, true)
            return
        }

        def codeMatch = msg =~ /status code: (\d{3})/
        if (codeMatch) {
            def code = codeMatch[0][1]
            sendEventIfChanged(attr, "online", "Online - HTTP ${code}")
            logInfo "${attr.toUpperCase()} treated as online: HTTP ${code} from ${url}"
        } else if (msg.toLowerCase().contains("connection refused") && settings.treatRefusedAsOnline) {
            sendEventIfChanged(attr, "online", "Online - Connection refused")
            logInfo "${attr.toUpperCase()} refused connection but treated as online (${url})"
        } else {
            sendEventIfChanged(attr, "offline", "Offline - ${msg}")
            logWarn "${attr.toUpperCase()} unreachable: ${msg} (${url})"
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
    if (settings.logLevel in ["warn", "debug"]) log.warn msg
}