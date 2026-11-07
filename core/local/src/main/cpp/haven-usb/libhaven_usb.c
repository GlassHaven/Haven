/*
 * libhaven_usb.so — Haven's guest USB interposition shim.
 *
 * Personalities:
 *  - Slice 3 (HID/hidraw): interposes /dev/hidraw* open/ioctl/read/write/poll.
 *  - Slice 4 (libudev): exports udev_* symbols for Mono DllMap (HidSharp).
 *  - Slice 5 (usbfs): interposes /dev/bus/usb/001/001 enumeration, descriptor
 *    reads, and USBDEVFS_* ioctls (sync control/bulk + async SUBMITURB/REAPURB/
 *    DISCARDURB) so adb, fastboot, scrcpy, and libusb-1.0 tools work inside the
 *    unprivileged PRoot guest over \0haven-usb without root or real device nodes.
 *
 * Built per-ABI with the glibc/musl cross toolchain (see build-haven-usb.sh),
 * _FORTIFY_SOURCE off so it loads under both glibc and musl.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/un.h>
#include <sys/vfs.h>
#include <linux/usbdevice_fs.h>

#ifndef USBDEVFS_GET_SPEED
#define USBDEVFS_GET_SPEED _IO('U', 31)
#endif
#ifndef USBDEVFS_GET_CAPABILITIES
#define USBDEVFS_GET_CAPABILITIES _IOR('U', 26, uint32_t)
#endif
#ifndef USBDEVFS_DISCONNECT_CLAIM
struct usbdevfs_disconnect_claim {
    unsigned int interface;
    unsigned int flags;
    char driver[256];
};
#define USBDEVFS_DISCONNECT_CLAIM _IOR('U', 27, struct usbdevfs_disconnect_claim)
#endif

/* ---- hidraw ioctl ABI (define locally to avoid header skew on musl) ------ */
#define HID_MAX_DESCRIPTOR_SIZE 4096
struct haven_hidraw_report_descriptor { uint32_t size; uint8_t value[HID_MAX_DESCRIPTOR_SIZE]; };
struct haven_hidraw_devinfo { uint32_t bustype; int16_t vendor; int16_t product; };
#define HIDIOCGRDESCSIZE  _IOR('H', 0x01, int)
#define HIDIOCGRDESC      _IOR('H', 0x02, struct haven_hidraw_report_descriptor)
#define HIDIOCGRAWINFO    _IOR('H', 0x03, struct haven_hidraw_devinfo)
#define HIDIOCGRAWNAME(len) _IOC(_IOC_READ, 'H', 0x04, len)
#define HIDIOCGRAWPHYS(len) _IOC(_IOC_READ, 'H', 0x05, len)
#define BUS_USB 0x03

/* ---- proxy protocol (mirror UsbProxyProtocol.kt) ------------------------- */
#define SOCK_NAME "haven-usb"
#define OP_GET_DESCRIPTORS 0x02
#define OP_CONTROL 0x03
#define OP_BULK 0x04
#define OP_CLOSE 0x05
#define USB_DT_REPORT 0x22
#define ERR_PROXY (-9999)
#define MAX_MANAGED 16

#define KIND_HIDRAW 1
#define KIND_USBFS  2

struct urb_node {
    struct usbdevfs_urb *urb;
    int discarded;
    struct urb_node *next;
};

/* Per-open device state.
 * - KIND_HIDRAW: fd is the proxy socket fd.
 * - KIND_USBFS:  fd is an eventfd used for poll() notification, backed by
 *   dedicated control/in/out proxy sockets and async URB worker threads. */
struct managed {
    int fd;                 /* -1 = slot free */
    int kind;               /* KIND_HIDRAW or KIND_USBFS */
    int hid_iface;          /* HID interface number */
    int ep_in, ep_out;      /* interrupt endpoint addresses (0 = none) */
    int16_t vendor, product;
    uint32_t report_desc_len;
    uint8_t report_desc[HID_MAX_DESCRIPTOR_SIZE];

    /* KIND_USBFS fields */
    int sock_ctrl;
    int sock_in;
    int sock_out;
    uint8_t *desc_buf;
    uint32_t desc_len;
    uint32_t desc_pos;
    pthread_mutex_t ctrl_lock;
    pthread_mutex_t urb_lock;
    pthread_cond_t urb_cond;
    struct urb_node *in_head, *in_tail;
    struct urb_node *out_head, *out_tail;
    struct urb_node *done_head, *done_tail;
    struct urb_node *in_flight_in;
    struct urb_node *in_flight_out;
    int closing;
    int threads_started;
    pthread_t thr_in;
    pthread_t thr_out;
};

static struct managed g_managed[MAX_MANAGED];
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

/* ---- real libc symbols --------------------------------------------------- */
static int (*real_open)(const char *, int, ...);
static int (*real_open64)(const char *, int, ...);
static int (*real_openat)(int, const char *, int, ...);
static int (*real_openat64)(int, const char *, int, ...);
static ssize_t (*real_read)(int, void *, size_t);
static ssize_t (*real_write)(int, const void *, size_t);
static off_t (*real_lseek)(int, off_t, int);
static int (*real_close)(int);
static int (*real_ioctl)(int, unsigned long, ...);
static int (*real_poll)(struct pollfd *, nfds_t, int);
static DIR *(*real_opendir)(const char *);
static struct dirent *(*real_readdir)(DIR *);
static struct dirent64 *(*real_readdir64)(DIR *);
static int (*real_closedir)(DIR *);
static int (*real_statfs)(const char *, struct statfs *);
static int (*real_statfs64)(const char *, struct statfs64 *);
static int (*real_fstat)(int, struct stat *);
static int (*real_fstat64)(int, struct stat64 *);
static int (*real_fxstat)(int, int, struct stat *);
static int (*real_fxstat64)(int, int, struct stat64 *);
static ssize_t (*real_readlink)(const char *, char *, size_t);
static int (*real_access)(const char *, int);
static int (*real_inotify_init)(void);
static int (*real_inotify_init1)(int);
static int (*real_inotify_add_watch)(int, const char *, uint32_t);

static int g_debug = 0;
#define DBG(...) do { if (g_debug) { fprintf(stderr, "haven-usb: " __VA_ARGS__); fflush(stderr); } } while (0)

/* Resolve a real libc symbol. RTLD_NEXT works when we're LD_PRELOADed (Slice 3,
 * loaded first). Under Mono DllMap (Slice 4) the shim is dlopen'd late, so
 * RTLD_NEXT can't see libc (it only searches libraries loaded AFTER us) — fall
 * back to an explicit libc handle. RTLD_DEFAULT is avoided: it would resolve
 * back to our own interposer and recurse. */
static void *g_libc = NULL;
static void *resolve_real(const char *name) {
    void *p = dlsym(RTLD_NEXT, name);
    if (p) return p;
    if (!g_libc) {
        g_libc = dlopen("libc.so.6", RTLD_NOW | RTLD_NOLOAD);   /* glibc, already loaded */
        if (!g_libc) g_libc = dlopen("libc.so.6", RTLD_NOW);
        if (!g_libc) g_libc = dlopen("libc.so", RTLD_NOW);      /* musl / fallback */
    }
    return g_libc ? dlsym(g_libc, name) : NULL;
}

static void init_reals(void) {
    static int done = 0;
    if (done) return;
    g_debug = getenv("HAVEN_USB_DEBUG") != NULL;
    /* Free-slot sentinel is -1, but the static table is zero-initialized
     * (fd 0 = a valid fd). Mark every slot free before first use. */
    for (int i = 0; i < MAX_MANAGED; i++) g_managed[i].fd = -1;
    real_open = resolve_real("open");
    real_open64 = resolve_real("open64");
    real_openat = resolve_real("openat");
    real_openat64 = resolve_real("openat64");
    real_read = resolve_real("read");
    real_write = resolve_real("write");
    real_lseek = resolve_real("lseek");
    real_close = resolve_real("close");
    real_ioctl = resolve_real("ioctl");
    real_poll = resolve_real("poll");
    real_opendir = resolve_real("opendir");
    real_readdir = resolve_real("readdir");
    real_readdir64 = resolve_real("readdir64");
    real_closedir = resolve_real("closedir");
    real_statfs = resolve_real("statfs");
    real_statfs64 = resolve_real("statfs64");
    real_fstat = resolve_real("fstat");
    real_fstat64 = resolve_real("fstat64");
    real_fxstat = resolve_real("__fxstat");
    real_fxstat64 = resolve_real("__fxstat64");
    real_readlink = resolve_real("readlink");
    real_access = resolve_real("access");
    real_inotify_init = resolve_real("inotify_init");
    real_inotify_init1 = resolve_real("inotify_init1");
    real_inotify_add_watch = resolve_real("inotify_add_watch");
    done = 1;
}

