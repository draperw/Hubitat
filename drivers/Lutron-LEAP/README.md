# Lutron LEAP for Hubitat

A Hubitat Elevation app and drivers for **Lutron Caseta** (Smart Bridge) and **Lutron RadioRA 3** (processor) over
LEAP, Lutron's JSON protocol on port 8081 with mutual TLS. It runs alongside Hubitat's built-in Lutron integration, on
the same hub and the same bridge, with its own pairing.

## What it does

- **Caseta:** dimmers, switches, Picos (standard with hold and double tap, or fast), scenes; occupancy (one motion
  device per room with sensors), offered as untested: no Caseta sensor has been tried.
- **RadioRA 3:** dimmers and switches, Picos, keypads (buttons, Push, LEDs), occupancy (one motion device per area).
- **Both:** pairing by the bridge button; finding the bridge by mDNS and following it to a new address; a device
  picker with a room filter; device names that follow Lutron (labels too, for the devices you pick); changes made in
  the Lutron app or Designer picked up within 10 minutes; `networkStatus` on the bridge device and `healthStatus` on
  it and on the dimmer, Pico and keypad devices (Hubitat's Generic Component drivers, used for switches, occupancy and
  keypad LEDs, don't keep `healthStatus`); connects that never stall the hub (each first checks, with an async request,
  that the address answers); a log of recent connection drops on the bridge device.
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

## Pico and keypad buttons

- **Picos** are numbered as Hubitat's own Lutron integrations number them, top to bottom: on 1, raise 2, favourite 3,
  lower 4, off 5; a 2-button or paddle Pico is on 1, off 2. Rules written for Hubitat's Lutron integrations carry over
  with a device change.
- **Standard Pico driver:** a quick press is `pushed` 0.75 s after the release, the *Double-tap window* in which a second
  press makes it `doubleTapped`; a press past the *Hold time* (1 s) is `held`, then `released`. Set the window to Off for
  `pushed` at the release (and no double tap), or use the **Fast Pico** driver for `pushed` at the press.
- **Keypads** (RadioRA 3) use Lutron's own button numbers (raise / lower 18 / 19 on a wall keypad); an LED takes its
  button's number.

## After a change in the Lutron app or Designer

The bridge device checks every 10 minutes whether anything changed on the Lutron side (a device added, removed,
renamed or moved to another room; on Caseta also a scene added, renamed or deleted) and reads the device list again
when it did. To get a change at once instead of waiting for that check, do either of these; they run the same read:

- In the app: *Devices* > **Refresh device list**.
- On the bridge device's page: **Discover**.

The read takes about a second on a Caseta bridge and about ten seconds on a RadioRA 3 processor with some 40 zones.
After it, device names follow Lutron (labels too, for the devices picked to follow), a new device is offered under
*Add Devices* (it isn't added by itself), and a new Caseta scene has its number, listed on the *Devices* page, for
Push on the bridge device. A Designer transfer needs neither: the processor restarts, and the bridge device reads the
list again when it reconnects.

## Licence

Apache License 2.0 (the `LICENSE` file at the top of the repository). Anyone may use, modify and redistribute the code,
commercially too; redistributors keep the copyright and licence notices that are in the files.

## Credits

The LAP pairing certificate, key and CA and the Lutron root CA come from
[pylutron-caseta](https://github.com/gurumitts/pylutron-caseta) (Apache-2.0), which also documents much of LEAP.

The logging functions in each file are adapted from Jeff Page's (@jtp10181)
[Hubitat drivers](https://github.com/jtp10181/Hubitat) (Apache-2.0).
