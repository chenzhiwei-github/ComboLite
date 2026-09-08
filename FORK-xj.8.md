# ComboLite 2.0.2-xj.8

Based on xj.7 commit `7cec2f5b0202f1f12c865f610ba4d9a2501ed017`.
Frozen xj.5, xj.6 and xj.7 artifacts remain unchanged.

Exact artifact installation now seals every manifest file to 0400 and every
manifest directory, including the artifact root, to 0500 after a complete
integrity check. Each chmod uses a NOFOLLOW descriptor with UID/device/inode/type
checks and fsync, deepest directories first. Exact inspect/load requires those
permissions as well as the existing complete inventory and SHA-256 checks.
Interrupted sealing is an incomplete installation; it is never silently adopted.

The exact lifecycle reads class_index and APK resources without rewriting them.
Native search resolves lib/<ABI> inside the exact artifact. On supported API 26+
DexClassLoader receives optimizedDirectory=null; the fork creates no global
shared DEX cache or writable optimization directory. Sealing prevents the app-UID
ART loader from creating neighboring persistent optimizer files; normal JIT is
allowed. API 29/36 OEM/background dexopt behavior still requires device evidence.

Host retirement must retain its process-death and reference barriers, inspect
only its recorded files, and restore owner write permission on the exact known
directories before unlinking them. Unknown files, symlinks and replaced inodes
must remain rejected. This fork exposes no broad directory-unseal/delete API.
File content/record formats and public API descriptors are unchanged.

Build from source with Java 21, Gradle 9.7.1, AGP 9.4.0, Kotlin 2.4.10:

```
./core-build/gradlew -p core-build :comboLite-core:assembleRelease \
  :comboLite-core:publishReleasePublicationToForkArchiveRepository \
  :comboLite-core:testDebugUnitTest
```

The source-only profile publishes into core-build/build/m2repo. Source/provenance
and static ABI evidence accompany the output. No new test source was added.