/* ---- low-level socket framing ------------------------------------------- */
static int io_full(int fd, void *buf, size_t n, int writing) {
    uint8_t *p = buf; size_t done = 0;
    while (done < n) {
        ssize_t r = writing ? real_write(fd, p + done, n - done)
                            : real_read(fd, p + done, n - done);
        if (r <= 0) return -1;
        done += (size_t)r;
    }
    return 0;
}
static uint32_t be32(uint32_t v) {
    return ((v & 0xFF) << 24) | ((v & 0xFF00) << 8) | ((v >> 8) & 0xFF00) | ((v >> 24) & 0xFF);
}

static int proxy_connect(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, SOCK_NAME, sizeof(SOCK_NAME) - 1);
    socklen_t len = offsetof(struct sockaddr_un, sun_path) + 1 + sizeof(SOCK_NAME) - 1;
    if (connect(fd, (struct sockaddr *)&addr, len) < 0) { real_close(fd); return -1; }
    return fd;
}

/* Send a framed request (opcode + payload), read a framed response into *out
 * (caller frees). Returns status (>=0 bytes / <0 device error / ERR_PROXY on
 * transport disconnect), out_len set. */
static int proxy_request(int fd, const uint8_t *body, uint32_t body_len, uint8_t **out, uint32_t *out_len) {
    if (out) { *out = NULL; *out_len = 0; }
    if (fd < 0) return ERR_PROXY;
    uint32_t flen = be32(body_len);
    if (io_full(fd, &flen, 4, 1) < 0) return ERR_PROXY;
    if (io_full(fd, (void *)body, body_len, 1) < 0) return ERR_PROXY;
    uint32_t rlen_be;
    if (io_full(fd, &rlen_be, 4, 0) < 0) return ERR_PROXY;
    uint32_t rlen = be32(rlen_be);
    if (rlen < 4 || rlen > (1u << 20)) return ERR_PROXY;
    uint8_t *resp = malloc(rlen);
    if (!resp) return ERR_PROXY;
    if (io_full(fd, resp, rlen, 0) < 0) { free(resp); return ERR_PROXY; }
    uint32_t st_be; memcpy(&st_be, resp, 4);
    int32_t status = (int32_t)be32(st_be);
    if (out && status > 0 && rlen > 4) {
        *out = malloc(rlen - 4);
        if (*out) {
            memcpy(*out, resp + 4, rlen - 4);
            *out_len = rlen - 4;
        }
    }
    free(resp);
    return status;
}

static int proxy_get_descriptors(int fd, uint8_t **out, uint32_t *out_len) {
    uint8_t op = OP_GET_DESCRIPTORS;
    return proxy_request(fd, &op, 1, out, out_len);
}

static int proxy_control_ex(int fd, int rt, int req, int val, int idx, int len, int timeout_ms,
                            const uint8_t *in_data, int in_len, uint8_t **out, uint32_t *out_len) {
    /* opcode(1) rt(1) req(1) value(2) index(2) length(2) timeout(4) + optional OUT data */
    uint32_t payload_len = (in_data && in_len > 0) ? (uint32_t)in_len : 0;
    uint32_t blen = 13 + payload_len;
    uint8_t *body = malloc(blen);
    if (!body) return ERR_PROXY;
    body[0] = OP_CONTROL;
    body[1] = (uint8_t)rt;
    body[2] = (uint8_t)req;
    body[3] = (uint8_t)(val >> 8); body[4] = (uint8_t)val;        /* u16 BE */
    body[5] = (uint8_t)(idx >> 8); body[6] = (uint8_t)idx;
    body[7] = (uint8_t)(len >> 8); body[8] = (uint8_t)len;
    uint32_t t = be32(timeout_ms > 0 ? (uint32_t)timeout_ms : 1000u);
    memcpy(body + 9, &t, 4);                                      /* u32 BE timeout */
    if (payload_len > 0) memcpy(body + 13, in_data, payload_len);
    int rc = proxy_request(fd, body, blen, out, out_len);
    free(body);
    return rc;
}

static int proxy_control(int fd, int rt, int req, int val, int idx, int len, uint8_t **out, uint32_t *out_len) {
    return proxy_control_ex(fd, rt, req, val, idx, len, 1000, NULL, 0, out, out_len);
}

static int proxy_bulk_ex(int fd, int ep, const uint8_t *data, int data_len, int read_len,
                         int timeout_ms, uint8_t **out, uint32_t *out_len) {
    uint32_t blen = 1 + 1 + 4 + 4 + (uint32_t)(data ? data_len : 0);
    uint8_t *body = malloc(blen);
    if (!body) return ERR_PROXY;
    body[0] = OP_BULK;
    body[1] = (uint8_t)ep;
    uint32_t l = be32((uint32_t)read_len); memcpy(body + 2, &l, 4);
    uint32_t t = be32(timeout_ms > 0 ? (uint32_t)timeout_ms : 1000u);
    memcpy(body + 6, &t, 4);
    if (data && data_len) memcpy(body + 10, data, data_len);
    int rc = proxy_request(fd, body, blen, out, out_len);
    free(body);
    return rc;
}

static int proxy_bulk(int fd, int ep, const uint8_t *data, int data_len, int read_len,
                      uint8_t **out, uint32_t *out_len) {
    return proxy_bulk_ex(fd, ep, data, data_len, read_len, 1000, out, out_len);
}

/* ---- descriptor parsing: find HID interface, endpoints, report-desc len -- */
static void parse_descriptors(struct managed *m, const uint8_t *d, uint32_t n) {
    m->hid_iface = 0; m->ep_in = 0; m->ep_out = 0; m->report_desc_len = 0;
    if (n >= 12) { m->vendor = (int16_t)(d[8] | (d[9] << 8)); m->product = (int16_t)(d[10] | (d[11] << 8)); }
    uint32_t i = 0; int cur_iface = -1, in_hid = 0;
    while (i + 2 <= n) {
        uint8_t blen = d[i], btype = d[i + 1];
        if (blen < 2 || i + blen > n) break;
        if (btype == 0x04) {                 /* INTERFACE */
            cur_iface = d[i + 2];
            in_hid = (d[i + 5] == 0x03);     /* bInterfaceClass == HID */
            if (in_hid) m->hid_iface = cur_iface;
        } else if (btype == 0x21 && in_hid) { /* HID descriptor */
            /* report descriptor length at offset 7..8 (first class desc) */
            if (i + 8 < n) m->report_desc_len = d[i + 7] | (d[i + 8] << 8);
        } else if (btype == 0x05 && in_hid) { /* ENDPOINT on the HID iface */
            uint8_t addr = d[i + 2];
            if (addr & 0x80) { if (!m->ep_in) m->ep_in = addr; }
            else { if (!m->ep_out) m->ep_out = addr; }
        }
        i += blen;
    }
}

static struct managed *find(int fd) {
    for (int i = 0; i < MAX_MANAGED; i++) if (g_managed[i].fd == fd) return &g_managed[i];
    return NULL;
}

static int is_hidraw_path(const char *path) {
    return path && strncmp(path, "/dev/hidraw", 11) == 0;
}

static int is_usbfs_dev_path(const char *path) {
    return path && (!strcmp(path, "/dev/bus/usb/001/001") ||
                    !strcmp(path, "/dev/bus/usb/001//001"));
}

