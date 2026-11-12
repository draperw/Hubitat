/*
 *  Lutron LEAP Bridge (User)
 *  The LEAP connection to one Lutron Caseta Smart Bridge or RadioRA 3 processor, for the Lutron LEAP Integration
 *  (User) app, which creates this device and owns the pairing credentials. It runs the pairing socket (port 8083)
 *  when the app asks, then holds the LEAP session on 8081 with the certificate the app hands over at each connect:
 *  keep-alive, reconnect, the request queue, discovery, resync, status and commands for the zone, Pico, keypad and
 *  occupancy devices, scenes, and raw LEAP tools for testing. The app parents those devices: their commands reach
 *  this device through the app, and this device asks the app for them (childByDni, lutronChildren) to send status.
 *  A processor (RA3; HomeWorks QSX and Athena untested) is told apart by /project's ProductType (isProcessor).
 *  Requires Hubitat 2.3.1 or later (raw socket client certificates); used with the app, which needs 2.5.0.
 *  Tested on 2.5.3.
 *
 *  Changelog:
 *  ## [1.0.1] - 2026-11-12
 *    - Pico buttons numbered as Hubitat's Lutron integrations (Telnet and LEAP): on 1, raise 2, favourite 3, lower 4,
 *      off 5; a 2-button or paddle Pico on 1, off 2. A Pico whose Double-tap window is unsaved waits 0.75 s for a second
 *      press, as its preference's default. Keypad buttons by Lutron's own numbers (raise / lower 18 / 19; tabletops 16,
 *      17, 24, 25), LEDs by their button's number. A button map saved with the old numbering is read again at the next
 *      connect; if that read fails the link reconnects instead of running on the old map, and the 10-minute check tries
 *      it again.
 *    - A connection ending while a button is down: a held button (or a Fast Pico or keypad button) is released at once,
 *      so a ramp started on held stops; a press not yet held is dropped (no held after the drop). Button timers carry
 *      their session.
 *    - Occupancy (RA3) is subscribed only while an occupancy device exists, and a failed subscription no longer drops
 *      the link: one Warn per connection, tried again every 10 minutes.
 *    - The bridge's identity (its serial number) is checked first at each connect: until then no command is sent (held
 *      ones wait) and no push is applied; another Lutron system while the app has devices stops the link, and an
 *      answer from the app other than ok or new (a serial it can't read) reconnects.
 *    - A resync that can't read the system's type, its identity, a first device list or the zone status reconnects
 *      instead of reporting connected; a reconnect whose /project read fails keeps the type last seen.
 *    - A refused button or LED subscription is subscribed again at the 10-minute check (one Warn).
 *    - Pairing: the retry with the other CA keeps its deadline; new credentials are on trial until they connect, and
 *      the app's Cancel or the trial's deadline ends the trial: the link goes back to the pairing before. So does a
 *      pairing cancelled or failed before a certificate; a late answer from a cancelled attempt, or an old socket's
 *      status while the pairing port is checked, is ignored.
 *    - A flash has a time limit (from the dimmer; 60 s if none), kept to the second: it then stops and the zone goes
 *      back to how it was, as it does after a step the bridge refuses or doesn't answer; the zone's status is read
 *      after (answered or not). During a flash the device shows on: the steps' reports don't reach it.
 *    - Every connect, pairing included, first checks that the address answers (an async request): a blocking connect to
 *      a host that drops packets stops socket data for every driver on the hub for ~30 s. A connect goes only to the
 *      address that answered, and one meant to replace the link (a new address) still does after a failed check.
 *    - Capability NetworkDevice: networkStatus (online once the link is up and the identity checked), ipAddress,
 *      lastUpdated.
 *    - After a driver code save the old socket's read timeout can still arrive; one that comes sooner than 25 s after
 *      the current link's last data (or during the address check) is ignored instead of failing the new link.
 *    - Caseta occupancy (untested): /occupancygroup read with the devices; a room's group with sensors becomes a motion
 *      device, fed by the /occupancygroup/status subscription. A failed read keeps the rooms as they were; the
 *      10-minute check reads the groups again, notices sensors moving between rooms, and after an upgrade reads the
 *      devices once if the bridge has rooms with sensors.
 *    - A Discover while one is reading joins it instead of breaking both. healthStatus is online once the resync
 *      completes (not at the first ping) and offline on Disconnect or a resync that keeps failing (a refused zone
 *      subscription included). A flash cut short by the connection ending goes back to how it was if the link is back
 *      within a minute, the zone got no command and the light is still where the flash left it.
 *    - Commands held for the link aren't sent to another Lutron system the app takes (Warn each). A device added before
 *      the bridge's identity is checked is read by the resync, after the check. The identity check tells the app the
 *      address it was made at.
 *    - A bridge with no Picos (/button answered with an empty body) is discovered. Held commands stop at the first
 *      write that fails (the rest Warned, not sent). A scene's pushed comes when the bridge accepts it, held or not.
 *    - A button press held for the link goes once the buttons are subscribed again, so its event comes; a held Double
 *      Tap keeps its second press. One Double Tap per button at a time, and none on a button whose subscription
 *      failed (Warn). A press during the resync waits for the button subscriptions; a Double Tap whose first press is
 *      refused, or gets no Release within 5 s, leaves nothing pending, and can't clear a later one's.
 *    - A keep-alive (or Ping) answered with an error drops and reconnects the link, as one not answered does.
 *    - A new pairing that reaches another Lutron system goes back to the pairing before instead of stopping the link.
 *    - Commands with the link down join the reconnect under way, so a long outage still reaches offline and the
 *      address search. Ping and Connect wait while a pairing waits for the button. A Flash restore cut off by a drop is
 *      sent after the reconnect, again after another drop, until it is answered.
 *    - Held commands and flash undos aren't sent to a processor whose project changed (a Designer transfer); held
 *      commands expire on time. A restore is never held: the next resync checks the level before sending it. After a
 *      detected transfer no command, press or flash goes until the devices are read again (across reconnects), and a
 *      re-read that fails reconnects; so also for a transfer found by the 10-minute check, and for a reconnect whose
 *      /project gets no answer, or for routing kept with no modified time; meanwhile the processor's events don't reach
 *      the devices and gestures under way are dropped; a re-read on a live link then reads the status again.
 *    - Disconnect and pairing forget a flash undo still due.
 *    - A Release after the hold time is a hold even when the hold timer runs late.
 *    - An address check that can't be started ends the pairing (the link before comes back) or retries the reconnect
 *      (offline after the first retry, as a check that fails).
 *    - A keypad button gets a released at a drop only if it is known to send Releases (a programmed one sends Press
 *      only; the history goes with a Designer transfer). A tap, then a press in the double-tap window that becomes a
 *      hold, or is cut short by a drop: the tap is pushed, with its own type. One outage's drop log entry counts repeated
 *      checks and keeps at most 8. A failing check of a new address doesn't mark a working link offline.
 *    - A link that stops for good (another system, no pairing) goes offline. Flash starts only once the zone's status is
 *      in on this connection. A certificate the app can't take settles its pairing as failed. A pairing session belongs to
 *      the app's attempt: that attempt's Cancel closes only its own.
 *    - A press after the double-tap window, before its timer ran, keeps the press before it. A new certificate stays
 *      only once the app has kept it. A failed status read after a live re-read reconnects (an LED subscribes again).
 *    - Socket data is decoded as UTF-8 by whole lines, so a non-ASCII name split across chunks comes through intact.
 *    - While a processor's devices are read again, no zone, LED or occupancy status reaches a device, an answer to a read
 *      included.
 *    - A flash stopped just after an on step: that step's late 100% report doesn't reach the device, which keeps its level
 *      (so On goes back to the level from before the flash), unless the zone was told to go to 100%. The app is told
 *      which pairing attempt a certificate, a progress report or a failure is for, a socket that couldn't open included.
 *    - A command that stops a flash reads the zone afterwards, so the device shows the real state even when the command
 *      leaves the level where the flash's last step put it (Off at an off step, Stop Level Change).
 *    - Caseta: the 10-minute check also notices a room renamed, a device moved to another room, and scenes added, renamed
 *      or deleted (a new scene can be pushed once it is noticed).
 *    - A second Discover while the devices are being read says only that.
 *    - A processor's project answer is logged by its length only, at Debug and Trace: its name is the owner's own
 *      text and can identify the home.
 *    - Debug logging turns itself off: a new bridge's 30 minutes start when it is created (before, only Save Preferences
 *      started them, so a new bridge stayed at Debug), and the 10-minute check ends a Debug whose timer was lost.
 *  ## [1.0.0] - 2026-11-10
 *    - First release.
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
 *  The LAP certificate, key and CA at the end of this file come from pylutron-caseta
 *  (github.com/gurumitts/pylutron-caseta, src/pylutron_caseta/assets.py, Apache-2.0), which uses them to
 *  open the bridge's pairing port. The pairing messages follow its pairing.py.
 *
 *  The logging functions in this file are adapted from Jeff Page's (@jtp10181) Hubitat drivers
 *  (github.com/jtp10181/Hubitat, Drivers/libraries/zwaveDriverLibrary.groovy, Apache-2.0).
 */
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import hubitat.helper.HexUtils
import java.util.concurrent.ConcurrentHashMap

@Field static final String VERSION = "1.0.1"
@Field static final String DRIVER  = "Lutron LEAP Bridge (User)"

@Field static final Integer PAIRING_PORT = 8083
@Field static final Integer LEAP_PORT = 8081
@Field static final Integer REPLY_SECS = 10            // a request with no answer by then has failed
@Field static final Integer BUTTON_SECS = 180          // time to press the bridge button (pylutron-caseta)
@Field static final Integer PROVISIONAL_TRIES = 3       // connects new credentials get before they're dropped
// The hub's read timeout fires after 30 s with no data: one sooner than this after a link's last data is an old socket's
@Field static final Long STALE_TIMEOUT_MS = 25_000L
// The hub drops a socket that receives nothing for 30 s ("receive error: Read timed out"): the LEAP
// session pings more often than that, and the pairing socket sets the connect option timeout past the button window.
@Field static final Integer KEEPALIVE_SECS = 20
// How often the live link checks the bridge's devices for changes (seconds)
@Field static final Integer CHANGE_CHECK_SECS = 600
// The reconnect probe's (and Probe Host's) request timeout (seconds)
@Field static final Integer PROBE_SECS = 5
// Probe errors meaning nothing at the address is listening (lower case, matched anywhere in the message)
@Field static final List<String> PROBE_DOWN = ["connect timed out", "no route to host", "unreachable", "connection refused"]
// The raw socket's line buffer (bufferSize, default 1024 bytes): a longer line kills the connection.
// The pairing reply is 2 KB; /virtualbutton is 17 KB on a small Caseta system.
@Field static final Integer SOCKET_BUFFER = 262144
@Field static final Integer RX_BUF_MAX = 256 * 1024    // characters of unfinished line kept before giving up
// Reconnect after 5, 15, 30, 60 s, then every 2 minutes
@Field static final List<Integer> RECONNECT_SECS = [5, 15, 30, 60, 120]
// A processor restarting (a Designer transfer) closes a live connection itself ("Stream closed") and is back within 1-2
// minutes: then retry every FAST_RETRY_SECS instead of backing off, for FAST_RETRY_WINDOW_SECS at most from the drop.
// Live: data within FAST_RETRY_RECENT_SECS of the close (a link the hub had stopped serving doesn't qualify).
@Field static final Integer FAST_RETRY_SECS = 15
@Field static final Integer FAST_RETRY_WINDOW_SECS = 180
@Field static final Integer FAST_RETRY_RECENT_SECS = 30
// After this many failed reconnects (about 2 minutes) the app looks for the bridge on the network, and again at every
// retry after that (the hub's mDNS list can lag behind a move)
@Field static final Integer LOOK_AFTER = 4
@Field static final String PING_URL = "/server/1/status/ping"
@Field static final List<String> CONNECTION_VALUES = ["disconnected", "connecting", "pairing", "resyncing", "connected"]
@Field static final List<String> DISCOVERY_READS = ["/project", "/area", "/device", "/zone", "/button", "/virtualbutton"]
// Read too, but the device list doesn't wait on it: occupancy groups (one per room; those with sensors become motion
// devices). A bridge that doesn't answer it gives its list without occupancy.
@Field static final String OCCUPANCY_GROUPS_URL = "/occupancygroup"
// /project ProductTypes of a processor (pylutron-caseta's PROCESSOR_PRODUCT_TYPES)
@Field static final List<String> PROCESSOR_TYPES = ["Lutron RadioRA 3 Project", "Lutron HWQS Project", "Lutron Athena Project"]
// A zone status within this long of our own command for that zone is "digital"
@Field static final Long DIGITAL_MS = 3000L
// Logging a whole 33 KB line took ~8 s on the hub and held up this single-threaded driver: long answers are logged
// in parts of this many characters, and only when asked for (Read URL)
@Field static final Integer LOG_PART = 2000

// Socket session in progress, keyed by device id. kind pair|leap; phase hello|up; pending: [tag: request];
// queue: reads and subscriptions waiting; reading: the tag of the read in flight; subs: [tag: url] for Subscribe URL.
@Field static ConcurrentHashMap<Long, Map> sessions = new ConcurrentHashMap<>()
// Received text not yet ending in a newline, keyed by device id
@Field static ConcurrentHashMap<Long, String> rxBuf = new ConcurrentHashMap<>()
// Reconnect bookkeeping, keyed by device id: [attempt: n (0 = none yet), fastUntil: ms (fast retries end)]. Whether the link should be up is
// state.linkWanted: it has to survive a driver code save, which clears these maps.
@Field static ConcurrentHashMap<Long, Map> links = new ConcurrentHashMap<>()
// Commands held while the link is down or coming up, keyed by device id: [[url:, body:, desc:, ctx:, type:, at:]], sent
// when it is up (HOLD_SECS at most). In memory: a press after a code save is held, one before it went with the session.
@Field static ConcurrentHashMap<Long, List> heldCommands = new ConcurrentHashMap<>()
@Field static final Integer HOLD_SECS = 10
@Field static final Integer AGAIN_MS = 5_000             // a Double Tap's second press waits this long for the first's Release
// When we last sent a command to each zone, keyed by device id: [zoneId (String): ms]
@Field static ConcurrentHashMap<Long, Map> zoneCommands = new ConcurrentHashMap<>()
// Pico button timing, keyed by device id: [buttonId (String): [seq:, down:, held:, second:, tapUntil:]]
@Field static ConcurrentHashMap<Long, Map> buttonStates = new ConcurrentHashMap<>()
// Zones flashing, keyed by device id: [zoneId (String): [run:, rate:, next: 0|100, seq:, sentAt:, name:, limit:, until:,
// restore:]]. run: this flash's id; its timers and step answers carry it, so one left over from a flash that was
// stopped can't drive the next.
// Cleared with the session: a driver code save or a lost connection ends every flash.
@Field static ConcurrentHashMap<Long, Map> flashes = new ConcurrentHashMap<>()
@Field static final Integer FLASH_MIN_MS = 500
@Field static final Integer FLASH_MAX_MS = 60000
@Field static final Integer FLASH_RESUME_SECS = 60           // a flash cut short by a drop is undone if the link is back by then
@Field static final Integer FLASH_LIMIT_SECS = 60            // a flash ends after this long unless the dimmer says otherwise
@Field static final Integer FLASH_LIMIT_MAX_SECS = 3600
// A flash's on step reports 100% 50-110 ms after it is sent: a flash stopped in that gap gets the report after it ended,
// which would give the device 100% for a level it never chose. Zones whose last step (to 100) hadn't reported when the
// flash stopped, keyed by device id: [zoneId (String): until ms]; the first 100% report by then is the step's, dropped.
@Field static ConcurrentHashMap<Long, Map> flashTails = new ConcurrentHashMap<>()
@Field static final Long FLASH_TAIL_MS = 2000L
// The bridge reports our own press's Press 60-110 ms after the request
@Field static final Long PRESS_DIGITAL_MS = 2000L
@Field static final Integer HOLD_MS = 1000             // quick presses take up to ~440 ms, holds ~3 s
@Field static final Integer TAP_MS = 750               // the Pico's Double-tap window while its preference is unsaved
// A Pico on this driver gets pushed at Press and released at Release, nothing timed
@Field static final String FAST_PICO = "Lutron LEAP Fast Pico (User)"

metadata {
    definition(name: DRIVER, namespace: "draperw", author: "Bill Draper", singleThreaded: true,
               importUrl: "https://raw.githubusercontent.com/draperw/Hubitat/main/drivers/Lutron-LEAP/Lutron%20LEAP%20Bridge%20(User).groovy") {
        capability "Actuator"
        capability "Initialize"
        capability "NetworkDevice"           // networkStatus (the link up, the bridge's identity checked), ipAddress, lastUpdated
        capability "PushableButton"          // scenes: push(n) presses /virtualbutton/<n>

        attribute "connection", "enum", CONNECTION_VALUES
        attribute "healthStatus", "enum", ["unknown", "online", "offline"]
        attribute "leapVersion", "string"

        command "ping"
        command "disconnect"
        command "discover"
        // Push (PushableButton) relabelled: it presses a Caseta scene by its number (/virtualbutton/<n>)
        command "push", [[name: "Scene number*", type: "NUMBER", description: "Caseta only"]]
        command "setZoneLevel", [[name: "Zone ID*", type: "NUMBER", description: "The number in the zone href (/zone/3 -> 3)"],
                                 [name: "Level*", type: "NUMBER", description: "0-100"],
                                 [name: "Fade seconds", type: "NUMBER", description: "Empty: GoToLevel; a number: GoToDimmedLevel"]]
        command "readUrl", [[name: "URL*", type: "STRING", description: "LEAP URL, e.g. /device, /zone/3/status, /server"]]
        command "subscribeUrl", [[name: "URL*", type: "STRING", description: "e.g. /button/101/status/event"]]
        command "probeHost", [[name: "IP address*", type: "STRING"], [name: "Port", type: "NUMBER", description: "Default 8081"]]
    }
    preferences {
        input name: "logLevel", type: "enum", title: fmtTitle("Logging Level"),
            description: fmtDesc("Logs selected level and above"), defaultValue: 3, options: LOG_LEVELS
        input name: "logLevelTime", type: "enum", title: fmtTitle("Logging Level Time"),
            description: fmtDesc("Time to enable Debug/Trace logging"), defaultValue: 30, options: LOG_TIMES
    }
}

/*******************************************************************
 ***** Lifecycle
********************************************************************/
void installed() {
    logWarn "installed..."
    checkLogLevel()                                      // the default level is Debug: its 30 minutes start here
    state.driverVersion = VERSION
    sendEventLog(name: "connection", value: "disconnected")
    sendEventLog(name: "healthStatus", value: "unknown")
    sendEventLog(name: "numberOfButtons", value: 0)
    setNetworkStatus("offline")
}

// NetworkDevice: networkStatus online once the link is up and the bridge's identity checked, offline when the session
// ends; lastUpdated is when it last changed
void setNetworkStatus(String v) {
    if (device.currentValue("networkStatus")?.toString() == v) return
    sendEventLog(name: "networkStatus", value: v)
    sendEventLog(name: "lastUpdated", value: new Date(now()).format("yyyy-MM-dd HH:mm:ss", location.timeZone))
}

void updated() {
    logDebug "updated..."
    checkDriverVersion()
    checkLogLevel()
}

// Hub start (capability Initialize) and driver code saves: connect if the app has credentials.
void initialize() {
    checkDriverVersion()
    checkLogsOff()
    logInfo "Initialize: connecting"
    connect()
}

// Code updates: users don't press anything after pasting a new version, so the first command or job after one logs
// it here; migrations from one version to the next would go here too (none since 1.0.0).
void checkDriverVersion() {
    String old = state.driverVersion
    if (old == VERSION) return
    state.driverVersion = VERSION
    if (old == null && device.currentValue("connection") == null) return      // brand new; installed() sets up
    logWarn "Driver updated (${old} -> ${VERSION})"
}

/*******************************************************************
 ***** Pairing (pylutron-caseta pairing.py flow), and connecting: called by the app
********************************************************************/
// 1. (startPairing) Connected to 8083 with the LAP client certificate; waiting for the button press
//    (a status message whose Permissions include PhysicalAccess).
// 2. Send the CSR to /pair; the reply carries the signed certificate and the bridge's root certificate.
// 3. Hand both to the app, which checks and keeps them; then connect on 8081.

// Starts pairing: connect to the pairing port with the LAP certificate and wait for the bridge button.
// ca: the CA the pairing certificate is checked against: "lap" (Caseta) or "lutron" (the Lutron root, for a processor,
// as in pylutron-caseta); a certificate failure tries the other one once (pairRetry). Returns null when started, or
// why it couldn't start; reports what happens next to the app from this device's own executions
// (parent.pairingFailed / parent.pairingResult), never from inside this call.
// appAttempt: the app's pairing attempt, kept on the session so that attempt's Cancel closes only its own session
String startPairing(String ip, String csrPem, String displayName, String ca = "lap", String appAttempt = null) {
    checkDriverVersion()
    if (!ip?.trim()) return "no bridge IP address"
    if (!csrPem?.contains("BEGIN CERTIFICATE REQUEST")) return "no CSR"
    stopLink("pairing")
    Map s = [kind: "pair", phase: "probe", csr: csrPem, displayName: displayName ?: "hubitat_leap", ip: ip.trim(),
             ca: (ca == "lutron") ? "lutron" : "lap", retried: false, attempt: UUID.randomUUID().toString(), appAttempt: appAttempt]
    // The pairing port is probed first, like every connect (a blocking connect to a dead address stalls the hub's
    // sockets); the session holds the request meanwhile, so Cancel works
    sessions[device.idAsLong] = s
    sendEventLog(name: "connection", value: "pairing")
    logDebug "Pairing: checking that ${s.ip}:${PAIRING_PORT} answers"
    try {
        asynchttpGet("pairProbe", [uri: "https://${s.ip}:${PAIRING_PORT}/".toString(), timeout: PROBE_SECS, ignoreSSLIssues: true],
                     [ip: s.ip, at: now(), attempt: s.attempt])
    } catch (Exception e) {
        // no callback will come: the pairing ends here (the app is told by the return), and the link before comes back
        sessions.remove(device.idAsLong)
        sendEventLog(name: "connection", value: "disconnected")
        runIn(1, "resumeAfterPairing")                   // in this device's own execution, not inside the app's call
        return "couldn't check the pairing port (${e.message?.take(80)})".toString()
    }
    return null
}

void pairProbe(Object resp, Map data) {
    Map s = sessions[device.idAsLong]
    // cancelled, or another pairing meanwhile: a late answer belongs to its own attempt only
    if (s?.kind != "pair" || s.phase != "probe" || s.attempt?.toString() != data?.attempt?.toString()) return
    String down = probeDown(resp)
    if (down != null) { pairingFailed("the bridge doesn't answer at ${s.ip}:${PAIRING_PORT} (${down})", s.appAttempt); return }
    s.phase = "button"
    String err = openPairing(s)
    if (err && certFailure(err)) err = pairRetry(s, err) ? null : err
    // a socket that couldn't open has already taken the session with it: its attempt comes from s
    if (err) { pairingFailed(err, s.appAttempt); return }
    logInfo "Press the small black button on the back of the bridge within ${BUTTON_SECS} s"
    runIn(BUTTON_SECS, "pairTimeout")
}

