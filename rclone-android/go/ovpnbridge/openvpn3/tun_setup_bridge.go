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

#include <tunsetup.h>

extern bool  goNewBuilder(user_callback_data);

extern bool  goSetLayer(user_callback_data, int layer);

extern bool  goSetRemoteAddress(user_callback_data, char *ip_address, bool ipv6);

extern bool  goAddAddress(user_callback_data ,
                                       char *address,
                                       int prefix_length,
                                       char *gateway, // optional
                                       bool ipv6,
                                       bool net30);

extern bool  goSetRouteMetricDefault(user_callback_data , int metric);

extern bool  goRerouteGw(user_callback_data,
                                    bool ipv4,
                                      bool ipv6,
                                      unsigned int flags);

extern bool  goAddRoute(user_callback_data ,
                                    char *address,
                                     int prefix_length,
                                     int metric,
                                     bool ipv6);

extern bool  goExcludeRoute(user_callback_data,
                                        char *address,
                                         int prefix_length,
                                         int metric,
                                         bool ipv6);

extern bool  goAddDnsServer(user_callback_data , char *address, bool ipv6);

extern bool  goAddSearchDomain(user_callback_data , char *domain);

extern bool  goSetMtu(user_callback_data , int mtu);

extern bool  goSetSessionName(user_callback_data , char *name);

extern bool  goAddProxyBypass(user_callback_data , char *bypass_host);

extern bool  goSetProxyAutoConfigUrl(user_callback_data , char *url);

extern bool  goSetProxyHttp(user_callback_data , char *host, int port);

extern bool  goSetProxyHttps(user_callback_data , char * host, int port);

extern bool  goAddWinsServer(user_callback_data , char *address);

extern bool  goSetBlockIpv6(user_callback_data , bool block_ipv6);

extern bool  goSetAdapterDomainSuffix (user_callback_data , char *name);

extern int  goEstablish(user_callback_data);

extern bool  goPersist(user_callback_data);

extern void  goEstablishLite(user_callback_data);

extern void  goTeardown(user_callback_data , bool disconnect);

extern bool goSocketProtect(user_callback_data, int socket);

*/
import "C"
import "sync"

//export goNewBuilder
func goNewBuilder(cd C.user_callback_data) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	return C.bool(delegate.NewBuilder())
}

//export goSetLayer
func goSetLayer(cd C.user_callback_data, layer C.int) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	res := delegate.SetLayer(int(layer))
	return C.bool(res)
}

//export goSetRemoteAddress
func goSetRemoteAddress(cd C.user_callback_data, ipAddress *C.char, ipv6 C.bool) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	res := delegate.SetRemoteAddress(C.GoString(ipAddress), bool(ipv6))
	return C.bool(res)
}

//export goAddAddress
func goAddAddress(cd C.user_callback_data, address *C.char, prefixLength C.int, gateway *C.char, ipv6 C.bool, net30 C.bool) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	res := delegate.AddAddress(C.GoString(address), int(prefixLength), C.GoString(gateway), bool(ipv6), bool(net30))
	return C.bool(res)
}

//export goSetRouteMetricDefault
func goSetRouteMetricDefault(cd C.user_callback_data, metric C.int) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	res := delegate.SetRouteMetricDefault(int(metric))
	return C.bool(res)
}

//export goRerouteGw
func goRerouteGw(cd C.user_callback_data, ipv4 C.bool, ipv6 C.bool, flags C.uint) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	res := delegate.RerouteGw(bool(ipv4), bool(ipv6), int(flags))
	return C.bool(res)
}

//export goAddRoute
func goAddRoute(cd C.user_callback_data, address *C.char, prefixLength C.int, metric C.int, ipv6 C.bool) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	res := delegate.AddRoute(C.GoString(address), int(prefixLength), int(metric), bool(ipv6))
	return C.bool(res)
}

//export goExcludeRoute
func goExcludeRoute(cd C.user_callback_data, address *C.char, prefixLength C.int, metric C.int, ipv6 C.bool) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	res := delegate.ExcludeRoute(C.GoString(address), int(prefixLength), int(metric), bool(ipv6))
	return C.bool(res)
}

//export goAddDnsServer
func goAddDnsServer(cd C.user_callback_data, address *C.char, ipv6 C.bool) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	res := delegate.AddDnsServer(C.GoString(address), bool(ipv6))
	return C.bool(res)
}

//export goAddSearchDomain
func goAddSearchDomain(cd C.user_callback_data, domain *C.char) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	res := delegate.AddSearchDomain(C.GoString(domain))
	return C.bool(res)
}

//export goSetMtu
func goSetMtu(cd C.user_callback_data, mtu C.int) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	res := delegate.SetMtu(int(mtu))
	return C.bool(res)
}

//export goSetSessionName
func goSetSessionName(cd C.user_callback_data, name *C.char) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	res := delegate.SetSessionName(C.GoString(name))
	return C.bool(res)
}

