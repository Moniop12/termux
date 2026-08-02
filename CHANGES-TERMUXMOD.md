# TermuxMod — Ringkasan Perubahan

Base: source resmi termux-app (upstream, tidak dimodifikasi packagenya/nama app-nya).

## 1. arm64-v8a only (app + terminal-emulator)
- `app/build.gradle`: `defaultConfig.ndk.abiFilters = ["arm64-v8a"]`, `splits.abi.include = ["arm64-v8a"]` (splits tetap `enable true` supaya APK dinamai `..._arm64-v8a.apk`, bukan `..._universal.apk`).
- `terminal-emulator/build.gradle`: **module ini punya native lib sendiri (`libtermux.so`, buat wcwidth/utf8) yang kelewat di patch awal** — sudah difix, `ndk.abiFilters` juga arm64-v8a only.
- `app/src/main/cpp/termux-bootstrap-zip.S`: cabang `#if/#elif` buat i686/x86_64/arm dihapus, cuma sisa `__aarch64__`.
- Task Gradle `downloadBootstraps` (yang auto-download `bootstrap-*.zip` dari rilis termux-packages sebelum compile): sekarang cuma download `aarch64`, gak lagi download `arm`/`i686`/`x86_64` yang gak kepake.
- **Koreksi dari respons saya sebelumnya**: saya sempat bilang kamu harus download `bootstrap-aarch64.zip` manual — itu salah, ternyata sudah otomatis lewat task Gradle ini. Gak perlu langkah manual.
- `.github/workflows/debug_build.yml` & `attach_debug_apks_to_release.yml`: validasi/upload/attach APK dipangkas dari 5 varian (universal/arm64/armeabi-v7a/x86_64/x86) jadi cuma `arm64-v8a`.

## 2. Auto setup storage
(sama seperti sebelumnya — lihat riwayat chat)

## 3. File Browser + Script Runner
(sama seperti sebelumnya — lihat riwayat chat)

## Yang PERLU kamu cek/lakukan sebelum build
1. ~~Download bootstrap-aarch64.zip manual~~ — TIDAK PERLU, sudah auto lewat Gradle task `downloadBootstraps`.
2. Sync Gradle / build via GitHub Actions.
3. Belum ditest di device — perlu 1x build+install manual buat verifikasi akhir.

