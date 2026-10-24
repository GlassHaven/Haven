// Haven's adapter layer bridging go-openvpn's C bridge (library.h/tunsetup.h,
// vendored at rclone-android/go/ovpnbridge/openvpn3/bridge/) to real
// OpenVPN3 Core (openvpn::ClientAPI::OpenVPNClient in client/ovpncli.hpp).
//
// Why this exists: go-openvpn's Go code (session.go) calls 6 plain C
// functions -- new_session/start_session/stop_session/reconnect_session/
// cleanup_session/check_library -- that are NOT part of upstream OpenVPN3
// Core. They were a thin adapter that only ever existed in a now-defunct
// fork (mysteriumnetwork/openvpn3), shipped compiled into go-openvpn's
// stale 2020 prebuilt binary, never available as source. This file is our
// own implementation of that same contract, against current upstream
// OpenVPN3 Core.
//
// Lives outside the openvpn3/ submodule so that submodule stays pristine
// upstream source (same split as build-proot/build.sh vs. the
// proot-termux submodule).

// client/ovpncli.hpp must come first -- it declares openvpn::ClientAPI::
// LogReceiver/LogInfo, which the two macros below reference. Both macros
// must be defined and logthread.hpp included before initprocess.hpp --
// mirrors client/ovpncli.cpp's own include order exactly (see its
// OPENVPN_LOG_CLASS/OPENVPN_LOG_INFO definitions and the comment
// "should be included early").
#include <client/ovpncli.hpp>

#define OPENVPN_LOG_CLASS openvpn::ClientAPI::LogReceiver
#define OPENVPN_LOG_INFO openvpn::ClientAPI::LogInfo
#include <openvpn/log/logthread.hpp>
#include <openvpn/init/initprocess.hpp>

#include <cstring>
#include <memory>
#include <string>

extern "C" {
#include "library.h"
#include "tunsetup.h"
}

namespace {

// C function pointers declared in tunsetup.h/library.h take char* (not
// const char*) even though they never write through it. Every callback
// below fires synchronously within the full-expression that owns the
// source std::string, so the const_cast is safe -- there's no window
// where the pointer outlives the string it points into.
char *mutableCStr(const std::string &s)
{
    return const_cast<char *>(s.c_str());
}

// Fallback strings for exception-handler logging that must not themselves
// allocate (a std::string concatenation in a catch block that's handling
// bad_alloc can throw again, silently reopening the same UB this guard
// exists to close).
constexpr const char *kNewSessionExceptionMsg = "new_session: exception during construction";
constexpr const char *kCheckLibraryExceptionMsg = "check_library: exception during self-test";

class HavenOpenVPNClient : public openvpn::ClientAPI::OpenVPNClient
{
  public:
    HavenOpenVPNClient(callbacks_delegate cb, tun_builder_callbacks tcb)
        : cb_(cb), tcb_(tcb)
    {
    }

    // ---- OpenVPNClient pure virtuals ----

    void event(const openvpn::ClientAPI::Event &ev) override
    {
        if (!cb_.eventCallback)
            return;
        conn_event ce{};
        ce.error = ev.error;
        ce.fatal = ev.fatal;
        ce.name = mutableCStr(ev.name);
        ce.info = mutableCStr(ev.info);
        cb_.eventCallback(cb_.usrData, ce);
    }

    void acc_event(const openvpn::ClientAPI::AppCustomControlMessageEvent &ev) override
    {
        if (!cb_.eventCallback)
            return;
        // No dedicated ACC event slot in the C bridge -- surface it through
        // the same event channel with an "ACC:"-prefixed name so the Go
        // side can still see it without a library.h change.
        std::string name = "ACC:" + ev.protocol;
        conn_event ce{};
        ce.error = false;
        ce.fatal = false;
        ce.name = mutableCStr(name);
        ce.info = mutableCStr(ev.payload);
        cb_.eventCallback(cb_.usrData, ce);
    }

    void log(const openvpn::ClientAPI::LogInfo &li) override
    {
        if (cb_.logCallback)
            cb_.logCallback(cb_.usrData, mutableCStr(li.text));
    }

    bool pause_on_connection_timeout() override
    {
        // false => core disconnects with CONNECTION_TIMEOUT (surfaced via
        // event()) rather than silently entering a PAUSE state Go can't see.
        return false;
    }

    void external_pki_cert_request(openvpn::ClientAPI::ExternalPKICertRequest &req) override
    {
        // Haven never sets up external-PKI profiles (DisableClientCert is
        // always false and profiles carry inline certs), so this should
        // never actually fire. Real-but-inert: signal failure without
        // touching cert/supportingChain, so core treats it as a declined
        // request rather than reading garbage.
        req.error = true;
        req.invalidAlias = true;
        req.errorText = "external PKI not supported";
    }

    void external_pki_sign_request(openvpn::ClientAPI::ExternalPKISignRequest &req) override
    {
        req.error = true;
        req.invalidAlias = true;
        req.errorText = "external PKI not supported";
    }

    // ---- TunBuilderBase overrides (forward to tcb_) ----

