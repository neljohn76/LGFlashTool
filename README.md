<div align="center">

# LG Flash Tool

### Flash LG firmware directly from an Android phone.

**No Windows PC required.** Connect an LG device over USB OTG, inspect compatible firmware, and use the supported LAF or Qualcomm EDL workflow.

<p>
  <a href="https://github.com/neljohn76/LGFlashTool/actions/workflows/android.yml">
    <img src="https://img.shields.io/github/actions/workflow/status/neljohn76/LGFlashTool/android.yml?branch=main&style=for-the-badge&logo=githubactions&logoColor=white&label=Android%20CI" alt="Android CI">
  </a>
  <a href="https://github.com/neljohn76/LGFlashTool/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/neljohn76/LGFlashTool?style=for-the-badge" alt="License">
  </a>
  <img src="https://img.shields.io/badge/Platform-Android%207.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 7.0 or newer">
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin">
</p>

<p>
  <a href="#features">Features</a> ·
  <a href="#supported-modes-and-firmware">Supported modes</a> ·
  <a href="#getting-the-apk">Get the APK</a> ·
  <a href="#usage">Usage</a> ·
  <a href="#architecture">Architecture</a> ·
  <a href="#safety-first">Safety</a>
</p>

</div>

---

> [!CAUTION]
> **Flashing firmware can permanently damage a device.** An incorrect model/region firmware, wrong partition or sector, corrupted image, or interrupted write can cause data loss or a hard brick. Confirm the exact device variant and firmware before proceeding. Keep the USB OTG connection stable, maintain sufficient battery, and do not disconnect the target during an active write.

## Overview

LG Flash Tool is an Android application for communicating with LG devices through USB Host / OTG. It implements two device communication paths:

- **LAF (LG Download Mode):** communicates with the LG Download Mode interface and supports LG KDZ/DZ firmware workflows, plus raw partition images.
- **Qualcomm EDL (9008):** uses the Sahara protocol to upload a device-compatible Firehose programmer and then programs raw images to a selected storage LUN and sector.

