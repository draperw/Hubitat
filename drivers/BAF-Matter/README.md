# Big Ass Fans Matter Driver — User Guide

A community Hubitat Elevation driver for Big Ass Fans ceiling fans paired over Matter. Supports fan speed control, direction, wind modes, auto mode, light kit brightness and color temperature, temperature and occupancy sensors, health monitoring, and runtime capability discovery across BAF fan variants.

## Requirements

- Hubitat Elevation hub with Matter support
- Big Ass Fans firmware **3.3 or newer**

## Installation

This driver requires **two** driver files:

1. **BAF Fan Matter** (parent) — manages the fan, temperature sensor, occupancy sensor, and health monitoring
2. **BAF Light Matter** (child) — manages the light kit, created automatically by the parent when a light is detected

### Steps

1. In Hubitat, go to **Drivers Code** and add both driver files.
2. Pair your BAF fan via **Devices → Add Device → Matter**.
3. Once paired, assign the **BAF Fan Matter** driver to the device.
4. Click **Configure**. The driver will discover your fan's capabilities and create the light child device if your fan has a light kit.

> **Note:** Do not manually create the child device. The parent creates it automatically based on the fan's endpoint discovery.

---

## Fan Controls (Parent Device)

### Speed

The fan has 7 discrete speed levels. You can control speed in multiple ways:

| Method | How |
|--------|-----|
| **Set Speed** (dropdown) | Choose from Off, Speed 1–7 |
| **Set Level** (slider/number) | Set a percentage (0–100%). Mapped to the nearest BAF speed |
| **On** / **Off** (buttons) | **On** restores the last-used speed; **Off** stops the fan |
| **Cycle Speed** (button) | Advance to the next speed step, wrapping from Speed 7 back to Off |
| **Fan Speed Up** (button) | Increase by one speed step (stops at Speed 7) |
| **Fan Speed Down** (button) | Decrease by one speed step (stops at Off) |

#### Speed ↔ Percent Mapping

BAF uses 7 discrete speeds mapped to percent ranges. The **Set %** column shows the value the driver sends when you select that speed. Any percent within the range maps to that BAF speed.

| BAF Speed | % Low | Set % | % High |
|-----------|-------|-------|--------|
| 1 | 1 | 1 | 9 |
| 2 | 10 | 18 | 25 |
| 3 | 26 | 34 | 42 |
| 4 | 43 | 51 | 58 |
| 5 | 59 | 67 | 75 |
| 6 | 76 | 84 | 91 |
| 7 | 92 | 100 | 100 |

### Auto Mode

Auto mode lets the fan adjust speed automatically based on room conditions.

- **Auto On** — Enable auto mode. The fan chooses its own speed.
- **Auto Off** — Disable auto mode. Restores the previous manual speed.


### Airflow Direction

- **Direction Forward** — Normal downward airflow
- **Direction Reverse** — Reverse (updraft) for winter mode

### Whoosh (Wind Mode)

- **Whoosh On** — Periodic speed variation that simulates a natural breeze
- **Whoosh Off** — Steady airflow at the set speed

### On Behavior

**On** always restores the last manual speed (stored internally). Defaults to Speed 3 (34%) on fresh install.

### Attributes

| Attribute | Capability | Values | Description |
|-----------|------------|--------|-------------|
| `switch` | Switch | on, off | Fan on/off state |
| `level` | SwitchLevel | 0–100 | Fan speed as a percentage |
| `speed` | FanControl | Off, Speed 1–7 | Fan speed name |
| `bafSpeed` | Custom | 0–7 | Numeric BAF speed level |
| `fanMode` | Custom | Off, Low, Medium, High, On, Auto | Matter fan operating mode |
| `airflowDirection` | Custom | Forward, Reverse | Blade rotation direction |
| `windMode` | Custom | Normal, Whoosh | Wind effect mode |
| `temperature` | TemperatureMeasurement | Numeric | Onboard temperature (°F or °C) |
| `motion` | MotionSensor | active, inactive | Occupancy sensor state |
| `healthStatus` | Custom | online, offline, unknown | Device connectivity status (see Health Monitoring) |
| `rtt` | Custom | Milliseconds | Round-trip time from last Ping (see Health Monitoring) |

