# Third-party notices

Omniverse is original code. As of 2026-09-27 **no third-party source code has been copied or adapted** into this repository. The open-source projects studied during research (listed in `docs/PLAN.md` §3.5) informed the design only.
If a future change adapts code from another project, it MUST be listed here, with its license, and shown on the app's About screen.

## Bundled fonts (SIL Open Font License 1.1)
- **Instrument Serif**: © The Instrument Serif Project Authors. License: `android/designsystem/InstrumentSerif-OFL.txt`
- **Manrope**: © The Manrope Project Authors. License: `android/designsystem/Manrope-OFL.txt`. Shipped as static-weight instances generated from the variable font; the names are unchanged and unmodified otherwise.

## Libraries
Libraries come in as dependencies (AndroidX/Jetpack Compose, Media3, Kotlin/kotlinx, SQLDelight, OkHttp/Okio, Coil).
Their licenses are checked on every build by the `licensee` Gradle plugin (only Apache-2.0, MIT, BSD and ISC are allowed); see `build-logic/.../LicensePolicy`.
