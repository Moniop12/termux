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

## 4. APK Builder (baru, V1)
- Package baru: `app/src/main/java/com/termux/app/apkbuilder/ApkBuilderActivity.java`
- Front-end native buat script builder APK milik kamu sendiri (bukan bagian dari Termux upstream — kamu tetap harus punya script-nya sendiri, app cuma bantu manggil).
- Alurnya:
  1. Pilih file `.sh` script builder kamu (pakai `FileBrowserActivity` mode baru "pick file")
  2. Pilih folder proyek Android (pakai `FileBrowserActivity` mode baru "pick folder") — path-nya otomatis ditulis ke `~/.termux-apk-builder/last_project.txt`, sama persis file yang dipakai fitur "proyek terakhir" di script kamu
  3. Tap "Build Debug"/"Build Release" → buka terminal, jalanin script kamu apa adanya (gak dimodif)
  4. **Yang PERLU kamu lakuin manual di V1**: pas terminal kebuka, tekan "1" (Debug) atau "2" (Release) lalu Enter. Ini gak bisa diauto-tekan di V1 karena `EXTRA_STDIN` di Termux cuma jalan buat mode eksekusi headless (`APP_SHELL`), bukan sesi terminal interaktif — dicoba dan dikonfirmasi gak jalan, makanya gak dipaksain.
- `FileBrowserActivity` ditambah 2 mode baru (gak ganggu perilaku browse biasa): `PICK_MODE_FOLDER` (nampilin tombol "Pilih Folder Ini") dan `PICK_MODE_FILE` (tap file balikin path-nya, gak langsung run/buka).
- Preference baru `KEY_APK_BUILDER_SCRIPT_PATH` (di `TermuxPreferenceConstants`/`TermuxAppSharedPreferences`) — nyimpen path script biar gak perlu pilih ulang tiap buka.
- Entry point: tombol wrench di drawer (sebelah Files).
- **V2 (belum dikerjain)**: full headless — terminal gak kebuka sama sekali, log native di layar sendiri. Butuh switch ke `Runner.APP_SHELL` + custom log-streaming UI.

## 5. Redesign UI: extra-keys row & tombol menu
- **Baris tombol ekstra terminal** (`TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS`) diganti dari `ESC / — HOME ↑ END PGUP` (jarang kepake) jadi:
  - Baris 1: `TAB CTRL ALT ← ↑ ↓ →` (masih perlu buat navigasi/shortcut manual)
  - Baris 2: **Stop** (kirim Ctrl+C — hentiin proses yang lagi jalan), **Bersihkan** (Ctrl+L — bersihin layar), **Keluar** (Ctrl+D), **Tempel/PASTE** (paste clipboard 1 tap), **⌨ KEYBOARD** (toggle keyboard)
  - Ini cuma ganti default bawaan — kalau user (kamu atau siapapun install app-nya) udah punya `~/.termux/termux.properties` sendiri, gak kesentuh/gak ke-override.
  - **Copy** teks gak perlu tombol baru — itu udah jalan lewat long-press+drag (seleksi teks native Android), bukan lewat baris extra-keys.
- **Tombol menu selalu keliatan**: nambah floating button bulat (☰) di pojok kiri-atas layar terminal, klik langsung buka drawer — gak perlu lagi swipe dari tepi layar.

## Belum dikerjain (dibahas terpisah, scope-nya besar)
1. ~~APK Builder V2 (full native, tanpa buka terminal)~~ — **SUDAH DIKERJAIN**, lihat bagian 6 di bawah.

## 6. APK Builder V2 — full native, headless (terminal gak pernah kebuka)
- **`AppShell.java` (termux-shared, inti eksekusi background Termux)**: ditambah kemampuan live-streaming stdout/stderr per baris (`AppShellClient.onAppShellStdoutLine/onAppShellStderrLine`, default no-op — gak ganggu kode lain yang udah pakai `AppShellClient`). Sebelumnya output cuma numpuk di `StringBuilder` dan baru kebaca pas proses selesai.
- **`ApkBuilderRunner.java`** (baru): pembungkus `AppShell` + `TermuxShellEnvironment` (environment setup yang sama persis kayak Termux pake buat eksekusi background resminya — PATH, dll bener). Nyediain `StdinScripts` — daftar urutan tombol yang dikirim otomatis ke menu script kamu:
  - `BUILD_DEBUG` = `"1\n\n\n0"` (pilih 1 → Enter pakai proyek terakhir → Enter lanjut → 0 keluar)
  - `BUILD_RELEASE` = `"2\n\n\n0"`
  - `IMPORT_BACKUP` = `"5\ny\n0"`
  - `AUTO_SETUP` = `"3\n0"`
  - `EXPORT_BACKUP` = `"6\n0"`
  - **PENTING**: angka-angka ini di-hardcode sesuai menu utama script kamu persis (1=Debug, 2=Release, 3=Auto-Setup, 5=Import, 6=Export). Kalau nomor menu di script kamu berubah, ini HARUS disesuaikan manual — gak otomatis ke-detect.