---

## Light Controls (Child Device)

The child device appears automatically if the fan has a light kit. It supports brightness and color-temperature-mode lighting. The fan reports all lights as CT. The driver detects whether CT is adjustable or fixed from Matter attributes at runtime. Some fans may have both a downlight and an uplight.

- **Adjustable CT fan:** supports a CT range (usually 2700K–4000K).
- **Fixed CT fan (dimmer behavior only):** reports one CT value only. On these lights, **Set Color Temperature** logs a fixed-CT info message and takes no action.
- **Dual light fan:** Fans with both a downlight and an uplight also support light mode selection. Attempts to change the light mode on single light fan logs a single light message and takes no action. 

### Brightness

BAF uses 16 discrete brightness levels internally. The driver maps standard 0–100% values to these levels.

| Method | How |
|--------|-----|
| **On** / **Off** (buttons) | **On** sets level based on selection noted below |
| **Set Level** (slider/number) | Set brightness 0–100%. Level 0 turns off |
| **Set Baf Level** (number input) | Set a specific BAF level (0=off, 1–16) directly |
| **Start Level Change** / **Stop Level Change** | Smooth ramp up or down |

#### On Behavior

The **On** command behavior is controlled by two preferences:

- **Use last level for on** (default: enabled) — Restores the brightness level from before the light was turned off. Falls back to 100% if no previous level is stored.
- **On level** — When "use last level" is disabled, turns on to this fixed percentage.

When "use last level" is disabled and "On level" is blank, **On** sends a simple on command with no level change — the fan hardware controls the level (which is usually the previous level).

#### Level ↔ Percent Mapping

BAF uses 16 discrete brightness levels mapped to percent ranges. The **Set %** column shows the value the driver sends for that BAF level. Any percent within the range maps to that BAF level.

| BAF Level | % Low | Set % | % High |
|-----------|-------|-------|--------|
| 1 | 1 | 1 | 4 |
| 2 | 5 | 8 | 10 |
| 3 | 11 | 14 | 17 |
| 4 | 18 | 21 | 24 |
| 5 | 25 | 27 | 30 |
| 6 | 31 | 34 | 37 |
| 7 | 38 | 41 | 43 |
| 8 | 44 | 47 | 50 |
| 9 | 51 | 54 | 57 |
| 10 | 58 | 60 | 63 |
| 11 | 64 | 67 | 70 |
| 12 | 71 | 74 | 76 |
| 13 | 77 | 80 | 83 |
| 14 | 84 | 87 | 90 |
| 15 | 91 | 93 | 96 |
| 16 | 97 | 100 | 100 |

#### Level Ramp Speed

The **Level ramp speed** preference controls how fast **Start Level Change** ramps:

| Setting | Speed |
|---------|-------|
| Fast | 0.5s per BAF level step (~8s full ramp) |
| Normal (default) | 1s per step (~16s full ramp) |
| Slow | 1.5s per step (~24s full ramp) |
| Very slow | 2s per step (~32s full ramp) |

Use **Stop Level Change** to freeze the ramp at any point. Sending **On**, **Off**, or **Set Level** also automatically cancels any pending ramp.

### Color Temperature

- **Set Color Temperature** — Set color temperature in Kelvin within the detected device range.
- Accepts an optional **level** (brightness %) and **transition time** (seconds). Both are independent of CT and default to no change if omitted.
- If a level is provided, brightness is set at the same time as CT.
- If a transition time is provided, it applies to both the CT command and the level change (if any).

> On fixed-CT fans, `Set Color Temperature` logs `fixed CT (...K only)` and does not send a CT command.

### Light Mode (2-Light Fans Only)

Fans with both an uplight and downlight can select which light to control:

