/*
 * go-openvpn -- Go gettable library for wrapping Openvpn functionality in go way.
 *
 * Copyright (C) 2020 BlockDev AG.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License Version 3
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with this program in the COPYING file.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package openvpn3

/*

#cgo CFLAGS: -I${SRCDIR}/bridge

// Haven only ships for Android; native libraries come from
// openvpn3-core/build-android.sh's output at
// core/tunnel/jniLibs-openvpn3/<abi>/libopenvpn3_android_<suffix>.a (one
// self-contained archive per ABI -- mbedtls/lz4 object code is merged into
// it at build time, see openvpn3-core/CMakeLists.txt's POST_BUILD step, so
// nothing beyond -lopenvpn3_android_<suffix> is needed here). -lm is added
// on all 4 lines defensively: only armeabi-v7a/x86 actually need it
// (a plain libm gap on `ceilf`, unrelated to mbedtls/lz4/OpenVPN3), but
// libm is always present on Android and linking an unused symbol from it
// costs nothing.
#cgo android,arm64 LDFLAGS: -L${SRCDIR}/../../../../core/tunnel/jniLibs-openvpn3/arm64-v8a -lopenvpn3_android_arm64 -lm
#cgo android,amd64 LDFLAGS: -L${SRCDIR}/../../../../core/tunnel/jniLibs-openvpn3/x86_64 -lopenvpn3_android_amd64 -lm
#cgo android,386 LDFLAGS: -L${SRCDIR}/../../../../core/tunnel/jniLibs-openvpn3/x86 -lopenvpn3_android_x86 -lm
#cgo android,arm LDFLAGS: -L${SRCDIR}/../../../../core/tunnel/jniLibs-openvpn3/armeabi-v7a -lopenvpn3_android_armeabi-v7a -lm

// Task 3b's openvpn3-core/adapter.cpp (new_session/start_session/etc, see
// bridge/library.h) links libc++ symbols from OpenVPN3 Core; the Android
// NDK's clang needs these two explicit static libc++ libs to resolve them
// at cgo link time (ordering matters: c++abi after c++_static).
#cgo android LDFLAGS: -lc++_static -lc++abi

#include <library.h>

*/
import "C"
import (
	"errors"
	"sync"
)

// Session represents the openvpn session
type Session struct {
	config          Config
	userCredentials UserCredentials
	callbacks       interface{}
	tunnelSetup     TunnelSetup
	finished        sync.WaitGroup
	stop            sync.WaitGroup
	reconnectChan   chan int

	// runtime variables
	resError error
}

// NewSession creates a new session given the callbacks
func NewSession(config Config, userCredentials UserCredentials, callbacks interface{}) *Session {
	return &Session{
		config:          config,
		userCredentials: userCredentials,
		callbacks:       callbacks,
		tunnelSetup:     &NoOpTunnelSetup{},
		resError:        nil,
		reconnectChan:   make(chan int, 1),
	}
}

// MobileSessionCallbacks are the callbacks required for a mobile session
type MobileSessionCallbacks interface {
	EventConsumer
	Logger
	StatsConsumer
}

// NewMobileSession creates a new mobile session provided the required callbacks and tunnel setup
func NewMobileSession(config Config, userCredentials UserCredentials, callbacks MobileSessionCallbacks, tunSetup TunnelSetup) *Session {
	return &Session{
		config:          config,
		userCredentials: userCredentials,
		callbacks:       callbacks,
		tunnelSetup:     tunSetup,
		resError:        nil,
		reconnectChan:   make(chan int, 1),
	}
}

// ErrInitFailed is the error we return when openvpn3 initiation fails
var ErrInitFailed = errors.New("openvpn3 init failed")

// ErrConnectFailed is the error we return when openvpn3 fails to connect
var ErrConnectFailed = errors.New("openvpn3 connect failed")

// Start starts the session
func (session *Session) Start() {
	session.finished.Add(1)
	session.stop.Add(1)
	go func() {
		defer session.finished.Done()
		cConfig, cConfigUnregister := session.config.toPtr()
		defer cConfigUnregister()

		cCredentials, cCredentialsUnregister := session.userCredentials.toPtr()
		defer cCredentialsUnregister()

		callbacksDelegate, removeCallback := registerCallbackDelegate(session.callbacks)
		defer removeCallback()

		tunBuilderCallbacks, removeTunCallbacks := registerTunnelSetupDelegate(session.tunnelSetup)
		defer removeTunCallbacks()

		sessionPtr, _ := C.new_session(
			cConfig,
			cCredentials,
			C.callbacks_delegate(callbacksDelegate),
			C.tun_builder_callbacks(tunBuilderCallbacks),
		)

		if sessionPtr == nil {
			session.resError = ErrInitFailed
			return
		}

		go func() {
			session.stop.Wait()
			C.stop_session(sessionPtr)
		}()

		go func() {
			for seconds := range session.reconnectChan {
				C.reconnect_session(sessionPtr, C.int(seconds))
			}
		}()

		res, _ := C.start_session(sessionPtr)
		if res != 0 {
			session.resError = ErrConnectFailed
		}

		C.cleanup_session(sessionPtr)
	}()
}

// Wait waits for the session to complete
func (session *Session) Wait() error {
	session.finished.Wait()
	return session.resError
}

// Stop stops the session
func (session *Session) Stop() {
	session.stop.Done()
	close(session.reconnectChan)
}

// Reconnect session without propagating DISCONNECT event after `seconds` time
func (session *Session) Reconnect(seconds int) error {
	session.reconnectChan <- seconds
	return nil
}
