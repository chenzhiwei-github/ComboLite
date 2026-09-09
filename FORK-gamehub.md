# GameHub local branch

This branch collects the ComboLite changes currently consumed by GameHubKMP:

| Component | Version | Source |
| --- | --- | --- |
| combolite-core | 2.0.2-xj.8 | Original fork commits 8c71ff3, 7cec2f5 and a0355e9, based on upstream f4d4524 |
| aar2apk | 1.1.2-xj.4 | All 16 Kotlin source files from the archived sources JAR |

The existing core fork commits and FORK-xj.6/7/8 notes are retained. Core implements
host-controlled registry access, child-first/force-parent loading policy, exact
immutable artifact installation/loading, owned descriptors and sealed directories.
All 71 files in the archived xj.8 source subset match this branch byte for byte.
See FORK-xj.6.md for the historical xj.5 reconstruction limits.

The aar2apk sources restore dependency collection for AAR/JAR/local projects,
host-provided dependency/class filtering, multidex, R8 library inputs, resource
packaging and dependency/class inventory diagnostics. No decompilation is used.
The sources JAR SHA-256 is `089b4055f978b3dbc217c83bed17084c0264b1635ec81e338d4b8bdf4a688e48`.
The historical aar2apk source commit/build script was not archived; its build
configuration is reconstructed from the upstream plugin and published metadata:
Gradle 8.13, JVM target 17, AGP API 8.12.0 and Kotlin Gradle plugin 2.2.0.
No bit-identical rebuilt JAR is claimed.

## Local builds

Use JDK 21 and Android SDK platform 36. The core-only build avoids the sample app
and remote publication/signing configuration:

```bash
./core-build/gradlew -p core-build :comboLite-core:assembleRelease :comboLite-core:testDebugUnitTest
./gradlew -p build-logic :aar2apk:jar :aar2apk:test
```

Optional local Maven publication (no remote upload):

```bash
./core-build/gradlew -p core-build :comboLite-core:publishReleasePublicationToForkArchiveRepository
./gradlew -p build-logic :aar2apk:publishAllPublicationsToGamehubLocalRepository
```

Outputs go to core-build/build/m2repo and build-logic/build/m2repo respectively.
The upstream samples are retained; use the scoped commands above for these forks.
The branch does not change GameHubKMP dependency versions or replace its frozen
AAR/JAR bytes. Plugin schemas and host/plugin runtime behavior remain those of
its existing xj.8/xj.4 integration; device/Release compatibility is not established
by restoring source or by a source comparison.

## Migration verification (2026-09-09)

- The complete 71-file core archive and all 55 core Kotlin source-JAR entries
  match the restored source; all 16 aar2apk Kotlin source-JAR entries match.
- Gradle compilation/tests were attempted for core and aar2apk but could not
  start: the wrapper cache lock was not writable, and an isolated writable
  Gradle home was blocked while creating FileLockContentionHandler's socket.
- No newly rebuilt artifacts, publication or device validation are claimed.
  The historical verification limits in FORK-xj.6/7/8.md still apply.
