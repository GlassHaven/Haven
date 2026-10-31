#ifndef __TUN_SETUP_H__
#define __TUN_SETUP_H__
/**
 *
 * C style exported functions for openvpn/tun/builder/base.hpp for using in GO
 * function names and meaming are the same as in base.hpp except parameters are adapted to C style (i.e. std::string -> const char * )
 * this tunnel builder is actually needed for iOS and Android style VPN setups (i.e. openvpn delegates tunnel setup to external methods)
 * first parameter usr_data represents uninterpreted data specified by user when creating tunnel builder structure
 * useful when doing callback id style C -> GO callbacks
 */

#include "user_data.h"

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>

//tun builder function types
typedef bool(*tun_builder_new)(user_callback_data);

typedef bool(*tun_builder_set_layer)(user_callback_data, int layer);

typedef bool(*tun_builder_set_remote_address)(user_callback_data, char *ip_address, bool ipv6);

typedef bool(*tun_builder_add_address)(user_callback_data ,
                                       char *address,
                                       int prefix_length,
                                       char *gateway, // optional
                                       bool ipv6,
                                       bool net30);

typedef bool(*tun_builder_set_route_metric_default)(user_callback_data , int metric);

typedef bool(*tun_builder_reroute_gw)(user_callback_data,
                                    bool ipv4,
                                      bool ipv6,
                                      unsigned int flags);

typedef bool(*tun_builder_add_route)(user_callback_data ,
                                    char *address,
                                     int prefix_length,
                                     int metric,
                                     bool ipv6);

typedef bool(*tun_builder_exclude_route)(user_callback_data,
                                        char *address,
                                         int prefix_length,
                                         int metric,
                                         bool ipv6);

typedef bool(*tun_builder_add_dns_server)(user_callback_data , char *address, bool ipv6);

typedef bool(*tun_builder_add_search_domain)(user_callback_data , char *domain);

typedef bool(*tun_builder_set_mtu)(user_callback_data , int mtu);

typedef bool(*tun_builder_set_session_name)(user_callback_data , char *name);

typedef bool(*tun_builder_add_proxy_bypass)(user_callback_data , char *bypass_host);

typedef bool(*tun_builder_set_proxy_auto_config_url)(user_callback_data , char *url);

typedef bool(*tun_builder_set_proxy_http)(user_callback_data , char *host, int port);

typedef bool(*tun_builder_set_proxy_https)(user_callback_data , char * host, int port);

typedef bool(*tun_builder_add_wins_server)(user_callback_data , char *address);

typedef bool(*tun_builder_set_block_ipv6)(user_callback_data , bool block_ipv6);

typedef bool(*tun_builder_set_adapter_domain_suffix)(user_callback_data , char *name);

typedef int(*tun_builder_establish)(user_callback_data);

typedef bool(*tun_builder_persist)(user_callback_data);

typedef void(*tun_builder_establish_lite)(user_callback_data);

typedef void(*tun_builder_teardown)(user_callback_data , bool disconnect);

typedef bool(*tun_socket_protect)(user_callback_data, int socket);

typedef struct {
    tun_builder_new new_builder;
    tun_builder_set_layer set_layer;
    tun_builder_set_remote_address set_remote_address;
    tun_builder_add_address add_address;
    tun_builder_set_route_metric_default set_route_metric_default;
    tun_builder_reroute_gw reroute_gw;
    tun_builder_add_route add_route;
    tun_builder_exclude_route exclude_route;
    tun_builder_add_dns_server add_dns_server;
    tun_builder_add_search_domain add_search_domain;
    tun_builder_set_mtu set_mtu;
    tun_builder_set_session_name set_session_name;
    tun_builder_add_proxy_bypass add_proxy_bypass;
    tun_builder_set_proxy_auto_config_url set_proxy_auto_config_url;
    tun_builder_set_proxy_http set_proxy_http;
    tun_builder_set_proxy_https set_proxy_https;
    tun_builder_add_wins_server add_wins_server;
    tun_builder_set_block_ipv6 set_block_ipv6;
    tun_builder_set_adapter_domain_suffix set_adapter_domain_suffix;
    tun_builder_establish establish;
    tun_builder_persist persist;
    tun_builder_establish_lite establish_lite;
    tun_builder_teardown teardown;
    tun_socket_protect socket_protect;
    user_callback_data usrData;
} tun_builder_callbacks;

#ifdef __cplusplus
}
#endif


#endif