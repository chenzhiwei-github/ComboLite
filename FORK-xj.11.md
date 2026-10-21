# ComboLite 2.0.2-xj.11

`PluginLifecycleManager.launchArtifact` no longer re-inspects an artifact that the
caller has just inspected. GameHub's version runtime always calls
`InstallerManager.inspectArtifact` and immediately passes the returned `PluginInfo`
to `PluginManager.launchArtifact`; both calls hashed and re-validated the complete
sealed artifact tree, so every PC / Game Library cold start paid the full-tree
inspection (about 380ms on a debug Host) twice.

`inspectArtifact` now records the exact `PluginInfo` instance it returned, a copy
snapshot and the SHA-256 it was checked against. `launchArtifact` skips its own
inspection only when it receives that same instance (reference identity), still
equal to the snapshot, for the same SHA-256. The record is one-shot: it is consumed
by the first `launchArtifact` for that plugin ID whether or not it matches. Any
other descriptor (self-built, copied, mutated, different SHA or stale) is
inspected exactly as before, including the `verified == plugin` check.

Public/protected ABI is unchanged; the additions are a private nested class, a
private field and an `internal` method. No ClassLoader policy, lifecycle dispatch,
IPC, Native ABI, resource names or disk format changes. The fix is delivered by
updating the Host's ComboLite dependency; PC / Game Library schema and artifact
versions remain unchanged. Rolling back to an xj.10 Host only restores the second
inspection.

## Build

Use Java 21 and Android SDK platform 36:

```sh
./core-build/gradlew -p core-build :comboLite-core:assembleRelease \
  :comboLite-core:publishReleasePublicationToForkArchiveRepository
```

There are no unit tests in this profile; NO-SOURCE is not a passing test run.
