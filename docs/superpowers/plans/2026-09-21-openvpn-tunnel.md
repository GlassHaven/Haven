# OpenVPN Tunnel Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add OpenVPN as a fifth per-profile userspace tunnel type in Haven (alongside WireGuard/Tailscale/NetBird/Cloudflare Access), with no Android VpnService involvement.

**Architecture:** Vendor `mysteriumnetwork/go-openvpn`'s Go/CGO glue around OpenVPN3 Core's `TunBuilderBase` callback interface, but (a) rebuild the OpenVPN3 Core static libraries from current upstream source instead of using go-openvpn's stale 2020 binaries, and (b) implement the `TunnelSetup.Establish()` callback to hand back one end of a local `socketpair()` instead of a real Android VpnService fd, bridging the other end into `wireguard-go`'s `tun/netstack` gVisor stack — the same userspace-netstack technique Haven's existing `wgbridge` package already uses for WireGuard. This makes OpenVPN a fifth `TunnelConfigType`, dispatched from `TunnelManager.create()` exactly like the other four.

**Tech Stack:** Go (gomobile-bound into `libgojni.so`), CGO linking a C++ static library (OpenVPN3 Core), Kotlin/Android (Hilt, Room), CMake + Android NDK 29.

**Spec:** `docs/superpowers/specs/2026-09-21-openvpn-tunnel-design.md`

## Global Constraints