// Opens the pairing socket with s.ca; returns null, or the connect error
String openPairing(Map s) {
    Map opts = [secureSocket: true, certificate: LAP_CERT_PEM, privateKey: LAP_KEY_PEM, eol: "\n",
                ignoreSSLIssues: false, caCertificate: caPem(s.ca),
                timeout: (BUTTON_SECS + 30) * 1000]           // silent until the button: past the hub's 30 s default
    logInfo "Pairing: connecting to ${s.ip}:${PAIRING_PORT} (checking against the ${caName(s.ca)})..."
    return openSession(s, s.ip.toString(), PAIRING_PORT, opts)
}

String caPem(Object ca) { (ca == "lutron") ? LUTRON_ROOT_CA_PEM : LAP_CA_PEM }

String caName(Object ca) { (ca == "lutron") ? "Lutron root CA" : "LAP CA" }

// The hub's words for a TLS certificate check that failed (Java's PKIX / handshake messages). Untested: every system
// so far has paired with the first CA tried.
boolean certFailure(String msg) { (msg ?: "") =~ /(?i)PKIX|certification path|certificate|SSLHandshake|handshake_failure|bad_certificate/ }

// A certificate failure during pairing: once, try the other CA. Returns true when the retry is under way.
boolean pairRetry(Map s, String why) {
    if (s.retried) return false
    String other = (s.ca == "lutron") ? "lap" : "lutron"
    logInfo "Pairing: the bridge's certificate didn't check out against the ${caName(s.ca)} (${why.take(120)}); trying the ${caName(other)}"
    Map n = [kind: "pair", phase: "button", csr: s.csr, displayName: s.displayName, ip: s.ip, ca: other, retried: true,
             attempt: s.attempt, appAttempt: s.appAttempt]
    String err = openPairing(n)
    if (err) { logWarn "Pairing: ${err}"; return false }
    runIn(BUTTON_SECS, "pairTimeout")                    // openSession cancelled the first socket's deadline
    return true
}

// The pairing socket's lines: the button press (step 1), then the certificate reply (steps 2 and 3)
void pairLine(Map s, Map j, String line) {
    if (j == null) { logDebug "Pairing: ignoring non-JSON line"; return }
    if (s.phase == "button") {
        List perms = (j.Body?.Status?.Permissions instanceof List) ? (List) j.Body.Status.Permissions : []
        if (j.Header?.ContentType?.toString()?.startsWith("status;") && "PhysicalAccess" in perms) {
            logInfo "Pairing: button pressed; requesting a certificate"
            s.phase = "cert"
            sendJson([Header: [RequestType: "Execute", Url: "/pair", ClientTag: "get-cert"],
                      Body: [CommandType: "CSR",
                             Parameters: [CSR: s.csr, DisplayName: s.displayName, DeviceUID: "000000000000", Role: "Admin"]]])
            runIn(REPLY_SECS, "pairTimeout")
            parent?.pairingProgress("requesting certificate", s.appAttempt?.toString())
        } else {
            logDebug "Pairing: waiting for the button (${leapSummary(j)}, permissions ${perms})"
        }
        return
    }
    if (s.phase == "cert") {
        if (j.Header?.ClientTag != "get-cert") { logDebug "Pairing: ignoring ${leapSummary(j)}"; return }
        String status = j.Header?.StatusCode?.toString()
        Map result = (j.Body?.SigningResult instanceof Map) ? (Map) j.Body.SigningResult : null
        if ((status && !status.startsWith("2")) || !result?.Certificate || !result?.RootCertificate) {
            pairingFailed("no certificate in the reply: ${leapSummary(j)}", s.appAttempt)
            return
        }
        endSession()
        sendEventLog(name: "connection", value: "disconnected")
        Boolean kept = false
        try {
            // On a processor the LEAP connection is checked against the Lutron root (pylutron-caseta keeps it as
            // the CA)
            kept = parent?.pairingResult(result.Certificate.toString(), result.RootCertificate.toString(),
                                         s.ca == "lutron" ? LUTRON_ROOT_CA_PEM : null, s.appAttempt?.toString())
        } catch (Exception e) {
            logErr "The app couldn't take the certificate: ${e.message}"
            // the app's pairing would otherwise stay busy (its trial timeout was never set): settle it as failed
            try { parent?.pairingFailed("the app couldn't take the certificate (${e.message?.take(80)})".toString(), s.appAttempt?.toString()) } catch (Exception ignored) { }
        }
        if (kept) {
            logInfo "Pairing: certificate handed to the app; connecting on ${LEAP_PORT}"
            linkOf().attempt = 0
            runIn(2, "connect")
        } else {
            logWarn "Pairing: the app didn't keep the certificate (see the app's log)"
            resumeAfterPairing()
        }
    }
}

void pairTimeout() {
    Map s = sessions[device.idAsLong]
    if (s?.kind != "pair") return
    pairingFailed(s.phase == "button" ? "no button press in ${BUTTON_SECS} s".toString() : "no certificate in ${REPLY_SECS} s".toString(), s.appAttempt)
}

// attempt: the app's attempt the pairing belonged to, passed by every caller (the session may already be gone: a socket
// that couldn't open, a retry that failed); the open session's when none is given
void pairingFailed(String why, Object attempt = null) {
    logWarn "Pairing failed: ${why}"
    Object appAttempt = attempt ?: sessions[device.idAsLong]?.appAttempt
    endSession()
    sendEventLog(name: "connection", value: "disconnected")
    try { parent?.pairingFailed(why, appAttempt?.toString()) } catch (Exception e) { logErr "Couldn't tell the app: ${e.message}" }
    resumeAfterPairing()
}

// appAttempt: only the session of that app attempt (a newer attempt's session is left alone); none: any pairing session
void cancelPairing(String appAttempt = null) {
    Map ps = sessions[device.idAsLong]
    if (ps?.kind != "pair") return
    if (appAttempt != null && ps.appAttempt?.toString() != appAttempt) return
    endSession()
    sendEventLog(name: "connection", value: "disconnected")
    logInfo "Pairing cancelled"
    resumeAfterPairing()
}

// Pairing stopped the link; a pairing that ends with no new certificate (cancelled, failed, the certificate refused)
// brings back the one the app already has, if any
void resumeAfterPairing() {
    boolean paired = false
    try { paired = parent?.credentialsPresent() == true } catch (Exception e) { logDebug "Couldn't ask the app for its pairing: ${e.message}" }
    if (!paired) return
    logInfo "Pairing ended without a new certificate: connecting with the pairing kept"
    linkOf().attempt = 0
    runIn(1, "connect")
}

// From the app: the new credentials' trial ended (cancelled or out of time). Any link using them closes, and the next
// connect asks the app again, which gives the pairing before, or none.
void cancelTrial() {
    if (!state.provisional) return
    state.remove("provisional")
    state.remove("provisionalFails")
    logInfo "Pairing trial ended: connecting with the previous pairing, if any"
    stopLink("pairing trial ended")
    sendEventLog(name: "connection", value: "disconnected")
    runIn(1, "connect")
}

// Connect a second from now, in this device's own execution (the app calls this; the connect then asks
// the app for the credentials without the app being mid-call).
void connectSoon() {
    runIn(1, "connect")
}

/*******************************************************************
 ***** Commands on the bridge device: scenes, raw LEAP tools, diagnostics
********************************************************************/
// Pings the bridge on the open session (connecting first if needed) and logs the round trip.
void ping() {
    checkDriverVersion()
    Map s = sessions[device.idAsLong]
    if (s?.kind == "pair") { logInfo "Ping: pairing under way; the link connects once it ends"; return }
    if (s?.kind != "leap" || s.phase != "up") {
        logInfo "Ping: not connected; connecting"
        connect()
        return
    }
    sendPing(s, "ping")
}

// Closes the session and stays disconnected until Initialize, a Ping, a command or the app connects again.
void disconnect() {
    checkDriverVersion()
    stopLink("Disconnect")
    sendEventLog(name: "connection", value: "disconnected")
    setHealth("offline")
    logInfo "Disconnected (no reconnect until Initialize, Ping, a command or the app connects)"
}

// Reads the bridge's devices again and hands the list to the app.
void discover() {
    checkDriverVersion()
    if (!ready("Discover")) return
    Map s = sessions[device.idAsLong]
    // one read at a time: a second Discover says only that (queue...Discovery)
    if ((isProcessor() ? s.pd : s.disc) == null) logInfo "Discover: reading the ${isProcessor() ? 'processor' : 'bridge'}'s devices"
    if (isProcessor()) queueProcessorDiscovery(s) else queueDiscovery(s)
    pump(s)
}

// PushableButton: presses scene n (/virtualbutton/n). Only programmed scenes.
void push(Object n) {
    checkDriverVersion()
    Integer id = intOrNull(n)
    String name = (id != null) ? state.scenes?.get(id.toString()) : null
    if (name == null) {
        logWarn "push(${n}): ${n} is not a programmed scene (${sceneList()})"
        return
    }
    pressScene(id, name)                                 // pushed comes with the bridge's acceptance (answer)
}

String sceneList() {
    Map sc = state.scenes ?: [:]
    return sc ? "scenes: " + sc.collect { k, v -> "${k} ${v}" }.join(", ") : "no scenes read yet: Discover"
}

boolean pressScene(Integer id, String name) {
    return command("/virtualbutton/${id}/commandprocessor".toString(), [Command: [CommandType: "PressAndRelease"]],
                   "Scene ${id} (${name})".toString(), [scene: id, name: name])
}

// Raw zone command for testing: GoToLevel, or GoToDimmedLevel when a fade is given, whatever
// the zone's type (the bridge's own answer shows, at Info).
void setZoneLevel(Object zoneId, Object level, Object fadeSecs = null) {
    checkDriverVersion()
    Integer z = intOrNull(zoneId)
    Integer lvl = intOrNull(level)
    if (z == null || lvl == null || lvl < 0 || lvl > 100) { logWarn "Set Zone Level: zone ${zoneId}, level ${level}?"; return }
    BigDecimal fade = decOrNull(fadeSecs)
    Map cmd = (fade == null) ? goToLevel(lvl) :
              [CommandType: "GoToDimmedLevel", DimmedLevelParameters: [Level: lvl, FadeTime: leapDuration(fade)]]
    Integer at = stopFlash(z, "Set Zone Level")
    zoneCommand(z, cmd, true, readAfter(z, at, lvl))
}

// Probe Host (diagnostic): an async HTTPS request to the address, with the answer and its time logged at Info. Shows
// what a live and a dead host answer, without the risk of a blocking rawSocket.connect, which stalls every raw socket
// on the hub while it waits on a host that drops packets.
void probeHost(String ip, Object port = null) {
    String h = ip?.trim()
    if (!h) { logWarn "Probe Host: an IP address is needed"; return }
    Integer p = intOrNull(port) ?: LEAP_PORT
    logInfo "Probe Host: ${h}:${p}..."
    asynchttpGet("probeAnswer", [uri: "https://${h}:${p}/".toString(), timeout: PROBE_SECS, ignoreSSLIssues: true],
                 [ip: h, port: p, at: now()])
}

void probeAnswer(Object resp, Map data) {
    long ms = now() - ((data?.at ?: now()) as Long)
    String err = null
    Object st = null
    try { st = resp?.status } catch (Exception e) { st = "?" }
    try { if (resp?.hasError()) err = resp.getErrorMessage() } catch (Exception e) { err = "(${e.message})" }
    logInfo "Probe Host ${data?.ip}:${data?.port}: status ${st}, ${err ? "error ${err}" : 'no error'}, after ${ms} ms"
}

void readUrl(String url) {
    checkDriverVersion()
    if (!url?.trim()?.startsWith("/")) { logWarn "Read URL: a LEAP URL starts with '/' (got '${url}')"; return }
    queueRead("ReadRequest", url.trim(), "rawAnswer", [raw: true])
}

void subscribeUrl(String url) {
    checkDriverVersion()
    if (!url?.trim()?.startsWith("/")) { logWarn "Subscribe URL: a LEAP URL starts with '/' (got '${url}')"; return }
    queueRead("SubscribeRequest", url.trim(), "rawSubscribed", [raw: true])
}

// Raw tools: whole answers and events at Info, in parts (asked for, so worth the cost)
void rawAnswer(Map s, Map j, Map p, String line) {
    if (line != null) logLong("Read ${p.url}: ${j?.Header?.StatusCode ?: 'failed'}", line)
}

void rawSubscribed(Map s, Map j, Map p, String line) {
    if (j == null) return
    s.subs[p.tag] = p.url
    logInfo "Subscribed to ${p.url}: its events are logged at Info until the connection ends"
}

void logLong(String title, String text) {
    if (text.length() <= LOG_PART) { logInfo "${title}: ${text}"; return }
    int parts = (int) Math.ceil(text.length() / (double) LOG_PART)
    for (int i = 0; i < parts; i++) {
        logInfo "${title} (part ${i + 1}/${parts}): ${text.substring(i * LOG_PART, Math.min(text.length(), (i + 1) * LOG_PART))}"
    }
}

/*******************************************************************
 ***** The app's devices: the app parents them; the bridge asks it for them
********************************************************************/
// The app's device for a DNI, or null. The bridge's DNI is the app's id, so the DNIs are unchanged.
Object childOf(String dni) {
    try {
        return parent?.childByDni(dni)
    } catch (Exception e) {
        logDebug "Couldn't get ${dni} from the app: ${e.message}"
        return null
    }
}

// Every zone and Pico device the app holds
List childList() {
    try {
        return (List) (parent?.lutronChildren() ?: [])
    } catch (Exception e) {
        logDebug "Couldn't get the devices from the app: ${e.message}"
        return []
    }
}

// The app created these devices (DNIs): subscribe the new Picos' buttons and read zone status now when the
// link is up; otherwise the next connect's resync does both.
void childrenAdded(List dnis) {
    checkDriverVersion()
    String prefix = "${device.deviceNetworkId}/"
    List<String> all = (dnis ?: []).collect { it.toString() }
    List<Integer> picoIds = all.findAll { it.startsWith(prefix + "d/") }.collect { intOrNull(it.substring(prefix.length() + 2)) }.findAll { it != null }
    boolean zones = all.any { it.startsWith(prefix + "z/") }
    List<Integer> btns = ((Map) state.buttons ?: [:]).findAll { k, v -> intOrNull(v.d) in picoIds }.collect { k, v -> k as Integer }.sort()
    Map s = sessions[device.idAsLong]
    if (!(s?.kind == "leap" && s.phase == "up")) {
        logDebug "Added devices: their buttons and status are read at the next connect"
        return
    }
    // Up but the bridge's identity not yet checked: the resync, after the check, subscribes and reads every device's
    if (!s.verified) {
        logDebug "Added devices: their buttons and status are read by the resync under way, once the bridge's identity is checked"
        return
    }
    newSubs(s, "subButtons", btns).each { enqueue(s, subItem(s, "button", it)) }
    List<Integer> ledIds = ((Map) state.leds ?: [:]).findAll { k, v -> intOrNull(v.d) in picoIds }.collect { k, v -> k as Integer }.sort()
    newSubs(s, "subLeds", ledIds).each { enqueue(s, subItem(s, "led", it)) }
    if (zones) enqueue(s, [type: "ReadRequest", url: "/zone/status", then: "zoneStatusAnswer"])
    if (all.any { it.startsWith(prefix + "a/") }) {
        if (s.areaSub) enqueue(s, [type: "ReadRequest", url: occupancyUrl(), then: "areaStatusAnswer"])
        else queueAreaSub(s)                                     // the first occupancy device: its answer has every area
    }
    pump(s)
}

void childrenHealth(String value) {
    childList().each { ch ->
        ch.parse([[name: "healthStatus", value: value, descriptionText: "${ch.displayName} healthStatus is ${value}".toString()]])
    }
}

/*******************************************************************
 ***** Zones: <bridge DNI>/z/<zoneId>; commands up, status down
********************************************************************/
// Any command to a zone stops its flash first
void componentOn(Object cd)  { Integer z = zoneOf(cd); if (z != null) { Integer at = stopFlash(z, "On"); zoneLevel(z, 100, null, readAfter(z, at, 100)) } }

void componentOff(Object cd) { Integer z = zoneOf(cd); if (z != null) { Integer at = stopFlash(z, "Off"); zoneLevel(z, 0, null, readAfter(z, at, 0)) } }

// While a zone flashes its device shows on and the steps' reports don't reach it, so the device learns its state from
// the report of the command that stops the flash. A command that leaves the zone where the flash left it (Off at an off
// step, Stop; target null: Stop or Raise/Lower, whose level isn't known) may bring no report: the zone is read once the
// command is answered (a command that changes the level reports it, and a read sooner could catch a fade midway).
// at: stopFlash's answer, the level the flash left the zone at (null: no flash was running).
Map readAfter(Integer z, Integer at, Integer target) {
    if (at == null) return null
    if (target != null && zoneControl(z) == "Switched") target = target > 0 ? 100 : 0
    return (target == null || target == at) ? [readAfter: z] : null
}

// As readAfter, for a level change asked of a switch: nothing is sent, so the zone is read at once
void readNow(Integer z, Integer at) {
    if (at != null) queueRead("ReadRequest", "/zone/${z}/status".toString(), "zoneStatusAnswer")
}

void componentSetLevel(Object cd, Object level, Object secs = null) {
    Integer z = zoneOf(cd)
    Integer lvl = intOrNull(level)
    if (z == null) return
    if (lvl == null) { logWarn "${cd.displayName}: set level ${level}?"; return }
    Integer at = stopFlash(z, "Set Level")
    zoneLevel(z, lvl, secs, readAfter(z, at, Math.max(0, Math.min(100, lvl))))
}

void componentStartLevelChange(Object cd, Object direction) {
    Integer z = zoneOf(cd)
    if (z == null) return
    Integer at = stopFlash(z, "Start Level Change")
    if (zoneControl(z) == "Switched") {
        logDebug "${cd.displayName}: a switch has no level change"
        readNow(z, at)
        return
    }
    zoneCommand(z, [CommandType: direction?.toString() == "up" ? "Raise" : "Lower"], false, readAfter(z, at, null))
}

void componentStopLevelChange(Object cd) {
    Integer z = zoneOf(cd)
    if (z == null) return
    Integer at = stopFlash(z, "Stop Level Change")
    if (zoneControl(z) == "Switched") { readNow(z, at); return }
    zoneCommand(z, [CommandType: "Stop"], false, readAfter(z, at, null))
}

void componentRefresh(Object cd) {
    String dni = cd?.deviceNetworkId?.toString() ?: ""
    if (dni.startsWith("${device.deviceNetworkId}/a/")) { queueRead("ReadRequest", occupancyUrl(), "areaStatusAnswer"); return }   // occupancy
    if (dni.startsWith("${device.deviceNetworkId}/d/")) { logDebug "${cd.displayName}: nothing to refresh"; return }            // keypad, Pico
    Integer z = zoneOf(cd)
    if (z == null) return
    queueRead("ReadRequest", "/zone/${z}/status".toString(), "zoneStatusAnswer")
}

Integer zoneOf(Object cd) {
    String dni = cd?.deviceNetworkId?.toString()
    String prefix = "${device.deviceNetworkId}/z/"
    Integer z = (dni?.startsWith(prefix)) ? intOrNull(dni.substring(prefix.length())) : null
    if (z == null) logWarn "${cd?.displayName} (${dni}): not one of this bridge's zone children; command ignored"
    return z
}

String zoneControl(Integer z) { state.zones?.get(z.toString())?.toString() }

// Switches get GoToLevel 0 or 100 (the bridge refuses GoToDimmedLevel on them); dimmers get GoToDimmedLevel
// when a fade above 0 is asked for (fractions kept), else GoToLevel.
void zoneLevel(Integer z, Integer level, Object secs, Map ctx = null) {
    int lvl = Math.max(0, Math.min(100, level))
    if (zoneControl(z) == "Switched") { zoneCommand(z, goToLevel(lvl > 0 ? 100 : 0), false, ctx); return }
    BigDecimal fade = decOrNull(secs)
    if (fade != null && fade > 0) {
        zoneCommand(z, [CommandType: "GoToDimmedLevel", DimmedLevelParameters: [Level: lvl, FadeTime: leapDuration(fade)]], false, ctx)
    } else {
        zoneCommand(z, goToLevel(lvl), false, ctx)
    }
}

Map goToLevel(int lvl) { [CommandType: "GoToLevel", Parameter: [[Type: "Level", Value: lvl]]] }

void zoneCommand(Integer z, Map cmd, boolean raw = false, Map ctx = null) {
    String what = "Zone ${z} ${cmd.CommandType}" + (cmd.Parameter ? " ${cmd.Parameter[0].Value}" : "") +
                  (cmd.DimmedLevelParameters ? " ${cmd.DimmedLevelParameters.Level} over ${cmd.DimmedLevelParameters.FadeTime}" : "")
    Map c = raw ? [raw: true] : null
    if (ctx) c = (c ?: [:]) + ctx
    // Told to go to 100: a stopped flash's late 100% step report is then true too, so it isn't dropped
    Object target = cmd.Parameter ? cmd.Parameter[0].Value : cmd.DimmedLevelParameters?.Level
    if (ctx?.flash == null && intOrNull(target) == 100) flashTails[device.idAsLong]?.remove(z.toString())
    if (command("/zone/${z}/commandprocessor".toString(), [Command: cmd], what, c)) markZoneCommand(z)
}

// A command to this zone just went out: its status within DIGITAL_MS is ours (digital)
void markZoneCommand(Object z) {
    Map zc = zoneCommands[device.idAsLong]
    if (zc == null) { zc = new ConcurrentHashMap<String, Long>(); zoneCommands[device.idAsLong] = zc }
    zc[z.toString()] = now()
}

