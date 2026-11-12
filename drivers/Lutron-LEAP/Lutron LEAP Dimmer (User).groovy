/*
 *  Lutron LEAP Dimmer (User)
 *  A dimmer zone on a Lutron Caseta bridge or RadioRA 3 processor. A device of the Lutron LEAP Integration (User)
 *  app: its commands go through the app to the Lutron LEAP Bridge (User) device, which also sends it its status.
 *  Not single-threaded, and writes no state in commands, so the bridge can update it while a command is running.
 *  Used with the app, which needs Hubitat 2.5.0 or later. Tested on 2.5.3.
 *
 *  Changelog:
 *  ## [1.0.1] - 2026-11-12
 *    - Flash leaves the level as it was: the next On goes back to the level from before it, not to the 100% of an on
 *      step (the bridge keeps the steps' reports, a late one included, off the device).
 *    - Flash time limit (seconds, 1-3600, default 60): the flash stops and the light goes back to how it was. A press at
 *      the wall during a flash isn't reported, so it can't stop one.
 *    - During a flash the device shows on (no event per step); Flash has no input on the device page (the rate is the
 *      Flash rate preference).
 *    - Debug logging ends at its deadline also when its timer was lost (checked when an event comes from the bridge).
 *  ## [1.0.0] - 2026-11-10
 *    - First release. A dimmer zone: on / off / level with transitions, start / stop level change, Flash with its rate,
 *      on-level options; physical and digital events.
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
@Field static final String DRIVER  = "Lutron LEAP Dimmer (User)"

// On goes to the last level. Transitions are in seconds, fractions allowed; 0 sends a fade-less GoToLevel, which changes
// at the dimmer's own rate.
@Field static final BigDecimal DEFAULT_TRANSITION_SECS = 1
// Lutron's own defaults: a quick fade up, a slower fade out
@Field static final BigDecimal DEFAULT_ON_TRANSITION_SECS = 0.75
@Field static final BigDecimal DEFAULT_OFF_TRANSITION_SECS = 2.5
// Flash rates (the same choices as Hubitat's built-in Lutron dimmer)
@Field static final Map FLASH_RATES = ["750": "750 ms", "1000": "1 s", "2000": "2 s", "5000": "5 s"]
@Field static final String DEFAULT_FLASH_RATE = "750"
// A press at the wall during a flash isn't reported by the processor, so only a command or this limit ends a flash
@Field static final Integer DEFAULT_FLASH_LIMIT_SECS = 60

metadata {
    definition(name: DRIVER, namespace: "draperw", author: "Bill Draper", component: true,
               importUrl: "https://raw.githubusercontent.com/draperw/Hubitat/main/drivers/Lutron-LEAP/Lutron%20LEAP%20Dimmer%20(User).groovy") {
        capability "Actuator"
        capability "Light"
        capability "Switch"
        capability "SwitchLevel"
        capability "ChangeLevel"
        capability "Flash"
        // The rate is the Flash rate preference: declared with no parameter, the device page shows no box for it (the
        // capability's own flash(rateToFlash) has one); flash() still takes a rate from a rule
        command "flash"
        capability "Refresh"

        attribute "healthStatus", "enum", ["unknown", "online", "offline"]
    }
    preferences {
        input name: "useLastLevel", type: "bool", title: fmtTitle("On goes to the last level"), defaultValue: true,
            description: fmtDesc("Off: On goes to the On level below")
        input name: "onLevel", type: "number", title: fmtTitle("On level (%)"), range: "1..100", defaultValue: 100,
            description: fmtDesc("Used when On doesn't go to the last level")
        input name: "transitionSecs", type: "decimal", title: fmtTitle("Transition time (seconds)"), range: "0..3600",
            defaultValue: DEFAULT_TRANSITION_SECS, description: fmtDesc("For Set Level without a duration. 0 for the dimmer's native rate")
        input name: "onTransitionSecs", type: "decimal", title: fmtTitle("Turn-on transition time (seconds)"), range: "0..3600",
            defaultValue: DEFAULT_ON_TRANSITION_SECS, description: fmtDesc("For On command. 0 for the dimmer's native rate")
        input name: "offTransitionSecs", type: "decimal", title: fmtTitle("Turn-off transition time (seconds)"), range: "0..3600",
            defaultValue: DEFAULT_OFF_TRANSITION_SECS, description: fmtDesc("For Off command. 0 for the dimmer's native rate")
        input name: "flashRate", type: "enum", title: fmtTitle("Flash rate"), options: FLASH_RATES, defaultValue: DEFAULT_FLASH_RATE,
            description: fmtDesc("For Flash without a rate. Any other command stops the flash")
        input name: "flashTimeLimit", type: "number", title: fmtTitle("Flash time limit (seconds)"), range: "1..3600",
            defaultValue: DEFAULT_FLASH_LIMIT_SECS, description: fmtDesc("A flash stops after this long and the light goes back to how it was (1-3600)")
        input name: "logLevel", type: "enum", title: fmtTitle("Logging Level"),
            description: fmtDesc("Logs selected level and above"), defaultValue: 2, options: LOG_LEVELS
        input name: "logLevelTime", type: "enum", title: fmtTitle("Logging Level Time"),
            description: fmtDesc("Time to enable Debug/Trace logging"), defaultValue: 30, options: LOG_TIMES
    }
}

/*******************************************************************
 ***** Lifecycle
********************************************************************/
// Runs while the app is creating this device: no calls back into the app from here.
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
 ***** Commands: all go through the app to the bridge
