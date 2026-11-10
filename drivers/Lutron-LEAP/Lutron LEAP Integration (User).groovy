/*
 *  Lutron LEAP Integration (User)
 *  Connects a Lutron Caseta Smart Bridge or RadioRA 3 processor to Hubitat over LEAP, alongside Hubitat's built-in
 *  Lutron integration and with its own pairing. One app instance per bridge or processor.
 *  The app finds the system by mDNS and follows it to a new address (known by its serial number), pairs with it (the
 *  key and certificate request are made on the hub), creates the bridge device (Lutron LEAP Bridge (User)) and hands
 *  it the credentials at each connect. It parents the zone, Pico, keypad and occupancy devices, at the top level of
 *  the device list: their commands come here and go on to the bridge device, which asks the app for them to send
 *  their status.
 *  Pages: the main page, Lutron System Configuration (the systems found, or an IP typed in; pairing) and Add Devices
 *  (the bridge's device list with a room filter, naming and the Pico driver; the devices already added).
 *  Requires Hubitat 2.5.0 or later (the definition's menu option; the mDNS listener needs 2.4.1). Tested on 2.5.3.
 *
 *  Changelog:
 *  ## [1.0.0] - 2026-11-10
 *    - First release. One app per Lutron Caseta Smart Bridge or RadioRA 3 processor (HomeWorks QSX and Athena are offered,
 *      marked untested): finds it by mDNS and follows it to a new address, pairs by the bridge button (key and certificate
 *      request made on the hub), offers its devices with a room filter and naming, and parents them. Device names follow
 *      Lutron; picked labels too. Data values: Lutron device type, model, location and, for zones, the controlling device.
 *      Occupancy devices follow their sensors to a new area. Listed under Integrations.
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
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap

@Field static final String VERSION = "1.0.0"
@Field static final String APP_NAME = "Lutron LEAP Integration (User)"
@Field static final String BRIDGE_DRIVER = "Lutron LEAP Bridge (User)"
@Field static final String NAMESPACE = "draperw"

// Finding the bridge on the network: an active browse of _lutron._tcp (every Lutron system, with its SYSTYPE); the
// _leap._tcp listener (LEAP on port 8081) only when the browse fails.
@Field static final String BROWSE_TYPE = "_lutron._tcp"
@Field static final Integer BROWSE_MS = 4000           // getMDNSServiceResults blocks this long (clamped 500-15000)
@Field static final List<String> SKIP_SYSTYPES = ["ConnectBridge"]   // RadioRA 2's main repeater: not LEAP
// The system types tested. Any other (HomeWorks QSX or Athena, whose SYSTYPE isn't known) is offered as untested: the
// bridge drives their /project types as it does RA3's, as pylutron-caseta does
@Field static final List<String> TESTED_SYSTYPES = ["SmartBridge", "RadioRa3Processor"]
@Field static final Map SYSTYPE_NAMES = [SmartBridge: "Lutron Smart Bridge", RadioRa3Processor: "Lutron RadioRA 3 Processor"]
@Field static final String MDNS_TYPE = "_leap._tcp"
@Field static final Integer LEAP_PORT = 8081
@Field static final Integer FIND_READS = 5             // browses before giving up
@Field static final Integer FIND_FIRST_SECS = 1        // the first browse, in its own execution (never in a page render)
@Field static final Integer FIND_EVERY_SECS = 3        // then every 3 s
@Field static final Long FIND_AGAIN_MS = 5 * 60 * 1000L  // opening the page looks again once the last look is this old

@Field static final String CSR_COMMON_NAME = "hubitat_leap_user"  // also the DisplayName the bridge keeps for the pairing
@Field static final Integer RSA_BITS = 2048

// Credentials: password settings when the probe survives there, otherwise atomicState.
// The setting names and the state names are the same. The key waiting for the button is kept the same way.
@Field static final List<String> CRED_NAMES = ["leapKeyPem", "leapCertPem", "leapCaPem"]
@Field static final String PENDING_KEY = "pendingKeyPem"
@Field static final String PROBE_SETTING = "credentialProbe"
@Field static final Integer PROBE_CHARS = 1800                 // a 2048-bit PKCS#8 key PEM is ~1704 characters
@Field static final List<String> BUSY_PHASES = ["making key", "press the bridge button", "requesting certificate"]

// DER object identifiers
@Field static final String OID_COMMON_NAME = "2.5.4.3"
@Field static final String OID_SHA256_RSA  = "1.2.840.113549.1.1.11"

definition(
    name: APP_NAME,
    namespace: NAMESPACE,
    author: "Bill Draper",
    description: "Lutron Caseta and RadioRA 3 over LEAP, alongside the built-in integration (one app per bridge or processor)",
    category: "Convenience",
    menu: "Integrations",                // Hubitat 2.5.0+: the menu the app is listed under (Integrations, Automations, Apps)
    iconUrl: "",
    iconX2Url: "",
    installOnOpen: true,                 // installed() runs when the app is first opened: no Done and re-open to pair
    importUrl: "https://raw.githubusercontent.com/draperw/Hubitat/main/drivers/Lutron-LEAP/Lutron%20LEAP%20Integration%20(User).groovy"
)

preferences {
    page(name: "mainPage")
    page(name: "configPage")
    page(name: "devicesPage")
}

/*******************************************************************
 ***** Pages (laid out like the built-in integration's)
********************************************************************/
// Pairing progress and the credential bookkeeping are written by the bridge's callbacks too: atomicState.
// Everything else is the app's alone: state.
Map mainPage() {
    checkAppVersion()
    if (settings.devicesToAdd) addSelected()          // Next on Add Devices adds what was ticked, as in the built-in
    recoverBridge()
    dynamicPage(name: "mainPage", title: "Lutron LEAP (User) ${VERSION}", install: true, uninstall: true) {
        section() {
            paragraph "<b>Status: ${statusText()}</b>"
        }
        section("System Setup") {
            if (!isInstalled()) {
                // installOnOpen should make this unreachable
                paragraph "Click <b>Done</b>, then open the app again to pair."
            }
            href name: "toConfig", page: "configPage", title: "Lutron System Configuration", description: configSummary()
            if (credentialsPresent()) href name: "toDevices", page: "devicesPage", title: "Add Devices", description: devicesSummary()
        }
        section("App Name") {
            label title: "Name this Lutron LEAP Integration", required: false,
                description: "Shown in the Apps list. Default: ${APP_NAME}"
        }
        section("Logging") {
            input name: "logLevel", type: "enum", title: "Logging Level", description: "Logs selected level and above",
                defaultValue: 3, options: LOG_LEVELS
            input name: "logLevelTime", type: "enum", title: "Logging Level Time", description: "Time to enable Debug/Trace logging",
                defaultValue: 30, options: LOG_TIMES
        }
    }
}

Map configPage() {
    boolean busy = pairingBusy()
    pageFind()
    syncPick()
    boolean looking = findActive()
    Map opts = [name: "configPage", title: "Lutron System Configuration", nextPage: credentialsPresent() ? "devicesPage" : "mainPage"]
    if (busy || looking) opts.refreshInterval = 3
    dynamicPage(opts) {
        section("Lutron System") {
            Map sys = systemOptions()
            if (looking) paragraph "Looking for Lutron systems (${Math.min(((Map) atomicState.find).reads as Integer, FIND_READS - 1) + 1} of ${FIND_READS})..."
            if (sys) input name: "bridgePick", type: "enum", title: "Discovered Lutron systems", options: sys, submitOnChange: true
            else if (!looking) paragraph "No Lutron system found. Enter its IP address below, or select <b>Find again</b>."
            if (!looking) input name: "btnFind", type: "button", title: "Find again"
            input name: "bridgeIp", type: "text", title: "Bridge IP address", required: true, submitOnChange: true,
                description: "Filled in when you pick a system above; or type it, e.g. 10.10.2.10"
            String other = otherSystemText()
            if (other) paragraph other
            paragraph bridgeText()
        }
        section("Pairing") {
            paragraph pairingText()
            if (!isInstalled()) {
                paragraph "Click <b>Done</b>, then open the app again to pair."
            } else if (busy) {
                input name: "btnCancel", type: "button", title: "Cancel pairing"
                input name: "btnRefresh", type: "button", title: "Refresh"
            } else {
                input name: "btnPair", type: "button", title: credentialsPresent() ? "Re-pair with Lutron" : "Pair with the bridge"
                if (credentialsPresent()) {
                    input name: "btnConnect", type: "button", title: "Connect now"
                    input name: "btnClear", type: "button", title: "Clear pairing"
                    paragraph "Select <b>Next</b> to add devices."
                }
            }
        }
        section("Credential storage") {
            paragraph storageText()
        }
    }
}