/* Open the proxy, fetch + parse descriptors, fetch the HID report descriptor,
 * and register a managed slot. Returns the socket fd, or -1 (errno set). */
static int open_managed(void) {
    init_reals();
    int fd = proxy_connect();
    if (fd < 0) { errno = ENOENT; return -1; }

    uint8_t *desc = NULL; uint32_t desc_len = 0;
    if (proxy_get_descriptors(fd, &desc, &desc_len) <= 0) { real_close(fd); errno = EIO; return -1; }

    pthread_mutex_lock(&g_lock);
    struct managed *m = find(-1);
    if (!m) { pthread_mutex_unlock(&g_lock); free(desc); real_close(fd); errno = EMFILE; return -1; }
    memset(m, 0, sizeof(*m));
    m->fd = fd;
    m->kind = KIND_HIDRAW;
    parse_descriptors(m, desc, desc_len);
    DBG("descs: len=%u iface=%d ep_in=0x%x ep_out=0x%x vid=%04x pid=%04x report_len=%u\n",
        desc_len, m->hid_iface, m->ep_in, m->ep_out, (uint16_t)m->vendor, (uint16_t)m->product, m->report_desc_len);
    free(desc);

    /* Fetch the HID report descriptor via control GET_DESCRIPTOR(0x22). */
    if (m->report_desc_len > 0 && m->report_desc_len <= HID_MAX_DESCRIPTOR_SIZE) {
        uint8_t *rd = NULL; uint32_t rd_len = 0;
        int want = m->report_desc_len;
        int rc = proxy_control(fd, 0x81, 0x06, (USB_DT_REPORT << 8), m->hid_iface,
                               want, &rd, &rd_len);
        DBG("report ctrl: want=%d rc=%d rd_len=%u first=%02x%02x\n",
            want, rc, rd_len, rd && rd_len > 0 ? rd[0] : 0, rd && rd_len > 1 ? rd[1] : 0);
        if (rc > 0 && rd) {
            uint32_t cp = rd_len < (uint32_t)want ? rd_len : (uint32_t)want;
            memcpy(m->report_desc, rd, cp);
            m->report_desc_len = cp;
        }
        free(rd);
    }
    pthread_mutex_unlock(&g_lock);
    return fd;
}

/* ===================================================================== */
/* libudev fakes (Slice 4) — DllMap'd from libudev.so.0/.1 for Mono apps  */
/* (HidSharp). We synthesise a single hidraw device backed by the proxy:  */
/* enumeration returns it, sysattr returns its VID/PID/strings, and the   */
/* hotplug monitor is a non-crashing fd that never fires. The devnode is  */
/* /dev/hidraw0, which the DllMap'd libc open() above routes to the proxy. */
/* ===================================================================== */

/* Cached device identity, fetched over the proxy. */
static struct {
    int present;           /* a device answered */
    char idVendor[8];      /* "268b" */
    char idProduct[8];     /* "0433" */
    char manufacturer[128];
    char product[128];
    char serial[128];
} g_dev;

/* Decode a USB string descriptor (UTF-16LE after a 2-byte header) into ASCII. */
static void decode_usb_string(const uint8_t *d, uint32_t n, char *out, size_t outsz) {
    out[0] = '\0';
    if (n < 2 || d[1] != 0x03) return;
    size_t j = 0;
    for (uint32_t i = 2; i + 1 < n && j + 1 < outsz; i += 2) {
        uint16_t c = d[i] | (d[i + 1] << 8);
        out[j++] = (c >= 0x20 && c < 0x7f) ? (char)c : '?';
    }
    out[j] = '\0';
}

/* Connect to the proxy, read the device descriptor for VID/PID + string
 * indices, then fetch the string descriptors. Best-effort: strings may stay
 * empty. Sets g_dev.present iff a device answered. Re-probes if not present
 * so a persistent daemon (e.g. adb server) picks up devices attached later. */
static void ensure_dev_info(void) {
    if (g_dev.present) return;
    init_reals();
    int fd = proxy_connect();
    if (fd < 0) { DBG("udev: no proxy/device\n"); return; }

    uint8_t *desc = NULL; uint32_t desc_len = 0;
    if (proxy_get_descriptors(fd, &desc, &desc_len) <= 0 || !desc || desc_len < 18) {
        free(desc); real_close(fd); DBG("udev: GET_DESCRIPTORS failed\n"); return;
    }
    uint16_t vid = desc[8] | (desc[9] << 8);
    uint16_t pid = desc[10] | (desc[11] << 8);
    uint8_t iMan = desc[14], iProd = desc[15], iSer = desc[16];
    free(desc);
    snprintf(g_dev.idVendor, sizeof(g_dev.idVendor), "%04x", vid);
    snprintf(g_dev.idProduct, sizeof(g_dev.idProduct), "%04x", pid);
    g_dev.present = 1;

    struct { uint8_t idx; char *out; size_t sz; } strs[] = {
        { iMan, g_dev.manufacturer, sizeof(g_dev.manufacturer) },
        { iProd, g_dev.product, sizeof(g_dev.product) },
        { iSer, g_dev.serial, sizeof(g_dev.serial) },
    };
    for (size_t k = 0; k < 3; k++) {
        if (strs[k].idx == 0) continue;
        uint8_t *s = NULL; uint32_t sl = 0;
        int rc = proxy_control(fd, 0x80, 0x06, (0x03 << 8) | strs[k].idx, 0x0409, 255, &s, &sl);
        if (rc > 0 && s) decode_usb_string(s, sl, strs[k].out, strs[k].sz);
        free(s);
    }
    DBG("udev: device %s:%s manuf='%s' prod='%s' serial='%s'\n",
        g_dev.idVendor, g_dev.idProduct, g_dev.manufacturer, g_dev.product, g_dev.serial);

    uint8_t op = OP_CLOSE;
    proxy_request(fd, &op, 1, NULL, NULL);
    real_close(fd);
}

/* ===================================================================== */
/* usbfs personality (Slice 5) — /dev/bus/usb/001/001                     */
/* ===================================================================== */

static void urb_queue_push(struct urb_node **head, struct urb_node **tail, struct urb_node *u) {
    u->next = NULL;
    if (*tail) (*tail)->next = u;
    else *head = u;
    *tail = u;
}

static struct urb_node *urb_queue_pop(struct urb_node **head, struct urb_node **tail) {
    struct urb_node *u = *head;
    if (!u) return NULL;
    *head = u->next;
    if (!*head) *tail = NULL;
    u->next = NULL;
    return u;
}

static void notify_urb_done(struct managed *m, struct urb_node *u) {
    urb_queue_push(&m->done_head, &m->done_tail, u);
    uint64_t one = 1;
    if (m->fd >= 0) (void)real_write(m->fd, &one, sizeof(one));
    pthread_cond_broadcast(&m->urb_cond);
}

