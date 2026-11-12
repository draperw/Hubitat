/*
 *  Lutron LEAP Fast Pico (User)
 *  A Lutron Pico remote with nothing timed: pushed when the bridge reports the Press, released when it reports the
 *  Release, the earliest events LEAP allows (it reports nothing in between). For rules that want the press at once;
 *  Lutron LEAP Pico (User) adds held and doubleTapped. Either driver can be chosen on the device page: the bridge
 *  times events by the device's driver.
 *  A device of the Lutron LEAP Integration (User) app. Push presses the button on the bridge, so the Pico's Lutron
 *  action runs; the event comes back from the bridge (digital). Buttons are numbered as in Lutron LEAP Pico (User):
 *  on 1, raise 2, favourite 3, lower 4, off 5 (a 2-button or paddle Pico on 1, off 2), as Hubitat's Lutron integrations.
 *  Not single-threaded, and writes no state in commands.
 *  Used with the app, which needs Hubitat 2.5.0 or later. Tested on 2.5.3.
 *
 *  Changelog:
 *  ## [1.0.1] - 2026-11-12
 *    - Buttons numbered as Hubitat's Lutron integrations (Telnet and LEAP): on 1, raise 2, favourite 3, lower 4, off 5;
 *      a 2-button or paddle Pico on 1, off 2.
 *    - A button the Pico doesn't have (3-5 on a 2-button or paddle Pico) is refused: Push sends nothing, and the test
 *      commands make no event.
 *    - Debug logging ends at its deadline also when its timer was lost (checked when an event comes from the bridge).
 *  ## [1.0.0] - 2026-11-10
 *    - First release. A Pico with no timing: pushed at the press, released at the release; Push presses the button on the
 *      bridge.
 *
 *  Copyright 2026 Bill Draper
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is
 *  distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied. See the License for the specific language governing permissions and limitations under the
 *  License.
 *
 *  The logging functions in this file are adapted from Jeff Page's (@jtp10181) Hubitat drivers
 *  (github.com/jtp10181/Hubitat, Drivers/libraries/zwaveDriverLibrary.groovy, Apache-2.0).
 */
import groovy.transform.Field

@Field static final String VERSION = "1.0.1"
@Field static final String DRIVER  = "Lutron LEAP Fast Pico (User)"

metadata {
    definition(name: DRIVER, namespace: "draperw", author: "Bill Draper", component: true,
               importUrl: "https://raw.githubusercontent.com/draperw/Hubitat/main/drivers/Lutron-LEAP/Lutron%20LEAP%20Fast%20Pico%20(User).groovy") {
        capability "Actuator"
        capability "PushableButton"
        capability "ReleasableButton"

        attribute "healthStatus", "enum", ["unknown", "online", "offline"]
    }
    preferences {
        input name: "logLevel", type: "enum", title: fmtTitle("Logging Level"),
            description: fmtDesc("Logs selected level and above"), defaultValue: 2, options: LOG_LEVELS
        input name: "logLevelTime", type: "enum", title: fmtTitle("Logging Level Time"),
            description: fmtDesc("Time to enable Debug/Trace logging"), defaultValue: 30, options: LOG_TIMES
    }
}

/*******************************************************************
 ***** Lifecycle
********************************************************************/
// Runs while the app is creating this device: no calls back into the app from here. The app sets
// numberOfButtons right after.
void installed() {
    logWarn "installed..."
    checkLogsOff()
    state.driverVersion = VERSION
}

void updated() {
    logDebug "updated..."
    state.driverVersion = VERSION
    checkLogLevel()
}

/*******************************************************************
 ***** Commands
********************************************************************/
// Push presses the button on the bridge; the event comes back from the bridge (digital)
void push(Object n) { Integer b = buttonOf("pushed", n); if (b != null) parent?.componentPush(device, b, 1) }
// LEAP has no release command: an event only, for testing rules (type digital)
void release(Object n) {
    Integer b = buttonOf("released", n)
    if (b == null) return
    sendEventLog([name: "released", value: b, isStateChange: true, type: "digital", descriptionText: "${device.displayName} button ${b} was released".toString()])
}

// A Pico has only some of the numbers (a 2-button or paddle Pico is 1 and 2), so a button is checked against its own list
// when the app has set it, and against 1..numberOfButtons otherwise
Integer buttonOf(String name, Object n) {
    Integer b = intOrNull(n)
    List<Integer> btns = picoButtons()
    Integer count = intOrNull(device.currentValue("numberOfButtons"))
    boolean ok = b != null && b >= 1 && (btns ? (b in btns) : (count == null || b <= count))
    if (!ok) {
        logWarn "${name}: button ${n}? (${btns ? 'buttons ' + btns.join(', ') : '1-' + (count ?: '?')})"
        return null
    }
    return b
}

// The Pico's buttons, from the data value the app sets ("1:0,5:2", button:Lutron number)
List<Integer> picoButtons() {
    String v = device?.getDataValue("lutronButtons")
    return (v ?: "").tokenize(",").collect { intOrNull(it.tokenize(":")[0]) }.findAll { it != null }
}

/*******************************************************************
 ***** Events from the bridge
********************************************************************/
void parse(List<Map> evts) {
    checkLogsOff()
    evts?.each { Map e -> sendEventLog(e) }
}

void parse(String description) {
    logWarn "parse(String) isn't used: events come from the bridge"
}