- License: OpenVPN3 Core and `go-openvpn`'s glue are AGPLv3 — matches Haven's own license (verified: Haven's `LICENSE` file is AGPLv3). Preserve copyright headers on any vendored file.
- No Android VpnService, no system-wide routes, no root — matches every other `Tunnel` implementation in `core/tunnel`.
- Compression is disabled unconditionally (VORACLE-class risk) — do not expose a compression toggle in config or UI.
- Static-key mode and `tls-crypt-v2` are out of scope for v1 (see spec's Scope section).
- Toolchain: JDK 17 + JDK 21, Android SDK + NDK 29.0.14206865 + CMake 3.31.6, Go 1.26, gomobile/gobind, Rust + cargo-ndk, cross-gcc (aarch64/x86_64/armhf), gawk, openvpn + easy-rsa — all under `~/terminal/build-tools` / `~/.cargo` / `~/go` this time (previous run had them scattered outside `$HOME` and lost them on a container restart; this run keeps everything persistent from the start).
- Working tree: `~/terminal/src/haven/.worktrees/openvpn-tunnel` (branch `openvpn-tunnel`).

---

### Task 1: Prove OpenVPN3 Core builds for Android arm64-v8a against NDK 29

This is the highest-risk unknown in the whole plan — if this doesn't work, everything downstream is blocked. Do this before writing any Go or Kotlin code.

**Known answer from the first attempt at this plan (session was lost to a container restart, but the finding stands and should be reproduced, not re-discovered from scratch):** `javacli/android` in current `OpenVPN/openvpn3` upstream is NOT a maintained Android build path — it's a single ~40-line `cpu.cpp` JNI stub with no CMakeLists.txt. `CMakePresets.json` has zero Android presets. `client/CMakeLists.txt` only defines a SWIG/Python target. The real, working recipe is a **standalone CMakeLists.txt** (not part of the vendored openvpn3 source) compiling exactly `client/ovpncli.cpp` + `openvpn/crypto/data_epoch.cpp` against four dependencies: mbedtls 3.6.7 (compiled), lz4 1.10.0 (compiled), asio 1.38.2 (header-only, needs 6 patches from openvpn3's own `deps/vcpkg-ports/asio/*.patch` applied by hand), and fmt 11.2.0 (header-only, `-DFMT_HEADER_ONLY`). Zero patches needed against openvpn3 core itself.

**Files:**
- Create: scratch clone of `OpenVPN/openvpn3` + dependency builds, outside the Haven tree (e.g. `/tmp/openvpn3-core`, `/tmp/openvpn3-deps`)

**Interfaces:**
- Produces: a working arm64-v8a static library build recipe, to inform Task 2.

- [ ] **Step 1: Clone OpenVPN3 Core upstream**

```bash
git clone --depth 1 https://github.com/OpenVPN/openvpn3.git /tmp/openvpn3-core
```

- [ ] **Step 2: Build the four dependencies for arm64-v8a**

mbedtls 3.6.7 (CMake + NDK toolchain file, `-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DENABLE_TESTING=OFF -DENABLE_PROGRAMS=OFF -DMBEDTLS_FATAL_WARNINGS=OFF`, install to a prefix); lz4 1.10.0 (`cmake -S build/cmake`, `-DBUILD_SHARED_LIBS=OFF -DLZ4_BUILD_CLI=OFF`, install to a prefix); asio 1.38.2 (`git clone --branch asio-1-38-2`, then `git apply -p1` each of openvpn3's `deps/vcpkg-ports/asio/*.patch`); fmt 11.2.0 (clone only, header-only mode, no build step).

- [ ] **Step 3: Write and run the standalone CMakeLists.txt**

```cmake
cmake_minimum_required(VERSION 3.16)
project(ovpncli_android_test CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CORE_DIR /tmp/openvpn3-core)
set(ASIO_DIR /tmp/openvpn3-deps/asio)
set(FMT_DIR /tmp/openvpn3-deps/fmt)
set(MBEDTLS_INSTALL /tmp/openvpn3-deps/install-arm64/mbedtls)
set(LZ4_INSTALL /tmp/openvpn3-deps/install-arm64/lz4)
add_library(ovpncli_static STATIC
    ${CORE_DIR}/client/ovpncli.cpp
    ${CORE_DIR}/openvpn/crypto/data_epoch.cpp
)
target_include_directories(ovpncli_static PRIVATE
    ${CORE_DIR} ${CORE_DIR}/client ${ASIO_DIR}/include ${FMT_DIR}/include
    ${MBEDTLS_INSTALL}/include ${LZ4_INSTALL}/include
)
target_compile_definitions(ovpncli_static PRIVATE
    -DASIO_STANDALONE -DUSE_ASIO -DHAVE_LZ4 -DUSE_MBEDTLS -DFMT_HEADER_ONLY
    -DOPENVPN_FORCE_TUN_NULL=0
)
target_link_libraries(ovpncli_static PRIVATE
    ${MBEDTLS_INSTALL}/lib/libmbedtls.a ${MBEDTLS_INSTALL}/lib/libmbedx509.a
    ${MBEDTLS_INSTALL}/lib/libmbedcrypto.a ${LZ4_INSTALL}/lib/liblz4.a
)
```

**Important — known bug to avoid this time:** the `-DOPENVPN_FORCE_TUN_NULL=0` line above looks harmless but is NOT — `client/ovpncli.cpp` checks this macro with `#if !defined(OPENVPN_FORCE_TUN_NULL)`, not by value. Defining it to `0` still satisfies `defined(...)`, silently disabling `USE_TUN_BUILDER` and routing to a no-op null tun device — meaning every `tun_builder_*` callback (the entire point of this integration) becomes dead code that compiles and links fine but never fires. **Leave the macro fully undefined** — do not pass `-DOPENVPN_FORCE_TUN_NULL` at all, in any form. (This was discovered and fixed once already; Task 2's CMakeLists.txt must not reintroduce it.)

```bash
export ANDROID_NDK_HOME=<path to NDK 29.0.14206865>
export PATH=<path to Android SDK's cmake 3.31.6 bin>:$PATH
cmake -B build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release
cmake --build build -j$(nproc)
```

- [ ] **Step 4: Verify the output artifact**

```bash
$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf -h build/libovpncli_static.a | grep -i machine
$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm -C --defined-only build/libovpncli_static.a | grep "ClientAPI::OpenVPNClient::"
```

Expect AArch64, and ~46 real `openvpn::ClientAPI::OpenVPNClient::` member symbols including constructor/destructor, `connect_run()`, `socket_protect()`, `stop()`. Mangled names needed by later tasks: `_ZN7openvpn9ClientAPI13OpenVPNClient11connect_runEv` (connect_run), and the class `client/ovpncli.hpp` declares is the target for Task 5+'s Go/cgo bridge.

- [ ] **Step 5: Commit nothing yet (this task is scratch-only investigation) — proceed to Task 2 with the proven recipe above.**

---

### Task 2: Build OpenVPN3 Core for all 4 ABIs, wire into Gradle

**Files:**
- Create: `openvpn3-core/` submodule (top level of the Haven repo, pinned to the commit Task 1 cloned) plus `openvpn3-core/build-android.sh` (Task 1's recipe, parameterized over `arm64-v8a:arm64`, `armeabi-v7a:armeabi-v7a`, `x86:x86`, `x86_64:amd64` — ABI directory name : `go-openvpn`'s existing cgo `-l` suffix convention) and `openvpn3-core/CMakeLists.txt` (Task 1's recipe as a real file, **without** the `OPENVPN_FORCE_TUN_NULL` bug — leave the macro fully undefined).
- Modify: `core/tunnel/build.gradle.kts` — add a `buildOpenvpn3Native` `Exec` task mirroring `core/local`'s `buildWayvncShim`/`fetchUml` pattern: `inputs.file`/`inputs.dir`/`outputs.dir`, `onlyIf { script.exists() && src.exists() && !project.hasProperty("skipOpenvpn3Native") }` (the skip property matters — see below), `workingDir`, `commandLine("bash", "build-android.sh")`, NDK auto-detected by the script itself (do **not** hardcode `environment("ANDROID_NDK_HOME", ...)` in the Gradle task — let `build-android.sh` find it the same way `build-proot/build.sh` does; a hardcoded absolute path here was tried once and is a portability regression), `tasks.named("preBuild") { dependsOn(buildOpenvpn3Native) }`.
- Modify: `scripts/check-native-libs.sh` — add a third block (after the existing `RDP_DIR`/`PRNS_DIR` blocks) checking `core/tunnel/jniLibs-openvpn3/<abi>/libopenvpn3_android_<suffix>.a` per ABI: right machine type via `readelf -h`, and the `connect_run` symbol present via `nm` (static `.a`, not `--dyn-syms` which is for shared libs).

**Interfaces:**
- Produces: `core/tunnel/jniLibs-openvpn3/<abi-dir>/libopenvpn3_android_<suffix>.a`, one per ABI.

- [ ] **Step 1–4: parameterize, add the Gradle task with the `-PskipOpenvpn3Native` escape hatch** (matches `fetchQemuLoaders`'s `-PskipQemuLoaders`/`fetchUml`'s `-PskipUml` — this codebase already has the F-Droid-buildserver-network-access problem solved for exactly this class of task; don't skip it here), run the build for all 4 ABIs, verify with `check-native-libs.sh`.
- [ ] **Step 5: Commit.**

---

### Task 2c: Bundle mbedtls+lz4 object code into the openvpn3 static library

**Known finding from the first attempt:** `target_link_libraries(ovpncli_static PRIVATE ...)` on a CMake STATIC target does NOT merge the linked libraries' object code into the output archive — it's a CMake-only transitive usage requirement, invisible to cgo's raw `-L`/`-l` consumption. Fix: after `ovpncli_static` builds, merge mbedtls (3 archives) + lz4 (1 archive) into the same output `.a` via an `ar -M` MRI script (`CREATE`/`ADDLIB` × 4/`SAVE`/`END`), as a CMake `add_custom_command(TARGET ovpncli_static POST_BUILD ...)`. This keeps the downstream contract (`-L <dir> -lopenvpn3_android_<suffix>`, nothing else) unchanged. Verify via a throwaway Go program importing the vendored `openvpn3` package (once Task 3 exists) and confirming zero undefined symbols using only the existing cgo directive.

---

### Task 2d: Fix `ceilf`/libm link gap on 32-bit ABIs

**Known finding:** `armeabi-v7a`/`x86` fail with `undefined symbol: ceilf` (a plain libm gap, unrelated to mbedtls/lz4/OpenVPN3). Fix: add `-lm` to all 4 `android,*` cgo LDFLAGS lines in `rclone-android/go/ovpnbridge/openvpn3/session.go` (Task 3's vendored file) — uniform across all 4 ABIs is fine, libm is always present and linking an unused symbol from it costs nothing.

---

### Task 3: Vendor go-openvpn's Go/CGO glue, re-pointed at our native libraries

**Files:**
- Create: `rclone-android/go/ovpnbridge/openvpn3/` — vendored copy of `mysteriumnetwork/go-openvpn`'s `openvpn3/` package: `session.go`, `config.go`, `credentials.go`, `c_args.go`, `callback_registry.go`, `callback_bridge.go`, `tun_setup.go`, `tun_setup_bridge.go`, `tun_setup_registry.go`, and the `bridge/{tunsetup.h,library.h,user_data.h}` headers only (not the stale prebuilt `.a` files).
- Modify (within the vendored copy): `session.go`'s cgo LDFLAGS — point `-L` at `${SRCDIR}/../../../../core/tunnel/jniLibs-openvpn3/<abi-dir>`, drop non-Android platform `#cgo` lines, add `-lm` to all 4 android lines (Task 2d).
- Create: `rclone-android/go/ovpnbridge/openvpn3/LICENSE-NOTICE` — AGPLv3 provenance note.

**Known API surface** (from `library.h`): 6 `extern "C"` functions `new_session`/`start_session`/`stop_session`/`reconnect_session`/`cleanup_session`/`check_library` that Task 3b's adapter must implement — **`go-openvpn`'s Go code does not call `ClientAPI::OpenVPNClient` directly**, it calls through this thin C adapter layer, which only ever existed in a defunct `mysteriumnetwork/openvpn3` fork and was never vendored as source anywhere (only shipped compiled into go-openvpn's stale 2020 `.a`). This is not optional — session.go will not link without Task 3b.

- [ ] Check `rclone-android/go/go.mod`'s real module path first (do not guess).
- [ ] Copy the files, edit `session.go`'s cgo block, verify `go vet`/syntax is clean (link will fail until Task 3b exists — expected).
- [ ] Commit.

---

### Task 3b: OpenVPN3 C adapter layer

Write `openvpn3-core/adapter.cpp` (outside the `openvpn3` submodule, which stays pristine) implementing `library.h`'s 6 functions as a thin wrapper subclassing `openvpn::ClientAPI::OpenVPNClient` — override its pure-virtual methods (`event`, `pause_on_connection_timeout`, `acc_event`, `external_pki_cert_request`/`external_pki_sign_request` — the last two are pure virtual but should be real-but-inert no-ops since `disableClientCert=true` means they should never actually fire) forwarding into the C function pointers from `callbacks_delegate`/`tun_builder_callbacks`. Add as a third source file to `openvpn3-core/CMakeLists.txt`'s target, plus the two new include paths (adapter.cpp's own dir + the vendored bridge headers dir from Task 3).

**Known hazard to fix proactively this time:** none of the 6 `extern "C"` functions should let a C++ exception cross into the cgo/Go caller (undefined behavior) — wrap each function body in `try { ... } catch (const std::exception&) { ... } catch (...) { ... }`, translating to that function's existing error convention (`new_session` → `nullptr`, `start_session` → non-zero, void-returning → no-op). Keep any exception-handler logging itself simple (a pre-formatted fallback string, not a fresh heap-allocating `std::string` concatenation, which could itself throw under a secondary OOM and reopen the same problem).

Object lifetime: `new_session` must delete the partially-constructed client on any post-construction failure path (`eval_config`/`provide_creds` failure) and return `nullptr`; `cleanup_session` does exactly one `delete`, null-safe; `stop_session`/`reconnect_session` null-guard before delegating.

Rebuild all 4 ABIs, verify the 6 previously-undefined symbols now resolve via a throwaway Go link test, clean up the throwaway test, commit.

---

### Task 4: `ovpnbridge` — socketpair-to-netstack pump (the new engineering)

Pure Go, independently unit-testable without a real OpenVPN3 session — TDD it properly.

**Files:**
- Create: `rclone-android/go/ovpnbridge/pump.go`, `rclone-android/go/ovpnbridge/pump_test.go`

**Known design trap from the first attempt — avoid repeating it:** `netstack.CreateNetTUN`'s `tun.Device` is **not a loopback** — `Write()` injects a packet as *inbound* traffic into the gVisor stack's routing/socket-delivery logic, `Read()` yields packets the stack itself wants to *transmit outbound*. A test that writes a packet in and expects the same bytes back via `Read()` is testing something that was never true. Since `tun.Device` is a genuine 8-method Go interface (`File() *os.File`, `Read(bufs [][]byte, sizes []int, offset int) (n int, err error)`, `Write(bufs [][]byte, offset int) (int, error)`, `MTU`, `Name`, `Events() <-chan Event`, `Close`, `BatchSize() int` — confirm via `go doc golang.zx2c4.com/wireguard/tun.Device`), write a **hand-written fake** implementing it (channels for observing `Write()` calls and injecting `Read()` data) for a true, deterministic unit test of the pump's own relay logic in both directions — leave real-gVisor-routing verification to Task 5's real-server integration test.

**Known concurrency hazard from the first attempt — avoid repeating it:** don't do raw fd syscalls (`unix.Select`/`unix.Read` on the bare int fd) in one goroutine while a different goroutine's `Close()` closes the same fd via `p.file.Close()` — there's a window where the OS can reuse the closed fd number before the raw-syscall goroutine's next read, silently reading an unrelated resource. **Fix:** set the socketpair fd non-blocking (`syscall.SetNonblock(nativeFD, true)`) *before* wrapping it with `os.NewFile`, then use plain `p.file.Read()`/`p.file.Write()` calls only — Go's runtime-integrated poller (`internal/poll`) then makes a concurrent `p.file.Close()` correctly and promptly interrupt a pending `Read()` (same mechanism `net.Conn` cancellation uses), which also gets `EINTR` retry for free (the runtime's read/write wrappers retry it internally) and lets you delete any manual `unix.Select` polling loop entirely — no raw fd syscalls anywhere in the file, no fd-reuse race.

```go
package ovpnbridge

import (
	"fmt"
	"os"
	"sync"
	"syscall"

	"golang.zx2c4.com/wireguard/tun"
)

type packetPump struct {
	file   *os.File
	dev    tun.Device
	wg     sync.WaitGroup
	closed chan struct{}
}

func newPacketPump(nativeFD int, dev tun.Device) (*packetPump, error) {
	if err := syscall.SetNonblock(nativeFD, true); err != nil {
		return nil, fmt.Errorf("set nonblocking: %w", err)
	}
	f := os.NewFile(uintptr(nativeFD), "ovpn-socketpair")
	if f == nil {
		return nil, fmt.Errorf("os.NewFile failed for fd %d", nativeFD)
	}
	p := &packetPump{file: f, dev: dev, closed: make(chan struct{})}
	p.wg.Add(2)
	go p.pumpFromNativeToTun()
	go p.pumpFromTunToNative()
	return p, nil
}

func (p *packetPump) pumpFromNativeToTun() {
	defer p.wg.Done()
	buf := make([]byte, 2048)
	for {
		n, err := p.file.Read(buf)
		if err != nil {
			return
		}
		if n == 0 {
			continue
		}
		if _, err := p.dev.Write([][]byte{buf[:n]}, 0); err != nil {
			return
		}
	}
}

func (p *packetPump) pumpFromTunToNative() {
	defer p.wg.Done()
	bufs := make([][]byte, 1)
	bufs[0] = make([]byte, 2048)
	sizes := make([]int, 1)
	for {
		n, err := p.dev.Read(bufs, sizes, 0)
		if err != nil {
			return
		}
		for i := 0; i < n; i++ {
			if _, err := p.file.Write(bufs[i][:sizes[i]]); err != nil {
				return
			}
		}
	}
}

func (p *packetPump) Close() error {
	select {
	case <-p.closed:
		return nil
	default:
		close(p.closed)
	}
	fileErr := p.file.Close()
	devErr := p.dev.Close()
	p.wg.Wait()
	if fileErr != nil {
		return fileErr
	}
	return devErr
}
```

Write tests using a fake `tun.Device`: one confirming a packet written to the socketpair arrives via the fake's `Write()` with exact bytes; one confirming a packet injected via the fake's `Read()` channel arrives on the socketpair's peer fd with exact bytes; a Close-idempotency test with an explicit short-deadline assertion (`select`/`time.After`, not the ambient `go test` timeout). Commit.

---

### Task 5: `OvpnTunnelSetup` and `StartTunnel` — wire the pump into a real session

**Files:**
- Create: `rclone-android/go/ovpnbridge/tunnelsetup.go`, `rclone-android/go/ovpnbridge/ovpnbridge.go`, `rclone-android/go/ovpnbridge/ovpnbridge_test.go`

`OvpnTunnelSetup` implements `openvpn3.TunnelSetup`: override **every** `TunnelSetup` method the interface exposes with a real value (not just the ones that seem relevant) — `NoOpTunnelSetup`'s embedded defaults return `false` for everything, and OpenVPN3 core aborts tun setup if *any* invoked builder callback returns false, so overrides for `SetRemoteAddress`/`SetRouteMetricDefault`/`RerouteGw`/`AddRoute`/`ExcludeRoute`/`AddDnsServer`/`AddSearchDomain`/`SetSessionName` → `true` (and `Persist` → `false`) are required, not optional, alongside `NewBuilder`/`SetLayer`/`AddAddress`/`SetMtu`/`SocketProtect`/`Establish`/`Teardown`.

`Establish()` accumulates addresses/mtu, creates the netstack + socketpair + `newPacketPump` (Task 4), returns the native-side fd. **Guard against reconnect re-entry from the start this time**: OpenVPN3 may call `Establish()` more than once (reconnect is normal OpenVPN behavior, not rare); close any previous pump first (share the cleanup path with `Teardown()`), and guard the "notify `StartTunnel` we're ready" channel send with a `sync.Once` so a second/third `Establish()` call never blocks trying to notify an already-answered caller.

`StartTunnel(configText, username, password string) (*TunnelHandle, error)`: `config := openvpn3.NewConfig(configText)`; **`config.DisableClientCert` must be set to `false`** — `NewConfig`'s default is `true`, which silently breaks any mutual-TLS server (the common case) by omitting the client cert entirely; `config.CompressionMode = "no"` unconditionally. Bridge the async connect (buffered-1 `established`/`failed` channels + a 30s timeout) into a synchronous return, `session.Stop()` on every losing path.

`TunnelHandle`/`Conn`: structurally parallel to `wgbridge.TunnelHandle`/`wgbridge.Conn` — `Dial`, `StartSocksListener`, `BindAddr`, `Close`; `Conn.Read`/`Write`/`Close`/`RemoteAddrHost`. `ListenTCP`/`ListenUDP` omitted (YAGNI, not part of v1).

Integration test: real local `openvpn` server process + EasyRSA-generated throwaway certs (system `openvpn`/`easyrsa` packages, install if missing), real handshake via `StartTunnel`, assert the server-pushed tunnel IP. **Known environment gap**: a full packet-forwarding round trip needs `CAP_NET_ADMIN` for a real TUN device on the *server* side, which a sandboxed dev environment may not have — if so, the handshake-only test (which still proves `Establish()` ran end-to-end: netstack + socketpair + pump all created) is sufficient evidence; skip the round-trip test gracefully with `t.Skip` and a concretely diagnosed cause rather than failing the whole task.

**Known "how do I run tests against Android-cross-compiled cgo code" answer**: build OpenVPN3 Core a *second* time for host `linux/amd64` (new `openvpn3-core/build-host-test.sh`, `USE_TUN_BUILDER` forced) plus one additional `#cgo linux,amd64 LDFLAGS` line in the vendored `session.go` — this is additive, inert for the shipped Android build, and lets `go test` actually execute (not just cross-compile-and-link) the integration test on the host running CI/dev.

Commit.

---

### Task 6: gomobile-bind `ovpnbridge` into `libgojni.so`

**Files:**
- Modify: `rclone-android/tools/build-android.sh` — add `./ovpnbridge` to **both** `gomobile bind` invocations (the full `rcbridge.aar` and the terminal-flavour `rcbridge-terminal.aar` — it doesn't depend on rclone so belongs in both), alongside the existing `wgbridge`/`tsbridge`/`mailbridge`/`nbbridge`. No `-target` list change (still `android/arm64,android/amd64,android/arm` — `x86` was never targeted here, unrelated to this task).

Verify: extract the real generated class from the built bindings jar (`javap` on `Ovpnbridge.class`) — expect `sh.haven.rclone.binding.ovpnbridge.Ovpnbridge` with `startTunnel(String, String, String) → TunnelHandle`, matching the `sh.haven.rclone.binding.wgbridge.Wgbridge` naming convention exactly. Confirm real OpenVPN3/mbedtls linkage in the output `.so` via `llvm-nm -D --defined-only | grep -c openvpn|mbedtls` (expect thousands of real symbols) and `-u | grep -c mbedtls_` (expect 0 undefined). Commit.

---

### Task 7: `OpenVpnTunnel.kt` — Kotlin `Tunnel` implementation

**Files:**
- Create: `core/tunnel/src/main/kotlin/sh/haven/core/tunnel/OpenVpnTunnel.kt`

Mirrors `WireguardTunnel.kt` closely: constructor wraps `Ovpnbridge.startTunnel(configText, username, password)` in try/catch → `IOException`; `dial`/`socksAddress`/`localAddress`/`close` delegate to the native `TunnelHandle`; private `NativeOvpnTunneledConnection` wraps the native `Conn` as `TunneledConnection` with the same gomobile `[]byte`-copy `InputStream`/`OutputStream` handling. `listenUdp`/`listenTcp` not overridden (interface default `null` — `ovpnbridge` doesn't expose inbound listeners in v1). **Confirm the real gomobile-generated method signatures** (Task 6's `javap` output) before writing the calls — don't assume they match `Wgbridge`'s shape purely by naming-convention analogy.

Commit.

---

### Task 8: Config storage — `OpenVpnConfigBlob`, `TunnelConfigType.OPENVPN`, dispatch

**Files:**
- Create: `core/tunnel/src/main/kotlin/sh/haven/core/tunnel/OpenVpnConfigBlob.kt` (JSON envelope `{ovpnConfigText, username?, password?}`, mirrors `TailscaleConfigBlob.kt`'s pattern exactly — `org.json.JSONObject`, no legacy-format fallback needed since this is a new type).
- Modify: `core/data/src/main/kotlin/sh/haven/core/data/db/entities/TunnelConfig.kt` (add `OPENVPN` to the `TunnelConfigType` enum, update the class kdoc's per-type payload list).
- Modify: `core/tunnel/src/main/kotlin/sh/haven/core/tunnel/TunnelManager.kt` (new `when` arm: `TunnelConfigType.OPENVPN -> { val parsed = OpenVpnConfigBlob.parse(config.configText) ?: throw IOException(...); OpenVpnTunnel(parsed.ovpnConfigText, parsed.username, parsed.password) }`).

Unit test for `OpenVpnConfigBlob` (round-trip encode/parse, malformed input returns null). Commit.

---

### Task 9: UI — add "OpenVPN" to the tunnel-type picker

**Files:**
- Modify: whichever screen currently offers WIREGUARD/TAILSCALE/CLOUDFLARE_ACCESS/NETBIRD — locate via `grep -rln "TunnelConfigType.WIREGUARD\|TunnelConfigType.TAILSCALE" feature/ app/ --include=*.kt`, don't guess the path.

Add an "OpenVPN" entry: multiline paste field for the `.ovpn` file, username/password fields shown when the pasted text contains `auth-user-pass` (heuristic only — OpenVPN3 Core is the real authority). Wire "Save" to construct an `OpenVpnConfigBlob` and persist via the same `TunnelConfigRepository` path the WireGuard entry uses. Commit.

---

### Task 10: End-to-end verification

Full build: `./gradlew :app:assembleArm64FullDebug -PtargetAbi=arm64 -PskipWaylandNatives=true -PskipFfmpegNatives=true -PskipUml -PskipQemuLoaders --no-daemon`. `scripts/check-native-libs.sh` passes. `CHANGELOG.md` entry (this repo's release gate requires one, per `scripts/check-changelog.sh`). Manual smoke test against a real OpenVPN server once installed on a device. Final commit.
