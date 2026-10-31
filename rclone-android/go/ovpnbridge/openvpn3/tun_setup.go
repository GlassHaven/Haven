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

import "errors"

// TunnelSetup is the interface representing the tunnel setup
type TunnelSetup interface {
	NewBuilder() bool
	SetLayer(layer int) bool
	SetRemoteAddress(ipAddress string, ipv6 bool) bool
	AddAddress(address string, prefixLength int, gateway string, ipv6 bool, net30 bool) bool
	SetRouteMetricDefault(metric int) bool
	RerouteGw(ipv4 bool, ipv6 bool, flags int) bool
	AddRoute(address string, prefixLength int, metric int, ipv6 bool) bool
	ExcludeRoute(address string, prefixLength int, metric int, ipv6 bool) bool
	AddDnsServer(address string, ipv6 bool) bool
	AddSearchDomain(domain string) bool
	SetMtu(mtu int) bool
	SetSessionName(name string) bool
	AddProxyBypass(bypassHost string) bool
	SetProxyAutoConfigUrl(url string) bool
	SetProxyHttp(host string, port int) bool
	SetProxyHttps(host string, port int) bool
	AddWinsServer(address string) bool
	SetBlockIpv6(ipv6Block bool) bool
	SetAdapterDomainSuffix(name string) bool
	Establish() (int, error)
	Persist() bool
	EstablishLite()
	Teardown(disconnect bool)
	SocketProtect(socket int) bool
}

//NoOpTunnelSetup by default returns false everywhere - to indicate that it is not supposed to be called and actual
//tunnel setup will not succeed
type NoOpTunnelSetup struct {
}

// NewBuilder - noop - returns false
func (setup *NoOpTunnelSetup) NewBuilder() bool {
	return false
}

// SetLayer - noop - returns false
func (setup *NoOpTunnelSetup) SetLayer(layer int) bool {
	return false
}

// SetRemoteAddress - noop - returns false
func (setup *NoOpTunnelSetup) SetRemoteAddress(ipAddress string, ipv6 bool) bool {
	return false
}

// AddAddress - noop - returns false
func (setup *NoOpTunnelSetup) AddAddress(address string, prefixLength int, gateway string, ipv6 bool, net30 bool) bool {
	return false
}

// SetRouteMetricDefault - noop - returns false
func (setup *NoOpTunnelSetup) SetRouteMetricDefault(metric int) bool {
	return false
}

// RerouteGw - noop - returns false
func (setup *NoOpTunnelSetup) RerouteGw(ipv4 bool, ipv6 bool, flags int) bool {
	return false
}

// AddRoute - noop - returns false
func (setup *NoOpTunnelSetup) AddRoute(address string, prefixLength int, metric int, ipv6 bool) bool {
	return false
}

// ExcludeRoute - noop - returns false
func (setup *NoOpTunnelSetup) ExcludeRoute(address string, prefixLength int, metric int, ipv6 bool) bool {
	return false
}

// AddDnsServer - noop - returns false
func (setup *NoOpTunnelSetup) AddDnsServer(address string, ipv6 bool) bool {
	return false
}

// AddSearchDomain - noop - returns false
func (setup *NoOpTunnelSetup) AddSearchDomain(domain string) bool {
	return false
}

// SetMtu - noop - returns false
func (setup *NoOpTunnelSetup) SetMtu(mtu int) bool {
	return false
}

// SetSessionName - noop - returns false
func (setup *NoOpTunnelSetup) SetSessionName(name string) bool {
	return false
}

// AddProxyBypass - noop - returns false
func (setup *NoOpTunnelSetup) AddProxyBypass(bypassHost string) bool {
	return false
}

// SetProxyAutoConfigUrl - noop - returns false
func (setup *NoOpTunnelSetup) SetProxyAutoConfigUrl(url string) bool {
	return false
}

// SetProxyHttp - noop - returns false
func (setup *NoOpTunnelSetup) SetProxyHttp(host string, port int) bool {
	return false
}

// SetProxyHttps - noop - returns false
func (setup *NoOpTunnelSetup) SetProxyHttps(host string, port int) bool {
	return false
}

// AddWinsServer - noop - returns false
func (setup *NoOpTunnelSetup) AddWinsServer(address string) bool {
	return false
}

// SetBlockIpv6 - noop - returns false
func (setup *NoOpTunnelSetup) SetBlockIpv6(ipv6Block bool) bool {
	return false
}

// SetAdapterDomainSuffix - noop - returns false
func (setup *NoOpTunnelSetup) SetAdapterDomainSuffix(name string) bool {
	return false
}

// Establish - noop - returns a noop operation error
func (setup *NoOpTunnelSetup) Establish() (int, error) {
	return 0, errors.New("noop operation")
}

// Persist - noop - returns false
func (setup *NoOpTunnelSetup) Persist() bool {
	return false
}

// EstablishLite - noop - does nothing
func (setup *NoOpTunnelSetup) EstablishLite() {

}

// Teardown - noop - does nothing
func (setup *NoOpTunnelSetup) Teardown(disconnect bool) {

}

// SocketProtect - noop - does nothing
func (setup *NoOpTunnelSetup) SocketProtect(socket int) bool {
	return false
}

var _ TunnelSetup = &NoOpTunnelSetup{}
