/**
 *  Trash Reminder
 *
 *  Reminds you to put the bins out, and knows which bin it is when collection alternates
 *  between rubbish and recycling.
 *
 *  This app asks the hub what day it is. It needs no date/time device, nothing to keep
 *  such a device refreshed, and no hub variables to carry the state between rules.
 *
 *  Alternating weeks are worked out from an anchor date — one date you know recycling
 *  was collected — and not from whether the week number is odd. Week numbers are not a
 *  reliable alternator: a 53-week year puts two odd weeks back to back, which silently
 *  inverts the schedule for the following year, and what counts as week 1 depends on
 *  locale.
 */

definition(
    name:        "Trash Reminder",
    namespace:   "ramset",
    author:      "RamSet",
    description: "Reminds you to take the bins out, tracking alternating rubbish and recycling weeks",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/apps/trash-reminder/trash-reminder.groovy"
)

preferences {
    page(name: "mainPage")
}

def DAYS() { ["Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"] }

def mainPage() {
    dynamicPage(name: "mainPage", title: "Trash Reminder", install: true, uninstall: true) {
        section("Status") {
            paragraph statusHtml()
        }
        section("Collection") {
            input "collectionDay", "enum", title: "Collection day",
                  options: DAYS(), required: true, submitOnChange: true
            input "alternates", "bool", title: "Recycling and rubbish alternate week by week",
                  defaultValue: true, required: true, submitOnChange: true
            if (alternates != false) {
                input "anchorDate", "date",
                      title: "A date when RECYCLING was collected",
                      description: "Any past collection date you are sure about. Weeks are counted from here, " +
                                   "so the alternation can never drift or invert at New Year.",
                      required: true, submitOnChange: true
            }
        }
        section("Remind me") {
            paragraph "Leave a time blank to skip that reminder."
            input "remindEve",   "time", title: "The evening before", required: false
            input "remindFinal", "time", title: "The evening before, again (final reminder)", required: false
            input "remindDay",   "time", title: "On the morning of", required: false
        }
        section(hideable: true, hidden: true, "Messages") {
            paragraph "Tokens: <b>%type%</b> (Recycling / Regular trash) &nbsp; <b>%day%</b> (e.g. Thursday)"
            input "msgEve",   "textarea", title: "Evening before",  defaultValue: defaultEve(),   required: false
            input "msgFinal", "textarea", title: "Final reminder",  defaultValue: defaultFinal(), required: false
            input "msgDay",   "textarea", title: "Morning of",      defaultValue: defaultDay(),   required: false
        }
        section("Notify") {
            input "notifiers", "capability.notification",    title: "Notification devices", multiple: true, required: false
            input "speakers",  "capability.speechSynthesis", title: "Speakers to announce on", multiple: true, required: false
        }
        section(hideable: true, hidden: true, "Lights (optional)") {
            paragraph "Switch something on when the reminder fires — a porch light by the bins, say."
            input "lights",   "capability.switch", title: "Switch on with the reminder", multiple: true, required: false, submitOnChange: true
            if (lights) {
                input "lightsFor", "number", title: "Then switch off after (hours)", defaultValue: 3, required: true
            }
        }
        section("Options") {
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
        }
    }
}

def installed() { initialize() }

def updated() {
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    // One daily cron per configured reminder. Each one checks the calendar when it fires;
    // nothing has to be rescheduled as the weeks alternate.
    if (remindEve)   schedule(cronFor(remindEve),   "eveHandler")
    if (remindFinal) schedule(cronFor(remindFinal), "finalHandler")
    if (remindDay)   schedule(cronFor(remindDay),   "dayHandler")

    logDebug "initialized — next collection ${nextCollection()?.format('EEEE d MMM', location.timeZone)} (${nextType()})"
}

// Quartz: sec min hour day-of-month month day-of-week
def cronFor(timeSetting) {
    def t = timeToday(timeSetting, location.timeZone)
    return "0 ${t.format('m', location.timeZone)} ${t.format('H', location.timeZone)} * * ?"
}

// ---------------------------------------------------------------- handlers

def eveHandler()   { remindIfCollection(tomorrow(), msgEve   ?: defaultEve()) }
def finalHandler() { remindIfCollection(tomorrow(), msgFinal ?: defaultFinal()) }
def dayHandler()   { remindIfCollection(today(),    msgDay   ?: defaultDay()) }

def remindIfCollection(Date d, String template) {
    if (!isCollectionDay(d)) {
        logDebug "${d.format('EEEE', location.timeZone)} is not collection day — quiet"
        return
    }

    def msg = render(template, d)
    log.info "trash reminder: ${msg}"

    notifiers*.deviceNotification(msg)
    speakers*.speak(msg)

    if (lights) {
        lights*.on()
        runIn(((lightsFor ?: 3) as Integer) * 3600, "lightsOff")
    }
}