// A zone status (a push, or an answer to a read): switch and level to the zone's child.
void zoneStatus(Map st) {
    Integer z = intOrNull(st?.Zone?.href?.toString()?.tokenize("/")?.getAt(1))
    Integer lvl = intOrNull(st?.Level)
    if (z == null || lvl == null) return
    // While the routing is blocked a zone number may name another load: no status reaches a device, whether pushed or
    // the answer to a read (the refresh after the re-read gives the real one)
    if (state.routingStale) { logDebug "Zone ${z}: level ${lvl} (not applied while the processor's devices are read again)"; return }
    // A zone with a flash undo due: the resync's level says whether the light is still where the flash left it
    Map s0 = sessions[device.idAsLong]
    if (s0 != null && !s0.resynced && ((Map) state.flashResume)?.containsKey(z.toString())) {
        if (s0.seenLevels == null) s0.seenLevels = [:]
        s0.seenLevels[z.toString()] = lvl
    }
    // The late report of the last step of a flash that has stopped (the device keeps the level it had)
    Map tails = flashTails[device.idAsLong]
    Object tailUntil = tails?.remove(z.toString())
    if (tailUntil != null && lvl == 100 && now() <= (tailUntil as Long)) { logDebug "Zone ${z}: 100% (a stopped flash's last step; not applied)"; return }
    Object child = childOf("${device.deviceNetworkId}/z/${z}".toString())
    if (child == null) { logDebug "Zone ${z}: level ${lvl} (no device)"; return }
    Map sSeen = sessions[device.idAsLong]
    if (sSeen != null) { if (sSeen.zonesSeen == null) sSeen.zonesSeen = [] as Set; sSeen.zonesSeen << z.toString() }   // its device has a current state
    String name = child.displayName
    // A flashing zone shows on for the whole flash: its steps' reports don't reach the device. The report of whatever
    // ends the flash (the time limit's restore, or the command that stopped it) gives the real state.
    Map fl = (Map) flashes[device.idAsLong]?.get(z.toString())
    if (fl != null) {
        if (fl.lastStep != null && lvl == (fl.lastStep as Integer)) fl.stepSeen = true      // the last step has reported
        if (child.currentValue("switch")?.toString() != "on")
            child.parse([[name: "switch", value: "on", type: "digital", descriptionText: "${name} switch is on (flashing)".toString()]])
        logDebug "Zone ${z}: level ${lvl} (a flash step)"
        return
    }
    Long at = (Long) zoneCommands[device.idAsLong]?.get(z.toString())
    String type = (at != null && now() - at <= DIGITAL_MS) ? "digital" : "physical"
    String sw = lvl > 0 ? "on" : "off"
    List<Map> evts = [[name: "switch", value: sw, type: type, descriptionText: "${name} switch is ${sw}".toString()]]
    String ctl = zoneControl(z) ?: (st.SwitchedLevel ? "Switched" : "Dimmed")
    // Off keeps the last level (the dimmer's On can go back to it)
    if (ctl != "Switched" && lvl > 0) evts << [name: "level", value: lvl, unit: "%", type: type, descriptionText: "${name} level is ${lvl}%".toString()]
    child.parse(evts)
}

void zoneStatusAnswer(Map s, Map j, Map p, String line) {
    if (j?.Body?.ZoneStatuses instanceof List) j.Body.ZoneStatuses.each { zoneStatus((Map) it) }
    else if (j?.Body?.ZoneStatus instanceof Map) zoneStatus((Map) j.Body.ZoneStatus)
}

/*******************************************************************
 ***** Flash: no LEAP flash command, so the bridge switches the zone itself
********************************************************************/
// Starts flashing the zone (or changes the rate of a flash under way). Steps: GoToLevel 0 and 100 in turn, the first
// opposite the device's switch; each sent only when the last one is answered (flashAnswered).
// A press at the wall during a flash isn't reported (the processor reports only the levels commanded), so a flash also
// ends after limitSecs and the zone goes back to how it was: on at its level, or off. Flash again restarts the limit.
void componentFlash(Object cd, Object rateMs = null, Object limitSecs = null) {
    Integer z = zoneOf(cd)
    if (z == null) return
    if (routingBlocked("${cd.displayName}: Flash")) return     // before a run starts from the old routing
    int rate = Math.max(FLASH_MIN_MS, Math.min(FLASH_MAX_MS, intOrNull(rateMs) ?: 750))
    int limit = Math.max(1, Math.min(FLASH_LIMIT_MAX_SECS, intOrNull(limitSecs) ?: FLASH_LIMIT_SECS))
    Map f = flashMap()
    Map e = (Map) f[z.toString()]
    if (e != null) {
        e.rate = rate
        e.limit = limit
        e.until = now() + limit * 1000L
        runInMillis(limit * 1000L, "flashDeadline", [data: [zone: z, run: e.run], overwrite: false])
        logInfo "${cd.displayName}: flashing every ${rate} ms, for up to ${limit} s from now"
        return
    }
    if (!ready("${cd.displayName}: Flash")) return
    // the restore is the device's level when the flash starts: only once that is the zone's status on this connection
    if (!(z.toString() in ((Set) sessions[device.idAsLong]?.zonesSeen ?: ([] as Set)))) {
        logWarn "${cd.displayName}: Flash not started: its status hasn't come in since the link came up; try again in a moment"
        return
    }
    clearFlashResume(z)
    Object child = childOf("${device.deviceNetworkId}/z/${z}".toString())
    boolean on = child?.currentValue("switch")?.toString() == "on"
    Integer lvl = intOrNull(child?.currentValue("level"))
    int first = on ? 0 : 100
    String run = UUID.randomUUID().toString()
    f[z.toString()] = [run: run, rate: rate, next: first, seq: 0, sentAt: 0L, name: cd.displayName?.toString(), limit: limit,
                       until: now() + limit * 1000L, restore: (on && lvl != null && lvl > 0) ? lvl : 0]
    runInMillis(limit * 1000L, "flashDeadline", [data: [zone: z, run: run], overwrite: false])
    logInfo "${cd.displayName}: flashing every ${rate} ms for up to ${limit} s (any other command stops it)"
    flashStep(z)
}

// The zone's flash, if it is the run named (a timer or a step answer of that run), else null
Map flashRun(Integer z, Object run) {
    Map e = (z != null) ? (Map) flashMap()[z.toString()] : null
    return (e != null && e.run?.toString() == run?.toString()) ? e : null
}

// At the limit, to the second, whatever the steps are doing: a slow or unanswered step doesn't push it back. A Flash
// again sets a later limit and its own timer; this one then finds the limit not yet reached.
void flashDeadline(Map data) {
    Integer z = intOrNull(data?.zone)
    Map e = flashRun(z, data?.run)
    if (e == null || now() < (e.until as Long)) return
    flashTimeUp(z)
}

// The flash's time is up: it stops, and the zone goes back to how it was before it
void flashTimeUp(Integer z) {
    Map e = (Map) flashMap()[z.toString()]
    if (e == null) return
    endFlash(z, "time limit, ${e.limit} s", false)
}

// Stops the flash and sends the zone back to how it was before it (on at its level, or off): after the time limit, and
// after a step that failed, which may have left the zone at either end. Its answer's report gives the device its state.
void endFlash(Integer z, String why, boolean warn) {
    Map e = (Map) flashMap()[z.toString()]
    if (e == null) return
    int back = (e.restore ?: 0) as Integer
    // Where the steps may have left it, for the undo after a drop (as stopAllFlashes keeps), until the restore is answered
    int last = ((e.next as Integer) == 0) ? 100 : 0
    List<Integer> left = (((e.acked as Integer) == (e.seq as Integer)) ? [last] : [0, 100]) + [back]
    stopFlash(z, why, warn, back > 0 ? "; back on at ${back}%" : "; back off")
    String rid = UUID.randomUUID().toString()
    state.flashResume = ((Map) state.flashResume ?: [:]) + [(z.toString()): [level: back, left: left, name: e.name,
                                                                             until: now() + FLASH_RESUME_SECS * 1000L, rid: rid]]
    zoneCommand(z, goToLevel(back), false, [restored: z, rid: rid])
}

Map flashMap() {
    Map f = flashes[device.idAsLong]
    if (f == null) { f = new ConcurrentHashMap<String, Map>(); flashes[device.idAsLong] = f }
    return f
}

void flashStep(Integer z) {
    Map e = (Map) flashMap()[z.toString()]
    if (e == null) return
    if (!ready("Zone ${z} flash")) { stopFlash(z, "not connected", true); return }
    if (e.until != null && now() >= (e.until as Long)) { flashTimeUp(z); return }
    int lvl = e.next as Integer
    e.next = (lvl == 0) ? 100 : 0
    e.seq = (e.seq as Integer) + 1
    e.sentAt = now()
    e.lastStep = lvl
    e.stepSeen = false
    zoneCommand(z, goToLevel(lvl), false, [flash: z, run: e.run, seq: e.seq])
}

// The answer to a flash step: the next step at the rate after the last was sent, or at once if the answer took longer
void flashAnswered(Integer z, Object run, Integer seq, boolean ok, String status) {
    Map e = flashRun(z, run)
    if (e == null || (e.seq as Integer) != seq) return
    if (!ok) { endFlash(z, "the bridge answered ${status}", true); return }
    e.acked = seq
    long wait = (e.sentAt as Long) + (e.rate as Integer) - now()
    if (wait <= 0) flashStep(z)
    else runInMillis(wait, "flashTimer", [data: [zone: z, run: run, seq: seq], overwrite: false])
}

// A step with no answer: the zone may be at either end, so it goes back to how it was
void flashUnanswered(Integer z, Object run, String why) {
    if (flashRun(z, run) != null) endFlash(z, why, true)
}

void flashTimer(Map data) {
    Integer z = intOrNull(data?.zone)
    Map e = flashRun(z, data?.run)
    if (e == null || (e.seq as Integer) != intOrNull(data.seq)) return
    flashStep(z)
}

// Ends the zone's flash, if there is one, and says why (a Warn when something went wrong). Returns the level the flash
// left the zone at (its last step, or the level before it when no step went out), or null when no flash was running.
Integer stopFlash(Integer z, String why, boolean warn = false, String after = "") {
    clearFlashResume(z)                                  // a command to the zone: an undo still due after a drop is dropped
    Map e = (Map) flashes[device.idAsLong]?.remove(z.toString())
    if (e == null) return null
    // its last step went to 100 and hasn't reported yet: that report, when it comes, isn't the zone's new level
    if (e.lastStep == 100 && e.stepSeen != true && now() - ((e.sentAt ?: 0L) as Long) <= FLASH_TAIL_MS) {
        Map tails = flashTails[device.idAsLong]
        if (tails == null) { tails = new ConcurrentHashMap<String, Long>(); flashTails[device.idAsLong] = tails }
        tails[z.toString()] = now() + FLASH_TAIL_MS
    }
    String m = "${e.name ?: 'Zone ' + z}: flash stopped (${why})${after ?: ''}"
    if (warn) logWarn m else logInfo m
    Integer step = intOrNull(e.lastStep)                 // 0 is a step too: not ?:
    return step != null ? step : ((intOrNull(e.restore) ?: 0) as Integer)
}

// The session ended: every flash stops where its last step left the light (0 or 100). When the link ended by itself
// (not Disconnect or pairing), each zone goes back to how it was before its flash once the next resync completes, if
// that is within FLASH_RESUME_SECS and nothing else was asked of the zone meanwhile (a command clears it, stopFlash).
void stopAllFlashes(String why) {
    Map f = flashes[device.idAsLong]
    if (!f) return
    boolean back = state.linkWanted != false
    Map resume = [:]
    new ArrayList(f.keySet()).each { String k ->
        Map e = (Map) f[k]
        int lvl = (e?.restore ?: 0) as Integer
        String was = lvl > 0 ? "${lvl}%" : "off"
        // Where the steps left it: the last one, if its answer came; else the last or the one before
        int last = ((e?.next as Integer) == 0) ? 100 : 0
        List<Integer> left = ((e?.acked as Integer) == (e?.seq as Integer)) ? [last] : [0, 100]
        stopFlash(intOrNull(k), why, true, back ? "; the light may be left at a step: it goes back to ${was} if the link is back within ${FLASH_RESUME_SECS} s"
                                                : "; the light may be left at a step (before the flash: ${was})")
        if (back) resume[k] = [level: lvl, left: left, name: e?.name, until: now() + FLASH_RESUME_SECS * 1000L, rid: UUID.randomUUID().toString()]
    }
    if (resume) state.flashResume = ((Map) state.flashResume ?: [:]) + resume
}

// After a resync: the flashes a drop cut short go back to how they were (stopAllFlashes), only where the resync found
// the light still where the flash left it: a change at the wall or in the Lutron app meanwhile isn't undone, and a zone
// the resync gave no level for is left alone
// Each record stays until its own restore is answered (another drop before then: the next resync tries again), a command
// to the zone clears it, or it expires. A restore's answer comes in a later execution, so the records kept here are
// written back whole.
void resumeFlashes(Map s) {
    Map r = (Map) state.flashResume
    if (!r) return
    Map keep = [:]
    r.each { String k, Object v ->
        Map m = (Map) v
        if (now() > (m.until as Long)) return
        // a write failed and the link dropped: the rest wait for the next resync, which reads their levels first
        if (!sessions[device.idAsLong].is(s)) { keep[k] = m; return }
        Integer z = intOrNull(k)
        int lvl = (m.level ?: 0) as Integer
        Integer now = intOrNull(((Map) s?.seenLevels)?.get(k))
        List left = ((List) m.left ?: [0, 100]).collect { it as Integer }
        if (now == null || !(now in left)) {
            logInfo "${m.name ?: 'Zone ' + k}: the flash the connection cut short is not undone (${now == null ? 'no level read' : 'now at ' + now + '%'})"
            return
        }
        logInfo "${m.name ?: 'Zone ' + k}: back to ${lvl > 0 ? lvl + '%' : 'off'}, as before the flash the connection cut short"
        String rid = m.rid?.toString() ?: UUID.randomUUID().toString()
        keep[k] = m + [rid: rid, left: (left + [lvl]).unique()]          // the restore may land before a drop
        state.flashResume = keep                                         // before the write: a failed write drops the link
        zoneCommand(z, goToLevel(lvl), false, [restored: z, rid: rid])
    }
    if (keep) state.flashResume = keep else state.remove("flashResume")
}

// rid: only that record (a restore's answer); none: whatever is there (a command to the zone supersedes it)
void clearFlashResume(Integer z, Object rid = null) {
    Map r = (Map) state.flashResume
    if (z == null || r == null || !r.containsKey(z.toString())) return
    if (rid != null && ((Map) r[z.toString()])?.rid?.toString() != rid.toString()) return
    r.remove(z.toString())
    if (r) state.flashResume = r else state.remove("flashResume")
}

/*******************************************************************
 ***** Buttons and LEDs: Picos and keypads, <bridge DNI>/d/<deviceId>
********************************************************************/
// A Pico child's Push (times 1) or Double Tap (2) presses the button on the bridge, which reports
// the press like a real one (buttonEvent makes the events). A second press waits for the first one's Release,
// so the two can't interleave.
void componentPush(Object cd, Object n, Object times = 1) {
    Integer b = intOrNull(n)
    Object bid = buttonKey((Map) state.buttons, lutronDeviceOf(cd), b)
    if (bid == null) { logWarn "${cd?.displayName}: button ${n} isn't one of this bridge's Pico buttons; not pressed"; return }
    String desc = "${cd.displayName} button ${b}".toString()
    if (routingBlocked(desc)) return                     // before a press is held as a raw button id
    Map st = buttonState(bid)
    boolean twice = intOrNull(times) == 2
    // One Double Tap per button at a time: its second press waits on the first one's Release, kept in one flag
    if (twice && doubleTapPending(bid)) { logWarn "${desc}: a Double Tap on it is already under way; this one is dropped"; return }
    Map s0 = sessions[device.idAsLong]
    // Each Double Tap has its own id: a late refusal or timeout of an earlier one can't clear this one's second press
    Map ctx = twice ? [again: true, gid: UUID.randomUUID().toString()] : null
    // Identity checked, but the resync hasn't subscribed the buttons yet: held, and sent with the other held presses
    // once it has (sendHeldPresses), so its reports come back and a Double Tap keeps its second press
    if (s0?.kind == "leap" && s0.phase == "up" && s0.verified && !s0.pressesSent) {
        holdCommand(s0, [url: "/button/${bid}/commandprocessor".toString(), body: [Command: [CommandType: "PressAndRelease"]], desc: desc,
                         ctx: ctx, type: "CreateRequest"])
        return
    }
    if (twice && s0?.pressesSent && !buttonSubscribedNow(s0, bid)) {
        logWarn "${desc}: Double Tap not sent (its button isn't subscribed on this connection, so the first press's Release wouldn't come back)"
        return
    }
    // A Double Tap held for the link carries its second press (ctx.again), so the replay keeps the whole gesture
    if (pressButton(bid, desc, ctx) && twice) armAgain(st, desc, ctx.gid)
}

// A Double Tap's second press, due on the first one's Release; it lapses after AGAIN_MS (no Release came)
void armAgain(Map st, Object desc, Object gid) {
    st.again = true; st.againDesc = desc?.toString(); st.againUntil = now() + AGAIN_MS; st.againId = gid?.toString()
}

// The second press, if one is due and still fresh; cleared either way
boolean takeAgain(Map st) {
    boolean due = st.again == true && now() <= ((st.againUntil ?: 0L) as Long)
    st.remove("again"); st.remove("againUntil"); st.remove("againId")
    return due
}

// Clears the button's pending second press; with gid, only if it is that Double Tap's
void clearAgain(Object bid, Object gid = null) {
    Map st = buttonState(bid)
    if (gid != null && st.againId?.toString() != gid.toString()) return
    st.remove("again"); st.remove("againUntil"); st.remove("againId")
}

// A Double Tap on this button waiting for its second press, or held for the link
boolean doubleTapPending(Object bid) {
    Map st = buttonState(bid)
    if (st.again && now() > ((st.againUntil ?: 0L) as Long)) clearAgain(bid)        // its Release never came
    if (st.again) return true
    String url = "/button/${bid}/commandprocessor".toString()
    return ((List) heldCommands[device.idAsLong] ?: []).any { it.url?.toString() == url && it.ctx?.again }
}

// The button's events subscription was answered on this connection
boolean buttonSubscribedNow(Map s, Object bid) {
    Integer id = intOrNull(bid)
    return id != null && ((List) s?.subAcked ?: []).any { intOrNull(it) == id }
}

boolean pressButton(Object bid, String desc, Map ctx = null) {
    if (!command("/button/${bid}/commandprocessor".toString(), [Command: [CommandType: "PressAndRelease"]], desc, ctx)) return false
    buttonState(bid).sentAt = now()
    return true
}

// The app's device (Pico or keypad) for a Lutron device id, or null
Object buttonDevice(Object deviceId) { childOf("${device.deviceNetworkId}/d/${deviceId}".toString()) }

// The Lutron device id in a Pico's or keypad's DNI (<bridge DNI>/d/<deviceId>), or null
String lutronDeviceOf(Object cd) {
    String dni = cd?.deviceNetworkId?.toString()
    String prefix = "${device.deviceNetworkId}/d/"
    return (dni?.startsWith(prefix)) ? dni.substring(prefix.length()) : null
}

// The key (button or LED id) in state.buttons / state.leds for that device's button n, or null
Object buttonKey(Map table, String dev, Integer n) {
    if (table == null || dev == null || n == null) return null
    return table.find { k, v -> v.d?.toString() == dev && intOrNull(v.n) == n }?.key
}

// Puts a subscription for each added Pico's or keypad's buttons at the front of the queue: the ones this connection
// hasn't subscribed yet (the resync: all; a later re-read: only the new ones). A button nobody subscribed to sends
// this connection nothing.
void queueButtonSubs(Map s) {
    Map btns = state.buttons ?: [:]
    Set<String> have = childList().collect { it.deviceNetworkId.toString() } as Set
    List<Integer> ids = btns.findAll { k, v -> "${device.deviceNetworkId}/d/${v.d}".toString() in have }.collect { k, v -> k as Integer }.sort()
    ids = newSubs(s, "subButtons", ids)
    s.queue.addAll(0, ids.collect { subItem(s, "button", it) })
}

// A button or LED subscription. One that failed before on this connection is retried quietly: its first failure Warned.
Map subItem(Map s, String kind, Integer id) {
    String url = (kind == "button") ? "/button/${id}/status/event".toString() : "/led/${id}/status".toString()
    Map item = [type: "SubscribeRequest", url: url, then: (kind == "button") ? "buttonSubscribed" : "ledSubscribed"]
    if (url in ((List) s.failedSubs ?: [])) item.ctx = [quiet: true]
    return item
}

// A refused (or twice unanswered) button or LED subscription comes off the list of what this connection has, so the
// change check subscribes it again
void subFailed(Map s, String key, String url) {
    Integer id = intOrNull(url?.tokenize("/")?.getAt(1))
    if (id != null) s[key] = ((List) (s[key] ?: [])).findAll { (it as Integer) != id }
    if (!(url in ((List) s.failedSubs ?: []))) s.failedSubs = ((List) (s.failedSubs ?: [])) + url
}

// The ids not yet subscribed on this connection (kept in the session, so a reconnect starts again), now marked as subscribed
List<Integer> newSubs(Map s, String key, List<Integer> ids) {
    List done = (List) (s[key] ?: [])
    List<Integer> fresh = ids.findAll { !(it in done) }
    s[key] = done + fresh
    return fresh
}

void buttonSubscribed(Map s, Map j, Map p, String line) {
    if (j == null) { subFailed(s, "subButtons", p?.url?.toString()); return }
    Integer id = intOrNull(p?.url?.toString()?.tokenize("/")?.getAt(1))
    if (id != null) s.subAcked = ((List) (s.subAcked ?: [])) + id      // answered: its Press and Release reports will come
}

// The LEDs of every added keypad, at the front of the queue (after its buttons)
void queueLedSubs(Map s) {
    Map leds = state.leds ?: [:]
    Set<String> have = childList().collect { it.deviceNetworkId.toString() } as Set
    List<Integer> ids = leds.findAll { k, v -> "${device.deviceNetworkId}/d/${v.d}".toString() in have }.collect { k, v -> k as Integer }.sort()
    ids = newSubs(s, "subLeds", ids)
    s.queue.addAll(0, ids.collect { subItem(s, "led", it) })
}

void ledSubscribed(Map s, Map j, Map p, String line) {
    if (j == null) { subFailed(s, "subLeds", p?.url?.toString()); return }
    if (j.Body?.LEDStatus instanceof Map) ledEvent((Map) j.Body.LEDStatus)
}

// An LED's state (its subscription's answer or a change) to its keypad: [name: led, button: n, value: on / off]
void ledEvent(Map st) {
    Integer id = hrefId(st.LED?.href ?: st.href)
    if (state.routingStale) { logDebug "LED ${id}: ${st.State} (not applied while the processor's devices are read again)"; return }
    Map info = (id != null) ? (Map) state.leds?.get(id.toString()) : null
    Object child = info ? buttonDevice(info.d) : null
    if (child == null) { logDebug "LED ${id}: ${st.State} (no keypad device)"; return }
    // A button with no programming reports Unknown: skipped, rather than shown as off
    String v = st.State?.toString()
    if (!(v in ["On", "Off"])) { logDebug "LED ${id}: ${v} (skipped)"; return }
    child.parse([[name: "led", button: intOrNull(info.n), value: v == "On" ? "on" : "off"]])
}

// Sets a keypad button's LED (UpdateRequest /led/<id>/status, as pylutron-caseta does). On a programmed button
// Lutron's programming sets it back.
void componentSetLed(Object cd, Object n, Object value) {
    Integer b = intOrNull(n)
    Object led = buttonKey((Map) state.leds, lutronDeviceOf(cd), b)
    if (led == null) { logWarn "${cd?.displayName}: button ${n} has no LED; not set"; return }
    String st = (value?.toString()?.toLowerCase() in ["on", "true", "1"]) ? "On" : "Off"
    command("/led/${led}/status".toString(), [LEDStatus: [State: st]], "${cd.displayName} button ${b} LED ${st}".toString(), null, "UpdateRequest")
}

Map buttonState(Object buttonId) {
    Map all = buttonStates[device.idAsLong]
    if (all == null) { all = new ConcurrentHashMap<String, Map>(); buttonStates[device.idAsLong] = all }
    Map st = (Map) all[buttonId.toString()]
    if (st == null) { st = [seq: 0]; all[buttonId.toString()] = st }
    return st
}

