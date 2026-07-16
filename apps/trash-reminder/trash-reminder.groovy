/**
 *  Trash Reminder
 *
 *  Reminds you to put the bins out, and tells you which bins.
 *
 *  Collections are modelled as independent streams sharing a collection day. Each stream
 *  has its own frequency: garbage might go out every week while recycling goes out every
 *  second week, in which case a recycling week means both bins, not one instead of the
 *  other. Change a frequency and the reminders follow.
 *
 *  This app asks the hub what day it is. It needs no date/time device, nothing to keep
 *  such a device refreshed, and no hub variables to carry state between rules.
 *
 *  A stream that repeats every N weeks is worked out by counting weeks from an anchor
 *  date — a collection you know happened — and not from whether the week number is odd.
 *  Week numbers are not a reliable alternator: a 53-week year puts two odd weeks back to
 *  back, which silently inverts the schedule for the year that follows, and what counts
 *  as week 1 depends on locale.
 */

definition(
    name:        "Trash Reminder",
    namespace:   "ramset",
    author:      "RamSet",
    description: "Reminds you to take the bins out, tracking each collection stream on its own frequency",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/apps/trash-reminder/trash-reminder.groovy"
)

preferences {
    page(name: "mainPage")
}

def DAYS()  { ["Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"] }
def EVERY() { ["1": "Every collection", "2": "Every 2nd", "3": "Every 3rd", "4": "Every 4th"] }

