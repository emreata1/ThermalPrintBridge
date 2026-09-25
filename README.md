# Thermal Print Bridge
[![Download APK](https://img.shields.io/badge/Download-APK-brightgreen?style=for-the-badge&logo=android)](https://github.com/emreata1/ThermalPrintBridge/releases/latest)
An Android tool designed to bridge mobile apps with Bluetooth ESC/POS thermal printers.

Built for field logistics, route delivery, and POS environments where quick receipt printing from mobile devices is required.

---

## Features

- **Bluetooth Compatibility:** Supports Android 8 (API 26) through modern Android versions with proper runtime permission checks (`BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` on API 31+).
- **Direct ESC/POS Commands:** Sends raw commands directly via Bluetooth SPP (initialization, line feed, raster bit-image, paper cut).
- **Localization (TR / EN):** Integrated multi-language support. Toggle between Turkish and English directly from the dashboard or through Android 13+ system app-language settings.
- **Hardware Alignment & Test Print:** Built-in test screen printing typography samples, alignment bars, and currency symbols (`₺`, `€`, `$`, `£`) to verify the thermal head.
- **Feed & Copy Controls:** Set custom copy counts and extra bottom feed lines directly from the dashboard.
- **Custom Header Logo:** Upload and save a 1-bit monochrome header logo to print at the top of receipts.
- **Intent Integration:** Appears in Android's "Share with" / "Open with" menus for PDF files.

---

## Tech Stack

- **Platform:** Native Android (Min SDK: 26, Target SDK: 37)
- **Language:** Kotlin
- **Architecture:** AndroidX, AppCompat, Single/Multi Activity pattern
- **Bluetooth:** Standard Bluetooth RFCOMM/SPP (`00001101-0000-1000-8000-00805F9B34FB`)
- **Data:** SharedPreferences with Gson

---

## Setup & Running

1. Clone the repo:
   ```bash
   git clone [https://github.com/emreata1/ThermalPrintBridge.git](https://github.com/emreata1/ThermalPrintBridge.git)