// The bridge sends only Press and Release; hold and double tap come from timing. A quick release is pushed (after the
// double-tap window, if on); a press past the hold time is held, then released. (Lutron LEAP Fast Pico (User) gives
// pushed at the Press.)
void buttonEvent(Map bs) {
    Integer bid = hrefId(bs.Button?.href)
    String ev = bs.ButtonEvent?.EventType?.toString()
    Map info = (bid != null) ? (Map) state.buttons?.get(bid.toString()) : null
    Object child = info ? buttonDevice(info.d) : null
    if (child == null) { logDebug "Button ${bid}: ${ev} (no Pico device)"; return }
    Integer n = info.n as Integer
    // Keypads and Fast Picos: pushed at the Press, released at the Release, nothing timed
    if (info.kp || child.typeName?.toString() == FAST_PICO) { fastButton(child, n, bid, ev); return }
    Integer holdMs = intOrNull(child.getSetting("holdMs")) ?: HOLD_MS
    Integer tapSet = intOrNull(child.getSetting("doubleTapMs"))          // "0" is Off, so no Groovy-truth default
    Integer tapMs = tapSet != null ? tapSet : TAP_MS
    Map st = buttonState(bid)
    long t = now()
    if (ev == "Press") {
        // A press waiting out the double-tap window whose timer hasn't run yet (the hub busy): the window closed, so it
        // was a single press: pushed now, before this one starts (bumping seq cancels its timer)
        if (st.tapUntil != null && t > (st.tapUntil as Long) && !st.down) {
            st.tapUntil = null
            buttonOut(child, n, "pushed", st)
        }
        st.digital = st.sentAt != null && t - (st.sentAt as Long) <= PRESS_DIGITAL_MS
        boolean second = tapMs > 0 && st.tapUntil != null && t <= (st.tapUntil as Long)
        st.seq = (st.seq as Integer) + 1                  // cancels a pending double-tap timer
        st.down = true; st.held = false; st.second = second; st.tapUntil = null
        st.holdAt = t + holdMs                            // the Release checks it too: the hold timer can run late
        runInMillis(holdMs, "buttonHoldTimer", [data: [b: bid, seq: st.seq, sid: sessionId()], overwrite: false])
    } else if (ev == "Release") {
        if (!st.down) return
        st.down = false
        st.seq = (st.seq as Integer) + 1                  // cancels the hold timer
        // past the hold time but its timer not run yet (the hub busy): it was a hold
        if (!st.held && st.holdAt != null && t >= (st.holdAt as Long)) {
            st.held = true
            if (st.second) { st.second = false; buttonOut(child, n, "pushed", tapOf(st)) }   // the tap before this press
            buttonOut(child, n, "held", st)
        }
        if (takeAgain(st)) pressButton(bid, st.againDesc?.toString())   // Double Tap's second press
        if (st.held) { buttonOut(child, n, "released", st); return }
        if (st.second) {
            buttonOut(child, n, "doubleTapped", st)
        } else if (tapMs > 0) {
            st.tapUntil = t + tapMs
            st.tapDigital = st.digital                    // the tap's own type, if a later press reports it
            runInMillis(tapMs, "buttonTapTimer", [data: [b: bid, seq: st.seq, sid: sessionId()], overwrite: false])
        } else {
            buttonOut(child, n, "pushed", st)
        }
    } else {
        logDebug "Button ${bid}: ${ev} (not used)"
    }
}

// Fast Pico: pushed at Press, released at Release, nothing timed. A Push of ours is digital.
void fastButton(Object child, Integer n, Integer bid, String ev) {
    Map st = buttonState(bid)
    if (ev == "Press") {
        st.digital = st.sentAt != null && now() - (st.sentAt as Long) <= PRESS_DIGITAL_MS
        st.seq = (st.seq as Integer) + 1
        st.down = true
        buttonOut(child, n, "pushed", st)
    } else if (ev == "Release") {
        if (!st.down) return
        st.down = false
        st.seq = (st.seq as Integer) + 1
        // a programmed RA3 keypad button reports Press only; one seen sending Release gets one at a drop too (kept
        // across reconnects; written once per button)
        if (!(bid.toString() in ((List) state.releaseButtons ?: []))) state.releaseButtons = ((List) state.releaseButtons ?: []) + bid.toString()
        if (takeAgain(st)) pressButton(bid, st.againDesc?.toString())
        buttonOut(child, n, "released", st)
    } else {
        logDebug "Button ${bid}: ${ev} (not used)"
    }
}

void buttonHoldTimer(Map data) {
    if (!thisSession(data)) return
    Map st = buttonState(data.b)
    if (st.seq != data.seq || !st.down) return
    st.held = true
    if (st.second) { st.second = false; buttonTimerOut(data.b, "pushed", tapOf(st)) }   // the tap before this press, not a double tap
    buttonTimerOut(data.b, "held")
}

void buttonTapTimer(Map data) {
    if (!thisSession(data)) return
    Map st = buttonState(data.b)
    if (st.seq != data.seq || st.down || st.tapUntil == null) return
    st.tapUntil = null
    buttonTimerOut(data.b, "pushed")
}

// A gesture's timers carry the session they began in: one from an ended session (a timer survives a code save, which
// clears the button states) never acts on a gesture of the next
Object sessionId() { sessions[device.idAsLong]?.startedAt }
boolean thisSession(Map data) { data?.sid != null && data.sid.toString() == sessionId()?.toString() }

// The session is ending, so no Release will come for a button that is down: a held button (or a Fast Pico or keypad
// button, pushed at the Press) gets released now, so a rule ramping on held / stopping on released stops; a press not
// yet held is dropped; a finished press waiting out the double-tap window is pushed now. Then the states and timers go.
void closeGestures() {
    Map all = buttonStates.remove(device.idAsLong)
    unschedule("buttonHoldTimer")
    unschedule("buttonTapTimer")
    if (!all) return
    all.each { Object bid, Object v ->
        Map st = (Map) v
        Map info = (Map) state.buttons?.get(bid.toString())
        Object child = info ? buttonDevice(info.d) : null
        if (child == null) return
        boolean fast = info.kp || child.typeName?.toString() == FAST_PICO
        // a keypad button released at the drop only if it is known to send Releases (a programmed one never does: its
        // "down" can be hours old)
        if (info.kp && !(bid.toString() in ((List) state.releaseButtons ?: []))) return
        if (st.down && (st.held || fast)) {
            logDebug "Button ${bid}: the connection ended while it was down; released"
            buttonOut(child, info.n as Integer, "released", st)
        } else if (!st.down && st.tapUntil != null) {
            buttonOut(child, info.n as Integer, "pushed", st)
        } else if (st.down && st.second && !st.held) {
            buttonOut(child, info.n as Integer, "pushed", tapOf(st))   // the tap before the press the drop cut short
        }
    }
}

void buttonTimerOut(Object bid, String name, Map st = null) {
    if (state.routingStale) return                       // its button number may name another button now
    Map info = (Map) state.buttons?.get(bid.toString())
    Object child = info ? buttonDevice(info.d) : null
    if (child != null) buttonOut(child, info.n as Integer, name, st ?: buttonState(bid))
}

// The tap waiting out the double-tap window, reported after a later press: with its own physical / digital type
Map tapOf(Map st) { [digital: st.tapDigital == true] }

void buttonOut(Object child, Integer n, String name, Map st) {
    String verb = (name == "doubleTapped") ? "double-tapped" : name
    String type = st?.digital ? "digital" : "physical"
    logDebug "${child.displayName} button ${n} ${verb} (${type})"
    child.parse([[name: name, value: n, isStateChange: true, type: type,
                  descriptionText: "${child.displayName} button ${n} was ${verb}".toString()]])
}

/*******************************************************************
 ***** Discovery: Caseta, six reads, one compact list for the app
********************************************************************/
// haveProject: the resync has just read /project and /device, so they aren't read twice. front: ahead of what is queued
// (the resync's discovery comes before the button subscriptions, which use its map)
void queueDiscovery(Map s, boolean haveProject = false, boolean front = false) {
    // One discovery at a time per session: its answers go into s.disc, so a second one would empty it mid-read
    if (s.disc != null) { logInfo "Discover: already reading the devices; that read gives the new list"; return }
    s.disc = [:]
    if (haveProject && s.project) s.disc["/project"] = s.project
    if (haveProject && s.identity) s.disc["/device"] = s.identity        // the resync's identity read: the same answer
    List items = DISCOVERY_READS.findAll { !s.disc[it] }.collect { [type: "ReadRequest", url: it, then: "discoveryAnswer"] }
    items << [type: "ReadRequest", url: OCCUPANCY_GROUPS_URL, then: "discoveryAnswer", ctx: [quiet: true]]
    items << [call: "discoveryDone"]
    if (front) s.queue.addAll(0, items) else s.queue.addAll(items)
}

void discoveryAnswer(Map s, Map j, Map p, String line) {
    if (s.disc == null) return
    if (j == null && p.url == OCCUPANCY_GROUPS_URL) { logDebug "Discover: no occupancy groups (${p.url} not answered)"; s.disc.occFailed = true; return }
    if (j == null) s.disc.failed = p.url
    else s.disc[p.url] = j
}

void discoveryDone(Map s) {
    Map d = (Map) s.remove("disc")
    if (d == null) return
    String missing = d.failed ?: DISCOVERY_READS.find { !d[it] }
    if (missing) { discoveryFailed(s, "no good answer to ${missing}"); return }
    Map list
    try {
        list = compactList(d)
    } catch (Exception e) {
        logErr "Discover: couldn't make sense of the answers (${e.message})"
        firstDiscoveryFailed(s)
        return
    }
    applySystem((Map) list.system)
    tellSerial(list.system?.serial)
    String fp = deviceFingerprint((Map) d["/device"])
    if (fp) state.deviceFingerprint = fp
    state.zones = list.zones.collectEntries { [(it.id.toString()): it.control] }
    state.scenes = list.scenes.collectEntries { [(it.id.toString()): it.name] }
    Map btns = [:]
    list.picos.each { Map pc -> pc.buttons.each { Map b -> btns[b.id.toString()] = [d: pc.id, n: b.n as Integer] } }
    state.buttons = btns
    state.buttonLayout = BUTTON_LAYOUT
    // Caseta: the occupancy groups with sensors. Lighting doesn't wait on them (as in pylutron-caseta): when their read
    // fails the list says occupancy is unknown (null, not empty), the groups and their routing stay as they were, and
    // the 10-minute check reads them again. An answer with no groups does clear them.
    if (d.occFailed) {
        list.occupancy = null
        list.occupancyUnknown = true                        // said outright: the app keeps the rooms it has
        state.occRetry = true
    } else {
        state.occAreas = list.occupancy.collect { it.id as Integer }.sort()
        state.occFingerprint = occFingerprint((Map) d[OCCUPANCY_GROUPS_URL])
        state.remove("occRetry")
    }
    state.discoveredAt = now()
    sendEventLog(name: "numberOfButtons", value: list.scenes ? list.scenes.collect { it.id as Integer }.max() : 0)
    logInfo "Devices read: ${list.zones.size()} zones, ${list.picos.size()} Picos, ${list.scenes.size()} scenes" +
            (list.occupancy ? ", ${list.occupancy.size()} rooms with occupancy sensors" : "") +
            (d.occFailed ? " (occupancy groups not read: the rooms with sensors are kept as they were)" : "")
    try {
        parent?.discoveryResult(list)
    } catch (Exception e) {
        logErr "Couldn't hand the device list to the app: ${e.message}"
    }
    subscribeNew(s)
}

// A re-read on a live link (Discover, the change check) subscribes the buttons and LEDs it hasn't yet
// (the resync's own subscriptions come after its discovery)
void subscribeNew(Map s) {
    if (!s?.resynced || !sessions[device.idAsLong].is(s)) return
    queueLedSubs(s)
    queueButtonSubs(s)
    pump(s)
}

// The six answers boiled down to what the app's picker and the routing need (tens of KB -> a few KB). A list a bridge
// leaves out of a 200 answer is empty: a bridge with no Picos answers /button with an empty Body (as pylutron-caseta
// handles it).
Map compactList(Map d) {
    Map areas = [:], parents = [:]
    ((List) d["/area"].Body?.Areas ?: []).each { areas[it.href] = it.Name; parents[it.href] = it.Parent?.href }
    Closure where = { Object href -> areaPath(href, areas, parents) }
    List devs = (List) d["/device"].Body?.Devices ?: []
    Map byHref = devs.collectEntries { [(it.href): it] }
    Map me = devs.find { it.IsThisDevice } ?: [:]
    List zones = ((List) d["/zone"].Body?.Zones ?: []).collect { Map z ->
        Map dv = (Map) byHref[z.Device?.href] ?: [:]
        [id: hrefId(z.href), name: z.Name, control: z.ControlType, area: areas[dv.AssociatedArea?.href],
         device: dv.href ? hrefId(dv.href) : null, model: dv.ModelNumber, deviceType: dv.DeviceType, deviceHref: dv.href,
         location: where(dv.AssociatedArea?.href)]
    }
    Map groups = ((List) d["/button"].Body?.Buttons ?: []).groupBy { it.Parent?.href }
    // Caseta: Picos only (RA3 keypads are read by area, pdDone)
    List picos = devs.findAll { deviceKind(it.DeviceType) == "pico" }.collect { Map dv ->
        List bs = (dv.ButtonGroups ?: []).collectMany { groups[it.href] ?: [] }
                    .collect { [id: hrefId(it.href), number: it.ButtonNumber, n: picoButton(dv.DeviceType, it.ButtonNumber)] }.sort { it.number }
        [id: hrefId(dv.href), name: dv.Name, area: areas[dv.AssociatedArea?.href], deviceType: dv.DeviceType,
         model: dv.ModelNumber, buttons: bs, location: where(dv.AssociatedArea?.href)]
    }
    List scenes = ((List) d["/virtualbutton"].Body?.VirtualButtons ?: []).findAll { it.IsProgrammed }.collect { [id: hrefId(it.href), name: it.Name] }
    // Occupancy: one group per room; a group with sensors becomes a motion device (id = the group's, sensors = its
    // /occupancysensor ids), as a processor's areas do (pylutron-caseta keeps the same ones)
    List occupancy = ((List) d[OCCUPANCY_GROUPS_URL]?.Body?.OccupancyGroups ?: []).findAll { it.AssociatedSensors && it.AssociatedAreas }.collect { Map g ->
        Object area = g.AssociatedAreas[0]?.Area?.href
        [id: hrefId(g.href), name: areas[area], sensors: g.AssociatedSensors.collect { hrefId(it.OccupancySensor?.href) }.findAll { it != null }.sort(),
         location: where(area)]
    }.sort { it.id }
    Map system = [productType: d["/project"].Body.Project?.ProductType, deviceType: me.DeviceType, model: me.ModelNumber,
                  serial: me.SerialNumber, firmware: me.FirmwareImage?.Firmware?.DisplayName]
    return [system: system, zones: zones, picos: picos, scenes: scenes, occupancy: occupancy, readAt: now()]
}

Integer hrefId(Object href) { intOrNull(href?.toString()?.tokenize("/")?.getAt(1)) }

// A device's location: its area's path from the top down, joined with " > ", without the top area (Caseta's home, a
// processor's project). names / parents: area key -> name / parent key.
String areaPath(Object key, Map names, Map parents) {
    List<String> path = []
    Object k = key
    int guard = 0
    while (k != null && parents[k] != null && guard++ < 20) {
        path.add(0, names[k]?.toString() ?: "?")
        k = parents[k]
    }
    return path ? path.join(" > ") : null
}

// Data values for the bridge, and its name: "Lutron <DeviceType>", set once, while the name is still the driver's. A
// label that is just the driver name is a default, not the user's, so it is replaced too; any other label stays.
void applySystem(Map sys) {
    [productType: sys.productType, lutronDeviceType: sys.deviceType, lutronModelNumber: sys.model, firmware: sys.firmware,
     serialNumber: sys.serial].each { k, v -> if (v != null) device.updateDataValue(k, v.toString()) }
    if (state.named || !sys.deviceType) return
    state.named = true
    String want = "Lutron ${sys.deviceType}"
    if (device.name == DRIVER) {
        device.setName(want)
        if (device.label == DRIVER) device.setLabel(want)
        logInfo "Named the device ${want}"
    }
}

/*******************************************************************
 ***** Discovery: processors, by area
********************************************************************/
// On a processor /device, /zone, /button, /controlstation and /led give nothing (204, 405, 400), so: /area; each leaf
// area's zones and control stations; each keypad's and Pico's button groups and device record; the processor's own
// device. Each answer may queue more reads at the front of the queue; pdDone, queued first, runs last.
void queueProcessorDiscovery(Map s) {
    if (s.pd != null) { logInfo "Discover: already reading the devices; that read gives the new list"; return }   // as queueDiscovery
    s.pd = [zones: [], stations: [], devices: [:], buttons: [:], areas: [:]]
    List items = [[type: "ReadRequest", url: "/area", then: "pdAreas"]]
    // The processor's own record: from this session's identity read when there is one, else read
    Map me = (Map) ((List) s.identity?.Body?.Devices ?: [])[0]
    if (me) s.pd.self = me
    else items << [type: "ReadRequest", url: "/device?where=IsThisDevice:true", then: "pdSelf"]
    s.queue.addAll(0, items + [[call: "pdDone"]])
}

boolean pdFailed(Map s, Map j, Map p) {
    if (s.pd == null) return true
    if (j == null && !s.pd.failed) s.pd.failed = p.url
    return s.pd.failed != null
}

void pdAreas(Map s, Map j, Map p, String line) {
    if (pdFailed(s, j, p)) return
    List items = []
    (j.Body?.Areas ?: []).each { Map a ->
        Integer id = hrefId(a.href)
        s.pd.areas[id.toString()] = [name: a.Name, leaf: a.IsLeaf == true, parent: a.Parent ? hrefId(a.Parent.href) : null]
        if (a.IsLeaf) items << [type: "ReadRequest", url: "/area/${id}/associatedzone".toString(), then: "pdZones"] <<
                               [type: "ReadRequest", url: "/area/${id}/associatedcontrolstation".toString(), then: "pdStations"]
    }
    items << [call: "pdDevices"]
    s.queue.addAll(0, items)
}

void pdZones(Map s, Map j, Map p, String line) {
    if (pdFailed(s, j, p)) return
    s.pd.zones.addAll((j.Body?.Zones ?: []) as List)                    // 204 NoContent: an area with none
}

void pdStations(Map s, Map j, Map p, String line) {
    if (pdFailed(s, j, p)) return
    s.pd.stations.addAll((j.Body?.ControlStations ?: []) as List)
}

// pylutron-caseta's type lists (_PICOS, _KEYPADS): types whose names don't say so (PaddleSwitchPico, the
// Vierti hybrid keypad's internal id) are known here; the name patterns below catch types newer than the lists
@Field static final List<String> PICO_TYPES = ["Pico1Button", "Pico2Button", "Pico2ButtonRaiseLower", "Pico3Button", "Pico3ButtonRaiseLower",
                                              "Pico4Button", "Pico4ButtonScene", "Pico4ButtonZone", "Pico4Button2Group", "PaddleSwitchPico"]
@Field static final List<String> KEYPAD_TYPES = ["SeeTouchTabletopKeypad", "SunnataKeypad", "SunnataHybridKeypad", "SeeTouchHybridKeypad",
                                                "SeeTouchKeypad", "HomeownerKeypad", "GrafikTHybridKeypad", "AlisseKeypad", "PalladiomKeypad",
                                                "PhantomKeypad", "N-CVLpqtTTOIeG-4hcjKgw"]

// Picos whose buttons sit in the five places of the 3-button raise / lower Pico, LEAP ButtonNumber 0 on, 1 favourite,
// 2 off, 3 raise, 4 lower. Hubitat's Lutron integrations (Telnet and LEAP) number those buttons top to bottom: on 1,
// raise 2, favourite 3, lower 4, off 5. Rules written for them then work unchanged.
@Field static final List<String> FIVE_PLACE_PICOS = ["Pico2ButtonRaiseLower", "Pico3Button", "Pico3ButtonRaiseLower"]
@Field static final Map<Integer, Integer> FIVE_PLACE_BUTTONS = [0: 1, 3: 2, 1: 3, 4: 4, 2: 5]
// A 2-button or paddle Pico has ButtonNumber 0 (on) and 2 (off): on 1, off 2, as Hubitat's LEAP integration numbers it
@Field static final List<String> TWO_BUTTON_PICOS = ["Pico2Button", "PaddleSwitchPico"]
@Field static final Map<Integer, Integer> TWO_BUTTONS = [0: 1, 2: 2]
// The numbering of the saved button map (state.buttons): Picos as above, keypads by Lutron's own numbers
@Field static final Integer BUTTON_LAYOUT = 3

// A Pico button's Hubitat number: as above on the five-place and two-button Picos, ButtonNumber + 1 on the others
Integer picoButton(Object deviceType, Object buttonNumber) {
    Integer b = intOrNull(buttonNumber)
    if (b == null) return null
    String t = deviceType?.toString()
    Integer mapped = (t in FIVE_PLACE_PICOS) ? FIVE_PLACE_BUTTONS[b] : (t in TWO_BUTTON_PICOS) ? TWO_BUTTONS[b] : null
    return mapped ?: b + 1
}

String deviceKind(Object type) {
    String t = type?.toString() ?: ""
    if (t in PICO_TYPES || t.startsWith("Pico")) return "pico"
    if (t in KEYPAD_TYPES || t.contains("Keypad")) return "keypad"
    if (t.contains("OccupancySensor")) return "sensor"
    return null
}

// The keypads' and Picos' buttons and device records; the other devices' records (dimmers, switches: their LocalZones
// name the zones they control, which a zone record doesn't). Occupancy sensors need none.
void pdDevices(Map s) {
    if (s.pd == null || s.pd.failed) return
    List items = []
    s.pd.stations.each { Map st ->
        (st.AssociatedGangedDevices ?: []).each { Map g ->
            String kind = deviceKind(g.Device?.DeviceType)
            if (kind == "sensor" || g.Device?.href == null) return
            Integer id = hrefId(g.Device.href)
            if (kind in ["pico", "keypad"]) items << [type: "ReadRequest", url: "/device/${id}/buttongroup/expanded".toString(), then: "pdButtons"]
            items << [type: "ReadRequest", url: "/device/${id}".toString(), then: "pdDevice"]
        }
    }
    s.queue.addAll(0, items)
}

void pdButtons(Map s, Map j, Map p, String line) {
    if (pdFailed(s, j, p)) return
    Integer id = hrefId(p.url)                                          // /device/<id>/buttongroup/expanded
    List bs = []
    (j.Body?.ButtonGroupsExpanded ?: []).each { Map g ->
        (g.Buttons ?: []).each { Map b ->
            bs << [id: hrefId(b.href), number: b.ButtonNumber, led: b.AssociatedLED ? hrefId(b.AssociatedLED.href) : null,
                   engraving: b.Engraving?.Text ?: null]
        }
    }
    s.pd.buttons[id.toString()] = bs.sort { it.number as Integer }
}

void pdDevice(Map s, Map j, Map p, String line) {
    if (pdFailed(s, j, p)) return
    Map d = (Map) j.Body?.Device
    if (d) s.pd.devices[hrefId(d.href).toString()] = d
}

void pdSelf(Map s, Map j, Map p, String line) {
    if (pdFailed(s, j, p)) return
    s.pd.self = ((List) j.Body?.Devices ?: [])[0]
}

