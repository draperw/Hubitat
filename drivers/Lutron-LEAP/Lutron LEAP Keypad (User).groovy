/*
 *  Lutron LEAP Keypad (User)
 *  A Lutron RadioRA 3 keypad: wall, hybrid, tabletop, virtual (Homeowner) or Phantom. Each button is Lutron's own
 *  number, as in the processor's integration report and Hubitat's Lutron (Telnet) integration: 1-6 and raise / lower
 *  18 / 19 on a six-button keypad, so numberOfButtons (the highest) is 19 there; the data value lutronButtons lists
 *  them ("button:Lutron number"). pushed comes when the processor reports the Press, released when it reports the
 *  Release, which it sends only for an unprogrammed button and for raise / lower. No hold or double-tap timing.
 *  A device of the Lutron LEAP Integration (User) app. Push presses the button on the processor, so its Lutron
 *  programming runs; the event comes back (digital).
 *  LEDs: LED On and LED Off (ledOn / ledOff, the button's number) set a button's LED, as on Hubitat's Lutron (Telnet)
 *  keypad, whose LED states are the attributes buttonLed-<n>. Create LED Device and Delete LED Device add or remove a
 *  Generic Component Switch that shows and sets one LED (the data value ledButtons lists the buttons that have one).
 *  Lutron's programming sets the LED of a programmed button back. These are commands, not preferences: the hub keeps no
 *  value for a preference built from a device's data.
 *  Not single-threaded, and writes no state in commands.
 *  Used with the app, which needs Hubitat 2.5.0 or later. Tested on 2.5.3.
 *
 *  Changelog:
 *  ## [1.0.1] - 2026-11-12
 *    - Buttons by Lutron's own numbers, as Hubitat's Lutron (Telnet) integration: 1-6 and raise / lower 18 / 19 on a
 *      wall keypad, 16, 17, 24, 25 on tabletops; numberOfButtons is the highest. LEDs by their button's number. Push and
 *      Release check the button against the keypad's own buttons. LED On and LED Off, one button number each (as the
 *      Telnet keypad's ledOn / ledOff), replace Set LED; each LED's state is the attribute buttonLed-<n>. A new LED
 *      device, and its Refresh, show the LED's last reported state.
 *    - Debug logging ends at its deadline also when its timer was lost (checked when an event comes from the bridge).
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

@Field static final String VERSION = "1.0.1"
@Field static final String DRIVER  = "Lutron LEAP Keypad (User)"
@Field static final String LED_DRIVER = "Generic Component Switch"
@Field static final List<Integer> LED_ATTRIBUTE_BUTTONS = (1..100).toList()

metadata {
    definition(name: DRIVER, namespace: "draperw", author: "Bill Draper", component: true,
               importUrl: "https://raw.githubusercontent.com/draperw/Hubitat/main/drivers/Lutron-LEAP/Lutron%20LEAP%20Keypad%20(User).groovy") {
        capability "Actuator"
        capability "PushableButton"
        capability "ReleasableButton"

        command "createLedDevice", [[name: "Button*", type: "NUMBER", description: "A button with an LED"]]
        command "deleteLedDevice", [[name: "Button*", type: "NUMBER", description: "A button with an LED device"]]
        command "ledOn", [[name: "Button*", type: "NUMBER", description: "A button with an LED"]]
        command "ledOff", [[name: "Button*", type: "NUMBER", description: "A button with an LED"]]

        attribute "healthStatus", "enum", ["unknown", "online", "offline"]
        // Each LED's state, by its button's number, as Hubitat's Lutron (Telnet) keypad has them; declared so rules can
        // use them (the Phantom Keypad numbers its buttons to 100)
        LED_ATTRIBUTE_BUTTONS.each { Integer n -> attribute "buttonLed-${n}".toString(), "enum", ["on", "off"] }
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
// Push presses the button on the processor; the event comes back from the bridge (digital)
void push(Object n) { Integer b = buttonOf("pushed", n); if (b != null) parent?.componentPush(device, b, 1) }
// LEAP has no release command: an event only, for testing rules (type digital)
void release(Object n) {
    Integer b = buttonOf("released", n)
    if (b == null) return
    sendEventLog([name: "released", value: b, isStateChange: true, type: "digital", descriptionText: "${device.displayName} button ${b} was released".toString()])
}

// LED On / LED Off: that button's LED on the processor, through the app and the bridge (the LED device, if any,
// follows when the processor reports the change). One number each, as Hubitat's Lutron (Telnet) keypad has them, so
// rules that run ledOn(n) / ledOff(n) work on either.
void ledOn(Object n)  { setLedTo(n, "on", "LED On") }
void ledOff(Object n) { setLedTo(n, "off", "LED Off") }

void setLedTo(Object n, String st, String what) {
    Integer b = ledButton(n, what)
    if (b != null) parent?.componentSetLed(device, b, st)
}

// Create LED Device: a Generic Component Switch for that button's LED, DNI <keypad>/led/<n>
void createLedDevice(Object n) {
    Integer b = ledButton(n, "Create LED Device")
    if (b == null) return
    String dni = ledDni(b)
    if (getChildDevice(dni) != null) { logInfo "Create LED Device: button ${b} already has an LED device"; return }
    try {
        Object ch = addChildDevice("hubitat", LED_DRIVER, dni, [name: "${device.displayName} LED ${b}".toString(),
                                                               label: "${device.displayName} button ${b} LED".toString(), isComponent: true])
        logInfo "Created the LED device for button ${b}"
        showLed(ch)                                       // the processor reports an LED only when it changes
    } catch (Exception e) {
        logErr "Couldn't create the LED device for button ${b}: ${e.message}"
    }
}

void deleteLedDevice(Object n) {
    Integer b = intOrNull(n)
    String dni = (b != null) ? ledDni(b) : null
    if (dni == null || getChildDevice(dni) == null) { logInfo "Delete LED Device: button ${n} has no LED device"; return }
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

// The buttons with an LED, from the data value the app sets ("1,2,3,4,5,6")
List<Integer> ledButtons() {
    String v = device?.getDataValue("ledButtons")
    return (v ?: "").tokenize(",").collect { intOrNull(it) }.findAll { it != null }
}

String ledDni(Integer b) { return "${device.deviceNetworkId}/led/${b}".toString() }

// Lutron's numbers have gaps (1-6, then raise / lower 18 / 19), so a button is checked against the keypad's own list
// when the app has set it, and against 1..numberOfButtons otherwise
Integer buttonOf(String name, Object n) {
    Integer b = intOrNull(n)
    List<Integer> btns = keypadButtons()
    Integer count = intOrNull(device.currentValue("numberOfButtons"))
    boolean ok = b != null && b >= 1 && (btns ? (b in btns) : (count == null || b <= count))
    if (!ok) {
        logWarn "${name}: button ${n}? (${btns ? 'buttons ' + btns.join(', ') : '1-' + (count ?: '?')})"
        return null
    }
    return b
}

// The keypad's buttons, from the data value the app sets ("1:1,2:2,...,18:18,19:19", button:Lutron number)
List<Integer> keypadButtons() {
    String v = device?.getDataValue("lutronButtons")
    return (v ?: "").tokenize(",").collect { intOrNull(it.tokenize(":")[0]) }.findAll { it != null }
}

/*******************************************************************
 ***** The LED children (Generic Component Switch): their commands set the LED
********************************************************************/
void componentOn(Object cd)  { Integer n = ledOf(cd); if (n != null) parent?.componentSetLed(device, n, "on") }
void componentOff(Object cd) { Integer n = ledOf(cd); if (n != null) parent?.componentSetLed(device, n, "off") }
void componentRefresh(Object cd) { showLed(cd) }

