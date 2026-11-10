# AVEN Billing Android app

This Android companion opens the live AVEN billing app and provides direct thermal receipt printing to a paired Bluetooth Classic ESC/POS printer.

## Included
- AVEN billing login and POS inside a locked-down Android WebView.
- Paired Bluetooth printer selection.
- Automatic receipt printing only after the AVEN bill-creation call succeeds.
- Manual print/reprint and a printer test receipt.
- 58mm and 80mm receipt bitmap rendering.

## Build
GitHub Actions creates a debug APK at android/app/build/outputs/apk/debug/app-debug.apk. The workflow uploads it as the artifact aven-billing-android-debug.

## First-time tablet setup
1. Install AVEN Billing on the Android tablet.
2. Power on the receipt printer and pair it in Android Settings > Bluetooth.
3. Open AVEN Billing and tap Printer setup at the top.
4. Choose the paired printer. Automatic printing is enabled when a printer is selected.
5. Tap Print a test receipt.
6. Create a real test bill. AVEN sends the receipt automatically after the bill is saved.

## Compatibility
This build uses Bluetooth Classic Serial Port Profile (SPP) and ESC/POS raster printing. BLE-only printers are not supported by this first bridge. The printer manufacturer must confirm SPP support. A physical printer test is still required before this is treated as production-verified.
