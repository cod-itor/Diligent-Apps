# Diligent-Apps

Companion code for [Diligent](https://github.com/cod-itor/Diligent-OpenSource), the phone-first coding app. Diligent on Android, a desktop companion and an iPhone app find each other on the local network, pair with a 6-digit code and sync project files device to device, with no cloud in between. All three speak one protocol, diligent-link.

| Folder | What it is | Status |
| --- | --- | --- |
| [`sync-core/`](sync-core) | Kotlin Multiplatform library with the protocol's pure logic, shared by all three apps | In use |
| `desktop/` | Desktop companion (tray app) | Later |
| `ios/` | iPhone app | Later |

## sync-core

[`sync-core/SPEC.md`](sync-core/SPEC.md) is the diligent-link v1 specification and the source of truth; code follows it, not the other way round.

The library holds logic only: framing, typed messages, device IDs and the device registry, the pairing state machines, manifests, ignore rules, the comparison table, conflict naming and the sync session. Everything that touches the platform sits behind small interfaces in `com.ditarector.diligent.sync.port` that each app implements: `Transport`, `Discovery`, `Identity`, `Srp`, `FileStore`, `Snapshotter`, `Clock`, `SecureRandomSource` and `Hashing`. sync-core contains no cryptography of its own; SRP-6a, TLS and the hash functions come from each platform's vetted libraries.

Targets are `jvm` (Android and desktop), `iosArm64` and `iosSimulatorArm64`. Build and test with JDK 17 or newer:

```sh
cd sync-core
./gradlew jvmTest     # the common tests on the JVM
./gradlew build       # also compiles the iOS targets; running their tests needs Xcode
```

On machines that cannot build Apple targets (Linux CI), `kotlin.native.ignoreDisabledTargets=true` skips them.

Diligent consumes sync-core as a Git submodule plus a Gradle composite build (`includeBuild`). Kotlin is kept at the version Diligent's Android Gradle Plugin bundles so the composite build loads a single Kotlin plugin.

## License

MIT, see [LICENSE](LICENSE).