/* Execute one control URB (urb->buffer starts with 8-byte setup packet). */
static void exec_control_urb(struct managed *m, struct urb_node *u) {
    int rt = 0, req = 0, val = 0, idx = 0, wlen = 0, dlen = 0;
    uint8_t *in_copy = NULL;

    pthread_mutex_lock(&m->urb_lock);
    if (u->discarded || !u->urb || u->urb->buffer_length < 8 || !u->urb->buffer) {
        if (!u->discarded && u->urb) {
            u->urb->status = -EINVAL;
            u->urb->actual_length = 0;
            notify_urb_done(m, u);
        } else {
            free(u);
        }
        pthread_mutex_unlock(&m->urb_lock);
        return;
    }
    const uint8_t *setup = (const uint8_t *)u->urb->buffer;
    rt = setup[0];
    req = setup[1];
    val = setup[2] | (setup[3] << 8);
    idx = setup[4] | (setup[5] << 8);
    wlen = setup[6] | (setup[7] << 8);
    int avail = u->urb->buffer_length - 8;
    dlen = avail < wlen ? avail : wlen;
    if (!(rt & 0x80) && dlen > 0) {
        in_copy = malloc((size_t)dlen);
        if (in_copy) memcpy(in_copy, setup + 8, (size_t)dlen);
    }
    pthread_mutex_unlock(&m->urb_lock);

    uint8_t *out = NULL; uint32_t out_len = 0;
    pthread_mutex_lock(&m->ctrl_lock);
    int rc = proxy_control_ex(m->sock_ctrl, rt, req, val, idx, wlen, 1000,
                              in_copy, dlen, &out, &out_len);
    pthread_mutex_unlock(&m->ctrl_lock);
    free(in_copy);

    /* Standard SET_CONFIGURATION (9), SET_INTERFACE (11), CLEAR_FEATURE (1)
     * may be rejected by Android's host stack because UsbBroker claims
     * interfaces lazily; treat as success so libusb callers proceed. */
    if (rc < 0 && rc != ERR_PROXY && (rt & 0x60) == 0 &&
        (req == 0x09 || req == 0x0b || req == 0x01)) {
        rc = 0;
    }

    pthread_mutex_lock(&m->urb_lock);
    if (u->discarded) {
        free(out);
        free(u);
        pthread_mutex_unlock(&m->urb_lock);
        return;
    }
    if (rc < 0) {
        u->urb->status = (rc == ERR_PROXY) ? -ENODEV : -EPIPE;
        u->urb->actual_length = 0;
    } else {
        if ((rt & 0x80) && out && out_len > 0 && u->urb->buffer_length > 8) {
            uint32_t max_cp = (uint32_t)(u->urb->buffer_length - 8);
            uint32_t cp = out_len < max_cp ? out_len : max_cp;
            memcpy((uint8_t *)u->urb->buffer + 8, out, cp);
            u->urb->actual_length = (int)cp;
        } else {
            u->urb->actual_length = rc;
        }
        u->urb->status = 0;
    }
    free(out);
    notify_urb_done(m, u);
    pthread_mutex_unlock(&m->urb_lock);
}

static void *usbfs_in_worker(void *arg) {
    struct managed *m = (struct managed *)arg;
    for (;;) {
        pthread_mutex_lock(&m->urb_lock);
        while (!m->closing && !m->in_head) {
            pthread_cond_wait(&m->urb_cond, &m->urb_lock);
        }
        if (m->closing) {
            pthread_mutex_unlock(&m->urb_lock);
            break;
        }
        struct urb_node *u = urb_queue_pop(&m->in_head, &m->in_tail);
        if (!u) {
            pthread_mutex_unlock(&m->urb_lock);
            continue;
        }
        m->in_flight_in = u;
        unsigned char type = u->urb->type;
        int ep = u->urb->endpoint;
        int blen = u->urb->buffer_length;
        pthread_mutex_unlock(&m->urb_lock);

        if (type == USBDEVFS_URB_TYPE_CONTROL) {
            exec_control_urb(m, u);
            pthread_mutex_lock(&m->urb_lock);
            m->in_flight_in = NULL;
            pthread_mutex_unlock(&m->urb_lock);
            continue;
        }

        uint8_t *out = NULL; uint32_t out_len = 0;
        int rc = -1;
        while (1) {
            pthread_mutex_lock(&m->urb_lock);
            int stop = m->closing || u->discarded;
            pthread_mutex_unlock(&m->urb_lock);
            if (stop) break;

            rc = proxy_bulk_ex(m->sock_in, ep, NULL, 0, blen, 100, &out, &out_len);
            if (rc == ERR_PROXY || rc >= 0) break;
            free(out); out = NULL; out_len = 0;
        }

        pthread_mutex_lock(&m->urb_lock);
        m->in_flight_in = NULL;
        if (u->discarded) {
            free(out);
            free(u);
            pthread_mutex_unlock(&m->urb_lock);
            continue;
        }
        if (m->closing || rc == ERR_PROXY) {
            u->urb->status = -ENODEV;
            u->urb->actual_length = 0;
            g_dev.present = 0;
        } else if (rc >= 0) {
            uint32_t cp = (blen > 0 && out_len > (uint32_t)blen) ? (uint32_t)blen : out_len;
            if (out && cp > 0 && u->urb->buffer) memcpy(u->urb->buffer, out, cp);
            u->urb->actual_length = (int)cp;
            u->urb->status = 0;
        } else {
            u->urb->status = -EIO;
            u->urb->actual_length = 0;
        }
        free(out);
        notify_urb_done(m, u);
        pthread_mutex_unlock(&m->urb_lock);
    }
    return NULL;
}

static void *usbfs_out_worker(void *arg) {
    struct managed *m = (struct managed *)arg;
    for (;;) {
        pthread_mutex_lock(&m->urb_lock);
        while (!m->closing && !m->out_head) {
            pthread_cond_wait(&m->urb_cond, &m->urb_lock);
        }
        if (m->closing) {
            pthread_mutex_unlock(&m->urb_lock);
            break;
        }
        struct urb_node *u = urb_queue_pop(&m->out_head, &m->out_tail);
        if (!u) {
            pthread_mutex_unlock(&m->urb_lock);
            continue;
        }
        m->in_flight_out = u;
        unsigned char type = u->urb->type;
        int ep = u->urb->endpoint;
        int blen = u->urb->buffer_length;
        uint8_t *payload = NULL;
        if (type != USBDEVFS_URB_TYPE_CONTROL && blen > 0 && u->urb->buffer) {
            payload = malloc((size_t)blen);
            if (payload) memcpy(payload, u->urb->buffer, (size_t)blen);
        }
        pthread_mutex_unlock(&m->urb_lock);

        if (type == USBDEVFS_URB_TYPE_CONTROL) {
            exec_control_urb(m, u);
            pthread_mutex_lock(&m->urb_lock);
            m->in_flight_out = NULL;
            pthread_mutex_unlock(&m->urb_lock);
            continue;
        }

        int rc = proxy_bulk_ex(m->sock_out, ep, payload, blen, 0, 5000, NULL, NULL);
        free(payload);

        pthread_mutex_lock(&m->urb_lock);
        m->in_flight_out = NULL;
        if (u->discarded) {
            free(u);
            pthread_mutex_unlock(&m->urb_lock);
            continue;
        }
        if (rc < 0) {
            u->urb->status = (rc == ERR_PROXY) ? -ENODEV : -EIO;
            u->urb->actual_length = 0;
            if (rc == ERR_PROXY) g_dev.present = 0;
        } else {
            u->urb->actual_length = rc;
            u->urb->status = 0;
        }
        notify_urb_done(m, u);
        pthread_mutex_unlock(&m->urb_lock);
    }
    return NULL;
}

static int open_usbfs(void) {
    init_reals();
    int s_ctrl = proxy_connect();
    if (s_ctrl < 0) { g_dev.present = 0; errno = ENOENT; return -1; }

    uint8_t *desc = NULL; uint32_t desc_len = 0;
    if (proxy_get_descriptors(s_ctrl, &desc, &desc_len) <= 0 || !desc) {
        free(desc); real_close(s_ctrl); g_dev.present = 0; errno = EIO; return -1;
    }

    int s_in = proxy_connect();
    int s_out = proxy_connect();
    int evfd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (s_in < 0 || s_out < 0 || evfd < 0) {
        free(desc);
        real_close(s_ctrl);
        if (s_in >= 0) real_close(s_in);
        if (s_out >= 0) real_close(s_out);
        if (evfd >= 0) real_close(evfd);
        errno = EIO;
        return -1;
    }

    pthread_mutex_lock(&g_lock);
    struct managed *m = find(-1);
    if (!m) {
        pthread_mutex_unlock(&g_lock);
        free(desc);
        real_close(s_ctrl); real_close(s_in); real_close(s_out); real_close(evfd);
        errno = EMFILE;
        return -1;
    }
    memset(m, 0, sizeof(*m));
    m->fd = evfd;
    m->kind = KIND_USBFS;
    m->sock_ctrl = s_ctrl;
    m->sock_in = s_in;
    m->sock_out = s_out;
    m->desc_buf = desc;
    m->desc_len = desc_len;
    m->desc_pos = 0;
    parse_descriptors(m, desc, desc_len);
    pthread_mutex_init(&m->ctrl_lock, NULL);
    pthread_mutex_init(&m->urb_lock, NULL);
    pthread_cond_init(&m->urb_cond, NULL);
    if (pthread_create(&m->thr_in, NULL, usbfs_in_worker, m) == 0 &&
        pthread_create(&m->thr_out, NULL, usbfs_out_worker, m) == 0) {
        m->threads_started = 1;
    }
    DBG("usbfs open: fd=%d desc_len=%u vid=%04x pid=%04x\n",
        evfd, desc_len, (uint16_t)m->vendor, (uint16_t)m->product);
    pthread_mutex_unlock(&g_lock);
    return evfd;
}

