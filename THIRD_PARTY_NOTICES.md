# Third-party components

## scrcpy server 4.1

The Android companion bundles `scrcpy-server-v4.1` for the optional Root live-screen/control feature.

- Project: [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy)
- Release: [v4.1](https://github.com/Genymobile/scrcpy/releases/tag/v4.1)
- Official artifact: [scrcpy-server-v4.1](https://github.com/Genymobile/scrcpy/releases/download/v4.1/scrcpy-server-v4.1)
- Size: 733706 bytes
- SHA-256: `deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae`
- License: Apache License 2.0, full text bundled at `android/app/src/main/assets/SCRCPY-LICENSE.txt`.

The bundled file's digest was checked against the official GitHub release asset metadata on 2026-09-12. It is not a project-built or modified scrcpy binary. To fetch the same upstream artifact:

```bash
curl -fL https://github.com/Genymobile/scrcpy/releases/download/v4.1/scrcpy-server-v4.1 -o scrcpy-server-v4.1
sha256sum scrcpy-server-v4.1
```

Compare the printed digest to the value above before replacing the bundled asset. The rest of this repository's MIT license does not relicense this third-party component.

## Build/runtime dependencies

Android and Python dependencies are declared in their respective Gradle and `pyproject.toml` files. They retain their upstream licenses. The Gradle wrapper is part of Gradle, licensed under Apache-2.0; see [Gradle](https://github.com/gradle/gradle/blob/master/LICENSE).
