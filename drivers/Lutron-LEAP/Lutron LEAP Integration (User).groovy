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
 *  ## [1.0.1] - 2026-11-12
 *    - Pico and keypad button numbers come from the bridge (Hubitat's Lutron Telnet numbering for Picos, Lutron's own
 *      numbers for keypads): numberOfButtons, LED buttons and the button map follow them.
 *    - Bound to one Lutron system: the bridge's serial is checked at each connect; another system is refused while the
 *      app has devices (its zone 3 isn't this app's zone 3), and taken when it has none.
 *    - A new pairing's credentials replace the old ones only once they work on the bridge's LEAP port, within 2 minutes;
 *      Cancel works until then too, and either way the pairing before is kept.
 *    - Flash passes the dimmer's time limit to the bridge.
 *    - Caseta occupancy: a room with sensors is offered as "<room> Motion (Occupancy, untested)"; a list from the bridge
 *      that couldn't read the rooms keeps the ones from before.
 *    - Cancel during key generation wins: a key finished after it is discarded and the pairing port isn't opened.
 *    - Each pairing keeps its own address: a new pairing's certificate is tried where it paired, and the kept pairing
 *      connects at its own address (moved by Done with a new address, or by following the bridge), not one typed in
 *      for a pairing that then failed or was cancelled (an install from before keeps the address it was last verified at).
 *    - Following the bridge compares what mDNS finds with the address the link uses, not the field; a browse that finds
 *      no Lutron system asks the _leap._tcp listener too.
 *    - An occupancy device follows its sensors to a new area only when all of them are there.
 *    - Pairing uses the address as it was when Pair was clicked.
 *    - Following the bridge also asks the mDNS listener when the browse doesn't show the paired bridge at a new address,
 *      and an address both report keeps the serial from either.
 *    - Connect now uses the address shown in the field, as Done does. Cancel wins over a key handed to the bridge
 *      meanwhile, and its clean-up (its bridge session included) leaves a newer attempt alone; a pairing that fails takes
 *      its stored certificate with it.
 *    - "Name new devices" names new devices only: each device keeps the style it was made with when it follows Lutron.
 *    - The bridge leaves a new certificate's trial only once the app has kept it. The pairing before is never written
 *      over: credentials have two slots, a new one goes into the free slot, and the app switches to it once it worked,
 *      slot, store, fingerprints and address in one record written at once. One caller settles a trial (its promotion,
 *      failure, expiry or Cancel), which settles it on every way out.
 *    - A device list with keypads or occupancy only counts as a read with devices (missing devices are marked).
 *    - A look for the bridge keeps a follow asked for during its browse. A zone whose controlling device changes under
 *      the same number Warns (its rules act on that number).
 *    - A new address saved (Done) during a pairing is moved to by the first Done after the pairing ends, instead of
 *      being taken as already applied.
 *    - Cancel wins over a certificate the bridge is handing over at that moment, and over a pairing status reported then.
 *      Pair is offered again 15 s after Cancel, once the cancelled attempt's work has ended. A certificate for an
 *      attempt that has ended is ignored, however late it comes; so are the bridge's progress and failure reports for one.
 *    - Pair is refused while a pairing is under way (from a page opened before it began, or two at the same moment).
 *      A key job that never finished (the hub restarted during it) no longer keeps Pair away.
 *    - Clear pairing is refused while a pairing is under way, and ends any key job left over. A key job's failure only
 *      fails its own attempt.
 *    - A pairing interrupted by a hub restart ends then; one whose bridge session was lost otherwise (a bridge code save)
 *      ends after its phase's time limit. The working pairing is kept.
 *    - Following the bridge, an address the live browse finds outranks one the listener has cached. The page shows when
 *      a new certificate is being checked.
 *    - A hub restart while a new certificate is being checked leaves the check to finish. Cancel does nothing when no
 *      pairing is under way.
 *    - Connect now always says so in the log (on a link that is up it reconnects it); so do Pair and Cancel. A pairing
 *      that worked leaves no bookkeeping behind in the app's state.
 *    - Picos get their list of buttons (lutronButtons), as keypads do: a Pico refuses a button it doesn't have itself.
 *    - A discovered service with a malformed serial no longer stops discovery, and a look that stopped part way no
 *      longer blocks the next one.
 *    - Debug logging ends at its deadline also when its timer was lost (checked at a hub start and when the app is
 *      opened).
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

@Field static final String VERSION = "1.0.1"
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
@Field static final Long FIND_STALE_MS = 60_000L        // a look whose last tick is this old has stopped (a tick threw)

@Field static final String CSR_COMMON_NAME = "hubitat_leap_user"  // also the DisplayName the bridge keeps for the pairing
@Field static final Integer RSA_BITS = 2048

// Credentials: password settings when the probe survives there, otherwise atomicState.
// The setting names and the state names are the same. The key waiting for the button is kept the same way.
@Field static final List<String> CRED_NAMES = ["leapKeyPem", "leapCertPem", "leapCaPem"]
// Two slots for the credentials, under their own names: the pairing in use is one (credRecord().set, "leap" when
// unset), a new pairing's candidate goes into the other, and when it works on the bridge's LEAP port the app makes that
// slot the one in use. The pairing before is never written over, so a failure while keeping a new one leaves it as it was.
@Field static final Map<String, List<String>> CRED_SLOTS = [leap: ["leapKeyPem", "leapCertPem", "leapCaPem"],
                                                             cand: ["candKeyPem", "candCertPem", "candCaPem"]]
// Who settles a new certificate's trial: promotion (credentialsWorked), its failure (candidateFailed), its expiry
// (trialExpired) or Cancel. The app isn't single-threaded, so two can run at once; each must first claim the trial
// (putIfAbsent: one wins), and the others leave it alone. Keyed "<app id>:<trial id>".
@Field static ConcurrentHashMap<String, Boolean> trialClaims = new ConcurrentHashMap<>()
// A Pair being started, keyed by app id: atomicState can't check and write in one step, so two Pair requests at the same
// moment would both pass the busy check. Held only for startPairing's check and writes.
@Field static ConcurrentHashMap<String, Boolean> pairStarts = new ConcurrentHashMap<>()