The project is inspired by the idea behind [ErosFlashTool](https://github.com/Gabriel2392/ErosFlashTool), adapted for LG firmware and Android USB Host workflows.

**Project status:** the repository includes protocol implementations and JVM unit tests, but end-to-end flashing has not yet been validated on physical hardware. A passing CI build is not proof that a particular phone, programmer, firmware package, or cable combination is safe.

## Features

| Component | Current capabilities |
|---|---|
| LG LAF | KILO challenge/response authentication, HELO handshake, disk access, GPT reading, block writes, reboot and power-off commands |
| Qualcomm EDL | Sahara handshake, 64-bit read-data requests, Firehose programmer upload, storage configuration, raw sector programming |
| KDZ | Container parsing and extraction of the embedded DZ payload, including supported zlib-wrapped variants |
| DZ | Chunk metadata parsing, decompression, MD5/CRC32 validation, and selected-chunk flashing through LAF |
| Raw images | `.img` and `.bin` partition images, with LAF partition selection or EDL LUN/sector targeting |
| Safety checks | Firmware validation before DZ writes, target-range checks, GPT-aware protections, and destructive-action confirmation |
| USB | Android USB Host discovery, permission handling, and bulk-transfer communication |

### Firmware format support

| Format | LAF / Download Mode | Qualcomm EDL |
|---|---:|---:|
| `.kdz` | Supported through DZ extraction | Not supported |
| `.dz` | Supported | Not supported |
| Raw `.img` / `.bin` | Supported for a selected GPT partition | Supported for a selected LUN and start sector |
| `.tot` | Not supported | Not supported as a container |

EDL requires a Firehose programmer compatible with the target device and its chipset/storage configuration. The application does not bundle device-specific programmers.

## Supported modes and compatibility

### LAF — LG Download Mode

Use this path when the target displays **Download Mode / Firmware Update** and exposes a compatible LG LAF USB interface.

The current implementation includes:

- KILO AES-256-ECB challenge/response authentication.
- LAF HELO handshake and disk access.
- GPT partition-table reading for partition selection.
- DZ chunk selection and pre-flash integrity verification.
- Raw image writing to a selected partition.
- Reboot and power-off commands.

### Qualcomm EDL — 9008

Use this path only when the target exposes a compatible Qualcomm Emergency Download interface.

The current implementation includes:

- Sahara handshake and programmer upload.
- Firehose configuration.
- Storage-sector geometry handling from the programmer/device response where available.
- Raw image programming to a selected LUN and start sector.

**EDL does not currently flash KDZ or DZ containers.** Convert or extract firmware only through a verified workflow that produces the correct raw partition image; never assume a file is suitable based on its extension.

### Device compatibility

The project is intended for LG devices that expose a compatible LAF or Qualcomm EDL implementation. The original project notes list LG models from roughly the 2016–2021 generation—including G, V, K, Velvet, and related series—as potential targets.

That list is **not a certification or guarantee**. USB identifiers, chipset, partition layout, firmware variant, bootloader state, and protocol behavior differ by model and region. Verify the exact device before attempting any write.

## Requirements

### Android host phone

- Android 7.0 or newer (API 24+).
- USB Host / USB OTG support.
- A suitable USB-OTG adapter and data-capable cable.
- Enough free storage for the selected firmware and any temporary processing files.

### Target LG device

- A compatible LAF Download Mode or Qualcomm EDL 9008 interface.
- Firmware that matches the exact model and regional variant.
- A stable USB connection and sufficient battery charge.

### Additional EDL requirement

- A device-specific Firehose programmer, usually named similarly to `prog_ufs_firehose_*.mbn`, `prog_emmc_firehose_*.mbn`, or an `.elf` equivalent.
- The programmer must match the target chipset and storage configuration.

The app does not provide programmers. A random programmer from another model can fail to initialize the device or create serious risk.

## Getting the APK

The GitHub Actions workflow builds the Android application and runs the JVM unit-test suite.

1. Open the repository's [Actions page](https://github.com/neljohn76/LGFlashTool/actions).
2. Select the latest **Android CI** workflow run.
3. Confirm that the test and build job completed successfully.
4. Open the run's **Artifacts** section.
5. Download `LGFlashTool-apks` and extract the APK files.

The workflow also publishes a `latest` prerelease after a successful run. See the [Releases page](https://github.com/neljohn76/LGFlashTool/releases).

> **Signing notice:** the current release build is signed with the Android debug key. It is a development artifact, not a production-signed release. Android does not receive a verified publisher identity from this signing configuration. Do not treat the APK as a production distribution.

## Build from source

### Build environment

- JDK 17
- Android SDK with API 34 platform/build tools
- Gradle 8.7
- Android USB Host-capable test device for hardware work

The repository currently does not include the Gradle Wrapper scripts. Use an installed Gradle 8.7 distribution or import the project into a compatible Android Studio installation.

### Build and test

From the repository root:

```bash
gradle testDebugUnitTest assembleDebug assembleRelease --no-daemon
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release APK output:

```text
app/build/outputs/apk/release/app-release.apk
```

The release variant currently uses the debug signing configuration. Configure a private release keystore before distributing a production build. Never commit keystore files or passwords to the repository.

## Usage

### LAF workflow

1. Boot the target LG phone into **Download Mode / Firmware Update**.
2. Connect the target to the Android host phone using USB OTG.
3. Open LG Flash Tool and select **LAF** mode.
4. Tap **Connect** and allow the Android USB permission request.
5. Wait for the LAF handshake and GPT partition discovery to complete.
6. Select the KDZ/DZ firmware file, or a raw image when using partition mode.
7. Review the detected firmware details and the available chunks/partition target.
8. Select only the intended chunks or partition.
9. Read the confirmation details carefully, then start the flash operation.
10. Wait for the operation to finish. Do not disconnect the cable or power off either device during a write.

For DZ firmware, the implementation performs a decompression and integrity-validation pass for selected chunks before beginning the erase/write phase. A validation failure should stop the operation before the write phase.

### Qualcomm EDL workflow

1. Put the target device into Qualcomm EDL **9008** mode using a method appropriate for that exact model.
2. Connect it to the Android host using USB OTG.
3. Select **EDL** mode and tap **Connect**.
4. Select a compatible Firehose programmer (`.mbn` or `.elf`).
5. Select a raw `.img` or `.bin` image.
6. Enter the correct storage LUN and starting sector.
7. Verify the programmer, LUN, sector, and image details before confirming.
8. Start the operation and keep the USB connection stable until it finishes.

**Do not use KDZ/DZ files in EDL mode.** The current EDL workflow accepts raw images only.

## Safety first

Firmware flashing is a destructive operation. Follow these rules:

- Confirm the complete model number and regional/carrier variant. Similar model names do not mean firmware is interchangeable.
- Use firmware from a trustworthy source and verify its integrity.
- Back up important data before flashing.
- Do not guess partition names, LUN numbers, start sectors, or programmer files.
- Do not flash a partition simply because its name looks familiar.
- Do not disconnect USB OTG or allow the host phone to shut down during an active operation.
- Do not attempt a flash if the app reports an unknown device, invalid firmware, failed verification, or an unsupported mode.
- Treat a successful software message as an operation result—not as proof that the device will boot correctly.
- Prefer a non-critical test device when validating new hardware combinations.

The project has not yet completed end-to-end physical-device validation. First-time hardware testing should be treated as experimental.

## Architecture

The project is implemented in Kotlin and uses Android's native USB Host APIs. Firmware parsing and protocol operations are kept in the Android application rather than delegated to a web page.

```text
LGFlashTool/
├── app/
│   └── src/
│       ├── main/
│       │   ├── java/com/neljohn76/lgflash/
│       │   │   ├── edl/
│       │   │   │   ├── EdlFlashEngine.kt
│       │   │   │   ├── FirehoseClient.kt
│       │   │   │   └── SaharaClient.kt
│       │   │   ├── flash/
│       │   │   │   ├── BlockAssembler.kt
│       │   │   │   └── LafFlashEngine.kt
│       │   │   ├── fw/
│       │   │   │   ├── ByteSource.kt
│       │   │   │   ├── DzParser.kt
│       │   │   │   ├── Firmware.kt
│       │   │   │   └── KdzParser.kt
│       │   │   ├── laf/
│       │   │   │   ├── Gpt.kt
│       │   │   │   ├── Laf.kt
│       │   │   │   └── LafClient.kt
│       │   │   ├── ui/
│       │   │   │   └── MainActivity.kt
│       │   │   ├── usb/
│       │   │   │   ├── BulkTransport.kt
│       │   │   │   └── UsbHelper.kt
│       │   │   └── util/
│       │   ├── res/
│       │   └── AndroidManifest.xml
│       └── test/
│           └── java/com/neljohn76/lgflash/
├── .github/workflows/
│   └── android.yml
├── app/build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
├── LICENSE
└── README.md
```

### Main modules

| Module | Responsibility |
|---|---|
| `usb/` | USB device discovery, permission handling, and bulk transport |
| `laf/` | LG LAF protocol, client communication, and GPT parsing |
| `edl/` | Qualcomm Sahara and Firehose communication |
| `fw/` | Firmware detection, KDZ/DZ parsing, and byte sources |
| `flash/` | LAF flashing orchestration and block assembly |
| `ui/` | Android activity and user interaction |
| `util/` | Shared byte and formatting helpers |

### Current UI implementation

The current app uses native Android XML layouts with AppCompat and Material Components. The main workflow is implemented in `MainActivity.kt`, including USB connection, firmware/programmer selection, chunk or partition selection, progress reporting, logs, and confirmation dialogs.

A full Jetpack Compose/Material 3 redesign is not represented as a completed feature in this repository. The README intentionally describes the code that exists rather than presenting a future interface as already implemented.

## Testing and validation

The repository contains JVM tests for several important code paths, including:

- Sahara packet parsing.
- LAF framing and protocol behavior.
- KILO authentication test vectors.
- GPT parsing.
- KDZ and DZ parser fixtures.
- Block-aligned write assembly.
- Byte utility behavior.

Run the tests with:

```bash
gradle testDebugUnitTest --no-daemon
```

CI also runs the test task before producing the APK artifacts.

### What CI does not prove

A successful unit-test suite or APK build does not validate:

- USB compatibility with every Android host phone.
- LAF behavior against every LG Download Mode implementation.
- Firehose programmer compatibility with a specific device.
- Real storage writes, erase behavior, or recovery behavior.
- Successful boot after flashing.

Physical-device validation remains a separate requirement.

## CI workflow

The workflow at `.github/workflows/android.yml` runs on pushes to `main` and manual dispatch. It:

1. Checks out the repository.
2. Sets up JDK 17 and Gradle 8.7.
3. Runs unit tests and builds debug/release APKs.
4. Uploads APKs as a GitHub Actions artifact.
5. Publishes a `latest` prerelease after a successful build.

The `latest` prerelease is recreated by the workflow, so it should be treated as a rolling development build rather than a permanent versioned release.

## Technical references and credits

This project builds on public protocol research and reference implementations:

- [Lekensteyn/lglaf](https://github.com/Lekensteyn/lglaf) — LG LAF protocol research and reference implementation.
- [ehem/kdztools](https://github.com/ehem/kdztools) — LG KDZ/DZ firmware format research.
- [bkerler/edl](https://github.com/bkerler/edl) — Qualcomm Sahara and Firehose reference implementation.
- [Gabriel2392/ErosFlashTool](https://github.com/Gabriel2392/ErosFlashTool) — project inspiration.

See the repository's [LICENSE](LICENSE) for licensing terms. The project currently identifies itself as GNU GPL v3. Review the licenses and obligations of upstream code and dependencies before redistributing binaries or derivative works.

## Contributing

Contributions should prioritize correctness, device safety, and reproducibility.

1. Inspect the existing implementation before changing protocol behavior.
2. Keep USB and flashing operations native to Android/Kotlin.
3. Add tests for parser boundaries, malformed input, offsets, and protocol responses.
4. Do not weaken validation to make a firmware package proceed.
5. Clearly distinguish tested behavior from assumptions.
6. Never claim hardware compatibility without testing the exact device and configuration.

For flashing-related changes, include the affected protocol, device family, firmware format, test evidence, and any remaining hardware limitations in the pull request.

---

<div align="center">

**LG Flash Tool** · Android-based LG firmware utility

<sub>Built for experimentation, protocol research, and careful device recovery. Flash responsibly.</sub>

</div>