/* Fake DIR streams for /dev/bus/usb and /dev/bus/usb/001 */
struct fake_dir {
    int in_use;
    int kind; /* 1 = /dev/bus/usb, 2 = /dev/bus/usb/001 */
    int step;
    struct dirent de;
    struct dirent64 de64;
};
static struct fake_dir g_dirs[8];

static int match_usbfs_dir(const char *name) {
    if (!name) return 0;
    if (!strcmp(name, "/dev/bus/usb") || !strcmp(name, "/dev/bus/usb/")) return 1;
    if (!strcmp(name, "/dev/bus/usb/001") || !strcmp(name, "/dev/bus/usb/001/")) return 2;
    return 0;
}

static struct fake_dir *find_fake_dir(DIR *dirp) {
    for (int i = 0; i < 8; i++) {
        if (g_dirs[i].in_use && (DIR *)&g_dirs[i] == dirp) return &g_dirs[i];
    }
    return NULL;
}

static const char *next_fake_dir_entry(struct fake_dir *d, unsigned char *dtype, ino_t *ino) {
    int idx = d->step++;
    if (idx == 0) { *dtype = DT_DIR; *ino = 1; return "."; }
    if (idx == 1) { *dtype = DT_DIR; *ino = 1; return ".."; }
    if (idx == 2) {
        if (d->kind == 1) { *dtype = DT_DIR; *ino = 2; return "001"; }
        ensure_dev_info();
        if (d->kind == 2 && g_dev.present) { *dtype = DT_CHR; *ino = 3; return "001"; }
    }
    return NULL;
}

DIR *opendir(const char *name) {
    init_reals();
    int kind = match_usbfs_dir(name);
    if (kind) {
        pthread_mutex_lock(&g_lock);
        for (int i = 0; i < 8; i++) {
            if (!g_dirs[i].in_use) {
                memset(&g_dirs[i], 0, sizeof(g_dirs[i]));
                g_dirs[i].in_use = 1;
                g_dirs[i].kind = kind;
                g_dirs[i].step = 0;
                pthread_mutex_unlock(&g_lock);
                return (DIR *)&g_dirs[i];
            }
        }
        pthread_mutex_unlock(&g_lock);
    }
    return real_opendir ? real_opendir(name) : NULL;
}

struct dirent *readdir(DIR *dirp) {
    init_reals();
    pthread_mutex_lock(&g_lock);
    struct fake_dir *d = find_fake_dir(dirp);
    if (d) {
        unsigned char dtype = 0; ino_t ino = 0;
        const char *name = next_fake_dir_entry(d, &dtype, &ino);
        if (!name) { pthread_mutex_unlock(&g_lock); return NULL; }
        memset(&d->de, 0, sizeof(d->de));
        d->de.d_ino = ino;
        d->de.d_type = dtype;
        d->de.d_reclen = sizeof(d->de);
        snprintf(d->de.d_name, sizeof(d->de.d_name), "%s", name);
        pthread_mutex_unlock(&g_lock);
        return &d->de;
    }
    pthread_mutex_unlock(&g_lock);
    return real_readdir ? real_readdir(dirp) : NULL;
}

struct dirent64 *readdir64(DIR *dirp) {
    init_reals();
    pthread_mutex_lock(&g_lock);
    struct fake_dir *d = find_fake_dir(dirp);
    if (d) {
        unsigned char dtype = 0; ino_t ino = 0;
        const char *name = next_fake_dir_entry(d, &dtype, &ino);
        if (!name) { pthread_mutex_unlock(&g_lock); return NULL; }
        memset(&d->de64, 0, sizeof(d->de64));
        d->de64.d_ino = ino;
        d->de64.d_type = dtype;
        d->de64.d_reclen = sizeof(d->de64);
        snprintf(d->de64.d_name, sizeof(d->de64.d_name), "%s", name);
        pthread_mutex_unlock(&g_lock);
        return &d->de64;
    }
    pthread_mutex_unlock(&g_lock);
    return real_readdir64 ? real_readdir64(dirp) : (struct dirent64 *)(real_readdir ? real_readdir(dirp) : NULL);
}

int closedir(DIR *dirp) {
    init_reals();
    pthread_mutex_lock(&g_lock);
    struct fake_dir *d = find_fake_dir(dirp);
    if (d) {
        d->in_use = 0;
        pthread_mutex_unlock(&g_lock);
        return 0;
    }
    pthread_mutex_unlock(&g_lock);
    return real_closedir ? real_closedir(dirp) : -1;
}

int statfs(const char *path, struct statfs *buf) {
    init_reals();
    if (strcmp(path, "/sys") == 0 || strncmp(path, "/sys/bus/usb", 12) == 0) {
        errno = ENOENT;
        return -1;
    }
    return real_statfs ? real_statfs(path, buf) : -1;
}

int statfs64(const char *path, struct statfs64 *buf) {
    init_reals();
    if (strcmp(path, "/sys") == 0 || strncmp(path, "/sys/bus/usb", 12) == 0) {
        errno = ENOENT;
        return -1;
    }
    return real_statfs64 ? real_statfs64(path, buf) : (real_statfs ? real_statfs(path, (struct statfs *)buf) : -1);
}

static int fill_usbfs_fstat(int fd, struct stat *st) {
    pthread_mutex_lock(&g_lock);
    struct managed *m = find(fd);
    if (!m || m->kind != KIND_USBFS) {
        pthread_mutex_unlock(&g_lock);
        return 0;
    }
    memset(st, 0, sizeof(*st));
    st->st_mode = S_IFCHR | 0666;
    st->st_rdev = makedev(189, 0);
    st->st_nlink = 1;
    st->st_size = (off_t)m->desc_len;
    pthread_mutex_unlock(&g_lock);
    return 1;
}

static int fill_usbfs_fstat64(int fd, struct stat64 *st) {
    pthread_mutex_lock(&g_lock);
    struct managed *m = find(fd);
    if (!m || m->kind != KIND_USBFS) {
        pthread_mutex_unlock(&g_lock);
        return 0;
    }
    memset(st, 0, sizeof(*st));
    st->st_mode = S_IFCHR | 0666;
    st->st_rdev = makedev(189, 0);
    st->st_nlink = 1;
    st->st_size = (off_t)m->desc_len;
    pthread_mutex_unlock(&g_lock);
    return 1;
}

int fstat(int fd, struct stat *st) {
    init_reals();
    if (fill_usbfs_fstat(fd, st)) return 0;
    if (real_fstat) return real_fstat(fd, st);
    if (real_fxstat) return real_fxstat(1, fd, st);
    errno = ENOSYS;
    return -1;
}

int fstat64(int fd, struct stat64 *st) {
    init_reals();
    if (fill_usbfs_fstat64(fd, st)) return 0;
    if (real_fstat64) return real_fstat64(fd, st);
    if (real_fxstat64) return real_fxstat64(1, fd, st);
    errno = ENOSYS;
    return -1;
}

int __fxstat(int ver, int fd, struct stat *st) {
    init_reals();
    if (fill_usbfs_fstat(fd, st)) return 0;
    if (real_fxstat) return real_fxstat(ver, fd, st);
    if (real_fstat) return real_fstat(fd, st);
    errno = ENOSYS;
    return -1;
}

