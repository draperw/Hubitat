# Big Ass Fans Matter Driver — Technical Reference

Technical documentation for the Hubitat Matter driver for Big Ass Fans ceiling fans. Covers device architecture, Matter protocol details, and driver implementation.

**Device:** Big Ass Fans ceiling fans
**Protocol:** Matter (controllerType: MAT)
**Hardware Version:** 1
**Software Version:** 3.3.0

---

## Table of Contents

- [Driver Architecture](#driver-architecture)
- [Endpoint Map](#endpoint-map)
- [Endpoint 0x00 — Root Node](#endpoint-0x00--root-node)
- [Endpoint 0x01 — Fan](#endpoint-0x01--fan-device-type-0x002b)
- [Endpoint 0x02 — Light](#endpoint-0x02--color-temperature-light-device-type-0x010c)
- [Endpoint 0x03 — Light Mode Select](#endpoint-0x03--light-channel-mode-select-device-type-0x0027)
- [Endpoint 0x04 — Temperature Sensor](#endpoint-0x04--temperature-sensor-device-type-0x0302)
- [Endpoint 0x06 — Occupancy Sensor](#endpoint-0x06--occupancy-sensor-device-type-0x0107)
- [BAF-Specific Behaviors](#baf-specific-behaviors)
- [Variant Support](#variant-support)
- [Parse Pipeline](#parse-pipeline)
- [Health Check System](#health-check-system)
- [Attribute Value Reference](#attribute-value-reference)
- [Confirmed Attribute Snapshot](#confirmed-attribute-snapshot)

---

## Driver Architecture

The device is managed by two Hubitat drivers:

| Driver | File | Responsibility |
|--------|------|----------------|
| **Parent** | `BAF-Fan-Matter-Parent.groovy` | EP 01 (fan), EP 04 (temp), EP 06 (occupancy), EP 00 (descriptor/basic info), child device management, health monitoring |
| **Child** | `BAF-Light-Matter-Child.groovy` | EP 02 (light control, light auto mode), EP 03 (light mode select) |

Both use `newParse:true` for Map-based Matter message parsing. The parent receives all Matter messages and routes EP 02/03 messages to the child via `parseFromParent()`.

### Data Flow

```
Matter Device → Hubitat Hub → Parent parse(Map)
                                ├── EP 01 → parseFanCluster()
                                ├── EP 02 → forwardToLightChild() ─(50ms defer)─ child.parseFromParent()
                                ├── EP 03 → forwardToLightChild() ─(50ms defer)─ child.parseFromParent()
                                ├── EP 04 → parseTemperature()
                                ├── EP 06 → parseOccupancy()
                                └── EP 00 → parseDescriptor() / parseBasicInfo()
```

> **Deferred light child forwarding:** EP 02 and EP 03 messages are forwarded via a 50ms `runInMillis` deferral rather than a direct call. This breaks a potential deadlock: `parse()` holds the parent device semaphore while routing, and child commands (`off()`, `setLevel()`, etc.) call `parent.sendToDevice()` which would block waiting for that same semaphore. The deferral lets `parse()` complete and release the semaphore before the child is invoked. `overwrite:false` is used so that burst forwarding (e.g., during a `refresh()`) accumulates all messages rather than coalescing them.

### Lifecycle Methods

| Method | Trigger | Action |
|--------|---------|--------|
| `configure()` | Manual / device page | Full init: endpoint discovery, subscription setup, child device management |
| `initialize()` | Hub reboot / manual | Subscribe immediately; re-subscribe after PartsList arrives with final endpoint list |
| `updated()` | Saving preferences | Calls `checkLogLevel()`, `initHealthCheck()`, and `subscribeToAttributes()` — re-subscribes without requiring a full Configure |
| `refresh()` | Manual | Reads all attribute values from the device |

### Endpoint Discovery

On initialize, the parent reads the Descriptor cluster (0x001D) PartsList attribute from EP 00 to discover which endpoints exist. This determines:

- **`lightCount`** — 0 (no light), 1 (single light, EP 02 only), or 2 (uplight + downlight, EP 02 + EP 03)
- Whether to create or remove the light child device
- Which endpoints to subscribe to and refresh

All capability accessors default conservatively (false/0) until PartsList arrives. The initial `subscribeToAttributes()` call in `initialize()` omits EP 04/EP 06 because `hasTemp()`/`hasOccupancy()` are false at that point. After `parseDescriptor()` updates the snapshot, a re-subscribe fires to pick up any newly confirmed endpoints. The re-subscribe and CT probe are skipped if the endpoint topology is unchanged from the previous run (detected by comparing the old and new endpoint lists), preventing churn on repeated PartsList reports.

Child device creation and CT probing are also deferred until PartsList arrives. No provisional child is created before endpoint topology is confirmed.

The parent stores runtime capabilities in `state.capabilitySnapshot` (light topology, CT profile/range, discovered endpoints) and syncs this to the child.

### Capability Accessors

| Accessor | Source | Default | Description |
|----------|--------|---------|-------------|
| `getLightCount()` | `state.capabilitySnapshot.lightCount` | 0 (conservative) | Number of lights (0, 1, or 2) |
| `hasLight()` | `getLightCount() > 0` | false | Fan has a light kit |
| `hasLightMode()` | `getLightCount() >= 2` | false | Fan has uplight + downlight |
| `hasTemp()` | `state.capabilitySnapshot.endpoints` contains EP 04 | false | Fan has temperature sensor |
| `hasOccupancy()` | `state.capabilitySnapshot.endpoints` contains EP 06 | false | Fan has occupancy sensor |
| `hasColorTemp()` | `state.capabilitySnapshot.ctPresent` | true | Light endpoint supports CT mode |
| `hasAdjustableColorTemp()` | `state.capabilitySnapshot.ctAdjustable` | true until detected | CT is adjustable (minK < maxK) |
| `getLightProfile()` | `state.capabilitySnapshot.lightProfile` | unknown | `no_light`, `single_light_dimmer`, `single_light_ct`, `dual_light_dimmer`, `dual_light_ct` |

All accessors default conservatively: `hasLight()`, `hasTemp()`, and `hasOccupancy()` return false until PartsList is received and `state.capabilitySnapshot` is populated.

The child driver receives runtime capability updates from the parent via `setLightCount()`, `setHasColorTemp()`, `setColorTempAdjustable()`, and `setColorTempRangeK()`.

---

## Endpoint Map

Endpoint presence varies by fan variant. PartsList examples observed:

- L Series (single light): `[1, 2]`
- H/I single-light variant: `[1, 2, 4, 6]`
- H/I dual-light + CT variant: `[1, 2, 3, 4, 6]`

> Endpoint 5 is absent (reserved or unused by BAF).

| Endpoint | Device Type ID | Device Type Name | Purpose |
|----------|---------------|------------------|---------|
| 0x00 | — (Root Node) | Root Node | Descriptor, Basic Information |
| 0x01 | 0x002B (43) | Fan | Primary fan motor control |
| 0x02 | 0x010C (268) | Color Temperature Light | Light kit (down/up/all) |
| 0x03 | 0x0027 (39) | Mode Select | Light channel mode select |
| 0x04 | 0x0302 (770) | Temperature Sensor | Onboard temperature sensor |
| 0x06 | 0x0107 (263) | Occupancy Sensor | Built-in PIR/presence sensor |

---

## Endpoint 0x00 — Root Node

Not fingerprinted. Provides device-wide metadata.

### Cluster 0x001D — Descriptor

| Attribute | ID | Description |
|-----------|----|-------------|
| DeviceTypeList | 0x0000 | Device type for each endpoint |
| PartsList | 0x0003 | List of child endpoints (variant-dependent, e.g., [1,2], [1,2,4,6], [1,2,3,4,6]) |

### Cluster 0x0028 — Basic Information

| Attribute | ID | Type | Observed Value | Description |
|-----------|----|------|----------------|-------------|
| DataModelRevision | 0x0000 | UINT | 17 | Matter data model revision (used as ping target) |
| VendorName | 0x0001 | STRING | "Big Ass Fans" | Manufacturer name |
| VendorID | 0x0002 | UINT | 5202 (0x1452) | Matter Vendor ID |
| ProductName | 0x0003 | STRING | "Haiku H/I Series" | Product name → stored as `model` |
| ProductID | 0x0004 | UINT | 3 | Product ID |
| NodeLabel | 0x0005 | STRING | "Bedroom Fan" | User-assigned name → stored as `matterName` |
| Location | 0x0006 | STRING | "US" | Device locale |
| HardwareVersion | 0x0007 | UINT | 1 | Hardware revision |
| HardwareVersionString | 0x0008 | STRING | "1" | Human-readable hardware version |
| SoftwareVersion | 0x0009 | UINT | 0 | Numeric software version |
| SoftwareVersionString | 0x000A | STRING | "3.3.0" | Human-readable firmware → stored as `softwareVersion` |
| UniqueID | 0x0012 | STRING | — | Not returned by device |

---

## Endpoint 0x01 — Fan (Device Type 0x002B)

**Fingerprint(s):**
`endpointId:"01", inClusters:"0003,0004,001D,0202", model:"Haiku L Series", manufacturer:"Big Ass Fans"`
`endpointId:"01", inClusters:"0003,0004,001D,0202", model:"Haiku H/I Series", manufacturer:"Big Ass Fans"`

Primary fan motor endpoint. Controls speed, mode, wind effect, and airflow direction.

### Cluster 0x0202 — Fan Control

| Attribute | ID | Type | R/W | Values | Description |
|-----------|----|------|-----|--------|-------------|
| FanMode | 0x0000 | UInt8 | R/W | 0=Off, 1=Low, 2=Medium, 3=High, 4=On, 5=Auto | Operating mode |
| FanModeSequence | 0x0001 | UInt8 | R | 2 = Low/Med/High/Auto | Supported mode sequence |
| PercentSetting | 0x0002 | UInt8 | R/W | 0–100, null in Auto | Requested speed |
| PercentCurrent | 0x0003 | UInt8 | R | 0–100 | Actual current speed |
| SpeedMax | 0x0004 | — | — | — | Not returned |
| SpeedSetting | 0x0005 | — | — | — | Not returned |
| SpeedCurrent | 0x0006 | — | — | — | Not returned |
| WindSetting | 0x000A | UInt8 | R/W | 0=Normal, 2=Whoosh | Wind effect mode |
| AirflowDirectionEnum | 0x000B | UInt8 | R/W | 0=Forward, 1=Reverse | Blade direction |
| FeatureMap | 0xFFFC | Bitmap32 | R | 0x3A observed | Feature capability bits (see below) |

**Fan Control FeatureMap (observed 0x3A on Haiku H/I Series):**

| Bit | Feature | Value | Notes |
|-----|---------|-------|-------|
| 0 | MultiSpeed | 0 | SpeedSetting (0x0005) and SpeedCurrent (0x0006) not present |
| 1 | Auto | 1 | `autoOn()`/`autoOff()` supported |
| 2 | Rocking | 0 | Not present |
| 3 | Wind | 1 | WindSetting (Whoosh) supported |
| 4 | Step | 1 | Advertised but non-functional — see note below |
| 5 | AirflowDirection | 1 | `directionForward()`/`directionReverse()` supported |

> **Step command note:** BAF advertises the Step feature bit and the Fan Control Step command (0x0002), but invoking it causes a Hubitat driver `initialize()` cascade regardless of TLV encoding. `cycleSpeed()`, `fanSpeedUp()`, and `fanSpeedDown()` are implemented using direct PercentSetting writes instead (see Speed Stepping below).

### BAF Speed Mapping (7 Discrete Speeds)

| BAF Speed | BAF % Report | Percent Range | PercentSetting Value |
|-----------|-------------|--------------|---------------------|
| Off | 0 | 0% | 0 |
| Speed 1 | 1 | 1–9% | 1 |
| Speed 2 | 18 | 10–25% | 18 |
| Speed 3 | 34 | 26–42% | 34 |
| Speed 4 | 51 | 43–58% | 51 |
| Speed 5 | 67 | 59–75% | 67 |
| Speed 6 | 84 | 76–91% | 84 |
| Speed 7 | 100 | 92–100% | 100 |

### Key Behaviors

- **PercentSetting vs PercentCurrent:** PercentSetting is the writable/requested value. PercentCurrent is the read-only actual speed. Always use PercentCurrent for display values. In Auto mode, PercentSetting goes null while PercentCurrent reflects the auto-chosen speed.
- **PercentSetting not subscribed:** PercentSetting (0x0002) is intentionally omitted from the subscription attribute list. Writing PercentSetting generates both a write-response and a subscription notification, both routing to `parse()`. Subscribing would cause double-parse on every write. PercentCurrent (0x0003) reflects the confirmed speed after the device processes the write and is used for all display values.
- **Auto mode:** Writing FanMode=5 enables auto mode. Writing any PercentSetting while in Auto implicitly exits Auto.
- **Previous speed restore:** The driver stores the last non-zero, non-Auto PercentCurrent value for `on()` and `autoOff()` restore. The `fanMode != "Auto"` guard prevents Auto-mode speeds from polluting this value.
- **WindSetting values:** 0=Sleep Wind (steady), 2=Whoosh (periodic variation). Value 1 is not used.

### Speed Stepping Commands

`cycleSpeed()`, `fanSpeedUp()`, and `fanSpeedDown()` step through the 7 BAF discrete speeds by writing PercentSetting directly:

| Command | Behavior |
|---------|----------|
| `cycleSpeed()` | Advance one speed step; wraps from Speed 7 → Off → Speed 1 |
| `fanSpeedUp()` | Increase one step; clamps at Speed 7 |
| `fanSpeedDown()` | Decrease one step; clamps at Off |

Implementation reads `bafSpeed` state (0–7), increments/decrements, maps to the canonical PercentSetting value for that speed, and calls `writeFanAttr(0x0002, percent)`. The Matter Fan Control Step command is not used (see FeatureMap note above).

### Other Clusters on EP 01

| Cluster | ID | Notes |
|---------|----|-------|
| Identify | 0x0003 | IdentifyType=2 (visible indicator) |
| Groups | 0x0004 | Standard group membership |
| Descriptor | 0x001D | Reports device type 0x002B (Fan) |

---

## Endpoint 0x02 — Color Temperature Light (Device Type 0x010C)

**Fingerprint(s):**
`endpointId:"02", inClusters:"0003,0004,0005,0006,0008,001D,0040,0050,0300", model:"Haiku L Series", manufacturer:"Big Ass Fans"`
`endpointId:"02", inClusters:"0003,0004,0005,0006,0008,001D,0040,0050,0300", model:"Haiku H/I Series", manufacturer:"Big Ass Fans"`

Light kit endpoint. Controls brightness, color temperature, on/off, and light auto mode.

### Cluster 0x0006 — On/Off

| Attribute | ID | Type | R/W | Values | Description |
|-----------|----|------|-----|--------|-------------|
| OnOff | 0x0000 | Boolean | R/W | true/false (or 1/0) | Light on/off state |

> **newParse:true type note:** This value may arrive as Boolean or Integer. The driver uses `instanceof` checks before casting.

### Cluster 0x0008 — Level Control

| Attribute | ID | Type | R/W | Values | Description |
|-----------|----|------|-----|--------|-------------|
| CurrentLevel | 0x0000 | UInt8 | R | 0–254 | Current brightness (maps to 0–100%) |

**Commands used:**

| Command | ID | Fields | Description |
|---------|----|--------|-------------|
| MoveToLevelWithOnOff | 0x0004 | Level (UInt8), TransitionTime (UInt16, tenths of second) | Set level; auto-on if off |
| StopWithOnOff | 0x0007 | (none) | Freeze a ramp in progress |

**Residual level behavior:** When the light is off, the device still reports a residual CurrentLevel (e.g., 3). The driver ignores level reports while the switch is off.

**Ramp cancellation:** `on()`, `off()`, and `setLevel()` each call `unschedule("off")` and `unschedule("refresh")` before executing. Any pending deferred `off()` from a down-ramp or `refresh()` from an up-ramp is cancelled when a new command arrives. `stopLevelChange()` also cancels both before sending StopWithOnOff.

**`startLevelChange` stale state resolution:** `bafLevel` can be 0 on driver startup before the first subscription report. To avoid silently dropping a down-ramp on stale state, the driver resolves the starting position from multiple signals in priority order: (1) `bafLevel` attribute, (2) `level` attribute, (3) `state.previousLevel`, (4) midpoint (BAF 8) as a conservative estimate if the switch is on, or bail if the switch is confirmed off.

### BAF Discrete Light Levels (16 Steps)

| BAF Level | BAF % Report | Percent Range |
|-----------|-------------|--------------|
| 1 | 1 | 1–4% |
| 2 | 8 | 5–10% |
| 3 | 14 | 11–17% |
| 4 | 21 | 18–24% |
| 5 | 27 | 25–30% |
| 6 | 34 | 31–37% |
| 7 | 41 | 38–43% |
| 8 | 47 | 44–50% |
| 9 | 54 | 51–57% |
| 10 | 60 | 58–63% |
| 11 | 67 | 64–70% |
| 12 | 74 | 71–76% |
| 13 | 80 | 77–83% |
| 14 | 87 | 84–90% |
| 15 | 93 | 91–96% |
| 16 | 100 | 97–100% |

### Cluster 0x0300 — Color Control

| Attribute | ID | Type | R/W | Values | Description |
|-----------|----|------|-----|--------|-------------|
| CurrentHue | 0x0000 | — | — | — | Not returned (no RGB) |
| CurrentSaturation | 0x0001 | — | — | — | Not returned (no RGB) |
| ColorTemperatureMireds | 0x0007 | UInt16 | R/W | runtime | Current CT in mireds |
| ColorMode | 0x0008 | UInt8 | R | 2 observed | `ColorTemperatureMireds` mode when CT active |
| EnhancedColorMode | 0x4001 | UInt8 | R | 2 observed | Enhanced color mode |
| ColorCapabilities | 0x400A | Bitmap16 | R | 0x10 observed | CT capability bit |
| ColorTempPhysicalMinMireds | 0x400B | UInt16 | R | runtime | Physical CT minimum |
| ColorTempPhysicalMaxMireds | 0x400C | UInt16 | R | runtime | Physical CT maximum |
| FeatureMap | 0xFFFC | Bitmap32 | R | 0x10 observed | CT feature bit |

**Conversion:** Mireds = 1,000,000 / Kelvin.

**Command:** MoveToColorTemperature (0x000A) with fields:

| Tag | Field | Type | Description |
|-----|-------|------|-------------|
| 0 | ColorTemperatureMireds | UInt16 | Target CT in mireds (little-endian) |
| 1 | TransitionTime | UInt16 | Tenths of a second (0 = instant) |
| 2 | OptionsMask | UInt8 | `0x01` — executeIfOff bit |
| 3 | OptionsOverride | UInt8 | `0x01` — executeIfOff bit |

OptionsMask and OptionsOverride both set to `0x01` enable the **executeIfOff** behavior: the device accepts and applies the CT command regardless of current on/off state. No power-on sequencing is needed.

CT behavior is classified at runtime:

- `minMireds < maxMireds` → adjustable CT (`*_ct` profile)
- `minMireds == maxMireds` → fixed CT / dimmer behavior (`*_dimmer` profile)

The child clamps CT reads/writes to parent-discovered CT bounds and rejects `setColorTemperature()` when CT is fixed.

**`setColorTemperature(ct, level, transitionTime)` parameter handling:**

| Parameter | Behavior |
|-----------|----------|
| `ct` | Required. Kelvin, clamped to device physical CT range, converted to mireds. |
| `level` | Optional. If provided, `setLevel(level, transitionTime)` is called after the CT command. |
| `transitionTime` | Optional. Seconds (decimal accepted). Converted to tenths-of-second for the CT command field and passed to `setLevel()` when level is also provided. |

**CT Probe (runtime capability discovery):**

At initialize time, the parent reads ColorTempPhysicalMinMireds (0x400B) and ColorTempPhysicalMaxMireds (0x400C) from EP 02. These arrive as separate attribute reports. `state.ctProbeWork` accumulates partial results until both min and max are received. Once both arrive, the driver computes the Kelvin range, classifies the light profile (adjustable vs fixed CT), and syncs the result to the child via `setColorTempAdjustable()` and `setColorTempRangeK()`. `state.ctProbeWork` is cleared on completion or on timeout.

### Cluster 0x0050 — Mode Select (Light Auto)

EP 02 uses Mode Select for light auto mode (User vs Auto control).

| Attribute | ID | Type | R/W | Description |
|-----------|----|------|-----|-------------|
| SupportedModes | 0x0002 | List | R | Available modes (struct array) |
| CurrentMode | 0x0003 | UInt8 | R | Active mode number |

| Mode | Value | Description |
|------|-------|-------------|
| User | 0 | Manual control — light responds to user commands only |
| Auto | 1 | Automatic control — adjusts based on occupancy/ambient |

**SupportedModes struct format:**
```
[
  [tag:0 (Label)="User",  tag:1 (Mode)=0, tag:2 (SemanticTags)=[[0:UINT:0, 1:UINT:0]]],
  [tag:0 (Label)="Auto",  tag:1 (Mode)=1, tag:2 (SemanticTags)=[[0:UINT:0, 1:UINT:0]]]
]
```

**Command:** ChangeToMode (0x0000) with field NewMode (UInt8).

### Other Clusters on EP 02

| Cluster | ID | Notes |
|---------|----|-------|
| Identify | 0x0003 | Standard |
| Groups | 0x0004 | Standard group membership |
| Scenes | 0x0005 | SceneCount=0 (no scenes defined) |
| Descriptor | 0x001D | Reports device type 0x010C |
| Fixed Label | 0x0040 | LabelList: `[[Name, Light]]` |

---

## Endpoint 0x03 — Light Channel Mode Select (Device Type 0x0027)

**Fingerprint (H/I dual-light variants):**
`endpointId:"03", inClusters:"0003,001D,0040,0050", model:"Haiku H/I Series", manufacturer:"Big Ass Fans"`

Selects which light channel is active. Only present on fans with 2 lights (uplight + downlight).

### Cluster 0x0050 — Mode Select (Light Channel)

| Attribute | ID | Type | R/W | Description |
|-----------|----|------|-----|-------------|
| SupportedModes | 0x0002 | List | R | Available light channels |
| CurrentMode | 0x0003 | UInt8 | R | Active channel |

| Mode | Value | Description |
|------|-------|-------------|
| All Lights | 0 | Both downlight and uplight active |
| Downlight | 1 | Only downlight active |
| Uplight | 2 | Only uplight active |

**SupportedModes struct format:**
```
[
  [tag:0 (Label)="All Lights", tag:1 (Mode)=0, tag:2 (SemanticTags)=[[0:UINT:0, 1:UINT:0]]],
  [tag:0 (Label)="Downlight",  tag:1 (Mode)=1, tag:2 (SemanticTags)=[[0:UINT:0, 1:UINT:0]]],
  [tag:0 (Label)="Uplight",    tag:1 (Mode)=2, tag:2 (SemanticTags)=[[0:UINT:0, 1:UINT:0]]]
]
```

**Command:** ChangeToMode (0x0000) with field NewMode (UInt8).

> On single-light fans, EP 03 may be absent entirely (not listed in PartsList).

### Other Clusters on EP 03

| Cluster | ID | Notes |
|---------|----|-------|
| Identify | 0x0003 | Standard |
| Descriptor | 0x001D | Reports device type 0x0027 (Mode Select) |
| Fixed Label | 0x0040 | LabelList: `[[Name, Light Mode]]` |

---

## Endpoint 0x04 — Temperature Sensor (Device Type 0x0302)

**Fingerprint (H/I variants with temp endpoint):**
`endpointId:"04", inClusters:"0003,001D,0402", model:"Haiku H/I Series", manufacturer:"Big Ass Fans"`

Onboard temperature sensor.

### Cluster 0x0402 — Temperature Measurement

| Attribute | ID | Type | R/W | Values | Description |
|-----------|----|------|-----|--------|-------------|
| MeasuredValue | 0x0000 | Int16 | R | Centidegrees C | value / 100 = degrees C |

**Special value:** `0x8000` = "invalid / not available" (Matter spec sentinel). The driver discards this value.

**Example:** Value 2059 = 20.59°C = 69.1°F.

The driver respects the hub's temperature scale setting (°C or °F) and rounds to one decimal place.

### Other Clusters on EP 04

| Cluster | ID | Notes |
|---------|----|-------|
| Identify | 0x0003 | Standard |
| Descriptor | 0x001D | Reports device type 0x0302 |

---

## Endpoint 0x06 — Occupancy Sensor (Device Type 0x0107)

**Fingerprint (H/I variants with occupancy endpoint):**
`endpointId:"06", inClusters:"0003,001D,0406", model:"Haiku H/I Series", manufacturer:"Big Ass Fans"`

Built-in PIR/presence sensor. Used by the fan for auto-on/off logic.

### Cluster 0x0406 — Occupancy Sensing

| Attribute | ID | Type | R/W | Values | Description |
|-----------|----|------|-----|--------|-------------|
| Occupancy | 0x0000 | Bitmap8 | R | Bit 0: 0=unoccupied, 1=occupied | Occupancy state |

Only bit 0 is used. Maps to Hubitat MotionSensor capability: occupied → "active", unoccupied → "inactive".

### Other Clusters on EP 06

| Cluster | ID | Notes |
|---------|----|-------|
| Identify | 0x0003 | Standard |
| Descriptor | 0x001D | Reports device type 0x0107 |

---

## BAF-Specific Behaviors

All clusters used are **standard Matter clusters** — BAF does not use manufacturer-specific cluster IDs. However, BAF applies several device-specific behaviors within standard clusters:

1. **Two Mode Select endpoints:** EP 02 for light auto mode (User/Auto) and EP 03 for light channel selection (All Lights/Downlight/Uplight).

2. **Wind Setting gap:** Only values 0 (Normal/Sleep Wind) and 2 (Whoosh/Natural Wind) are used. Value 1 is skipped.

3. **16 discrete light levels:** BAF internally uses 16 brightness steps, not continuous 0–100%. The Matter Level Control attribute (0–254) maps to these steps.

4. **7 discrete fan speeds:** BAF maps 7 physical speeds to percent ranges within the standard 0–100% PercentSetting attribute.

5. **Missing Endpoint 5:** The endpoint list jumps from 4 to 6. Endpoint 5 is not exposed.

6. **PercentSetting nullability:** In Auto mode (FanMode=5), PercentSetting goes null while PercentCurrent continues to report the auto-determined speed.

7. **Fixed Label cluster (0x0040):** Present on EP 02 ("Light") and EP 03 ("Light Mode"). Returns a label struct, not a boolean.

8. **Residual CurrentLevel on off:** The light reports a non-zero CurrentLevel even when the switch is off. The driver ignores these residual reports.

---

## Variant Support

Not all BAF fan models expose every endpoint. Capabilities are declared statically in metadata (Hubitat does not support dynamic capabilities), so features that a specific fan lacks are handled at runtime:

### Parent Device

| Feature | Endpoint | If Absent |
|---------|----------|-----------|
| Temperature sensor | EP 04 | `temperature` attribute stays null. No events fire. Attribute visible on device page but blank. |
| Occupancy sensor | EP 06 | `motion` attribute stays null. No events fire. |
| Fan auto mode | — | Supported on all BAF fan models. `autoOn()`/`autoOff()` write unconditionally. |
| Light kit | EP 02 | No child device is created. EP 02/03 messages are silently ignored. |

### Child Device

| Feature | Endpoint | If Absent |
|---------|----------|-----------|
| Light mode (uplight/downlight) | EP 03 | `controlAllLights`/`Downlight`/`Uplight` log "not available." EP 03 messages ignored. |
| Light auto mode | — | Supported on all BAF fan models. `lightAutoOn()`/`lightAutoOff()` write unconditionally. |
| Color temperature adjustable control | — | On fixed-CT fans, `setColorTemperature()` logs fixed-CT info and sends no CT command. |

---

## Parse Pipeline

### newParse:true Type Handling

With `newParse:true`, Hubitat delivers pre-parsed Maps with typed values. Key type behaviors:

| Cluster | Attribute | Delivered Type | Notes |
|---------|-----------|---------------|-------|
| On/Off (0x0006) | OnOff (0x0000) | **Boolean** | Use `instanceof Boolean` before casting |
| Level (0x0008) | CurrentLevel (0x0000) | Integer | `as Integer` cast is sufficient |
| Fan Control (0x0202) | All attributes | Integer | `as Integer` cast is sufficient |
| Temperature (0x0402) | MeasuredValue (0x0000) | Integer | Signed (can represent negative temps) |
| Mode Select (0x0050) | CurrentMode (0x0003) | Integer | `as Integer` cast is sufficient |
| Descriptor (0x001D) | PartsList (0x0003) | List | May contain Integer or String elements |

### SubscriptionResult Callbacks

Matter subscriptions periodically send `callbackType:SubscriptionResult` maps with no endpoint or cluster. The driver detects these via `descMap.containsKey("callbackType")` and silently ignores them.

### Duplicate Event Guards

All parse methods check `device.currentValue()` before calling `sendEvent()` and logging at info level. Events are only sent when values actually change, preventing chatty logs and unnecessary event bus traffic.

---

## Health Check System

### Architecture

The parent driver implements three health check approaches:

| Method | Value | Behavior |
|--------|-------|----------|
| Disabled | 0 | No health monitoring |
| Activity check | 1 | Monitors for any incoming messages within the interval |
| Periodic polling | 2 | Sends a ping (reads BasicInfo DataModelRevision from EP 00) at the configured interval |

### Offline Detection

- A counter increments on each health check interval where no message is received.
- **3 consecutive misses** (`PRESENCE_COUNT_THRESHOLD + 1`) marks the device offline.
- Any received message resets the counter and marks the device online.
- Health status is mirrored to the child device via `child.setHealthStatus()`. The mirror fires on every transition **and** at child creation time and on `initialize()`, so the child never gets stuck at its initial "unknown" value if the parent is already online when the child is created or restarted.

### Ping

The `ping()` command reads BasicInfo DataModelRevision (attr 0x0000) from EP 00 and measures round-trip time. RTT values above 15 seconds are discarded as invalid.

---

## Attribute Value Reference

All custom attribute values use Title Case for consistency. Hubitat-standard capability attributes use the casing Hubitat defines.

### Parent Device Attributes

| Attribute | Source | Values |
|-----------|--------|--------|
| `switch` | FanMode (Hubitat standard) | on, off |
| `speed` | PercentCurrent | Off, Speed 1–7 |
| `level` | PercentCurrent | 0–100 |
| `bafSpeed` | PercentCurrent | 0–7 |
| `fanMode` | FanMode attr | Off, Low, Medium, High, On, Auto |
| `airflowDirection` | AirflowDirection attr | Forward, Reverse |
| `windMode` | WindSetting attr | Normal, Whoosh |
| `temperature` | MeasuredValue attr | Numeric (°C or °F per hub setting) |
| `motion` | Occupancy attr (Hubitat standard) | active, inactive |
| `healthStatus` | Health check system | online, offline, unknown |
| `rtt` | Ping response | Milliseconds |

### Child Device Attributes

| Attribute | Source | Values |
|-----------|--------|--------|
| `switch` | OnOff attr (Hubitat standard) | on, off |
| `level` | CurrentLevel attr | 0–100 |
| `bafLevel` | Computed from level | 0–16 |
| `colorTemperature` | ColorTemperatureMireds attr | runtime detected range (Kelvin) |
| `colorMode` | Static | CT |
| `lightMode` | EP 03 CurrentMode | All Lights, Downlight, Uplight |
| `lightAuto` | EP 02 CurrentMode | on, off |
| `healthStatus` | Mirrored from parent | online, offline, unknown |

---

## Cluster Reference Summary

| Cluster ID | Name | Endpoints | Purpose |
|-----------|------|-----------|---------|
| 0x0003 | Identify | All | Device identification |
| 0x0004 | Groups | 01, 02 | Group membership |
| 0x0005 | Scenes | 02 | Scene management (none defined) |
| 0x0006 | On/Off | 02 | Light on/off |
| 0x0008 | Level Control | 02 | Light brightness (0–254) |
| 0x001D | Descriptor | All | Endpoint metadata, device types |
| 0x0028 | Basic Information | 00 | Vendor, product, firmware info |
| 0x0040 | Fixed Label | 02, 03 | Endpoint labels |
| 0x0050 | Mode Select | 02, 03 | Light auto (EP 02), light channel (EP 03) |
| 0x0202 | Fan Control | 01 | Fan speed, mode, wind, direction |
| 0x0300 | Color Control | 02 | Color temperature (CT only) |
| 0x0402 | Temperature Measurement | 04 | Onboard temp (centidegrees C) |
| 0x0406 | Occupancy Sensing | 06 | PIR/presence detection |

---

## Confirmed Attribute Snapshot

Values observed from a full `refresh()` read (2026-02-15):

| Endpoint | Cluster | Attr | Type | Value | Interpretation |
|----------|---------|------|------|-------|----------------|
| 0x00 | 0x0028 | 0x0000 | UINT | 17 | DataModelRevision |
| 0x00 | 0x0028 | 0x0001 | STRING | "Big Ass Fans" | VendorName |
| 0x00 | 0x0028 | 0x0002 | UINT | 5202 | VendorID (0x1452) |
| 0x00 | 0x0028 | 0x0003 | STRING | "Haiku H/I Series" | ProductName |
| 0x00 | 0x0028 | 0x0004 | UINT | 3 | ProductID |
| 0x00 | 0x0028 | 0x0005 | STRING | "Bedroom Fan" | NodeLabel |
| 0x00 | 0x0028 | 0x0006 | STRING | "US" | Location |
| 0x00 | 0x0028 | 0x0007 | UINT | 1 | HardwareVersion |
| 0x00 | 0x0028 | 0x0008 | STRING | "1" | HardwareVersionString |
| 0x00 | 0x0028 | 0x0009 | UINT | 0 | SoftwareVersion (numeric) |
| 0x00 | 0x0028 | 0x000A | STRING | "3.3.0" | SoftwareVersionString |
| 0x01 | 0x0202 | 0x0000 | UINT | 2 | FanMode = Medium |
| 0x01 | 0x0202 | 0x0002 | UINT | 34 | PercentSetting = 34% |
| 0x01 | 0x0202 | 0x0003 | UINT | 34 | PercentCurrent = 34% (Speed 3) |
| 0x01 | 0x0202 | 0x000A | UINT | 0 | WindSetting = Normal |
| 0x01 | 0x0202 | 0x000B | UINT | 0 | AirflowDirection = Forward |
| 0x02 | 0x0006 | 0x0000 | BOOL | false | OnOff = off |
| 0x02 | 0x0008 | 0x0000 | UINT | 36 | CurrentLevel = 36 (~14%) |
| 0x02 | 0x0050 | 0x0002 | ARRAY-STRUCT | (see EP 02 modes) | SupportedModes: User, Auto |
| 0x02 | 0x0050 | 0x0003 | UINT | 0 | CurrentMode = User |
| 0x02 | 0x0300 | 0x0007 | UINT | 250 | ColorTemperatureMireds (4000K) |
| 0x03 | 0x0050 | 0x0002 | ARRAY-STRUCT | (see EP 03 modes) | SupportedModes: All Lights, Downlight, Uplight |
| 0x03 | 0x0050 | 0x0003 | UINT | 0 | CurrentMode = All Lights |
| 0x04 | 0x0402 | 0x0000 | INT | 2081 | Temperature = 20.81°C (69.5°F) |
| 0x06 | 0x0406 | 0x0000 | UINT | 1 | Occupancy = occupied |

**Data type observations:**
- On/Off (0x0006, attr 0x0000) arrives as **BOOL** (not INT) with `newParse:true`
- Temperature (0x0402, attr 0x0000) arrives as **INT** (signed, for negative temps)
- All Fan Control and Level attributes arrive as **UINT**
- SupportedModes arrives as **ARRAY-STRUCT** with nested tag/value maps