@Field static final String PENDING_KEY = "pendingKeyPem"
@Field static final String PROBE_SETTING = "credentialProbe"
@Field static final Integer PROBE_CHARS = 1800                 // a 2048-bit PKCS#8 key PEM is ~1704 characters
@Field static final Integer TRIAL_SECS = 120          // time the new certificate gets to connect on the LEAP port
// After Cancel, Pair waits this long: the cancelled attempt's work still running (a key being made, 2-9 s; its hand-off
// to the bridge; a certificate being kept) then always ends before a new attempt starts, so it never meets one
@Field static final Long CANCEL_COOLDOWN_MS = 15_000L
@Field static final List<String> BUSY_PHASES = ["making key", "press the bridge button", "requesting certificate", "checking the new certificate"]
// How long each phase can last before the app counts the pairing as stopped. Their own ends live in memory that a hub
// restart or a bridge code save clears (the key job; the bridge's session, whose timeout then finds nothing to end):
// the key job takes 2-9 s; the bridge allows 180 s for the button (restarted once on a certificate retry) and 10 s for
// the certificate. "checking the new certificate" isn't here: the app's own trialExpired job ends it.
@Field static final Map<String, Long> PHASE_LIMIT_MS = ["making key": 60_000L, "press the bridge button": 300_000L,
                                                        "requesting certificate": 120_000L]

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
    checkLogsOff()
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
    boolean cooling = coolingDown()
    Map opts = [name: "configPage", title: "Lutron System Configuration", nextPage: credentialsPresent() ? "devicesPage" : "mainPage"]
    if (busy || looking || cooling) opts.refreshInterval = 3
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
                if (cooling) paragraph "Cancelled. <b>Pair</b> is offered again in a few seconds."
                else input name: "btnPair", type: "button", title: credentialsPresent() ? "Re-pair with Lutron" : "Pair with the bridge"
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

String statusText() {
    if (atomicState.otherSystem && lutronChildren())
        return "Paired with a different Lutron system from the one this app's devices belong to: not connected. Add that system in a new app instance, or remove this app's devices and connect again."
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
    Object pairedAt = credRecord().at
    String head = credentialsPresent() ? "<b>Paired</b>. (Re-pair only if the Lutron system was reset.)" : "Not paired."
    String st = null
    if (pairingStopped(p)) st = p.phase == "making key" ? "Last pairing failed: the key wasn't made (the hub may have restarted)" :
                                "Last pairing failed: the pairing stopped (the hub may have restarted)"
    else if (p?.phase == "making key") st = "Making the key (a few seconds)..."
    else if (p?.phase == "press the bridge button") st = "<b>Press the small black button on the back of the bridge now</b> (within 3 minutes)."
    else if (p?.phase == "requesting certificate") st = "Button pressed; asking the bridge for a certificate..."
    else if (p?.phase == "checking the new certificate") st = "Checking the new certificate with the bridge..."
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
    String store = credRecord().store
    String where = store ? "Credentials are kept in <b>${store == 'settings' ? 'app password settings' : 'app state'}</b>; ${credentialCheck()}." : "No credentials stored."
    return "Password setting test with a dummy the size of a key: ${esc(probe)}.<br>${where}"
}

