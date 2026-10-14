#ifndef __LIBRARY_H__
#define __LIBRARY_H__

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h> //needed for bools :/
#include "user_data.h"
#include "tunsetup.h"

typedef void(*log_callback)(user_callback_data , char * msg);

typedef struct {
    int bytes_in;
    int bytes_out;
} conn_stats;

typedef void(*stats_callback)(user_callback_data, conn_stats);

//C++ -> C struct remap of openvpn::ClientApi::event
typedef struct {
    bool error;
    bool fatal;
    char *name;
    char *info;
} conn_event;

typedef void(*event_callback)(user_callback_data, conn_event);

typedef struct {
    user_callback_data usrData;
    log_callback logCallback;
    stats_callback statsCallback;
    event_callback eventCallback;
} callbacks_delegate;

typedef struct {
    const char *profileContent;
    const char *guiVersion;
    bool info; // turn on verbose logs
    int clockTickMS; // e.g. 1000 ticks every 1 sec
    bool disableClientCert; // we don't use certs for client identification
    int connTimeout; // connection timeout spend on connect and reconnect overal time
    bool tunPersist;
    const char *compressionMode;
} config;

typedef struct {
    const char * username;
    const char * password;
} user_credentials;

//creates new session - nil on error,
void *new_session(config, user_credentials, callbacks_delegate , tun_builder_callbacks);

//starts created session
int start_session(void *ptr);
//stops running session
void stop_session(void *ptr);
//cleanups session
void cleanup_session(void *ptr);
//reconnect session
void reconnect_session(void *ptr, int seconds);

void check_library(user_callback_data userData, log_callback);

#ifdef __cplusplus
}
#endif

#endif
