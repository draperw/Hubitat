// Big Ass Fans Matter Light (Child)
//
// Hubitat Matter driver for Big Ass Fans ceiling fan light.
// Child device handling light control (EP 02), light mode select (EP 03),
// and EP 02 mode select (User/Auto).
//
// Copyright 2026 draperw
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// Source: https://
//
// Changelog:
//
// ## [1.0.0] - 2026-03-06
//   - Initial release
//   - On/off, level, color temperature
//   - 16 discrete BAF light levels with setBafLevel command
//   - Smooth level ramping via startLevelChange/stopLevelChange
//   - Light mode select (All Lights / Downlight / Uplight) for 2-light fans
//   - Light auto mode (User / Auto)

import groovy.transform.Field
import hubitat.helper.HexUtils

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

@Field static final String VERSION = "1.0.0"

// Endpoint IDs — Integer for parse comparisons
@Field static final Integer EP_LIGHT     = 0x02
@Field static final Integer EP_LIGHTMODE = 0x03

// Endpoint ID strings for Matter API calls
@Field static final String EP_LIGHT_STR     = "02"
@Field static final String EP_LIGHTMODE_STR = "03"

// Matter Cluster IDs
@Field static final Integer CLUSTER_ON_OFF      = 0x0006
@Field static final Integer CLUSTER_LEVEL       = 0x0008
@Field static final Integer CLUSTER_COLOR       = 0x0300
@Field static final Integer CLUSTER_MODE_SELECT = 0x0050

// Matter Command IDs
@Field static final Integer CMD_OFF                     = 0x0000
@Field static final Integer CMD_ON                      = 0x0001
@Field static final Integer CMD_MOVE_TO_LEVEL_WITH_ONOFF = 0x0004  // MoveToLevelWithOnOff
@Field static final Integer CMD_STOP_WITH_ONOFF         = 0x0007  // StopWithOnOff
@Field static final Integer CMD_MOVE_TO_CT              = 0x000A  // MoveToColorTemperature
@Field static final Integer CMD_CHANGE_TO_MODE          = 0x0000  // Mode Select ChangeToMode

// Color temperature fallback range (Kelvin) — used until parent syncs the discovered device range
@Field static final Integer CT_MIN = 2700
@Field static final Integer CT_MAX = 4000

// EP 03 Light Mode map (known from device logs)
// Command labels use "Control" prefix; attribute values use the base name
@Field static final Map LIGHT_MODES = [
    "Control All Lights": 0, "Control Downlight": 1, "Control Uplight": 2
]
@Field static final Map LIGHT_MODES_REVERSE = [
    0: "All Lights", 1: "Downlight", 2: "Uplight"
]

// EP 02 Light Auto modes (known from device logs)
// User = manual control (Auto off), Auto = automatic control (Auto on)
@Field static final Map LIGHT_AUTO_MODES = [
    "off": 0, "on": 1   // off = User mode, on = Auto mode
]
@Field static final Map LIGHT_AUTO_REVERSE = [
    0: "off", 1: "on"   // 0 = User (off), 1 = Auto (on)
]

// BAF discrete light levels (1-16) mapped to percent midpoints
@Field static final Map BAF_LEVEL_TO_PERCENT = [
    1: 1, 2: 8, 3: 14, 4: 21, 5: 27, 6: 34, 7: 41, 8: 47,
    9: 54, 10: 60, 11: 67, 12: 74, 13: 80, 14: 87, 15: 93, 16: 100
]

// Logging level configuration
@Field static final Map LOG_LEVELS = [0: "Error", 1: "Warn", 2: "Info", 3: "Debug", 4: "Trace"]
@Field static final Map LOG_TIMES  = [0: "Indefinitely", 30: "30 Minutes", 60: "1 Hour", 120: "2 Hours", 180: "3 Hours"]

// ---------------------------------------------------------------------------
// Metadata
// ---------------------------------------------------------------------------

