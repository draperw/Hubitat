// Big Ass Fans Matter Fan (Parent)
//
// Hubitat Matter driver for Big Ass Fans ceiling fans over Matter.
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
// Acknowledgments:
//  BAF Matter device insights from @Mavrrick's original BAF Haiku H/I drivers
//  Health check pattern adapted from Matter Advanced Bridge by @kkossev
//  Leveled logging pattern adapted from drivers by @jtp10181
//
// Changelog:
//
// ## [1.0.0] - 2026-03-06
//   - Initial release
//   - Fan control with 7 discrete BAF speeds, auto mode, whoosh, direction
//   - Temperature and occupancy sensor support
//   - Light child device auto-creation via Descriptor PartsList discovery
//   - Light control with 16 discrete BAF levels, auto mode, uplight, downlight control

import groovy.transform.Field
import hubitat.device.HubAction
import hubitat.device.HubMultiAction
import hubitat.device.Protocol

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

@Field static final String VERSION = "1.0.0"

// Endpoint IDs — Integer for parse(Map) comparisons
@Field static final Integer EP_FAN       = 0x01
@Field static final Integer EP_LIGHT     = 0x02
@Field static final Integer EP_LIGHTMODE = 0x03
@Field static final Integer EP_TEMP      = 0x04
@Field static final Integer EP_OCCUPANCY = 0x06

// Endpoint ID strings for Matter API calls and child DNI
@Field static final String EP_LIGHT_STR = "02"

// Matter Cluster IDs
@Field static final Integer CLUSTER_FAN_CONTROL = 0x0202
@Field static final Integer CLUSTER_ON_OFF      = 0x0006
@Field static final Integer CLUSTER_LEVEL       = 0x0008
@Field static final Integer CLUSTER_COLOR       = 0x0300
@Field static final Integer CLUSTER_TEMP        = 0x0402
@Field static final Integer CLUSTER_OCCUPANCY   = 0x0406
@Field static final Integer CLUSTER_MODE_SELECT = 0x0050
@Field static final Integer CLUSTER_BASIC_INFO  = 0x0028
@Field static final Integer CLUSTER_DESCRIPTOR  = 0x001D

// Color Control (0x0300) CT capability probe attributes (EP 02)
@Field static final Integer ATTR_COLOR_MODE                   = 0x0008
@Field static final Integer ATTR_ENHANCED_COLOR_MODE          = 0x4001
@Field static final Integer ATTR_COLOR_CAPABILITIES           = 0x400A
@Field static final Integer ATTR_COLOR_TEMP_PHYSICAL_MIN      = 0x400B
@Field static final Integer ATTR_COLOR_TEMP_PHYSICAL_MAX      = 0x400C
@Field static final Integer ATTR_FEATURE_MAP                  = 0xFFFC
@Field static final Integer ATTR_MODE_SELECT_CURRENT_MODE     = 0x0003

// Fan speed mapping: 7 discrete BAF speeds to percent values (BAF-reported values)
@Field static final Map FAN_SPEED_TO_PERCENT = [
    "Off": 0, "Speed 1": 1, "Speed 2": 18, "Speed 3": 34,
    "Speed 4": 51, "Speed 5": 67, "Speed 6": 84, "Speed 7": 100
]

// FanMode enum values (Matter Fan Control cluster 0x0202, attr 0x0000)
@Field static final Map FAN_MODE_MAP = [
    0: "Off", 1: "Low", 2: "Medium", 3: "High", 4: "On", 5: "Auto"
]

// FanModeSequence (0x0202 / 0x0001) known enum values.
@Field static final Map FAN_MODE_SEQUENCE_MAP = [
    0: "Off/Low/Medium/High",
    1: "Off/Low/High",
    2: "Off/Low/Medium/High/Auto",
    3: "Off/Low/High/Auto",
    4: "Off/High/Auto",
    5: "Off/High"
]

// Driver constants
@Field static final Integer PRESENCE_COUNT_THRESHOLD = 2   // missing 3 checks = offline
@Field static final Integer COMMAND_TIMEOUT = 15            // seconds to wait for command response
@Field static final Integer MAX_PING_MILLISECONDS = 15000   // ignore RTT above this
@Field static final Integer DEFAULT_SPEED_PCT = 34          // default restore speed (BAF Speed 3)

@Field static final Map HealthcheckMethodOpts = [
    defaultValue: 2,
    options     : [0: 'Disabled', 1: 'Activity check', 2: 'Periodic polling']
]
@Field static final Map HealthcheckIntervalOpts = [
    defaultValue: 15,
    options     : [1: 'Every minute (not recommended!)', 15: 'Every 15 Mins', 30: 'Every 30 Mins', 60: 'Every 1 Hour', 240: 'Every 4 Hours', 720: 'Every 12 Hours']
]

// Logging level configuration
@Field static final Map LOG_LEVELS = [0: "Error", 1: "Warn", 2: "Info", 3: "Debug", 4: "Trace"]
@Field static final Map LOG_TIMES  = [0: "Indefinitely", 30: "30 Minutes", 60: "1 Hour", 120: "2 Hours", 180: "3 Hours"]

// ---------------------------------------------------------------------------
// Metadata
// ---------------------------------------------------------------------------