// A re-read that fails keeps the list from before (Warn); a first one leaves nothing to route by, and one that was to
// renumber a map saved with another numbering leaves a map that routes presses to the wrong buttons: in either case the
// resync can't call the link connected, so it reconnects instead
void discoveryFailed(Map s, String why) {
    logWarn "Discover: ${why}; the device list wasn't updated"
    firstDiscoveryFailed(s)
}

void firstDiscoveryFailed(Map s) {
    // the routing is blocked until the devices are read again: a failed re-read, even on a live link, reconnects
    if (state.routingStale) { resyncIncomplete(s, "the devices weren't read again after the project changed"); return }
    if (s.resynced) return
    if (!state.discoveredAt) resyncIncomplete(s, "no first device list")
    else if (oldButtonLayout()) resyncIncomplete(s, "the button map wasn't read again with the current numbering")
}

void pdDone(Map s) {
    Map pd = (Map) s.remove("pd")
    if (pd == null) return
    if (pd.failed) { discoveryFailed(s, "no good answer to ${pd.failed}"); return }
    List zones = pdZoneList(pd, pdZoneDevices(pd))
    Map stations = pdStationLists(pd)
    List picos = (List) stations.picos, keypads = (List) stations.keypads
    Map occupancy = (Map) stations.occupancy
    Map me = (Map) pd.self ?: [:]
    Map system = [productType: state.productType, deviceType: me.DeviceType, model: me.ModelNumber, serial: me.SerialNumber,
                  firmware: me.FirmwareImage?.Firmware?.DisplayName]
    Map list = [system: system, zones: zones, picos: picos.sort { it.id }, keypads: keypads.sort { it.id },
                occupancy: occupancy.values().sort { it.id }, scenes: [], readAt: now()]
    applySystem(system)
    tellSerial(system.serial)
    boolean unblockedLive = state.routingStale == true && s.resynced == true
    pdSaveRouting(s, zones, picos, keypads, occupancy)          // before the hand-off: it numbers the Picos' and keypads' buttons
    state.remove("routingStale")                                // the new project's routing is in: commands go again
    sendEventLog(name: "numberOfButtons", value: 0)
    logInfo "Devices read: ${zones.size()} zones, ${picos.size()} Picos, ${keypads.size()} keypads, ${occupancy.size()} areas with occupancy sensors"
    try {
        parent?.discoveryResult(list)
    } catch (Exception e) {
        logErr "Couldn't hand the device list to the app: ${e.message}"
    }
    subscribeNew(s)
    // On a live link the processor's pushes weren't applied while it was blocked: read the status now (a reconnect's
    // resync reads and subscribes it anyway)
    if (unblockedLive) refreshAfterReread(s)
}

// The zones', occupancy areas' and added keypads' LED states, read once the new routing is in. An LED status read
// answers as its subscription does (200, OneLEDStatus); a failure is quiet.
void refreshAfterReread(Map s) {
    if (!sessions[device.idAsLong].is(s)) return
    enqueue(s, [type: "ReadRequest", url: "/zone/status", then: "refreshZoneAnswer"])
    if (hasOccupancyDevices()) enqueue(s, [type: "ReadRequest", url: occupancyUrl(), then: "refreshAreaAnswer"])
    Set<String> have = childList().collect { it.deviceNetworkId.toString() } as Set
    ((Map) state.leds ?: [:]).findAll { k, v -> "${device.deviceNetworkId}/d/${v.d}".toString() in have }.keySet().sort().each { Object id ->
        enqueue(s, [type: "ReadRequest", url: "/led/${id}/status".toString(), then: "ledStatusRead", ctx: [quiet: true, led: id]])
    }
    logInfo "The processor's devices were read again: reading their status"
    pump(s)
}

// A refresh read that gets no good answer leaves the device as it was before the re-read: reconnect, and the resync
// reads and subscribes everything
void refreshZoneAnswer(Map s, Map j, Map p, String line) {
    if (j == null) { resyncIncomplete(s, "the zone status couldn't be read after the devices were read again"); return }
    zoneStatusAnswer(s, j, p, line)
}

void refreshAreaAnswer(Map s, Map j, Map p, String line) {
    if (j == null) { resyncIncomplete(s, "the occupancy status couldn't be read after the devices were read again"); return }
    areaStatusAnswer(s, j, p, line)
}

// An LED read that fails: its subscription's answer carries the state, so subscribe it again rather than reconnect
// (which would repeat if a processor didn't answer such reads)
void ledStatusRead(Map s, Map j, Map p, String line) {
    if (j?.Body?.LEDStatus instanceof Map) { ledEvent((Map) j.Body.LEDStatus); return }
    Object id = p?.ctx?.led
    if (id != null) { s.queue.add(0, subItem(s, "led", intOrNull(id))); pump(s) }
}

// An area's name, and its path without the project, from the areas the processor read
String pdAreaName(Map pd, Object href) { href ? pd.areas[hrefId(href).toString()]?.name : null }

String pdWhere(Map pd, Object href) {
    if (!href) return null
    Map names = pd.areas.collectEntries { k, v -> [(k): v.name] }, parents = pd.areas.collectEntries { k, v -> [(k): v.parent?.toString()] }
    return areaPath(hrefId(href).toString(), names, parents)
}

// Each zone's controlling device, from the device records' LocalZones (a list: a device with several zones gives each
// of them its type and model; a zone named by two devices keeps the first)
Map pdZoneDevices(Map pd) {
    Map zoneDev = [:]
    pd.devices.values().each { Map dv ->
        (dv.LocalZones ?: []).each { Map lz ->
            String zid = hrefId(lz.href)?.toString()
            if (zid == null) return
            if (zoneDev[zid] != null) { logDebug "Zone ${zid} is named by ${zoneDev[zid].href} and ${dv.href}: keeping ${zoneDev[zid].href}"; return }
            zoneDev[zid] = dv
        }
    }
    return zoneDev
}

// The zones for the app's list, with their device's type, model and href when known, sorted by zone id
List pdZoneList(Map pd, Map zoneDev) {
    return pd.zones.collect { Map z ->
        Map dv = (Map) zoneDev[hrefId(z.href)?.toString()]
        [id: hrefId(z.href), name: z.Name, control: z.ControlType, area: pdAreaName(pd, z.AssociatedArea?.href),
         areaId: z.AssociatedArea ? hrefId(z.AssociatedArea.href) : null, device: dv ? hrefId(dv.href) : null,
         model: dv?.ModelNumber, deviceType: dv?.DeviceType, deviceHref: dv?.href, location: pdWhere(pd, z.AssociatedArea?.href)]
    }.sort { it.id }
}

// From the control stations: [picos: [...], keypads: [...], occupancy: [areaId: [id:, name:, sensors:, location:]]]
Map pdStationLists(Map pd) {
    List picos = [], keypads = []
    Map occupancy = [:]
    pd.stations.each { Map st ->
        List ganged = (st.AssociatedGangedDevices ?: []).collect { it.Device }
        Integer areaId = st.AssociatedArea ? hrefId(st.AssociatedArea.href) : null
        ["pico", "keypad"].each { String kind ->
            List mine = ganged.findAll { deviceKind(it.DeviceType) == kind }
            mine.each { Map g ->
                Integer id = hrefId(g.href)
                Map d = (Map) pd.devices[id.toString()] ?: [:]
                String base = st.Name?.toString() ?: "Station"
                String name = (kind == "pico") ? "${base} Pico" : (base.contains("Keypad") ? base : "${base} Keypad")
                String pos = (d.Name?.toString() =~ /(\d+)$/) ? (d.Name.toString() =~ /(\d+)$/)[0][1] : null
                if (mine.size() > 1 && pos) name += " ${pos}"
                String loc = [pdWhere(pd, st.AssociatedArea?.href), st.Name?.toString()].findAll { it }.join(" > ")
                if (mine.size() > 1 && pos) loc += " (position ${pos})"
                Map e = [id: id, name: name.toString(), area: pdAreaName(pd, st.AssociatedArea?.href), areaId: areaId, deviceType: g.DeviceType,
                         model: d.ModelNumber, buttons: pd.buttons[id.toString()] ?: [], location: loc ?: null]
                if (kind == "pico") picos << e else keypads << e
            }
        }
        // The area's sensor devices too, so the app can follow a sensor moved to another area
        List<Integer> sensorIds = ganged.findAll { deviceKind(it.DeviceType) == "sensor" }.collect { hrefId(it.href) }.findAll { it != null }
        if (areaId != null && sensorIds) {
            Map o = (Map) occupancy[areaId] ?: [id: areaId, name: pdAreaName(pd, st.AssociatedArea?.href), sensors: [], location: pdWhere(pd, st.AssociatedArea?.href)]
            o.sensors = ((List) o.sensors + sensorIds).unique().sort()
            occupancy[areaId] = o
        }
    }
    return [picos: picos, keypads: keypads, occupancy: occupancy]
}

// What the bridge keeps to route status and events: zone control types, the button and LED maps, the areas with
// sensors, the project stamp
void pdSaveRouting(Map s, List zones, List picos, List keypads, Map occupancy) {
    state.zones = zones.collectEntries { [(it.id.toString()): it.control] }
    state.scenes = [:]
    Map btns = [:]
    picos.each { Map pc ->
        pc.buttons.each { Map b ->
            b.n = picoButton(pc.deviceType, b.number)
            btns[b.id.toString()] = [d: pc.id, n: b.n]
        }
    }
    // Keypads: each button is Lutron's own number (raise / lower 18 / 19 on a wall keypad), as the processor's
    // integration report and Hubitat's Lutron (Telnet) integration have them
    Map leds = [:]
    keypads.each { Map k ->
        k.buttons.each { Map b ->
            Integer n = b.number as Integer
            b.n = n
            btns[b.id.toString()] = [d: k.id, n: n, kp: true]
            if (b.led != null) leds[b.led.toString()] = [d: k.id, n: n]
        }
    }
    state.buttons = btns
    state.buttonLayout = BUTTON_LAYOUT
    state.leds = leds
    state.occAreas = occupancy.keySet().collect { it as Integer }.sort()
    if (s?.projectStamp) state.projectStamp = s.projectStamp
    state.discoveredAt = now()
}

/*******************************************************************
 ***** Requests: commands at once, reads one at a time; commands held while the link comes back
********************************************************************/
// Sends one request on the session and remembers it by its tag. p: what (hello|ping|keepalive|command|read),
// type, url, body, then (handler for reads), ctx, desc, tries. Returns the tag.
String sendRequest(Map s, Map p) {
    s.n = (s.n as Integer) + 1
    String prefix = [hello: "p", ping: "p", keepalive: "ka", command: "c"][p.what] ?: "r"
    String tag = "${prefix}${s.n}".toString()
    p.tag = tag
    p.sentAt = now()
    s.pending[tag] = p
    Map msg = [CommuniqueType: p.type, Header: [ClientTag: tag, Url: p.url]]
    if (p.body != null) msg.Body = p.body
    sendJson(msg)
    armCheck(s)
    return tag
}

void sendPing(Map s, String what) {
    sendRequest(s, [what: what, type: "ReadRequest", url: PING_URL])
    if (what == "keepalive") { s.pingSentAt = now(); s.pingAnswered = false }
}

// A command, sent at once. Returns false (and says why) when there's no session to send it on.
boolean command(String url, Map body, String desc, Map ctx = null, String type = "CreateRequest") {
    Map s0 = sessions[device.idAsLong]
    if (routingBlocked(desc)) return false
    boolean up = s0?.kind == "leap" && s0.phase == "up" && s0.verified     // verified: the bridge is the paired system
    // A flash step or restore isn't held: a restore that can't go stays in state.flashResume, for a resync to check
    if (!up && state.linkWanted != false && s0?.kind != "pair" && ctx?.flash == null && ctx?.restored == null) {
        holdCommand(s0, [url: url, body: body, desc: desc, ctx: ctx, type: type])
        return false
    }
    if (!ready(desc)) return false
    Map s = sessions[device.idAsLong]
    try {
        sendRequest(s, [what: "command", type: type, url: url, body: body, desc: desc, ctx: ctx])
    } catch (Exception e) {
        linkDropped("sendMessage threw ${e.message}")
        return false
    }
    return true
}

// The processor's routing can't be trusted until its devices are read again (pdDone clears it): nothing is sent, its
// pushes don't reach the devices, flashes stop, undo records and gestures under way are dropped (a reused number
// could name another load or button). Kept in state, so it lasts across reconnects.
void blockRouting() {
    state.routingStale = true
    sessions[device.idAsLong]?.remove("zonesSeen")       // the devices' levels count again only from the refreshed status
    state.remove("releaseButtons")                       // a transfer can make a button a scene button (Press only)
    state.remove("flashResume")
    new ArrayList(flashMap().keySet()).each { String k -> stopFlash(intOrNull(k), "the processor's project changed") }
    buttonStates.remove(device.idAsLong)                 // no released / pushed / held from a gesture on the old map
    unschedule("buttonHoldTimer")
    unschedule("buttonTapTimer")
}

// A processor's project changed and its devices haven't been read again yet: every zone, button and LED number may
// name another load, so nothing is sent (commands, presses, flashes) until the re-read installs the new routing
boolean routingBlocked(String desc) {
    if (!state.routingStale) return false
    logWarn "${desc} not sent (the processor's project changed: its devices are being read again)"
    return true
}

// A read or subscription, queued behind any in flight.
void queueRead(String type, String url, String then, Map ctx = null) {
    if (!ready("${type == 'SubscribeRequest' ? 'Subscribe' : 'Read'} ${url}")) return
    Map s = sessions[device.idAsLong]
    enqueue(s, [type: type, url: url, then: then, ctx: ctx])
    pump(s)
}

// True when the LEAP session is up. If not, says so, and starts a connect unless one is under way.
boolean ready(String what) {
    Map s = sessions[device.idAsLong]
    if (s?.kind == "leap" && s.phase == "up" && s.verified) return true
    if (s?.kind == "leap") { logWarn "Not connected yet (connecting): ${what} not sent"; return false }
    if (s?.kind == "pair") { logWarn "Pairing in progress: ${what} not sent"; return false }
    logWarn "Not connected: ${what} not sent; connecting"
    connectUnlessRetrying()
    return false
}

// A command with the link down starts a connect, unless reconnecting is already under way (a retry counted, or a probe
// out): then it joins that one, so its count keeps growing to the offline and look-for-the-bridge thresholds
void connectUnlessRetrying() {
    Map link = linkOf()
    if (state.linkWanted != false && (((link.attempt ?: 0) as Integer) > 0 || link.probeAt != null)) return
    runIn(1, "connect")
}

// A command while the link is down or coming up: held, and a connect started if none is under way (as ready() does)
void holdCommand(Map s, Map item) {
    List l = (List) heldCommands[device.idAsLong]
    if (l == null) { l = []; heldCommands[device.idAsLong] = l }
    item.at = now()
    l << item
    logInfo "Not connected: ${item.desc} held for up to ${HOLD_SECS} s" + (s == null ? "; connecting" : " (connecting)")
    if (s == null) connectUnlessRetrying()
    if (l.size() == 1) runIn(HOLD_SECS + 1, "heldExpire")     // the first held: later ones don't put off its expiry
}

// The link is up: send what is held and still fresh; drop the rest with the Warn
// At the identity check: zone and scene commands go at once. Button presses wait for the resync's button subscriptions
// (sendHeldPresses): a press's Press and Release reports are what give its event, and a Double Tap's second press goes
// on the first one's Release.
void sendHeld(Map s, boolean presses = false) {
    List all = (List) heldCommands.remove(device.idAsLong)
    if (!all) return
    List l = presses ? all : all.findAll { !isButtonPress(it) }
    List later = presses ? [] : all.findAll { isButtonPress(it) }
    if (later) heldCommands[device.idAsLong] = later              // heldExpire still drops them if the resync stalls
    else unschedule("heldExpire")
    if (!l) return
    long cutoff = now() - HOLD_SECS * 1000L
    List fresh = l.findAll { (it.at as Long) > cutoff }
    (l - fresh).each { logWarn "Not connected after ${HOLD_SECS} s: ${it.desc} not sent" }
    if (fresh) logInfo "Sending ${fresh.size()} held command${fresh.size() == 1 ? '' : 's'}"
    for (int i = 0; i < fresh.size(); i++) {
        Map it2 = (Map) fresh[i]
        // A Double Tap needs its first press's Release back to send the second: not without its button's subscription
        if (it2.ctx?.again && !buttonSubscribedNow(s, it2.url.toString().tokenize("/")[1])) {
            logWarn "${it2.desc}: Double Tap not sent (its button's subscription failed, so the first press's Release wouldn't come back)"
            continue
        }
        try {
            sendRequest(s, [what: "command", type: it2.type, url: it2.url, body: it2.body, desc: it2.desc, ctx: it2.ctx])
        } catch (Exception e) {
            // the socket is gone: this command and the ones after it aren't sent
            fresh.drop(i).each { logWarn "${it.desc} not sent (the connection dropped while sending held commands)" }
            linkDropped("sendMessage threw ${e.message}")
            return
        }
        // what command()'s callers record when they send: digital for the zone's push or the button's press
        List<String> parts = it2.url.toString().tokenize("/")            // [zone, 3, commandprocessor]
        if (parts.size() == 3 && parts[2] == "commandprocessor" && parts[0] == "zone") markZoneCommand(parts[1])
        else if (parts.size() == 3 && parts[2] == "commandprocessor" && parts[0] == "button") {
            Map bst = buttonState(parts[1])
            bst.sentAt = now()
            if (it2.ctx?.again) armAgain(bst, it2.desc, it2.ctx.gid)    // a Double Tap: its second press on the Release
        }
    }
}

void sendHeldPresses(Map s) {
    if (!s.verified) return
    s.pressesSent = true                                 // from now on a press goes at once
    sendHeld(s, true)
}

boolean isButtonPress(Map c) { c?.url?.toString()?.startsWith("/button/") }

// HOLD_SECS after a command was held with no link: drop what is too old; check again for any newer
void heldExpire() {
    List l = (List) heldCommands[device.idAsLong]
    if (!l) return
    long cutoff = now() - HOLD_SECS * 1000L
    List old = l.findAll { (it.at as Long) <= cutoff }
    old.each { logWarn "Not connected after ${HOLD_SECS} s: ${it.desc} not sent" }
    l.removeAll(old)
    if (!l) { heldCommands.remove(device.idAsLong); return }
    // the next check at the oldest left's deadline
    long next = (l.collect { it.at as Long }.min() as Long) + HOLD_SECS * 1000L - now()
    runInMillis(Math.max(1_000L, next + 500L), "heldExpire")
}

void enqueue(Map s, Map item) { s.queue << item }

// Sends the next queued read when none is in flight. Queued calls ([call: name]) run when reached.
void pump(Map s) {
    while (s.reading == null && s.queue && sessions[device.idAsLong].is(s)) {
        Map item = (Map) s.queue.remove(0)
        if (item.call) { "${item.call}"(s); continue }
        try {
            s.reading = sendRequest(s, [what: "read", type: item.type, url: item.url, then: item.then, ctx: item.ctx,
                                        tries: item.tries ?: 0])
        } catch (Exception e) {
            linkDropped("sendMessage threw ${e.message}")
            return
        }
    }
}

// Times out requests REPLY_SECS after they were sent. Scheduled for the oldest one in flight.
void armCheck(Map s) {
    if (s.checkArmed || !s.pending) return
    long oldest = s.pending.values().collect { it.sentAt as Long }.min()
    int secs = Math.max(1, (int) Math.ceil((oldest + REPLY_SECS * 1000L - now()) / 1000.0))
    s.checkArmed = true
    runIn(secs, "checkPending")
}

void checkPending() {
    Map s = sessions[device.idAsLong]
    if (s == null) { lostSession("Request check"); return }
    if (s.kind != "leap") return
    s.checkArmed = false
    long cutoff = now() - REPLY_SECS * 1000L
    List<String> late = s.pending.findAll { k, v -> (v.sentAt as Long) <= cutoff }.collect { it.key.toString() }
    for (String tag in late) {
        Map p = (Map) s.pending.remove(tag)
        if (p.what in ["hello", "ping", "keepalive"]) {
            String what = (p.what == "keepalive") ? "keep-alive ping" : (p.what == "hello" ? "first ping" : "ping")
            if (s.phase == "up") linkDropped("no answer to the ${what} in ${REPLY_SECS} s")
            else connectFailed("no answer to the ${what} in ${REPLY_SECS} s")
            return
        }
        if (p.what == "command") {
            logWarn "No answer to ${p.desc} (${p.url}) in ${REPLY_SECS} s"
            if (p.ctx?.again) clearAgain(p.url?.toString()?.tokenize("/")?.getAt(1), p.ctx.gid)
            if (p.ctx?.flash != null) flashUnanswered(intOrNull(p.ctx.flash), p.ctx.run, "no answer to a step in ${REPLY_SECS} s")
            // A flash's restore with no answer: the device still shows the flash's on, so the zone's status is read
            if (p.ctx?.restored != null) queueRead("ReadRequest", "/zone/${p.ctx.restored}/status".toString(), "zoneStatusAnswer")
            if (p.ctx?.readAfter != null) queueRead("ReadRequest", "/zone/${p.ctx.readAfter}/status".toString(), "zoneStatusAnswer")
        } else {
            if (s.reading == tag) s.reading = null
            String verb = (p.type == "SubscribeRequest") ? "Subscribe" : "Read"
            if ((p.tries as Integer) < 1) {
                logDebug "No answer to ${verb} ${p.url} in ${REPLY_SECS} s; trying once more"
                s.queue.add(0, [type: p.type, url: p.url, then: p.then, ctx: p.ctx, tries: 1])
            } else {
                String gaveUp = "No answer to ${verb} ${p.url} in ${REPLY_SECS} s, twice; giving up".toString()
                if (p.ctx?.quiet) logDebug gaveUp else logWarn gaveUp
                if (p.then) "${p.then}"(s, null, p, null)
            }
        }
    }
    if (sessions[device.idAsLong].is(s)) { pump(s); armCheck(s) }
}

/*******************************************************************
 ***** The LEAP session on 8081: connect, first ping, resync
********************************************************************/
// Every connect (hub start, the app, Ping, a command while down, the code-save recovery) asks first whether anything
// answers at the bridge's address (probeThenOpen): a rawSocket.connect to a host that silently drops packets blocks
// ~30 s and stops socket data for every driver on the hub, which the async request doesn't.
void connect() {
    // A pairing owns the session (its socket waits for the button): the link connects when it ends (resumeAfterPairing,
    // or the new certificate's trial)
    if (sessions[device.idAsLong]?.kind == "pair") { logInfo "Pairing under way: connecting once it ends"; return }
    state.linkWanted = true
    unschedule("connect")
    unschedule("reconnect")
    probeThenOpen(true)
}