int __fxstat64(int ver, int fd, struct stat64 *st) {
    init_reals();
    if (fill_usbfs_fstat64(fd, st)) return 0;
    if (real_fxstat64) return real_fxstat64(ver, fd, st);
    if (real_fstat64) return real_fstat64(fd, st);
    errno = ENOSYS;
    return -1;
}

ssize_t readlink(const char *path, char *buf, size_t bufsiz) {
    init_reals();
    if (strcmp(path, "/sys/dev/char/189:0") == 0) {
        const char *target = "../../devices/usb1/1-1";
        size_t len = strlen(target);
        if (len > bufsiz) len = bufsiz;
        memcpy(buf, target, len);
        return (ssize_t)len;
    }
    return real_readlink ? real_readlink(path, buf, bufsiz) : -1;
}

int access(const char *pathname, int mode) {
    init_reals();
    if (match_usbfs_dir(pathname)) return 0;
    if (is_usbfs_dev_path(pathname)) {
        ensure_dev_info();
        if (g_dev.present) return 0;
        errno = ENOENT;
        return -1;
    }
    return real_access ? real_access(pathname, mode) : -1;
}

int inotify_init(void) {
    init_reals();
    int fd = real_inotify_init ? real_inotify_init() : -1;
    if (fd < 0) fd = eventfd(0, EFD_CLOEXEC);
    return fd;
}

int inotify_init1(int flags) {
    init_reals();
    int fd = real_inotify_init1 ? real_inotify_init1(flags) : -1;
    if (fd < 0) fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    return fd;
}

int inotify_add_watch(int fd, const char *pathname, uint32_t mask) {
    init_reals();
    if (strncmp(pathname, "/dev/bus/usb", 12) == 0) return 1;
    int wd = real_inotify_add_watch ? real_inotify_add_watch(fd, pathname, mask) : -1;
    if (wd < 0 && errno == EINVAL) return 1; /* fallback eventfd from inotify_init1 */
    return wd;
}

/* Opaque handles — distinct non-null sentinels HidSharp passes back to us. */
static int g_udev_obj, g_enum_obj, g_hid_dev, g_usb_dev, g_list_entry, g_monitor;
static int g_monitor_fd = -1;

void *udev_new(void) { ensure_dev_info(); return &g_udev_obj; }
void *udev_ref(void *u) { return u; }
void *udev_unref(void *u) { (void)u; return NULL; }

void *udev_enumerate_new(void *u) { (void)u; return &g_enum_obj; }
void *udev_enumerate_ref(void *e) { return e; }
void *udev_enumerate_unref(void *e) { (void)e; return NULL; }
int udev_enumerate_add_match_subsystem(void *e, const char *s) { (void)e; (void)s; return 0; }
int udev_enumerate_add_match_parent(void *e, void *p) { (void)e; (void)p; return 0; }
int udev_enumerate_scan_devices(void *e) { (void)e; return 0; }
void *udev_enumerate_get_list_entry(void *e) {
    (void)e; ensure_dev_info();
    return g_dev.present ? &g_list_entry : NULL;   /* exactly one device, or none */
}
void *udev_list_entry_get_next(void *le) { (void)le; return NULL; }   /* single entry */
const char *udev_list_entry_get_name(void *le) { (void)le; return "/sys/class/hidraw/hidraw0"; }

void *udev_device_new_from_syspath(void *u, const char *p) { (void)u; (void)p; return &g_hid_dev; }
void *udev_device_new_from_subsystem_sysname(void *u, const char *s, const char *n) {
    (void)u; (void)s; (void)n; return &g_hid_dev;
}
void *udev_device_ref(void *d) { return d; }
void *udev_device_unref(void *d) { (void)d; return NULL; }
const char *udev_device_get_devnode(void *d) { (void)d; return "/dev/hidraw0"; }
const char *udev_device_get_syspath(void *d) { (void)d; return "/sys/class/hidraw/hidraw0"; }
int udev_device_get_is_initialized(void *d) { (void)d; return 1; }
void *udev_device_get_parent(void *d) { (void)d; return &g_usb_dev; }
void *udev_device_get_parent_with_subsystem_devtype(void *d, const char *s, const char *t) {
    (void)d; (void)s; (void)t; return &g_usb_dev;
}
const char *udev_device_get_sysattr_value(void *d, const char *attr) {
    (void)d; ensure_dev_info();
    if (!attr) return NULL;
    if (!strcmp(attr, "idVendor")) return g_dev.idVendor[0] ? g_dev.idVendor : NULL;
    if (!strcmp(attr, "idProduct")) return g_dev.idProduct[0] ? g_dev.idProduct : NULL;
    if (!strcmp(attr, "manufacturer")) return g_dev.manufacturer[0] ? g_dev.manufacturer : NULL;
    if (!strcmp(attr, "product")) return g_dev.product[0] ? g_dev.product : NULL;
    if (!strcmp(attr, "serial")) return g_dev.serial[0] ? g_dev.serial : NULL;
    return NULL;
}

void *udev_monitor_new_from_netlink(void *u, const char *name) {
    (void)u; (void)name;
    /* A real, never-readable fd so HidSharp's monitor thread blocks harmlessly
     * instead of throwing (the udev_monitor_new_from_netlink failure that
     * killed EScribe's GUI). eventfd with no writes never polls readable. */
    if (g_monitor_fd < 0) g_monitor_fd = eventfd(0, EFD_CLOEXEC);
    return &g_monitor;
}
int udev_monitor_filter_add_match_subsystem_devtype(void *m, const char *s, const char *t) {
    (void)m; (void)s; (void)t; return 0;
}
int udev_monitor_enable_receiving(void *m) { (void)m; return 0; }
int udev_monitor_get_fd(void *m) { (void)m; return g_monitor_fd; }
void *udev_monitor_receive_device(void *m) { (void)m; return NULL; }
void *udev_monitor_unref(void *m) { (void)m; return NULL; }

/* ---- interposers --------------------------------------------------------- */
static int open_common(const char *path) {
    if (is_hidraw_path(path)) {
        return open_managed(); /* errno already set on failure */
    }
    if (is_usbfs_dev_path(path)) {
        return open_usbfs();
    }
    if (path && strcmp(path, "/sys/bus/usb/devices/1-1/serial") == 0) {
        ensure_dev_info();
        if (!g_dev.present) { errno = ENOENT; return -1; }
        int pfd[2];
        if (pipe(pfd) < 0) return -1;
        char buf[140];
        int n = snprintf(buf, sizeof(buf), "%s\n", g_dev.serial[0] ? g_dev.serial : "haven-usb");
        if (n > 0) (void)real_write(pfd[1], buf, (size_t)n);
        real_close(pfd[1]);
        return pfd[0];
    }
    return -2; /* sentinel: not ours, fall through */
}

int open(const char *path, int flags, ...) {
    init_reals();
    int r = open_common(path);
    if (r != -2) return r;
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return real_open(path, flags, mode);
}
int open64(const char *path, int flags, ...) {
    init_reals();
    int r = open_common(path);
    if (r != -2) return r;
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return real_open64 ? real_open64(path, flags, mode) : real_open(path, flags, mode);
}
int openat(int dirfd, const char *path, int flags, ...) {
    init_reals();
    int r = open_common(path);
    if (r != -2) return r;
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return real_openat(dirfd, path, flags, mode);
}
int openat64(int dirfd, const char *path, int flags, ...) {
    init_reals();
    int r = open_common(path);
    if (r != -2) return r;
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return real_openat64 ? real_openat64(dirfd, path, flags, mode) : real_openat(dirfd, path, flags, mode);
}