// One event sender: Info on change (and for button events, which are always events), Debug when unchanged
void sendEventLog(Map evt) {
    Map e = new LinkedHashMap(evt)
    if (!e.descriptionText) e.descriptionText = "${device.displayName} ${e.name} is ${e.value}".toString()
    if (device.currentValue(e.name)?.toString() != e.value?.toString() || e.isStateChange) logInfo "${e.descriptionText}"
    else logDebug "${e.descriptionText} [NOT CHANGED]"
    sendEvent(e)
}

Integer intOrNull(Object v) {
    try { return (v == null || v.toString().trim() == "") ? null : new BigDecimal(v.toString().trim()).intValue() } catch (Exception e) { return null }
}

/*******************************************************************
 ***** Logging Functions (adapted from jtp10181's zwaveDriverLibrary.groovy, Apache-2.0)
********************************************************************/
@Field static final Map LOG_LEVELS = [0:"Error", 1:"Warn", 2:"Info", 3:"Debug", 4:"Trace"]
@Field static final Map LOG_TIMES = [0:"Indefinitely", 30:"30 Minutes", 60:"1 Hour", 120:"2 Hours", 180:"3 Hours", 360:"6 Hours", 720:"12 Hours", 1440:"24 Hours"]

//Call this function from within updated() and configure() with no parameters: checkLogLevel()
void checkLogLevel(Map levelInfo = [level:null, time:null]) {
    unschedule("logsOff")
    state.remove("logsOffAt")
    //Set Defaults
    if (settings.logLevel == null) {
        device.updateSetting("logLevel",[value:"2", type:"enum"])
        levelInfo.level = 2
    }
    if (settings.logLevelTime == null) {
        device.updateSetting("logLevelTime",[value:"30", type:"enum"])
        levelInfo.time = 30
    }
    //Schedule turn off and log as needed
    // what wasn't just defaulted comes from the settings: a level defaulted beside a stored time still gets its timer
    Map stored = getLogLevelInfo()
    if (levelInfo.level == null) levelInfo.level = stored.level
    if (levelInfo.time == null) levelInfo.time = stored.time
    String logMsg = "Logging Level is: ${LOG_LEVELS[levelInfo.level]} (${levelInfo.level})"
    if (levelInfo.level >= 3 && levelInfo.time > 0) {
        logMsg += " for ${LOG_TIMES[levelInfo.time]}"
        runIn(60*levelInfo.time, "logsOff")
        state.logsOffAt = now() + 60000L * levelInfo.time           // the deadline, kept apart from the job (checkLogsOff)
    }
    logInfo(logMsg)

    //Store last level below Debug
    if (levelInfo.level <= 2) state.lastLogLevel = levelInfo.level
}

Map getLogLevelInfo() {
    Integer level = settings.logLevel != null ? settings.logLevel as Integer : 2
    Integer time = settings.logLevelTime != null ? settings.logLevelTime as Integer : 30
    return [level: level, time: time]
}

void logsOff() {
    state.remove("logsOffAt")
    logWarn "Debug and Trace logging disabled..."
    if (logLevelInfo.level >= 3) {
        Integer lastLvl = state.lastLogLevel != null ? state.lastLogLevel as Integer : 2
        device.updateSetting("logLevel",[value:lastLvl.toString(), type:"enum"])
        logWarn "Logging Level is: ${LOG_LEVELS[lastLvl]} (${lastLvl})"
    }
}

// Debug and Trace end at their deadline even with no job to end them: the hub stores a preference's default when it
// creates a device, without running updated(), and a job can be lost. Called where this code runs anyway.
void checkLogsOff() {
    Map info = getLogLevelInfo()
    if (info.level < 3 || info.time <= 0) return
    if (state.logsOffAt == null) checkLogLevel()          // no deadline yet: its time starts now
    else if (now() >= (state.logsOffAt as Long)) logsOff()
}

void logErr(String msg) {
    log.error "${device.displayName}: ${msg}"
}
void logWarn(String msg) {
    if (logLevelInfo.level>=1) log.warn "${device.displayName}: ${msg}"
}
void logInfo(String msg) {
    if (logLevelInfo.level>=2) log.info "${device.displayName}: ${msg}"
}
void logDebug(String msg) {
    if (logLevelInfo.level>=3) log.debug "${device.displayName}: ${msg}"
}
void logTrace(String msg) {
    if (logLevelInfo.level>=4) log.trace "${device.displayName}: ${msg}"
}

//Preference formatting helpers
String fmtTitle(String str) {
    return "<strong>${str}</strong>"
}
String fmtDesc(String str) {
    if (firmwareAtLeast("2.4.0.0")) {
        return "<div style='font-style: italic; padding: 0px 0px 4px 6px; line-height:1.4;'>${str}</div>"
    } else {
        return "<div style='font-size: 85%; font-style: italic; padding: 1px 0px 4px 2px;'>${str}</div>"
    }
}

Boolean firmwareAtLeast(String min) {
    List<Integer> have = location.hub.firmwareVersionString.tokenize('.')*.toInteger()
    List<Integer> want = min.tokenize('.')*.toInteger()
    for (int i = 0; i < Math.max(have.size(), want.size()); i++) {
        int h = i < have.size() ? have[i] : 0, w = i < want.size() ? want[i] : 0
        if (h != w) return h > w
    }
    return true
}
