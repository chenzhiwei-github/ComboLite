# ComboLite 2.0.2-xj.9

Plugin APK resources and assets now use Android's native ZIP reader through
`ResourcesProvider.loadFromApk(fd)`, without a custom Java AssetsProvider.
The input ParcelFileDescriptor is closed after Android duplicates its descriptor.
The PluginAssetsProvider class remains available; public API, ClassLoader policy,
Activity lifecycle, resource names and persisted formats are unchanged.

On vivo DPD2605 Android 17 (API 37), window theme resolution calls
`AssetManager2::loadHideResInfo()` inside NativeApplyStyle's primitive-array
critical section. The custom provider causes LoaderAssetsProvider to call
NewStringUTF before dispatching its Java callback. CheckJNI detects illegal JNI
re-entry and aborts the plugin process. Removing the callback fixes this path
without disabling CheckJNI or delaying window initialization.

Both publication profiles advance to the new immutable xj.9 coordinate; xj.8
artifacts must not be overwritten. The fix is delivered by updating the Host's
ComboLite dependency, with no new plugin-facing API or required plugin migration.

## Build

Use Java 21 and Android SDK platform 36:

```sh
./core-build/gradlew -p core-build :comboLite-core:assembleRelease \
  :comboLite-core:publishReleasePublicationToForkArchiveRepository
```

The source-only profile publishes into `core-build/build/m2repo`.
There are no unit tests in this profile; NO-SOURCE is not a passing test run.

## Verification

The same loader fix was verified in GameHub on 2026-10-08 with a gamehubDebug
Host and Release plugins on vivo DPD2605 Android 17. CheckJNI remained enabled.
Hollow Knight: Silksong reached Steam login_success, app_launch and launch
validation Passed at approximately 60 FPS. Compose loading text/images and the
virtual controller rendered. Static comparison against archived xj.8 found the
same 170 classes and identical public/protected JVM descriptors.

Historical Host/plugin combinations, API 29/36, a minified Release Host,
settings, resource unloading and exit/re-entry remain release verification gates.
An xj.8 Host rollback restores the offending callback and may reproduce the
crash on affected CheckJNI-enabled devices; saved data is unchanged.