    bool tun_builder_new() override
    {
        return tcb_.new_builder ? tcb_.new_builder(tcb_.usrData) : false;
    }

    bool tun_builder_set_layer(int layer) override
    {
        return tcb_.set_layer ? tcb_.set_layer(tcb_.usrData, layer) : true;
    }

    bool tun_builder_set_remote_address(const std::string &address, bool ipv6) override
    {
        return tcb_.set_remote_address ? tcb_.set_remote_address(tcb_.usrData, mutableCStr(address), ipv6) : false;
    }

    bool tun_builder_add_address(const std::string &address,
                                  int prefix_length,
                                  const std::string &gateway,
                                  bool ipv6,
                                  bool net30) override
    {
        return tcb_.add_address ? tcb_.add_address(tcb_.usrData, mutableCStr(address), prefix_length, mutableCStr(gateway), ipv6, net30) : false;
    }

    bool tun_builder_set_route_metric_default(int metric) override
    {
        return tcb_.set_route_metric_default ? tcb_.set_route_metric_default(tcb_.usrData, metric) : true;
    }

    bool tun_builder_reroute_gw(bool ipv4, bool ipv6, unsigned int flags) override
    {
        return tcb_.reroute_gw ? tcb_.reroute_gw(tcb_.usrData, ipv4, ipv6, flags) : true;
    }

    bool tun_builder_add_route(const std::string &address, int prefix_length, int metric, bool ipv6) override
    {
        return tcb_.add_route ? tcb_.add_route(tcb_.usrData, mutableCStr(address), prefix_length, metric, ipv6) : true;
    }

    bool tun_builder_exclude_route(const std::string &address, int prefix_length, int metric, bool ipv6) override
    {
        return tcb_.exclude_route ? tcb_.exclude_route(tcb_.usrData, mutableCStr(address), prefix_length, metric, ipv6) : true;
    }

    // NOTE: current upstream TunBuilderBase no longer declares
    // tun_builder_add_dns_server/tun_builder_add_search_domain as virtuals
    // (merged into tun_builder_set_dns_options(const DnsOptions&) at some
    // point after go-openvpn's 2020 bridge/tunsetup.h was written, which
    // still has the old C function-pointer names). Not overriding the new
    // method just means DNS options the server pushes aren't forwarded to
    // the Go side -- harmless here, since Haven never touches system-level
    // DNS/routing regardless of what any tunnel type pushes.

    bool tun_builder_set_mtu(int mtu) override
    {
        return tcb_.set_mtu ? tcb_.set_mtu(tcb_.usrData, mtu) : true;
    }

    bool tun_builder_set_session_name(const std::string &name) override
    {
        return tcb_.set_session_name ? tcb_.set_session_name(tcb_.usrData, mutableCStr(name)) : true;
    }

    bool tun_builder_add_proxy_bypass(const std::string &bypass_host) override
    {
        return tcb_.add_proxy_bypass ? tcb_.add_proxy_bypass(tcb_.usrData, mutableCStr(bypass_host)) : false;
    }

    bool tun_builder_set_proxy_auto_config_url(const std::string &url) override
    {
        return tcb_.set_proxy_auto_config_url ? tcb_.set_proxy_auto_config_url(tcb_.usrData, mutableCStr(url)) : false;
    }

    bool tun_builder_set_proxy_http(const std::string &host, int port) override
    {
        return tcb_.set_proxy_http ? tcb_.set_proxy_http(tcb_.usrData, mutableCStr(host), port) : false;
    }

    bool tun_builder_set_proxy_https(const std::string &host, int port) override
    {
        return tcb_.set_proxy_https ? tcb_.set_proxy_https(tcb_.usrData, mutableCStr(host), port) : false;
    }

    bool tun_builder_add_wins_server(const std::string &address) override
    {
        return tcb_.add_wins_server ? tcb_.add_wins_server(tcb_.usrData, mutableCStr(address)) : false;
    }

    // NOTE: tun_builder_set_adapter_domain_suffix (Windows-adapter-only)
    // no longer exists in current upstream TunBuilderBase either -- same
    // situation as the DNS methods above, harmless to drop.

    int tun_builder_establish() override
    {
        return tcb_.establish ? tcb_.establish(tcb_.usrData) : -1;
    }

    bool tun_builder_persist() override
    {
        // false, not TunBuilderBase's own default of true: OvpnTunnelSetup
        // (Go side) always tears down and recreates its netstack/pump per
        // Establish() call rather than trying to persist state across
        // reconnects, so a persisted session here would leave stale state
        // the Go side never expects.
        return tcb_.persist ? tcb_.persist(tcb_.usrData) : false;
    }

    void tun_builder_establish_lite() override
    {
        if (tcb_.establish_lite)
            tcb_.establish_lite(tcb_.usrData);
    }

    void tun_builder_teardown(bool disconnect) override
    {
        if (tcb_.teardown)
            tcb_.teardown(tcb_.usrData, disconnect);
    }

