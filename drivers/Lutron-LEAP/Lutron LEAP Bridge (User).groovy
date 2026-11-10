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

@Field static final String VERSION = "1.0.0"
@Field static final String DRIVER  = "Lutron LEAP Bridge (User)"

@Field static final Integer PAIRING_PORT = 8083
@Field static final Integer LEAP_PORT = 8081
@Field static final Integer REPLY_SECS = 10            // a request with no answer by then has failed
@Field static final Integer BUTTON_SECS = 180          // time to press the bridge button (pylutron-caseta)
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
// When we last sent a command to each zone, keyed by device id: [zoneId (String): ms]
@Field static ConcurrentHashMap<Long, Map> zoneCommands = new ConcurrentHashMap<>()
// Pico button timing, keyed by device id: [buttonId (String): [seq:, down:, held:, second:, tapUntil:]]
@Field static ConcurrentHashMap<Long, Map> buttonStates = new ConcurrentHashMap<>()
// Zones flashing, keyed by device id: [zoneId (String): [rate:, next: 0|100, seq:, sentAt:, name:]].
// Cleared with the session: a driver code save or a lost connection ends every flash.
@Field static ConcurrentHashMap<Long, Map> flashes = new ConcurrentHashMap<>()
@Field static final Integer FLASH_MIN_MS = 500
@Field static final Integer FLASH_MAX_MS = 60000
// The bridge reports our own press's Press 60-110 ms after the request
@Field static final Long PRESS_DIGITAL_MS = 2000L
@Field static final Integer HOLD_MS = 1000             // quick presses take up to ~440 ms, holds ~3 s
// A Pico on this driver gets pushed at Press and released at Release, nothing timed
@Field static final String FAST_PICO = "Lutron LEAP Fast Pico (User)"

