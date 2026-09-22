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

#include <library.h>

extern void goLogCallback(user_callback_data usrData, char * str);

extern void goStatsCallback(user_callback_data usrData, conn_stats stats);

extern void goEventCallback(user_callback_data usrData, conn_event event);
*/
import "C"

var callbacks = newCallbackRegistry()

//export goStatsCallback
func goStatsCallback(ptr C.user_callback_data, cStats C.conn_stats) {
	id := int(ptr)
	var stats Statistics
	stats.BytesIn = uint64(cStats.bytes_in)
	stats.BytesOut = uint64(cStats.bytes_out)
	callbacks.stats(id, stats)
}

//export goLogCallback
func goLogCallback(ptr C.user_callback_data, cStr *C.char) {
	goStr := C.GoString(cStr)
	id := int(ptr)
	callbacks.log(id, goStr)
}

//export goEventCallback
func goEventCallback(ptr C.user_callback_data, cEvent C.conn_event) {
	id := int(ptr)
	var e Event
	e.Error = bool(cEvent.error)
	e.Fatal = bool(cEvent.fatal)
	e.Name = C.GoString(cEvent.name)
	e.Info = C.GoString(cEvent.info)
	callbacks.event(id, e)
}

// SelfCheck runs the openvpn self check
func SelfCheck(logger Logger) {
	id, callbackRemove := callbacks.register(logger)
	defer callbackRemove()
	C.check_library(C.user_callback_data(id), C.log_callback(C.goLogCallback))
}

type expCallbacks C.callbacks_delegate

func registerCallbackDelegate(callbacksDelegate interface{}) (expCallbacks, func()) {
	id, unregister := callbacks.register(callbacksDelegate)
	return expCallbacks{
		usrData:       C.user_callback_data(id),
		statsCallback: C.stats_callback(C.goStatsCallback),
		logCallback:   C.log_callback(C.goLogCallback),
		eventCallback: C.event_callback(C.goEventCallback),
	}, unregister

}
