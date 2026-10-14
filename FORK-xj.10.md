# ComboLite 2.0.2-xj.10

`PluginManager.launchArtifact` no longer resolves its own `@RequiresPermission`
method through kotlin-reflect (`::launchArtifact.javaMethod`). It now uses a lazily
cached plain Java `Method` with the same annotation and the same `checkApiCaller`
decision. The kotlin-reflect lookup deserialized Kotlin metadata on its first use,
and `launchArtifact` always runs first in a fresh plugin process: on a debug Host
that cost about 2.4s per PC/Game Library cold start before any plugin work began.

The other `@RequiresPermission` entry points keep their existing lookup; none of
them runs on the cold-start path. `PluginManager` and every `@RequiresPermission`
method are kept by `consumer-rules.pro`, so the name-based lookup is stable under
Host R8 minification.

Both publication profiles advance to the new immutable xj.10 coordinate; xj.9
artifacts must not be overwritten. Public/protected ABI is unchanged: the only
class difference is the removed compiler-synthesized
`PluginManager$launchArtifact$2` function-reference wrapper (169 vs 170 classes).
The fix is delivered by updating the Host's ComboLite dependency; no plugin API,
ClassLoader policy, lifecycle dispatch, IPC, resource or disk format changes.

## Build

Use Java 21 and Android SDK platform 36:

```sh
./core-build/gradlew -p core-build :comboLite-core:assembleRelease \
  :comboLite-core:publishReleasePublicationToForkArchiveRepository
```

The source-only profile publishes into `core-build/build/m2repo`.
There are no unit tests in this profile; NO-SOURCE is not a passing test run.

## Verification

Measured in GameHub on 2026-10-14 with a gamehubDebug plugin-mode Host and the
installed PC 126 / Game Library 66 plugins on Xiaomi 2106118C (API 34), two cold
starts each: Game Library LaunchArtifact 11.8s -> 9.7-9.8s, PC 13.3s -> 12.4-12.6s.
Both plugins loaded, published Ready and committed.

Historical Host/plugin combinations, API 29/36 and a minified Release Host remain
release verification gates. An xj.9 Host rollback only restores the slower lookup.