def lightsOff() { lights*.off() }

// ---------------------------------------------------------------- the calendar

def today()    { new Date().clearTime() }
def tomorrow() { today() + 1 }

def isCollectionDay(Date d) {
    d.format("EEEE", location.timeZone) == collectionDay
}

// The next collection day, today included.
def nextCollection() {
    if (!collectionDay) return null
    def d = today()
    for (int i = 0; i < 8; i++) {
        if (isCollectionDay(d)) return d
        d = d + 1
    }
    return null
}

// Weeks counted from the anchor, so this cannot drift, invert at New Year, or depend on
// what the locale thinks week 1 is.
def typeFor(Date d) {
    if (alternates == false) return "Regular trash"
    def a = anchor()
    if (a == null) return "unknown"

    long days  = Math.round((d.clearTime().time - a.time) / 86400000.0d)
    long weeks = Math.floorDiv(days, 7L)
    return (Math.floorMod(weeks, 2L) == 0L) ? "Recycling" : "Regular trash"
}

def nextType() {
    def n = nextCollection()
    return n == null ? "unknown" : typeFor(n)
}

// Any date inside a recycling week will do: the week maths floors to whole weeks, so the
// anchor does not have to be the collection day itself.
def anchor() {
    if (!anchorDate) return null

    def raw = anchorDate.toString().trim()
    // Do not assume what the date input hands back — take the leading yyyy-MM-dd from it
    // whether it arrives bare or as a full timestamp.
    def m = (raw =~ /(\d{4})-(\d{2})-(\d{2})/)
    if (!m.find()) {
        log.warn "could not read the anchor date '${raw}'"
        return null
    }

    def c = Calendar.getInstance(location.timeZone)
    c.clear()
    c.set(m.group(1) as Integer, (m.group(2) as Integer) - 1, m.group(3) as Integer)
    return c.getTime()
}

// ---------------------------------------------------------------- messages

def defaultEve()   { 'Tomorrow is %type% day. Take out the trash.' }
def defaultFinal() { 'Tomorrow is %type% day. Take out the trash (final reminder).' }
def defaultDay()   { 'TODAY is %type% day. Take out the trash, if you have not already.' }

def render(String tmpl, Date d) {
    (tmpl ?: '')
        .replace('%type%', typeFor(d))
        .replace('%day%',  d.format("EEEE", location.timeZone))
        .trim()
}

// ---------------------------------------------------------------- status

def statusHtml() {
    if (!collectionDay) return "<i>Pick a collection day below.</i>"

    def n = nextCollection()
    if (n == null) return "<i>No collection day set.</i>"

    def days = Math.round((n.time - today().time) / 86400000.0d) as Integer
    def when = days == 0 ? "<b>today</b>" : (days == 1 ? "<b>tomorrow</b>" : "in ${days} days")
    def type = typeFor(n)

    def rows = []
    rows << ["Next collection", "${dot(type == 'Recycling' ? '#27ae60' : '#95a5a6')} " +
                                "<b>${type}</b> &nbsp;·&nbsp; ${n.format('EEEE d MMM', location.timeZone)} &nbsp;·&nbsp; ${when}"]

    if (alternates != false) {
        if (anchor() == null) {
            rows << ["Anchor", "${dot('#e74c3c')} <b>set an anchor date below</b> — without it the app cannot tell the weeks apart"]
        } else {
            // Show the run of upcoming collections, so a wrong anchor is obvious at a glance
            // rather than discovered on the wrong Thursday.
            def upcoming = []
            def d = n
            4.times {
                upcoming << "${d.format('d MMM', location.timeZone)}: <b>${typeFor(d)}</b>"
                d = d + 7
            }
            rows << ["Coming up", upcoming.join("<br>")]
        }
    }

    def times = []
    if (remindEve)   times << "evening before"
    if (remindFinal) times << "final reminder"
    if (remindDay)   times << "morning of"
    rows << ["Reminders", times ? times.join(", ") : "<i>none set — nothing will be sent</i>"]

    if (!notifiers && !speakers) rows << ["<b>Warning</b>", "${dot('#e67e22')} no notification device or speaker selected"]

    def s = new StringBuilder("<table style='border-collapse:collapse'>")
    rows.each {
        s << "<tr><td style='padding:3px 14px 3px 0;white-space:nowrap;vertical-align:top;opacity:.6'>${it[0]}</td>"
        s << "<td style='padding:3px 0'>${it[1]}</td></tr>"
    }
    s << "</table>"
    return s.toString()
}

def dot(String color) { "<span style='color:${color};font-size:1.1em'>&#9679;</span>" }

def logDebug(msg) { if (logEnable) log.debug msg }
