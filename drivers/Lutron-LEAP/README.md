# Lutron LEAP for Hubitat

A Hubitat Elevation app and drivers for **Lutron Caseta** (Smart Bridge) and **Lutron RadioRA 3** (processor) over
LEAP, Lutron's JSON protocol on port 8081 with mutual TLS. It runs alongside Hubitat's built-in Lutron integration, on
the same hub and the same bridge, with its own pairing.

## What it does

- **Caseta:** dimmers, switches, Picos (standard with hold and double tap, or fast), scenes.
- **RadioRA 3:** dimmers and switches, Picos, keypads (buttons, Push, LEDs), occupancy (one motion device per area).
- **Both:** pairing by the bridge button; finding the bridge by mDNS and following it to a new address; a device
  picker with a room filter; device names that follow Lutron (labels too, for the devices you pick); changes made in
  the Lutron app or Designer picked up within 10 minutes; `healthStatus` on every device; reconnects that never stall
  the hub; a log of recent connection drops on the bridge device.
- **Untested:** HomeWorks QSX and Athena processors. They use the same LEAP model as RadioRA 3, so they are offered,
  marked untested.
- **Not yet:** fans, shades, Ketra and Lumaris lights, contact closures.

## Files

| File | What it is |
|---|---|
| `Lutron LEAP Integration (User).groovy` | The app (Apps code). One instance per bridge or processor; listed under *Integrations* |
| `Lutron LEAP Bridge (User).groovy` | The bridge device's driver: the LEAP connection |
| `Lutron LEAP Dimmer (User).groovy` | Dimmer zones |
| `Lutron LEAP Pico (User).groovy` | Picos: pushed, held, released, double tap |
| `Lutron LEAP Fast Pico (User).groovy` | Picos: pushed at the press, released at the release |
| `Lutron LEAP Keypad (User).groovy` | RadioRA 3 keypads |

Switch zones, occupancy areas and keypad LED devices use Hubitat's own Generic Component drivers.

## Install and set up

Requires Hubitat 2.5.0 or later (tested on 2.5.3).

1. Add the app under *Apps code* and the five drivers under *Drivers code* (each file's *Import* button takes its raw
   GitHub URL).
2. *Integrations* > *Add user integration* > **Lutron LEAP Integration (User)**.
3. *Lutron System Configuration*: pick the bridge or processor found on the network (or type its IP address), then
   pair, pressing the button on the bridge or processor when asked.
4. *Add Devices*: tick the devices to add. Use one app instance per bridge or processor.

## Licence

Apache License 2.0 (the `LICENSE` file at the top of the repository). Anyone may use, modify and redistribute the code,
commercially too; redistributors keep the copyright and licence notices that are in the files.

## Credits

The LAP pairing certificate, key and CA and the Lutron root CA come from
[pylutron-caseta](https://github.com/gurumitts/pylutron-caseta) (Apache-2.0), which also documents much of LEAP.

The logging functions in each file are adapted from Jeff Page's (@jtp10181)
[Hubitat drivers](https://github.com/jtp10181/Hubitat) (Apache-2.0).
