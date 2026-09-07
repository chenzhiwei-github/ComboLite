# ComboLite 2.0.2-xj.6 source fork

This source was reconstructed from the user-provided local upstream checkout at
`f4d45240357835fb4c00478ab0ff0db9c4f84bce`. The historical GameHub xj.5 source
commit `0366b3722be8c416485e348573599c85cf3e04a1` was not present in that checkout.
This is **not** an assertion that the historical xj.5 source was recovered.
Existing host API descriptors and behavior were inspected against the frozen
GameHub xj.5 AAR (`6c8834be4d9aba163f01cde61c4b4e6b01be7fec086d07b7cacaca3a9efe470d`)
and the checked-in fork design and xj.5 source patch. The source consumer rules
are restored from the archived xj.5 rules text. No AAR was edited or repacked.

## Build

The source-only profile avoids the upstream sample graph and publication signing:

```
./core-build/gradlew -p core-build \
  :comboLite-core:assembleRelease \
  :comboLite-core:publishReleasePublicationToForkArchiveRepository \
  :comboLite-core:testDebugUnitTest
```

Set Android SDK location using `ANDROID_HOME` or local `core-build/local.properties`.
Use Java 21, Gradle 9.7.1, AGP 9.4.0 and Kotlin 2.4.10. Library dependency versions
remain the upstream catalog values. The host project resolves its own newer
shared runtime libraries. Kotlin module name is pinned to `comboLite-core_release`
to retain existing internal JVM descriptors. The publication writes only to
`core-build/build/m2repo` and includes the generated sources JAR.

## Added APIs

- `InstallerManager.installArtifact(File source, File artifactDirectory,
  String expectedPluginId, Long expectedVersionCode, String expectedSha256)`
  returns existing `InstallResult` and exact `PluginInfo` on success.
- `InstallerManager.inspectArtifact(File artifactDirectory, String expectedPluginId,
  Long expectedVersionCode, String expectedSha256)` returns exact `PluginInfo` or
  throws for missing/incomplete/corrupt/different inventory or APK metadata.
- `PluginManager.launchArtifact(PluginInfo, String expectedSha256)` returns Boolean;
  initialization errors are failures and no candidate becomes routed by this API.

## Ownership and lifecycle

The host Authority must validate trusted plugin ID, schema, ABI, signed APK,
trusted expected digest, operation/generation and its durable installation intent
**before** calling the fork. It creates the trusted parent directory and holds
its installation OS lock. The fork creates only a new leaf directory under the
application files directory, or verifies a byte-identical complete previous
installation. It never overwrites or removes an existing tree. Interrupted trees
remain rejected until the Authority safely cleans them using its ownership ledger.

Installed files are `base.apk`, `class_index`, `artifact-record.json`,
`artifact-installation-intent.json` and selected Native files under
`lib/<supported ABI>/<filename.so>`. The intent lists exact planned relative files
and directories. The versioned record contains PluginInfo and all other regular
files with size/SHA-256; inspection validates the exact full file inventory and
rejects symlinks, hard links, special files, extra files and empty directories.
The record itself is a host-produced control file, not an authentication root.
The Authority's expected digest and ownership records remain authoritative.

APKs/Native/index are sealed read-only after writing and fsync. ZIP central
metadata rejects links/devices, path escape, duplicate names, encrypted entries,
unsupported archive layout and excessive output. Native extraction checks CRC.
The class index is generated from actual DEX using the existing Dexlib2 path.
No packaged precomputed index is assumed.

Exact artifact APIs require Android API 26+ (GameHub supports 29+), generate no
DEX cache outside the artifact, never query/recover/write global `plugins.xml`,
and do not create business or instance data directories. Runtime Native/index
resolution uses the exact APK parent. Each exact-loaded plugin ID stays pinned
to its artifact path/version/digest until process death, including after failure
or unload. Global registry loading is denied once a process is exact-pinned.
A mismatched exact load request cannot unload the existing instance.

This fork does not implement Broker activation, business writer ownership,
health adapters or active-selection transactions; those are host protocols.
Source-backed API/behavior reconstruction and changed loading/lifecycle semantics
require GameHub's planned schema bump and real host/plugin Release verification.

## Verification limits

No new tests were added. Core source release build and local publication passed.
`:comboLite-core:testDebugUnitTest` passed with `NO-SOURCE`; upstream has no test
sources in this checkout. The release unit-test variant is not exposed by the
core-only profile, so the existing Debug task was used.
The ABI report distinguishes compiler-generated members from source API and is
archived separately. Android API 29/36, Release/R8, Native and actual A/B instance
loading still require the GameHub integration and QA device matrix.
