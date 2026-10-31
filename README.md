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
  - `KILO CENT/METR` AES-256-ECB challenge/response authentication (ported byte-for-byte from
    lglaf's `laf_crypto.py` and covered by that project's official test vectors)
  - HELO handshake, opens the raw disk and streams firmware at absolute block addresses
  - Writes are always **512-byte block aligned**; READ/WRTE/ERSE responses are verified against
    the request (fd / offset / length echo, as lglaf asserts)
  - Reads the live GPT so you can pick a partition by name for raw images
  - Reboot / power-off controls (`CTRL RSET` / `CTRL POFF`)
- **Qualcomm EDL (9008)** — full Sahara handshake (including 64-bit `READ_DATA_64` requests) and
  firehose programmer upload, then raw sector programming to any LUN. The storage sector size is
  taken from the firehose configure result (UFS 4096 / eMMC 512) and overridden by
  `getstorageinfo` when the programmer reports it; write ranges are checked against the reported
  capacity when available. **EDL mode supports raw images only** (no KDZ/DZ).
- **Firmware formats**
  - `.kdz` — LG's container; the embedded `.dz` payload is extracted (including zlib-wrapped variants)
  - `.dz` — LG's firmware archive; **every selected chunk is fully decompressed and MD5/CRC32-verified
    BEFORE anything is erased or written**, then streamed with per-chunk hash verification again
  - raw `.img` / `.bin` — written to a partition you select from the device's GPT (size-checked
    against the partition)
  - `.tot` / BIN-style KDZ payloads are **not** supported yet
- **Safety guards**
  - Pre-flash integrity pass (decompress + verify all chunks before the first erase)
  - Chunk targets bounded by the device GPT's last usable LBA when the GPT is readable
  - GPT area (blocks < 34) requires an explicit opt-in, matching lglaf's own sanity check
  - Parser hardening: KDZ/DZ headers are bounds-checked against the real file size, entry and
    chunk counts are capped, and the KDZ zlib-inflate path refuses multi-GB in-memory fallbacks
  - The confirmation dialog shows the exact target (blocks / partition / LUN + sector)

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
2. Wait for the latest workflow run (triggered on every push) to finish — it also runs the
   unit test suite.
3. Download the **`LGFlashTool-apks`** artifact and unzip it — it contains both
   `app-debug.apk` and the signed `app-release.apk`.
4. Sideload the APK on your phone.

To build locally: open the project in Android Studio, or run `./gradlew testDebugUnitTest assembleDebug`.

## How to use

### LAF mode (recommended)

1. Boot the target LG into Download Mode, connect it to your phone with an OTG cable.
2. In the app, choose **LAF**, tap **Connect** — the GPT partition list is read and logged.
3. Tap **Pick firmware** and select your KDZ/DZ. All chunks are listed and pre-checked.
4. Review the chunks (you can deselect, e.g. leave out `userdata`), press **FLASH**, confirm.
   The firmware is verified first; only then does the write phase start.
5. Optionally enable auto-reboot so the target restarts when done.

### EDL mode (for bricked/unresponsive devices)

1. Get the target into EDL 9008 (test point or button combo) and connect via OTG.
2. Choose **EDL**, tap **Connect** — the Sahara HELLO is read automatically.
3. Tap **Pick programmer** and select your firehose `.mbn`/`.elf`.
4. Pick a **raw image file** (KDZ/DZ is not supported in EDL mode), set the LUN and start
   sector, press **FLASH**.

## Status / validation

The LAF protocol implementation is a faithful port of the reverse-engineered protocol from
[Lekensteyn/lglaf](https://github.com/Lekensteyn/lglaf) (MIT), the KDZ/DZ parsing follows
[ehem/kdztools](https://github.com/ehem/kdztools) (GPLv3), and EDL follows
[bkerler/edl](https://github.com/bkerler/edl) (GPLv3). The protocol handling is covered by JVM
unit tests (LAF framing/CRC/KILO vectors, GPT parsing, KDZ/DZ synthetic fixtures, Sahara packet
framing, block-aligned write buffering) which run in CI on every push. **The code paths have
not yet been hardware-validated end-to-end** — the first flash on a real device should be done
deliberately, with the correct model firmware and a full backup mindset. Remaining
hardware-dependent assumptions:

- DZ `targetAddr` is treated as an absolute 512-byte block index (per kdztools).
- LAF addressing (block offsets, byte lengths, sector erase) follows lglaf's implementation
  exactly, but has not been exercised against a physical download-mode device by this project.
- Firehose sector size defaults to 4096 (UFS) / 512 (eMMC) unless `getstorageinfo` reports one.

## Release signing

The published APKs are signed with the **debug key** so they can be sideloaded immediately.
This is a development build, not a production-signed release: Android will not verify a
publisher identity for it. To produce a production-signed build, create your own keystore and
point `signingConfig` at it (see the Android docs on signing).

## How it works

```
                    USB OTG (host mode)
 ┌──────────┐   bulk endpoints    ┌─────────────────────┐
 │  phone   │◄──────────────────►│  LG target          │
 │  (app)   │   LAF protocol     │  Download Mode      │
 └──────────┘   or Sahara+Firehose  └─────────────────────┘
      │
      │ KDZ → DZ → zlib chunks (verify first, then stream, MD5/CRC verified)
      ▼
  GPT-aware block-aligned writer
```

## Credits & license

- [Lekensteyn/lglaf](https://github.com/Lekensteyn/lglaf) — LAF protocol documentation (MIT)
- [ehem/kdztools](https://github.com/ehem/kdztools) — KDZ/DZ format documentation (GPLv3)
- [bkerler/edl](https://github.com/bkerler/edl) — Sahara/Firehose reference (GPLv3)
- [Gabriel2392/ErosFlashTool](https://github.com/Gabriel2392/ErosFlashTool) — the original
  "flash Samsung from your phone" inspiration

Licensed under the **GNU GPL v3** — see [LICENSE](LICENSE).
