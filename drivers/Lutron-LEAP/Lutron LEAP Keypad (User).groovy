/*
 *  Lutron LEAP Keypad (User)
 *  A Lutron RadioRA 3 keypad: wall, hybrid, tabletop, virtual (Homeowner) or Phantom. Buttons are numbered 1..N in
 *  Lutron's order, so raise / lower come last (Lutron 18 / 19 are buttons 7 / 8 on a six-button keypad); the data
 *  value lutronButtons maps "button:Lutron number". pushed comes when the processor reports the Press, released when
 *  it reports the Release, which it sends only for an unprogrammed button and for raise / lower. No hold or double-tap
 *  timing.
 *  A device of the Lutron LEAP Integration (User) app. Push presses the button on the processor, so its Lutron
 *  programming runs; the event comes back (digital).
 *  LEDs: Set LED sets a button's LED; Create LED Device and Delete LED Device add or remove a Generic Component Switch
 *  that shows and sets one LED (the data value ledButtons lists the buttons that have one). Lutron's programming sets
 *  the LED of a programmed button back. These are commands, not preferences: the hub keeps no value for a preference
 *  built from a device's data.
 *  Not single-threaded, and writes no state in commands.
 *  Used with the app, which needs Hubitat 2.5.0 or later. Tested on 2.5.3.
 *
 *  Changelog:
 *  ## [1.0.0] - 2026-11-10
 *    - First release. A RadioRA 3 keypad: buttons 1..N in Lutron's order (raise / lower last), pushed and released as the
 *      processor reports them, Push, Set LED, and LED devices made and removed by command.
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
@Field static final String DRIVER  = "Lutron LEAP Keypad (User)"
@Field static final String LED_DRIVER = "Generic Component Switch"

metadata {
    definition(name: DRIVER, namespace: "draperw", author: "Bill Draper", component: true,
               importUrl: "https://raw.githubusercontent.com/draperw/Hubitat/main/drivers/Lutron-LEAP/Lutron%20LEAP%20Keypad%20(User).groovy") {
        capability "Actuator"
        capability "PushableButton"
        capability "ReleasableButton"

        command "createLedDevice", [[name: "Button*", type: "NUMBER", description: "A button with an LED"]]
        command "deleteLedDevice", [[name: "Button*", type: "NUMBER", description: "A button with an LED device"]]
        command "setLed", [[name: "Button*", type: "NUMBER", description: "A button with an LED"],
                           [name: "State*", type: "ENUM", description: "The LED", constraints: ["on", "off"]]]

        attribute "healthStatus", "enum", ["unknown", "online", "offline"]
    }
    preferences {
        input name: "logLevel", type: "enum", title: fmtTitle("Logging Level"),
            description: fmtDesc("Logs selected level and above"), defaultValue: 2, options: LOG_LEVELS
        input name: "logLevelTime", type: "enum", title: fmtTitle("Logging Level Time"),
            description: fmtDesc("Time to enable Debug/Trace logging"), defaultValue: 30, options: LOG_TIMES
    }
}

// The buttons with an LED, from the data value the app sets ("1,2,3,4,5,6")
List<Integer> ledButtons() {
    String v = device?.getDataValue("ledButtons")
    return (v ?: "").tokenize(",").collect { intOrNull(it) }.findAll { it != null }
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
// Push presses the button on the processor; the event comes back from the bridge (digital)
void push(Object n) { Integer b = buttonOf("pushed", n); if (b != null) parent?.componentPush(device, b, 1) }
// LEAP has no release command: an event only, for testing rules (type digital)
void release(Object n) {
    Integer b = buttonOf("released", n)
    if (b == null) return
    sendEventLog([name: "released", value: b, isStateChange: true, type: "digital", descriptionText: "${device.displayName} button ${b} was released".toString()])
}

// Set LED: the button's LED on or off on the processor, through the app and the bridge (the LED device, if any,
// follows when the processor reports the change)
void setLed(Object n, Object v) {
    Integer b = ledButton(n, "Set LED")
    if (b == null) return
    String st = v?.toString()?.trim()?.toLowerCase()
    if (!(st in ["on", "off"])) { logWarn "Set LED: on or off? (${v})"; return }
    parent?.componentSetLed(device, b, st)
}

// Create LED Device: a Generic Component Switch for that button's LED, DNI <keypad>/led/<n>
void createLedDevice(Object n) {
    Integer b = ledButton(n, "Create LED Device")
    if (b == null) return
    String dni = ledDni(b)
    if (getChildDevice(dni) != null) { logInfo "Create LED Device: button ${b} already has an LED device"; return }
    try {
        addChildDevice("hubitat", LED_DRIVER, dni, [name: "${device.displayName} LED ${b}".toString(),
                                                   label: "${device.displayName} button ${b} LED".toString(), isComponent: true])
        logInfo "Created the LED device for button ${b}"
    } catch (Exception e) {
        logErr "Couldn't create the LED device for button ${b}: ${e.message}"
    }
}

void deleteLedDevice(Object n) {
    Integer b = intOrNull(n)
    String dni = ledDni(b)
    if (b == null || getChildDevice(dni) == null) { logInfo "Delete LED Device: button ${n} has no LED device"; return }
    deleteChildDevice(dni)
    logInfo "Deleted the LED device for button ${b}"
}

// The button, if it has an LED
Integer ledButton(Object n, String what) {
    Integer b = intOrNull(n)
    List<Integer> leds = ledButtons()
    if (b == null || !(b in leds)) {
        logWarn "${what}: button ${n} has no LED (LED buttons: ${leds ? leds.join(', ') : 'none'})"
        return null
    }
    return b
}

String ledDni(Integer b) { return "${device.deviceNetworkId}/led/${b}".toString() }

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
 ***** The LED children (Generic Component Switch): their commands set the LED
********************************************************************/
void componentOn(Object cd)  { Integer n = ledOf(cd); if (n != null) parent?.componentSetLed(device, n, "on") }
void componentOff(Object cd) { Integer n = ledOf(cd); if (n != null) parent?.componentSetLed(device, n, "off") }
void componentRefresh(Object cd) { logDebug "${cd?.displayName}: the LED's state comes from the processor" }

Integer ledOf(Object cd) {
    String dni = cd?.deviceNetworkId?.toString() ?: ""
    String prefix = "${device.deviceNetworkId}/led/"
    return dni.startsWith(prefix) ? intOrNull(dni.substring(prefix.length())) : null
}

/*******************************************************************
 ***** Events from the bridge
********************************************************************/
// Button events are this device's; {name: led, button: n, value: on / off} goes to that button's LED child, if any
void parse(List<Map> evts) {
    evts?.each { Map e ->
        if (e.name == "led") {
            Object ch = getChildDevice("${device.deviceNetworkId}/led/${e.button}".toString())
            if (ch != null) ch.parse([[name: "switch", value: e.value, descriptionText: "${ch.displayName} switch is ${e.value}".toString()]])
            else logDebug "Button ${e.button} LED is ${e.value} (no LED device)"
            return
        }
        sendEventLog(e)
    }
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
