# Professor OS

Professor OS is an online academic workspace for university professors, with a companion Student Portal. Manage courses, attendance, assessments, and exams from Windows. Publish final grades explicitly so students can view their own results online.

[Download Professor OS for Windows x64](https://github.com/retinazer21/ProfessorOS/releases/tag/v1.1.0) · [Open the live Student Portal](https://retinazer21.github.io/ProfessorOS/)

## Features

- Manage academic terms, courses, offerings, students, enrollments, and lectures.
- Record attendance in lecture and matrix views with bulk marking and conflict checks.
- Maintain a weighted gradebook, build exams from a question bank, and export student papers and answer keys as PDFs.
- Import student rosters from Excel and export gradebooks and attendance.
- Review authorized courses, lectures, attendance, and published grades in the Student Portal.
- Choose from ten shared themes. Appearance preferences are stored on the device or in the browser.

## Windows x64 installation

1. Download **Professor OS-1.1.0.exe** from [GitHub Releases](https://github.com/retinazer21/ProfessorOS/releases).
2. Compare the download's SHA-256 with the value on the release page. In PowerShell, run `Get-FileHash -Algorithm SHA256 -LiteralPath '.\Professor OS-1.1.0.exe'`.
3. Close Professor OS if it is running. Run the installer, then launch **Professor OS** from Start. The installer includes Java and uses a per-user installation.
4. Sign in with your provisioned professor account and a current authenticator code. The online workspace requires an internet connection.

Version 1.1.0 is an **unsigned prerelease**. Windows may show a publisher or reputation warning. Physical reboot and clean-machine testing remain pending.

The desktop stores refresh credentials using Windows current-user DPAPI. Normal app closure preserves the session. Use **Sign out** to end the desktop session. For local SQLite recovery, launch the installed executable with `--recovery`. See the [Windows packaging guide](docs/WINDOWS_PACKAGING.md).

## Architecture

| Layer | Role |
| --- | --- |
| Kotlin and Compose Desktop | Professor workspace on Windows |
| Java 25 | Academic services, validation, Excel via Apache POI, and PDFs via PDFBox |
| Supabase | Authoritative academic database, password authentication, TOTP MFA, and PostgreSQL row-level security |
| SQLite | Explicit local recovery workspace |
| React, TypeScript, and Vite | Student Portal, served by GitHub Pages |

Both applications require an authorized account and TOTP MFA for protected records. Database policies scope academic access to authorized assignments or enrollments. Draft grades remain private until explicitly published.
 the [Student Portal setup](student-portal/README.md). Database changes use new versioned migrations. See the [authentication setup](student-portal/AUTH_SETUP.md), [desktop cloud integration](docs/DESKTOP_ONLINE_MIGRATION.md), and [academic workflows](docs/USER_GUIDE.md).
