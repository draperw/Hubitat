/*
 *  Lutron LEAP Pico (User)
 *  A Lutron Pico remote. A device of the Lutron LEAP Integration (User) app: the Lutron LEAP Bridge (User) device
 *  subscribes to its buttons and turns each Press and Release into pushed, held, released or doubleTapped, timed by
 *  this device's preferences. Buttons are numbered from 1 (LEAP ButtonNumber + 1): 1-5 on a 3-button Pico with
 *  raise / lower. Push and Double Tap press the button on the bridge, so the Pico's Lutron action runs; the event
 *  comes back from the bridge. Not single-threaded, and writes no state in commands.
 *  Used with the app, which needs Hubitat 2.5.0 or later. Tested on 2.5.3.
 *
 *  Changelog:
 *  ## [1.0.0] - 2026-11-10
 *    - First release. A Pico: pushed, held, released and (optional, per Pico) double tapped, with per-Pico hold and
 *      double-tap timing; Push and Double Tap press the button on the bridge.
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

@Field static final String VERSION = "1.0.0"
@Field static final String DRIVER  = "Lutron LEAP Pico (User)"

// Read by the bridge on every press; it uses the same defaults while a preference is unsaved. Measured on a Caseta
// bridge: quick presses 220-440 ms, natural holds 2.8-3.1 s, the gap in a double tap about 60 ms.
@Field static final Map HOLD_TIMES = ["500": "0.5 s", "750": "0.75 s", "1000": "1 s", "1500": "1.5 s", "2000": "2 s", "3000": "3 s"]
@Field static final Map TAP_WINDOWS = ["0": "Off", "250": "0.25 s", "300": "0.3 s", "400": "0.4 s", "500": "0.5 s", "750": "0.75 s"]

metadata {
    definition(name: DRIVER, namespace: "draperw", author: "Bill Draper", component: true,
               importUrl: "https://raw.githubusercontent.com/draperw/Hubitat/main/drivers/Lutron-LEAP/Lutron%20LEAP%20Pico%20(User).groovy") {
        capability "Actuator"
        capability "PushableButton"
        capability "HoldableButton"
        capability "ReleasableButton"
        capability "DoubleTapableButton"

        attribute "healthStatus", "enum", ["unknown", "online", "offline"]
    }
    preferences {
        input name: "holdMs", type: "enum", title: fmtTitle("Hold time"), options: HOLD_TIMES, defaultValue: "1000",
            description: fmtDesc("A button down this long is held")
        input name: "doubleTapMs", type: "enum", title: fmtTitle("Double-tap window"), options: TAP_WINDOWS, defaultValue: "0",
            description: fmtDesc("On: a quick press waits this long for a second one (doubleTapped instead of pushed); every single press is that much later")
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
// Push and Double Tap press the button on the bridge; the event comes back from the bridge (digital)
void push(Object n)      { Integer b = buttonOf("pushed", n); if (b != null) parent?.componentPush(device, b, 1) }
void doubleTap(Object n) { Integer b = buttonOf("doubleTapped", n); if (b != null) parent?.componentPush(device, b, 2) }
// LEAP has no hold command: events only, for testing rules (type digital)
void hold(Object n)      { buttonCommand("held", n) }
void release(Object n)   { buttonCommand("released", n) }

void buttonCommand(String name, Object n) {
    Integer b = buttonOf(name, n)
    if (b == null) return
    sendEventLog([name: name, value: b, isStateChange: true, type: "digital", descriptionText: "${device.displayName} button ${b} was ${name}".toString()])
}

Integer buttonOf(String name, Object n) {
    Integer b = intOrNull(n)
    Integer count = intOrNull(device.currentValue("numberOfButtons"))
    if (b == null || b < 1 || (count != null && b > count)) {
        logWarn "${name}: button ${n}? (1-${count ?: '?'})"
        return null
    }
    return b
}

/*******************************************************************
 ***** Events from the bridge
********************************************************************/
void parse(List<Map> evts) {
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
    if (levelInfo.level == null) levelInfo = getLogLevelInfo()
    String logMsg = "Logging Level is: ${LOG_LEVELS[levelInfo.level]} (${levelInfo.level})"
    if (levelInfo.level >= 3 && levelInfo.time > 0) {
        logMsg += " for ${LOG_TIMES[levelInfo.time]}"
        runIn(60*levelInfo.time, "logsOff")
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
    logWarn "Debug and Trace logging disabled..."
    if (logLevelInfo.level >= 3) {
        Integer lastLvl = state.lastLogLevel != null ? state.lastLogLevel as Integer : 2
        device.updateSetting("logLevel",[value:lastLvl.toString(), type:"enum"])
        logWarn "Logging Level is: ${LOG_LEVELS[lastLvl]} (${lastLvl})"
    }
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