// An LED device shows the LED's last state the processor reported (buttonLed-<n>); with none yet it stays blank
void showLed(Object cd) {
    Integer n = ledOf(cd)
    String v = (n != null) ? device.currentValue("buttonLed-${n}")?.toString() : null
    if (!(v in ["on", "off"])) { logDebug "${cd?.displayName}: no LED state reported yet"; return }
    Object ch = getChildDevice(cd.deviceNetworkId?.toString())       // the child as this device holds it
    ch?.parse([[name: "switch", value: v, descriptionText: "${ch.displayName} switch is ${v}".toString()]])
}

Integer ledOf(Object cd) {
    String dni = cd?.deviceNetworkId?.toString() ?: ""
    String prefix = "${device.deviceNetworkId}/led/"
    return dni.startsWith(prefix) ? intOrNull(dni.substring(prefix.length())) : null
}

/*******************************************************************
 ***** Events from the bridge
********************************************************************/
// Button events are this device's; {name: led, button: n, value: on / off} sets buttonLed-<n> and goes to that
// button's LED child, if any
void parse(List<Map> evts) {
    checkLogsOff()
    evts?.each { Map e ->
        if (e.name == "led") {
            sendEventLog(name: "buttonLed-${e.button}".toString(), value: e.value,
                         descriptionText: "${device.displayName} LED ${e.button} was turned ${e.value}".toString())
            Object ch = getChildDevice("${device.deviceNetworkId}/led/${e.button}".toString())
            if (ch != null) ch.parse([[name: "switch", value: e.value, descriptionText: "${ch.displayName} switch is ${e.value}".toString()]])
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
