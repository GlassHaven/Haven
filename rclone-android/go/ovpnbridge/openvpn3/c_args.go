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

// #include <stdlib.h>
import "C"
import "unsafe"

type cCharPointer struct {
	Ptr *C.char
}

func newCharPointer(str string) *cCharPointer {
	return &cCharPointer{
		Ptr: C.CString(str),
	}
}

func (ptr *cCharPointer) delete() {
	C.free(unsafe.Pointer(ptr.Ptr))
}

type cCharPointerArray struct {
	pointers []*C.char
}

func (a *cCharPointerArray) addAll(args ...string) {
	for _, arg := range args {
		a.pointers = append(a.pointers, C.CString(arg))
	}
}

func (a *cCharPointerArray) cPointer() **C.char {
	return (**C.char)(&a.pointers[0])
}

func (a *cCharPointerArray) cCount() C.int {
	return C.int(len(a.pointers))
}

func (a *cCharPointerArray) free() {
	for _, arg := range a.pointers {
		C.free(unsafe.Pointer(arg))
	}
}
