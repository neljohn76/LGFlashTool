# LG Flash Tool

Flash LG firmware **from an Android phone** — no PC required. Connect the target LG device in
Download Mode (LAF) or Qualcomm EDL (9008) mode with a USB-OTG cable, pick a KDZ/DZ firmware,
and write it. This is the LG equivalent of [ErosFlashTool](https://github.com/Gabriel2392/ErosFlashTool)
(Odin-style flashing for Samsung, from a phone).

> ⚠️ **Warning: this tool writes directly to your device's storage. A wrong firmware, an
> interrupted transfer, or a wrong model file can hard-brick the phone. You are responsible
> for what you flash. Charge the target device and never disconnect the OTG cable mid-flash.**

## Features

- **LAF / LG Download Mode** — talks LG's LAF (LG Automated Flash) protocol directly over USB host,
  the same protocol LGUP / LG Flash Tool use on a PC:
  - HELO handshake with the AES-256-ECB challenge/response (`KILO CENT/METR`) authentication
  - Opens the raw disk and streams firmware at absolute block addresses
  - Reads the live GPT so you can pick a partition by name for raw images
  - Reboot / power-off controls (`CTRL RSET` / `CTRL POFF`)
- **Qualcomm EDL (9008)** — full Sahara handshake and firehose programmer upload, then raw
  sector programming to any LUN (UFS) or eMMC.
- **Firmware formats**
  - `.kdz` — LG's container; the embedded `.dz` payload is extracted (including zlib-wrapped variants)
  - `.dz` — LG's firmware archive; chunks are inflated, **MD5- and CRC32-verified** as they stream
  - raw `.img` / `.bin` — written to a partition you select from the device's GPT
  - `.tot` / BIN-style KDZ payloads are **not** supported yet
- Per-chunk progress with live speed, safety guard against writing the GPT area (blocks < 34)
  unless explicitly allowed, and a detailed on-device log.

## Requirements

- Host phone: **Android 7.0+ (API 24)** with USB-OTG / USB host support
- Target: LG 2016–2021 era (G6, G7, G8, V30, V35, V40, V50, V50S, V60, K series, Velvet…) in
  **Download Mode** (screen shows "Download Mode / Firmware Update"), or any Qualcomm LG in **EDL 9008**
- Firmware: a KDZ or DZ matching your exact model (e.g. `V500N30k_00.kdz` for the LG V50S)
- For EDL: a **device-specific firehose programmer** (`prog_ufs_firehose_<soc>.mbn` / `.elf`).
  LG firmware KDZs sometimes include it; otherwise you need the one matching your SoC. This app
  does **not** bundle programmers.

## Getting the APK

The project builds itself in the cloud:

1. Go to the **Actions** tab of this repository.
2. Wait for the latest workflow run (triggered on every push) to finish.
3. Download the **`LGFlashTool-apks`** artifact and unzip it — it contains both
   `app-debug.apk` and the signed `app-release.apk`.
4. Sideload the APK on your phone.

To build locally: open the project in Android Studio, or run `./gradlew assembleDebug`.

## How to use

### LAF mode (recommended)

1. Boot the target LG into Download Mode, connect it to your phone with an OTG cable.
2. In the app, choose **LAF**, tap **Connect** — the GPT partition list is read and logged.
3. Tap **Pick firmware** and select your KDZ/DZ. All chunks are listed and pre-checked.
4. Review the chunks (you can deselect, e.g. leave out `userdata`), press **FLASH**, confirm.
5. Optionally enable auto-reboot so the target restarts when done.

### EDL mode (for bricked/unresponsive devices)

1. Get the target into EDL 9008 (test point or button combo) and connect via OTG.
2. Choose **EDL**, tap **Connect** — the Sahara HELLO is read automatically.
3. Tap **Pick programmer** and select your firehose `.mbn`/`.elf`.
4. Pick a raw image file, set the LUN and start sector, press **FLASH**.

## Status / validation

The LAF protocol implementation is a faithful port of the reverse-engineered protocol from
[Lekensteyn/lglaf](https://github.com/Lekensteyn/lglaf) (MIT), the KDZ/DZ parsing follows
[ehem/kdztools](https://github.com/ehem/kdztools) (GPLv3), and EDL follows
[bkerler/edl](https://github.com/bkerler/edl) (GPLv3). The code paths have **not yet been
hardware-validated end-to-end** — the first flash on a real device should be done deliberately,
with the correct model firmware and a full backup mindset. Notable assumptions:

- DZ `targetAddr` is treated as an absolute 512-byte block index (per kdztools).
- LAF READ/WRTE offsets are in 512-byte blocks, and writes use 16 KiB max bodies
  (31 × 512 bytes per command, as in lglaf).
- Firehose programming assumes 4096-byte sectors for UFS LUNs.

## How it works

```
                    USB OTG (host mode)
 ┌──────────┐   bulk endpoints    ┌─────────────────────┐
 │  phone   │◄──────────────────►│  LG target          │
 │  (app)   │   LAF protocol     │  Download Mode      │
 └──────────┘   or Sahara+Firehose  └─────────────────────┘
      │
      │ KDZ → DZ → zlib chunks (streamed, MD5/CRC verified)
      ▼
  GPT-aware block writer
```

## Credits & license

- [Lekensteyn/lglaf](https://github.com/Lekensteyn/lglaf) — LAF protocol documentation (MIT)
- [ehem/kdztools](https://github.com/ehem/kdztools) — KDZ/DZ format documentation (GPLv3)
- [bkerler/edl](https://github.com/bkerler/edl) — Sahara/Firehose reference (GPLv3)
- [Gabriel2392/ErosFlashTool](https://github.com/Gabriel2392/ErosFlashTool) — the original
  "flash Samsung from your phone" inspiration

Licensed under the **GNU GPL v3** — see [LICENSE](LICENSE).
