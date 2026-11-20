# Windows installer

Professor OS uses Compose native distributions and JDK jpackage to build a per-user Windows x64 EXE installer. Users need neither Java nor Gradle. The runtime, native libraries, fonts and dependency JARs are bundled. Distribution configuration is in `prototype-compose/build.gradle.kts`.

## Build

On Windows, install a Windows x64 JDK 25 and set `JAVA_HOME`. If the distribution ships its JDK modules separately, install the verified module archive for the exact same build into `JAVA_HOME/jmods`. Close development and installed instances before building or updating. From the repository root:

```powershell
cmd /d /c ".\mvnw.cmd -DforkCount=0 clean verify"
.\tools\package-windows.ps1
```

The in-process Maven option is the documented workaround for the original machine's shell AutoRun setting; other machines should keep Maven's default test forking. The packaging script runs Compose tests and builds the Windows EXE from the Compose app image. It downloads portable WiX 3.14.1 from its official release, verifies its pinned SHA-256 and supplies that tool directory explicitly. First builds need network access. No machine-wide Java/Gradle/WiX installation changes are performed by the script.

Set `PROFESSOR_OS_RELEASE_SUPABASE_URL` to the HTTPS project origin and `PROFESSOR_OS_RELEASE_SUPABASE_PUBLISHABLE_KEY` to its public `sb_publishable_` key before building. The installer stores those public values in its launcher configuration. Installed users can sign in without Gradle, developer environment variables, or a portal `.env.local`.

Installer output: `prototype-compose/build/compose/binaries/main/exe/Professor OS-1.1.0.exe`.

The installer creates Desktop and Start Menu shortcuts, uses the official icon for launcher/installer, and includes a full runtime to cover JDBC, POI, PDFBox, AWT and service/reflection dependencies reliably. Package version must increase for updates. Keep upgrade UUID `b5680b84-3e0b-4a04-9550-8745858157d9` fixed. Windows rejects a different build with the same installed version; do not use same-version reinstalls as an upgrade strategy.

## Storage and updates

Native launchers explicitly set `professoros.packaged=true`. Their default storage is `%LOCALAPPDATA%\ProfessorOS\data\professor-os.db`, outside the install directory (normally `%LOCALAPPDATA%\Professor OS`). Appearance preferences live beside the database. Gradle development runs explicitly select the existing repository-relative `data/` behavior. Tests preserve the explicit `PROFESSOR_OS_DATA_DIR` override in both modes; the fallback when LOCALAPPDATA is absent is the user's `AppData/Local` directory.

The installed launcher opens the online professor workspace. To explicitly open the local SQLite workspace, run the installed `Professor OS.exe` with `--recovery`. The installer contains no database, backup or demo dataset. Updates replace application files and preserve user data. The Windows current-user DPAPI session lives at `%LOCALAPPDATA%\ProfessorOS\sessions`, outside the install directory. Before removing the older package, the installer preserves legacy ciphertext from `%LOCALAPPDATA%\Professor OS\sessions` without decrypting it or replacing a newer stored session. Close Professor OS before installing an update so the existing session is not being refreshed during installation.

Supabase stores the online academic records. Settings shows the on-device paths for the separate SQLite recovery database and appearance preferences.