static int handle_usbfs_ioctl(struct managed *m, unsigned long request, void *arg) {
    if (request == (unsigned long)USBDEVFS_GET_CAPABILITIES) {
        /* ZERO_PACKET(0x01) | BULK_CONTINUATION(0x02) | NO_PACKET_SIZE_LIM(0x04) |
         * BULK_SCATTER_GATHER(0x08) | REAP_AFTER_DISCONNECT(0x10) */
        if (arg) *(uint32_t *)arg = 0x1fu;
        return 0;
    }
    if (request == (unsigned long)USBDEVFS_GET_SPEED) {
        return 3; /* USB_SPEED_HIGH (480 Mbps) */
    }
    if (request == (unsigned long)USBDEVFS_CLAIMINTERFACE ||
        request == (unsigned long)USBDEVFS_RELEASEINTERFACE ||
        request == (unsigned long)USBDEVFS_DISCONNECT_CLAIM ||
        request == (unsigned long)USBDEVFS_SETINTERFACE ||
        request == (unsigned long)USBDEVFS_SETCONFIGURATION ||
        request == (unsigned long)USBDEVFS_CLEAR_HALT ||
        request == (unsigned long)USBDEVFS_RESET ||
        request == (unsigned long)USBDEVFS_RESETEP ||
        request == (unsigned long)USBDEVFS_IOCTL) {
        return 0;
    }
    if (request == (unsigned long)USBDEVFS_CONTROL) {
        struct usbdevfs_ctrltransfer *ct = (struct usbdevfs_ctrltransfer *)arg;
        if (!ct) { errno = EINVAL; return -1; }
        /* Synthesise GET_CONFIGURATION (0x80, 0x08) from cached active config
         * descriptor so libusb's usbfs_get_active_config always sees a
         * configured device without risking a stall. */
        if (ct->bRequestType == 0x80 && ct->bRequest == 0x08 && ct->wLength >= 1 && ct->data) {
            uint8_t cfg = (m->desc_buf && m->desc_len >= 24 && m->desc_buf[23]) ? m->desc_buf[23] : 1;
            *(uint8_t *)ct->data = cfg;
            return 1;
        }
        int is_in = (ct->bRequestType & 0x80) != 0;
        uint8_t *out = NULL; uint32_t out_len = 0;
        pthread_mutex_lock(&m->ctrl_lock);
        int rc = proxy_control_ex(m->sock_ctrl, ct->bRequestType, ct->bRequest,
                                  ct->wValue, ct->wIndex, ct->wLength, (int)ct->timeout,
                                  is_in ? NULL : (const uint8_t *)ct->data,
                                  is_in ? 0 : (int)ct->wLength, &out, &out_len);
        pthread_mutex_unlock(&m->ctrl_lock);
        if (rc < 0) { free(out); errno = (rc == ERR_PROXY) ? ENODEV : EPIPE; return -1; }
        if (is_in && out && ct->data) {
            uint32_t cp = out_len < ct->wLength ? out_len : ct->wLength;
            memcpy(ct->data, out, cp);
            free(out);
            return (int)cp;
        }
        free(out);
        return rc;
    }
    if (request == (unsigned long)USBDEVFS_BULK) {
        struct usbdevfs_bulktransfer *bt = (struct usbdevfs_bulktransfer *)arg;
        if (!bt) { errno = EINVAL; return -1; }
        int is_in = (bt->ep & 0x80) != 0;
        uint8_t *out = NULL; uint32_t out_len = 0;
        pthread_mutex_lock(&m->ctrl_lock);
        int rc = proxy_bulk_ex(m->sock_ctrl, (int)bt->ep,
                               is_in ? NULL : (const uint8_t *)bt->data,
                               is_in ? 0 : (int)bt->len,
                               is_in ? (int)bt->len : 0,
                               (int)bt->timeout, &out, &out_len);
        pthread_mutex_unlock(&m->ctrl_lock);
        if (rc < 0) { free(out); errno = (rc == ERR_PROXY) ? ENODEV : EIO; return -1; }
        if (is_in && out && bt->data) {
            uint32_t cp = out_len < bt->len ? out_len : bt->len;
            memcpy(bt->data, out, cp);
            free(out);
            return (int)cp;
        }
        free(out);
        return rc;
    }
    if (request == (unsigned long)USBDEVFS_SUBMITURB) {
        struct usbdevfs_urb *urb = (struct usbdevfs_urb *)arg;
        if (!urb) { errno = EINVAL; return -1; }
        struct urb_node *u = calloc(1, sizeof(*u));
        if (!u) { errno = ENOMEM; return -1; }
        u->urb = urb;
        urb->status = -EINPROGRESS;
        urb->actual_length = 0;
        urb->error_count = 0;
        pthread_mutex_lock(&m->urb_lock);
        if (urb->type == USBDEVFS_URB_TYPE_CONTROL || (urb->endpoint & 0x80) == 0) {
            urb_queue_push(&m->out_head, &m->out_tail, u);
        } else {
            urb_queue_push(&m->in_head, &m->in_tail, u);
        }
        pthread_cond_broadcast(&m->urb_cond);
        pthread_mutex_unlock(&m->urb_lock);
        return 0;
    }
    if (request == (unsigned long)USBDEVFS_DISCARDURB) {
        struct usbdevfs_urb *urb = (struct usbdevfs_urb *)arg;
        if (!urb) { errno = EINVAL; return -1; }
        pthread_mutex_lock(&m->urb_lock);
        /* Search pending IN and OUT queues first. */
        struct urb_node **lists[2][2] = {
            { &m->in_head, &m->in_tail },
            { &m->out_head, &m->out_tail },
        };
        for (int q = 0; q < 2; q++) {
            struct urb_node *prev = NULL, *cur = *lists[q][0];
            while (cur) {
                if (cur->urb == urb) {
                    if (prev) prev->next = cur->next;
                    else *lists[q][0] = cur->next;
                    if (*lists[q][1] == cur) *lists[q][1] = prev;
                    cur->urb->status = -ENOENT;
                    cur->urb->actual_length = 0;
                    notify_urb_done(m, cur);
                    pthread_mutex_unlock(&m->urb_lock);
                    return 0;
                }
                prev = cur;
                cur = cur->next;
            }
        }
        /* Check in-flight URBs on worker threads. */
        struct urb_node *inflight[2] = { m->in_flight_in, m->in_flight_out };
        for (int q = 0; q < 2; q++) {
            struct urb_node *u = inflight[q];
            if (u && u->urb == urb && !u->discarded) {
                u->discarded = 1;
                urb->status = -ENOENT;
                urb->actual_length = 0;
                struct urb_node *done_node = calloc(1, sizeof(*done_node));
                if (done_node) {
                    done_node->urb = urb;
                    notify_urb_done(m, done_node);
                }
                pthread_mutex_unlock(&m->urb_lock);
                return 0;
            }
        }
        pthread_mutex_unlock(&m->urb_lock);
        errno = EINVAL;
        return -1;
    }
    if (request == (unsigned long)USBDEVFS_REAPURB ||
        request == (unsigned long)USBDEVFS_REAPURBNDELAY) {
        int block = (request == (unsigned long)USBDEVFS_REAPURB);
        pthread_mutex_lock(&m->urb_lock);
        while (!m->done_head && block && !m->closing) {
            pthread_cond_wait(&m->urb_cond, &m->urb_lock);
        }
        struct urb_node *u = urb_queue_pop(&m->done_head, &m->done_tail);
        if (!m->done_head && m->fd >= 0) {
            uint64_t val;
            while (real_read(m->fd, &val, sizeof(val)) > 0) {}
        }
        pthread_mutex_unlock(&m->urb_lock);
        if (!u) {
            errno = m->closing ? ENODEV : EAGAIN;
            return -1;
        }
        if (arg) *(void **)arg = u->urb;
        free(u);
        return 0;
    }
    errno = ENOTTY;
    return -1;
}