    // 3-arg OpenVPNClient::socket_protect(socket, remote, ipv6) vs. the
    // 2-arg C bridge's tun_socket_protect(usrData, socket) -- the extra
    // remote/ipv6 context isn't in the C contract, so it's dropped here.
    // Haven never installs a VpnService or system-wide routes, so there's
    // no real routing loop to protect this socket against regardless.
    bool socket_protect(openvpn_io::detail::socket_type socket, std::string /*remote*/, bool /*ipv6*/) override
    {
        return tcb_.socket_protect ? tcb_.socket_protect(tcb_.usrData, static_cast<int>(socket)) : true;
    }

  private:
    callbacks_delegate cb_;
    tun_builder_callbacks tcb_;
    // Mirrors OpenVPNClientHelper's own init member (see ovpncli.hpp's
    // comment on why OpenVPNClientHelper uses a pointer instead -- that
    // constraint is about not pulling initprocess.hpp into a SWIG-wrapped
    // header; adapter.cpp is a plain .cpp file with no such constraint,
    // so a real member is simpler and needs no manual new/delete).
    openvpn::InitProcess::Init init_;
};

openvpn::ClientAPI::Config toCppConfig(const config &c)
{
    openvpn::ClientAPI::Config cfg;
    cfg.content = c.profileContent ? std::string(c.profileContent) : std::string();
    cfg.guiVersion = c.guiVersion ? std::string(c.guiVersion) : std::string();
    cfg.info = c.info;
    cfg.clockTickMS = c.clockTickMS;
    cfg.disableClientCert = c.disableClientCert;
    cfg.connTimeout = c.connTimeout;
    cfg.tunPersist = c.tunPersist;
    cfg.compressionMode = c.compressionMode ? std::string(c.compressionMode) : std::string("no");
    return cfg;
}

openvpn::ClientAPI::ProvideCreds toCppCreds(const user_credentials &c)
{
    openvpn::ClientAPI::ProvideCreds creds;
    creds.username = c.username ? std::string(c.username) : std::string();
    creds.password = c.password ? std::string(c.password) : std::string();
    return creds;
}

} // namespace

extern "C" {

void *new_session(config cConfig, user_credentials cCreds, callbacks_delegate cb, tun_builder_callbacks tcb)
{
    HavenOpenVPNClient *client = nullptr;
    try {
        client = new HavenOpenVPNClient(cb, tcb);

        auto evalResult = client->eval_config(toCppConfig(cConfig));
        if (evalResult.error) {
            if (cb.logCallback) {
                std::string msg = "eval_config failed: " + evalResult.message;
                cb.logCallback(cb.usrData, mutableCStr(msg));
            }
            delete client;
            return nullptr;
        }

        auto credsStatus = client->provide_creds(toCppCreds(cCreds));
        if (credsStatus.error) {
            if (cb.logCallback) {
                std::string msg = "provide_creds failed: " + credsStatus.message;
                cb.logCallback(cb.usrData, mutableCStr(msg));
            }
            delete client;
            return nullptr;
        }

        return client;
    } catch (const std::exception &e) {
        delete client;
        if (cb.logCallback) {
            std::string msg = std::string("new_session: ") + e.what();
            cb.logCallback(cb.usrData, mutableCStr(msg));
        }
        return nullptr;
    } catch (...) {
        delete client;
        if (cb.logCallback)
            cb.logCallback(cb.usrData, const_cast<char *>(kNewSessionExceptionMsg));
        return nullptr;
    }
}

int start_session(void *ptr)
{
    if (!ptr)
        return 1;
    auto *client = static_cast<HavenOpenVPNClient *>(ptr);
    try {
        auto status = client->connect();
        return status.error ? 1 : 0;
    } catch (...) {
        // Can't safely log here without risking a second exception from a
        // heap-allocating message build during whatever just went wrong;
        // reuse the same failure code connect()'s own Status.error path
        // already uses, so this isn't a new failure mode for the caller.
        return 1;
    }
}

void stop_session(void *ptr)
{
    if (!ptr)
        return;
    try {
        static_cast<HavenOpenVPNClient *>(ptr)->stop();
    } catch (...) {
    }
}

void reconnect_session(void *ptr, int seconds)
{
    if (!ptr)
        return;
    try {
        static_cast<HavenOpenVPNClient *>(ptr)->reconnect(seconds);
    } catch (...) {
    }
}

void cleanup_session(void *ptr)
{
    // Defense in depth only: OpenVPNClient's destructor is implicitly
    // noexcept like any C++ destructor without an explicit specifier, so a
    // real throw here would hit std::terminate before this catch could
    // ever run. The try/catch costs nothing and matches the other 5
    // functions' shape.
    try {
        delete static_cast<HavenOpenVPNClient *>(ptr);
    } catch (...) {
    }
}

void check_library(user_callback_data userData, log_callback logCb)
{
    try {
        openvpn::ClientAPI::OpenVPNClientHelper helper;
        std::string result = helper.crypto_self_test();
        if (logCb)
            logCb(userData, mutableCStr(result));
    } catch (const std::exception &e) {
        if (logCb) {
            std::string msg = std::string("check_library: ") + e.what();
            logCb(userData, mutableCStr(msg));
        }
    } catch (...) {
        if (logCb)
            logCb(userData, const_cast<char *>(kCheckLibraryExceptionMsg));
    }
}

} // extern "C"