Map devicesPage() {
    dynamicPage(name: "devicesPage", title: "Devices", nextPage: "mainPage") {
        section("Discovery") {
            paragraph discoveryText()
            input name: "btnDiscover", type: "button", title: "Refresh device list"
            String sc = scenesText()
            if (sc) paragraph sc
        }
        section("Add Devices") {
            input name: "showOptions", type: "bool", title: "Show list and device customization options", defaultValue: false, submitOnChange: true
            if (settings.showOptions) {
                input name: "roomFilter", type: "enum", title: "Filter by room", options: roomOptions(), defaultValue: "all", submitOnChange: true
                paragraph "Choose the room first: devices it hides are unticked."
                input name: "nameStyle", type: "enum", title: "Name new devices", options: NAME_STYLES, defaultValue: "room", submitOnChange: true
                paragraph "Names are set only when a device is added. Existing devices are never renamed."
                input name: "picoDriver", type: "enum", title: "Driver for new Pico devices", options: PICO_DRIVERS, defaultValue: "standard"
            }
            // submitOnChange: a button press doesn't submit the page, so the ticks are saved as they change
            input name: "devicesToAdd", type: "enum", title: "Select devices to add", options: addOptions(), multiple: true, required: false,
                submitOnChange: true
            paragraph addNote()
            input name: "btnAdd", type: "button", title: "Add selected device(s)"
        }
        section("Previously Added Devices") {
            applyFollowedLabels()
            paragraph addedText()
            List kids = lutronChildren()
            if (kids) {
                input name: "labelFollow", type: "enum", title: "Devices whose labels follow Lutron programming", multiple: true, required: false,
                    submitOnChange: true, options: kids.sort { it.displayName?.toString() }.collectEntries { [(it.id.toString()): it.displayName?.toString()] }
                paragraph "A device's Hubitat name is always its Lutron name. The label of a device picked here is set to it too, now and whenever the name changes in Lutron programming."
            }
        }
    }
}

boolean isInstalled() { app.getInstallationState() == "COMPLETE" }

boolean pairingBusy() { ((Map) atomicState.pairing)?.phase in BUSY_PHASES }

String statusText() {
    if (!credentialsPresent()) return pairingBusy() ? "Pairing" : "Not paired"
    Object b = bridgeDevice()
    if (b == null) return "Paired; no bridge device"
    return "Paired and ${b.currentValue('connection') ?: 'not connected'}"
}

String configSummary() {
    String ip = settings.bridgeIp?.trim()
    if (!ip) return "Not set up"
    return "${ip} - ${credentialsPresent() ? 'paired' : 'not paired'}"
}

String devicesSummary() {
    Map d = (Map) atomicState.discovery
    String s = "${lutronChildren().size()} device(s) added - ${d ? bridgeEntries().size() : 0} discovered"
    int missing = missingDnis().size()
    return missing ? "${s} - ${missing} not on the bridge any more" : s
}

String bridgeText() {
    Object b = bridgeDevice()
    if (b == null) return "The bridge device is created when you pair."
    String conn = b.currentValue("connection") ?: "unknown"
    String health = b.currentValue("healthStatus") ?: "unknown"
    String ver = b.currentValue("leapVersion")
    return "Bridge device: <a href='/device/edit/${b.id}' target='_blank'>${esc(b.displayName)}</a>. " +
           "Connection: <b>${esc(conn)}</b>, health: ${esc(health)}" + (ver ? ", LEAP ${esc(ver)}" : "")
}

// "Paired. (Re-pair only ...)" then a Status line with the pairing's date and time, as in the built-in
String pairingText() {
    Map p = (Map) atomicState.pairing
    Object pairedAt = atomicState.pairedAt
    String head = credentialsPresent() ? "<b>Paired</b>. (Re-pair only if the Lutron system was reset.)" : "Not paired."
    String st = null
    if (p?.phase == "making key") st = "Making the key (a few seconds)..."
    else if (p?.phase == "press the bridge button") st = "<b>Press the small black button on the back of the bridge now</b> (within 3 minutes)."
    else if (p?.phase == "requesting certificate") st = "Button pressed; asking the bridge for a certificate..."
    else if (p?.phase == "failed") st = "Last pairing failed: ${esc(p.detail)}"
    else if (credentialsPresent()) st = "&#10003; Pairing complete" + (pairedAt ? " on ${fmtTime(pairedAt as Long)}" : "")
    return st ? "${head}<br>Status: ${st}" : head
}

String discoveryText() {
    Map d = (Map) atomicState.discovery
    if (!d) return "Not read yet: the bridge reads its devices when it first connects."
    return "Last discovery found: ${bridgeEntries().size()} device(s) (read ${fmtTime(d.readAt as Long)})."
}

String scenesText() {
    List sc = ((Map) atomicState.discovery)?.scenes ?: []
    if (!sc) return null
    return "Scenes (Push n on the bridge device): " + sc.collect { "${it.id} = ${esc(it.name)}" }.join(", ")
}

String storageText() {
    String probe = (state.probe instanceof Map) ? "${state.probe.result} (${fmtTime(state.probe.at as Long)})" : "not tested yet"
    String store = atomicState.credStore
    String where = store ? "Credentials are kept in <b>${store == 'settings' ? 'app password settings' : 'app state'}</b>; ${credentialCheck()}." : "No credentials stored."
    return "Password setting test with a dummy the size of a key: ${esc(probe)}.<br>${where}"
}

void appButtonHandler(String btn) {
    switch (btn) {
        case "btnPair":    startPairing(); break
        case "btnCancel":  cancelPairing(); break
        case "btnConnect": bridgeDevice()?.connectSoon(); break
        case "btnClear":   clearPairing(); break
        case "btnDiscover": bridgeDevice()?.discover(); break
        case "btnAdd":     addSelected(); break
        case "btnFind":    atomicState.remove("systems"); startFind(false); break
    }
}

/*******************************************************************
 ***** Lifecycle
********************************************************************/
// With installOnOpen this runs when the app is first opened, before the user has entered anything.
void installed() {
    logWarn "installed (${VERSION})"
    state.appVersion = VERSION
    initialize()
}

void updated() {
    logDebug "updated (${VERSION})"
    initialize()
}