// Opens the LEAP socket, once the probe has found something answering at the address. probed: the address that
// answered; when the app's address has changed since, that one is probed first.
void openLink(String probed = null) {
    Map creds = null
    try {
        creds = (Map) parent?.leapCredentials()
    } catch (Exception e) {
        logErr "Couldn't get the credentials from the app: ${e.message}"
    }
    if (!creds?.ip || !creds?.certificate || !creds?.privateKey || !creds?.caCertificate) {
        logWarn "Not connecting: the app has no pairing (pair from the app first)"
        stopLink("not paired")
        sendEventLog(name: "connection", value: "disconnected")
        setHealth("offline")
        return
    }
    Map link = linkOf()
    if (probed != null && probed != creds.ip.toString()) {
        logDebug "The address changed to ${creds.ip} while ${probed} was being checked; checking the new one"
        probeThenOpen(false)
        return
    }
    link.remove("replaceWanted")
    state.linkWanted = true
    unschedule("connect")
    unschedule("reconnect")
    // New credentials from a pairing are on trial until this link checks the bridge's identity (credentialsWorked)
    if (creds.provisional) state.provisional = true else state.remove("provisional")
    Map s = [kind: "leap", phase: "hello", n: 0, pending: [:], queue: [], reading: null, subs: [:], ip: creds.ip.toString()]
    Map opts = [secureSocket: true, certificate: creds.certificate, privateKey: creds.privateKey,
                caCertificate: creds.caCertificate, ignoreSSLIssues: false, eol: "\n"]
    if (device.currentValue("ipAddress")?.toString() != creds.ip.toString()) sendEventLog(name: "ipAddress", value: creds.ip.toString())
    logInfo "Connecting to ${creds.ip}:${LEAP_PORT}" + ((link.attempt as Integer) ? " (reconnect attempt ${link.attempt})" : "") + "..."
    sendEventLog(name: "connection", value: "connecting")
    String err = openSession(s, creds.ip.toString(), LEAP_PORT, opts)
    if (err) { connectFailed(err); return }
    try {
        sendPing(s, "hello")
    } catch (Exception e) {
        connectFailed("sendMessage threw ${e.message}")
    }
}

void leapLine(Map s, Map j, String line) {
    if (j == null) { logDebug "Not JSON: ${line.take(80)}"; return }
    String tag = j.Header?.ClientTag?.toString()
    Map p = tag ? (Map) s.pending.remove(tag) : null
    if (p != null) { answer(s, j, p, line); return }
    // Until the identity is checked the bridge may be another system: its pushes must not reach this app's devices
    if (!s.verified) { logDebug "Before the bridge's identity is checked: ${leapSummary(j)} (not applied)"; return }
    // The routing is blocked (the project changed): a push names its zone, button or LED by a number that may be reused
    if (state.routingStale) { logDebug "While the processor's devices are read again: ${leapSummary(j)} (not applied)"; return }
    if (tag && tag == s.zoneSub) { zoneStatusAnswer(s, j, null, line); return }     // processor zone changes
    if (tag && tag == s.areaSub) { areaStatusAnswer(s, j, null, line); return }     // processor occupancy
    // Caseta occupancy changes: routed by their body, whatever the message type or tag (no change has been captured)
    if (j.Body?.OccupancyGroupStatuses instanceof List) { areaStatusAnswer(s, j, null, line); return }
    if (j.Body?.LEDStatus instanceof Map && !(tag && s.subs[tag])) { ledEvent((Map) j.Body.LEDStatus); return }
    if (j.CommuniqueType == "UpdateResponse" && j.Body?.ButtonStatus instanceof Map) buttonEvent((Map) j.Body.ButtonStatus)
    if (tag && s.subs[tag]) { logLong("Event for ${s.subs[tag]}", line); return }
    if (j.Body?.ButtonStatus instanceof Map) return
    if (!tag && j.Body?.ZoneStatus instanceof Map) { zoneStatus((Map) j.Body.ZoneStatus); return }
    logDebug "Not handled: ${leapSummary(j)}"
}

void answer(Map s, Map j, Map p, String line) {
    long ms = now() - (p.sentAt as Long)
    String status = j.Header?.StatusCode?.toString() ?: "?"
    boolean ok = status.startsWith("2")
    switch (p.what) {
        case "hello": case "ping": case "keepalive":
            pingAnswer(s, j, p, ms)
            return
        case "command":
            String msg = "${p.desc}: ${status}, ${ms} ms"
            if (!ok) logWarn "${p.desc} failed: ${status}${j.Body?.Message ? ': ' + j.Body.Message : ''}"
            else if (p.ctx?.raw) logLong(msg, line)
            else logDebug msg
            if (p.ctx?.flash != null) flashAnswered(intOrNull(p.ctx.flash), p.ctx.run, intOrNull(p.ctx.seq), ok, status)
            // A Double Tap's first press refused: no Release will come, so no second press is due
            if (!ok && p.ctx?.again) clearAgain(p.url?.toString()?.tokenize("/")?.getAt(1), p.ctx.gid)
            // A scene: pushed once the bridge has taken it (204), whether it went at once or was held for the link
            if (ok && p.ctx?.scene != null) {
                sendEventLog(name: "pushed", value: intOrNull(p.ctx.scene), isStateChange: true, type: "digital",
                             desc: "Scene ${p.ctx.scene} (${p.ctx.name}) pressed")
            }
            // The device showed on through the flash; a restore that leaves the level where it was may get no status
            // push, so the zone's status is read once the restore is answered
            if (p.ctx?.readAfter != null) queueRead("ReadRequest", "/zone/${p.ctx.readAfter}/status".toString(), "zoneStatusAnswer")
            if (p.ctx?.restored != null) {
                clearFlashResume(intOrNull(p.ctx.restored), p.ctx.rid)   // this restore's result is known: its record goes
                queueRead("ReadRequest", "/zone/${p.ctx.restored}/status".toString(), "zoneStatusAnswer")
            }
            return
        default:                                    // a read or subscription
            if (s.reading == p.tag) s.reading = null
            String verb = (p.type == "SubscribeRequest") ? "Subscribe" : "Read"
            String failed = "${verb} ${p.url} failed: ${status}${j.Body?.Message ? ': ' + j.Body.Message : ''}".toString()
            if (!ok && p.ctx?.quiet) logDebug failed                // its handler says what the failure means
            else if (!ok) logWarn failed
            else logDebug "${verb} ${p.url}: ${status}, ${ms} ms, ${line.length()} characters"
            if (p.then) "${p.then}"(s, ok ? j : null, p, line)
            if (sessions[device.idAsLong].is(s)) pump(s)
    }
}

void pingAnswer(Map s, Map j, Map p, long ms) {
    Object v = (j.Body?.PingResponse instanceof Map) ? j.Body.PingResponse.LEAPVersion : null
    boolean ok = j.Header?.StatusCode?.toString()?.startsWith("2") && v != null
    if (!ok) {
        String what = (p.what == "keepalive") ? "keep-alive ping" : (p.what == "hello" ? "first ping" : "ping")
        if (p.what == "hello") connectFailed("unexpected answer to the first ping: ${leapSummary(j)}")
        // a live link whose ping fails is as dead as one whose ping goes unanswered (the timeout can't see it any more)
        else if (s.phase == "up") linkDropped("unexpected answer to the ${what}: ${leapSummary(j)}")
        else logWarn "Ping: unexpected answer: ${leapSummary(j)}"
        return
    }
    switch (p.what) {
        case "hello":
            s.phase = "up"
            Map link = linkOf()
            Integer attempts = link.attempt as Integer
            link.attempt = 0
            link.remove("fastUntil")
            logInfo "Connected: LEAP ${v}, ${ms} ms" + (attempts ? " (after ${attempts} reconnect attempt${attempts == 1 ? '' : 's'})" : "")
            dropBack(attempts)
            sendEventLog(name: "leapVersion", value: v.toString())
            sendEventLog(name: "connection", value: "resyncing")
            runIn(KEEPALIVE_SECS, "keepAlive")
            startResync(s)                                   // held commands go once the identity is checked
            break
        case "ping":
            logInfo "Ping: LEAP ${v}, ${ms} ms"
            break
        default:
            s.pingAnswered = true
            logDebug "Keep-alive answered in ${ms} ms"
    }
}

// After every connect, read the status of every zone (Caseta pushes only changes), with discovery first if it has never
// run, and subscribe the buttons. /project comes first: it tells a Caseta bridge from a processor.
void startResync(Map s) {
    enqueue(s, [type: "ReadRequest", url: "/project", then: "projectAnswer"])
    pump(s)
}

void projectAnswer(Map s, Map j, Map p, String line) {
    String pt = j?.Body?.Project?.ProductType?.toString()
    if (pt) { state.productType = pt; device.updateDataValue("productType", pt) }
    else if (state.productType) logInfo "Resync: no answer to /project; carrying on as the ${isProcessor() ? 'processor' : 'Caseta bridge'} last seen"
    else { resyncIncomplete(s, "no answer to /project, and the system's type isn't known yet"); return }
    s.project = j
    // Which system this is comes next: until it is checked, no command is sent and no push is applied
    enqueue(s, [type: "ReadRequest", url: isProcessor() ? "/device?where=IsThisDevice:true" : "/device", then: "identityAnswer"])
}

// The bridge's own serial number, checked by the app against the system its devices belong to: the same one carries on;
// another one with devices stops the link (its zone 3 isn't this app's zone 3); with no devices the app takes it, and
// the routing kept for the old system goes.
void identityAnswer(Map s, Map j, Map p, String line) {
    Map me = (Map) ((List) j?.Body?.Devices ?: []).find { it.IsThisDevice }
    if (me?.SerialNumber == null) { resyncIncomplete(s, "no serial number in the answer to ${p.url}"); return }
    String verdict
    try {
        verdict = parent?.bridgeIdentity(me.SerialNumber, s.ip)?.toString()   // s.ip: the address this session is on
    } catch (Exception e) {
        resyncIncomplete(s, "the app couldn't check the bridge's identity (${e.message})")
        return
    }
    if (verdict == "wrong") {
        if (state.provisional) {
            // A new pairing that reached another system: the app drops its certificate and keeps the pairing before,
            // which belongs to the right one, so the link goes back to it rather than stopping
            provisionalFailed("it is a different Lutron system from the one this app's devices belong to", true)
            logWarn "The new pairing is with another Lutron system: its certificate is dropped; connecting with the pairing before"
            endSession()
            sendEventLog(name: "connection", value: "disconnected")
            linkOf().attempt = 0
            runIn(1, "connect")
            return
        }
        wrongSystem()
        return
    }
    // Only an explicit verdict verifies the bridge: the app answers null for a serial it can't read
    if (!(verdict in ["ok", "new"])) {
        resyncIncomplete(s, "the app couldn't confirm the bridge's identity (${verdict ?: 'no verdict'})")
        return
    }
    if (state.provisional) {
        // The trial ends only when the app says it kept the new credentials (true): otherwise (its trial over, the store
        // failing) the link goes back to the pairing the app has
        Object kept = null
        try { kept = parent?.credentialsWorked() } catch (Exception e) { logErr "Couldn't tell the app the new credentials work: ${e.message}" }
        state.remove("provisional")
        state.remove("provisionalFails")
        if (kept != true) {
            logWarn "The app didn't keep the new certificate: connecting with the pairing it has"
            endSession()
            sendEventLog(name: "connection", value: "disconnected")
            linkOf().attempt = 0
            runIn(1, "connect")
            return
        }
    }
    if (verdict == "new") {
        // Another Lutron system, taken by the app: a held command names a zone or scene by its number on the old one
        dropHeld("another Lutron system: its zone and scene numbers aren't the old one's")
        forgetRouting()
    }
    s.verified = true
    setNetworkStatus("online")
    s.identity = j                                      // a discovery that follows uses it rather than reading it again
    // A processor whose project changed (a Designer transfer) may use a held command's zone or LED number for another load
    String stamp = (isProcessor() && s.project) ? projectStamp((Map) s.project) : null
    // The saved routing is verified only against a known modified time: with none from /project, or none kept with the
    // routing, whether the project changed can't be told, so it is handled as if it had (the devices read again, and the
    // re-read keeps the first modified time it gets)
    boolean unverified = isProcessor() && state.discoveredAt && (stamp == null || !state.projectStamp)
    if (unverified) logInfo(stamp == null ? "No modified time from /project: reading the processor's devices again before anything is sent"
                                          : "No modified time kept with the processor's devices: reading them again before anything is sent")
    if (unverified || (stamp && state.projectStamp && stamp != state.projectStamp)) {
        dropHeld("the processor's project changed or couldn't be checked: its numbers may mean other loads now")
        blockRouting()
    }
    sendHeld(s)
    resyncAfterIdentity(s, j, p, line)
}

void resyncAfterIdentity(Map s, Map j, Map p, String line) {
    if (isProcessor()) {
        // Processor: discovery by area, zone status only by subscription. A Designer transfer changes
        // ProjectModifiedTimestamp (and restarts the processor): read the devices again when it differs
        String stamp = s.project ? projectStamp((Map) s.project) : null
        s.projectStamp = stamp
        boolean changed = stamp && state.projectStamp && stamp != state.projectStamp
        if (!state.discoveredAt || changed || oldButtonLayout() || state.routingStale) {
            if (state.routingStale && !changed) logInfo "The devices haven't been read since the processor's project changed: reading them again"
            else if (changed && state.discoveredAt) logInfo "The processor's project changed (${state.projectStamp} -> ${stamp}): reading its devices again"
            else if (oldButtonLayout()) logInfo "The button map was saved with another numbering: reading the devices again"
            queueProcessorDiscovery(s)
        }
        enqueue(s, [call: "queueButtonSubs"])
        enqueue(s, [call: "sendHeldPresses"])            // once the buttons are subscribed: their reports come back
        enqueue(s, [call: "queueLedSubs"])
        enqueue(s, [type: "SubscribeRequest", url: "/zone/status", then: "zoneSubscribed"])
        enqueue(s, [call: "queueAreaSub"])
        enqueue(s, [call: "resyncDone"])
        pump(s)
        return
    }
    if (oldButtonLayout()) logInfo "The button map was saved with another numbering: reading the devices again"
    if (!state.discoveredAt || oldButtonLayout()) queueDiscovery(s, true)   // never read: discovery first
    else selfAnswer(s, j, p, line)                      // the /device just read: the serial and the change check
    enqueue(s, [call: "queueButtonSubs"])               // after discovery: the button map comes from it
    enqueue(s, [call: "sendHeldPresses"])               // once the buttons are subscribed: their reports come back
    enqueue(s, [type: "ReadRequest", url: "/zone/status", then: "resyncZoneStatus"])
    enqueue(s, [call: "queueAreaSub"])
    enqueue(s, [call: "resyncDone"])
    pump(s)
}

// Paired with another Lutron system that has its own devices: the routing kept for the old one (zones, buttons, LEDs,
// the change-check marks) goes, so the resync reads the new system's devices
void forgetRouting() {
    ["discoveredAt", "zones", "scenes", "buttons", "leds", "occAreas", "buttonLayout", "projectStamp", "deviceFingerprint",
     "flashResume", "routingStale", "releaseButtons"].each { state.remove(it) }      // routingStale: the old system's block goes with it
    logDebug "Another Lutron system: the device list and routing kept for the old one are gone"
}

// The bridge isn't the system this app's devices belong to: nothing more is sent, held commands are dropped, and the
// link stays down until the user acts (a new app instance for this system, or this app's devices removed)
void wrongSystem() {
    logWarn "This isn't the Lutron system this app's devices belong to (its serial number differs): nothing sent, not connecting again. Add it in a new app instance, or remove this app's devices first."
    stopLink("another Lutron system")
    sendEventLog(name: "connection", value: "disconnected")
    setHealth("offline")                                 // stopped for good: not available, the devices with it
}

// A step the resync can't do without (the system's type, its identity, a first device list, the zone status) failed:
// rather than report connected with stale or missing devices, reconnect, backing off while it keeps failing
void resyncIncomplete(Map s, String why) {
    resyncFailed(s) { int n -> "Resync incomplete (${why}): reconnecting (${n} in a row)".toString() }
}

// A resync that can't complete (resyncIncomplete, coreSubscriptionFailed): drop the link and reconnect, backing off
// (5, 15, 30 s...) while it keeps failing; offline from the second in a row (the first may be a restart under way).
// warning: the Warn, given how many in a row.
void resyncFailed(Map s, Closure warning) {
    if (!sessions[device.idAsLong].is(s)) return
    Map link = linkOf()
    int n = ((link.subFails ?: 0) as Integer) + 1
    link.subFails = n
    logWarn warning(n)
    if (n >= 2) setHealth("offline")
    endSession()
    sendEventLog(name: "connection", value: "disconnected")
    link.attempt = n - 1
    scheduleReconnect()
}

// The resync's zone status read (Caseta): without it the devices show the levels from before the drop
void resyncZoneStatus(Map s, Map j, Map p, String line) {
    if (j == null) { resyncIncomplete(s, "no zone status"); return }
    zoneStatusAnswer(s, j, p, line)
}

boolean isProcessor() { state.productType?.toString() in PROCESSOR_TYPES }

// A button map saved under another numbering than BUTTON_LAYOUT would give events the wrong button numbers until the
// next discovery; the resync reads the devices again instead
boolean oldButtonLayout() { state.discoveredAt && state.buttons && intOrNull(state.buttonLayout) != BUTTON_LAYOUT }

void resyncDone(Map s) {
    s.resynced = true
    linkOf().subFails = 0
    runIn(CHANGE_CHECK_SECS, "changeCheck")
    sendEventLog(name: "connection", value: "connected")
    setHealth("online")                                  // online only now: identity checked, devices and status read
    childrenHealth("online")
    resumeFlashes(s)
}

/*******************************************************************
 ***** Status subscriptions (processors) and occupancy; a refused zone subscription drops the link
********************************************************************/
// The /zone/status subscription (processors): its answer has every zone; its changes come tagged with it (leapLine)
void zoneSubscribed(Map s, Map j, Map p, String line) {
    if (j == null) { coreSubscriptionFailed(s, p.url?.toString()); return }
    s.zoneSub = p.tag
    zoneStatusAnswer(s, j, p, line)
}

// Occupancy is a status feed of its own (a processor: per area, /area/status; Caseta: per occupancy group,
// /occupancygroup/status), subscribed only while an occupancy device exists. Lighting and keypads don't depend on it,
// so a failure leaves the link up: one Warn per connection, and the change check (every CHANGE_CHECK_SECS) tries again.
void queueAreaSub(Map s) {
    if (s.areaSub || s.areaSubPending || !hasOccupancyDevices()) return
    s.areaSubPending = true
    s.queue.add(0, [type: "SubscribeRequest", url: occupancyUrl(), then: "areaSubscribed", ctx: [quiet: true]])
}

String occupancyUrl() { isProcessor() ? "/area/status" : "/occupancygroup/status" }

boolean hasOccupancyDevices() {
    String prefix = "${device.deviceNetworkId}/a/"
    return childList().any { it.deviceNetworkId?.toString()?.startsWith(prefix) }
}

// Occupancy, per area, from the /area/status subscription (its answer and its changes)
void areaSubscribed(Map s, Map j, Map p, String line) {
    s.remove("areaSubPending")
    if (j == null) {
        String m = "Subscribe ${p.url} failed: occupancy won't update until it succeeds (tried again every ${CHANGE_CHECK_SECS.intdiv(60)} minutes); lighting and keypads carry on".toString()
        if (s.areaWarned) logDebug m else logWarn m
        s.areaWarned = true
        return
    }
    s.areaSub = p.tag
    if (s.areaWarned) logInfo "Occupancy status subscribed"
    areaStatusAnswer(s, j, p, line)
}

// Lines with OccupancyStatus Occupied / Unoccupied go to the area's motion device; Unknown (no sensors, or not yet
// known), lines with only power or scene, and areas with no device are left out. So are areas the last device read found
// no sensors in: the processor keeps an emptied area's last status (Occupied, say, after its sensors moved).
// A processor's area statuses, or Caseta's occupancy group statuses: the same handling, keyed by the area or group id
void areaStatusAnswer(Map s, Map j, Map p, String line) {
    if (state.routingStale) { logDebug "Occupancy status not applied while the processor's devices are read again"; return }
    List occAreas = (List) state.occAreas          // null until the first device read: every area applies
    ((List) (j?.Body?.AreaStatuses ?: j?.Body?.OccupancyGroupStatuses ?: [])).each { Map st ->
        String occ = st.OccupancyStatus?.toString()
        if (!(occ in ["Occupied", "Unoccupied"])) return
        Integer area = hrefId(st.OccupancyGroup?.href ?: st.href)
        Object child = childOf("${device.deviceNetworkId}/a/${area}".toString())
        if (child == null) { logDebug "Area ${area}: ${occ} (no device)"; return }
        if (occAreas != null && !occAreas.any { (it as Integer) == area }) { logDebug "Area ${area}: ${occ} (no occupancy sensors any more)"; return }
        String v = (occ == "Occupied") ? "active" : "inactive"
        child.parse([[name: "motion", value: v, descriptionText: "${child.displayName} motion is ${v}".toString()]])
    }
}

// A refused (or twice unanswered) /zone/status subscription would leave the link up with no status
// coming: drop it and reconnect, backing off while it keeps failing (pylutron-caseta closes and reconnects when its
// re-login fails, for the same reason). A refused button or LED subscription Warns once and is subscribed again at the
// change check (subFailed): reconnecting can't fix a stale button list.
void coreSubscriptionFailed(Map s, String url) {
    resyncFailed(s) { int n -> "Subscribe ${url} failed: reconnecting so status keeps coming (${n} in a row)".toString() }
}

/*******************************************************************
 ***** Noticing changes on the bridge, and its identity
********************************************************************/
// The bridge's own entry in /device (IsThisDevice): its serial number goes to the app
void selfAnswer(Map s, Map j, Map p, String line) {
    Map me = (Map) ((List) j?.Body?.Devices ?: []).find { it.IsThisDevice }
    if (me?.SerialNumber != null) tellSerial(me.SerialNumber)
    if (isProcessor()) return
    // Caseta: devices added, removed, renamed or replaced in the Lutron app change this list
    String fp = deviceFingerprint(j)
    if (fp == null) return
    if (state.deviceFingerprint && fp != state.deviceFingerprint && state.discoveredAt && s.disc == null) {
        logInfo "The bridge's devices changed: reading them again"
        queueDiscovery(s, true, true)                                // j is the resync's identity read (s.identity)
    } else if (!state.deviceFingerprint && state.discoveredAt) {
        state.deviceFingerprint = fp                                  // none kept yet: keep this one
    }
}

// Between connects: a Caseta bridge doesn't restart for a change in the Lutron app, nor a processor for a rename there,
// and neither pushes one; so every CHANGE_CHECK_SECS the live link reads /project (processor) or /device (Caseta). On a
// change it reads the devices again on the live link and subscribes what's new.
void changeCheck() {
    checkLogsOff()
    Map s = sessions[device.idAsLong]
    if (s?.kind != "leap" || s.phase != "up") return
    enqueue(s, [type: "ReadRequest", url: isProcessor() ? "/project" : "/device", then: "changeCheckAnswer"])
    // Caseta: which sensors are in which room doesn't show in /device, nor do the scenes: each is checked on its own
    if (!isProcessor()) {
        enqueue(s, [type: "ReadRequest", url: OCCUPANCY_GROUPS_URL, then: "occupancyCheckAnswer", ctx: [quiet: true]])
        enqueue(s, [type: "ReadRequest", url: "/virtualbutton", then: "sceneCheckAnswer", ctx: [quiet: true]])
    }
    queueAreaSub(s)
    queueLedSubs(s)                                      // anything refused before: subscribed again
    queueButtonSubs(s)
    pump(s)
}