metadata {
    definition(
        name: "BAF Fan Matter",
        namespace: "community",
        author: "draperw",
        singleThreaded: true
    ) {
        capability "Actuator"
        capability "Switch"
        capability "SwitchLevel"
        capability "FanControl"
        capability "TemperatureMeasurement"
        capability "MotionSensor"
        capability "Initialize"
        capability "Configuration"
        capability "Refresh"
        capability "Health Check"

        // Custom fan commands
        command "setSpeed", [[name: "Fan speed*", type: "ENUM", description: "Fan speed to set",
            constraints: FAN_SPEED_TO_PERCENT.collect { k, v -> k }]]
        command "autoOn"
        command "autoOff"
        command "cycleSpeed"
        command "fanSpeedUp"
        command "fanSpeedDown"
        command "directionForward"
        command "directionReverse"
        command "whooshOn"
        command "whooshOff"
        // command "runDiagnostics"       // Dev/debug — single diagnostics entry point
        command "ping"

        // Health check attributes
        attribute "healthStatus", "enum", ["unknown", "offline", "online"]
        attribute "rtt", "number"

        // Custom attributes
        attribute "fanMode", "string"            // Off, Low, Medium, High, On, Auto
        attribute "airflowDirection", "string"   // forward, reverse
        attribute "windMode", "string"           // Normal, Whoosh
        attribute "bafSpeed", "number"           // 1-7 discrete BAF speed

        // All BAF fan variants
        fingerprint endpointId: "01", inClusters: "0003,0004,001D,0202",
            outClusters: "", model: "BAF Fan", manufacturer: "Big Ass Fans",
            controllerType: "MAT"
        fingerprint endpointId: "02", inClusters: "0003,0004,0005,0006,0008,001D,0040,0050,0300",
            outClusters: "", model: "BAF Fan", manufacturer: "Big Ass Fans",
            controllerType: "MAT"
        fingerprint endpointId: "03", inClusters: "0003,001D,0040,0050",
            outClusters: "", model: "BAF Fan", manufacturer: "Big Ass Fans",
            controllerType: "MAT"
        fingerprint endpointId: "04", inClusters: "0003,001D,0402",
            outClusters: "", model: "BAF Fan", manufacturer: "Big Ass Fans",
            controllerType: "MAT"
        fingerprint endpointId: "06", inClusters: "0003,001D,0406",
            outClusters: "", model: "BAF Fan", manufacturer: "Big Ass Fans",
            controllerType: "MAT"
    }

    preferences {
        input name: "healthCheckMethod", type: "enum", title: "Health Check Method",
            description: "Method to check device online/offline status",
            defaultValue: HealthcheckMethodOpts.defaultValue, options: HealthcheckMethodOpts.options, required: true
        input name: "healthCheckInterval", type: "enum", title: "Health Check Interval",
            description: "How often the hub checks device health. 3 consecutive failures = offline.",
            defaultValue: HealthcheckIntervalOpts.defaultValue, options: HealthcheckIntervalOpts.options, required: true
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
    logInfo "installed() — BAF Fan Matter v${VERSION}"
    state.previousSpeed = DEFAULT_SPEED_PCT  // default to speed 3; updated by parseFanPercentCurrent()
    state.health = [:]
    state.stats = [:]
    state.states = [:]
    state.lastTx = [:]
    initialize()
}

void updated() {
    logDebug "updated()"
    checkLogLevel()
    initHealthCheck()
    subscribeToAttributes()
}

void initialize() {
    logInfo "initialize()"
    device.updateDataValue("newParse", "true")

    sendEvent(name: "supportedFanSpeeds", value: groovy.json.JsonOutput.toJson(
        FAN_SPEED_TO_PERCENT.collect { k, v -> k }))  // FanControl capability requirement

    // Discover endpoints — light child and CT probe are deferred until PartsList arrives.
    discoverEndpoints()

    if (hasLight() && !getChildDevice(lightChildDNI())) {
        createLightChild()
    }
    def child = getChildDevice(lightChildDNI())
    if (child) {
        syncLightProfileToChild()
        // Sync health status — child may be stale (e.g., just created, or driver reinstalled)
        sendHealthStatusToChild(device.currentValue("healthStatus") ?: "unknown")
    }

    subscribeToAttributes()
    if (hasLight()) {
        runIn(2, "probeColorCapabilities")
    }
    initHealthCheck()

    // Read device metadata (model, name, firmware version)
    sendToDevice(matter.readAttributes([
        matter.attributePath(0x00, CLUSTER_BASIC_INFO, 0x0003),  // ProductName → model
        matter.attributePath(0x00, CLUSTER_BASIC_INFO, 0x0005),  // NodeLabel → matterName
        matter.attributePath(0x00, CLUSTER_BASIC_INFO, 0x000A)   // SoftwareVersionString → softwareVersion
    ]))

    runIn(2, "refresh")
}

void configure() {
    logWarn "configure()"
    initialize()
}

void uninstalled() {
    logInfo "uninstalled() — removing child devices"
    childDevices.each { deleteChildDevice(it.deviceNetworkId) }
}

// ---------------------------------------------------------------------------
// Endpoint Discovery (Descriptor Cluster 0x001D)
// ---------------------------------------------------------------------------

// Capability snapshot accessors.
// CT/light topology are runtime-discovered via probeColorCapabilities().
private Map getCapabilitySnapshot() {
    if (!(state.capabilitySnapshot instanceof Map)) {
        state.capabilitySnapshot = [:]
    }
    return state.capabilitySnapshot as Map
}

private void updateCapabilitySnapshot(Map updates) {
    Map snap = getCapabilitySnapshot()
    updates?.each { k, v -> snap[k] = v }
    state.capabilitySnapshot = snap
}


private Integer getLightCount()  { return safeToInt(getCapabilitySnapshot().lightCount, 0) }
private Boolean hasLight()       { return getLightCount() > 0 }
private Boolean hasLightMode()   { return getLightCount() >= 2 }  // 2 lights = uplight + downlight
private Boolean hasTemp()        { def eps = getCapabilitySnapshot().endpoints; return (eps != null) ? eps.contains(EP_TEMP) : false }
private Boolean hasOccupancy()   { def eps = getCapabilitySnapshot().endpoints; return (eps != null) ? eps.contains(EP_OCCUPANCY) : false }

private Boolean hasColorTemp()   { Map snap = getCapabilitySnapshot(); return (snap.ctPresent != null) ? (snap.ctPresent == true) : true }
private Boolean hasAdjustableColorTemp() { Map snap = getCapabilitySnapshot(); return (snap.ctAdjustable != null) ? (snap.ctAdjustable == true) : true }
private String getLightProfile() { return (getCapabilitySnapshot().lightProfile ?: "unknown").toString() }
private void discoverEndpoints() {
    logDebug "discoverEndpoints() — reading Descriptor PartsList from EP 00"
    String cmd = matter.readAttributes([
        matter.attributePath(0x00, CLUSTER_DESCRIPTOR, 0x0003)  // PartsList
    ])
    sendToDevice(cmd)
}

// Parses PartsList — determines lightCount and creates/removes child devices.
private void parseDescriptor(Integer attr, Map descMap) {
    if (attr != 0x0003) {
        logTrace "EP00 Descriptor unhandled attr 0x${Integer.toHexString(attr ?: 0)}: ${descMap.value}"
        return
    }

    logDebug "Descriptor PartsList raw: ${descMap.value}"

    List endpoints = []
    if (descMap.value instanceof List) {
        descMap.value.each { ep ->
            if (ep instanceof Integer) {
                endpoints.add(ep)
            } else {
                // Try to parse from string or tagged struct
                try { endpoints.add(ep as Integer) } catch (e) { /* skip */ }
            }
        }
    }

    Map snap = getCapabilitySnapshot()
    List oldEndpoints = (snap.endpoints instanceof List) ? (snap.endpoints as List).sort() : null
    Boolean topologyChanged = (oldEndpoints == null || oldEndpoints != endpoints.sort())

    snap.endpoints = endpoints
    logInfo "Discovered endpoints: ${endpoints}"

    Boolean hasEP02 = endpoints.contains(EP_LIGHT)
    Boolean hasEP03 = endpoints.contains(EP_LIGHTMODE)
    Integer newLightCount = hasEP02 ? (hasEP03 ? 2 : 1) : 0

    if (newLightCount != safeToInt(snap.lightCount, null)) {
        logInfo "Light count: ${newLightCount} (EP02=${hasEP02}, EP03=${hasEP03})"
    }
    snap.lightCount = newLightCount
    if (newLightCount == 0) {
        snap.lightProfile = "no_light"
        snap.ctPresent = false
        snap.ctAdjustable = false
        snap.ctMinK = null
        snap.ctMaxK = null
    } else if ((snap.lightProfile ?: "unknown") == "no_light") {
        snap.lightProfile = "unknown"
    }
    state.capabilitySnapshot = snap

    if (hasEP02) {
        if (!getChildDevice(lightChildDNI())) {
            createLightChild()
        }
        // Inform child of light count and current parent capability snapshot.
        def child = getChildDevice(lightChildDNI())
        if (child) {
            child.setLightCount(newLightCount)
            syncLightProfileToChild()
        }
        if (!state.diagActive && topologyChanged) {
            unschedule("probeColorCapabilities")
            runIn(1, "probeColorCapabilities")
        }
    } else {
        // No light endpoint — remove orphan child if one exists from a previous config
        def orphan = getChildDevice(lightChildDNI())
        if (orphan) {
            logWarn "No light endpoint (EP 02) found — removing orphan light child"
            deleteChildDevice(orphan.deviceNetworkId)
        } else {
            logInfo "No light endpoint (EP 02) found — no light child needed"
        }
    }

    // Re-subscribe when topology changes so EP04/EP06 (temp/occupancy) are included.
    // unschedule() guards against duplicate queued runs from noisy descriptor traffic.
    if (topologyChanged) {
        unschedule("subscribeToAttributes")
        runIn(1, "subscribeToAttributes")
    }
}

// Logs Descriptor diagnostics for any endpoint.
private void parseDescriptorDiagnostics(Integer ep, Integer attr, Map descMap) {
    if (!state.diagActive) return
    String attrName = (attr == 0x0000) ? "DeviceTypeList" : (attr == 0x0001) ? "ServerList" : (attr == 0x0003) ? "PartsList" : "Attr"
    String epHex   = String.format("0x%02X", ep ?: 0)
    String attrHex = String.format("0x%04X", attr ?: 0)
    diagDebug "Diagnostics Descriptor ${attrName} EP${epHex} (${attrHex}): ${descMap?.value}"
}

// ---------------------------------------------------------------------------
// Child Device Management
// ---------------------------------------------------------------------------

private String lightChildDNI() {
    return "${device.deviceNetworkId}-${EP_LIGHT_STR}"
}

void createLightChild() {
    String dni = lightChildDNI()
    if (getChildDevice(dni)) {
        logDebug "Light child already exists: ${dni}"
        return
    }
    logInfo "Creating light child device: ${dni}"
    try {
        def child = addChildDevice("community", "BAF Light Matter", dni,
            [name: "${device.displayName} Light", isComponent: false,
             endpointId: EP_LIGHT_STR])
        // Mirror current health status immediately — the transition-based mirror fires
        // only on status changes, so if the parent is already online when the child is
        // created, the child would otherwise stay at its initialized "unknown" value.
        String currentHealth = device.currentValue("healthStatus") ?: "unknown"
        child.setHealthStatus(currentHealth)
    } catch (e) {
        logErr "Failed to create light child device: ${e.message}"
    }
}

private void forwardToLightChild(Map descMap) {
    // Defer child call to avoid deadlock: parse() holds the parent semaphore while
    // calling the child, and child command methods (off, setLevel, etc.) call
    // parent.sendToDevice() which needs the parent semaphore. If both run
    // simultaneously, each blocks on the other's lock. The 50ms defer lets parse()
    // complete and release the parent semaphore before the child is invoked.
    // Child existence is checked in the deferred handler — no need to look it up here.
    runInMillis(50, "deferredForwardToLightChild", [data: descMap, overwrite: false])
}

// Deferred handler for forwardToLightChild — called via runInMillis to break the
// parse() → child → parent.sendToDevice() deadlock potential.
void deferredForwardToLightChild(Map descMap) {
    def child = getChildDevice(lightChildDNI())
    if (child) {
        child.parseFromParent(descMap)
    } else {
        logWarn "Light child not found (${lightChildDNI()}) — cannot forward deferred message"
    }
}

// ---------------------------------------------------------------------------
// Parse — newParse:true entry point
// ---------------------------------------------------------------------------

// Hubitat delivers pre-parsed Matter attributes as a Map: endpointInt, clusterInt, attrInt, value
def parse(Map descMap) {
    if (!descMap) return

    // Health check: mark device as online on any received message
    setHealthStatusOnline()
    unschedule("deviceCommandTimeout")
    if (state.stats == null) { state.stats = [:] }
    state.stats["rxCtr"] = (state.stats["rxCtr"] ?: 0) + 1

    try {
        Integer ep   = descMap.endpointInt
        Integer clus = descMap.clusterInt
        Integer attr = descMap.attrInt

        if (ep == null || clus == null) {
            // SubscriptionResult callbacks have no endpoint/cluster — silently ignore
            if (descMap.containsKey("callbackType")) return
            logDebug "parse: missing endpoint or cluster — ${descMap}"
            return
        }

        if (clus == CLUSTER_DESCRIPTOR && (attr in [0x0000, 0x0001, 0x0003])) {
            parseDescriptorDiagnostics(ep, attr, descMap)
            if (ep == 0x00 && attr == 0x0003) {
                parseDescriptor(attr, descMap)
            }
            return
        }

        // Raw dump of every attribute received — diagnostics only (trace level).
        if (state.diagActive) {
            String epHex   = String.format("0x%02X", ep)
            String clusHex = String.format("0x%04X", clus)
            String attrHex = String.format("0x%04X", attr)
            diagTrace "RAW EP${epHex} cluster=${clusHex} attr=${attrHex} value=${descMap?.value} data=${descMap?.data}"
        }

        // EP02 Color Control responses are processed by the parent for CT capability detection
        // AND forwarded to the child below. The child receives probe-only attrs (FeatureMap,
        // ColorCapabilities, min/max mired, EnhancedColorMode) during probeColorCapabilities();
        // the child driver is expected to ignore attrs it does not handle.
        if (ep == EP_LIGHT && clus == CLUSTER_COLOR) {
            parseCtProbeResponse(attr, descMap)
        }

        switch (ep) {
            case EP_FAN:
                parseFanCluster(clus, attr, descMap)
                break
            case EP_LIGHT:
                if (hasLight()) {
                    forwardToLightChild(descMap)
                } else {
                    logDebug "EP02 message ignored (no light): ${descMap}"
                }
                break
            case EP_LIGHTMODE:
                if (hasLightMode()) {
                    forwardToLightChild(descMap)
                } else {
                    logTrace "EP03 message ignored (single-light fan): ${descMap}"
                }
                break
            case EP_TEMP:
                if (clus == CLUSTER_TEMP && attr == 0x0000) {
                    parseTemperature(descMap)
                } else {
                    logTrace "EP04 unhandled: ${descMap}"
                }
                break
            case EP_OCCUPANCY:
                if (clus == CLUSTER_OCCUPANCY && attr == 0x0000) {
                    parseOccupancy(descMap)
                } else {
                    logTrace "EP06 unhandled: ${descMap}"
                }
                break
            case 0x00:
                if (clus == CLUSTER_BASIC_INFO) {
                    parseBasicInfo(attr, descMap)
                } else {
                    logTrace "EP00 unhandled cluster 0x${Integer.toHexString(clus)}: ${descMap}"
                }
                break
            default:
                logDebug "Unhandled endpoint ${ep}: ${descMap}"
        }
    } catch (e) {
        logErr "parse(Map) exception: ${e.message} — descMap: ${descMap}"
    }
}

// Legacy String-based parse — should not be called with newParse:true.
def parse(String description) {
    logWarn "Unexpected legacy parse(String) called: ${description}"
}

// Parses targeted Color Control probe attributes to infer CT support.
private void parseCtProbeResponse(Integer attr, Map descMap) {
    if (attr == null) return
    if (!(attr in [ATTR_FEATURE_MAP, ATTR_COLOR_CAPABILITIES, ATTR_COLOR_TEMP_PHYSICAL_MIN, ATTR_COLOR_TEMP_PHYSICAL_MAX, ATTR_COLOR_MODE, ATTR_ENHANCED_COLOR_MODE])) return

    String attrHex = String.format("0x%04X", attr)
    if (state.diagActive) {
        Integer v = safeToInt(descMap?.value, null)
        switch (attr) {
            case ATTR_FEATURE_MAP:
                diagDebug "Diagnostics CT FeatureMap (0xFFFC): ${v} (CT bit=${((v ?: 0) & 0x10) != 0})"
                break
            case ATTR_COLOR_CAPABILITIES:
                diagDebug "Diagnostics CT ColorCapabilities (0x400A): ${v} (CT bit=${((v ?: 0) & 0x10) != 0})"
                break
            case ATTR_COLOR_TEMP_PHYSICAL_MIN:
                Integer minK = (v != null && v > 0) ? Math.round(1000000.0 / v as float) as Integer : null
                diagDebug "Diagnostics CT MinMireds (0x400B): ${v}${minK != null ? " (~${minK}K)" : ''}"
                break
            case ATTR_COLOR_TEMP_PHYSICAL_MAX:
                Integer maxK = (v != null && v > 0) ? Math.round(1000000.0 / v as float) as Integer : null
                diagDebug "Diagnostics CT MaxMireds (0x400C): ${v}${maxK != null ? " (~${maxK}K)" : ''}"
                break
            case ATTR_COLOR_MODE:
                diagDebug "Diagnostics CT ColorMode (0x0008): ${v} (${colorModeName(v)})"
                break
            case ATTR_ENHANCED_COLOR_MODE:
                diagDebug "Diagnostics CT EnhancedColorMode (0x4001): ${v} (${colorModeName(v)})"
                break
            default:
                diagDebug "Diagnostics CT EP02/0x0300 ${attrHex}: ${v}"
                break
        }
    }

    updateDetectedColorTempSupport(attrHex, descMap)
}

// Attempts a conservative CT support determination from probe responses.
private void updateDetectedColorTempSupport(String attrHex, Map descMap) {
    if (!(state.ctProbeWork instanceof Map)) state.ctProbeWork = [:]
    state.ctProbeWork[attrHex] = [
        value      : descMap.value,
        callback   : descMap.callbackType,
        unsupported: isUnsupportedMatterResponse(descMap)
    ]
    Map probe = (Map) state.ctProbeWork
    Map minEntry = probe["0x400B"] instanceof Map ? (Map) probe["0x400B"] : null
    Map maxEntry = probe["0x400C"] instanceof Map ? (Map) probe["0x400C"] : null

    Integer minMired = safeToInt(minEntry?.value, null)
    Integer maxMired = safeToInt(maxEntry?.value, null)
    Boolean minUnsupported = (minEntry?.unsupported == true)
    Boolean maxUnsupported = (maxEntry?.unsupported == true)

    Boolean ctPresent = null
    Boolean ctAdjustable = null
    Integer minK = null
    Integer maxK = null

    if (minMired != null && maxMired != null && minMired > 0 && maxMired > 0 && maxMired >= minMired) {
        ctPresent = true
        ctAdjustable = (maxMired > minMired)
        minK = Math.round(1000000.0 / maxMired as float) as Integer
        maxK = Math.round(1000000.0 / minMired as float) as Integer
    } else if (minUnsupported && maxUnsupported) {
        ctPresent = false
        ctAdjustable = false
    }

    if (ctPresent == null) return

    Map snap = getCapabilitySnapshot()
    String profile = deriveLightProfile(getLightCount(), ctPresent, ctAdjustable)
    Boolean snapCtPresent = snap.containsKey("ctPresent") ? (snap.ctPresent as Boolean) : null
    Boolean snapCtAdjustable = snap.containsKey("ctAdjustable") ? (snap.ctAdjustable as Boolean) : null
    Boolean changed = (
        ctPresent != snapCtPresent ||
        ctAdjustable != snapCtAdjustable ||
        minK != safeToInt(snap.ctMinK, null) ||
        maxK != safeToInt(snap.ctMaxK, null) ||
        profile != (snap.lightProfile ?: "unknown")
    )
    if (!changed) {
        state.remove("ctProbeWork")
        return
    }

    updateCapabilitySnapshot([
        ctPresent   : ctPresent,
        ctAdjustable: ctAdjustable,
        ctMinK      : minK,
        ctMaxK      : maxK,
        lightProfile: profile
    ])

    if (state.diagActive) {
        diagDebug "Diagnostics Light classification: profile=${profile}, ctPresent=${ctPresent}, ctAdjustable=${ctAdjustable}, ctRange=${minK ?: '?'}-${maxK ?: '?'}K"
    } else {
        logDebug "Light profile detected: ${profile} (ctPresent=${ctPresent}, ctAdjustable=${ctAdjustable}, range=${minK ?: '?'}-${maxK ?: '?'}K)"
    }
    syncLightProfileToChild()
    state.remove("ctProbeWork")
}

// Tries to detect unsupported-attribute responses across varying descMap shapes.
private Boolean isUnsupportedMatterResponse(Map descMap) {
    String blob = "${descMap?.status ?: ''} ${descMap?.error ?: ''} ${descMap?.reason ?: ''} ${descMap?.data ?: ''} ${descMap ?: ''}".toUpperCase()
    return blob.contains("UNSUPPORTED_ATTRIBUTE") || blob.contains("UNSUPPORTED")
}

private String colorModeName(Integer mode) {
    switch (mode) {
        case 0: return "CurrentHueCurrentSaturation"
        case 1: return "CurrentXCurrentY"
        case 2: return "ColorTemperatureMireds"
        case 3: return "EnhancedCurrentHueAndSaturation"
        default: return "Unknown"
    }
}

private String deriveLightProfile(Integer lightCount, Boolean ctPresent, Boolean ctAdjustable) {
    if ((lightCount ?: 0) <= 0) return "no_light"
    if (ctPresent != true) return (lightCount >= 2) ? "dual_light_dimmer" : "single_light_dimmer"
    if (ctAdjustable == true) return (lightCount >= 2) ? "dual_light_ct" : "single_light_ct"
    return (lightCount >= 2) ? "dual_light_dimmer" : "single_light_dimmer"
}

private void syncLightProfileToChild() {
    def child = getChildDevice(lightChildDNI())
    if (!child) return

    Map snap = getCapabilitySnapshot()
    child.setHasColorTemp(hasColorTemp())
    child.setColorTempAdjustable(hasAdjustableColorTemp())
    child.setColorTempRangeK(safeToInt(snap.ctMinK, null), safeToInt(snap.ctMaxK, null))
}

// Called by light child on initialize() to re-sync all capability state after an independent restart.
void syncChildState() {
    def child = getChildDevice(lightChildDNI())
    if (!child) return
    logDebug "syncChildState: pushing capability state to light child"
    child.setLightCount(getLightCount())
    syncLightProfileToChild()
}


// ---------------------------------------------------------------------------
// Fan Cluster Parsing (EP 01, Cluster 0x0202)
// ---------------------------------------------------------------------------
// Fan Control values arrive as Integer with newParse:true; `as Integer` cast is sufficient.
// Exception: On/Off arrives as Boolean — handled in child driver with instanceof.

private void parseFanCluster(Integer clus, Integer attr, Map descMap) {
    if (clus != CLUSTER_FAN_CONTROL) {
        logTrace "EP01 non-fan cluster 0x${Integer.toHexString(clus)}: ${descMap}"
        return
    }

    switch (attr) {
        case 0x0000:  // FanMode
            parseFanMode(descMap)
            break
        case 0x0001:  // FanModeSequence — probe-only, not subscribed
            if (state.diagActive) {
                Integer seq = safeToInt(descMap?.value, null)
                String seqName = FAN_MODE_SEQUENCE_MAP[seq] ?: "Unknown (${seq})"
                diagDebug "Diagnostics Fan FanModeSequence (0x0202/0x0001): ${seq} (${seqName})"
            }
            break
        case 0x0002:  // PercentSetting (writable — what we requested)
            parseFanPercentSetting(descMap)
            break
        case 0x0003:  // PercentCurrent (read-only — actual fan speed)
            parseFanPercentCurrent(descMap)
            break
        case 0x0004:  // SpeedMax — probe-only, not subscribed
        case 0x0005:  // SpeedSetting — probe-only, not subscribed
        case 0x0006:  // SpeedCurrent — probe-only, not subscribed
            if (state.diagActive) {
                diagDebug "Diagnostics Fan Speed attr 0x${String.format('%04X', attr)}: ${descMap?.value}"
            }
            break
        case 0x000A:  // WindSetting
            parseWindSetting(descMap)
            break
        case 0x000B:  // AirflowDirection
            parseAirflowDirection(descMap)
            break
        case ATTR_FEATURE_MAP:  // Fan Control FeatureMap (0xFFFC) — probe-only, not subscribed
            if (state.diagActive) {
                Integer v = safeToInt(descMap?.value, null)
                diagDebug "Diagnostics Fan FeatureMap (0x0202/0xFFFC): ${v} (${v != null ? String.format('0x%02X', v) : 'null'})"
            }
            break
        default:
            if (state.diagActive) {
                diagDebug "Diagnostics Fan unknown attr 0x${String.format('%04X', attr ?: 0)}: ${descMap?.value}"
            } else {
                logDebug "Unhandled fan attr 0x${String.format('%04X', attr ?: 0)}: ${descMap?.value}"
            }
    }
}

// Parses FanMode attribute (0x0000). Values: 0=Off,1=Low,2=Medium,3=High,4=On,5=Auto
private void parseFanMode(Map descMap) {
    Integer value = descMap.value as Integer
    if (value == null) return

    String mode = FAN_MODE_MAP[value] ?: "Unknown (${value})"
    if (state.diagActive) { diagDebug "Diagnostics Fan FanMode (0x0202/0x0000): ${value} (${mode})" }

    if (mode != device.currentValue("fanMode")) {
        String desc = "Fan mode is ${mode}"
        logInfo desc
        sendEvent(name: "fanMode", value: mode, descriptionText: desc)
    }

    // Update switch based on mode (FanMode is the authoritative source for switch)
    String switchValue = (value == 0) ? "off" : "on"
    if (switchValue != device.currentValue("switch")) {
        String switchDesc = "${device.displayName} was turned ${switchValue}"
        logInfo switchDesc
        sendEvent(name: "switch", value: switchValue, descriptionText: switchDesc)
    }

}

// Parses PercentSetting attribute (0x0002) — the requested/written speed.
// Informational only; does NOT update display attributes or store previousSpeed.
// Display and previousSpeed are driven by PercentCurrent (0x0003).
private void parseFanPercentSetting(Map descMap) {
    if (descMap.value == null) {
        if (state.diagActive) { diagDebug "Diagnostics Fan PercentSetting (0x0202/0x0002): null" }
        logTrace "Fan PercentSetting: null"
    } else {
        if (state.diagActive) { diagDebug "Diagnostics Fan PercentSetting (0x0202/0x0002): ${descMap.value as Integer}%" }
        logTrace "Fan PercentSetting: ${descMap.value as Integer}%"
    }
}

// Parses PercentCurrent attribute (0x0003) — the actual fan speed.
// This is the read-only value reflecting what the fan is really doing,
// and is the primary source for speed/level display in Hubitat.
// Maps percent 0-100 to bafSpeed 1-7 and standard FanControl speed names.
private void parseFanPercentCurrent(Map descMap) {
    Integer percent = descMap.value as Integer
    if (percent == null) return

    // Store previous speed for on()/autoOff() restore (non-zero, non-Auto only).
    // In Auto mode, PercentCurrent reflects the auto-chosen speed, not the user's intent.
    if (percent > 0 && device.currentValue("fanMode") != "Auto") {
        state.previousSpeed = percent
    }

    // Map percent to BAF discrete speed (1-7) and FanControl speed name
    Integer bafSpd
    String speedName
    switch (true) {
        case (percent == 0):
            bafSpd = 0; speedName = "Off"; break
        case (percent <= 9):
            bafSpd = 1; speedName = "Speed 1"; break
        case (percent <= 25):
            bafSpd = 2; speedName = "Speed 2"; break
        case (percent <= 42):
            bafSpd = 3; speedName = "Speed 3"; break
        case (percent <= 58):
            bafSpd = 4; speedName = "Speed 4"; break
        case (percent <= 75):
            bafSpd = 5; speedName = "Speed 5"; break
        case (percent <= 91):
            bafSpd = 6; speedName = "Speed 6"; break
        default:
            bafSpd = 7; speedName = "Speed 7"; break
    }

    if (state.diagActive) { diagDebug "Diagnostics Fan PercentCurrent (0x0202/0x0003): ${percent}% → bafSpeed ${bafSpd} (${speedName})" }
    logTrace "Fan PercentCurrent: ${percent}% → bafSpeed ${bafSpd} (${speedName})"

    // Only send events if values actually changed
    Boolean speedChanged = (speedName != device.currentValue("speed"))
    Boolean bafChanged   = (bafSpd != (device.currentValue("bafSpeed") as Integer))

    if (speedChanged || bafChanged) {
        String desc = "Fan speed is ${speedName} / BAF ${bafSpd} (${percent}%)"
        logInfo desc
    }

    if (speedChanged) {
        sendEvent(name: "speed", value: speedName, descriptionText: "Fan speed is ${speedName} (${percent}%)")
    }

    if (percent != (device.currentValue("level") as Integer)) {
        sendEvent(name: "level", value: percent, unit: "%",
            descriptionText: "Fan level is ${percent}%")
    }

    if (bafChanged) {
        sendEvent(name: "bafSpeed", value: bafSpd, descriptionText: "BAF speed is ${bafSpd}")
    }
    // Note: switch state is managed by parseFanMode, not here
}

// Parses WindSetting attribute (0x000A). Values: 0=Normal (Sleep Wind), 2=Whoosh (Natural Wind).
private void parseWindSetting(Map descMap) {
    Integer value = descMap.value as Integer
    if (value == null) return

    String mode = (value == 2) ? "Whoosh" : "Normal"
    if (state.diagActive) { diagDebug "Diagnostics Fan WindSetting (0x0202/0x000A): ${value} (${mode})" }
    if (mode == device.currentValue("windMode")) return

    String desc = "Wind mode is ${mode}"
    logInfo desc
    sendEvent(name: "windMode", value: mode, descriptionText: desc)
}

// Parses AirflowDirection attribute (0x000B). Values: 0=Forward, 1=Reverse.
private void parseAirflowDirection(Map descMap) {
    Integer value = descMap.value as Integer
    if (value == null) return

    String direction = (value == 0) ? "Forward" : "Reverse"
    if (state.diagActive) { diagDebug "Diagnostics Fan AirflowDirection (0x0202/0x000B): ${value} (${direction})" }
    if (direction == device.currentValue("airflowDirection")) return

    String desc = "Airflow direction is ${direction}"
    logInfo desc
    sendEvent(name: "airflowDirection", value: direction, descriptionText: desc)
}

// ---------------------------------------------------------------------------
// Temperature Parsing (EP 04, Cluster 0x0402)
// ---------------------------------------------------------------------------

// Parses temperature from Matter (centidegrees Celsius). Respects hub temp scale.
private void parseTemperature(Map descMap) {
    Integer rawValue = descMap.value as Integer
    if (rawValue == null || rawValue == 0x8000) {  // 0x8000 = Matter "invalid/not available" sentinel
        logDebug "Temperature: invalid/null value (${rawValue})"
        return
    }

    // Matter temperature is in centidegrees Celsius (value / 100 = °C)
    BigDecimal tempC = rawValue / 100.0
    if (state.diagActive) { diagDebug "Diagnostics Temperature (0x0402/0x0000): raw=${rawValue} → ${tempC}°C" }
    logTrace "Temperature raw: ${rawValue} → ${tempC}°C"

    BigDecimal temp
    String unit
    if (location.temperatureScale == "F") {
        temp = (tempC * 1.8 + 32).setScale(1, BigDecimal.ROUND_HALF_UP)
        unit = "°F"
    } else {
        temp = tempC.setScale(1, BigDecimal.ROUND_HALF_UP)
        unit = "°C"
    }

    if (temp == (device.currentValue("temperature") as BigDecimal)) return

    String desc = "Temperature is ${temp}${unit}"
    logInfo desc
    sendEvent(name: "temperature", value: temp, unit: unit, descriptionText: desc)
}

// ---------------------------------------------------------------------------
// Occupancy Parsing (EP 06, Cluster 0x0406)
// ---------------------------------------------------------------------------

// Parses occupancy sensing as motion (active/inactive).
private void parseOccupancy(Map descMap) {
    Integer value = descMap.value as Integer
    if (value == null) return

    String motion = (value & 0x01) ? "active" : "inactive"
    if (state.diagActive) { diagDebug "Diagnostics Occupancy (0x0406/0x0000): ${value} (${motion})" }
    if (motion == device.currentValue("motion")) return

    String desc = "Motion is ${motion}"
    logInfo desc
    sendEvent(name: "motion", value: motion, descriptionText: desc)
}

// ---------------------------------------------------------------------------
// Basic Information Parsing (EP 00, Cluster 0x0028) — trace logging only
// ---------------------------------------------------------------------------

// Parses Basic Information attributes. Key fields are stored in Device Data.
// Also handles ping response: ping reads attr 0x0000 (DataModelRevision).
private void parseBasicInfo(Integer attr, Map descMap) {
    // Handle ping response — attr 0x0000 is what ping() reads
    if (attr == 0x0000 && state.states != null && state.states["isPing"] == true) {
        state.states["isPing"] = false
        sendRttEvent()
    }

    // Map of attr -> [logName, deviceDataKey (null = trace-only)]
    Map attrInfo = [
        0x0000: [name: "DataModelRevision",      dataKey: null],
        0x0001: [name: "VendorName",             dataKey: null],
        0x0002: [name: "VendorID",               dataKey: null],
        0x0003: [name: "ProductName",            dataKey: "model"],
        0x0004: [name: "ProductID",              dataKey: null],
        0x0005: [name: "NodeLabel",              dataKey: "matterName"],
        0x0006: [name: "Location",               dataKey: null],
        0x0007: [name: "HardwareVersion",        dataKey: null],
        0x0008: [name: "HardwareVersionString",  dataKey: null],
        0x0009: [name: "SoftwareVersion",        dataKey: null],
        0x000A: [name: "SoftwareVersionString",  dataKey: "softwareVersion"],
        0x0012: [name: "UniqueID",               dataKey: null]
    ]

    Map info = attrInfo[attr]
    String name = info?.name ?: "attr 0x${Integer.toHexString(attr)}"
    String value = descMap.value?.toString()
    logTrace "BasicInfo: ${name} = ${value}"

    // Store key fields in Device Data (always updated)
    if (info?.dataKey && value) {
        device.updateDataValue(info.dataKey, value)
    }
}

// ---------------------------------------------------------------------------
// Fan Commands (EP 01, Cluster 0x0202)
// ---------------------------------------------------------------------------

// All fan commands write PercentSetting (attr 0x0002, UInt8) to control speed.
// FanMode auto-updates from the written percent. Only autoOn() writes FanMode directly.
// Writing any PercentSetting while in Auto mode implicitly exits Auto.

// Writes a single Fan Control attribute on EP 01 and schedules command timeout.
private void writeFanAttr(Integer attrId, Integer value) {
    sendToDevice(matter.writeAttributes([
        matter.attributeWriteRequest(EP_FAN, CLUSTER_FAN_CONTROL, attrId, 0x04, intToHexStr(value))
    ]))
    scheduleCommandTimeoutCheck()
}

// Returns the previous non-zero speed to restore on on()/autoOff(), defaulting to speed 3.
private Integer getRestoredSpeed() {
    Integer prev = (state.previousSpeed ?: DEFAULT_SPEED_PCT) as Integer
    return (prev > 0) ? prev : DEFAULT_SPEED_PCT
}

void on() {
    logDebug "on()"
    Integer prevSpeed = getRestoredSpeed()
    logDebug "on() — restoring previousSpeed: ${prevSpeed}%"
    writeFanAttr(0x0002, prevSpeed)  // PercentSetting = previous
}

void off() {
    logDebug "off()"
    writeFanAttr(0x0002, 0)          // PercentSetting = 0
}

void setSpeed(String fanspeed) {
    logDebug "setSpeed(${fanspeed})"
    if (fanspeed == "Off") { off(); return }
    Integer percent = FAN_SPEED_TO_PERCENT[fanspeed]
    if (percent == null) { logWarn "setSpeed: unknown speed '${fanspeed}'"; return }
    writeFanAttr(0x0002, percent)    // PercentSetting
}

void setLevel(Object value, Object rate = 0) {
    if (value == null) return
    Integer percent = Math.max(0, Math.min(100, value as Integer))
    logDebug "setLevel(${percent})"
    if (percent == 0) { off(); return }
    writeFanAttr(0x0002, percent)    // PercentSetting
}

void autoOn() {
    logDebug "autoOn()"
    writeFanAttr(0x0000, 5)          // FanMode = Auto (5)
}

void autoOff() {
    logDebug "autoOff()"
    Integer prevSpeed = getRestoredSpeed()
    logDebug "autoOff() — restoring previousSpeed: ${prevSpeed}%"
    writeFanAttr(0x0002, prevSpeed)  // PercentSetting = previous
}

// Cycles through all speeds in sequence (off→1→2→...→7→off), wrapping at max.
// Maps to the Hubitat FanControl capability cycleSpeed command.
void cycleSpeed() {
    logDebug "cycleSpeed()"
    stepBafSpeed(1, true)
}

// Steps fan up one discrete BAF speed (1–7). Stops at Speed 7.
void fanSpeedUp() {
    logDebug "fanSpeedUp()"
    stepBafSpeed(1, false)
}

// Steps fan down one discrete BAF speed. Stops at off (0).
void fanSpeedDown() {
    logDebug "fanSpeedDown()"
    stepBafSpeed(-1, false)
}

// Steps fan speed by direction (+1 or -1). Wraps 7→0 if wrap=true, otherwise clamps to 0–7.
private void stepBafSpeed(Integer direction, Boolean wrap) {
    Integer n = FAN_SPEED_TO_PERCENT.size()  // 8: Off (0) + speeds 1–7
    Integer current = (device.currentValue("bafSpeed") as Integer) ?: 0
    Integer next = wrap ? ((current + direction + n) % n) : Math.max(0, Math.min(n - 1, current + direction))
    Integer percent = bafIndexToPercent(next)
    logDebug "stepBafSpeed: bafSpeed ${current} → ${next} (${percent}%)"
    writeFanAttr(0x0002, percent)
}

// Maps BAF speed index (0=Off, 1–7) to the canonical PercentSetting value.
private Integer bafIndexToPercent(Integer speedIndex) {
    if (speedIndex == 0) return 0
    return FAN_SPEED_TO_PERCENT["Speed ${speedIndex}"] ?: 0
}

void directionForward() {
    logDebug "directionForward()"
    writeFanAttr(0x000B, 0)          // AirflowDirection = Forward
}

void directionReverse() {
    logDebug "directionReverse()"
    writeFanAttr(0x000B, 1)          // AirflowDirection = Reverse
}

void whooshOn() {
    logDebug "whooshOn()"
    writeFanAttr(0x000A, 2)          // WindSetting = Whoosh (Natural Wind)
}

void whooshOff() {
    logDebug "whooshOff()"
    writeFanAttr(0x000A, 0)          // WindSetting = Normal (Sleep Wind)
}

// ---------------------------------------------------------------------------
// Subscribe & Refresh
// ---------------------------------------------------------------------------

// Builds the attribute path list shared by subscribeToAttributes() and refresh().
private List buildAttributePaths() {
    List paths = []

    // EP 01: Fan Control
    paths.add(matter.attributePath(EP_FAN, CLUSTER_FAN_CONTROL, 0x0000))  // FanMode
    paths.add(matter.attributePath(EP_FAN, CLUSTER_FAN_CONTROL, 0x0003))  // PercentCurrent
    paths.add(matter.attributePath(EP_FAN, CLUSTER_FAN_CONTROL, 0x000A))  // WindSetting
    paths.add(matter.attributePath(EP_FAN, CLUSTER_FAN_CONTROL, 0x000B))  // AirflowDirection

    // EP 02: Light (forwarded to child) — only if fan has a light
    if (hasLight()) {
        paths.add(matter.attributePath(EP_LIGHT, CLUSTER_ON_OFF, 0x0000))     // OnOff
        paths.add(matter.attributePath(EP_LIGHT, CLUSTER_LEVEL, 0x0000))      // CurrentLevel
        if (hasColorTemp()) {
            paths.add(matter.attributePath(EP_LIGHT, CLUSTER_COLOR, 0x0007))  // ColorTemperatureMireds
        }
        paths.add(matter.attributePath(EP_LIGHT, CLUSTER_MODE_SELECT, 0x0003)) // EP02 CurrentMode (light auto state)
    }

    // EP 03: Light Mode Select (forwarded to child) — only if fan has 2 lights
    if (hasLightMode()) {
        paths.add(matter.attributePath(EP_LIGHTMODE, CLUSTER_MODE_SELECT, 0x0003)) // EP03 CurrentMode
    }

    // EP 04: Temperature — only if fan has a temp sensor
    if (hasTemp()) {
        paths.add(matter.attributePath(EP_TEMP, CLUSTER_TEMP, 0x0000))        // MeasuredValue
    }

    // EP 06: Occupancy — only if fan has an occupancy sensor
    if (hasOccupancy()) {
        paths.add(matter.attributePath(EP_OCCUPANCY, CLUSTER_OCCUPANCY, 0x0000)) // Occupancy
    }

    return paths
}

// Subscribes to all relevant Matter attributes across all endpoints.
void subscribeToAttributes() {
    logInfo "Subscribing to Matter attributes"
    List paths = buildAttributePaths()
    logTrace "subscribeToAttributes: subscribing to ${paths.size()} attributes"
    sendToDevice(matter.cleanSubscribe(1, 0xFFFF, paths))
}

// Reads all dynamic device attributes.
void refresh() {
    logDebug "refresh()"
    List paths = buildAttributePaths()
    logTrace "refresh: reading ${paths.size()} attributes"
    sendToDevice(matter.readAttributes(paths))
    scheduleCommandTimeoutCheck()
}

// Reads all exploratory/debug attributes for discovery purposes.
// Includes Basic Information metadata and unconfirmed cluster attributes.
// Results appear in diagnostics debug output when runDiagnostics() is active.
void discoverAttributes() {
    if (state.diagActive) {
        diagDebug "Diagnostics reading discovery attributes"
    } else {
        logDebug "discoverAttributes()"
    }

    List<Map<String, String>> paths = []

    // Descriptor on root and known fan endpoints: DeviceTypeList / ServerList / PartsList
    [0x00, EP_FAN, EP_LIGHT, EP_LIGHTMODE, EP_TEMP, EP_OCCUPANCY].each { Integer ep ->
        paths.add(matter.attributePath(ep, CLUSTER_DESCRIPTOR, 0x0000)) // DeviceTypeList
        paths.add(matter.attributePath(ep, CLUSTER_DESCRIPTOR, 0x0001)) // ServerList
    }
    paths.add(matter.attributePath(0x00, CLUSTER_DESCRIPTOR, 0x0003))     // PartsList

    // EP 00: Basic Information — full metadata
    paths.add(matter.attributePath(0x00, CLUSTER_BASIC_INFO, 0x0001))  // VendorName
    paths.add(matter.attributePath(0x00, CLUSTER_BASIC_INFO, 0x0002))  // VendorID
    paths.add(matter.attributePath(0x00, CLUSTER_BASIC_INFO, 0x0003))  // ProductName
    paths.add(matter.attributePath(0x00, CLUSTER_BASIC_INFO, 0x0004))  // ProductID
    paths.add(matter.attributePath(0x00, CLUSTER_BASIC_INFO, 0x0005))  // NodeLabel
    paths.add(matter.attributePath(0x00, CLUSTER_BASIC_INFO, 0x0006))  // Location
    paths.add(matter.attributePath(0x00, CLUSTER_BASIC_INFO, 0x0007))  // HardwareVersion
    paths.add(matter.attributePath(0x00, CLUSTER_BASIC_INFO, 0x0008))  // HardwareVersionString
    paths.add(matter.attributePath(0x00, CLUSTER_BASIC_INFO, 0x0009))  // SoftwareVersion (numeric)
    paths.add(matter.attributePath(0x00, CLUSTER_BASIC_INFO, 0x000A))  // SoftwareVersionString
    paths.add(matter.attributePath(0x00, CLUSTER_BASIC_INFO, 0x0012))  // UniqueID

    // EP 01: Fan Control — unconfirmed attributes
    paths.add(matter.attributePath(EP_FAN, CLUSTER_FAN_CONTROL, 0x0004))  // SpeedMax
    paths.add(matter.attributePath(EP_FAN, CLUSTER_FAN_CONTROL, 0x0005))  // SpeedSetting
    paths.add(matter.attributePath(EP_FAN, CLUSTER_FAN_CONTROL, 0x0006))  // SpeedCurrent

    // EP 01: Identify cluster attributes
    paths.add(matter.attributePath(EP_FAN, 0x0003, 0x0000))              // IdentifyTime
    paths.add(matter.attributePath(EP_FAN, 0x0003, 0x0001))              // IdentifyType

    // EP 02: Color Control — CurrentHue, CurrentSaturation + CT capability probe attrs
    paths.add(matter.attributePath(EP_LIGHT, CLUSTER_COLOR, 0x0000))     // CurrentHue
    paths.add(matter.attributePath(EP_LIGHT, CLUSTER_COLOR, 0x0001))     // CurrentSaturation
    paths.add(matter.attributePath(EP_LIGHT, CLUSTER_COLOR, ATTR_FEATURE_MAP))             // FeatureMap
    paths.add(matter.attributePath(EP_LIGHT, CLUSTER_COLOR, ATTR_COLOR_CAPABILITIES))      // ColorCapabilities
    paths.add(matter.attributePath(EP_LIGHT, CLUSTER_COLOR, ATTR_COLOR_TEMP_PHYSICAL_MIN)) // ColorTempPhysicalMinMireds
    paths.add(matter.attributePath(EP_LIGHT, CLUSTER_COLOR, ATTR_COLOR_TEMP_PHYSICAL_MAX)) // ColorTempPhysicalMaxMireds
    paths.add(matter.attributePath(EP_LIGHT, CLUSTER_COLOR, ATTR_COLOR_MODE))              // ColorMode
    paths.add(matter.attributePath(EP_LIGHT, CLUSTER_COLOR, ATTR_ENHANCED_COLOR_MODE))     // EnhancedColorMode

    // EP 02: Mode Select (light auto state)
    paths.add(matter.attributePath(EP_LIGHT, CLUSTER_MODE_SELECT, ATTR_MODE_SELECT_CURRENT_MODE))    // CurrentMode

    // EP 02: Scenes cluster
    paths.add(matter.attributePath(EP_LIGHT, 0x0005, 0x0000))            // SceneCount
    paths.add(matter.attributePath(EP_LIGHT, 0x0005, 0x0001))            // CurrentScene
    paths.add(matter.attributePath(EP_LIGHT, 0x0005, 0x0002))            // CurrentGroup

    // EP 02: Fixed Label (0x0040)
    paths.add(matter.attributePath(EP_LIGHT, 0x0040, 0x0000))            // LabelList

    // EP 03: Fixed Label (0x0040)
    paths.add(matter.attributePath(EP_LIGHTMODE, 0x0040, 0x0000))        // LabelList

    // EP 04: Temperature
    paths.add(matter.attributePath(EP_TEMP, CLUSTER_TEMP, 0x0000))        // MeasuredValue

    // EP 06: Occupancy
    paths.add(matter.attributePath(EP_OCCUPANCY, CLUSTER_OCCUPANCY, 0x0000)) // Occupancy

    if (state.diagActive) {
        diagDebug "Diagnostics discovery read count=${paths.size()}"
    } else {
        logTrace "discoverAttributes: reading ${paths.size()} attributes"
    }
    String cmd = matter.readAttributes(paths)
    sendToDevice(cmd)
}

// Single diagnostics entry point (kept hidden by default via commented metadata command).
void runDiagnostics() {
    state.diagActive = true
    state.diagStartedMs = now()
    getChildDevice(lightChildDNI())?.setDiagActive(true)
    diagDebug "Diagnostics start"
    discoverEndpoints()
    discoverAttributes()
    probeFanCapabilities()
    probeColorCapabilities()
    refresh()
    runIn(4, "finishDiagnostics")
}

void finishDiagnostics() {
    long elapsed = Math.max(0L, ((now() ?: 0L) - ((state.diagStartedMs ?: now()) as Long)))
    Map snap = getCapabilitySnapshot()
    String fw = device.getDataValue("softwareVersion") ?: "unknown"
    String model = device.getDataValue("model") ?: "unknown"
    diagDebug "Diagnostics result: model=${model}, firmware=${fw}, endpoints=${snap.endpoints ?: []}, lightCount=${getLightCount()}, profile=${getLightProfile()}, ctAdjustable=${hasAdjustableColorTemp()}, ctRange=${snap.ctMinK ?: '?'}-${snap.ctMaxK ?: '?'}K, elapsedMs=${elapsed}"
    diagDebug "Diagnostics end"
    state.diagActive = false
    getChildDevice(lightChildDNI())?.setDiagActive(false)
    state.remove("diagStartedMs")
    state.remove("ctProbeWork")
}

// Targeted probe of Fan Control attributes that don't appear in the bulk discovery read.
// Only called during runDiagnostics(). RAW logging in parse() captures whatever comes back.
void probeFanCapabilities() {
    List<Map<String, String>> paths = []
    paths.add(matter.attributePath(EP_FAN, CLUSTER_FAN_CONTROL, 0x0001))        // FanModeSequence
    paths.add(matter.attributePath(EP_FAN, CLUSTER_FAN_CONTROL, ATTR_FEATURE_MAP)) // FeatureMap
    paths.add(matter.attributePath(EP_FAN, CLUSTER_FAN_CONTROL, 0x0004))        // SpeedMax
    paths.add(matter.attributePath(EP_FAN, CLUSTER_FAN_CONTROL, 0x0005))        // SpeedSetting
    paths.add(matter.attributePath(EP_FAN, CLUSTER_FAN_CONTROL, 0x0006))        // SpeedCurrent
    diagDebug "Diagnostics Fan probe: reading ${paths.size()} attributes on EP01/0x0202"
    sendToDevice(matter.readAttributes(paths))
}

// Reads CT-relevant Color Control attributes on EP 02 for capability detection.
void probeColorCapabilities() {
    if (!hasLight()) {
        if (state.diagActive) {
            diagDebug "Diagnostics CT probe skipped (no light endpoint)"
        } else {
            logDebug "probeColorCapabilities(): skipped — no light endpoint detected"
        }
        return
    }

    List<Map<String, String>> paths = []
    paths.add(matter.attributePath(EP_LIGHT, CLUSTER_COLOR, ATTR_FEATURE_MAP))
    paths.add(matter.attributePath(EP_LIGHT, CLUSTER_COLOR, ATTR_COLOR_CAPABILITIES))
    paths.add(matter.attributePath(EP_LIGHT, CLUSTER_COLOR, ATTR_COLOR_TEMP_PHYSICAL_MIN))
    paths.add(matter.attributePath(EP_LIGHT, CLUSTER_COLOR, ATTR_COLOR_TEMP_PHYSICAL_MAX))
    paths.add(matter.attributePath(EP_LIGHT, CLUSTER_COLOR, ATTR_COLOR_MODE))
    paths.add(matter.attributePath(EP_LIGHT, CLUSTER_COLOR, ATTR_ENHANCED_COLOR_MODE))

    if (state.diagActive) {
        diagDebug "Diagnostics CT probe: reading ${paths.size()} attributes on EP02/0x0300"
    } else {
        logDebug "probeColorCapabilities(): reading ${paths.size()} attributes on EP02/0x0300"
    }
    sendToDevice(matter.readAttributes(paths))
    runIn(10, "cleanupCtProbeWork")
}

void cleanupCtProbeWork() {
    if (state.ctProbeWork != null) {
        logDebug "CT probe work timed out — cleaning up"
        state.remove("ctProbeWork")
    }
}

// ---------------------------------------------------------------------------
// Health Check
// ---------------------------------------------------------------------------

// Reads health check preferences and schedules or unschedules the periodic check.
void initHealthCheck() {
    if (state.health == null) { state.health = [:] }
    if (state.stats == null) { state.stats = [:] }
    if (state.states == null) { state.states = [:] }
    if (state.lastTx == null) { state.lastTx = [:] }

    Integer healthMethod = (settings.healthCheckMethod as Integer) ?: HealthcheckMethodOpts.defaultValue
    if (healthMethod == 1 || healthMethod == 2) {
        Integer interval = (settings.healthCheckInterval as Integer) ?: HealthcheckIntervalOpts.defaultValue
        if (interval > 0) {
            logInfo "Scheduling health check every ${interval} minutes (method: ${HealthcheckMethodOpts.options[healthMethod]})"
            scheduleDeviceHealthCheck(interval)
        }
    } else {
        unScheduleDeviceHealthCheck()
        logInfo "Health check is disabled"
    }

    if (device.currentValue("healthStatus") == null) {
        sendHealthStatusEvent("unknown")
    }
}

private void scheduleDeviceHealthCheck(Integer intervalMins) {
    String cron = getCron(intervalMins * 60)
    schedule(cron, "deviceHealthCheck")
    logDebug "deviceHealthCheck scheduled every ${intervalMins} minutes"
}

private void unScheduleDeviceHealthCheck() {
    unschedule("deviceHealthCheck")
    device.deleteCurrentState("healthStatus")
    // Mirror health reset to child so parent/child stay in sync
    sendHealthStatusToChild("unknown")
    logWarn "Device health check is disabled"
}

// Called when any event is received from the device in parse().
void setHealthStatusOnline() {
    if (state.health == null) { state.health = [:] }
    state.health["checkCtr3"] = 0
    if (((device.currentValue("healthStatus") ?: "unknown") != "online")) {
        sendHealthStatusEvent("online")
        logInfo "Is online"
    }
}

// Called periodically by the cron job — increments counter and checks threshold.
void checkHealthStatusForOffline() {
    if (state.health == null) { state.health = [:] }
    Integer ctr = state.health["checkCtr3"] ?: 0
    if (ctr >= PRESENCE_COUNT_THRESHOLD) {
        state.health["offlineCtr"] = (state.health["offlineCtr"] ?: 0) + 1
        String healthStatus = device.currentValue("healthStatus") ?: "unknown"
        if (healthStatus != "offline") {
            sendHealthStatusEvent("offline")
        }
    } else {
        logDebug "Health check: ${device.currentValue('healthStatus') ?: 'unknown'} (missed=${ctr}/${PRESENCE_COUNT_THRESHOLD})"
    }
    state.health["checkCtr3"] = ctr + 1
}

// Periodic cron job — checks for offline and optionally pings.
void deviceHealthCheck() {
    checkHealthStatusForOffline()
    if (((settings.healthCheckMethod as Integer) ?: 0) == 2) {
        ping()
    }
}

void sendHealthStatusEvent(String value) {
    String descriptionText = "${device.displayName} healthStatus changed to ${value}"
    sendEvent(name: "healthStatus", value: value, descriptionText: descriptionText, isStateChange: true, type: "digital")
    if (value == "online") {
        logInfo "${descriptionText}"
    } else if (value == "offline") {
        logWarn "${descriptionText}"
    } else {
        logDebug "${descriptionText}"
    }
    // Mirror to light child
    sendHealthStatusToChild(value)
}

// Mirrors healthStatus to the light child device (if it exists).
private void sendHealthStatusToChild(String value) {
    if (hasLight()) {
        def child = getChildDevice(lightChildDNI())
        if (child) {
            child.setHealthStatus(value)
        }
    }
}

String getCron(int timeInSeconds) {
    final Random rnd = new Random()
    int minutes = (timeInSeconds / 60) as int
    int hours = (minutes / 60) as int
    hours = Math.min(hours, 23)
    String cron
    if (timeInSeconds < 60) {
        cron = "*/${timeInSeconds} * * * * ? *"
    } else if (minutes < 60) {
        cron = "${rnd.nextInt(59)} */${minutes} * ? * *"
    } else {
        cron = "${rnd.nextInt(59)} ${rnd.nextInt(59)} */${hours} ? * *"
    }
    return cron
}

// ---------------------------------------------------------------------------
// Ping & Command Timeout
// ---------------------------------------------------------------------------

void ping() {
    if (state.lastTx == null) { state.lastTx = [:] }
    state.lastTx["pingTime"] = now()
    if (state.states == null) { state.states = [:] }
    state.states["isPing"] = true
    if (state.stats == null) { state.stats = [:] }
    state.lastTx["rxCtrAtPing"] = state.stats["rxCtr"] ?: 0
    scheduleCommandTimeoutCheck()
    sendToDevice(matter.readAttributes([
        matter.attributePath(0, CLUSTER_BASIC_INFO, 0x00)  // DataModelRevision
    ]))
    logDebug "ping..."
}

void scheduleCommandTimeoutCheck(int delay = COMMAND_TIMEOUT) {
    runIn(delay, "deviceCommandTimeout")
}

// Called when no response is received within the timeout period.
// Does NOT call checkHealthStatusForOffline() — that is driven solely by the periodic
// cron job in deviceHealthCheck(). Calling it here would double-increment checkCtr3,
// causing the device to appear offline sooner than the configured threshold.
void deviceCommandTimeout() {
    if (state.health == null) { state.health = [:] }
    logWarn "no response received (device offline?) checkCtr3=${state.health['checkCtr3']} offlineCtr=${state.health['offlineCtr']}"
    if (state.states == null) { state.states = [:] }
    if (state.states["isPing"] == true) {
        sendRttEvent("timeout")
        state.states["isPing"] = false
        if (state.stats == null) { state.stats = [:] }
        state.stats["pingsFail"] = (state.stats["pingsFail"] ?: 0) + 1
    }
}

void sendRttEvent(String value = null) {
    Long currentMs = now()
    if (state.lastTx == null) { state.lastTx = [:] }
    Long timeRunning = currentMs - ((state.lastTx["pingTime"] ?: currentMs) as Long)
    Integer rxCtrAtPing = state.lastTx["rxCtrAtPing"] ?: 0
    Integer rxCtrNow = (state.stats != null && state.stats["rxCtr"] != null) ? state.stats["rxCtr"] : 0
    Integer rxCtrDelta = rxCtrNow - rxCtrAtPing
    String descriptionText
    if (value == null) {
        if (timeRunning > MAX_PING_MILLISECONDS) {
            logWarn "ping RTT ${timeRunning}ms exceeds max (${MAX_PING_MILLISECONDS}ms) — ignoring"
            return
        }
        descriptionText = "${device.displayName} round-trip time is ${timeRunning}ms (rxCtr delta=${rxCtrDelta})"
        logInfo "${descriptionText}"
        sendEvent(name: "rtt", value: timeRunning, descriptionText: descriptionText, unit: "ms", type: "physical")
    } else {
        descriptionText = "${device.displayName} round-trip time: ${value} (rxCtr delta=${rxCtrDelta}, healthStatus=${device.currentValue('healthStatus')} offlineCtr=${state.health['offlineCtr']} checkCtr3=${state.health['checkCtr3']})"
        logInfo "${descriptionText}"
        sendEvent(name: "rtt", value: value, descriptionText: descriptionText, type: "digital")
    }
    state.lastTx["rxCtrAtPing"] = rxCtrNow
}

// ---------------------------------------------------------------------------
// Matter Communication Helpers
// ---------------------------------------------------------------------------

// Sends a single Matter command string to the device.
void sendToDevice(String cmd) {
    // Log just the command prefix (e.g., "he rattrs", "he wattrs", "he invoke") for readability
    logTrace "sendToDevice: ${cmd.length() > 40 ? cmd.take(40) + '…' : cmd}"
    sendHubCommand(new HubAction(cmd, Protocol.MATTER))
}

// Sends a list of Matter command strings to the device with delay between.
void sendToDevice(List<String> cmds, Integer delay = 300) {
    logTrace "sendToDevice (list): ${cmds.size()} commands"
    sendHubCommand(new HubMultiAction(delayBetween(cmds, delay), Protocol.MATTER))
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
void diagTrace(String msg) { log.trace "${device.displayName}: ${msg}" }  // Unconditional diagnostic trace

private Integer safeToInt(def value, Integer fallback = null) {
    try {
        if (value == null) return fallback
        if (value instanceof Number) return ((Number) value).intValue()
        String s = value.toString().trim()
        if (s.length() == 0) return fallback
        return Integer.parseInt(s)
    } catch (e) {
        return fallback
    }
}
