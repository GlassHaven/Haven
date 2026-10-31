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

// #include <library.h>
import "C"

// UserCredentials represents the credentials structure
type UserCredentials struct {
	Username string
	Password string
}

func (credentials *UserCredentials) toPtr() (cCredentials C.user_credentials, unregister func()) {
	cUsername := newCharPointer(credentials.Username)
	cPassword := newCharPointer(credentials.Password)

	cCredentials = C.user_credentials{
		username: cUsername.Ptr,
		password: cPassword.Ptr,
	}
	unregister = func() {
		cUsername.delete()
		cPassword.delete()
	}
	return
}