int ioctl(int fd, unsigned long request, ...) {
    init_reals();
    va_list ap; va_start(ap, request); void *arg = va_arg(ap, void *); va_end(ap);
    pthread_mutex_lock(&g_lock);
    struct managed *m = find(fd);
    if (!m) { pthread_mutex_unlock(&g_lock); return real_ioctl(fd, request, arg); }
    if (m->kind == KIND_USBFS) {
        pthread_mutex_unlock(&g_lock);
        return handle_usbfs_ioctl(m, request, arg);
    }

    int ret = 0;
    if (request == (unsigned long)HIDIOCGRDESCSIZE) {
        *(int *)arg = (int)m->report_desc_len;
    } else if (request == (unsigned long)HIDIOCGRDESC) {
        struct haven_hidraw_report_descriptor *r = arg;
        uint32_t cp = r->size < m->report_desc_len ? r->size : m->report_desc_len;
        if (cp == 0) cp = m->report_desc_len;
        memcpy(r->value, m->report_desc, cp);
        r->size = cp;
    } else if (request == (unsigned long)HIDIOCGRAWINFO) {
        struct haven_hidraw_devinfo *info = arg;
        info->bustype = BUS_USB; info->vendor = m->vendor; info->product = m->product;
    } else if (_IOC_NR(request) == 0x04) { /* HIDIOCGRAWNAME(len) */
        snprintf((char *)arg, _IOC_SIZE(request), "Haven USB %04x:%04x",
                 (uint16_t)m->vendor, (uint16_t)m->product);
        ret = (int)strlen((char *)arg);
    } else if (_IOC_NR(request) == 0x05) { /* HIDIOCGRAWPHYS(len) */
        snprintf((char *)arg, _IOC_SIZE(request), "haven-usb");
        ret = (int)strlen((char *)arg);
    } else {
        errno = ENOTTY; ret = -1;
    }
    pthread_mutex_unlock(&g_lock);
    return ret;
}

off_t lseek(int fd, off_t offset, int whence) {
    init_reals();
    pthread_mutex_lock(&g_lock);
    struct managed *m = find(fd);
    if (m && m->kind == KIND_USBFS) {
        off_t next = 0;
        if (whence == SEEK_SET) next = offset;
        else if (whence == SEEK_CUR) next = (off_t)m->desc_pos + offset;
        else if (whence == SEEK_END) next = (off_t)m->desc_len + offset;
        else { pthread_mutex_unlock(&g_lock); errno = EINVAL; return -1; }
        if (next < 0) next = 0;
        if ((uint32_t)next > m->desc_len) next = (off_t)m->desc_len;
        m->desc_pos = (uint32_t)next;
        pthread_mutex_unlock(&g_lock);
        return next;
    }
    pthread_mutex_unlock(&g_lock);
    return real_lseek ? real_lseek(fd, offset, whence) : -1;
}

off_t lseek64(int fd, off_t offset, int whence) {
    return lseek(fd, offset, whence);
}

ssize_t read(int fd, void *buf, size_t count) {
    init_reals();
    pthread_mutex_lock(&g_lock);
    struct managed *m = find(fd);
    if (!m) { pthread_mutex_unlock(&g_lock); return real_read(fd, buf, count); }
    if (m->kind == KIND_USBFS) {
        uint32_t rem = (m->desc_pos < m->desc_len) ? (m->desc_len - m->desc_pos) : 0;
        uint32_t cp = rem < count ? rem : (uint32_t)count;
        if (cp > 0 && buf && m->desc_buf) {
            memcpy(buf, m->desc_buf + m->desc_pos, cp);
            m->desc_pos += cp;
        }
        pthread_mutex_unlock(&g_lock);
        return (ssize_t)cp;
    }
    int ep = m->ep_in, sock = m->fd;
    pthread_mutex_unlock(&g_lock);
    if (!ep) { errno = EIO; return -1; }
    uint8_t *out = NULL; uint32_t out_len = 0;
    int rc = proxy_bulk(sock, ep, NULL, 0, (int)count, &out, &out_len);
    if (rc < 0) { free(out); errno = EIO; return -1; }
    uint32_t cp = out_len < count ? out_len : (uint32_t)count;
    if (out && cp) memcpy(buf, out, cp);
    free(out);
    return (ssize_t)cp;
}

ssize_t write(int fd, const void *buf, size_t count) {
    init_reals();
    pthread_mutex_lock(&g_lock);
    struct managed *m = find(fd);
    if (!m) { pthread_mutex_unlock(&g_lock); return real_write(fd, buf, count); }
    int ep = m->ep_out, sock = m->fd;
    pthread_mutex_unlock(&g_lock);
    if (!ep) { errno = EIO; return -1; }
    int rc = proxy_bulk(sock, ep, buf, (int)count, 0, NULL, NULL);
    if (rc < 0) { errno = EIO; return -1; }
    return rc; /* bytes written */
}

int close(int fd) {
    init_reals();
    pthread_mutex_lock(&g_lock);
    struct managed *m = find(fd);
    if (m) {
        if (m->kind == KIND_USBFS) {
            m->fd = -1;
            pthread_mutex_unlock(&g_lock);

            pthread_mutex_lock(&m->urb_lock);
            m->closing = 1;
            pthread_cond_broadcast(&m->urb_cond);
            pthread_mutex_unlock(&m->urb_lock);

            if (m->threads_started) {
                pthread_join(m->thr_in, NULL);
                pthread_join(m->thr_out, NULL);
            }
            uint8_t op = OP_CLOSE;
            if (m->sock_ctrl >= 0) { proxy_request(m->sock_ctrl, &op, 1, NULL, NULL); real_close(m->sock_ctrl); }
            if (m->sock_in >= 0)   { proxy_request(m->sock_in, &op, 1, NULL, NULL);   real_close(m->sock_in); }
            if (m->sock_out >= 0)  { proxy_request(m->sock_out, &op, 1, NULL, NULL);  real_close(m->sock_out); }

            struct urb_node *u;
            while ((u = urb_queue_pop(&m->in_head, &m->in_tail)) != NULL) free(u);
            while ((u = urb_queue_pop(&m->out_head, &m->out_tail)) != NULL) free(u);
            while ((u = urb_queue_pop(&m->done_head, &m->done_tail)) != NULL) free(u);
            free(m->desc_buf);
            pthread_mutex_destroy(&m->ctrl_lock);
            pthread_mutex_destroy(&m->urb_lock);
            pthread_cond_destroy(&m->urb_cond);
            return real_close(fd);
        }
        uint8_t op = OP_CLOSE;
        proxy_request(m->fd, &op, 1, NULL, NULL);
        m->fd = -1;
        pthread_mutex_unlock(&g_lock);
        return real_close(fd);
    }
    pthread_mutex_unlock(&g_lock);
    return real_close(fd);
}

int poll(struct pollfd *fds, nfds_t nfds, int timeout) {
    init_reals();
    /* KIND_HIDRAW: report immediately readable.
     * KIND_USBFS:  backed by an eventfd that becomes POLLIN when done_head is
     * non-empty; libusb polls usbfs fds with POLLOUT, so translate POLLOUT to
     * POLLIN for real_poll() and map the resulting revents back to POLLOUT. */
    int hidraw_ready = 0, has_other = 0;
    short stack_events[16];
    short *orig_events = (nfds <= 16) ? stack_events : malloc(nfds * sizeof(short));
    pthread_mutex_lock(&g_lock);
    for (nfds_t i = 0; i < nfds; i++) {
        orig_events[i] = fds[i].events;
        struct managed *m = find(fds[i].fd);
        if (m && m->kind == KIND_HIDRAW) {
            fds[i].revents = fds[i].events & POLLIN;
            if (fds[i].revents) hidraw_ready++;
        } else if (m && m->kind == KIND_USBFS) {
            fds[i].events = POLLIN;
            has_other = 1;
        } else {
            has_other = 1;
        }
    }
    pthread_mutex_unlock(&g_lock);
    if (hidraw_ready && !has_other) {
        if (orig_events != stack_events) free(orig_events);
        return hidraw_ready;
    }
    int rc = real_poll(fds, nfds, hidraw_ready ? 0 : timeout);
    pthread_mutex_lock(&g_lock);
    for (nfds_t i = 0; i < nfds; i++) {
        struct managed *m = find(fds[i].fd);
        fds[i].events = orig_events[i];
        if (m && m->kind == KIND_USBFS && (fds[i].revents & POLLIN)) {
            fds[i].revents = orig_events[i] & (POLLOUT | POLLIN | POLLWRNORM | POLLRDNORM);
        }
    }
    pthread_mutex_unlock(&g_lock);
    if (orig_events != stack_events) free(orig_events);
    return rc;
}