void appButtonHandler(String btn) {
    switch (btn) {
        case "btnPair":    startPairing(); break
        case "btnCancel":  cancelPairing(); break
        case "btnConnect":
            // the address shown in the field, as Done would take it (not during a pairing: that carries its own)
            String shown = settings.bridgeIp?.trim()
            if (shown && credentialsPresent() && !pairingBusy() && shown != linkIp()) {
                updateCred(ip: shown)
                state.lastBridgeIp = shown
                logInfo "Connect now: connecting at ${shown}, the address in the field"
            } else {
                // a link that is up is dropped and made again: said here, as every other reconnect has its reason logged
                boolean up = bridgeDevice()?.currentValue("connection")?.toString() == "connected"
                logInfo "Connect now: ${up ? 'reconnecting' : 'connecting'} at ${linkIp() ?: '(no address)'}"
            }
            bridgeDevice()?.connectSoon()
            break
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
        if (check == "intact") logDebug "Credentials intact (${credRecord().store})"
        else logWarn "Credentials ${check}: pair again"
    }
    // Connect when not connected, or reconnect when the IP changed; otherwise leave a live link alone
    String ip = settings.bridgeIp?.trim()
    Object b = bridgeDevice()
    // Done with a new address moves the kept pairing there (not during a pairing: that one carries its own address)
    boolean busy = pairingBusy()
    if (ip && ip != state.lastBridgeIp && !busy) updateCred(ip: ip)
    if (credentialsPresent() && b != null && (ip != state.lastBridgeIp || b.currentValue("connection") != "connected")) b.connectSoon()
    // During a pairing the new address isn't marked as applied, so the first Done after it moves the pairing there
    if (busy && ip != state.lastBridgeIp) logInfo "A pairing is under way: the address ${ip} is used from the next Done after it ends"
    else state.lastBridgeIp = ip
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

// Hub start: the listener again, and one look for the paired bridge (it may have moved while the hub was down)
void hubStarted(Object evt) {
    logDebug "Hub started"
    checkLogsOff()
    // A phase whose end was in memory (the key job, the bridge's pairing session) has ended; the working pairing, if any,
    // is kept. A certificate on trial carries on: its end (trialExpired) and the bridge's trial persist.
    Map p = (Map) atomicState.pairing
    if (pairingBusy() && PHASE_LIMIT_MS.containsKey(p?.phase?.toString()))
        pairingFailed("the hub restarted during the pairing", atomicState.pairAttempt?.toString())
    listenMdns()
    if (credentialsPresent() && pairedSerial() != null) startFind(true)
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

// The pairing in use, as one record written in one go: [set: slot, store: settings | state, hash: fingerprints, at:,
// ip: its address]. An install from before keeps these as separate keys (credSet, credStore, credHash, pairedAt,
// credIp): read from those until the first write, which replaces them with the record.
Map credRecord() {
    Map c = (Map) atomicState.cred
    if (c != null) return c
    if (atomicState.credStore == null && atomicState.credIp == null) return [:]
    return [set: atomicState.credSet ?: "leap", store: atomicState.credStore, hash: atomicState.credHash, at: atomicState.pairedAt, ip: atomicState.credIp]
}

void putCred(Map c) {
    atomicState.cred = c
    ["credSet", "credStore", "credHash", "pairedAt", "credIp"].each { atomicState.remove(it) }
}

void updateCred(Map changes) { putCred(new LinkedHashMap(credRecord()) + changes) }

boolean credentialsPresent() { credRecord().store != null }

// The candidate's slot becomes the pairing in use: it was read back whole (c), its fingerprints are kept, and the pairing
// and its store switch in one write. Only then are the old slot's secrets removed; a failure before the switch leaves
// the pairing before untouched.
void useCandidate(Map c, String slot) {
    if (c == null || !c.values().every { it }) throw new IllegalStateException("the new credentials didn't read back")
    String oldSlot = credentialsPresent() ? activeSlot() : null
    Map hashes = c.collectEntries { String k, String v -> [(k): sha256(v)] }
    String ip = atomicState.candIp?.toString() ?: credRecord().ip
    if (!atomicState.candStore || readSlot(slot, atomicState.candStore?.toString()) == null) throw new IllegalStateException("the new credentials are gone")
    // slot, store, fingerprints, time and address committed together: one write
    putCred([set: slot, store: atomicState.candStore, hash: hashes, at: now(), ip: ip])
    atomicState.remove("candStore")
    atomicState.remove("candIp")
    // the slot before is now the free one: cleared (best effort; a leftover is overwritten by the next pairing)
    if (oldSlot && oldSlot != slot) CRED_SLOTS[oldSlot].each { String k -> try { removeSecret(k) } catch (Exception ignored) { } }
}

void removeCredentials() {
    CRED_SLOTS[activeSlot()].each { removeSecret(it) }
    Object ip = credRecord().ip
    putCred(ip ? [ip: ip] : [:])                         // one write; the address stays (a new pairing at the same bridge uses it)
}

// The candidate: the same three secrets under other names, in the same store; credentialsWorked() makes them the pairing
String activeSlot() { credRecord().set == "cand" ? "cand" : "leap" }
String candidateSlot() { activeSlot() == "leap" ? "cand" : "leap" }

// A slot's three secrets by role (leapKeyPem, leapCertPem, leapCaPem), or null if any is missing
Map readSlot(String slot, String store) {
    if (!store) return null
    List<String> names = CRED_SLOTS[slot]
    Map c = [leapKeyPem: getSecret(names[0], store), leapCertPem: getSecret(names[1], store), leapCaPem: getSecret(names[2], store)]
    return c.values().every { it } ? c : null
}

void storeCandidate(String keyPem, String certPem, String caPem) {
    removeCandidate()
    trialClaims.keySet().removeAll { it.startsWith("${app.id}:".toString()) }   // the trials before are long settled
    atomicState.candId = UUID.randomUUID().toString()
    List<String> n = CRED_SLOTS[candidateSlot()]
    atomicState.candStore = putSecrets([(n[0]): keyPem, (n[1]): certPem, (n[2]): caPem], secretStore())
}

// True for the one caller that may settle this trial (see trialClaims)
boolean claimTrial() {
    String id = atomicState.candId?.toString()
    return id != null && trialClaims.putIfAbsent("${app.id}:${id}".toString(), true) == null
}

void removeCandidate() {
    CRED_SLOTS[candidateSlot()].each { removeSecret(it) }
    atomicState.remove("candId")
    atomicState.remove("candStore")
    atomicState.remove("candIp")
}

Map readCandidate() { readSlot(candidateSlot(), atomicState.candStore?.toString()) }

void removePending() {
    removeSecret(PENDING_KEY)
    atomicState.remove("pendingStore")
    atomicState.remove("pendingPubKey")
}

Map readCredentials() {
    String store = credRecord().store
    if (!store) return null
    List<String> names = CRED_SLOTS[activeSlot()]
    return [leapKeyPem: getSecret(names[0], store), leapCertPem: getSecret(names[1], store), leapCaPem: getSecret(names[2], store)]
}

// "intact", or what is wrong (without revealing anything). Reads only: it runs inside the bridge's calls too.
String credentialCheck() {
    Map c = readCredentials()
    if (c == null) return "none stored"
    Map hashes = (Map) credRecord().hash ?: [:]
    List<String> bad = CRED_NAMES.findAll { String k -> c[k] == null || sha256(c[k]) != hashes[k] }
    if (!bad) return "intact"
    return "changed or missing: " + bad.collect { [leapKeyPem: "key", leapCertPem: "certificate", leapCaPem: "root CA"][it] }.join(", ")
}

String sha256(String s) {
    MessageDigest md = MessageDigest.getInstance("SHA-256")
    return md.digest(s.getBytes("UTF-8")).encodeHex().toString()
}

/*******************************************************************
 ***** Called by the bridge device: credentials and the device list (pairing reports: Pairing)
********************************************************************/
// The bridge device asks for these each time it connects. Reads only (no state writes): it runs while
// the bridge is mid-call. null when not paired or a credential no longer matches its fingerprint.
// Each set of credentials goes with its own address: a new pairing's with the address it paired at (candIp), the kept
// pairing's with the address it last connected or was moved to (credIp: a Done with a new address, the bridge
// followed). The address field alone can be mid-edit, or changed during a pairing that is then cancelled or fails.
Map leapCredentials() {
    String field = settings.bridgeIp?.trim()
    Map cand = readCandidate()
    String candAt = atomicState.candIp ?: field
    if (cand && candAt) return [ip: candAt, certificate: cand.leapCertPem, privateKey: cand.leapKeyPem, caCertificate: cand.leapCaPem, provisional: true]
    String ip = credRecord().ip?.toString() ?: field
    if (!credentialsPresent() || !ip) return null
    String check = credentialCheck()
    if (check != "intact") {
        logWarn "Not handing over the credentials: ${check}. Pair again."
        return null
    }
    Map c = readCredentials()
    return [ip: ip, certificate: c.leapCertPem, privateKey: c.leapKeyPem, caCertificate: c.leapCaPem]
}

// The bridge's device list, from the bridge's own execution: system, zones, picos, keypads, occupancy, scenes, readAt.
// The keys of the list before it are kept, to tag what is new.
void discoveryResult(Map list) {
    Map before = (Map) atomicState.discovery
    // occupancyUnknown: the bridge couldn't read the occupancy groups this time (Caseta), so the rooms from the list
    // before stay; a list with an empty occupancy clears them
    if (list != null && list.occupancyUnknown == true) {
        list.remove("occupancyUnknown")
        list.occupancy = before?.occupancy
    }
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
        // Only when every sensor it had is in that one area: a device that kept just part of its sensors would take its
        // rules to another room
        Map to = (targets.size() == 1 && mine.every { String sid -> ((List) targets[0].sensors ?: []).collect { it.toString() }.contains(sid) }) ?
                 (Map) targets[0] : null
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
                // A zone controlled by another device under the same number: the Hubitat device (and its rules) stays
                // bound to the number, which may now name another load (a project that reused it)
                if (dk == "lutronDeviceHref" && old != null && ch.deviceNetworkId?.toString()?.contains("/z/")) {
                    logWarn "${ch.displayName} (zone ${e.id}): now controlled by another device (${old} -> ${dv}); its rules act on what zone ${e.id} controls now: check them if that's another load"
                }
                ch.updateDataValue(dk, dv.toString())
                if (old == null && dk in ["lutronDeviceType", "lutronModelNumber", "lutronLocation", "lutronDeviceHref", "lutronButtons"]) return   // first set: not news
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
    String lutron = labelFor(e, nameStyleOf(ch, e))
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
// Bridge, from an active browse (~4 s: never call this from a page render). When the browse fails or finds none, the
// _leap._tcp listener's entries with LEAP on 8081 (systype unknown: a Smart Bridge). Serials only in Debug logs.
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
    }
    // The listener too when the browse failed or found no Lutron system: it may have seen the bridge announce itself
    if (found == null || out.isEmpty()) out.addAll(listenerSystems())
    return mergeByIp(out)
}

// One record per address, each field from whichever source has it: the browse's system type, a serial only the
// listener has (the paired bridge is known by its serial)
List<Map> mergeByIp(List<Map> l) {
    Map<String, Map> by = [:]
    l.each { Map e ->
        Map m = by[e.ip?.toString()]
        if (m == null) { by[e.ip?.toString()] = new LinkedHashMap(e); return }
        if (m.serial == null && e.serial != null) m.serial = e.serial
        if (m.systype == null && e.systype != null) { m.systype = e.systype; m.untested = e.untested }
    }
    return by.values().sort { it.ip }
}

// The _leap._tcp listener's entries with LEAP on 8081, as readSystems' entries (systype unknown: a Smart Bridge)
List<Map> listenerSystems() {
    List<Map> out = []
    List entries = []
    try { entries = (List) getMDNSEntries(MDNS_TYPE) ?: [] } catch (Exception e) { logDebug "getMDNSEntries threw ${e.message}" }
    entries.each { Object en ->
        if (!(en instanceof Map) || en.port?.toString() != LEAP_PORT.toString()) return
        String ip = firstIp(en.ip4Addresses)
        if (ip) out << [ip: ip, serial: serialOfHost(en.server), systype: null, untested: false]
    }
    logDebug "mDNS ${MDNS_TYPE} listener: ${entries.size()} entries, ${out.size()} on port ${LEAP_PORT}: ${out}"
    return out
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
    if (!h || !(h ==~ /(?i)[0-9a-f]{1,15}/)) return null    // 15 hex digits always fit a Long; Lutron's have 8
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

// The bridge's own serial number (/device, IsThisDevice), checked by the bridge at each connect before it sends or
// applies anything. The devices are keyed by Lutron's ids (<app>/z/3), which mean other loads on another system, so the
// app stays bound to one: "ok" (that system, or the first one), "new" (another system and no devices yet: the app takes
// it), "wrong" (another system while this app has devices: they belong to the first one).
// ip: the address of the session that read the serial (from the bridge's identity check; null from a device list).
String bridgeIdentity(Object serial, Object ip = null) {
    if (serial == null) return null
    Long sn
    try { sn = serial as Long } catch (Exception e) { return null }
    Long bound = pairedSerial()
    if (bound == null || sn == bound) {
        if (sn != bound) logDebug "Bridge serial number: ${sn}"
        atomicState.bridgeSerial = sn
        atomicState.remove("otherSystem")
        if (ip) atomicState.verifiedIp = ip.toString()            // the address this system was last verified at
        if (!atomicState.candStore) seedCredIp()                 // a trial's address is kept when it works
        return "ok"
    }
    logDebug "Bridge serial number: ${sn}; this app's devices belong to ${bound}"
    if (lutronChildren()) {
        atomicState.otherSystem = now()
        logWarn "The bridge at ${settings.bridgeIp?.trim()} is a different Lutron system from the one this app's devices belong to: not connected"
        return "wrong"
    }
    atomicState.bridgeSerial = sn
    atomicState.remove("discovery")                              // the old system's device list
    atomicState.remove("otherSystem")
    logInfo "Paired with another Lutron system: its devices can be added once it has been read"
    return "new"
}

// A look is running while its ticks keep coming: each tick stamps "at", so one that stopped can't block the next look
boolean findActive() {
    Map f = (Map) atomicState.find
    return f?.active == true && f.at != null && now() - (f.at as Long) < FIND_STALE_MS
}

// Opening Lutron System Configuration starts a look (its browses run in their own executions, never in the page
// render), unless one is running or the last one is recent.
void pageFind() {
    Map f = (Map) atomicState.find
    if (findActive()) return
    if (f?.at != null && now() - (f.at as Long) < FIND_AGAIN_MS) return
    startFind(false)
}

// Browses FIND_FIRST_SECS later, then every FIND_EVERY_SECS, up to FIND_READS times (the listener is registered too,
// for the fallback).
// follow: also look for the paired serial and follow it. A look already running takes the follow on.
void startFind(boolean follow, boolean quiet = false) {
    listenMdns()
    Map f = (Map) atomicState.find
    if (findActive()) {
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
    // The browse blocks ~4 s: a follow asked for meanwhile (startFind(true)) is in atomicState, not in f
    Map fNow = (Map) atomicState.find
    boolean follow = f.follow == true || fNow?.follow == true
    Long sn = pairedSerial()
    String cur = linkIp()                                 // where the link is trying, not the field (it may be mid-edit)
    // Following: the browse may list other systems but not the paired one; the listener may have it. A live browse that
    // lists it (at any address) outranks the listener's cache, which can hold an address it had before.
    if (follow && sn != null && !sys.any { it.serial != null && (it.serial as Long) == sn }) {
        sys = mergeByIp(sys + listenerSystems())
    }
    if (sys) atomicState.systems = sys
    // The paired bridge can be listed both at the failing address (a stale entry, as while it restarts) and at its new
    // one: follow the new one; while only the failing one is listed, read on.
    List<Map> mine = (follow && sn != null) ? sys.findAll { it.serial != null && (it.serial as Long) == sn } : []
    Map pick = mine.find { it.ip != cur } ?: (mine ? mine[0] : null)
    boolean moved = pick != null && pick.ip != cur
    boolean done = follow ? (moved || reads >= FIND_READS) : (!sys.isEmpty() || reads >= FIND_READS)
    atomicState.find = [active: !done, reads: reads, follow: follow, quiet: f.quiet, at: now()]
    if (!done) { runIn(FIND_EVERY_SECS, "findTick"); return }
    Closure say = f.quiet ? { String m -> logDebug m } : { String m -> logInfo m }
    if (sys) say("Lutron systems found: ${sys.collect { it.ip }.join(', ')}")
    else say("No Lutron system found on the network (${reads} looks)")
    if (follow) {
        if (pick) followTo(pick.ip.toString())
        if (!moved && sn != null) say("The paired bridge wasn't found at a new address; it keeps trying ${cur}")
    } else if (sys) {
        autoPick(sys)
    }
}

// The bridge was found at this address (by its serial): a new one is saved, and the bridge connects there.
void followTo(String ip) {
    String old = linkIp()
    if (ip == old) { logDebug "The bridge is still at ${ip}"; return }
    if (settings.bridgeIp?.trim() != ip) setBridgeIp(ip)
    atomicState.lastBridgeIp = ip
    updateCred(ip: ip)
    logWarn "The bridge moved from ${old ?: '(no address)'} to ${ip}"
    bridgeDevice()?.connectSoon()
}

// The address the bridge device connects to with the kept pairing (leapCredentials): its own, else the field
String linkIp() { credRecord().ip?.toString() ?: settings.bridgeIp?.trim() }

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
    return lutronChildren() ? "<b>This isn't the Lutron system this app's devices belong to</b>: pick the paired one, or add this system in a new app instance." :
                              "<b>This isn't the Lutron system the app is paired with: pair again</b> (Re-pair with Lutron), or pick the paired one."
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

// The device with this DNI, or null. Never the bridge device itself.
Object childByDni(String dni) { (dni == null || dni == app.id.toString()) ? null : getChildDevice(dni) }

// Every zone and Pico device (all children but the bridge device)
List lutronChildren() { (getChildDevices() ?: []).findAll { it.deviceNetworkId?.toString() != app.id.toString() } }

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
    // Each button's Hubitat number (n) comes from the bridge, which routes the events by it; numberOfButtons is the
    // highest, as Hubitat's buttons run 1..N. The numbers can have gaps (a keypad's do), so the device gets
    // its own list too (lutronButtons, "button:Lutron number"), as a keypad does.
    d.picos?.each { Map pc ->
        List bs = (List) pc.buttons ?: []
        List<Integer> ns = bs.collect { hubitatButton(it, (it.number as Integer) + 1) }
        out << [key: "d${pc.id}".toString(), id: pc.id, kind: "pico", name: pc.name, area: pc.area, type: pc.deviceType?.toString(),
                deviceType: pc.deviceType, model: pc.model, buttons: ns ? ns.max() : 0, location: pc.location,
                lutronButtons: bs.collect { "${hubitatButton(it, (it.number as Integer) + 1)}:${it.number}" }.join(",")]
    }
    // RA3: keypads (each button is Lutron's own number) and the areas with occupancy sensors
    d.keypads?.each { Map k ->
        List bs = (List) k.buttons ?: []
        List<Integer> ns = bs.collect { hubitatButton(it, it.number as Integer) }
        out << [key: "d${k.id}".toString(), id: k.id, kind: "keypad", name: k.name, area: k.area, type: k.deviceType?.toString(),
                deviceType: k.deviceType, model: k.model, buttons: ns ? ns.max() : 0, location: k.location,
                ledButtons: bs.findAll { it.led != null }.collect { hubitatButton(it, it.number as Integer) }.join(","),
                lutronButtons: bs.collect { "${hubitatButton(it, it.number as Integer)}:${it.number}" }.join(",")]
    }
    d.occupancy?.each { Map o ->
        // Caseta's occupancy (by occupancy group) is built from pylutron-caseta and a bridge with no sensors: untested
        String occType = (d.system?.deviceType?.toString() == "SmartBridge") ? "Occupancy, untested" : "Occupancy"
        out << [key: "a${o.id}".toString(), id: o.id, kind: "motion", name: "Motion", area: o.name, type: occType,
                sensors: o.sensors ? ((List) o.sensors).join(",") : null, location: o.location]
    }
    return out
}

// A button's Hubitat number from the bridge's list; a list saved before the bridge gave one has the fallback
Integer hubitatButton(Map b, Integer fallback) { (b?.n != null) ? (b.n as Integer) : fallback }

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

// The label a new device gets, from the Name new devices option (kept while the options are hidden); style: a device's
// own (nameStyleOf), so changing the option doesn't rename devices that exist
String labelFor(Map e, String style = null) {
    String st = style ?: settings.nameStyle?.toString()
    return (st == "name") ? (e.name?.toString()?.trim() ?: "Lutron device") : deviceLabel(e.area, e.name)
}

// The naming style a device was made with (data value lutronNameStyle). A device from before it was kept: the style its
// name matches now, kept from then on
String nameStyleOf(Object ch, Map e) {
    String st = ch.getDataValue("lutronNameStyle")
    if (st in ["room", "name"]) return st
    String n = ch.name?.toString()
    st = (n == labelFor(e, "name") && n != labelFor(e, "room")) ? "name" : (n == labelFor(e, "room") ? "room" : (settings.nameStyle?.toString() == "name" ? "name" : "room"))
    ch.updateDataValue("lutronNameStyle", st)
    return st
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
    boolean empty = d != null && !d.zones && !d.picos && !d.keypads && !d.occupancy
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
    if (!d || (!d.zones && !d.picos && !d.keypads && !d.occupancy)) return []
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
            ch.updateDataValue("lutronNameStyle", settings.nameStyle?.toString() == "name" ? "name" : "room")   // this device's, for good
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
void componentFlash(Object cd, Object rateMs = null, Object limitSecs = null) { bridgeFor(cd)?.componentFlash(cd, rateMs, limitSecs) }
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
 ***** Pairing: its state, the start, the bridge's reports, the new certificate's trial, Cancel and Clear
********************************************************************/
boolean pairingBusy() {
    Map p = (Map) atomicState.pairing
    return p?.phase in BUSY_PHASES && !pairingStopped(p)
}

// A phase past its limit (PHASE_LIMIT_MS): whatever was to end it is gone, so the pairing is over
boolean pairingStopped(Map p) {
    Long limit = PHASE_LIMIT_MS[p?.phase?.toString()]
    return limit != null && (p.at == null || now() - (p.at as Long) >= limit)
}

// A Cancel less than CANCEL_COOLDOWN_MS ago: its attempt may still be finishing, so Pair waits
boolean coolingDown() {
    Object at = atomicState.cancelledAt
    return at != null && now() - (at as Long) < CANCEL_COOLDOWN_MS
}

// Creates the bridge device if needed (no Done required), then makes the key in its own execution
// (2-9 s on the hub) so the page answers at once.
void startPairing() {
    String busyMsg = "Pair: a pairing is under way; Cancel it first to start again"
    String claim = app.id.toString()
    // One start at a time (pairStarts), and one pairing at a time: a page shown before this one began (another tab)
    // can still offer Pair
    if (pairStarts.putIfAbsent(claim, true) != null) { logWarn busyMsg; return }
    try {
        if (pairingBusy()) { logWarn busyMsg; return }
        if (coolingDown()) { logWarn "Pair: the pairing just cancelled is still finishing; Pair again in a few seconds"; return }
        if (!settings.bridgeIp?.trim()) { logErr "Set the bridge IP address first"; return }
        if (ensureBridge() == null) { logErr "No bridge device: is the '${BRIDGE_DRIVER}' driver installed?"; return }
        atomicState.pairing = [phase: "making key", at: now()]
        String attempt = UUID.randomUUID().toString()
        atomicState.pairAttempt = attempt
        // the address as Pair was clicked: the field stays editable while the key is made
        runIn(1, "makeKeyAndPair", [data: [attempt: attempt, ip: settings.bridgeIp.trim()]])
        logInfo "Pairing: started (Pair) for ${settings.bridgeIp.trim()}; making the key"
    } finally {
        pairStarts.remove(claim)
    }
}

// attempt: the pairing this key is for. Cancel can come while the key is being made (2-9 s): the run already going
// then finds the attempt gone, and neither keeps the key nor opens the pairing port.
void makeKeyAndPair(Map data = null) {
    Object b = bridgeDevice()
    if (b == null) { pairingFailed("no bridge device", data?.attempt?.toString()); return }
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
        pairingFailed("making the key or CSR: ${e.message}", data?.attempt?.toString())
        return
    }
    if (data?.attempt?.toString() != atomicState.pairAttempt?.toString()) {
        logInfo "Pairing: cancelled while the key was being made; the key is discarded"
        return
    }
    removePending()
    atomicState.pendingStore = putSecrets([(PENDING_KEY): keyPem], secretStore())
    atomicState.pendingPubKey = pubB64
    // Before the bridge starts: its "requesting certificate" report must come after this, never before
    atomicState.pairing = [phase: "press the bridge button", at: now()]
    String pairAt = data?.ip?.toString() ?: settings.bridgeIp.trim()
    seedCredIp()                                         // the kept pairing's address, before the field can lead it astray
    atomicState.pairIp = pairAt                          // the certificate from this pairing goes with this address
    String err = b.startPairing(pairAt, csrPem, CSR_COMMON_NAME, pairingCa(pairAt), data?.attempt?.toString())
    // Cancel can run between the attempt check above and the hand-off: the attempt governs the hand-off too
    if (data?.attempt != null && atomicState.pairAttempt?.toString() != data.attempt.toString()) {
        if (!err) b.cancelPairing(data.attempt.toString())   // only this attempt's session (a newer one's is left alone)
        // the shared pending key and status belong to a newer attempt if one has started since: left to it
        if (atomicState.pairAttempt == null) {
            removePending()
            atomicState.pairing = [phase: "failed", detail: "cancelled", at: now()]
        }
        logInfo "Pairing: cancelled while it was being handed to the bridge"
        return
    }
    if (err) { pairingFailed(err, data?.attempt?.toString()); return }
    logInfo "Pairing: press the small black button on the back of the bridge"
}

// The kept pairing's address, for an install from before it was kept: the address the system was last verified at,
// else the bridge device's address while its link is verified (networkStatus online: set at the identity check; the
// device shows an address before connecting, which may be one that failed), else the address last saved. Never the
// field, which may be mid-edit.
void seedCredIp() {
    if (credRecord().ip || !credentialsPresent()) return
    Object b = bridgeDevice()
    String live = (b?.currentValue("networkStatus")?.toString() == "online") ? b.currentValue("ipAddress")?.toString() : null
    String at = atomicState.verifiedIp?.toString() ?: live ?: atomicState.lastBridgeIp?.toString()
    if (at) updateCred(ip: at)
}

// The CA the bridge device checks the system against while pairing: the LAP CA for a Smart Bridge (and for a system
// found only by the listener, or typed in), the Lutron root for any processor (RA3; HomeWorks QSX and Athena, as in
// pylutron-caseta). The bridge device tries the other one once if the check fails.
String pairingCa(String ip) {
    Map m = ((List) atomicState.systems ?: []).find { it.ip == ip }
    String t = m?.systype?.toString()
    return (t && t != "SmartBridge") ? "lutron" : "lap"
}

// The bridge reports from its own execution, so Cancel can run at the same moment: the attempt is checked again after
// the write, and a Cancel meanwhile keeps its "cancelled" (the page would otherwise stay busy). forAttempt: the attempt
// the bridge's pairing session belongs to; a report for any other is ignored.
void pairingProgress(String phase, String forAttempt = null) {
    String attempt = atomicState.pairAttempt?.toString()
    if (attempt == null || (forAttempt != null && forAttempt != attempt) || !pairingBusy()) return
    atomicState.pairing = [phase: phase, at: now()]
    if (atomicState.pairAttempt?.toString() != attempt) {
        if (atomicState.pairAttempt == null) atomicState.pairing = [phase: "failed", detail: "cancelled", at: now()]
        return
    }
    logInfo "Pairing: ${phase}"
}

// The signed certificate and the bridge's root. Kept only if the certificate holds our public key and
// parses; returns whether it was kept (the bridge connects only then).
// caPem: the CA the LEAP connection is checked against when it isn't the root the bridge sent (RA3: the Lutron root)
// forAttempt: the attempt the bridge's pairing session belongs to; a certificate for any other is ignored before anything
// is read or changed (it may come late, after that attempt was cancelled and a new one started)
Boolean pairingResult(String certPem, String rootPem, String caPem = null, String forAttempt = null) {
    String attempt = atomicState.pairAttempt?.toString()     // the pairing this certificate is for (the pending key's)
    if (forAttempt != null && forAttempt != attempt) {
        logInfo "Pairing: a certificate for a pairing that has ended arrived; ignored"
        return false
    }
    String pendingStore = atomicState.pendingStore
    String keyPem = pendingStore ? getSecret(PENDING_KEY, pendingStore) : null
    String pubB64 = atomicState.pendingPubKey
    if (!keyPem || !pubB64 || attempt == null) {
        logWarn "A certificate arrived with no pairing in progress; ignored"
        return false
    }
    try {
        X509Certificate cert = parseCert(certPem)
        if (cert.getPublicKey().getEncoded().encodeBase64().toString() != pubB64) {
            failAttempt(attempt, "the certificate is not for the key made here")
            return false
        }
        String signedBy
        try { cert.verify(parseCert(rootPem).getPublicKey()); signedBy = "signed by the bridge's root" }
        catch (Exception e) { signedBy = "NOT signed by the root it came with (${e.message})" }
        logInfo "Certificate '${cert.getSubjectX500Principal().getName()}' expires ${cert.getNotAfter().format('yyyy-MM-dd')}, ${signedBy}"
    } catch (Exception e) {
        failAttempt(attempt, "the certificate doesn't parse: ${e.message}")
        return false
    }
    // Kept apart until the bridge's LEAP port takes them (credentialsWorked): a certificate that doesn't work there
    // mustn't replace a pairing that does
    storeCandidate(keyPem, certPem, caPem ?: rootPem)
    atomicState.candIp = atomicState.pairIp ?: settings.bridgeIp?.trim()   // where it paired: its trial goes there
    // Cancel can run while the certificate is checked and stored (it found no candidate yet, so it ended no trial): it
    // wins. Checked before the status is written (a newer attempt's status is never overwritten) and again after it (a
    // Cancel in between is seen; one after finds the candidate). The pending key, possibly a newer attempt's, is
    // touched only after both.
    if (pairingEnded(attempt)) return false
    atomicState.pairing = [phase: "checking the new certificate", at: now()]
    runIn(TRIAL_SECS, "trialExpired")
    if (pairingEnded(attempt)) return false
    removePending()
    // The bridge connects to this IP next: a later Done with the same IP leaves that link alone (the address it paired
    // at, not the field: a different address typed meanwhile is still moved to by the next Done)
    atomicState.lastBridgeIp = atomicState.candIp
    logInfo "Pairing: certificate received; checking it on the bridge's LEAP port before keeping it"
    return true
}

// A certificate of this attempt failed: the pairing fails, unless the attempt has ended meanwhile (the pending key and
// status may be a newer attempt's by then)
void failAttempt(String attempt, String why) {
    if (!pairingEnded(attempt)) pairingFailed(why, attempt)
}

// The certificate's attempt was cancelled while it was being kept: its candidate goes (claimed, as any settling of a
// trial), and its status says cancelled unless a newer attempt has started. True when it ended.
boolean pairingEnded(String attempt) {
    if (atomicState.pairAttempt?.toString() == attempt) return false
    unschedule("trialExpired")
    if (atomicState.candStore && claimTrial()) removeCandidate()
    if (atomicState.pairAttempt == null) atomicState.pairing = [phase: "failed", detail: "cancelled", at: now()]
    logInfo "Pairing: cancelled while the certificate was being kept; it is discarded"
    return true
}

// forAttempt: as for pairingProgress; a failure of another attempt (one that has ended) leaves the current one alone
void pairingFailed(String why, String forAttempt = null) {
    if (forAttempt != null && forAttempt != atomicState.pairAttempt?.toString()) {
        logDebug "Pairing: a failure of a pairing that has ended (${why}); ignored"
        return
    }
    // a certificate stored as the candidate before the failure (its trial never set) goes with it; claimed, as any
    // settling of a trial, so a trial already being settled is left to that
    if (atomicState.candStore && claimTrial()) { unschedule("trialExpired"); removeCandidate() }
    removePending()
    atomicState.pairing = [phase: "failed", detail: why, at: now()]
    logWarn "Pairing failed: ${why}"
}

// From the bridge: the new credentials connected and the bridge's identity checked out: they become the pairing
// Returns true only once they are kept: the bridge leaves its trial only then (false: none to keep, or the store failed)
Boolean credentialsWorked() {
    if (!claimTrial()) return false                      // the trial was settled meanwhile (expired, cancelled, failed)
    // Claimed: no one else can settle this trial now, so every way out of here settles it
    unschedule("trialExpired")
    Map c = readCandidate()
    if (c == null) {
        removeCandidate()
        String kept = credentialsPresent() ? "; the previous pairing is kept" : ""
        atomicState.pairing = [phase: "failed", detail: "the new certificate couldn't be read back${kept}".toString(), at: now()]
        logWarn "Pairing failed: the new certificate couldn't be read back${kept}"
        return false
    }
    String slot = candidateSlot()                        // the candidate's slot, fixed before anything changes
    try {
        useCandidate(c, slot)
    } catch (Exception e) {
        if (activeSlot() == slot && credentialCheck() == "intact") {
            // the switch was committed (one write) before the failure: the new pairing is the one in use
            logWarn "Pairing: the new certificate is in use; tidying up after it failed (${e.message?.take(80)})"
            atomicState.remove("candStore")
            atomicState.remove("candIp")
            atomicState.pairing = [phase: "paired", at: now()]
            tidyPairing()
            return true
        }
        // not switched: only the candidate's own slot is cleared (fixed above, never worked out again)
        CRED_SLOTS[slot].each { String k -> try { removeSecret(k) } catch (Exception ignored) { } }
        atomicState.remove("candStore")
        atomicState.remove("candIp")
        String kept = credentialsPresent() ? "; the previous pairing is kept" : ""
        atomicState.pairing = [phase: "failed", detail: "the new certificate couldn't be kept (${e.message?.take(80)})${kept}".toString(), at: now()]
        logWarn "Pairing failed: the new certificate couldn't be kept (${e.message?.take(80)})${kept}"
        return false
    }
    atomicState.pairing = [phase: "paired", at: now()]
    tidyPairing()
    logInfo "Paired; credentials kept in ${credRecord().store == 'settings' ? 'password settings' : 'state'}"
    return true
}

// A pairing that ended as paired leaves nothing of itself: its attempt, its address and the trial's id. (After a
// failure the attempt's id stays, so a late report of that attempt is still known for what it is.)
void tidyPairing() {
    ["pairAttempt", "pairIp", "candId"].each { atomicState.remove(it) }
}

// From the bridge: the new credentials didn't connect (why: the connect error, never a secret). The pairing before, if
// any, stays.
void candidateFailed(String why) {
    if (!atomicState.candStore || !claimTrial()) return
    unschedule("trialExpired")
    removeCandidate()
    String kept = credentialsPresent() ? "; the previous pairing is kept" : ""
    atomicState.pairing = [phase: "failed", detail: "the new certificate didn't work on the bridge's LEAP port (${why})${kept}".toString(), at: now()]
    logWarn "Pairing failed: the new certificate didn't work on the bridge's LEAP port (${why})${kept}"
}

// The trial's deadline: a candidate the bridge hasn't confirmed or rejected by now is dropped, and the bridge goes back
// to the pairing before (or stops, with none). A late answer from the bridge finds no candidate and is ignored.
void trialExpired() {
    if (!atomicState.candStore || !claimTrial()) return   // a promotion (or Cancel) under way has it
    removeCandidate()
    String kept = credentialsPresent() ? "; the previous pairing is kept" : ""
    String detail = "the new certificate didn't connect within ${(int) (TRIAL_SECS / 60)} minutes on the bridge's LEAP port${kept}"
    atomicState.pairing = [phase: "failed", detail: detail, at: now()]
    logWarn "Pairing failed: ${detail}"
    bridgeDevice()?.cancelTrial()
}

// Cancel covers the whole pairing: the key, the pairing port, and the new certificate's trial on the LEAP port
void cancelPairing() {
    // A page shown during a pairing still offers Cancel after it ended
    if (!pairingBusy()) { logDebug "Cancel: no pairing under way"; return }
    logInfo "Pairing: cancelled (Cancel pairing)"
    atomicState.cancelledAt = now()                      // Pair waits for this attempt's work to end (coolingDown)
    unschedule("makeKeyAndPair")
    atomicState.remove("pairAttempt")                    // a key generation already running finds it gone
    unschedule("trialExpired")
    // a trial being promoted right now is left to finish (it claimed the trial first)
    boolean onTrial = atomicState.candStore != null && claimTrial()
    if (onTrial || atomicState.candStore == null) removeCandidate()   // before the bridge reconnects: it asks for the credentials
    removePending()
    atomicState.pairing = [phase: "failed", detail: "cancelled", at: now()]
    bridgeDevice()?.cancelPairing()
    if (onTrial) bridgeDevice()?.cancelTrial()
}

// Not during a pairing (Cancel first; a page shown before it began can still offer Clear). Takes the same claim as
// startPairing, so a Clear and a Pair at the same moment can't interleave.
void clearPairing() {
    String claim = app.id.toString()
    String busyMsg = "Clear pairing: a pairing is under way; Cancel it first"
    if (pairStarts.putIfAbsent(claim, true) != null) { logWarn busyMsg; return }
    try {
        if (pairingBusy()) { logWarn busyMsg; return }
        // a key job past its time limit may still be running: its attempt ends here, so it opens nothing afterwards
        unschedule("makeKeyAndPair")
        atomicState.remove("pairAttempt")
        atomicState.remove("pairIp")
        bridgeDevice()?.disconnect()
        removeCredentials()
        removeCandidate()
        removePending()
        atomicState.remove("pairing")
        logInfo "Pairing cleared (the bridge still lists the integration until it is removed in the Lutron app)"
    } finally {
        pairStarts.remove(claim)
    }
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
    state.remove("logsOffAt")
    if (settings.logLevel == null) {
        app.updateSetting("logLevel", [value:"3", type:"enum"])
        levelInfo.level = 3
    }
    if (settings.logLevelTime == null) {
        app.updateSetting("logLevelTime", [value:"30", type:"enum"])
        levelInfo.time = 30
    }
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
    if (levelInfo.level <= 2) state.lastLogLevel = levelInfo.level
}

Map getLogLevelInfo() {
    Integer level = settings.logLevel != null ? settings.logLevel as Integer : 1
    Integer time = settings.logLevelTime != null ? settings.logLevelTime as Integer : 30
    return [level: level, time: time]
}

void logsOff() {
    state.remove("logsOffAt")
    logWarn "Debug and Trace logging disabled..."
    if (logLevelInfo.level >= 3) {
        Integer lastLvl = state.lastLogLevel != null ? state.lastLogLevel as Integer : 2
        app.updateSetting("logLevel", [value:lastLvl.toString(), type:"enum"])
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
