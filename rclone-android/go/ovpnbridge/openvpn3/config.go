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

// NewConfig creates new Config from given profile key-value configuration string
func NewConfig(profile string) Config {
	return Config{
		ProfileContent:    profile,
		GuiVersion:        "cli 1.0",
		Info:              true,
		ClockTickMS:       1000, // ticks every 1 sec
		DisableClientCert: true,
		ConnTimeout:       10, // 10 seconds
		TunPersist:        true,
		CompressionMode:   "yes",
	}
}

// Config holds all parameters to start session
type Config struct {
	ProfileContent    string
	GuiVersion        string
	Info              bool
	ClockTickMS       int
	DisableClientCert bool
	ConnTimeout       int
	TunPersist        bool
	CompressionMode   string
}

func (config *Config) toPtr() (cConfig C.config, unregister func()) {
	cProfileContent := newCharPointer(config.ProfileContent)
	cGuiVersion := newCharPointer(config.GuiVersion)
	cCompressionMode := newCharPointer(config.CompressionMode)

	cConfig = C.config{
		profileContent:    cProfileContent.Ptr,
		guiVersion:        cGuiVersion.Ptr,
		info:              C.bool(config.Info),
		clockTickMS:       C.int(config.ClockTickMS),
		disableClientCert: C.bool(config.DisableClientCert),
		connTimeout:       C.int(config.ConnTimeout),
		tunPersist:        C.bool(config.TunPersist),
		compressionMode:   cCompressionMode.Ptr,
	}
	unregister = func() {
		cProfileContent.delete()
		cGuiVersion.delete()
		cCompressionMode.delete()
	}
	return
}