//export goAddProxyBypass
func goAddProxyBypass(cd C.user_callback_data, bypassHost *C.char) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	res := delegate.AddProxyBypass(C.GoString(bypassHost))
	return C.bool(res)
}

//export goSetProxyAutoConfigUrl
func goSetProxyAutoConfigUrl(cd C.user_callback_data, url *C.char) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	res := delegate.SetProxyAutoConfigUrl(C.GoString(url))
	return C.bool(res)
}

//export goSetProxyHttp
func goSetProxyHttp(cd C.user_callback_data, host *C.char, port C.int) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	res := delegate.SetProxyHttp(C.GoString(host), int(port))
	return C.bool(res)
}

//export goSetProxyHttps
func goSetProxyHttps(cd C.user_callback_data, host *C.char, port C.int) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	res := delegate.SetProxyHttps(C.GoString(host), int(port))
	return C.bool(res)
}

//export goAddWinsServer
func goAddWinsServer(cd C.user_callback_data, address *C.char) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	res := delegate.AddWinsServer(C.GoString(address))
	return C.bool(res)
}

//export goSetBlockIpv6
func goSetBlockIpv6(cd C.user_callback_data, ipv6Block C.bool) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	res := delegate.SetBlockIpv6(bool(ipv6Block))
	return C.bool(res)
}

//export goSetAdapterDomainSuffix
func goSetAdapterDomainSuffix(cd C.user_callback_data, name *C.char) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	res := delegate.SetAdapterDomainSuffix(C.GoString(name))
	return C.bool(res)
}

//export goEstablish
func goEstablish(cd C.user_callback_data) C.int {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	sock, err := delegate.Establish()
	if err != nil {
		return -1 //indicated that socket establish failed
	}
	return C.int(sock)
}

//export goPersist
func goPersist(cd C.user_callback_data) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	res := delegate.Persist()
	return C.bool(res)
}

//export goEstablishLite
func goEstablishLite(cd C.user_callback_data) {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	delegate.EstablishLite()
}

//export goTeardown
func goTeardown(cd C.user_callback_data, disconnect C.bool) {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	delegate.Teardown(bool(disconnect))
}

//export goSocketProtect
func goSocketProtect(cd C.user_callback_data, socket C.int) C.bool {
	id := int(cd)
	delegate := tunnelSetupRegistry.lookup(id)
	return C.bool(delegate.SocketProtect(int(socket)))
}

var tunnelSetupRegistry = tunSetupRegistry{
	lock:   &sync.Mutex{},
	idMap:  make(map[int]TunnelSetup),
	lastId: 0,
}

func registerTunnelSetupDelegate(delegate TunnelSetup) (C.tun_builder_callbacks, func()) {
	id, unregister := tunnelSetupRegistry.register(delegate)
	return C.tun_builder_callbacks{
		usrData: C.user_callback_data(id),
		//delegates to go callbacks
		new_builder:               C.tun_builder_new(C.goNewBuilder),
		set_layer:                 C.tun_builder_set_layer(C.goSetLayer),
		set_remote_address:        C.tun_builder_set_remote_address(C.goSetRemoteAddress),
		add_address:               C.tun_builder_add_address(C.goAddAddress),
		set_route_metric_default:  C.tun_builder_set_route_metric_default(C.goSetRouteMetricDefault),
		reroute_gw:                C.tun_builder_reroute_gw(C.goRerouteGw),
		add_route:                 C.tun_builder_add_route(C.goAddRoute),
		exclude_route:             C.tun_builder_exclude_route(C.goExcludeRoute),
		add_dns_server:            C.tun_builder_add_dns_server(C.goAddDnsServer),
		add_search_domain:         C.tun_builder_add_search_domain(C.goAddSearchDomain),
		set_mtu:                   C.tun_builder_set_mtu(C.goSetMtu),
		set_session_name:          C.tun_builder_set_session_name(C.goSetSessionName),
		add_proxy_bypass:          C.tun_builder_add_proxy_bypass(C.goAddProxyBypass),
		set_proxy_auto_config_url: C.tun_builder_set_proxy_auto_config_url(C.goSetProxyAutoConfigUrl),
		set_proxy_http:            C.tun_builder_set_proxy_http(C.goSetProxyHttp),
		set_proxy_https:           C.tun_builder_set_proxy_https(C.goSetProxyHttps),
		add_wins_server:           C.tun_builder_add_wins_server(C.goAddWinsServer),
		set_block_ipv6:            C.tun_builder_set_block_ipv6(C.goSetBlockIpv6),
		set_adapter_domain_suffix: C.tun_builder_set_adapter_domain_suffix(C.goSetAdapterDomainSuffix),
		establish:                 C.tun_builder_establish(C.goEstablish),
		persist:                   C.tun_builder_persist(C.goPersist),
		establish_lite:            C.tun_builder_establish_lite(C.goEstablishLite),
		teardown:                  C.tun_builder_teardown(C.goTeardown),
		socket_protect:            C.tun_socket_protect(C.goSocketProtect),
	}, unregister
}