- **Control All Lights** — Both lights active
- **Control Downlight** — Downlight only
- **Control Uplight** — Uplight only

> These commands are only available on 2-light fans. Single-light fans log "not available."

### Light Auto Mode

- **Light Auto On** — Enable automatic light control (adjusts based on occupancy/ambient light)
- **Light Auto Off** — Return to manual control

### Attributes

| Attribute | Capability | Values | Description |
|-----------|------------|--------|-------------|
| `switch` | Switch | on, off | Light on/off state |
| `level` | SwitchLevel | 0–100 | Brightness as a percentage |
| `bafLevel` | Custom | 0–16 | BAF discrete light level |
| `colorTemperature` | ColorTemperature | Kelvin | Color temperature |
| `colorMode` | ColorMode | CT | Always CT (no RGB on this device) |
| `lightMode` | Custom | All Lights, Downlight, Uplight | Active light channel (2-light fans) |
| `lightAuto` | Custom | on, off | Light auto mode state |

---

## Health Monitoring

The driver monitors device connectivity with configurable health checks.

### Settings

| Preference | Options | Default |
|------------|---------|---------|
| **Health Check Method** | Disabled, Activity check, Periodic polling | Periodic polling |
| **Health Check Interval** | 1 min – 12 hours | Every 15 minutes |

- **Periodic polling** sends a **Ping** at the configured interval. Three consecutive missed responses mark the device offline.
- **Activity check** monitors for any incoming messages. If none arrive within the interval, the device is marked offline.
- The `healthStatus` attribute is mirrored to the light child device.

### Attributes

| Attribute | Values | Description |
|-----------|--------|-------------|
| `healthStatus` | online, offline, unknown | Current connectivity status |
| `rtt` | Milliseconds | Round-trip time from last **Ping** |

---

## Variant Support

Not all BAF fan models have every feature. Since Hubitat does not support adding or removing capabilities at runtime, all capabilities are declared on every device. Features that your fan doesn't have are handled gracefully:

### Parent Device

| Feature | If Absent |
|---------|-----------|
| Temperature sensor (EP 04) | `temperature` attribute stays blank. No events fire. |
| Occupancy sensor (EP 06) | `motion` attribute stays blank. No events fire. |
| Fan auto mode | Supported on all models. | 
| Light kit (EP 02) | No child device is created. |

### Child Device

| Feature | If Absent |
|---------|-----------|
| Light mode / uplight | **Control All Lights** / **Control Downlight** / **Control Uplight** log "not available." |
| Light auto mode | Supported on all models. |
| Adjustable color temperature | **Set Color Temperature** logs fixed-CT info and takes no action. Light operates as dimmer behavior. |

---

## Logging

Both drivers use leveled logging with an auto-disable timer for verbose levels.

### Settings

| Preference | Options | Default |
|------------|---------|---------|
| **Logging Level** | Error, Warn, Info, Debug, Trace | Info |
| **Debug/Trace Log Duration** | 30 min – 3 hours, or Indefinitely | 30 Minutes |

- **Info** is recommended for normal use. Shows speed changes, mode changes, and important events.
- **Debug** adds command-level detail (useful for troubleshooting).
- **Trace** adds raw Matter protocol detail (useful for driver development).
- When set to Debug or Trace, logging automatically reverts to the previous level after the configured duration.

---

## Lifecycle

- **Configure** — Re-runs full initialization: endpoint discovery, subscription setup, and child device management. Use after a firmware update or if the fan seems misconfigured.
- **Initialize** — Runs automatically on hub reboot. Subscriptions are set up immediately, then updated again once endpoint discovery completes — ensuring temperature and occupancy sensors are correctly included for fans that have them. Can also be run manually from the device page if subscriptions seem stale.
- **Refresh** — Reads all current values from the fan. The parent's **Refresh** covers both fan and light attributes. Use if attributes seem stale after a network hiccup.
- **Save Preferences** — Saving preferences automatically re-subscribes to device attributes. No need to run Configure after changing preferences.
