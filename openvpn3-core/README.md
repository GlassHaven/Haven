# OpenVPN3 native builds

`rclone-android:buildRcloneNative` depends on
`rclone-android:buildOpenvpn3Native`, which builds the static archives consumed
by ovpnbridge's cgo link. `core:tunnel:buildOpenvpn3Native` delegates to that
same producer across the composite build boundary. Building only rclone, lint,
or tests therefore also builds the archives first. The archives are inputs to
rclone's task, so changing native sources also invalidates the Go build.

Dependency preparation is separate from compilation. `deps.lock` pins source
archives for OpenVPN3, mbedtls, mbedtls-framework, lz4, fmt, and Asio by commit
and SHA-256.
`fetch-deps.sh` verifies downloads and cached archives, installs the framework,
and applies the Asio patches from the pinned OpenVPN3 archive. OpenVPN3 is
prepared in `.deps/sources/openvpn3` and does not require a Git checkout or nested
submodules at the pinned revision. No binaries are downloaded. The prepared
tree is `openvpn3-core/.deps/sources`; downloaded
archives are cached in `.deps/downloads`.

Normal Gradle builds run `rclone-android:fetchOpenvpn3Deps` automatically. For a
direct script invocation, prepare sources first, from the repository root:

```sh
bash openvpn3-core/fetch-deps.sh
bash openvpn3-core/build-android.sh
# Or, for Linux host Go tests:
bash openvpn3-core/build-host-test.sh
```

Both compilation scripts use `fetch-deps.sh --check`, which never downloads or
modifies sources. Missing or stale dependencies fail with preparation instructions.
The prepared fingerprint includes the lock and preparation script; the lock's
OpenVPN3 archive checksum also pins its Asio patches. Changes to any of these
inputs require preparing again. Paths are excluded so the tree can be relocated
into a build server.

## Offline and F-Droid preparation

Supply dependencies before entering the offline build phase. Either run the
preparation script while network access is available, or place the exact
`<name>-<commit>.tar.gz` archives listed in `deps.lock` in `.deps/downloads` and
run:

```sh
bash openvpn3-core/fetch-deps.sh --offline
```

Alternatively, `OPENVPN3_DEPS_MIRROR=file:///path/to/archives` lets preparation
use a local archive mirror, with the same checksum verification. `--offline`
only uses cached archives or an already prepared source tree and never invokes
curl. Gradle's `--offline` also selects this mode for `fetchOpenvpn3Deps`.

F-Droid's source preparation should populate this tree before scanning and
building, following its
[build metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/).
The latest entry in Haven's [metadata mirror](../metadata/sh.haven.app.yml)
contains a recipe proposal for maintainer review: run `fetch-deps.sh` in
`prebuild`, discard the downloaded archive cache after preparation, and compile
OpenVPN before the direct rclone invocation in `build`. The maintainer should
apply these steps to the first fdroiddata release containing this PR, rebased
onto the current recipe. The mirror retains its historical release identifiers.

For the recipe's `subdir: app`, the preparation command is
`bash ../openvpn3-core/fetch-deps.sh --offline` after the pinned source archives
have been supplied by preparation. Keep the prepared `.deps/sources` directory
in the source tarball; `.deps/downloads` can be removed after preparation.

If the recipe invokes rclone's shell script directly before Gradle, it must first
run `bash ../openvpn3-core/build-android.sh` in its **build** block. Alternatively,
let Gradle build rclone through its task dependency. Neither OpenVPN compilation
script fetches anything during this phase.

The active F-Droid recipe must also drop `rclone-android/build` from `scandelete`
now that the bindings JAR is generated rather than tracked. That recipe lives in
fdroiddata; the historical metadata mirror in this repository does not update it.

`-PskipOpenvpn3Native` skips preparation and native compilation for callers that
have already supplied compatible archives. It does not remove ovpnbridge from
the Go bind; those archives are still required to link.
