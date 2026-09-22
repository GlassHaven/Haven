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

import "sync"

type tunSetupRegistry struct {
	lock   sync.Locker
	idMap  map[int]TunnelSetup
	lastId int
}

func (registry *tunSetupRegistry) register(delegate TunnelSetup) (int, func()) {
	registry.lock.Lock()
	defer registry.lock.Unlock()

	id := registry.lastId
	registry.lastId++
	registry.idMap[id] = delegate

	return id, func() {
		registry.unregister(id)
	}
}

func (registry *tunSetupRegistry) unregister(id int) {
	registry.lock.Lock()
	defer registry.lock.Unlock()
	delete(registry.idMap, id)
}

func (registry *tunSetupRegistry) lookup(id int) TunnelSetup {
	//to avoid error handling in Go -> C callbacks, return Noop TunnelSetup in case id is not registered or already
	//removed
	registry.lock.Lock()
	defer registry.lock.Unlock()
	tunSetup, ok := registry.idMap[id]
	if !ok {
		return &NoOpTunnelSetup{}
	}
	return tunSetup
}