metadata {
    definition(name: DRIVER, namespace: "draperw", author: "Bill Draper", singleThreaded: true,
               importUrl: "https://raw.githubusercontent.com/draperw/Hubitat/main/drivers/Lutron-LEAP/Lutron%20LEAP%20Bridge%20(User).groovy") {
        capability "Actuator"
        capability "Initialize"
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
    state.driverVersion = VERSION
    sendEventLog(name: "connection", value: "disconnected")
    sendEventLog(name: "healthStatus", value: "unknown")
    sendEventLog(name: "numberOfButtons", value: 0)
}

void updated() {
    logDebug "updated..."
    checkDriverVersion()
    checkLogLevel()
}

// Hub start (capability Initialize) and driver code saves: connect if the app has credentials.
void initialize() {
    checkDriverVersion()
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
 ***** Called by the app: pairing and connecting
********************************************************************/
// Starts pairing: connect to the pairing port with the LAP certificate and wait for the bridge button.
// ca: the CA the pairing certificate is checked against: "lap" (Caseta) or "lutron" (the Lutron root, for a processor,
// as in pylutron-caseta); a certificate failure tries the other one once (pairRetry). Returns null when started, or
// why it couldn't start; reports what happens next to the app from this device's own executions
// (parent.pairingFailed / parent.pairingResult), never from inside this call.
String startPairing(String ip, String csrPem, String displayName, String ca = "lap") {
    checkDriverVersion()
    if (!ip?.trim()) return "no bridge IP address"
    if (!csrPem?.contains("BEGIN CERTIFICATE REQUEST")) return "no CSR"
    stopLink("pairing")
    Map s = [kind: "pair", phase: "button", csr: csrPem, displayName: displayName ?: "hubitat_leap", ip: ip.trim(),
             ca: (ca == "lutron") ? "lutron" : "lap", retried: false]
    String err = openPairing(s)
    if (err && certFailure(err)) err = pairRetry(s, err) ? null : err
    if (err) {
        sendEventLog(name: "connection", value: "disconnected")
        return err
    }
    sendEventLog(name: "connection", value: "pairing")
    logInfo "Press the small black button on the back of the bridge within ${BUTTON_SECS} s"
    runIn(BUTTON_SECS, "pairTimeout")
    return null
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
    Map n = [kind: "pair", phase: "button", csr: s.csr, displayName: s.displayName, ip: s.ip, ca: other, retried: true]
    String err = openPairing(n)
    if (err) { logWarn "Pairing: ${err}"; return false }
    return true
}

void cancelPairing() {
    if (sessions[device.idAsLong]?.kind != "pair") return
    endSession()
    sendEventLog(name: "connection", value: "disconnected")
    logInfo "Pairing cancelled"
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
    logInfo "Disconnected (no reconnect until Initialize, Ping, a command or the app connects)"
}

// Reads the bridge's devices again and hands the list to the app.
void discover() {
    checkDriverVersion()
    if (!ready("Discover")) return
    Map s = sessions[device.idAsLong]
    logInfo "Discover: reading the ${isProcessor() ? 'processor' : 'bridge'}'s devices"
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
    if (pressScene(id, name)) {
        sendEventLog(name: "pushed", value: id, isStateChange: true, type: "digital", desc: "Scene ${id} (${name}) pressed")
    }
}

String sceneList() {
    Map sc = state.scenes ?: [:]
    return sc ? "scenes: " + sc.collect { k, v -> "${k} ${v}" }.join(", ") : "no scenes read yet: Discover"
}

boolean pressScene(Integer id, String name) {
    return command("/virtualbutton/${id}/commandprocessor".toString(), [Command: [CommandType: "PressAndRelease"]],
                   "Scene ${id} (${name})".toString())
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
    stopFlash(z, "Set Zone Level")
    zoneCommand(z, cmd, true)
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
    newSubs(s, "subButtons", btns).each { enqueue(s, [type: "SubscribeRequest", url: "/button/${it}/status/event".toString(), then: "buttonSubscribed"]) }
    List<Integer> ledIds = ((Map) state.leds ?: [:]).findAll { k, v -> intOrNull(v.d) in picoIds }.collect { k, v -> k as Integer }.sort()
    newSubs(s, "subLeds", ledIds).each { enqueue(s, [type: "SubscribeRequest", url: "/led/${it}/status".toString(), then: "ledSubscribed"]) }
    if (zones) enqueue(s, [type: "ReadRequest", url: "/zone/status", then: "zoneStatusAnswer"])
    if (all.any { it.startsWith(prefix + "a/") }) enqueue(s, [type: "ReadRequest", url: "/area/status", then: "areaStatusAnswer"])
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
void componentOn(Object cd)  { Integer z = zoneOf(cd); if (z != null) { stopFlash(z, "On"); zoneLevel(z, 100, null) } }

void componentOff(Object cd) { Integer z = zoneOf(cd); if (z != null) { stopFlash(z, "Off"); zoneLevel(z, 0, null) } }

void componentSetLevel(Object cd, Object level, Object secs = null) {
    Integer z = zoneOf(cd)
    Integer lvl = intOrNull(level)
    if (z == null) return
    if (lvl == null) { logWarn "${cd.displayName}: set level ${level}?"; return }
    stopFlash(z, "Set Level")
    zoneLevel(z, lvl, secs)
}

void componentStartLevelChange(Object cd, Object direction) {
    Integer z = zoneOf(cd)
    if (z == null) return
    stopFlash(z, "Start Level Change")
    if (zoneControl(z) == "Switched") { logDebug "${cd.displayName}: a switch has no level change"; return }
    zoneCommand(z, [CommandType: direction?.toString() == "up" ? "Raise" : "Lower"])
}

void componentStopLevelChange(Object cd) {
    Integer z = zoneOf(cd)
    if (z == null) return
    stopFlash(z, "Stop Level Change")
    if (zoneControl(z) == "Switched") return
    zoneCommand(z, [CommandType: "Stop"])
}

void componentRefresh(Object cd) {
    String dni = cd?.deviceNetworkId?.toString() ?: ""
    if (dni.startsWith("${device.deviceNetworkId}/a/")) { queueRead("ReadRequest", "/area/status", "areaStatusAnswer"); return }   // occupancy
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
void zoneLevel(Integer z, Integer level, Object secs) {
    int lvl = Math.max(0, Math.min(100, level))
    if (zoneControl(z) == "Switched") { zoneCommand(z, goToLevel(lvl > 0 ? 100 : 0)); return }
    BigDecimal fade = decOrNull(secs)
    if (fade != null && fade > 0) {
        zoneCommand(z, [CommandType: "GoToDimmedLevel", DimmedLevelParameters: [Level: lvl, FadeTime: leapDuration(fade)]])
    } else {
        zoneCommand(z, goToLevel(lvl))
    }
}

Map goToLevel(int lvl) { [CommandType: "GoToLevel", Parameter: [[Type: "Level", Value: lvl]]] }

void zoneCommand(Integer z, Map cmd, boolean raw = false, Map ctx = null) {
    String what = "Zone ${z} ${cmd.CommandType}" + (cmd.Parameter ? " ${cmd.Parameter[0].Value}" : "") +
                  (cmd.DimmedLevelParameters ? " ${cmd.DimmedLevelParameters.Level} over ${cmd.DimmedLevelParameters.FadeTime}" : "")
    Map c = raw ? [raw: true] : null
    if (ctx) c = (c ?: [:]) + ctx
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
    Object child = childOf("${device.deviceNetworkId}/z/${z}".toString())
    if (child == null) { logDebug "Zone ${z}: level ${lvl} (no device)"; return }
    Long at = (Long) zoneCommands[device.idAsLong]?.get(z.toString())
    String type = (at != null && now() - at <= DIGITAL_MS) ? "digital" : "physical"
    String sw = lvl > 0 ? "on" : "off"
    String name = child.displayName
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
void componentFlash(Object cd, Object rateMs = null) {
    Integer z = zoneOf(cd)
    if (z == null) return
    int rate = Math.max(FLASH_MIN_MS, Math.min(FLASH_MAX_MS, intOrNull(rateMs) ?: 750))
    Map f = flashMap()
    Map e = (Map) f[z.toString()]
    if (e != null) {
        e.rate = rate
        logInfo "${cd.displayName}: flashing every ${rate} ms"
        return
    }
    if (!ready("${cd.displayName}: Flash")) return
    Object child = childOf("${device.deviceNetworkId}/z/${z}".toString())
    int first = (child?.currentValue("switch")?.toString() == "on") ? 0 : 100
    f[z.toString()] = [rate: rate, next: first, seq: 0, sentAt: 0L, name: cd.displayName?.toString()]
    logInfo "${cd.displayName}: flashing every ${rate} ms (any other command stops it)"
    flashStep(z)
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
    int lvl = e.next as Integer
    e.next = (lvl == 0) ? 100 : 0
    e.seq = (e.seq as Integer) + 1
    e.sentAt = now()
    zoneCommand(z, goToLevel(lvl), false, [flash: z, seq: e.seq])
}

// The answer to a flash step: the next step at the rate after the last was sent, or at once if the answer took longer
void flashAnswered(Integer z, Integer seq, boolean ok, String status) {
    Map e = (Map) flashMap()[z.toString()]
    if (e == null || (e.seq as Integer) != seq) return
    if (!ok) { stopFlash(z, "the bridge answered ${status}", true); return }
    long wait = (e.sentAt as Long) + (e.rate as Integer) - now()
    if (wait <= 0) flashStep(z)
    else runInMillis(wait, "flashTimer", [data: [zone: z, seq: seq], overwrite: false])
}

void flashTimer(Map data) {
    Integer z = intOrNull(data?.zone)
    Map e = (z != null) ? (Map) flashMap()[z.toString()] : null
    if (e == null || (e.seq as Integer) != intOrNull(data.seq)) return
    flashStep(z)
}

// Ends the zone's flash, if there is one; says why (a Warn when something went wrong)
void stopFlash(Integer z, String why, boolean warn = false) {
    Map e = (Map) flashes[device.idAsLong]?.remove(z.toString())
    if (e == null) return
    String m = "${e.name ?: 'Zone ' + z}: flash stopped (${why})"
    if (warn) logWarn m else logInfo m
}

void stopAllFlashes(String why) {
    Map f = flashes[device.idAsLong]
    if (!f) return
    new ArrayList(f.keySet()).each { String k -> stopFlash(intOrNull(k), why) }
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
    Map st = buttonState(bid)
    if (pressButton(bid, desc) && intOrNull(times) == 2) { st.again = true; st.againDesc = desc }
}

boolean pressButton(Object bid, String desc) {
    if (!command("/button/${bid}/commandprocessor".toString(), [Command: [CommandType: "PressAndRelease"]], desc)) return false
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
    s.queue.addAll(0, ids.collect { [type: "SubscribeRequest", url: "/button/${it}/status/event".toString(), then: "buttonSubscribed"] })
}

// The ids not yet subscribed on this connection (kept in the session, so a reconnect starts again), now marked as subscribed
List<Integer> newSubs(Map s, String key, List<Integer> ids) {
    List done = (List) (s[key] ?: [])
    List<Integer> fresh = ids.findAll { !(it in done) }
    s[key] = done + fresh
    return fresh
}

void buttonSubscribed(Map s, Map j, Map p, String line) { }

// The LEDs of every added keypad, at the front of the queue (after its buttons)
void queueLedSubs(Map s) {
    Map leds = state.leds ?: [:]
    Set<String> have = childList().collect { it.deviceNetworkId.toString() } as Set
    List<Integer> ids = leds.findAll { k, v -> "${device.deviceNetworkId}/d/${v.d}".toString() in have }.collect { k, v -> k as Integer }.sort()
    ids = newSubs(s, "subLeds", ids)
    s.queue.addAll(0, ids.collect { [type: "SubscribeRequest", url: "/led/${it}/status".toString(), then: "ledSubscribed"] })
}

void ledSubscribed(Map s, Map j, Map p, String line) { if (j?.Body?.LEDStatus instanceof Map) ledEvent((Map) j.Body.LEDStatus) }

// An LED's state (its subscription's answer or a change) to its keypad: [name: led, button: n, value: on / off]
void ledEvent(Map st) {
    Integer id = hrefId(st.LED?.href ?: st.href)
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
    Integer tapMs = intOrNull(child.getSetting("doubleTapMs")) ?: 0
    Map st = buttonState(bid)
    long t = now()
    if (ev == "Press") {
        st.digital = st.sentAt != null && t - (st.sentAt as Long) <= PRESS_DIGITAL_MS
        boolean second = tapMs > 0 && st.tapUntil != null && t <= (st.tapUntil as Long)
        st.seq = (st.seq as Integer) + 1                  // cancels a pending double-tap timer
        st.down = true; st.held = false; st.second = second; st.tapUntil = null
        runInMillis(holdMs, "buttonHoldTimer", [data: [b: bid, seq: st.seq], overwrite: false])
    } else if (ev == "Release") {
        if (!st.down) return
        st.down = false
        st.seq = (st.seq as Integer) + 1                  // cancels the hold timer
        if (st.again) { st.remove("again"); pressButton(bid, st.againDesc?.toString()) }   // Double Tap's second press
        if (st.held) { buttonOut(child, n, "released", st); return }
        if (st.second) {
            buttonOut(child, n, "doubleTapped", st)
        } else if (tapMs > 0) {
            st.tapUntil = t + tapMs
            runInMillis(tapMs, "buttonTapTimer", [data: [b: bid, seq: st.seq], overwrite: false])
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
        if (st.again) { st.remove("again"); pressButton(bid, st.againDesc?.toString()) }
        buttonOut(child, n, "released", st)
    } else {
        logDebug "Button ${bid}: ${ev} (not used)"
    }
}

void buttonHoldTimer(Map data) {
    Map st = buttonState(data.b)
    if (st.seq != data.seq || !st.down) return
    st.held = true
    buttonTimerOut(data.b, "held")
}

void buttonTapTimer(Map data) {
    Map st = buttonState(data.b)
    if (st.seq != data.seq || st.down || st.tapUntil == null) return
    st.tapUntil = null
    buttonTimerOut(data.b, "pushed")
}

void buttonTimerOut(Object bid, String name) {
    Map info = (Map) state.buttons?.get(bid.toString())
    Object child = info ? buttonDevice(info.d) : null
    if (child != null) buttonOut(child, info.n as Integer, name, buttonState(bid))
}

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
// haveProject: the resync has just read /project, so it isn't read twice
void queueDiscovery(Map s, boolean haveProject = false) {
    s.disc = [:]
    if (haveProject && s.project) s.disc["/project"] = s.project
    DISCOVERY_READS.findAll { !(haveProject && s.project && it == "/project") }.each { enqueue(s, [type: "ReadRequest", url: it, then: "discoveryAnswer"]) }
    enqueue(s, [call: "discoveryDone"])
}

void discoveryAnswer(Map s, Map j, Map p, String line) {
    if (s.disc == null) return
    if (j == null) s.disc.failed = p.url
    else s.disc[p.url] = j
}

void discoveryDone(Map s) {
    Map d = (Map) s.remove("disc")
    if (d == null) return
    String missing = d.failed ?: DISCOVERY_READS.find { !d[it] }
    if (missing) { logWarn "Discover: no good answer to ${missing}; the device list wasn't updated"; return }
    Map list
    try {
        list = compactList(d)
    } catch (Exception e) {
        logErr "Discover: couldn't make sense of the answers (${e.message})"
        return
    }
    applySystem((Map) list.system)
    tellSerial(list.system?.serial)
    String fp = deviceFingerprint((Map) d["/device"])
    if (fp) state.deviceFingerprint = fp
    state.zones = list.zones.collectEntries { [(it.id.toString()): it.control] }
    state.scenes = list.scenes.collectEntries { [(it.id.toString()): it.name] }
    Map btns = [:]
    list.picos.each { Map pc -> pc.buttons.each { Map b -> btns[b.id.toString()] = [d: pc.id, n: (b.number as Integer) + 1] } }
    state.buttons = btns
    state.discoveredAt = now()
    sendEventLog(name: "numberOfButtons", value: list.scenes ? list.scenes.collect { it.id as Integer }.max() : 0)
    logInfo "Devices read: ${list.zones.size()} zones, ${list.picos.size()} Picos, ${list.scenes.size()} scenes"
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

// The six answers boiled down to what the app's picker and the routing need (tens of KB -> a few KB).
Map compactList(Map d) {
    Map areas = [:], parents = [:]
    d["/area"].Body.Areas.each { areas[it.href] = it.Name; parents[it.href] = it.Parent?.href }
    Closure where = { Object href -> areaPath(href, areas, parents) }
    List devs = d["/device"].Body.Devices
    Map byHref = devs.collectEntries { [(it.href): it] }
    Map me = devs.find { it.IsThisDevice } ?: [:]
    List zones = d["/zone"].Body.Zones.collect { Map z ->
        Map dv = (Map) byHref[z.Device?.href] ?: [:]
        [id: hrefId(z.href), name: z.Name, control: z.ControlType, area: areas[dv.AssociatedArea?.href],
         device: dv.href ? hrefId(dv.href) : null, model: dv.ModelNumber, deviceType: dv.DeviceType, deviceHref: dv.href,
         location: where(dv.AssociatedArea?.href)]
    }
    Map groups = d["/button"].Body.Buttons.groupBy { it.Parent?.href }
    // Caseta: Picos only (RA3 keypads are read by area, pdDone)
    List picos = devs.findAll { deviceKind(it.DeviceType) == "pico" }.collect { Map dv ->
        List bs = (dv.ButtonGroups ?: []).collectMany { groups[it.href] ?: [] }
                    .collect { [id: hrefId(it.href), number: it.ButtonNumber] }.sort { it.number }
        [id: hrefId(dv.href), name: dv.Name, area: areas[dv.AssociatedArea?.href], deviceType: dv.DeviceType,
         model: dv.ModelNumber, buttons: bs, location: where(dv.AssociatedArea?.href)]
    }
    List scenes = d["/virtualbutton"].Body.VirtualButtons.findAll { it.IsProgrammed }.collect { [id: hrefId(it.href), name: it.Name] }
    Map system = [productType: d["/project"].Body.Project?.ProductType, deviceType: me.DeviceType, model: me.ModelNumber,
                  serial: me.SerialNumber, firmware: me.FirmwareImage?.Firmware?.DisplayName]
    return [system: system, zones: zones, picos: picos, scenes: scenes, readAt: now()]
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
    s.pd = [zones: [], stations: [], devices: [:], buttons: [:], areas: [:]]
    s.queue.addAll(0, [[type: "ReadRequest", url: "/area", then: "pdAreas"],
                       [type: "ReadRequest", url: "/device?where=IsThisDevice:true", then: "pdSelf"],
                       [call: "pdDone"]])
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

void pdDone(Map s) {
    Map pd = (Map) s.remove("pd")
    if (pd == null) return
    if (pd.failed) { logWarn "Discover: no good answer to ${pd.failed}; the device list wasn't updated"; return }
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
    pdSaveRouting(s, zones, picos, keypads, occupancy)          // before the hand-off: it numbers the keypads' buttons
    sendEventLog(name: "numberOfButtons", value: 0)
    logInfo "Devices read: ${zones.size()} zones, ${picos.size()} Picos, ${keypads.size()} keypads, ${occupancy.size()} areas with occupancy sensors"
    try {
        parent?.discoveryResult(list)
    } catch (Exception e) {
        logErr "Couldn't hand the device list to the app: ${e.message}"
    }
    subscribeNew(s)
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
    picos.each { Map pc -> pc.buttons.each { Map b -> btns[b.id.toString()] = [d: pc.id, n: (b.number as Integer) + 1] } }
    // Keypads: buttons 1..N in Lutron's order (raise / lower last), as Hubitat's built-in integration numbers them
    Map leds = [:]
    keypads.each { Map k ->
        k.buttons.eachWithIndex { Map b, int i ->
            b.n = i + 1
            btns[b.id.toString()] = [d: k.id, n: i + 1, kp: true]
            if (b.led != null) leds[b.led.toString()] = [d: k.id, n: i + 1]
        }
    }
    state.buttons = btns
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
    boolean up = s0?.kind == "leap" && s0.phase == "up"
    if (!up && state.linkWanted != false && s0?.kind != "pair" && ctx?.flash == null) {
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
    if (s?.kind == "leap" && s.phase == "up") return true
    if (s?.kind == "leap") { logWarn "Not connected yet (connecting): ${what} not sent"; return false }
    if (s?.kind == "pair") { logWarn "Pairing in progress: ${what} not sent"; return false }
    logWarn "Not connected: ${what} not sent; connecting"
    linkOf().attempt = 0
    runIn(1, "connect")
    return false
}

// A command while the link is down or coming up: held, and a connect started if none is under way (as ready() does)
void holdCommand(Map s, Map item) {
    List l = (List) heldCommands[device.idAsLong]
    if (l == null) { l = []; heldCommands[device.idAsLong] = l }
    item.at = now()
    l << item
    logInfo "Not connected: ${item.desc} held for up to ${HOLD_SECS} s" + (s == null ? "; connecting" : " (connecting)")
    if (s == null) { linkOf().attempt = 0; runIn(1, "connect") }
    runIn(HOLD_SECS + 1, "heldExpire")
}

// The link is up: send what is held and still fresh; drop the rest with the Warn
void sendHeld(Map s) {
    List l = (List) heldCommands.remove(device.idAsLong)
    if (!l) return
    unschedule("heldExpire")
    long cutoff = now() - HOLD_SECS * 1000L
    List fresh = l.findAll { (it.at as Long) > cutoff }
    (l - fresh).each { logWarn "Not connected after ${HOLD_SECS} s: ${it.desc} not sent" }
    if (fresh) logInfo "Sending ${fresh.size()} held command${fresh.size() == 1 ? '' : 's'}"
    fresh.each { Map it2 ->
        try {
            sendRequest(s, [what: "command", type: it2.type, url: it2.url, body: it2.body, desc: it2.desc, ctx: it2.ctx])
        } catch (Exception e) {
            linkDropped("sendMessage threw ${e.message}")
            return
        }
        // what command()'s callers record when they send: digital for the zone's push or the button's press
        List<String> parts = it2.url.toString().tokenize("/")            // [zone, 3, commandprocessor]
        if (parts.size() == 3 && parts[2] == "commandprocessor" && parts[0] == "zone") markZoneCommand(parts[1])
        else if (parts.size() == 3 && parts[2] == "commandprocessor" && parts[0] == "button") buttonState(parts[1]).sentAt = now()
    }
}

// HOLD_SECS after a command was held with no link: drop what is too old; check again for any newer
void heldExpire() {
    List l = (List) heldCommands[device.idAsLong]
    if (!l) return
    long cutoff = now() - HOLD_SECS * 1000L
    List old = l.findAll { (it.at as Long) <= cutoff }
    old.each { logWarn "Not connected after ${HOLD_SECS} s: ${it.desc} not sent" }
    l.removeAll(old)
    if (l) runIn(HOLD_SECS, "heldExpire") else heldCommands.remove(device.idAsLong)
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
            if (p.ctx?.flash != null) stopFlash(intOrNull(p.ctx.flash), "no answer to a step in ${REPLY_SECS} s", true)
        } else {
            if (s.reading == tag) s.reading = null
            String verb = (p.type == "SubscribeRequest") ? "Subscribe" : "Read"
            if ((p.tries as Integer) < 1) {
                logDebug "No answer to ${verb} ${p.url} in ${REPLY_SECS} s; trying once more"
                s.queue.add(0, [type: p.type, url: p.url, then: p.then, ctx: p.ctx, tries: 1])
            } else {
                logWarn "No answer to ${verb} ${p.url} in ${REPLY_SECS} s, twice; giving up"
                if (p.then) "${p.then}"(s, null, p, null)
            }
        }
    }
    if (sessions[device.idAsLong].is(s)) { pump(s); armCheck(s) }
}

/*******************************************************************
 ***** The LEAP session on 8081: connect, first ping, resync
********************************************************************/
void connect() {
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
        return
    }
    Map link = linkOf()
    state.linkWanted = true
    unschedule("connect")
    unschedule("reconnect")
    Map s = [kind: "leap", phase: "hello", n: 0, pending: [:], queue: [], reading: null, subs: [:]]
    Map opts = [secureSocket: true, certificate: creds.certificate, privateKey: creds.privateKey,
                caCertificate: creds.caCertificate, ignoreSSLIssues: false, eol: "\n"]
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
    if (tag && tag == s.zoneSub) { zoneStatusAnswer(s, j, null, line); return }     // processor zone changes
    if (tag && tag == s.areaSub) { areaStatusAnswer(s, j, null, line); return }     // processor occupancy
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
            if (p.ctx?.flash != null) flashAnswered(intOrNull(p.ctx.flash), intOrNull(p.ctx.seq), ok, status)
            return
        default:                                    // a read or subscription
            if (s.reading == p.tag) s.reading = null
            String verb = (p.type == "SubscribeRequest") ? "Subscribe" : "Read"
            if (!ok) logWarn "${verb} ${p.url} failed: ${status}${j.Body?.Message ? ': ' + j.Body.Message : ''}"
            else logDebug "${verb} ${p.url}: ${status}, ${ms} ms, ${line.length()} characters"
            if (p.then) "${p.then}"(s, ok ? j : null, p, line)
            if (sessions[device.idAsLong].is(s)) pump(s)
    }
}

void pingAnswer(Map s, Map j, Map p, long ms) {
    Object v = (j.Body?.PingResponse instanceof Map) ? j.Body.PingResponse.LEAPVersion : null
    boolean ok = j.Header?.StatusCode?.toString()?.startsWith("2") && v != null
    if (!ok) {
        if (p.what == "hello") connectFailed("unexpected answer to the first ping: ${leapSummary(j)}")
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
            setHealth("online")
            runIn(KEEPALIVE_SECS, "keepAlive")
            sendHeld(s)
            startResync(s)
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
    s.project = j
    if (pt in PROCESSOR_TYPES) {
        // Processor: discovery by area, zone status only by subscription. A Designer transfer changes
        // ProjectModifiedTimestamp (and restarts the processor): read the devices again when it differs
        String stamp = projectStamp(j)
        s.projectStamp = stamp
        boolean changed = stamp && state.projectStamp && stamp != state.projectStamp
        if (!state.discoveredAt || changed) {
            if (changed && state.discoveredAt) logInfo "The processor's project changed (${state.projectStamp} -> ${stamp}): reading its devices again"
            queueProcessorDiscovery(s)
        } else {
            if (stamp && !state.projectStamp) state.projectStamp = stamp       // none kept yet: keep this one
            enqueue(s, [type: "ReadRequest", url: "/device?where=IsThisDevice:true", then: "selfAnswer"])   // the serial
        }
        enqueue(s, [call: "queueButtonSubs"])
        enqueue(s, [call: "queueLedSubs"])
        enqueue(s, [type: "SubscribeRequest", url: "/zone/status", then: "zoneSubscribed"])
        enqueue(s, [type: "SubscribeRequest", url: "/area/status", then: "areaSubscribed"])
        enqueue(s, [call: "resyncDone"])
        return
    }
    if (!state.discoveredAt) queueDiscovery(s, true)                   // never read: discovery first
    else enqueue(s, [type: "ReadRequest", url: "/device", then: "selfAnswer"])   // the serial, for the app
    enqueue(s, [call: "queueButtonSubs"])               // after discovery: the button map comes from it
    enqueue(s, [type: "ReadRequest", url: "/zone/status", then: "zoneStatusAnswer"])
    enqueue(s, [call: "resyncDone"])
}

boolean isProcessor() { state.productType?.toString() in PROCESSOR_TYPES }

void resyncDone(Map s) {
    s.resynced = true
    linkOf().subFails = 0
    runIn(CHANGE_CHECK_SECS, "changeCheck")
    sendEventLog(name: "connection", value: "connected")
    childrenHealth("online")
}

/*******************************************************************
 ***** Status subscriptions (processors) and occupancy; a refused one drops the link
********************************************************************/
// The /zone/status subscription (processors): its answer has every zone; its changes come tagged with it (leapLine)
void zoneSubscribed(Map s, Map j, Map p, String line) {
    if (j == null) { coreSubscriptionFailed(s, p.url?.toString()); return }
    s.zoneSub = p.tag
    zoneStatusAnswer(s, j, p, line)
}

// Occupancy, per area, from the /area/status subscription (its answer and its changes)
void areaSubscribed(Map s, Map j, Map p, String line) {
    if (j == null) { coreSubscriptionFailed(s, p.url?.toString()); return }
    s.areaSub = p.tag
    areaStatusAnswer(s, j, p, line)
}

// Lines with OccupancyStatus Occupied / Unoccupied go to the area's motion device; Unknown (no sensors, or not yet
// known), lines with only power or scene, and areas with no device are left out. So are areas the last device read found
// no sensors in: the processor keeps an emptied area's last status (Occupied, say, after its sensors moved).
void areaStatusAnswer(Map s, Map j, Map p, String line) {
    List occAreas = (List) state.occAreas          // null until the first device read: every area applies
    ((List) j?.Body?.AreaStatuses ?: []).each { Map st ->
        String occ = st.OccupancyStatus?.toString()
        if (!(occ in ["Occupied", "Unoccupied"])) return
        Integer area = hrefId(st.href)
        Object child = childOf("${device.deviceNetworkId}/a/${area}".toString())
        if (child == null) { logDebug "Area ${area}: ${occ} (no device)"; return }
        if (occAreas != null && !occAreas.any { (it as Integer) == area }) { logDebug "Area ${area}: ${occ} (no occupancy sensors any more)"; return }
        String v = (occ == "Occupied") ? "active" : "inactive"
        child.parse([[name: "motion", value: v, descriptionText: "${child.displayName} motion is ${v}".toString()]])
    }
}

// A refused (or twice unanswered) /zone/status or /area/status subscription would leave the link up with no status
// coming: drop it and reconnect, backing off while it keeps failing (pylutron-caseta closes and reconnects when its
// re-login fails, for the same reason). A refused button or LED subscription only Warns: reconnecting can't fix a stale
// button list.
void coreSubscriptionFailed(Map s, String url) {
    if (!sessions[device.idAsLong].is(s)) return
    Map link = linkOf()
    int n = ((link.subFails ?: 0) as Integer) + 1
    link.subFails = n
    logWarn "Subscribe ${url} failed: reconnecting so status keeps coming (${n} in a row)"
    endSession()
    sendEventLog(name: "connection", value: "disconnected")
    link.attempt = n - 1                                       // back off (5, 15, 30 s...) while it keeps failing
    scheduleReconnect()
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
    if (state.deviceFingerprint && fp != state.deviceFingerprint && state.discoveredAt) {
        logInfo "The bridge's devices changed: reading them again"
        s.disc = [:]
        if (s.project) s.disc["/project"] = s.project
        List items = DISCOVERY_READS.findAll { !(s.project && it == "/project") }.collect { [type: "ReadRequest", url: it, then: "discoveryAnswer"] }
        s.queue.addAll(0, items + [[call: "discoveryDone"]])         // before the button subscriptions, which use its map
    } else if (!state.deviceFingerprint) {
        state.deviceFingerprint = fp                                  // none kept yet: keep this one
    }
}

// Between connects: a Caseta bridge doesn't restart for a change in the Lutron app, nor a processor for a rename there,
// and neither pushes one; so every CHANGE_CHECK_SECS the live link reads /project (processor) or /device (Caseta). On a
// change it reads the devices again on the live link and subscribes what's new.
void changeCheck() {
    Map s = sessions[device.idAsLong]
    if (s?.kind != "leap" || s.phase != "up") return
    enqueue(s, [type: "ReadRequest", url: isProcessor() ? "/project" : "/device", then: "changeCheckAnswer"])
    pump(s)
}

void changeCheckAnswer(Map s, Map j, Map p, String line) {
    if (!sessions[device.idAsLong].is(s)) return
    runIn(CHANGE_CHECK_SECS, "changeCheck")
    if (j == null) return
    if (isProcessor()) {
        String stamp = projectStamp(j)
        if (!(stamp && state.projectStamp && stamp != state.projectStamp)) return
        logInfo "The processor's project changed (${state.projectStamp} -> ${stamp}): reading its devices again"
        s.projectStamp = stamp                                      // kept when the discovery completes
        queueProcessorDiscovery(s)
    } else {
        String fp = deviceFingerprint(j)
        if (!(fp && state.deviceFingerprint && fp != state.deviceFingerprint)) return
        logInfo "The bridge's devices changed: reading them again"
        queueDiscovery(s)
    }
    pump(s)
}

// "YYYY-MM-DD hh:mm:ss" of /project's ProjectModifiedTimestamp (processors), or null
String projectStamp(Map j) {
    Map t = (Map) j?.Body?.Project?.ProjectModifiedTimestamp
    if (t == null) return null
    List<String> f = [t.Year, t.Month, t.Day, t.Hour, t.Minute, t.Second].collect { (it ?: 0).toString().padLeft(2, "0") }
    return "${f[0]}-${f[1]}-${f[2]} ${f[3]}:${f[4]}:${f[5]}".toString()
}

// Each device's href, name and type in a /device answer, as a short fingerprint, or null
String deviceFingerprint(Map j) {
    List devs = (List) j?.Body?.Devices
    if (!devs) return null
    String all = devs.collect { "${it.href}|${it.Name}|${it.DeviceType}" }.sort().join(";")
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
    endSession()
    sendEventLog(name: "connection", value: "disconnected")
    if ((link.attempt as Integer) > 0) setHealth("offline")
    logWarn "Connect failed: ${why}"
    dropCheck("connect failed: ${why.take(60)}")
    if (state.linkWanted) scheduleReconnect()
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

// A scheduled reconnect asks first whether anything answers at the bridge's address, with an async HTTPS request to the
// LEAP port. A rawSocket.connect to a host that silently drops packets blocks ~30 s and stalls every raw socket on the
// hub (other bridges' links drop meanwhile); the async request doesn't.
void reconnect() {
    if (state.linkWanted == false) return
    if (sessions[device.idAsLong] != null) return              // connected meanwhile (Connect now, the app)
    String ip = null
    try { ip = parent?.leapCredentials()?.ip?.toString() } catch (Exception e) { logDebug "Couldn't get the address from the app: ${e.message}" }
    if (!ip) { connect(); return }                             // connect() says what's missing
    logDebug "Checking that ${ip}:${LEAP_PORT} answers before connecting"
    asynchttpGet("reconnectProbe", [uri: "https://${ip}:${LEAP_PORT}/".toString(), timeout: PROBE_SECS, ignoreSSLIssues: true],
                 [ip: ip, port: LEAP_PORT, at: now()])
}

void reconnectProbe(Object resp, Map data) {
    if (state.linkWanted == false || sessions[device.idAsLong] != null) return
    String down = probeDown(resp)
    if (down == null) {
        logDebug "${data?.ip}:${data?.port} answered (${probeError(resp) ?: 'status ' + probeStatus(resp)}) in ${now() - ((data?.at ?: now()) as Long)} ms; connecting"
        connect()
        return
    }
    Map link = linkOf()
    if ((link.attempt as Integer) > 1) setHealth("offline")   // the first retry after a drop may find it rebooting
    logWarn "Not connecting: ${data?.ip}:${data?.port} doesn't answer (${down})"
    String k = PROBE_DOWN.find { down.toLowerCase().contains(it) }
    int at = k ? down.toLowerCase().indexOf(k) : -1
    dropCheck(at >= 0 ? down.substring(at, at + k.length()) : down.take(40))
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

// Ends any session and stops reconnecting (pairing, Disconnect, not paired).
void stopLink(String why) {
    Map link = linkOf()
    state.linkWanted = false
    link.attempt = 0
    link.remove("fastUntil")
    state.remove("dropOpen")                                  // a drop still open ends here, not back
    ((List) heldCommands.remove(device.idAsLong) ?: []).each { logWarn "${it.desc} not sent (${why})" }
    unschedule("heldExpire")
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
void dropCheck(String what) {
    Map d = (Map) state.dropOpen
    if (d == null) return
    d.checks = ((List) d.checks ?: []) + what
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
    if (d.checks) line += " (checks: ${((List) d.checks).join(', ')})"
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
 ***** Pairing (pylutron-caseta pairing.py flow)
********************************************************************/
// 1. (startPairing) Connected to 8083 with the LAP client certificate; waiting for the button press
//    (a status message whose Permissions include PhysicalAccess).
// 2. Send the CSR to /pair; the reply carries the signed certificate and the bridge's root certificate.
// 3. Hand both to the app, which checks and keeps them; then connect on 8081.
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
            parent?.pairingProgress("requesting certificate")
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
            pairingFailed("no certificate in the reply: ${leapSummary(j)}")
            return
        }
        endSession()
        sendEventLog(name: "connection", value: "disconnected")
        Boolean kept = false
        try {
            // On a processor the LEAP connection is checked against the Lutron root (pylutron-caseta keeps it as
            // the CA)
            kept = parent?.pairingResult(result.Certificate.toString(), result.RootCertificate.toString(),
                                         s.ca == "lutron" ? LUTRON_ROOT_CA_PEM : null)
        } catch (Exception e) {
            logErr "The app couldn't take the certificate: ${e.message}"
        }
        if (kept) {
            logInfo "Pairing: certificate handed to the app; connecting on ${LEAP_PORT}"
            linkOf().attempt = 0
            runIn(2, "connect")
        } else {
            logWarn "Pairing: the app didn't keep the certificate (see the app's log)"
        }
    }
}

void pairTimeout() {
    Map s = sessions[device.idAsLong]
    if (s?.kind != "pair") return
    pairingFailed(s.phase == "button" ? "no button press in ${BUTTON_SECS} s".toString() : "no certificate in ${REPLY_SECS} s".toString())
}

void pairingFailed(String why) {
    logWarn "Pairing failed: ${why}"
    endSession()
    sendEventLog(name: "connection", value: "disconnected")
    try { parent?.pairingFailed(why) } catch (Exception e) { logErr "Couldn't tell the app: ${e.message}" }
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
    sessions.remove(device.idAsLong)
    rxBuf.remove(device.idAsLong)
    unschedule("checkPending")
    unschedule("keepAlive")
    unschedule("pairTimeout")
    unschedule("changeCheck")
    try { interfaces.rawSocket.close() } catch (Exception ignored) { }
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
    if (s?.kind != "pair") logTrace "parse: ${msg.length() > 400 ? msg.take(400) + '...' : msg}"
    if (s == null) { lostSession("Socket data"); return }
    s.lastRxAt = now()
    if (s.rxMode == null) s.rxMode = (msg ==~ /(?:[0-9A-Fa-f]{2})+/) ? "hex" : "text"
    String text = msg
    if (s.rxMode == "hex") {
        try {
            text = new String(HexUtils.hexStringToByteArray(msg), "UTF-8")
        } catch (Exception e) {
            logWarn "Undecodable socket data (${e.message}): ${msg.take(80)}"
            return
        }
    }
    String buf = (rxBuf[device.idAsLong] ?: "") + text
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
    else logDebug "Received: ${line.length() > 300 ? line.take(300) + '...' : line}"
    Map j = null
    try { j = (Map) new JsonSlurper().parseText(line) } catch (Exception ignored) { }
    if (s.kind == "pair") pairLine(s, j, line)
    else leapLine(s, j, line)
}

void socketStatus(String msg) {
    logDebug "socketStatus: ${msg}"
    Map s = sessions[device.idAsLong]
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
        pairingFailed(detail)
    }
    else if (s.phase == "up") linkDropped(detail)
    else connectFailed(detail)
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
    Integer level = settings.logLevel != null ? settings.logLevel as Integer : 1
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