void changeCheckAnswer(Map s, Map j, Map p, String line) {
    if (!sessions[device.idAsLong].is(s)) return
    runIn(CHANGE_CHECK_SECS, "changeCheck")
    if (j == null) return
    if (isProcessor()) {
        String stamp = projectStamp(j)
        boolean changed = stamp && state.projectStamp && stamp != state.projectStamp
        boolean unverified = stamp && !state.projectStamp            // routing kept with no modified time to check it by
        if (!changed && !unverified && !oldButtonLayout()) return
        if (changed) logInfo "The processor's project changed (${state.projectStamp} -> ${stamp}): reading its devices again"
        else if (unverified) logInfo "No modified time kept with the processor's devices: reading them again"
        else logInfo "The button map was saved with another numbering: reading the devices again"
        s.projectStamp = stamp                                      // kept when the discovery completes
        if (changed || unverified) blockRouting()                   // its numbers may name other loads until then
        queueProcessorDiscovery(s)
    } else {
        String fp = deviceFingerprint(j)
        boolean changed = fp && state.deviceFingerprint && fp != state.deviceFingerprint
        if (!changed && !oldButtonLayout()) return
        logInfo(changed ? "The bridge's devices changed: reading them again" : "The button map was saved with another numbering: reading the devices again")
        queueDiscovery(s)
    }
    pump(s)
}

// Caseta, the change check's /occupancygroup read: a change in the groups with sensors (or a read that failed at the last
// discovery) reads the devices again. A failed read here changes nothing: the next check tries again.
void occupancyCheckAnswer(Map s, Map j, Map p, String line) {
    if (!sessions[device.idAsLong].is(s) || j == null || s.disc != null) return   // s.disc: a discovery already queued
    String fp = occFingerprint(j)
    // None kept yet (a device list from before occupancy was read): with groups that have sensors, or groups kept
    // with no fingerprint, the devices are read so the app gets the rooms; with none anywhere, the fingerprint is kept
    if (state.occFingerprint == null && !state.occRetry) {
        boolean anySensors = ((List) j.Body?.OccupancyGroups ?: []).any { it.AssociatedSensors && it.AssociatedAreas }
        if (!anySensors && !state.occAreas) { state.occFingerprint = fp; return }
    }
    if (!state.occRetry && fp == state.occFingerprint) return
    logInfo(state.occRetry ? "The occupancy groups weren't read last time: reading the devices again"
          : state.occFingerprint == null ? "The occupancy groups haven't been read with the devices yet: reading the devices again"
          : "The bridge's occupancy groups changed: reading the devices again")
    queueDiscovery(s)
    pump(s)
}

// Caseta, the change check's /virtualbutton read: programmed scenes other than the bridge's map (a scene added, renamed
// or deleted in the Lutron app) read the devices again; push and Activate Scene go only by that map. A failed read
// changes nothing: the next check tries again.
void sceneCheckAnswer(Map s, Map j, Map p, String line) {
    if (!sessions[device.idAsLong].is(s) || j == null || s.disc != null) return   // s.disc: a discovery already queued
    List vbs = (List) j.Body?.VirtualButtons
    if (vbs == null) return
    Map now = vbs.findAll { it.IsProgrammed }.collectEntries { [(hrefId(it.href).toString()): it.Name?.toString()] }
    Map kept = ((Map) state.scenes ?: [:]).collectEntries { k, v -> [(k.toString()): v?.toString()] }
    if (now == kept) return
    logInfo "The bridge's scenes changed: reading the devices again"
    queueDiscovery(s)
    pump(s)
}

// The groups with sensors in an /occupancygroup answer (group, room, sensors), as a short fingerprint
String occFingerprint(Map j) {
    List gs = ((List) j?.Body?.OccupancyGroups ?: []).findAll { it.AssociatedSensors && it.AssociatedAreas }
    String all = gs.collect { Map g ->
        "${g.href}|${g.AssociatedAreas[0]?.Area?.href}|${g.AssociatedSensors.collect { it.OccupancySensor?.href }.sort().join(',')}"
    }.sort().join(";")
    return "${gs.size()}:${Integer.toHexString(all.hashCode())}".toString()
}

// "YYYY-MM-DD hh:mm:ss" of /project's ProjectModifiedTimestamp (processors), or null
String projectStamp(Map j) {
    Map t = (Map) j?.Body?.Project?.ProjectModifiedTimestamp
    if (t == null) return null
    List<String> f = [t.Year, t.Month, t.Day, t.Hour, t.Minute, t.Second].collect { (it ?: 0).toString().padLeft(2, "0") }
    return "${f[0]}-${f[1]}-${f[2]} ${f[3]}:${f[4]}:${f[5]}".toString()
}

// Each device's href, name, type, room and full name (room and name: a room renamed changes it) in a /device answer, as
// a short fingerprint, or null
String deviceFingerprint(Map j) {
    List devs = (List) j?.Body?.Devices
    if (!devs) return null
    String all = devs.collect { "${it.href}|${it.Name}|${it.DeviceType}|${it.AssociatedArea?.href}|${(it.FullyQualifiedName ?: []).join('/')}" }.sort().join(";")
    return "${devs.size()}:${Integer.toHexString(all.hashCode())}".toString()
}

void tellSerial(Object serial) {
    try {
        parent?.bridgeIdentity(serial)
    } catch (Exception e) {
        logDebug "Couldn't give the app the serial number: ${e.message}"
    }
}

/*******************************************************************
 ***** The link: keep-alive, drops, reconnect after a probe, health
********************************************************************/
// A ping keeps data arriving, so the hub's read timeout doesn't drop the connection; a ping with no answer in
// REPLY_SECS means a dead link.
void keepAlive() {
    checkDriverVersion()
    Map s = sessions[device.idAsLong]
    // A driver code save clears the session map but not this job: the socket's answers go nowhere. This job
    // only runs while a link is up, so reconnect unless the user disconnected (linkWanted false).
    if (s == null && state.linkWanted != false) {
        logInfo "No session (driver code saved?); reconnecting"
        connect()
        return
    }
    if (s?.kind != "leap" || s.phase != "up") return
    try {
        sendPing(s, "keepalive")
    } catch (Exception e) {
        linkDropped("keep-alive sendMessage threw ${e.message}")
        return
    }
    runIn(KEEPALIVE_SECS, "keepAlive")
}

// The link was up and went down: reconnect at once (first attempt after RECONNECT_SECS[0]); healthStatus stays as it is
// until that attempt fails, so a blip doesn't flap every device.
void linkDropped(String why) {
    logWarn "Connection lost: ${why}"
    Map s = sessions[device.idAsLong]
    Map link = linkOf()
    boolean live = s != null && (now() - ((s.lastRxAt ?: s.startedAt ?: 0L) as Long)) <= FAST_RETRY_RECENT_SECS * 1000L
    if (why.contains("Stream closed") && live) {
        link.fastUntil = now() + FAST_RETRY_WINDOW_SECS * 1000L
        logInfo "The bridge closed the connection: retrying every ${FAST_RETRY_SECS} s for up to ${(int) (FAST_RETRY_WINDOW_SECS / 60)} minutes"
    } else {
        link.remove("fastUntil")
    }
    dropStart(why, s)
    endSession()
    sendEventLog(name: "connection", value: "disconnected")
    scheduleReconnect()
}

// A connect attempt failed (refused, TLS failure, socket closed or no answer before the first ping reply).
void connectFailed(String why) {
    Map link = linkOf()
    if (state.provisional) provisionalFailed(why, certFailure(why))
    endSession()
    sendEventLog(name: "connection", value: "disconnected")
    if ((link.attempt as Integer) > 0) setHealth("offline")
    logWarn "Connect failed: ${why}"
    dropCheck("connect failed: ${why.take(60)}")
    if (state.linkWanted) scheduleReconnect()
}

// New credentials that don't connect: a certificate failure at once, anything else after PROVISIONAL_TRIES connects. The
// app drops them and keeps the pairing it had; the next connect uses that one.
void provisionalFailed(String why, boolean now) {
    int n = ((state.provisionalFails ?: 0) as Integer) + 1
    state.provisionalFails = n
    if (!now && n < PROVISIONAL_TRIES) return
    state.remove("provisional")
    state.remove("provisionalFails")
    try { parent?.candidateFailed(why.take(160)) } catch (Exception e) { logErr "Couldn't tell the app the new credentials failed: ${e.message}" }
    linkOf().attempt = 0
}

void scheduleReconnect() {
    Map link = linkOf()
    if (state.linkWanted == false) return              // false only after Disconnect, pairing or Clear
    int n = link.attempt as Integer
    Integer secs = RECONNECT_SECS[Math.min(n, RECONNECT_SECS.size() - 1)]
    Long fastUntil = link.fastUntil as Long
    if (fastUntil != null) {
        Integer fast = Math.min(secs, FAST_RETRY_SECS)
        if (now() + fast * 1000L <= fastUntil) secs = fast   // only retries that fall inside the window
        else link.remove("fastUntil")
    }
    link.attempt = n + 1
    logInfo "Reconnecting in ${secs} s"
    runIn(secs, "reconnect")
    // n reconnects have failed: the bridge may have a new address
    if (n >= LOOK_AFTER) {
        try {
            parent?.bridgeUnreachable(n == LOOK_AFTER)
        } catch (Exception e) {
            logDebug "Couldn't ask the app to look for the bridge: ${e.message}"
        }
    }
}

// A scheduled reconnect: probed like every connect
void reconnect() {
    if (state.linkWanted == false) return
    // connected meanwhile (Connect now, the app), unless the session open is the one to replace (a new address)
    if (sessions[device.idAsLong] != null && linkOf().replaceWanted != true) return
    probeThenOpen(false)
}

// An async HTTPS request to the LEAP port, then openLink() if anything answered. One probe at a time. replace: an open
// session is replaced (the app's connect after a new address); a scheduled reconnect leaves one alone. The wish to
// replace lasts until a socket opens, so the retries after a failed probe keep it.
void probeThenOpen(boolean replace) {
    Map link = linkOf()
    if (replace) link.replaceWanted = true
    if (link.probeAt != null && now() - (link.probeAt as Long) < (PROBE_SECS + 5) * 1000L) return
    String ip = null
    try { ip = parent?.leapCredentials()?.ip?.toString() } catch (Exception e) { logDebug "Couldn't get the address from the app: ${e.message}" }
    if (!ip) { openLink(); return }                            // openLink() says what's missing
    link.probeAt = now()
    logDebug "Checking that ${ip}:${LEAP_PORT} answers before connecting"
    try {
        asynchttpGet("reconnectProbe", [uri: "https://${ip}:${LEAP_PORT}/".toString(), timeout: PROBE_SECS, ignoreSSLIssues: true],
                     [ip: ip, port: LEAP_PORT, at: now()])
    } catch (Exception e) {
        // no callback will come: not "under way" any more, and the usual retry takes over
        link.remove("probeAt")
        probeFailed("couldn't start the address check (${e.message?.take(80)})", "check not started")
    }
}

void reconnectProbe(Object resp, Map data) {
    Map lk = linkOf()
    boolean replace = lk.replaceWanted == true
    lk.remove("probeAt")
    if (state.linkWanted == false || (!replace && sessions[device.idAsLong] != null)) return
    String down = probeDown(resp)
    if (down == null) {
        logDebug "${data?.ip}:${data?.port} answered (${probeError(resp) ?: 'status ' + probeStatus(resp)}) in ${now() - ((data?.at ?: now()) as Long)} ms; connecting"
        openLink(data?.ip?.toString())
        return
    }
    probeFailed("${data?.ip}:${data?.port} doesn't answer (${down})", down)
}

// A reconnect's address check that failed (the address didn't answer, or the check couldn't start): offline after the
// first retry, a drop log note, and the next retry
void probeFailed(String why, String detail) {
    Map link = linkOf()
    // offline only with no link up: a check of a new address can fail while the link on the old one works
    Map s0 = sessions[device.idAsLong]
    boolean up = s0?.kind == "leap" && s0.phase == "up"
    if ((link.attempt as Integer) > 1 && !up) setHealth("offline")   // the first retry after a drop may find it rebooting
    logWarn "Not connecting: ${why}"
    String k = PROBE_DOWN.find { detail.toLowerCase().contains(it) }
    int at = k ? detail.toLowerCase().indexOf(k) : -1
    dropCheck(at >= 0 ? detail.substring(at, at + k.length()) : detail.take(40))
    scheduleReconnect()
}

// The probe's error when nothing at the address is listening: "connect timed out" (packets dropped), "No route to
// host" (nothing there), "Connection refused" (port closed). Anything else means the host answered on the port (a
// Caseta bridge: "Broken pipe (Write failed)"; an RA3 processor: "Read timed out"; or an HTTP status). Returns that
// error, or null.
String probeDown(Object resp) {
    String err = probeError(resp)
    String e = err?.toLowerCase() ?: ""
    return PROBE_DOWN.any { e.contains(it) } ? err : null
}

String probeError(Object resp) {
    try { return resp?.hasError() ? resp.getErrorMessage()?.toString() : null } catch (Exception e) { return null }
}

Object probeStatus(Object resp) {
    try { return resp?.status } catch (Exception e) { return null }
}

// The session map was cleared (a driver code save; the hub can clear it again ~10 s later) while the link should be
// coming up or up: whatever arrives for it goes nowhere. Reconnect, once (connection becomes "disconnected", so the
// next stray message doesn't).
void lostSession(String what) {
    if (state.linkWanted == false) return
    if (!(device.currentValue("connection") in ["connecting", "resyncing", "connected"])) return
    logWarn "Connection lost: ${what} with no session (driver code saved?); reconnecting"
    dropStart("${what} with no session (driver code saved?)", null)
    sendEventLog(name: "connection", value: "disconnected")
    linkOf().attempt = 0
    runIn(1, "connect")
}

// The commands held for the link to come up are dropped, each with a Warn
void dropHeld(String why) {
    ((List) heldCommands.remove(device.idAsLong) ?: []).each { logWarn "${it.desc} not sent (${why})" }
    unschedule("heldExpire")
}

// Ends any session and stops reconnecting (pairing, Disconnect, not paired).
void stopLink(String why) {
    Map link = linkOf()
    state.linkWanted = false
    link.attempt = 0
    link.remove("fastUntil")
    link.remove("replaceWanted")
    state.remove("dropOpen")                                  // a drop still open ends here, not back
    state.remove("flashResume")                               // a deliberate stop: no flash undo after it
    dropHeld(why)
    unschedule("connect")
    unschedule("reconnect")
    if (sessions[device.idAsLong]) logDebug "Closing the open session (${why})"
    endSession()
}

Map linkOf() {
    Map link = links[device.idAsLong]
    if (link == null) { link = [attempt: 0]; links[device.idAsLong] = link }
    return link
}

// Children go offline with the bridge; they come back online when the resync after a connect ends.
void setHealth(String value) {
    if (device.currentValue("healthStatus") == value) return
    if (value == "offline") logWarn "Bridge offline"
    else if (value == "online" && device.currentValue("healthStatus") == "offline") logInfo "Bridge back online"
    sendEventLog(name: "healthStatus", value: value, isStateChange: true)
    if (value == "offline") childrenHealth("offline")
}

/*******************************************************************
 ***** The drop log: state.drops, newest first, DROP_LOG_MAX entries
********************************************************************/
@Field static final Integer DROP_LOG_MAX = 10

// The link dropped: a new entry at once (the link may never come back), from what the session knew
void dropStart(String why, Map s) {
    long t = now()
    Map d = [at: t, why: why, checks: []]
    if (s != null) {
        d.rx = ((t - ((s.lastRxAt ?: s.startedAt ?: t) as Long)) / 1000.0).doubleValue()
        if (s.pingSentAt != null) { d.ping = ((t - (s.pingSentAt as Long)) / 1000.0).doubleValue(); d.answered = s.pingAnswered == true }
    }
    state.dropOpen = d
    state.drops = ([dropLine(d)] + ((List) state.drops ?: [])).take(DROP_LOG_MAX)
}

// A failed check or connect while the link is down
// One outage's checks: a repeat of the last one is counted ("connect timed out ×12"), and at most DROP_CHECKS_MAX are
// kept (the oldest dropped, counted in the first), so a long outage can't grow the entry without bound
@Field static final Integer DROP_CHECKS_MAX = 8

void dropCheck(String what) {
    Map d = (Map) state.dropOpen
    if (d == null) return
    List l = new ArrayList((List) d.checks ?: [])
    String last = l ? l[-1].toString() : null
    java.util.regex.Matcher m = last ? (last =~ /^(.*) ×(\d+)$/) : null
    String base = (m != null && m.matches()) ? m.group(1) : last
    int count = (m != null && m.matches()) ? (m.group(2) as Integer) : 1
    if (base == what) l[-1] = "${what} ×${count + 1}".toString()
    else l << what
    if (l.size() > DROP_CHECKS_MAX) {
        int dropped = ((d.checksDropped ?: 0) as Integer) + (l.size() - DROP_CHECKS_MAX)
        l = l.drop(l.size() - DROP_CHECKS_MAX)
        d.checksDropped = dropped
    }
    d.checks = l
    state.dropOpen = d
    dropRewrite(d)
}

// Connected again: the entry is complete
void dropBack(Integer attempts) {
    Map d = (Map) state.dropOpen
    if (d == null) return
    d.back = now()
    d.attempts = attempts ?: 0
    dropRewrite(d)
    state.remove("dropOpen")
}

void dropRewrite(Map d) {
    List l = (List) state.drops ?: []
    if (l) l[0] = dropLine(d) else l = [dropLine(d)]
    state.drops = l
}

String dropLine(Map d) {
    String tz = "yyyy-MM-dd HH:mm:ss"
    String line = "${new Date(d.at as Long).format(tz, location.timeZone)} ${d.why}"
    if (d.rx != null) line += "; last data ${String.format('%.1f', d.rx as Double)} s before"
    if (d.ping != null) {
        double ps = d.ping as Double
        if (d.answered && ps > KEEPALIVE_SECS + 5) line += "; no ping for ${String.format('%.1f', ps)} s (due every ${KEEPALIVE_SECS} s)"
        else line += "; last ping ${String.format('%.1f', ps)} s before, ${d.answered ? 'answered' : 'not answered'}"
    }
    if (d.back != null) {
        long secs = Math.round(((d.back as Long) - (d.at as Long)) / 1000.0)
        int n = (d.attempts ?: 0) as Integer
        line += "; back ${new Date(d.back as Long).format('HH:mm:ss', location.timeZone)} after ${secs} s" + (n ? ", ${n} attempt${n == 1 ? '' : 's'}" : "")
    } else line += "; not back yet"
    if (d.checks) line += " (checks: ${d.checksDropped ? "${d.checksDropped} earlier, " : ''}${((List) d.checks).join(', ')})"
    return line
}

/*******************************************************************
 ***** Helpers
********************************************************************/
Integer intOrNull(Object v) {
    try { return (v == null || v.toString().trim() == "") ? null : new BigDecimal(v.toString().trim()).intValue() } catch (Exception e) { return null }
}

BigDecimal decOrNull(Object v) {
    try { return (v == null || v.toString().trim() == "") ? null : new BigDecimal(v.toString().trim()) } catch (Exception e) { return null }
}

// hh:mm:ss, with hundredths when the time has a fraction (00:00:00.75, 00:00:02.5), as Lutron's own fade times are
String leapDuration(BigDecimal secs) {
    BigDecimal s = ((secs ?: 0G).max(0G)).setScale(2, java.math.RoundingMode.DOWN)
    long t = s.longValue()
    String whole = String.format("%02d:%02d:%02d", t.intdiv(3600), (t % 3600).intdiv(60), t % 60)
    BigDecimal frac = s - t
    return (frac.signum() == 0) ? whole : whole + frac.stripTrailingZeros().toPlainString().substring(1)
}

/*******************************************************************
 ***** Socket
********************************************************************/
// Closes any open session, then connects. Returns null, or why connect threw.
String openSession(Map s, String ip, Integer port, Map opts) {
    endSession()
    s.port = port
    s.startedAt = now()
    sessions[device.idAsLong] = s
    opts.bufferSize = SOCKET_BUFFER
    try {
        interfaces.rawSocket.connect(opts, ip, port)          // options map first
    } catch (Exception e) {
        sessions.remove(device.idAsLong)
        return "connect to ${ip}:${port} threw ${e.message ?: e.toString()}".toString()
    }
    return null
}

void endSession() {
    stopAllFlashes("the connection ended")
    closeGestures()
    setNetworkStatus("offline")
    sessions.remove(device.idAsLong)
    rxBuf.remove(device.idAsLong)
    unschedule("checkPending")
    unschedule("keepAlive")
    unschedule("pairTimeout")
    unschedule("changeCheck")
    try { interfaces.rawSocket.close() } catch (Exception ignored) { }
}

// A /project answer holds the project's name, the owner's own text, which can identify the home: the log gets its
// length, not its text.
// The answer is known by its Url, as text or as the hex the hub delivers ("Url":"/project").
@Field static final String PROJECT_URL = '"Url":"/project"'
@Field static final String PROJECT_URL_HEX = "2255726C223A222F70726F6A65637422"

boolean isProjectAnswer(String text) {
    String head = text.take(800)
    return head.contains(PROJECT_URL) || head.toUpperCase().contains(PROJECT_URL_HEX)
}

// What Trace shows of a chunk of socket data: its first 400 characters; only its length for a /project answer, and for
// any chunk that comes while /project is the read awaited (a chunk can begin mid-answer, with no Url to know it by)
String traceText(Map s, String msg) {
    Map due = (s?.reading != null) ? (Map) s.pending?.get(s.reading) : null
    if (isProjectAnswer(msg) || due?.url == "/project") return "project answer (${msg.length()} characters; its name not logged)"
    return msg.length() > 400 ? msg.take(400) + '...' : msg
}

void sendJson(Map msg) {
    String text = JsonOutput.toJson(msg)
    logTrace "send: ${text}"
    interfaces.rawSocket.sendMessage(text + "\r\n")
}

// Socket data arrives hex-encoded on the hub (no byteInterface set); the first chunk of a session
// decides. Lines end in CRLF; a chunk can hold part of a line or several.
void parse(String msg) {
    Map s = sessions[device.idAsLong]
    // Pairing traffic carries the signed certificate: never logged raw
    if (s?.kind != "pair") logTrace "parse: ${traceText(s, msg)}"
    if (s == null) { lostSession("Socket data"); return }
    s.lastRxAt = now()
    if (s.rxMode == null) s.rxMode = (msg ==~ /(?:[0-9A-Fa-f]{2})+/) ? "hex" : "text"
    if (s.rxMode == "hex") { parseHex(s, msg); return }
    String buf = (rxBuf[device.idAsLong] ?: "") + msg
    int nl
    while ((nl = buf.indexOf("\n")) >= 0) {
        String line = buf.substring(0, nl).replaceAll(/\r$/, "")
        buf = buf.substring(nl + 1)
        if (line.trim()) handleLine(s, line)
        if (!sessions[device.idAsLong].is(s)) { rxBuf.remove(device.idAsLong); return }   // session ended
    }
    if (buf.length() > RX_BUF_MAX) {
        logWarn "No line end in ${buf.length()} characters; dropping them"
        buf = ""
    }
    if (buf) rxBuf[device.idAsLong] = buf else rxBuf.remove(device.idAsLong)
}