metadata {
    definition(
        name: "BAF Light Matter",
        namespace: "community",
        author: "draperw",
        importUrl: "https://raw.githubusercontent.com/draperw/Hubitat/refs/heads/main/drivers/BAF-Matter/BAF-Light-Matter-Child.groovy",
        singleThreaded: true
    ) {
        capability "Actuator"
        capability "Switch"
        capability "SwitchLevel"
        capability "ColorTemperature"
        capability "ColorMode"
        capability "ChangeLevel"
        capability "Light"
        capability "Initialize"
        capability "Refresh"
        capability "Health Check"

        // Light mode (EP 03): All Lights / Downlight / Uplight
        command "controlAllLights"
        command "controlDownlight"
        command "controlUplight"

        // Light auto mode (EP 02): on = Auto, off = User/manual
        command "lightAutoOn"
        command "lightAutoOff"

        // BAF discrete light level (1-16)
        command "setBafLevel", [[name: "BAF Level*", type: "NUMBER",
            description: "BAF discrete light level (0=off, 1-16)",
            range: "0..16"]]

        // Health check attribute (mirrored from parent)
        attribute "healthStatus", "enum", ["unknown", "offline", "online"]

        // Custom attributes
        attribute "lightMode", "string"       // All Lights, Downlight, Uplight
        attribute "lightAuto", "string"        // on (Auto) / off (User/manual)
        attribute "bafLevel", "number"        // 1-16 discrete BAF light level
    }

    preferences {
        input name: "useLastLevelForOn", type: "bool", title: "Use last level for on?",
            description: "Restore previous brightness when turning on", defaultValue: true, required: true
        input name: "onLevel", type: "number", title: "On level",
            description: "Fixed level (1-100%) used when 'Use last level' is disabled. Leave blank for a plain on().",
            range: "1..100", defaultValue: 100, required: false
        input name: "levelRampRate", type: "enum", title: "Level ramp speed",
            description: "Seconds per BAF level step during startLevelChange (e.g., 16 steps × rate = full ramp time)",
            defaultValue: "1", options: ["0.5": "Fast (0.5s/step)", "1": "Normal (1s/step, default)", "1.5": "Slow (1.5s/step)", "2": "Very slow (2s/step)"]
        input name: "logLevel", type: "enum", title: "Logging Level",
            description: "Logs selected level and above", defaultValue: "2", options: LOG_LEVELS
        input name: "logLevelTime", type: "enum", title: "Debug/Trace Log Duration",
            description: "Auto-disable timer for Debug/Trace logging", defaultValue: "30", options: LOG_TIMES
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

void installed() {
    logInfo "installed() -- BAF Light Matter v${VERSION}"
    sendEvent(name: "healthStatus", value: "unknown", descriptionText: "${device.displayName} healthStatus initialized", type: "digital")
    initialize()
}

void updated() {
    logDebug "updated()"
    checkLogLevel()
}

void initialize() {
    logInfo "initialize()"
    // Re-sync capability state from parent in case child was restarted independently.
    parent?.syncChildState()
}

// Called by parent after endpoint discovery.
void setLightCount(Integer count) {
    if (count != state.lightCount) {
        state.lightCount = count
        logInfo "Light count set to ${count} by parent"
    }
}

// Called by parent after runtime capability/profile update.
void setHasColorTemp(Boolean supported) {
    if (supported != state.hasColorTemp) {
        state.hasColorTemp = supported
        logInfo "Color temperature ${supported ? 'enabled' : 'not available'} (set by parent)"
    }
}

// Called by parent after runtime capability/profile discovery.
void setColorTempAdjustable(Boolean adjustable) {
    if (adjustable != state.ctAdjustable) {
        state.ctAdjustable = adjustable
        if (adjustable != null) {
            logInfo "Color temperature control is ${adjustable ? 'adjustable' : 'fixed'}"
        }
    }
}

// Called by parent after runtime capability/profile discovery.
void setColorTempRangeK(Integer minK, Integer maxK) {
    boolean changed = (minK != state.ctMinK) || (maxK != state.ctMaxK)
    if (changed) {
        state.ctMinK = minK
        state.ctMaxK = maxK
        if (minK != null && maxK != null) {
            logInfo "Color temperature range is ${minK}-${maxK}K"
        }
    }
}

// Capability accessors — defaults apply until parent syncs capability state.
private Boolean hasLightMode() { return (state.lightCount ?: 0) >= 2 }   // 2 lights = uplight + downlight; conservative default until parent sync
private Boolean hasColorTemp() { return (state.hasColorTemp != null) ? state.hasColorTemp : true }
private Boolean hasAdjustableColorTemp() { return (state.ctAdjustable != null) ? (state.ctAdjustable == true) : true }
private Integer getCtMinBoundK() {
    Integer v = state.ctMinK as Integer
    return (v != null && v > 0) ? v : CT_MIN
}
private Integer getCtMaxBoundK() {
    Integer v = state.ctMaxK as Integer
    return (v != null && v > 0) ? v : CT_MAX
}
private Integer clampToCtBounds(Integer k) {
    Integer lo = getCtMinBoundK()
    Integer hi = getCtMaxBoundK()
    if (hi < lo) { Integer t = lo; lo = hi; hi = t }
    return Math.max(lo, Math.min(hi, k))
}

// ---------------------------------------------------------------------------
// Parse — entry point from parent via parseFromParent()
// ---------------------------------------------------------------------------

// Routes by endpoint: EP 02 = light, EP 03 = light mode.
void parseFromParent(Map descMap) {
    if (!descMap) return

    try {
        Integer ep   = descMap.endpointInt
        Integer clus = descMap.clusterInt
        Integer attr = descMap.attrInt

        if (ep == null || clus == null) {
            logDebug "parseFromParent: missing endpoint or cluster -- ${descMap}"
            return
        }

        switch (ep) {
            case EP_LIGHT:
                parseLightEndpoint(clus, attr, descMap)
                break
            case EP_LIGHTMODE:
                parseLightModeEndpoint(clus, attr, descMap)
                break
            default:
                logDebug "parseFromParent: unexpected endpoint ${ep}: ${descMap}"
        }
    } catch (e) {
        logErr "parseFromParent exception: ${e.message} -- descMap: ${descMap}"
    }
}

// ---------------------------------------------------------------------------
// EP 02: Light Endpoint Parsing
// ---------------------------------------------------------------------------

private void parseLightEndpoint(Integer clus, Integer attr, Map descMap) {
    switch (clus) {
        case CLUSTER_ON_OFF:
            if (attr == 0x0000) parseSwitchEvent(descMap)
            break
        case CLUSTER_LEVEL:
            if (attr == 0x0000) parseLevelEvent(descMap)
            break
        case CLUSTER_COLOR:
            if (attr == 0x0007) parseColorTempEvent(descMap)
            break
        case CLUSTER_MODE_SELECT:
            parseEP02ModeSelect(attr, descMap)
            break
        default:
            logTrace "EP02 unhandled cluster 0x${Integer.toHexString(clus)}: ${descMap}"
    }
}

// ---------------------------------------------------------------------------
// EP 03: Light Mode Endpoint Parsing
// ---------------------------------------------------------------------------

private void parseLightModeEndpoint(Integer clus, Integer attr, Map descMap) {
    if (clus == CLUSTER_MODE_SELECT) {
        parseModeSelectCurrentMode(attr, descMap, LIGHT_MODES_REVERSE, "lightMode", "EP03 light mode")
    } else {
        logTrace "EP03 unhandled cluster 0x${Integer.toHexString(clus)}: ${descMap}"
    }
}

// ---------------------------------------------------------------------------
// Light Event Handlers
// ---------------------------------------------------------------------------

// On/Off attr 0x0000 — value arrives as Boolean or Integer with newParse:true.
private void parseSwitchEvent(Map descMap) {
    def rawValue = descMap.value
    if (rawValue == null) return

    String value
    if (rawValue instanceof Boolean) {
        value = rawValue ? "on" : "off"
    } else {
        value = ((rawValue as Integer) == 1) ? "on" : "off"
    }
    if (state.diagActive) { diagDebug "Diagnostics Light OnOff (0x0006/0x0000): ${rawValue} (${value})" }
    if (device.currentValue("switch") == value) return

    String desc = "${device.displayName} was turned ${value}"
    logInfo desc
    sendEvent(name: "switch", value: value, descriptionText: desc)

    // Set level/bafLevel to 0 on off for consistent display
    if (value == "off") {
        if ((device.currentValue("level") as Integer) != 0) {
            sendEvent(name: "level", value: 0, unit: "%", descriptionText: "${device.displayName} level is 0%")
        }
        if ((device.currentValue("bafLevel") as Integer) != 0) {
            sendEvent(name: "bafLevel", value: 0, descriptionText: "BAF level is 0")
        }
    }
}

// CurrentLevel attr 0x0000 (Matter 0-254 → 0-100%).
// Residual level reports while off are ignored — parseSwitchEvent handles off→0.
private void parseLevelEvent(Map descMap) {
    Integer rawValue = descMap.value as Integer
    if (rawValue == null) return

    // Ignore residual level reports when the light is off
    if (device.currentValue("switch") == "off") {
        if (state.diagActive) { diagDebug "Diagnostics Light Level (0x0008/0x0000): raw=${rawValue} (ignored — switch is off)" }
        logTrace "Light level raw: ${rawValue} -> ignored (switch is off)"
        return
    }

    Integer percent = Math.round(rawValue / 2.54)
    if (state.diagActive) { diagDebug "Diagnostics Light Level (0x0008/0x0000): raw=${rawValue} → ${percent}%" }
    if (percent == (device.currentValue("level") as Integer)) return

    // Store for on() restore
    if (percent > 0) {
        state.previousLevel = percent
    }

    // Map percent to BAF level (1-16)
    Integer bafLvl = percentToBafLevel(percent)

    logInfo "Light level is ${percent}% / BAF ${bafLvl}"
    sendEvent(name: "level", value: percent, unit: "%", descriptionText: "${device.displayName} level is ${percent}%")
    sendEvent(name: "bafLevel", value: bafLvl, descriptionText: "BAF level is ${bafLvl}")
}

// ColorTemperatureMireds attr 0x0007 (mireds → Kelvin, clamped to discovered CT range).
private void parseColorTempEvent(Map descMap) {
    if (!hasColorTemp()) { logTrace "parseColorTempEvent: CT not supported, ignoring"; return }
    Integer mireds = descMap.value as Integer
    if (mireds == null || mireds == 0) return

    Integer kelvin = Math.round(1000000 / mireds)
    kelvin = clampToCtBounds(kelvin)

    if (state.diagActive) { diagDebug "Diagnostics Light CT (0x0300/0x0007): ${mireds} mireds → ${kelvin}K" }
    if (kelvin == (device.currentValue("colorTemperature") as Integer)) return

    logTrace "Color temp: ${mireds} mireds -> ${kelvin}K"

    logInfo "Color temperature is ${kelvin}K"
    sendEvent(name: "colorTemperature", value: kelvin, unit: "K", descriptionText: "${device.displayName} color temperature is ${kelvin}K")
    if (device.currentValue("colorMode") != "CT") {
        sendEvent(name: "colorMode", value: "CT")
    }
}

// ---------------------------------------------------------------------------
// EP 02 Mode Select Parsing
// ---------------------------------------------------------------------------

// EP 02 Mode Select: CurrentMode (0x0003) → lightAuto attribute.
private void parseEP02ModeSelect(Integer attr, Map descMap) {
    parseModeSelectCurrentMode(attr, descMap, LIGHT_AUTO_REVERSE, "lightAuto", "EP02 light auto")
}

// Shared Mode Select CurrentMode parser for EP02 (lightAuto) and EP03 (lightMode).
private void parseModeSelectCurrentMode(Integer attr, Map descMap, Map reverseMap, String attrName, String label) {
    if (attr == 0x0003) {
        Integer modeNum = descMap.value as Integer
        if (modeNum == null) return
        String modeName = reverseMap[modeNum] ?: "Unknown (${modeNum})"
        if (modeName.startsWith("Unknown")) {
            logWarn "${label} unknown mode value: ${modeNum}"
        }
        if (state.diagActive) { diagDebug "Diagnostics Light ${label} (0x0050/0x0003): ${modeNum} (${modeName})" }
        if (modeName != device.currentValue(attrName)) {
            String descLabel = (attrName == "lightAuto") ? "Light auto" : "Light mode"
            String desc = "${descLabel} is ${modeName}"
            logInfo desc
            sendEvent(name: attrName, value: modeName, descriptionText: desc)
        }
    } else if (attr == 0x0002) {
        logTrace "${label} SupportedModes received"
    } else {
        logTrace "${label} Mode Select unhandled attr 0x${Integer.toHexString(attr ?: 0)}: ${descMap.value}"
    }
}

// ---------------------------------------------------------------------------
// Light Commands (via parent.sendToDevice)
// ---------------------------------------------------------------------------

void on() {
    logDebug "on()"
    unschedule("off")      // cancel any pending down-ramp deferred off()
    unschedule("refresh")  // cancel any pending ramp-end refresh
    if (settings.useLastLevelForOn != false) {
        Integer target = state.previousLevel ?: 100
        target = Math.max(1, Math.min(100, target))
        logDebug "on: restoring level ${target}%"
        setLevel(target)
    } else if (settings.onLevel != null) {
        Integer target = Math.max(1, Math.min(100, settings.onLevel as Integer))
        logDebug "on: fixed level ${target}%"
        setLevel(target)
    } else {
        String cmd = matter.invoke(EP_LIGHT_STR, CLUSTER_ON_OFF, CMD_ON)
        parent.sendToDevice(cmd)
    }
}

void off() {
    logDebug "off()"
    unschedule("off")      // cancel any pending down-ramp deferred off()
    unschedule("refresh")  // cancel any pending ramp-end refresh
    String cmd = matter.invoke(EP_LIGHT_STR, CLUSTER_ON_OFF, CMD_OFF)
    parent.sendToDevice(cmd)
}

// MoveToLevelWithOnOff (auto-on). Level 0 → off(). Rate in seconds (null = instant).
void setLevel(Object value, Object rate = null) {
    if (value == null) return
    Integer level = Math.max(0, Math.min(100, value as Integer))

    logDebug "setLevel(${level}, rate=${rate})"
    unschedule("off")      // cancel any pending down-ramp deferred off()
    unschedule("refresh")  // cancel any pending ramp-end refresh

    if (level == 0) {
        off()
        return
    }

    Integer ttTenths = (rate != null) ? Math.round((rate as BigDecimal) * 10) as Integer : 0  // rate → tenths

    sendMoveToLevelCmd(level, ttTenths)

    // Refresh after ramp completes to pick up final level
    if (ttTenths > 0) {
        Integer delaySecs = (Math.ceil(ttTenths / 10.0) as Integer) + 1  // +1s buffer
        runIn(delaySecs, "refresh")
    }
}

// BAF discrete level (1-16) → percent → setLevel().
void setBafLevel(Object bafLevel) {
    Integer lvl = Math.max(0, Math.min(16, bafLevel as Integer))
    logDebug "setBafLevel(${lvl})"

    if (lvl == 0) {
        off()
        return
    }

    Integer percent = BAF_LEVEL_TO_PERCENT[lvl]
    if (percent == null) {
        logWarn "setBafLevel: invalid level ${lvl}"
        return
    }
    setLevel(percent)
}

// Kelvin → mireds. Clamped to parent-discovered CT range.
// OptionsMask/OptionsOverride both set to 0x01 (executeIfOff) so the device accepts CT while off.
void setColorTemperature(Object colortemperature, Object level = null, Object transitionTime = null) {
    if (!hasColorTemp()) {
        logInfo "setColorTemperature: not available on this fan"
        return
    }
    if (!hasAdjustableColorTemp()) {
        Integer minK = state.ctMinK as Integer
        Integer maxK = state.ctMaxK as Integer
        if (minK != null && maxK != null && minK == maxK) {
            logInfo "setColorTemperature: fixed CT (${minK}K only)"
        } else {
            logInfo "setColorTemperature: fixed CT (not adjustable)"
        }
        return
    }
    if (colortemperature == null) { logDebug "setColorTemperature: null colortemperature — ignoring"; return }
    Integer ct = clampToCtBounds(colortemperature as Integer)

    logDebug "setColorTemperature(${ct}K, level=${level}, transitionTime=${transitionTime})"

    Integer mireds = Math.round(1000000 / ct)
    Integer ttTenths = (transitionTime != null) ? Math.round((transitionTime as BigDecimal) * 10) as Integer : 0
    String ctValue = toUInt16LE(mireds)
    String hexTransition = toUInt16LE(ttTenths)
    List<Map<String, String>> cmdFields = []
    cmdFields.add(matter.cmdField(0x05, 0x00, ctValue))        // ColorTemperatureMireds (UInt16)
    cmdFields.add(matter.cmdField(0x05, 0x01, hexTransition))  // TransitionTime (UInt16, tenths of a second)
    cmdFields.add(matter.cmdField(0x04, 0x02, "01"))           // OptionsMask     (UInt8) bit0=executeIfOff
    cmdFields.add(matter.cmdField(0x04, 0x03, "01"))           // OptionsOverride (UInt8) bit0=executeIfOff
    String cmd = matter.invoke(EP_LIGHT_STR, CLUSTER_COLOR, CMD_MOVE_TO_CT, cmdFields)
    logTrace "setColorTemperature: ${cmd.length() > 40 ? cmd.take(40) + '…' : cmd}"
    parent.sendToDevice(cmd)

    if (level != null) {
        setLevel(level, transitionTime)
    }
}

// Single MoveToLevelWithOnOff with transition time proportional to distance remaining.
// Device ramps internally. Use stopLevelChange() to freeze mid-ramp.
void startLevelChange(String direction) {
    if (direction != "up" && direction != "down") {
        logWarn "startLevelChange: invalid direction '${direction}' — must be 'up' or 'down'"
        return
    }
    logDebug "startLevelChange(${direction})"

    // Target and distance in BAF levels.
    // bafLevel can be 0 for reasons other than the light being at minimum: stale state on
    // startup, driver restart before the first subscription report, or brief timing gaps.
    // Resolve the most reliable starting position using multiple signals before computing
    // step distance, so a down-ramp is never silently dropped on a stale 0.
    Integer currentBaf = (device.currentValue("bafLevel") as Integer) ?: 0
    if (currentBaf == 0) {
        Integer levelPercent = (device.currentValue("level") as Integer) ?: 0
        if (levelPercent > 0) {
            // level reports user-visible brightness even when bafLevel may still be stale.
            currentBaf = percentToBafLevel(levelPercent)
            logDebug "startLevelChange: bafLevel=0 — resolved from level (${levelPercent}% → BAF ${currentBaf})"
        } else {
            Integer prevPct = (state.previousLevel as Integer) ?: 0
            if (prevPct > 0) {
                // previousLevel persists across restarts — use it as the best available estimate
                currentBaf = percentToBafLevel(prevPct)
                logDebug "startLevelChange: bafLevel=0 — resolved from previousLevel (${prevPct}% → BAF ${currentBaf})"
            } else if (direction == "down") {
                // No reliable level evidence. Bail only with strong confirmation the light is already off.
                // Otherwise use midpoint fallback to avoid over/under estimate extremes.
                if (device.currentValue("switch") == "off") {
                    logDebug "startLevelChange: bafLevel=0, switch=off — light is already off, nothing to ramp down"
                    return
                }
                currentBaf = 8
                logDebug "startLevelChange: bafLevel=0, no reliable level source — assuming midpoint (BAF ${currentBaf}) for down ramp"
            }
            // Up with currentBaf still 0: stepsRemaining = 16 → full ramp, which is safe fallback
        }
    }
    Integer targetBaf
    Integer targetPercent
    Integer stepsRemaining

    if (direction == "up") {
        targetBaf = 16
        targetPercent = 100  // BAF level 16 = 100%
        stepsRemaining = targetBaf - currentBaf
    } else {
        targetBaf = 0
        targetPercent = 0
        stepsRemaining = currentBaf
    }

    if (stepsRemaining <= 0) {
        logDebug "startLevelChange: already at ${direction == 'up' ? 'maximum' : 'minimum'}"
        return
    }

    BigDecimal ratePerStep = (settings.levelRampRate ?: 1) as BigDecimal
    Integer rampSeconds = Math.round(stepsRemaining * ratePerStep) as Integer
    Integer transitionTimeTenths = rampSeconds * 10

    logDebug "startLevelChange: bafLevel ${currentBaf} -> ${targetBaf} (${stepsRemaining} steps, ${rampSeconds}s)"

    if (targetPercent == 0) {
        // Ramp to 1% (lowest visible), then schedule off() — level 0 turns off immediately without ramping.
        sendMoveToLevelCmd(1, transitionTimeTenths)
        runIn(rampSeconds + 1, "off")
    } else {
        sendMoveToLevelCmd(targetPercent, transitionTimeTenths)
        runIn(rampSeconds + 1, "refresh")
    }
}

// StopWithOnOff freezes the ramp; reads back current level after settling.
void stopLevelChange() {
    logDebug "stopLevelChange()"

    unschedule("off")      // cancel pending off() from down-ramp
    unschedule("refresh")  // cancel pending refresh() from up-ramp; readCurrentLevel covers it
    String cmd = matter.invoke(EP_LIGHT_STR, CLUSTER_LEVEL, CMD_STOP_WITH_ONOFF)
    parent.sendToDevice(cmd)
    runInMillis(500, "readCurrentLevel")
}

// Reads current level/switch after a ramp stop.
void readCurrentLevel() {
    logTrace "readCurrentLevel: reading level after ramp stop"
    List<Map<String, String>> paths = []
    paths.add(matter.attributePath(EP_LIGHT_STR, CLUSTER_LEVEL, 0x0000))  // CurrentLevel
    paths.add(matter.attributePath(EP_LIGHT_STR, CLUSTER_ON_OFF, 0x0000)) // OnOff
    String cmd = matter.readAttributes(paths)
    parent.sendToDevice(cmd)
}

// ---------------------------------------------------------------------------
// Mode Select Commands
// ---------------------------------------------------------------------------

void controlAllLights() {
    if (!hasLightMode()) { logInfo "controlAllLights: not available on single-light fans"; return }
    logDebug "controlAllLights()"
    sendModeSelectCommand(EP_LIGHTMODE_STR, LIGHT_MODES["Control All Lights"])
}

void controlDownlight() {
    if (!hasLightMode()) { logInfo "controlDownlight: not available on single-light fans"; return }
    logDebug "controlDownlight()"
    sendModeSelectCommand(EP_LIGHTMODE_STR, LIGHT_MODES["Control Downlight"])
}

void controlUplight() {
    if (!hasLightMode()) { logInfo "controlUplight: not available on single-light fans"; return }
    logDebug "controlUplight()"
    sendModeSelectCommand(EP_LIGHTMODE_STR, LIGHT_MODES["Control Uplight"])
}

// ChangeToMode → Mode Select cluster on the specified endpoint.
private void sendModeSelectCommand(String epStr, Integer modeNum) {
    String hexMode = intToHexStr(modeNum)
    List<Map<String, String>> cmdFields = []
    cmdFields.add(matter.cmdField(0x04, 0x00, hexMode))  // NewMode (UInt8)

    String cmd = matter.invoke(epStr, CLUSTER_MODE_SELECT, CMD_CHANGE_TO_MODE, cmdFields)
    logTrace "sendModeSelectCommand(${epStr}): ${cmd.length() > 40 ? cmd.take(40) + '…' : cmd}"
    parent.sendToDevice(cmd)
}

// MoveToLevelWithOnOff command helper shared by setLevel and startLevelChange.
private void sendMoveToLevelCmd(Integer levelPercent, Integer transitionTimeTenths) {
    String hexLevel = int100ToHex254(levelPercent)
    String hexTransition = toUInt16LE(transitionTimeTenths)
    List<Map<String, String>> cmdFields = []
    cmdFields.add(matter.cmdField(0x04, 0x00, hexLevel))       // Level (UInt8)
    cmdFields.add(matter.cmdField(0x05, 0x01, hexTransition))  // TransitionTime (UInt16)
    String cmd = matter.invoke(EP_LIGHT_STR, CLUSTER_LEVEL, CMD_MOVE_TO_LEVEL_WITH_ONOFF, cmdFields)
    logTrace "sendMoveToLevelCmd(${levelPercent}%, ${transitionTimeTenths / 10.0}s): ${cmd.length() > 40 ? cmd.take(40) + '…' : cmd}"
    parent.sendToDevice(cmd)
}

void lightAutoOn() {
    logDebug "lightAutoOn()"
    sendModeSelectCommand(EP_LIGHT_STR, LIGHT_AUTO_MODES["on"])
}

void lightAutoOff() {
    logDebug "lightAutoOff()"
    sendModeSelectCommand(EP_LIGHT_STR, LIGHT_AUTO_MODES["off"])
}

// ---------------------------------------------------------------------------
// Refresh
// ---------------------------------------------------------------------------

// Reads current state from EP 02 (light) and EP 03 (light mode).
// Keep attribute list in sync with parent's subscribeToAttributes().
void refresh() {
    logDebug "refresh()"

    List<Map<String, String>> paths = []

    // EP 02: Light
    paths.add(matter.attributePath(EP_LIGHT_STR, CLUSTER_ON_OFF, 0x0000))           // OnOff
    paths.add(matter.attributePath(EP_LIGHT_STR, CLUSTER_LEVEL, 0x0000))            // CurrentLevel
    if (hasColorTemp()) {
        paths.add(matter.attributePath(EP_LIGHT_STR, CLUSTER_COLOR, 0x0007))        // ColorTemperatureMireds
    }
    paths.add(matter.attributePath(EP_LIGHT_STR, CLUSTER_MODE_SELECT, 0x0003))      // EP02 CurrentMode (light auto state)

    // EP 03: Light Mode Select — only if fan has 2 lights
    if (hasLightMode()) {
        paths.add(matter.attributePath(EP_LIGHTMODE_STR, CLUSTER_MODE_SELECT, 0x0003))  // EP03 CurrentMode
    }

    logTrace "refresh: reading ${paths.size()} attributes"
    String cmd = matter.readAttributes(paths)
    parent.sendToDevice(cmd)
}

// ---------------------------------------------------------------------------
// Health Status (mirrored from parent)
// ---------------------------------------------------------------------------

// Called by parent to mirror healthStatus.
void setHealthStatus(String value) {
    if (value == device.currentValue("healthStatus")) return
    String descriptionText = "${device.displayName} healthStatus changed to ${value}"
    sendEvent(name: "healthStatus", value: value, descriptionText: descriptionText, isStateChange: true, type: "digital")
    if (value == "online") {
        logInfo "${descriptionText}"
    } else if (value == "offline") {
        logWarn "${descriptionText}"
    } else {
        logDebug "${descriptionText}"
    }
}

// Health Check capability — delegate to parent.
void ping() {
    logDebug "ping() — delegating to parent"
    parent.ping()
}

// ---------------------------------------------------------------------------
// Utility: BAF Level Mapping
// ---------------------------------------------------------------------------

// Percent (0-100) → BAF discrete level (1-16) using BAF range thresholds.
private Integer percentToBafLevel(Integer percent) {
    if (percent == null || percent <= 0) return 0
    switch (true) {
        case (percent <= 4):  return 1
        case (percent <= 10): return 2
        case (percent <= 17): return 3
        case (percent <= 24): return 4
        case (percent <= 30): return 5
        case (percent <= 37): return 6
        case (percent <= 43): return 7
        case (percent <= 50): return 8
        case (percent <= 57): return 9
        case (percent <= 63): return 10
        case (percent <= 70): return 11
        case (percent <= 76): return 12
        case (percent <= 83): return 13
        case (percent <= 90): return 14
        case (percent <= 96): return 15
        default:              return 16
    }
}

// ---------------------------------------------------------------------------
// Utility: Hex Conversion
// ---------------------------------------------------------------------------

// 0-100 → 0-254 hex string for Matter level attributes.
private String int100ToHex254(Integer value) {
    return intToHexStr(Math.round(value * 2.54))
}

// Integer → UInt16 little-endian hex string for Matter command fields.
private String toUInt16LE(Integer value) {
    return zigbee.swapOctets(HexUtils.integerToHexString(value, 2))
}

// ---------------------------------------------------------------------------
// Logging (leveled, with auto-off timer)
// ---------------------------------------------------------------------------

private Map getLogLevelInfo() {
    Integer level = settings.logLevel != null ? settings.logLevel as Integer : 2
    Integer time = settings.logLevelTime != null ? settings.logLevelTime as Integer : 30
    return [level: level, time: time]
}

void checkLogLevel(Map levelInfo = [level: null, time: null]) {
    unschedule("logsOff")
    if (settings.logLevel == null) {
        device.updateSetting("logLevel", [value: "2", type: "enum"])
        levelInfo.level = 2
    }
    if (settings.logLevelTime == null) {
        device.updateSetting("logLevelTime", [value: "30", type: "enum"])
        levelInfo.time = 30
    }
    if (levelInfo.level == null) levelInfo = logLevelInfo

    String logMsg = "Logging level is: ${LOG_LEVELS[levelInfo.level]} (${levelInfo.level})"
    if (levelInfo.level >= 3 && levelInfo.time > 0) {
        logMsg += " for ${LOG_TIMES[levelInfo.time]}"
        runIn(60 * levelInfo.time, "logsOff")
    }
    logInfo logMsg
    if (levelInfo.level <= 2) state.lastLogLevel = levelInfo.level
}

void logsOff() {
    if (logLevelInfo.level >= 3) {
        logWarn "Debug/Trace logging disabled by timer"
        Integer lastLvl = state.lastLogLevel != null ? state.lastLogLevel as Integer : 2
        device.updateSetting("logLevel", [value: lastLvl.toString(), type: "enum"])
        logWarn "Logging level restored to: ${LOG_LEVELS[lastLvl]} (${lastLvl})"
    }
}

void logErr(String msg)   { log.error "${device.displayName}: ${msg}" }
void logWarn(String msg)  { if (logLevelInfo.level >= 1) log.warn "${device.displayName}: ${msg}" }
void logInfo(String msg)  { if (logLevelInfo.level >= 2) log.info "${device.displayName}: ${msg}" }
void logDebug(String msg) { if (logLevelInfo.level >= 3) log.debug "${device.displayName}: ${msg}" }
void logTrace(String msg) { if (logLevelInfo.level >= 4) log.trace "${device.displayName}: ${msg}" }
void diagDebug(String msg) { log.debug "${device.displayName}: ${msg}" }  // Unconditional diagnostic debug

// Called by parent driver to enable/disable diagnostic logging mode.
void setDiagActive(Boolean active) { state.diagActive = active }