def mainPage() {
    dynamicPage(name: "mainPage", title: "Trash Reminder", install: true, uninstall: true) {
        section("Status") {
            paragraph statusHtml()
        }
        section("Collection day") {
            input "collectionDay", "enum", title: "Bins go out on", options: DAYS(), required: true, submitOnChange: true
        }
        section("What goes out, and how often") {
            paragraph "Each stream is independent. If garbage is every week and recycling every second week, " +
                      "then on a recycling week <b>both</b> go out — and the reminder says so."

            input "trashOn", "bool", title: "Garbage", defaultValue: true, submitOnChange: true
            if (trashOn != false) {
                input "trashEvery", "enum", title: "collected", options: EVERY(), defaultValue: "1", required: true, submitOnChange: true, width: 6
                if ((trashEvery ?: "1") != "1") {
                    input "trashAnchor", "date", title: "...counting from this collection date", required: true, submitOnChange: true, width: 6
                }
                input "trashLights", "capability.switch", title: "Light for garbage (optional)", multiple: true, required: false, submitOnChange: true
            }

            input "recycleOn", "bool", title: "Recycling", defaultValue: true, submitOnChange: true
            if (recycleOn != false) {
                input "recycleEvery", "enum", title: "collected", options: EVERY(), defaultValue: "2", required: true, submitOnChange: true, width: 6
                if ((recycleEvery ?: "2") != "1") {
                    input "recycleAnchor", "date", title: "...counting from this collection date", required: true, submitOnChange: true, width: 6
                }
                input "recycleLights", "capability.switch", title: "Light for recycling (optional)", multiple: true, required: false, submitOnChange: true
            }

            if (allLights()) {
                paragraph "Lights come on <b>the day before</b> collection, on their own schedule — independent of the " +
                          "reminders below. Each stream's light comes on only when <b>that</b> stream is actually due, " +
                          "so on a garbage-only week the recycling light stays off, and on a double week both come on."
                input "lightsAt",  "time",   title: "Switch the lights on at (the day before)", defaultValue: "07:00", required: true, width: 6
                input "lightsFor", "number", title: "and off again after (hours)", defaultValue: 12, required: true, width: 6
            }
        }
        section("Remind me") {
            paragraph "Leave a time blank to skip that reminder. Nothing is sent if nothing goes out that week."
            input "remindEve",   "time", title: "The evening before", required: false
            input "remindFinal", "time", title: "The evening before, again (final reminder)", required: false
            input "remindDay",   "time", title: "On the morning of", required: false
        }
        section(hideable: true, hidden: true, "Messages") {
            paragraph "Tokens: <b>%what%</b> what goes out, e.g. <i>Garbage and Recycling</i> &nbsp; " +
                      "<b>%day%</b> e.g. <i>Thursday</i>"
            input "msgEve",   "textarea", title: "Evening before", defaultValue: defaultEve(),   required: false
            input "msgFinal", "textarea", title: "Final reminder", defaultValue: defaultFinal(), required: false
            input "msgDay",   "textarea", title: "Morning of",     defaultValue: defaultDay(),   required: false
        }
        section("Notify") {
            input "notifiers", "capability.notification",    title: "Notification devices", multiple: true, required: false
            input "speakers",  "capability.speechSynthesis", title: "Speakers to announce on", multiple: true, required: false
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
    // One daily cron per configured reminder. Each checks the calendar when it fires, so
    // nothing needs rescheduling as the streams cycle.
    if (remindEve)   schedule(cronFor(remindEve),   "eveHandler")
    if (remindFinal) schedule(cronFor(remindFinal), "finalHandler")
    if (remindDay)   schedule(cronFor(remindDay),   "dayHandler")

    // The lights run on their own clock, not off the back of a reminder.
    if (allLights() && lightsAt) schedule(cronFor(lightsAt), "lightsHandler")

    def n = nextCollection()
    logDebug "initialized — next collection ${n?.format('EEEE d MMM', location.timeZone)}: ${whatText(n)}"
}

// Quartz: sec min hour day-of-month month day-of-week
def cronFor(timeSetting) {
    def t = timeToday(timeSetting, location.timeZone)
    return "0 ${t.format('m', location.timeZone)} ${t.format('H', location.timeZone)} * * ?"
}

// ---------------------------------------------------------------- streams

// Each stream: what it is, how often, the date to count from, and its own light.
def streams() {
    def out = []
    if (trashOn   != false) out << [name: "Garbage",   every: ((trashEvery   ?: "1") as Integer), anchor: parseDate(trashAnchor),   lights: trashLights]
    if (recycleOn != false) out << [name: "Recycling", every: ((recycleEvery ?: "2") as Integer), anchor: parseDate(recycleAnchor), lights: recycleLights]
    return out
}

def allLights() { (trashLights ?: []) + (recycleLights ?: []) }

// Every collection means always. Otherwise count whole weeks from the anchor — immune to
// 53-week years, to locale, and to drift.
def isDue(Date d, Map s) {
    if (s.every <= 1)     return true
    if (s.anchor == null) return false

    long days  = Math.round((d.clearTime().time - s.anchor.time) / 86400000.0d)
    long weeks = Math.floorDiv(days, 7L)
    return Math.floorMod(weeks, (long) s.every) == 0L
}

def whatGoesOut(Date d) {
    d == null ? [] : streams().findAll { isDue(d, it) }*.name
}

// "Garbage", "Garbage and Recycling", "Garbage, Recycling and Garden"
def whatText(Date d) {
    def w = whatGoesOut(d)
    if (!w)            return "nothing"
    if (w.size() == 1) return w[0]
    return "${w[0..-2].join(', ')} and ${w[-1]}"
}

// ---------------------------------------------------------------- handlers

def eveHandler()   { remind(tomorrow(), msgEve   ?: defaultEve()) }
def finalHandler() { remind(tomorrow(), msgFinal ?: defaultFinal()) }
def dayHandler()   { remind(today(),    msgDay   ?: defaultDay()) }

def remind(Date d, String template) {
    def due = dueOn(d)
    if (due == null) return

    def msg = render(template, d)
    log.info "trash reminder: ${msg}"

    notifiers*.deviceNotification(msg)
    speakers*.speak(msg)
}

// Fires the day before collection, at whatever time the lights are set to. Only the
// streams actually going out get lit — on a dual-bin day, that is both.
def lightsHandler() {
    def due = dueOn(tomorrow())
    if (due == null) return

    def on = due.collectMany { it.lights ?: [] }
    if (!on) {
        logDebug "nothing due tomorrow has a light"
        return
    }

    logDebug "lighting ${on*.displayName.join(', ')} for ${lightsFor ?: 12}h (${due*.name.join(' and ')} tomorrow)"
    on*.on()
    runIn(((lightsFor ?: 12) as Integer) * 3600, "lightsOff")
}

// Switch off everything we could have lit, not just this run's streams — cheaper than
// remembering, and a light that is already off does not care.
def lightsOff() { allLights()*.off() }

// The streams due on that date, or null if it is not a collection day at all or nothing
// is due on it.
def dueOn(Date d) {
    if (!isCollectionDay(d)) {
        logDebug "${d.format('EEEE', location.timeZone)} is not collection day — quiet"
        return null
    }
    def due = streams().findAll { isDue(d, it) }
    if (!due) {
        logDebug "collection day but nothing is due — quiet"
        return null
    }
    return due
}

// ---------------------------------------------------------------- the calendar

def today()    { new Date().clearTime() }
def tomorrow() { today() + 1 }

def isCollectionDay(Date d) {
    collectionDay && d.format("EEEE", location.timeZone) == collectionDay
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

// Do not assume what the date input hands back — take the leading yyyy-MM-dd from it
// whether it arrives bare or as a full timestamp.
def parseDate(v) {
    if (!v) return null
    def m = (v.toString().trim() =~ /(\d{4})-(\d{2})-(\d{2})/)
    if (!m.find()) {
        log.warn "could not read the date '${v}'"
        return null
    }
    def c = Calendar.getInstance(location.timeZone)
    c.clear()
    c.set(m.group(1) as Integer, (m.group(2) as Integer) - 1, m.group(3) as Integer)
    return c.getTime()
}

// ---------------------------------------------------------------- messages

def defaultEve()   { 'Tomorrow is %what% day. Take it out.' }
def defaultFinal() { 'Tomorrow is %what% day. Take it out (final reminder).' }
def defaultDay()   { 'TODAY is %what% day. Take it out, if you have not already.' }

def render(String tmpl, Date d) {
    def what = whatText(d)
    (tmpl ?: '')
        .replace('%what%', what)
        .replace('%type%', what)   // legacy alias: earlier versions used %type%
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
    def what = whatGoesOut(n)

    def rows = []
    rows << ["Next collection", "${dot(what ? '#27ae60' : '#95a5a6')} <b>${whatText(n)}</b> &nbsp;·&nbsp; " +
                                "${n.format('EEEE d MMM', location.timeZone)} &nbsp;·&nbsp; ${when}"]

    def needsAnchor = streams().findAll { it.every > 1 && it.anchor == null }
    if (needsAnchor) {
        rows << ["Anchor", "${dot('#e74c3c')} <b>${needsAnchor*.name.join(', ')}</b> repeats every few weeks but has no " +
                           "date to count from — set one below, or it will never be due"]
    }

    // Print the run ahead: a wrong anchor is then obvious here, rather than discovered on
    // the wrong morning.
    def upcoming = []
    def d = n
    6.times {
        upcoming << "${d.format('EEE d MMM', location.timeZone)}: <b>${whatText(d)}</b>"
        d = d + 7
    }
    rows << ["Coming up", upcoming.join("<br>")]

    def times = []
    if (remindEve)   times << "evening before"
    if (remindFinal) times << "final reminder"
    if (remindDay)   times << "morning of"
    rows << ["Reminders", times ? times.join(", ") : "<i>none set — nothing will be sent</i>"]

    if (allLights()) {
        def lit  = streams().findAll { isDue(n, it) && it.lights }
        def eve  = n - 1
        def at   = lightsAt ? timeToday(lightsAt, location.timeZone).format('h:mm a', location.timeZone) : "?"
        rows << ["Lights", lit
            ? "<b>${lit.collectMany { it.lights }*.displayName.join(', ')}</b> on at ${at} " +
              "${eve.format('EEEE', location.timeZone)} (the day before), off after ${lightsFor ?: 12}h &nbsp;·&nbsp; " +
              "for ${lit*.name.join(' and ')}"
            : "<i>nothing due next collection has a light</i>"]
    }

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