********************************************************************/
void on() {
    Integer lvl = (settings.useLastLevel != false) ? intOrNull(device.currentValue("level")) : intOrNull(settings.onLevel)
    if (lvl == null || lvl < 1) lvl = 100
    logDebug "on: level ${lvl}"
    parent?.componentSetLevel(device, Math.min(100, lvl), secsOr(settings.onTransitionSecs, DEFAULT_ON_TRANSITION_SECS))
}

// Off fades over the Turn-off transition (a fade-less GoToLevel 0 runs at the processor's own, faster rate); 0 sends
// GoToLevel 0. The level is kept for the next On.
void off() {
    BigDecimal secs = secsOr(settings.offTransitionSecs, DEFAULT_OFF_TRANSITION_SECS)
    logDebug "off over ${secs} s"
    parent?.componentSetLevel(device, 0, secs)
}

void setLevel(Object level, Object duration = null) {
    Integer lvl = intOrNull(level)
    if (lvl == null) { logWarn "setLevel: level ${level}?"; return }
    lvl = Math.max(0, Math.min(100, lvl))
    BigDecimal secs = (decOrNull(duration) != null) ? decOrNull(duration) : secsOr(settings.transitionSecs, DEFAULT_TRANSITION_SECS)
    logDebug "setLevel: ${lvl} over ${secs} s"
    parent?.componentSetLevel(device, lvl, secs)
}

void startLevelChange(Object direction) {
    parent?.componentStartLevelChange(device, direction)
}

void stopLevelChange() {
    parent?.componentStopLevelChange(device)
}

void refresh() {
    parent?.componentRefresh(device)
}

// The bridge switches the zone on and off at this rate until another command. The steps' reports don't reach this device
// (it shows on, at its level), so the next On goes back to the level from before the flash
void flash(Object rateToFlash = null) {
    Integer ms = intOrNull(rateToFlash) ?: intOrNull(settings.flashRate) ?: intOrNull(DEFAULT_FLASH_RATE)
    Integer limit = intOrNull(settings.flashTimeLimit) ?: DEFAULT_FLASH_LIMIT_SECS
    logDebug "flash: every ${ms} ms, for up to ${limit} s"
    parent?.componentFlash(device, ms, limit)
}

BigDecimal secsOr(Object v, BigDecimal dflt) {
    BigDecimal d = decOrNull(v)
    return (d != null && d >= 0) ? d : dflt
}

/*******************************************************************
 ***** Status from the bridge
********************************************************************/
void parse(List<Map> evts) {
    checkLogsOff()
    evts?.each { Map e -> sendEventLog(e) }
}

void parse(String description) {
    logWarn "parse(String) isn't used: status comes from the bridge"
}

// One event sender: Info on change, Debug when unchanged
void sendEventLog(Map evt) {
    Map e = new LinkedHashMap(evt)
    if (!e.descriptionText) e.descriptionText = "${device.displayName} ${e.name} is ${e.value}${e.unit ?: ''}".toString()
    if (device.currentValue(e.name)?.toString() != e.value?.toString() || e.isStateChange) logInfo "${e.descriptionText}"
    else logDebug "${e.descriptionText} [NOT CHANGED]"
    sendEvent(e)
}

Integer intOrNull(Object v) {
    try { return (v == null || v.toString().trim() == "") ? null : new BigDecimal(v.toString().trim()).intValue() } catch (Exception e) { return null }
}

BigDecimal decOrNull(Object v) {
    try { return (v == null || v.toString().trim() == "") ? null : new BigDecimal(v.toString().trim()) } catch (Exception e) { return null }
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
