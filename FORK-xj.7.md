# ComboLite 2.0.2-xj.7

This source change is based on xj.6 commit
`8c71ff31325a850538b10bb7f173327a9e2af326`, reconstructed from upstream
`f4d45240357835fb4c00478ab0ff0db9c4f84bce` as documented in FORK-xj.6.md.
Neither frozen xj.5 nor xj.6 AAR bytes are changed.

Android FileInputStream/FileOutputStream constructed from a raw FileDescriptor
are non-owning. xj.6's exact-artifact and read-only registry input streams could
leave Os.open descriptors alive despite stream.use. xj.7 transfers descriptor
ownership to explicit input/output wrappers which call Os.close in finally,
including when stream.close throws, and prevents double close. Directory fsync
paths already used explicit finally/Os.close and remain unchanged. This covers
every raw Os.open in the fork: exact APK/index/Native/record reads, immutable
artifact output writes, and read-only legacy registry reads.

Public loading/installation/control interfaces, record formats and semantics are
unchanged. Rebuild from source using the core-only wrapper/profile:

```
./core-build/gradlew -p core-build :comboLite-core:assembleRelease \
  :comboLite-core:publishReleasePublicationToForkArchiveRepository \
  :comboLite-core:testDebugUnitTest
```

Java 21, Gradle 9.7.1, AGP 9.4.0, Kotlin 2.4.10. Local publication writes only
core-build/build/m2repo. Build and static ABI evidence are archived separately.
No new test source is added. Android descriptor counts and API 29/36 Native/file
retirement still require real-device verification.