void handleLine(Map s, String line) {
    if (s.kind == "pair" && line.contains("SigningResult")) logDebug "Received: signing result (${line.length()} characters; certificates not logged)"
    else if (isProjectAnswer(line)) logDebug "Received: project answer (${line.length()} characters; its name not logged)"
    else logDebug "Received: ${line.length() > 300 ? line.take(300) + '...' : line}"
    Map j = null
    try { j = (Map) new JsonSlurper().parseText(line) } catch (Exception ignored) { }
    if (s.kind == "pair") pairLine(s, j, line)
    else leapLine(s, j, line)
}

void socketStatus(String msg) {
    logDebug "socketStatus: ${msg}"
    Map s = sessions[device.idAsLong]
    boolean readTimeout = msg?.contains("Read timed out")
    // A socket left open by a driver code save keeps its read timeout, and the hub delivers it to this driver even after
    // a newer connect (closing the old socket first doesn't stop it). The hub's read timeout fires only after 30 s with
    // no data, so one that comes while a probe is under way, or sooner after this link's last data or connect, is an old
    // socket's.
    if (s == null && readTimeout && linkOf().probeAt != null) { logDebug "Read timed out while checking the address: an old socket's; ignored"; return }
    // While the pairing port is being checked no pairing socket is open yet: any socket status is an old socket's
    if (s?.kind == "pair" && s.phase == "probe") { logDebug "Socket status while checking the pairing port: an old socket's; ignored"; return }
    if (s != null && readTimeout) {
        long quiet = now() - ((s.lastRxAt ?: s.startedAt) as Long)
        if (quiet < STALE_TIMEOUT_MS) {
            logDebug "Read timed out ${String.format('%.1f', quiet / 1000.0)} s after this link's last data: an old socket's; ignored"
            return
        }
    }
    if (s == null) {
        // The socket of a session this driver no longer knows (cleared by a code save) while the link should
        // be coming up or up: reconnect. After a normal close connection is "disconnected".
        lostSession("Socket error (${msg})")
        return
    }
    String detail = "socketStatus: ${msg}"
    if (msg?.contains("Read timed out")) {
        long since = now() - ((s.lastRxAt ?: s.startedAt) as Long)
        detail += " (${String.format('%.1f', since / 1000.0)} s after data last arrived)"
    }
    if (s.kind == "pair") {
        if (s.phase == "button" && certFailure(msg) && pairRetry(s, msg)) return
        pairingFailed(detail, s.appAttempt)                 // a failed retry has already closed the session
    }
    else if (s.phase == "up") linkDropped(detail)
    else connectFailed(detail)
}

// Hex socket data: kept as hex until a line end (0A at a byte boundary), and only whole lines decoded as UTF-8. A chunk
// can end inside a multi-byte character (an é in a room name), which decoding chunk by chunk would corrupt.
void parseHex(Map s, String msg) {
    String buf = (rxBuf[device.idAsLong] ?: "") + msg.toUpperCase()
    int from = 0
    while (true) {
        int nl = buf.indexOf("0A", from)
        while (nl >= 0 && ((nl - from) % 2) != 0) nl = buf.indexOf("0A", nl + 1)    // only at a byte boundary
        if (nl < 0) break
        String line
        try {
            line = new String(HexUtils.hexStringToByteArray(buf.substring(from, nl)), "UTF-8").replaceAll(/\r$/, "")
        } catch (Exception e) {
            logWarn "Undecodable socket data (${e.message}): ${buf.substring(from, Math.min(nl, from + 80))}"
            line = ""
        }
        from = nl + 2
        if (line.trim()) handleLine(s, line)
        if (!sessions[device.idAsLong].is(s)) { rxBuf.remove(device.idAsLong); return }   // session ended
    }
    buf = buf.substring(from)
    if (buf.length() > 2 * RX_BUF_MAX) {
        logWarn "No line end in ${buf.length().intdiv(2)} bytes; dropping them"
        buf = ""
    }
    if (buf) rxBuf[device.idAsLong] = buf else rxBuf.remove(device.idAsLong)
}

// One-line summary of a LEAP message for logs: type, URL, status, exception message
String leapSummary(Map j) {
    if (j == null) return "(not JSON)"
    List<String> parts = [j.CommuniqueType, j.Header?.Url, j.Header?.StatusCode, j.Body?.Exception?.Message, j.Body?.Message]
        .findAll { it != null }.collect { it.toString() }
    return parts ? parts.join(" ") : "(no header)"
}

/*******************************************************************
 ***** Events
********************************************************************/
// One event sender: Info on change, Debug when unchanged; always sends
void sendEventLog(Map evt) {
    evt.descriptionText = evt.desc ?: "${evt.name} set to ${evt.value} ${evt.unit ?: ''}".trim()
    evt.remove("desc")
    if (device.currentValue(evt.name)?.toString() != evt.value?.toString() || evt.isStateChange) {
        logInfo "${evt.descriptionText}"
    } else {
        logDebug "${evt.descriptionText} [NOT CHANGED]"
    }
    sendEvent(evt)
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
        device.updateSetting("logLevel",[value:"3", type:"enum"])
        levelInfo.level = 3
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
    Integer level = settings.logLevel != null ? settings.logLevel as Integer : 1
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

/*******************************************************************
 ***** Lutron certificates (from pylutron-caseta assets.py, Apache-2.0)
********************************************************************/
// The Lutron root CA: an RA3 processor's pairing and LEAP certificates chain to it (pylutron-caseta pairing.py)
@Field static final String LUTRON_ROOT_CA_PEM = '''-----BEGIN CERTIFICATE-----
MIIH5DCCBMygAwIBAgIJAKk++JqaJetSMA0GCSqGSIb3DQEBCwUAMH8xCzAJBgNV
BAYTAlVTMQswCQYDVQQIDAJQQTELMAkGA1UEBwwCQ0IxHzAdBgNVBAoMFkx1dHJv
biBFbGVjdHJvbmljcyBJbmMxHzAdBgNVBAsMFkx1dHJvbiBFbGVjdHJvbmljcyBJ
bmMxFDASBgNVBAMMC2x1dHJvbi1yb290MB4XDTE2MDkyODE5NTk0MVoXDTM2MDky
MzE5NTk0MVowfzELMAkGA1UEBhMCVVMxCzAJBgNVBAgMAlBBMQswCQYDVQQHDAJD
QjEfMB0GA1UECgwWTHV0cm9uIEVsZWN0cm9uaWNzIEluYzEfMB0GA1UECwwWTHV0
cm9uIEVsZWN0cm9uaWNzIEluYzEUMBIGA1UEAwwLbHV0cm9uLXJvb3QwggMiMA0G
CSqGSIb3DQEBAQUAA4IDDwAwggMKAoIDAQDBZbMODMzm+qpsOF5hhQ272GUlOaKz
n5b5YxokSAoxY4TqQApb9/uRHIBuuGLntq0QhR0Y3b0lXBeJWzWC6zscZJrheUKW
+2aHVvU4ugPAAXK/WVI68adBSY1UP0BcO1paYrXONcuXQgdy2/GV1mo1b+bmjNFT
zeDopkUoBxivBDZZ7B5vFfbJSgSF47Xsz8cspCEUIaV1rZbaDYBzsimdvrKusJfZ
Pci+Cx71sZuKunGTCgwHduYFsBfYRgTG1ihNEASi2++Er67AcabUGaqVQr/kIrUD
sS9jB6uaqPgMajjwXiZPDm82tTHobbKSav7aq+kSBNIFyvhK5y+vAWoGeZr5WK7n
9EekO3x7LXc6XSCASuhzK6zquAGUBSQNEO3c7sZ1rIdNs1lBSkCSxs+Bl8eEHO8k
O20TqKzKF9bQtccNkFWtRKIhVLFxQt234P+XJtWvWKVOlkLCAo0QgDivFJQVnNKM
Hr2/CIsOLC+ZSWAYl0lZEJaszt7wjR9cc7DRizq9aoKcGlPRvxzobFoQ4H0Z8vIR
DQRUQWFaTTOGiEk7JKxqjXX8xuGZpoXWw8VX0gz3Y0Bz8sU58ZZbugmVjvnKKYzd
ueZ/9+FsaYX6CKdJDANEJf+fqfkGXwQGt8Ns7SeG0JyCdJ4K2ECoOURYS4P1vSY1
40L9OldDjsW2qhpSBPHppfJ4rPRUu5J9Ux3AX4Mz+ibl6MS3wRpP1Rg+9TLITK5o
3AYrJO6oMsYrQkQvc+k20ocD7Iq0522iyw62/DpKMsPZXHNTT/rqzIihkaZaR8aa
ZOgAKi5o398mcfsuv42f8DriYc0Gr+3btiDU7rINqM935YNIABBDtVT9Ybc5uPHa
wXLmAIx2yLjqYaRDhr01Sql6WGy8Y0HcI5lM1pw4Vpx+VKWG/QdORGtZgKySGZ0+
9bY9cRN9IBFz4J60xoqx0MsM5o6FqVDypDCB32KaobVZSAnHifwEGtJJimNIzHpY
jGCTzBHSpuZcvV2dVAuPTHzck37ifpNTUFUCAwEAAaNjMGEwHQYDVR0OBBYEFErb
2SmGkh+4kYe2twSie5+xaqRsMB8GA1UdIwQYMBaAFErb2SmGkh+4kYe2twSie5+x
aqRsMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/BAQDAgGGMA0GCSqGSIb3DQEB
CwUAA4IDAQAP9t0STrn0xENQEDNwRMQUBYTA/r7BmzXX6b0Ip6HW7QNmTkFc5pUb
KT86v6g8cJ7ls96JwdTu1kRQt4Qpbyvzp2p5YlRFnm3NTVVdcffcZNo95x9Z/1Cv
5xZgw0OKODwPBJLCyq5ET4W6WrIZucRVBs035YXIN+z3EzCxBj6O1lcjOTTHSFFR
jI3t3AGdkFo7tCBu5TFlNEFfmaqjse140vfGWJpRKyOT4ahzXLcVxzfg/SKRID3Q
2Rop4KqLNCddzz+UM+IiwyFkOfjrXWStW46cLUzM1k5GRrl0aBg5oqMCBY4/Eeh3
W0ZATsxxfg2Ly4FIO5d7/xiZqARFuurYe/2PSzVSPIKQrPVjDEekD+qQ0bRRQGvL
KBiMhYZHwnz/OEQl21PNp5rksuFjKG4/PimQ8V96jpbzzsOZuic3aScszgNUPbdI
0LYjCQ8xCOFPpFC4x1+rGubRjKEuGCvvYErVkX9rQlFRGPOp+k8bYTlIUKZeNsuL
KiZ4VH5+ZUAIf94DHayoo/SvBsQ5Qizb17KVRKil+vidUkMtndrNtjr3GWmH+nkn
PhRXBlekUy3dgRvTE8RFDOG9TYAN1Bs/uMgNc8Sg5Yz0SG96SLXVer2zsjmQ7tf9
6s+UVvrr+wlL7jSJCfJo6gaUQh1sD3umPXDS+Fq+J7tiRwOvP3cejo8dLyhesDun
FGIHlKmUCIwS/3Kzvd9OtAJMsmV9Q2B1dXudJloj6ADaAmVvhI/eMUncL9sXMJZH
3CCorh2OSZt0vtdA59osgDSMrsQZSMrtovrKgeFmP1Z0ENvo90Zenm7Bjn6Hw3Y/
GebIgSgoc149ElxjN4nagIqSJJHRrYq85sjTUSESvQUL1oi4R/VU+qMIRSHju/ZM
bkqONDohUc7/pg5rnLTZnnaQ09KvdF0yySx3hYph7L7MZWV/tF7O7yj1egRKh7lT
rgZOI7EiN4DPfTTpXoWVmIpiB/ouKp6uZ/Zrq00WthT8lUaBsFYaC3FDkkcxwdkk
lJ+cvdbUdsU=
-----END CERTIFICATE-----
'''

@Field static final String LAP_CA_PEM = '''-----BEGIN CERTIFICATE-----
MIIEsjCCA5qgAwIBAgIBATANBgkqhkiG9w0BAQ0FADCBlzELMAkGA1UEBhMCVVMx
FTATBgNVBAgTDFBlbm5zeWx2YW5pYTElMCMGA1UEChMcTHV0cm9uIEVsZWN0cm9u
aWNzIENvLiwgSW5jLjEUMBIGA1UEBxMLQ29vcGVyc2J1cmcxNDAyBgNVBAMTK0Nh
c2V0YSBMb2NhbCBBY2Nlc3MgUHJvdG9jb2wgQ2VydCBBdXRob3JpdHkwHhcNMTUx
MDMxMDAwMDAwWhcNMzUxMDMxMDAwMDAwWjCBlzELMAkGA1UEBhMCVVMxFTATBgNV
BAgTDFBlbm5zeWx2YW5pYTElMCMGA1UEChMcTHV0cm9uIEVsZWN0cm9uaWNzIENv
LiwgSW5jLjEUMBIGA1UEBxMLQ29vcGVyc2J1cmcxNDAyBgNVBAMTK0Nhc2V0YSBM
b2NhbCBBY2Nlc3MgUHJvdG9jb2wgQ2VydCBBdXRob3JpdHkwggEiMA0GCSqGSIb3
DQEBAQUAA4IBDwAwggEKAoIBAQDamUREO0dENJxvxdbsDATdDFq+nXdbe62XJ4hI
t15nrUolwv7S28M/6uPPFtRSJW9mwvk/OKDlz0G2D3jw6SdzV3I7tNzvDptvbAL2
aDy9YNp9wTub/pLF6ONDa56gfAxsPQnMBwgoZlKqNQQsjykiyBv8FX42h3Nsa+Bl
q3hjnZEdOAkdn0rvCWD605c0+VWWOWm2vv7bwyOsfgsvCPxooAyBhTDeA0JPjVE/
wHPfiDF3WqA8JzWv4Ibvkg1g33oD6lG8LulWKDS9TPBYF+cvJ40aFPMreMoAQcrX
uD15vaS7iWXKI+anVrBpqE6pRkwLhR+moFjv5GZ+9oP8eawzAgMBAAGjggEFMIIB
ATAMBgNVHRMEBTADAQH/MB0GA1UdDgQWBBSB7qznOajKywOtZypVvV7ECAsgZjCB
xAYDVR0jBIG8MIG5gBSB7qznOajKywOtZypVvV7ECAsgZqGBnaSBmjCBlzELMAkG
A1UEBhMCVVMxFTATBgNVBAgTDFBlbm5zeWx2YW5pYTElMCMGA1UEChMcTHV0cm9u
IEVsZWN0cm9uaWNzIENvLiwgSW5jLjEUMBIGA1UEBxMLQ29vcGVyc2J1cmcxNDAy
BgNVBAMTK0Nhc2V0YSBMb2NhbCBBY2Nlc3MgUHJvdG9jb2wgQ2VydCBBdXRob3Jp
dHmCAQEwCwYDVR0PBAQDAgG+MA0GCSqGSIb3DQEBDQUAA4IBAQB9UDVi2DQI7vHp
F2Lape8SCtcdGEY/7BV4a3F+Xp9WxpE4bVtwoHlb+HG4tYQk9LO7jReE3VBmzvmU
aj+Y3xa25PSb+/q6U6MuY5OscyWo6ZGwtlsrWcP5xsey950WLwW6i8mfIkqFf6uT
gPbUjLsOstB4p7PQVpFgS2rP8h50Psue+XtUKRpR+JSBrHXKX9VuU/aM4PYexSvF
WSHa2HEbjvp6ccPm53/9/EtOtzcUMNspKt3YzABAoQ5/69nebRtC5lWjFI0Ga6kv
zKyu/aZJXWqskHkMz+Mbnky8tP37NmVkMnmRLCfdCG0gHiq/C2tjWDfPQID6HY0s
zq38av5E
-----END CERTIFICATE-----
'''
@Field static final String LAP_CERT_PEM = '''-----BEGIN CERTIFICATE-----
MIIECjCCAvKgAwIBAgIBAzANBgkqhkiG9w0BAQ0FADCBlzELMAkGA1UEBhMCVVMx
FTATBgNVBAgTDFBlbm5zeWx2YW5pYTElMCMGA1UEChMcTHV0cm9uIEVsZWN0cm9u
aWNzIENvLiwgSW5jLjEUMBIGA1UEBxMLQ29vcGVyc2J1cmcxNDAyBgNVBAMTK0Nh
c2V0YSBMb2NhbCBBY2Nlc3MgUHJvdG9jb2wgQ2VydCBBdXRob3JpdHkwHhcNMTUx
MDMxMDAwMDAwWhcNMzUxMDMxMDAwMDAwWjB+MQswCQYDVQQGEwJVUzEVMBMGA1UE
CBMMUGVubnN5bHZhbmlhMSUwIwYDVQQKExxMdXRyb24gRWxlY3Ryb25pY3MgQ28u
LCBJbmMuMRQwEgYDVQQHEwtDb29wZXJzYnVyZzEbMBkGA1UEAxMSQ2FzZXRhIEFw
cGxpY2F0aW9uMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAyAOELqTw
WNkF8ofSYJ9QkOHAYMmkVSRjVvZU2AqFfaZYCfWLoors7EBeQrsuGyojqxCbtRUd
l2NQrkPrGVw9cp4qsK54H8ntVadNsYi7KAfDW8bHQNf3hzfcpe8ycXcdVPZram6W
pM9P7oS36jV2DLU59A/OGkcO5AkC0v5ESqzab3qaV3ZvELP6qSt5K4MaJmm8lZT2
6deHU7Nw3kR8fv41qAFe/B0NV7IT+hN+cn6uJBxG5IdAimr4Kl+vTW9tb+/Hh+f+
pQ8EzzyWyEELRp2C72MsmONarnomei0W7dVYbsgxUNFXLZiXBdtNjPCMv1u6Znhm
QMIu9Fhjtz18LwIDAQABo3kwdzAJBgNVHRMEAjAAMB0GA1UdDgQWBBTiN03yqw/B
WK/jgf6FNCZ8D+SgwDAfBgNVHSMEGDAWgBSB7qznOajKywOtZypVvV7ECAsgZjAL
BgNVHQ8EBAMCBaAwHQYDVR0lBBYwFAYIKwYBBQUHAwEGCCsGAQUFBwMCMA0GCSqG
SIb3DQEBDQUAA4IBAQABdgPkGvuSBCwWVGO/uzFEIyRius/BF/EOZ7hMuZluaF05
/FT5PYPWg+UFPORUevB6EHyfezv+XLLpcHkj37sxhXdDKB4rrQPNDY8wzS9DAqF4
WQtGMdY8W9z0gDzajrXRbXkYLDEXnouUWA8+AblROl1Jr2GlUsVujI6NE6Yz5JcJ
zDLVYx7pNZkhYcmEnKZ30+ICq6+0GNKMW+irogm1WkyFp4NHiMCQ6D2UMAIMfeI4
xsamcaGquzVMxmb+Py8gmgtjbpnO8ZAHV6x3BG04zcaHRDOqyA4g+Xhhbxp291c8
B31ZKg0R+JaGyy6ZpE5UPLVyUtLlN93V2V8n66kR
-----END CERTIFICATE-----
'''
@Field static final String LAP_KEY_PEM = '''-----BEGIN RSA PRIVATE KEY-----
MIIEpQIBAAKCAQEAyAOELqTwWNkF8ofSYJ9QkOHAYMmkVSRjVvZU2AqFfaZYCfWL
oors7EBeQrsuGyojqxCbtRUdl2NQrkPrGVw9cp4qsK54H8ntVadNsYi7KAfDW8bH
QNf3hzfcpe8ycXcdVPZram6WpM9P7oS36jV2DLU59A/OGkcO5AkC0v5ESqzab3qa
V3ZvELP6qSt5K4MaJmm8lZT26deHU7Nw3kR8fv41qAFe/B0NV7IT+hN+cn6uJBxG
5IdAimr4Kl+vTW9tb+/Hh+f+pQ8EzzyWyEELRp2C72MsmONarnomei0W7dVYbsgx
UNFXLZiXBdtNjPCMv1u6ZnhmQMIu9Fhjtz18LwIDAQABAoIBAQCXDtDNyZQcBgwP
17RzdN8MDPOWJbQO+aRtES2S3J9k/jSPkPscj3/QDe0iyOtRaMn3cFuor4HhzAgr
FPCB/sAJyJrFRX9DwuWUQv7SjkmLOhG5Rq9FsdYoMXBbggO+3g8xE8qcX1k2r7vW
kDW2lRnLDzPtt+IYxoHgh02yvIYnPn1VLuryM0+7eUrTVmdHQ1IGS5RRAGvtoFjf
4QhkkwLzZzCBly/iUDtNiincwRx7wUG60c4ZYu/uBbdJKT+8NcDLnh6lZyJIpGns
jjZvvYA9kgCB2QgQ0sdvm0rA31cbc72Y2lNdtE30DJHCQz/K3X7T0PlfR191NMiX
E7h2I/oBAoGBAPor1TqsQK0tT5CftdN6j49gtHcPXVoJQNhPyQldKXADIy8PVGnn
upG3y6wrKEb0w8BwaZgLAtqOO/TGPuLLFQ7Ln00nEVsCfWYs13IzXjCCR0daOvcF
3FCb0IT/HHym3ebtk9gvFY8Y9AcV/GMH5WkAufWxAbB7J82M//afSghPAoGBAMys
g9D0FYO/BDimcBbUBpGh7ec+XLPaB2cPM6PtXzMDmkqy858sTNBLLEDLl+B9yINi
FYcxpR7viNDAWtilVGKwkU3hM514k+xrEr7jJraLzd0j5mjp55dnmH0MH0APjEV0
qum+mIJmWXlkfKKIiIDgr6+FwIiF5ttSbX1NwnYhAoGAMRvjqrXfqF8prEk9xzra
7ZldM7YHbEI+wXfADh+En+FtybInrvZ3UF2VFMIQEQXBW4h1ogwfTkn3iRBVje2x
v4rHRbzykjwF48XPsTJWPg2E8oPK6Wz0F7rOjx0JOYsEKm3exORRRhru5Gkzdzk4
lok29/z8SOmUIayZHo+cV88CgYEAgPsmhoOLG19A9cJNWNV83kHBfryaBu0bRSMb
U+6+05MtpG1pgaGVNp5o4NxsdZhOyB0DnBL5D6m7+nF9zpFBwH+s0ftdX5sg/Rfs
1Eapmtg3f2ikRvFAdPVf7024U9J4fzyqiGsICQUe1ZUxxetsumrdzCrpzh80AHrN
bO2X4oECgYEAxoVXNMdFH5vaTo3X/mOaCi0/j7tOgThvGh0bWcRVIm/6ho1HXk+o
+kY8ld0vCa7VvqT+iwPt+7x96qesVPyWQN3+uLz9oL3hMOaXCpo+5w8U2Qxjinod
uHnNjMTXCVxNy4tkARwLRwI+1aV5PMzFSi+HyuWmBaWOe19uz3SFbYs=
-----END RSA PRIVATE KEY-----
'''
