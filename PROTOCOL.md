# R99 ring Bluetooth protocol

Recovered on 2026-08-03 from a ring reporting itself as `R99 8D43`, by capturing the vendor
app (`com.zhuoting.healthyucheng`, "SmartHealth") with the Android HCI snoop log and replaying
the frames from this app. Every claim marked **verified** was reproduced by sending our own
frame and observing the ring behave; anything else is inference and should be treated as such.

Ring firmware differs between R99 batches. Re-capture before trusting this on another ring.

## Frame format — verified

```
group | command | total length (uint16 LE) | payload | CRC (uint16 LE)
```

`total length` counts the entire frame, header and CRC included, so a frame with no payload is
6 bytes. **A frame whose length field is wrong does not get ignored — the firmware drops the
connection.** That is the one genuinely destructive mistake available here, and it is
recoverable by reconnecting.

The ring echoes `group` and `command` back in its reply, so requests and responses pair up
without needing sequence numbers.

### CRC — verified

CRC-16/CCITT-FALSE: polynomial `0x1021`, initial value `0xFFFF`, no input or output
reflection, no final XOR. Appended little-endian. Recovered by brute-forcing the parameter
space against 11 captured frames; the solution was unique and reproduces every captured frame
byte-for-byte.

```python
def crc16(buf):
    reg = 0xFFFF
    for b in buf:
        reg ^= b << 8
        for _ in range(8):
            reg = ((reg << 1) ^ 0x1021) & 0xFFFF if reg & 0x8000 else (reg << 1) & 0xFFFF
    return reg
```

## GATT map

| Handle | Characteristic | Role |
|---|---|---|
| `0x000C` | `be940001` | **command channel** — write frames here, replies indicate back |
| `0x0011` | `be940003` | ring-initiated data (live heart rate) |
| `0x001D` | `fea1` | 10-byte status frame, pushed every ~2 s |
| `0x006E` | `2a37` | standard SIG Heart Rate Measurement |
| `0x0082` / `0x0084` | `ae01` / `ae02` | vendor authentication handshake |

`2a38` (Body Sensor Location) reads `03` = Finger.

## Commands

Written to `be940001`. Payload excludes the header and CRC, which are computed.

| Group/cmd | Payload | Meaning | Status |
|---|---|---|---|
| `01 00` | `YY YY MM DD HH MM SS 00` | set clock, year uint16 LE | verified by matching wall clock |
| `03 2F` | `01 00` | start heart-rate measurement | **verified** — LED lights, values flow |
| `03 2F` | `01 02` | start SpO2 measurement | inferred, same shape |
| `03 2F` | `00 00` | stop measurement | inferred |
| `02 00` | `47 43` (`"GC"`) | `GetDeviceInfo`, 30-byte reply | captured, not decoded |
| `02 01` | `47 46` (`"GF"`) | `GetDeviceSupportFunction`, 66-byte capability bitmap | captured, not decoded |
| `02 0C` | none | `GetNowStep` — today's steps, distance, calories | **verified** |
| `02 25` | none | `GetPowerStatistics` — includes battery percentage | **verified** |
| `05 02`/`04`/`06`/`1A` | none | stored history: sport, sleep, heart, blood oxygen | verified as reachable; all returned zero records |

The two literal payloads `"GC"` and `"GF"` are copied from the vendor app. Queries the ring does
not implement answer with a single payload byte `FC` — on this ring that covers
`GetSleepStatus`, `GetMeasurementFunction` and `GetRingSizeAndColor`.

The full 329-command table, lifted from the vendor SDK, is in [COMMANDS.md](COMMANDS.md).

### Ring-initiated

| Group/cmd | Payload | Meaning | Status |
|---|---|---|---|
| `06 01` | `<bpm>` | live heart rate, one byte | **verified** — matches `2a37` reading for reading |
| `04 0E` | `<type> 01` | measurement complete; app acknowledges with `04 0E` + `00` | **verified** |
| `05 80` | varies | data push | captured, not decoded |

## Reading a heart rate — verified end to end

1. Connect to the ring (it is paired, so it does not advertise; connect by address).
2. Subscribe to `2a37` and `be940003`.
3. Write `03 2F 08 00 01 00 4F 1B` to `be940001`.
4. The ring acknowledges `03 2F 07 00 00 EE 99` and its LED begins flashing.
5. Readings arrive for ~30 s on both `2a37` and as `06 01` frames on `be940003`.
6. The ring sends `04 0E 08 00 00 01 FB 52` when finished.

**The `2a37` sensor-contact bit is unreliable on this firmware** — it reports "not detected"
even while returning genuine, varying readings. Treat a non-zero value as the signal, not the
contact flag. Note also that `2a37` holds its last value indefinitely when idle, so a plausible
number does not mean a fresh one; only trust readings during an active measurement.

## Steps — verified

`fea1` is the live activity counter, not a static frame. Its payload matches the reply to
`GetNowStep` field for field:

```
fea1        07 | 41 00 00 | 2F 00 00 | 04 00      pushed every ~2 s
GetNowStep     | 41 00 00 | 04 00 | 2F 00 00 …    on request
```

`0x41` = 65 steps, with distance and calorie counters alongside. Watching `fea1` is therefore
enough to track steps live without polling.

## Firmware

The ring is a Nordic nRF5x running Nordic DFU (`no.nordicsemi.android.dfu` appears throughout
the vendor app). Vendor images are served from `https://staticpage.ycaviation.com/firmware/`.

**Firmware cannot be read off the ring.** The OTA group is `OTADownload`, `OtaSend` and
`OtaBlock` — all push. No read-out command exists anywhere in the 329-command table, Nordic DFU
is upload-only, and nRF52 parts ship with readback protection that also blocks SWD. Obtaining
the vendor's image from their server is possible; extracting the running image is not.

## Not yet decoded

- **SpO2 values.** `AppStartMeasurement` with type `02` starts it; the value encoding is
  unconfirmed. `Real_UploadBloodOxygen` (`06 02`) is the frame to watch.
- **The capability bitmap** returned by `GetDeviceSupportFunction` — this is what would say
  definitively which features the firmware implements.
- **The `ae01`/`ae02` handshake.** Magic header `FE DC BA`, then 16-byte encrypted blobs, then
  literal ASCII `"pass"`. Not required for anything above — the command channel answered our
  frames without it.

## Privacy note

The vendor app uploads health data to `web-api.ycaviation.com` (`upheart`, `upblood`,
`upsleep`, `upsport`, `upHrv`, `upmac`). Nothing in this repository contacts a network.