void uninstalled() {
    logWarn "uninstalled: removing the bridge device and the app's devices"
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

void initialize() {
    checkLogLevel()
    checkAppVersion()
    // The mDNS listener at install, save and hub start; hub start also checks the bridge's address
    listenMdns()
    unsubscribe()
    subscribe(location, "systemStart", "hubStarted")
    if (!(state.probe instanceof Map)) startProbe()
    ensureBridge()
    // A setting cut short or wiped by Done shows up here rather than at the next connect
    if (credentialsPresent()) {
        String check = credentialCheck()
        if (check == "intact") logDebug "Credentials intact (${atomicState.credStore})"
        else logWarn "Credentials ${check}: pair again"
    }
    // Connect when not connected, or reconnect when the IP changed; otherwise leave a live link alone
    String ip = settings.bridgeIp?.trim()
    Object b = bridgeDevice()
    if (credentialsPresent() && b != null && (ip != state.lastBridgeIp || b.currentValue("connection") != "connected")) b.connectSoon()
    state.lastBridgeIp = ip
}

// Code updates: the user presses nothing after pasting a new version. Runs from the page and initialize().
void checkAppVersion() {
    String old = state.appVersion
    if (old == VERSION) return
    state.appVersion = VERSION
    if (old == null && state.probe == null) return                 // brand new; installed() sets up
    logWarn "App updated (${old} -> ${VERSION})"
    // Migrations from one version to the next go here (none since 1.0.0)
}

Object bridgeDevice() { getChildDevice(app.id.toString()) }

// Opening the app with a pairing but no bridge device (the user deleted it): create it again and connect.
void recoverBridge() {
    if (!isInstalled() || !credentialsPresent() || bridgeDevice() != null || !settings.bridgeIp?.trim()) return
    logWarn "The bridge device is missing; creating it again"
    ensureBridge()?.connectSoon()
}

// Creates the bridge device when there is an IP and no device yet. Returns the device, or null.
Object ensureBridge() {
    Object b = bridgeDevice()
    if (b != null || !settings.bridgeIp?.trim()) return b
    try {
        b = addChildDevice(NAMESPACE, BRIDGE_DRIVER, app.id.toString(), [name: BRIDGE_DRIVER, isComponent: false])
        logInfo "Created the bridge device (DNI ${app.id})"
    } catch (Exception e) {
        logErr "Couldn't create the bridge device: is the '${BRIDGE_DRIVER}' driver installed? (${e.message})"
    }
    return b
}

/*******************************************************************
 ***** Credential storage
********************************************************************/
// Writes a dummy the size and shape of a PEM (64-character lines) to a password setting; checkProbe reads
// it back in a later execution. The key itself never goes to a call that could fail with it in the message.
void startProbe() {
    String v = probeValue()
    try {
        app.updateSetting(PROBE_SETTING, [type: "password", value: v])
    } catch (Exception e) {
        state.probe = [result: "refused (${probeError(e)})", ok: false, at: now()]
        logInfo "Password setting test: refused (${probeError(e)}); credentials will go in state"
        return
    }
    state.probe = [result: "checking", ok: false, at: now()]
    runIn(2, "checkProbe")
}

void checkProbe() {
    String want = probeValue()
    Object got = settings[PROBE_SETTING]
    String result
    boolean ok = false
    if (got == null) result = "missing on read-back"
    else if (got.toString() == want) { result = "intact (${want.length()} characters, ${want.readLines().size()} lines)"; ok = true }
    else result = "differs (${got.toString().length()} of ${want.length()} characters)"
    try { app.removeSetting(PROBE_SETTING) } catch (Exception ignored) { }
    state.probe = [result: result, ok: ok, at: now()]
    logInfo "Password setting test: ${result}; credentials will go in ${ok ? 'password settings' : 'state'}"
}

String probeValue() {
    StringBuilder sb = new StringBuilder("-----BEGIN DUMMY-----\n")
    String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    int n = 0
    while (n < PROBE_CHARS) { sb.append(abc).append("\n"); n += 65 }
    sb.append("-----END DUMMY-----\n")
    return sb.toString()
}

// A failed write can quote the value in its message (the hub's SQL error does), so only the dummy's
// errors are shown, and only their first line.
String probeError(Exception e) {
    String m = e.message ?: e.toString()
    if (m.contains("Value too long")) return "value too long for the setting"
    return m.readLines() ? m.readLines()[0].take(120) : "error"
}

// Where secrets go: password settings when the probe survived there, otherwise atomicState
String secretStore() { (state.probe instanceof Map && state.probe.ok) ? "settings" : "state" }

// Stores secrets (name -> PEM) in the given store; returns the store used. A password setting that refuses
// one sends all of them to atomicState. Never logs a value or an exception message (it can quote the value).
String putSecrets(Map vals, String store) {
    if (store == "settings") {
        try {
            vals.each { String k, String v -> app.updateSetting(k, [type: "password", value: v]) }
            return "settings"
        } catch (Exception e) {
            logWarn "A password setting refused a secret; keeping it in state instead"
            vals.keySet().each { String k -> try { app.removeSetting(k) } catch (Exception ignored) { } }
        }
    }
    vals.each { String k, String v -> atomicState[k] = v }
    return "state"
}

String getSecret(String name, String store) {
    return (store == "settings" ? settings[name] : atomicState[name])?.toString()
}

void removeSecret(String name) {
    atomicState.remove(name)
    try { app.removeSetting(name) } catch (Exception ignored) { }
}

boolean credentialsPresent() { atomicState.credStore != null }

void storeCredentials(String keyPem, String certPem, String caPem) {
    removeCredentials()
    Map vals = [leapKeyPem: keyPem, leapCertPem: certPem, leapCaPem: caPem]
    atomicState.credStore = putSecrets(vals, secretStore())
    atomicState.credHash = vals.collectEntries { String k, String v -> [(k): sha256(v)] }
    atomicState.pairedAt = now()
}

void removeCredentials() {
    CRED_NAMES.each { removeSecret(it) }
    atomicState.remove("credStore")
    atomicState.remove("credHash")
    atomicState.remove("pairedAt")
}

void removePending() {
    removeSecret(PENDING_KEY)
    atomicState.remove("pendingStore")
    atomicState.remove("pendingPubKey")
}

Map readCredentials() {
    String store = atomicState.credStore
    if (!store) return null
    return CRED_NAMES.collectEntries { String k -> [(k): getSecret(k, store)] }
}

// "intact", or what is wrong (without revealing anything). Reads only: it runs inside the bridge's calls too.
String credentialCheck() {
    Map c = readCredentials()
    if (c == null) return "none stored"
    Map hashes = (Map) atomicState.credHash ?: [:]
    List<String> bad = CRED_NAMES.findAll { String k -> c[k] == null || sha256(c[k]) != hashes[k] }
    if (!bad) return "intact"
    return "changed or missing: " + bad.collect { [leapKeyPem: "key", leapCertPem: "certificate", leapCaPem: "root CA"][it] }.join(", ")
}

String sha256(String s) {
    MessageDigest md = MessageDigest.getInstance("SHA-256")
    return md.digest(s.getBytes("UTF-8")).encodeHex().toString()
}

/*******************************************************************
 ***** Called by the bridge device
********************************************************************/
// The bridge device asks for these each time it connects. Reads only (no state writes): it runs while
// the bridge is mid-call. null when not paired or a credential no longer matches its fingerprint.
Map leapCredentials() {
    String ip = settings.bridgeIp?.trim()
    if (!credentialsPresent() || !ip) return null
    String check = credentialCheck()
    if (check != "intact") {
        logWarn "Not handing over the credentials: ${check}. Pair again."
        return null
    }
    Map c = readCredentials()
    return [ip: ip, certificate: c.leapCertPem, privateKey: c.leapKeyPem, caCertificate: c.leapCaPem]
}

void pairingProgress(String phase) {
    if (!pairingBusy()) return
    atomicState.pairing = [phase: phase, at: now()]
    logInfo "Pairing: ${phase}"
}

void pairingFailed(String why) {
    removePending()
    atomicState.pairing = [phase: "failed", detail: why, at: now()]
    logWarn "Pairing failed: ${why}"
}

// The signed certificate and the bridge's root. Kept only if the certificate holds our public key and
// parses; returns whether it was kept (the bridge connects only then).
// The bridge's device list, from the bridge's own execution: system, zones, picos, keypads, occupancy, scenes, readAt.
// The keys of the list before it are kept, to tag what is new.
void discoveryResult(Map list) {
    Map before = (Map) atomicState.discovery
    List prev = before ? entriesOf(before).collect { it.key } : null
    if (prev != null) atomicState.prevKeys = prev
    atomicState.discovery = list
    bridgeIdentity(list?.system?.serial)
    logInfo "Devices on the bridge: ${list?.zones?.size() ?: 0} zones, ${list?.picos?.size() ?: 0} Picos, ${list?.scenes?.size() ?: 0} scenes" +
            (list?.keypads != null ? ", ${list.keypads.size()} keypads, ${list.occupancy?.size() ?: 0} areas with occupancy sensors" : "")
    followMovedSensors(list)
    refreshDevices(list)
    if (prev != null) {
        List<String> fresh = entriesOf(list).findAll { it.kind && !(it.key in prev) && getChildDevice(dniOf(it)) == null }.collect { labelFor(it) }
        if (fresh) logInfo "New on the bridge: ${fresh.join(', ')} (add them in Add Devices)"
    }
}

// An occupancy device whose area has no sensors any more follows them when they all went to one area that has no
// device yet: its DNI changes to that area (rules use the device's id, not its DNI). Otherwise it stays, marked "not
// on the bridge any more", with a Warn saying where its sensors went.
void followMovedSensors(Map list) {
    List occ = (List) list?.occupancy
    if (occ == null) return
    Set<String> areas = occ.collect { it.id.toString() } as Set
    String prefix = "${app.id}/a/"
    lutronChildren().findAll { it.deviceNetworkId?.toString()?.startsWith(prefix) }.each { ch ->
        String area = ch.deviceNetworkId.toString().substring(prefix.length())
        if (area in areas) return
        List<String> mine = (ch.getDataValue("sensors") ?: "").tokenize(",")
        List targets = mine ? occ.findAll { Map o -> ((List) o.sensors ?: []).collect { it.toString() }.any { it in mine } } : []
        if (!targets) {
            motionIdle(ch)
            logWarn "${ch.displayName}: its area has no occupancy sensors any more"
            return
        }
        Map to = (targets.size() == 1) ? (Map) targets[0] : null
        String toDni = to ? "${prefix}${to.id}".toString() : null
        if (to && getChildDevice(toDni) == null) {
            try {
                ch.setDeviceNetworkId(toDni)
                ch.updateDataValue("sensors", ((List) to.sensors).join(","))
                logInfo "Moved ${ch.displayName} to area ${to.name}: its occupancy sensors moved there"
                try { bridgeDevice()?.childrenAdded([toDni]) } catch (Exception e) { logDebug "Couldn't ask the bridge for the area's status: ${e.message}" }
                return
            } catch (Exception e) {
                logWarn "Couldn't move ${ch.displayName} to area ${to.name}: ${e.message}"
            }
        }
        motionIdle(ch)
        logWarn "${ch.displayName}: its area has no occupancy sensors any more; they're now in ${targets.collect { it.name }.join(', ')}"
    }
}

// An occupancy device left with no sensors: inactive, since nothing will clear it (the bridge ignores its area's status)
void motionIdle(Object ch) {
    if (ch.currentValue("motion") == "inactive") return
    ch.parse([[name: "motion", value: "inactive", descriptionText: "${ch.displayName} motion is inactive".toString()]])
}

// Devices already added get the new list's data: type, model, location, LED buttons, button map, numberOfButtons,
// occupancy sensors (a Designer transfer can add buttons and LEDs). The Hubitat name is always Lutron's name (in the
// Name new devices style), and so is the label of a device picked to follow Lutron; other labels stay.
void refreshDevices(Map list) {
    entriesOf(list).findAll { it.kind }.each { Map e ->
        Object ch = getChildDevice(dniOf(e))
        if (ch == null) return
        List<String> changed = followName(ch, e)
        lutronData(e).each { String dk, Object dv ->
            if (dv == null) return
            String old = ch.getDataValue(dk)
            if (old != dv.toString()) {
                ch.updateDataValue(dk, dv.toString())
                if (old == null && dk in ["lutronDeviceType", "lutronModelNumber", "lutronLocation", "lutronDeviceHref"]) return   // first set: not news
                changed << (dk == "ledButtons" ? "LED buttons ${old ?: 'none'} -> ${dv ?: 'none'}" : dk in ["lutronButtons", "sensors"] ? (dk == "sensors" ? "occupancy sensors" : "button map") : "${dk} ${old} -> ${dv}").toString()
            }
        }
        if (e.kind in ["pico", "keypad"]) {
            Integer n = (e.buttons ?: 0) as Integer
            Object cur = ch.currentValue("numberOfButtons")
            if (cur == null || (cur as Integer) != n) {
                ch.parse([[name: "numberOfButtons", value: n, descriptionText: "${ch.displayName} numberOfButtons is ${n}".toString()]])
                changed << "numberOfButtons ${cur} -> ${n}".toString()
            }
        }
        if (changed) logInfo "Updated ${ch.displayName}: ${changed.join(', ')}"
    }
}

// The device's name, and its label if it follows Lutron, set to Lutron's name; returns what changed
List<String> followName(Object ch, Map e) {
    List<String> changed = []
    String lutron = labelFor(e)
    String oldName = ch.name?.toString()
    if (oldName != lutron) { ch.setName(lutron); changed << "name ${oldName} -> ${lutron}".toString() }
    if (labelFollows(ch)) {
        String oldLabel = ch.label?.toString()
        if (oldLabel != lutron) { ch.setLabel(lutron); changed << "label ${oldLabel} -> ${lutron}".toString() }
    }
    return changed
}

boolean labelFollows(Object ch) { ch?.id?.toString() in ((List) settings.labelFollow ?: []).collect { it.toString() } }

// Devices picked to follow Lutron get their label at once (on the page), not only at the next read
void applyFollowedLabels() {
    if (!settings.labelFollow) return
    Map byDni = bridgeEntries().findAll { it.kind }.collectEntries { [(dniOf(it)): it] }
    lutronChildren().findAll { labelFollows(it) }.each { ch ->
        Map e = (Map) byDni[ch.deviceNetworkId?.toString()]
        if (e == null) return
        List<String> changed = followName(ch, e)
        if (changed) logInfo "Updated ${ch.displayName}: ${changed.join(', ')}"
    }
}

// caPem: the CA the LEAP connection is checked against when it isn't the root the bridge sent (RA3: the Lutron root)
Boolean pairingResult(String certPem, String rootPem, String caPem = null) {
    String pendingStore = atomicState.pendingStore
    String keyPem = pendingStore ? getSecret(PENDING_KEY, pendingStore) : null
    String pubB64 = atomicState.pendingPubKey
    if (!keyPem || !pubB64) {
        logWarn "A certificate arrived with no pairing in progress; ignored"
        return false
    }
    try {
        X509Certificate cert = parseCert(certPem)
        if (cert.getPublicKey().getEncoded().encodeBase64().toString() != pubB64) {
            pairingFailed("the certificate is not for the key made here")
            return false
        }
        String signedBy
        try { cert.verify(parseCert(rootPem).getPublicKey()); signedBy = "signed by the bridge's root" }
        catch (Exception e) { signedBy = "NOT signed by the root it came with (${e.message})" }
        logInfo "Certificate '${cert.getSubjectX500Principal().getName()}' expires ${cert.getNotAfter().format('yyyy-MM-dd')}, ${signedBy}"
    } catch (Exception e) {
        pairingFailed("the certificate doesn't parse: ${e.message}")
        return false
    }
    storeCredentials(keyPem, certPem, caPem ?: rootPem)
    removePending()
    // The bridge connects to this IP next: a later Done with the same IP leaves that link alone
    atomicState.lastBridgeIp = settings.bridgeIp?.trim()
    atomicState.pairing = [phase: "paired", at: now()]
    logInfo "Paired; credentials kept in ${atomicState.credStore == 'settings' ? 'password settings' : 'state'}"
    return true
}

/*******************************************************************
 ***** Finding the bridge on the network
********************************************************************/
// Looks are kept in atomicState.find: [active:, reads:, follow:, at:]. follow: looking for the paired serial to
// follow the bridge to a new address. The systems from the last read with any: atomicState.systems.
// Everything here writes atomicState only: it runs from the page, from the bridge's calls and from timers.

void listenMdns() {
    try {
        registerMDNSListener(MDNS_TYPE)
    } catch (Exception e) {
        logWarn "Couldn't start the mDNS listener (${e.message}); type the bridge's address instead"
    }
}

// The Lutron systems on the network: [ip:, serial:, systype:, untested:] for each _lutron._tcp service but the Connect
// Bridge, from an active browse (~4 s: never call this from a page render). When the browse fails, the _leap._tcp
// listener's entries with LEAP on 8081 (systype unknown: a Smart Bridge). Serials only in Debug logs.
List<Map> readSystems() {
    List<Map> out = []
    List found = null
    try {
        Object r = browseMdns(BROWSE_TYPE)
        found = (r instanceof List) ? (List) r : (r == null ? [] : [r])
    } catch (Throwable e) {
        logDebug "The mDNS browse failed (${e.toString().take(120)}); using the ${MDNS_TYPE} listener"
    }
    if (found != null) {
        found.each { Object en ->
            if (!(en instanceof Map)) return
            Map txt = (en.textRecords instanceof Map) ? (Map) en.textRecords : [:]
            String sys = txt.SYSTYPE?.toString()
            if (sys in SKIP_SYSTYPES) return
            String ip = firstIp(en.ip4Addresses)
            if (!ip) return
            Long sn = serialOfHex(txt.SERNUM) ?: serialOfHost(en.server)
            out << [ip: ip, serial: sn, systype: sys, untested: (sys != null && !(sys in TESTED_SYSTYPES))]
        }
        logDebug "mDNS ${BROWSE_TYPE}: ${found.size()} services, ${out.size()} kept: ${out}"
    } else {
        List entries = []
        try { entries = (List) getMDNSEntries(MDNS_TYPE) ?: [] } catch (Exception e) { logDebug "getMDNSEntries threw ${e.message}" }
        entries.each { Object en ->
            if (!(en instanceof Map) || en.port?.toString() != LEAP_PORT.toString()) return
            String ip = firstIp(en.ip4Addresses)
            if (ip) out << [ip: ip, serial: serialOfHost(en.server), systype: null, untested: false]
        }
        logDebug "mDNS ${MDNS_TYPE} listener: ${entries.size()} entries, ${out.size()} on port ${LEAP_PORT}: ${out}"
    }
    return out.unique { it.ip }.sort { it.ip }
}

// The hub's active mDNS browse (NetworkUtils; works from a user app). Blocks for about BROWSE_MS.
List browseMdns(String type) {
    return hubitat.helper.NetworkUtils.getMDNSServiceResults(type, BROWSE_MS)
}

// ip4Addresses: a List (browse) or a comma-separated String (listener)
String firstIp(Object addrs) {
    List l = (addrs instanceof Collection) ? new ArrayList((Collection) addrs) : (addrs?.toString()?.tokenize(",") ?: [])
    return l.collect { it?.toString()?.trim() }.find { it }
}

Long serialOfHex(Object hex) {
    String h = hex?.toString()?.trim()
    if (!h || !(h ==~ /(?i)[0-9a-f]{1,16}/)) return null
    return Long.parseLong(h, 16)
}

Long serialOfHost(Object host) {
    def m = (host?.toString() =~ /(?i)lutron-([0-9a-f]{8})/)
    return m.find() ? Long.parseLong(m.group(1), 16) : null
}

// The paired bridge's serial: from the bridge (bridgeIdentity), or else from the device list it read
Long pairedSerial() {
    Object sn = atomicState.bridgeSerial
    if (sn == null) sn = ((Map) atomicState.discovery)?.system?.serial
    try { return (sn != null) ? sn as Long : null } catch (Exception e) { return null }
}

// The bridge's own serial number (/device, IsThisDevice), from the bridge at each connect and in its device list
void bridgeIdentity(Object serial) {
    if (serial == null) return
    Long sn
    try { sn = serial as Long } catch (Exception e) { return }
    if (sn != pairedSerial()) logDebug "Bridge serial number: ${sn}"
    atomicState.bridgeSerial = sn
}

boolean findActive() { ((Map) atomicState.find)?.active == true }

// Opening Lutron System Configuration starts a look (its browses run in their own executions, never in the page
// render), unless one is running or the last one is recent.
void pageFind() {
    Map f = (Map) atomicState.find
    if (f?.active) return
    if (f?.at != null && now() - (f.at as Long) < FIND_AGAIN_MS) return
    startFind(false)
}

// Browses FIND_FIRST_SECS later, then every FIND_EVERY_SECS, up to FIND_READS times (the listener is registered too,
// for the fallback).
// follow: also look for the paired serial and follow it. A look already running takes the follow on.
void startFind(boolean follow, boolean quiet = false) {
    listenMdns()
    Map f = (Map) atomicState.find
    if (f?.active) {
        if (follow && !f.follow) atomicState.find = f + [follow: true]
        return
    }
    atomicState.find = [active: true, reads: 0, follow: follow, quiet: quiet, at: now()]
    runIn(FIND_FIRST_SECS, "findTick")
}

void findTick() {
    Map f = (Map) atomicState.find
    if (!f?.active) return
    int reads = (f.reads as Integer) + 1
    List<Map> sys = readSystems()
    if (sys) atomicState.systems = sys
    Long sn = pairedSerial()
    String cur = settings.bridgeIp?.trim()
    // The paired bridge can be listed both at the failing address (a stale entry, as while it restarts) and at its new
    // one: follow the new one; while only the failing one is listed, read on.
    List<Map> mine = (f.follow && sn != null) ? sys.findAll { it.serial != null && (it.serial as Long) == sn } : []
    Map pick = mine.find { it.ip != cur } ?: (mine ? mine[0] : null)
    boolean moved = pick != null && pick.ip != cur
    boolean done = f.follow ? (moved || reads >= FIND_READS) : (!sys.isEmpty() || reads >= FIND_READS)
    atomicState.find = [active: !done, reads: reads, follow: f.follow, quiet: f.quiet, at: now()]
    if (!done) { runIn(FIND_EVERY_SECS, "findTick"); return }
    Closure say = f.quiet ? { String m -> logDebug m } : { String m -> logInfo m }
    if (sys) say("Lutron systems found: ${sys.collect { it.ip }.join(', ')}")
    else say("No Lutron system found on the network (${reads} looks)")
    if (f.follow) {
        if (pick) followTo(pick.ip.toString())
        if (!moved && sn != null) say("The paired bridge wasn't found at a new address; it keeps trying ${cur}")
    } else if (sys) {
        autoPick(sys)
    }
}

// The bridge was found at this address (by its serial): a new one is saved, and the bridge connects there.
void followTo(String ip) {
    String old = settings.bridgeIp?.trim()
    if (ip == old) { logDebug "The bridge is still at ${ip}"; return }
    setBridgeIp(ip)
    atomicState.lastBridgeIp = ip
    logWarn "The bridge moved from ${old ?: '(no address)'} to ${ip}"
    bridgeDevice()?.connectSoon()
}

// Exactly one system found and no address set yet: it is the one
void autoPick(List<Map> sys) {
    List<Map> ok = sys ?: []
    if (ok.size() != 1 || settings.bridgeIp?.trim()) return
    setBridgeIp(ok[0].ip.toString())
    logInfo "One Lutron system found, at ${ok[0].ip}: picked"
}

void setBridgeIp(String ip) {
    app.updateSetting("bridgeIp", [type: "text", value: ip])
    if (systemOptions().containsKey(ip)) { app.updateSetting("bridgePick", [type: "enum", value: ip]); atomicState.lastPick = ip }
}

// The list's choice and the IP field follow each other: a system picked fills in the address; an address typed
// in, or followed, moves the choice to that system (or clears it when the list doesn't have it).
void syncPick() {
    String ip = settings.bridgeIp?.trim()
    String pick = settings.bridgePick?.toString()
    if (pick && pick != atomicState.lastPick) {
        atomicState.lastPick = pick
        if (pick != ip) app.updateSetting("bridgeIp", [type: "text", value: pick])
        return
    }
    if (!ip || pick == ip) return
    if (systemOptions().containsKey(ip)) {
        app.updateSetting("bridgePick", [type: "enum", value: ip])
        atomicState.lastPick = ip
    } else if (pick) {
        app.removeSetting("bridgePick")
        atomicState.remove("lastPick")
    }
}

// The systems found, IP -> "Lutron Smart Bridge at <IP>", with an untested system type and the paired system marked
Map systemOptions() {
    Long sn = credentialsPresent() ? pairedSerial() : null
    Map o = [:]
    ((List) atomicState.systems ?: []).each { Map m ->
        o[m.ip.toString()] = "${systemName(m)} at ${m.ip}" + (m.untested ? " (untested)" : "") +
                             ((sn != null && m.serial != null && (m.serial as Long) == sn) ? " (paired)" : "")
    }
    return o
}

// "Lutron Smart Bridge", "Lutron RadioRA 3 Processor", or "Lutron <SYSTYPE>"; no SYSTYPE (the listener): a Smart Bridge
String systemName(Map m) {
    String t = m.systype?.toString()
    if (!t) return "Lutron Smart Bridge"
    return (SYSTYPE_NAMES[t] ?: "Lutron ${t}").toString()
}

// The address set belongs to another Lutron system than the one paired (its serial differs)
String otherSystemText() {
    Long sn = credentialsPresent() ? pairedSerial() : null
    String ip = settings.bridgeIp?.trim()
    Map m = ((List) atomicState.systems ?: []).find { it.ip == ip }
    if (sn == null || m?.serial == null || (m.serial as Long) == sn) return null
    return "<b>This isn't the Lutron system the app is paired with: pair again</b> (Re-pair with Lutron), or pick the paired one."
}

// Hub start: the listener again, and one look for the paired bridge (it may have moved while the hub was down)
void hubStarted(Object evt) {
    logDebug "Hub started"
    listenMdns()
    if (credentialsPresent() && pairedSerial() != null) startFind(true)
}

// From the bridge device: it has failed to connect for about 2 minutes, and then at each 2-minute retry. Look for it;
// logged at Info only the first time in an outage.
void bridgeUnreachable(Object first = true) {
    if (!credentialsPresent() || pairedSerial() == null) return
    boolean quiet = (first == false)
    String m = "The bridge isn't answering at ${settings.bridgeIp?.trim()}; looking for it on the network"
    if (quiet) logDebug m else logInfo m
    startFind(true, quiet)
}

/*******************************************************************
 ***** Devices: the app parents them, at the top level
********************************************************************/
@Field static final Map ZONE_KINDS = [Dimmed: "dimmer", Switched: "switch"]
@Field static final Map NAME_STYLES = ["room": "Room + device name (e.g. \"Living Room Lamp\")", "name": "Device name only (e.g. \"Lamp\")"]
@Field static final Map PICO_DRIVERS = ["standard": "Standard: push/hold/double tap/release", "fast": "Fast: push/release"]
// Drivers by kind: [namespace, driver]
@Field static final Map CHILD_DRIVERS = [dimmer: ["draperw", "Lutron LEAP Dimmer (User)"],
                                         switch: ["hubitat", "Generic Component Switch"],
                                         pico:   ["draperw", "Lutron LEAP Pico (User)"],
                                         fastPico: ["draperw", "Lutron LEAP Fast Pico (User)"],
                                         keypad: ["draperw", "Lutron LEAP Keypad (User)"],
                                         motion: ["hubitat", "Generic Component Motion Sensor"]]
// Apps adding devices right now (by app id): a new device's refresh from its installed() then waits for the
// next connect when the bridge isn't connected (its resync reads every zone), rather than warning
@Field static ConcurrentHashMap<Long, Boolean> adding = new ConcurrentHashMap<>()

// Every zone and Pico in the bridge's last device list, keyed by what its DNI is made from (z<zoneId>, d<deviceId>),
// never by name. kind: dimmer, switch or pico; null for zone types we don't drive yet (fans, shades).
List<Map> bridgeEntries() { entriesOf((Map) atomicState.discovery) }

List<Map> entriesOf(Map d) {
    if (!d) return []
    List<Map> out = []
    d.zones?.each { Map z ->
        out << [key: "z${z.id}".toString(), id: z.id, kind: ZONE_KINDS[z.control?.toString()], name: z.name, area: z.area,
                type: (z.deviceType ?: z.control)?.toString(), deviceType: z.deviceType, model: z.model, deviceHref: z.deviceHref,
                location: z.location]
    }
    d.picos?.each { Map pc ->
        Integer n = pc.buttons ? pc.buttons.collect { (it.number as Integer) + 1 }.max() : 0
        out << [key: "d${pc.id}".toString(), id: pc.id, kind: "pico", name: pc.name, area: pc.area, type: pc.deviceType?.toString(),
                deviceType: pc.deviceType, model: pc.model, buttons: n, location: pc.location]
    }
    // RA3: keypads (buttons 1..N in Lutron's order, as the bridge numbers them) and the areas with occupancy sensors
    d.keypads?.each { Map k ->
        List bs = (List) k.buttons ?: []
        out << [key: "d${k.id}".toString(), id: k.id, kind: "keypad", name: k.name, area: k.area, type: k.deviceType?.toString(),
                deviceType: k.deviceType, model: k.model, buttons: bs.size(), location: k.location,
                ledButtons: bs.withIndex().findAll { b, i -> b.led != null }.collect { b, i -> i + 1 }.join(","),
                lutronButtons: bs.withIndex().collect { b, i -> "${i + 1}:${b.number}" }.join(",")]
    }
    d.occupancy?.each { Map o ->
        out << [key: "a${o.id}".toString(), id: o.id, kind: "motion", name: "Motion", area: o.name, type: "Occupancy",
                sensors: o.sensors ? ((List) o.sensors).join(",") : null, location: o.location]
    }
    return out
}

// The data values the app gives a device, shown on its page as "Lutron Device Type" and so on. lutronDeviceHref only
// on zone devices: the Lutron device controlling the zone (the zone itself is in the DNI).
Map lutronData(Map e) {
    [lutronDeviceType: e.deviceType, lutronModelNumber: e.model, lutronLocation: e.location,
     lutronDeviceHref: (e.kind in ["dimmer", "switch"]) ? e.deviceHref : null,
     ledButtons: e.ledButtons, lutronButtons: e.lutronButtons, sensors: e.sensors]
}

String dniOf(Map e) { "${app.id}/${e.kind in ['pico', 'keypad'] ? 'd' : (e.kind == 'motion' ? 'a' : 'z')}/${e.id}".toString() }
String keyOfDni(String dni) {
    String prefix = "${app.id}/"
    if (!dni?.startsWith(prefix)) return null
    List<String> p = dni.substring(prefix.length()).tokenize("/")
    return p.size() == 2 ? "${p[0]}${p[1]}".toString() : null
}

// "<room> <name>", as the built-in names them; just the name when it already starts with the room.
String deviceLabel(Object area, Object name) {
    String a = area?.toString()?.trim(), n = name?.toString()?.trim() ?: "Lutron device"
    if (!a || a.equalsIgnoreCase("root") || n.toLowerCase().startsWith(a.toLowerCase())) return n
    return "${a} ${n}".toString()
}

// The label a new device gets, from the Name new devices option (kept while the options are hidden)
String labelFor(Map e) {
    return (settings.nameStyle == "name") ? (e.name?.toString()?.trim() ?: "Lutron device") : deviceLabel(e.area, e.name)
}

Map roomOptions() {
    Map o = ["all": "All rooms"]
    bridgeEntries().collect { it.area?.toString() }.findAll { it && !it.equalsIgnoreCase("root") }.unique().sort().each { o[it] = it }
    return o
}

// What Select devices to add offers: zones and Picos with no device yet, labelled as they will be named, each
// with its Lutron device type. The room filter applies while the options are shown.
Map addOptions() {
    Set<String> have = lutronChildren().collect { it.deviceNetworkId.toString() } as Set
    List prev = (List) atomicState.prevKeys
    String room = (settings.showOptions && settings.roomFilter && settings.roomFilter != "all") ? settings.roomFilter.toString() : null
    Map o = [:]
    bridgeEntries().each { Map e ->
        if (e.kind && dniOf(e) in have) return
        if (room && e.area?.toString() != room) return
        String text = "${labelFor(e)} (${e.type ?: '?'})"
        if (!e.kind) text += " - not yet supported"
        if (prev != null && !(e.key in prev)) text += " (new)"
        o[e.key] = text
    }
    return o
}

String addNote() {
    String note = "Selected devices are added when you tap <b>Add selected device(s)</b> or <b>Next</b>."
    if (settings.nameStyle == "name") {
        Set<String> have = lutronChildren().collect { it.deviceNetworkId.toString() } as Set
        List<String> names = bridgeEntries().findAll { it.kind && !(dniOf(it) in have) }.collect { labelFor(it) }
        List<String> dup = names.findAll { String n -> names.count(n) > 1 }.unique()
        if (dup) note += "<br><b>Some new devices would have the same name:</b> ${dup.collect { esc(it) }.join(', ')}. Room + device name tells them apart."
    }
    Map last = (Map) atomicState.lastAdd
    if (last) note += "<br>Last added ${fmtTime(last.at as Long)}: ${last.created} created" +
                      (last.existing ? ", ${last.existing} already there" : "") +
                      (last.unsupported ? ", ${last.unsupported} not yet supported (nothing created)" : "") +
                      (last.failed ? ", <b>${last.failed} failed</b> (see the logs)" : "") + "."
    return note
}

// The app's devices, with the bridge's name for each; those whose zone or Pico isn't in the bridge's last
// list are marked, unless that list had nothing in it (a bridge mid-restore marks nothing missing).
String addedText() {
    List kids = lutronChildren().sort { it.displayName?.toString() }
    if (!kids) return "None yet."
    Map byKey = bridgeEntries().collectEntries { [(it.key): it] }
    Map d = (Map) atomicState.discovery
    boolean empty = d != null && !d.zones && !d.picos
    List<String> lines = kids.collect { ch ->
        Map e = (Map) byKey[keyOfDni(ch.deviceNetworkId.toString())]
        String where = e ? esc(deviceLabel(e.area, e.name)) : (d == null || empty ? "?" : "<b>not on the bridge any more</b>")
        "<li><a href='/device/edit/${ch.id}' target='_blank'>${esc(ch.displayName)}</a> (${where})</li>"
    }
    String note = empty ? "The bridge reported no devices in its last read, so none is marked missing.<br>" : ""
    return note + "<ul>${lines.join('')}</ul>"
}

// DNIs of the app's devices that aren't in the bridge's last list (none when that list was empty)
List<String> missingDnis() {
    Map d = (Map) atomicState.discovery
    if (!d || (!d.zones && !d.picos)) return []
    Set<String> keys = bridgeEntries().collect { it.key } as Set
    return lutronChildren().collect { it.deviceNetworkId.toString() }.findAll { !(keyOfDni(it) in keys) }
}

// Add selected device(s), or Next: adds what is ticked, then clears the selection.
void addSelected() {
    Object sel = settings.devicesToAdd
    List keys = (sel instanceof Collection) ? new ArrayList((Collection) sel) : (sel ? [sel] : [])
    app.removeSetting("devicesToAdd")
    if (!keys) return
    Map res = addDevices(keys.collect { it.toString() })
    atomicState.lastAdd = res + [at: now()]
}

// Creates a device for each key that has none yet: named after Lutron, labelled as Name new devices says, with its data
// values; Picos on the driver picked in Driver for new Pico devices. An existing device is never renamed. Returns
// [created:, existing:, unsupported:, failed:]. Then the bridge subscribes the new buttons and reads zone status.
Map addDevices(List<String> keys) {
    Map counts = [created: 0, existing: 0, unsupported: 0, failed: 0]
    Map byKey = bridgeEntries().collectEntries { [(it.key): it] }
    Object b = bridgeDevice()
    String health = b?.currentValue("healthStatus") ?: "unknown"
    List<String> added = []
    adding[app.id as Long] = true
    try {
        keys.each { String k ->
            Map e = (Map) byKey[k]
            if (e == null) { counts.failed++; logWarn "Add: ${k} isn't in the bridge's device list any more (Refresh device list)"; return }
            if (!e.kind) { counts.unsupported++; logInfo "Add: ${deviceLabel(e.area, e.name)} (${e.type}) is not yet supported; nothing created"; return }
            String dni = dniOf(e)
            if (getChildDevice(dni) != null) { counts.existing++; return }
            List drv = (List) CHILD_DRIVERS[(e.kind == "pico" && settings.picoDriver == "fast") ? "fastPico" : e.kind]
            String label = labelFor(e)
            Object ch
            try {
                ch = addChildDevice(drv[0], drv[1], dni, [name: label,
                                                         label: label, isComponent: false])
            } catch (Exception ex) {
                counts.failed++
                logErr "Add: couldn't create ${label} (${dni}) with ${drv[1]}: is the driver installed? (${ex.message})"
                return
            }
            counts.created++
            added << dni
            lutronData(e).each { dk, dv -> if (dv != null) ch.updateDataValue(dk.toString(), dv.toString()) }
            List<Map> evts = [[name: "healthStatus", value: health, descriptionText: "${label} healthStatus is ${health}".toString()]]
            if (e.kind in ["pico", "keypad"]) evts << [name: "numberOfButtons", value: e.buttons ?: 0, descriptionText: "${label} numberOfButtons is ${e.buttons ?: 0}".toString()]
            ch.parse(evts)
            logInfo "Add: created ${label} (${drv[1]}, ${dni})"
        }
    } finally {
        adding.remove(app.id as Long)
    }
    logInfo "Add: ${counts.created} created" + (counts.existing ? ", ${counts.existing} already there" : "") +
            (counts.unsupported ? ", ${counts.unsupported} not yet supported" : "") + (counts.failed ? ", ${counts.failed} failed" : "")
    if (added && b != null) {
        try { b.childrenAdded(added) } catch (Exception ex) { logErr "Couldn't tell the bridge about the new devices: ${ex.message}" }
    }
    return counts
}

/*******************************************************************
 ***** The devices' commands, handed to the bridge
********************************************************************/
void componentOn(Object cd)  { bridgeFor(cd)?.componentOn(cd) }
void componentOff(Object cd) { bridgeFor(cd)?.componentOff(cd) }
void componentSetLevel(Object cd, Object level, Object secs = null) { bridgeFor(cd)?.componentSetLevel(cd, level, secs) }
void componentStartLevelChange(Object cd, Object direction) { bridgeFor(cd)?.componentStartLevelChange(cd, direction) }
void componentStopLevelChange(Object cd) { bridgeFor(cd)?.componentStopLevelChange(cd) }
void componentFlash(Object cd, Object rateMs = null) { bridgeFor(cd)?.componentFlash(cd, rateMs) }
void componentPush(Object cd, Object n, Object times = 1) { bridgeFor(cd)?.componentPush(cd, n, times) }
void componentSetLed(Object cd, Object n, Object value) { bridgeFor(cd)?.componentSetLed(cd, n, value) }

void componentRefresh(Object cd) {
    Object b = bridgeFor(cd)
    if (b == null) return
    if (adding[app.id as Long] && b.currentValue("connection") != "connected") {
        logDebug "${cd?.displayName}: refresh at the bridge's next connect"
        return
    }
    b.componentRefresh(cd)
}

Object bridgeFor(Object cd) {
    Object b = bridgeDevice()
    if (b == null) logWarn "${cd?.displayName}: no bridge device (open the app to create it again); command not sent"
    return b
}

/*******************************************************************
 ***** Called by the bridge device: the app's devices
********************************************************************/
// The device with this DNI, or null. Never the bridge device itself.
Object childByDni(String dni) { (dni == null || dni == app.id.toString()) ? null : getChildDevice(dni) }

// Every zone and Pico device (all children but the bridge device)
List lutronChildren() { (getChildDevices() ?: []).findAll { it.deviceNetworkId?.toString() != app.id.toString() } }

/*******************************************************************
 ***** Pairing
********************************************************************/
// Creates the bridge device if needed (no Done required), then makes the key in its own execution
// (2-9 s on the hub) so the page answers at once.
void startPairing() {
    if (!settings.bridgeIp?.trim()) { logErr "Set the bridge IP address first"; return }
    if (ensureBridge() == null) { logErr "No bridge device: is the '${BRIDGE_DRIVER}' driver installed?"; return }
    atomicState.pairing = [phase: "making key", at: now()]
    runIn(1, "makeKeyAndPair")
}

void makeKeyAndPair() {
    Object b = bridgeDevice()
    if (b == null) { pairingFailed("no bridge device"); return }
    String keyPem, csrPem, pubB64
    try {
        long t0 = now()
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(RSA_BITS, new SecureRandom())
        KeyPair kp = kpg.generateKeyPair()
        byte[] csr = buildCsr(CSR_COMMON_NAME, kp.getPublic(), kp.getPrivate())
        keyPem = pem(kp.getPrivate().getEncoded(), "PRIVATE KEY")
        csrPem = pem(csr, "CERTIFICATE REQUEST")
        pubB64 = kp.getPublic().getEncoded().encodeBase64().toString()
        logInfo "Key and CSR made in ${now() - t0} ms"
    } catch (Exception e) {
        pairingFailed("making the key or CSR: ${e.message}")
        return
    }
    removePending()
    atomicState.pendingStore = putSecrets([(PENDING_KEY): keyPem], secretStore())
    atomicState.pendingPubKey = pubB64
    // Before the bridge starts: its "requesting certificate" report must come after this, never before
    atomicState.pairing = [phase: "press the bridge button", at: now()]
    String err = b.startPairing(settings.bridgeIp.trim(), csrPem, CSR_COMMON_NAME, pairingCa(settings.bridgeIp.trim()))
    if (err) { pairingFailed(err); return }
    logInfo "Pairing: press the small black button on the back of the bridge"
}

// The CA the bridge device checks the system against while pairing: the LAP CA for a Smart Bridge (and for a system
// found only by the listener, or typed in), the Lutron root for any processor (RA3; HomeWorks QSX and Athena, as in
// pylutron-caseta). The bridge device tries the other one once if the check fails.
String pairingCa(String ip) {
    Map m = ((List) atomicState.systems ?: []).find { it.ip == ip }
    String t = m?.systype?.toString()
    return (t && t != "SmartBridge") ? "lutron" : "lap"
}

void cancelPairing() {
    bridgeDevice()?.cancelPairing()
    removePending()
    atomicState.pairing = [phase: "failed", detail: "cancelled", at: now()]
    unschedule("makeKeyAndPair")
}

void clearPairing() {
    bridgeDevice()?.disconnect()
    removeCredentials()
    removePending()
    atomicState.remove("pairing")
    logInfo "Pairing cleared (the bridge still lists the integration until it is removed in the Lutron app)"
}

/*******************************************************************
 ***** CSR (PKCS#10, built by hand: no CSR builder on the allow list)
********************************************************************/
byte[] buildCsr(String cn, PublicKey pub, PrivateKey priv) {
    byte[] name = derSeq([derSet([derSeq([derOid(OID_COMMON_NAME), derUtf8(cn)])])])
    byte[] info = derSeq([derInt(0), name, pub.getEncoded(), derTag(0xA0, new byte[0])])  // attributes [0] {}
    Signature s = Signature.getInstance("SHA256withRSA")
    s.initSign(priv)
    s.update(info)
    byte[] sig = s.sign()
    return derSeq([info, derSeq([derOid(OID_SHA256_RSA), derNull()]), derBitString(sig)])
}

byte[] derTag(int tag, byte[] content) {
    ByteArrayOutputStream o = new ByteArrayOutputStream()
    o.write(tag)
    int n = content.length
    if (n < 0x80) {
        o.write(n)
    } else {
        List<Integer> len = []
        while (n > 0) { len.add(0, n & 0xFF); n = n >>> 8 }
        o.write(0x80 | len.size())
        len.each { o.write(it) }
    }
    o.write(content, 0, content.length)
    return o.toByteArray()
}

byte[] derSeq(List items) { derTag(0x30, concat(items)) }
byte[] derSet(List items) { derTag(0x31, concat(items)) }
byte[] derNull() { derTag(0x05, new byte[0]) }
byte[] derUtf8(String s) { derTag(0x0C, s.getBytes("UTF-8")) }
byte[] derInt(int v) { derTag(0x02, BigInteger.valueOf(v).toByteArray()) }
// BIT STRING with 0 unused bits
byte[] derBitString(byte[] b) {
    ByteArrayOutputStream o = new ByteArrayOutputStream()
    o.write(0)
    o.write(b, 0, b.length)
    return derTag(0x03, o.toByteArray())
}

byte[] derOid(String dotted) {
    List<Long> arcs = dotted.tokenize(".").collect { it as Long }
    ByteArrayOutputStream o = new ByteArrayOutputStream()
    o.write((int) (arcs[0] * 40 + arcs[1]))
    arcs.drop(2).each { Long arc ->
        List<Integer> b7 = []
        long v = arc
        b7.add(0, (int) (v & 0x7F))
        v = v >>> 7
        while (v > 0) { b7.add(0, (int) ((v & 0x7F) | 0x80)); v = v >>> 7 }
        b7.each { o.write(it) }
    }
    return derTag(0x06, o.toByteArray())
}

// Byte arrays go in untyped Lists: the sandbox rejects byte[] in generics, casts and closure parameters
// ("Importing [[B] is not allowed")
byte[] concat(List parts) {
    ByteArrayOutputStream o = new ByteArrayOutputStream()
    for (Object p : parts) o.write(p, 0, p.length)
    return o.toByteArray()
}

/*******************************************************************
 ***** PEM and X.509
********************************************************************/
String pem(byte[] der, String label) {
    String b64 = der.encodeBase64().toString()
    List<String> lines = []
    for (int i = 0; i < b64.length(); i += 64) lines << b64.substring(i, Math.min(i + 64, b64.length()))
    return "-----BEGIN ${label}-----\n${lines.join('\n')}\n-----END ${label}-----\n".toString()
}

byte[] pemBody(String pemText) {
    String body = pemText.readLines().findAll { !it.startsWith("-----") }.join("").replaceAll(/\s/, "")
    return body.decodeBase64()
}

X509Certificate parseCert(String pemText) {
    CertificateFactory cf = CertificateFactory.getInstance("X.509")
    return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(pemBody(pemText)))
}

