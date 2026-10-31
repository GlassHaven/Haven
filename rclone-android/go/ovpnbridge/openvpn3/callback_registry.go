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

// Logger represents the logger
type Logger interface {
	Log(string)
}

// Event contains all the info relating to the event
type Event struct {
	Fatal bool
	Error bool
	Name  string
	Info  string
}

// EventConsumer represents an event consumer
type EventConsumer interface {
	OnEvent(Event)
}

// Statistics shows the bytes in/out for openvpn
type Statistics struct {
	BytesIn  uint64
	BytesOut uint64
}

// StatsConsumer consumes the bytes/in out statistics
type StatsConsumer interface {
	OnStats(Statistics)
}

type unregister func()

type callbackRegistry struct {
	lock   sync.Locker
	idMap  map[int]interface{}
	lastId int
}

func (cr *callbackRegistry) register(callbacks interface{}) (int, unregister) {
	cr.lock.Lock()
	defer cr.lock.Unlock()

	id := cr.lastId
	cr.lastId++

	cr.idMap[id] = callbacks

	return id, func() {
		cr.unregister(id)
	}
}

func (cr *callbackRegistry) unregister(id int) {
	cr.lock.Lock()
	defer cr.lock.Unlock()
	delete(cr.idMap, id)
}

func (cr *callbackRegistry) log(id int, text string) {
	cr.lock.Lock()
	defer cr.lock.Unlock()
	callback, ok := cr.idMap[id]
	if !ok {
		return
	}
	logCallback, ok := callback.(Logger)
	if !ok {
		return
	}
	logCallback.Log(text)
}

func (cr *callbackRegistry) event(id int, event Event) {
	cr.lock.Lock()
	defer cr.lock.Unlock()
	callback, ok := cr.idMap[id]
	if !ok {
		return
	}
	eventCallback, ok := callback.(EventConsumer)
	if !ok {
		return
	}
	eventCallback.OnEvent(event)

}

func (cr *callbackRegistry) stats(id int, stats Statistics) {
	cr.lock.Lock()
	defer cr.lock.Unlock()
	callback, ok := cr.idMap[id]
	if !ok {
		return
	}
	statsCallback, ok := callback.(StatsConsumer)
	if !ok {
		return
	}
	statsCallback.OnStats(stats)

}

func newCallbackRegistry() *callbackRegistry {
	return &callbackRegistry{
		lastId: 0,
		idMap:  make(map[int]interface{}),
		lock:   &sync.Mutex{},
	}
}