- **`ApkBuilderLogActivity.java`** (baru): layar log native — nampilin stdout/stderr live (bukan nunggu proses selesai), status banner (Sedang berjalan/Selesai/Gagal), tombol **Stop** (kirim SIGKILL beneran lewat `AppShell.kill()`, bukan cuma UI doang), tombol Tutup (aktif kalau udah selesai). Back button diblokir selama proses masih jalan (biar gak ninggalin build "nyantol" tanpa jalan buat balik liat log-nya).
- **`ApkBuilderActivity.java`** diupdate:
  - Tombol Build Debug/Release sekarang manggil `ApkBuilderLogActivity` (headless), BUKAN buka terminal lagi
  - Tombol baru **Auto-Setup Environment** — jalanin opsi 3 di script kamu headless
  - Tombol baru **Import Backup/NDK (.zip)** — pilih file zip apapun via file browser, disalin otomatis ke `/sdcard/builder-backup-complete-<timestamp>.zip` (format yang dikenali `import_backup()` di script kamu), lalu tanya konfirmasi sebelum jalanin import
- **Catatan jujur soal "Import NDK"**: dari yang saya baca di script kamu, `import_backup()` itu SATU fungsi yang nanganin backup lengkap DAN scan NDK archive sekaligus — tapi scan NDK-nya cuma jalan kalau file `builder-backup-complete-*.zip` ketemu duluan (ada pengecekan gate di awal fungsi). Jadi kalau kamu punya NDK zip MURNI (bukan bagian dari backup lengkap), fitur import ini mungkin gak nemu itu sebagai NDK standalone — perlu dicoba langsung di device buat mastiin, saya gak bisa run script kamu buat verifikasi interaktif dari sini.
- **Belum sempat diverifikasi di device asli** (sama kayak fitur sebelumnya) — terutama urutan stdin buat Auto-Setup (`"3\n0"`), karena saya cuma lihat sebagian kecil dari fungsi `auto_setup()` yang mungkin punya prompt tambahan yang saya lewat.

## 7. APK Builder V3 — fix bug + script dibundle + posisi UI diperbaiki
Respons langsung ke feedback: bug ANR, "masih nyuruh pilih script", tombol menu gak jelas, posisi APK Builder.

- **Fix ANR ("Termux tidak menanggapi")**: root cause-nya nyalin file zip (NDK >1GB) dilakuin di **main thread** — Android nganggep app hang. Sekarang semua copy file (import zip DAN extract script bundled) jalan di background thread + ada overlay "Menyalin file, mohon tunggu..." biar keliatan lagi kerja, bukan freeze.
- **Script gak perlu dipilih lagi**: `build.sh` (persis kiriman kamu) di-bundle sebagai asset APK (`app/src/main/assets/apkbuilder/build.sh`), di-extract otomatis ke `~/.termux-apk-builder/build.sh` tiap `ApkBuilderActivity` dibuka. Tombol "Pilih Script" dihapus total. User cuma milih folder proyek.
  - **Logic build TETAP di script (bash), TIDAK diterjemahin ke Java** — sesuai arahan kamu, biar risiko rendah (logic udah kebukti jalan, gak usah ditulis ulang). Yang jadi Java cuma orkestrasi menu (Build/Setup/Import jadi tombol) + log native.
  - Nanti kalau mau pindah ke online (biar update tanpa rebuild APK): tinggal ganti `extractBundledScript()` di `ApkBuilderActivity.java` jadi download dari URL, bukan copy dari assets. Belum dikerjain sesuai request kamu (tes bundle dulu).
- **Tombol menu floating**: kontrasnya diperbaiki — background solid hijau + border putih (sebelumnya hitam transparan, ilang di atas terminal item).
- **APK Builder pindah posisi**: dari icon row atas (sejajar Settings/Files) ke **row mirip item sesi**, nempel tepat di atas daftar sesi terminal di drawer — biar kerasa "buka ini" bukan "buka menu app lain".


## Yang PERLU kamu cek/lakukan sebelum build
1. ~~Download bootstrap-aarch64.zip manual~~ — TIDAK PERLU, sudah auto lewat Gradle task `downloadBootstraps`.
2. Sync Gradle / build via GitHub Actions.
3. Belum ditest di device — perlu 1x build+install manual buat verifikasi akhir.