/*******************************************************************
 ***** Helpers
********************************************************************/
String esc(Object o) {
    String s = (o == null) ? "" : o.toString()
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

String fmtTime(Long ms) {
    return (ms == null) ? "?" : new Date(ms).format("yyyy-MM-dd HH:mm", location.timeZone)
}

/*******************************************************************
 ***** Logging Functions (adapted from jtp10181's zwaveDriverLibrary.groovy, Apache-2.0)
********************************************************************/
@Field static final Map LOG_LEVELS = [0:"Error", 1:"Warn", 2:"Info", 3:"Debug", 4:"Trace"]
@Field static final Map LOG_TIMES = [0:"Indefinitely", 30:"30 Minutes", 60:"1 Hour", 120:"2 Hours", 180:"3 Hours", 360:"6 Hours", 720:"12 Hours", 1440:"24 Hours"]

void checkLogLevel(Map levelInfo = [level:null, time:null]) {
    unschedule("logsOff")
    if (settings.logLevel == null) {
        app.updateSetting("logLevel", [value:"3", type:"enum"])
        levelInfo.level = 3
    }
    if (settings.logLevelTime == null) {
        app.updateSetting("logLevelTime", [value:"30", type:"enum"])
        levelInfo.time = 30
    }
    if (levelInfo.level == null) levelInfo = getLogLevelInfo()
    String logMsg = "Logging Level is: ${LOG_LEVELS[levelInfo.level]} (${levelInfo.level})"
    if (levelInfo.level >= 3 && levelInfo.time > 0) {
        logMsg += " for ${LOG_TIMES[levelInfo.time]}"
        runIn(60*levelInfo.time, "logsOff")
    }
    logInfo(logMsg)
    if (levelInfo.level <= 2) state.lastLogLevel = levelInfo.level
}

Map getLogLevelInfo() {
    Integer level = settings.logLevel != null ? settings.logLevel as Integer : 1
    Integer time = settings.logLevelTime != null ? settings.logLevelTime as Integer : 30
    return [level: level, time: time]
}

void logsOff() {
    logWarn "Debug and Trace logging disabled..."
    if (logLevelInfo.level >= 3) {
        Integer lastLvl = state.lastLogLevel != null ? state.lastLogLevel as Integer : 2
        app.updateSetting("logLevel", [value:lastLvl.toString(), type:"enum"])
        logWarn "Logging Level is: ${LOG_LEVELS[lastLvl]} (${lastLvl})"
    }
}

void logErr(String msg) {
    log.error "${app.label}: ${msg}"
}
void logWarn(String msg) {
    if (logLevelInfo.level>=1) log.warn "${app.label}: ${msg}"
}
void logInfo(String msg) {
    if (logLevelInfo.level>=2) log.info "${app.label}: ${msg}"
}
void logDebug(String msg) {
    if (logLevelInfo.level>=3) log.debug "${app.label}: ${msg}"
}
void logTrace(String msg) {
    if (logLevelInfo.level>=4) log.trace "${app.label}: ${msg}"
}