For the physical reboot check, close the installed app while signed in, reboot Windows, and sign back into the same Windows profile. Launch Professor OS from Start and confirm the workspace opens without another password or authenticator prompt and that Attendance loads. Record the Windows build and installer version. The physical reboot check is pending. The [full restart procedure](DESKTOP_ONLINE_MIGRATION.md#windows-restart-check) also covers independent Portal sign-out.

## Icon source

`professor-os-logo.png` is the unchanged official RGBA artwork. Run `python tools/build_windows_icon.py` with Pillow available on the developer machine to regenerate committed resources. The ICO contains lossless RGBA frames at 16, 24, 32, 48, 64, 128 and 256 pixels; corresponding PNGs provide AWT window/taskbar size selection. Transparency is retained. Small-size review uses the same artwork; no redesign or manually simplified mark is substituted.

## Independent verification

`tools/PackagedSmoke.java` is a developer-only harness, not shipped application functionality. Compile it against the installed `app/*.jar` files on the build machine. Run it with a native jpackage test launcher using a copy of the installed runtime and a manifest referencing the installed JARs, independently of Gradle and development classes. Supply an isolated `package-qa-*` directory with a COPY of the compact demo test database (offering ID 1) and set `PROFESSOR_OS_DATA_DIR` to that directory. `write` saves a test score, generates/reopens a four-sheet workbook, generates/reopens both PDF kinds and renders PDF previews. A second `verify` process checks the persisted score, SQLite integrity and foreign keys. Never point this helper at a real workspace.

The native application itself must also be launched directly from its installed EXE with JAVA_HOME unset, a system-only PATH and an unrelated working directory. Verify the profile storage path and restart persistence. Check that both shortcuts reference the installed launcher and that launcher/installer PE resources contain all seven official icon sizes.

## Release limitations

This build is Windows x64 and unsigned. Public distribution should be code-signed with the publisher's certificate; an unsigned download can trigger Windows reputation warnings. Testing on the development Windows machine with Java/Gradle absent from the launch environment is not a clean-machine or Windows ARM64 certification. External question images remain user-managed file references, as in the existing application.

## Verified artifact — 2026-10-28

Version 1.0.1, Windows x64. Size: 141,851,136 bytes (135.28 MiB). SHA-256: `bfd27ab12db16e74e95515b6b2347446f5b15d0b05a61fd69e4f2fbac48354a2`. Built with JDK 25.0.1 and WiX 3.14.1. Installer and installed launcher PE icon groups independently contain all seven required resolutions. Both shortcuts resolve to the installed EXE and inherit its embedded icon.

226 Maven tests and 43 Compose tests passed. The EXE installed successfully and upgraded candidate 1.0.0 to 1.0.1 with exit code 0; protected source/backup and QA database hashes were unchanged. Native startup with JAVA_HOME removed, PATH restricted to Windows System32, and the temporary directory as working directory created an empty workspace at the intended LOCALAPPDATA path, with integrity ok and no FK violations. A separate populated QA copy was displayed by the installed application, closed and reopened successfully; its bytes and saved grades were unchanged.

The developer-only QA launcher used installed JARs and a runtime copy verified byte-for-byte against the installed runtime, with JAVA_HOME removed and no Java/Gradle on PATH. Both PDF kinds were generated, reopened and rendered; Excel reopened with all four sheets. A second process verified the saved score and database integrity/FKs. No development/demo records were changed. The unsigned installer and generated verification outputs stay outside Git; only source artwork, derived icons, packaging/test tools, configuration and documentation are committed.

## Initial Windows 1.1.0 candidate — 2026-11-20

Windows x64, 143,449,600 bytes (136.80 MiB). SHA-256 `fab59ff319553abc70de62c1be943f726ec5816c97e5267ff6e10fe44beca282`. Built with Temurin 25.0.5 and WiX 3.14.1. The EXE is unsigned.

All 234 Java tests and 49 Compose tests passed. The in-place upgrade from 1.0.1 and uninstall/reinstall on the same Windows machine preserved the SQLite database, preferences and encrypted session byte-for-byte. The installed launcher restored that session with a system-only PATH and loaded the hosted Lecture, Matrix and Gradebook views. Settings displayed all ten themes. Student and answer-key PDFs and the four-sheet Excel workbook were generated and reopened using the installed runtime and a separate fictional SQLite fixture.

A separate installed-app session passed fresh professor TOTP, switched to Oceanic, and retained the theme and authenticated workspace after reopening. Explicit sign-out cleared the test ciphertext, and reopening returned to sign-in. The regular Windows profile retained its encrypted session. The installed `--recovery` launcher opened an isolated SQLite workspace, and the original 16-table backup passed SQLite integrity and foreign-key checks. The physical restart and clean-machine certification remain pending.

## Polished Windows 1.1.0 build

Windows x64, 143,449,600 bytes. SHA-256 `f5bb0b7b1e0c3bfd036717c8820d26d174f52f88fce0126193d4b0f67bc4c83e`. The rebuilt EXE carries the online workspace and SQLite recovery description. All 234 Java and 49 Compose tests passed.

Replacing the earlier 1.1.0 candidate on the same Windows profile preserved the encrypted session, database and preferences byte-for-byte through uninstall and reinstall. The installed app restored the authenticated workspace and displayed the new Settings wording. The physical reboot and clean-machine checks remain pending.
