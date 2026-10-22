# ComboLite 2.0.2-xj.12

`ArtifactInstaller.install` no longer fails permanently when the previous install of the
same directory was killed between writing the artifact record and finishing the seal.
Sealing makes every owned node read-only and seals the artifact root last; a process kill
in that window (for example a vendor "one-tap cleaner") left a complete tree with partly
sealed modes. The next `install` saw the existing directory, went straight to the sealed
inspection and failed with `Immutable artifact permissions changed: <artifact root>`, which
GameHub records as a hard installation rejection.

An existing directory now repeats the install tail: inspect the complete inventory,
hashes and ownership intent without the seal requirement, seal the owned nodes, then
inspect the sealed tree. Every check that ran before still runs before any mode changes.
A directory without a valid record (killed during the copy) fails unchanged and is left for
the Authority to clean from its intent; ComboLite still never deletes or overwrites a
partial install.

Public/protected ABI is unchanged; the only addition is a private method. No ClassLoader
policy, lifecycle dispatch, IPC, Native ABI, resource names or disk format changes. The
fix is delivered by updating the Host's ComboLite dependency; PC / Game Library schema and
artifact versions remain unchanged. Rolling back to an xj.11 Host only restores the old
failure for a tree interrupted during sealing.

## Build

Use Java 21 and Android SDK platform 36:

```sh
./core-build/gradlew -p core-build :comboLite-core:assembleRelease \
  :comboLite-core:publishReleasePublicationToForkArchiveRepository
```

There are no unit tests in this profile; NO-SOURCE is not a passing test run.
