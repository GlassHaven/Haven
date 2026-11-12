/* Static guest client for Haven's Shizuku bridge (#837).
 *
 * Bind-mounted inside PRoot at /usr/local/bin/shizuku and /usr/local/bin/rish.
 * Speaks a framed stream protocol over AF_UNIX (/tmp/.haven-shizuku.sock by
 * default, or $HAVEN_SHIZUKU_SOCK) to ShizukuBridgeServer in Haven's process,
 * which verifies SO_PEERCRED (uid == Haven's app uid) and opens a privileged
 * PTY or pipe session via ShizukuPtyUserService.
 */
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <termios.h>
#include <unistd.h>

#define DEFAULT_SOCK_PATH "/tmp/.haven-shizuku.sock"
#define MAX_FRAME_LEN 65536u

#define MSG_STDIN     0x01
#define MSG_STDIN_EOF 0x02
#define MSG_RESIZE    0x03

#define MSG_STDOUT    0x11
#define MSG_STDERR    0x12
#define MSG_ERROR     0x13
#define MSG_EXIT      0x14

static struct termios g_orig_termios;
static volatile sig_atomic_t g_termios_saved = 0;
static volatile sig_atomic_t g_winch_pending = 0;

static void restore_tty(void) {
    if (g_termios_saved) {
        tcsetattr(STDIN_FILENO, TCSADRAIN, &g_orig_termios);
        g_termios_saved = 0;
    }
}

static void on_sigwinch(int sig) {
    (void)sig;
    g_winch_pending = 1;
}

static int write_all(int fd, const void *buf, size_t len) {
    const uint8_t *p = (const uint8_t *)buf;
    while (len > 0) {
        ssize_t n = write(fd, p, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;
        p += (size_t)n;
        len -= (size_t)n;
    }
    return 0;
}

static int read_exact(int fd, void *buf, size_t len) {
    uint8_t *p = (uint8_t *)buf;
    while (len > 0) {
        ssize_t n = read(fd, p, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return 1; /* EOF */
        p += (size_t)n;
        len -= (size_t)n;
    }
    return 0;
}

static void put_u16_be(uint8_t *dst, uint16_t v) {
    dst[0] = (uint8_t)((v >> 8) & 0xffu);
    dst[1] = (uint8_t)(v & 0xffu);
}

static void put_u32_be(uint8_t *dst, uint32_t v) {
    dst[0] = (uint8_t)((v >> 24) & 0xffu);
    dst[1] = (uint8_t)((v >> 16) & 0xffu);
    dst[2] = (uint8_t)((v >> 8) & 0xffu);
    dst[3] = (uint8_t)(v & 0xffu);
}

static uint32_t get_u32_be(const uint8_t *src) {
    return ((uint32_t)src[0] << 24) |
           ((uint32_t)src[1] << 16) |
           ((uint32_t)src[2] << 8) |
           (uint32_t)src[3];
}

static int send_frame(int sock, uint8_t type, const void *payload, uint32_t len) {
    uint8_t hdr[5];
    hdr[0] = type;
    put_u32_be(hdr + 1, len);
    if (write_all(sock, hdr, sizeof(hdr)) < 0) return -1;
    if (len > 0 && payload != NULL) {
        if (write_all(sock, payload, len) < 0) return -1;
    }
    return 0;
}

static void query_winsize(uint16_t *rows_out, uint16_t *cols_out) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    if (ioctl(STDIN_FILENO, TIOCGWINSZ, &ws) == 0 && ws.ws_row > 0 && ws.ws_col > 0) {
        *rows_out = ws.ws_row;
        *cols_out = ws.ws_col;
        return;
    }
    if (ioctl(STDOUT_FILENO, TIOCGWINSZ, &ws) == 0 && ws.ws_row > 0 && ws.ws_col > 0) {
        *rows_out = ws.ws_row;
        *cols_out = ws.ws_col;
        return;
    }
    *rows_out = 24;
    *cols_out = 80;
}

static int send_resize(int sock) {
    uint16_t rows = 24, cols = 80;
    uint8_t payload[4];
    query_winsize(&rows, &cols);
    put_u16_be(payload, rows);
    put_u16_be(payload + 2, cols);
    return send_frame(sock, MSG_RESIZE, payload, sizeof(payload));
}

static void print_usage(const char *prog) {
    fprintf(stdout,
        "Usage: %s [-t|-T] [-c COMMAND] [ARG ...]\n"
        "\n"
        "Start an Android Shizuku shell through Haven.\n"
        "\n"
        "Options:\n"
        "  -c COMMAND  Execute COMMAND via /system/bin/sh -c\n"
        "  -t          Force pseudo-terminal allocation\n"
        "  -T          Disable pseudo-terminal allocation\n"
        "  -h, --help  Show this help message\n",
        prog);
}

int main(int argc, char **argv) {
    const char *prog = (argc > 0 && argv[0] && argv[0][0]) ? argv[0] : "shizuku";
    const char *slash = strrchr(prog, '/');
    if (slash && slash[1]) prog = slash + 1;

    int force_tty = -1; /* -1 = auto, 0 = no-pty, 1 = pty */
    int arg_idx = 1;
    while (arg_idx < argc) {
        const char *a = argv[arg_idx];
        if (strcmp(a, "-h") == 0 || strcmp(a, "--help") == 0) {
            print_usage(prog);
            return 0;
        } else if (strcmp(a, "-t") == 0) {
            force_tty = 1;
            arg_idx++;
        } else if (strcmp(a, "-T") == 0) {
            force_tty = 0;
            arg_idx++;
        } else if (strcmp(a, "--") == 0) {
            arg_idx++;
            break;
        } else {
            break;
        }
    }

    int use_pty;
    if (force_tty >= 0) {
        use_pty = force_tty;
    } else if (arg_idx < argc) {
        /* When running a command (e.g. `shizuku -c id`), only allocate a PTY
         * if both stdin and stdout are terminals and no pipe redirection is active;
         * actually matching ssh: non-interactive `-c` defaults to pipe mode so
         * stdout isn't CRLF-mangled unless `-t` is passed! */
        use_pty = 0;
    } else {
        use_pty = (isatty(STDIN_FILENO) && isatty(STDOUT_FILENO)) ? 1 : 0;
    }

    const char *sock_path = getenv("HAVEN_SHIZUKU_SOCK");
    if (!sock_path || !sock_path[0]) {
        sock_path = DEFAULT_SOCK_PATH;
    }

    struct sockaddr_un addr;
    if (strlen(sock_path) >= sizeof(addr.sun_path)) {
        fprintf(stderr, "%s: socket path too long: %s\n", prog, sock_path);
        return 1;
    }

    signal(SIGPIPE, SIG_IGN);

    int sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock < 0) {
        fprintf(stderr, "%s: socket failed: %s\n", prog, strerror(errno));
        return 1;
    }

    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, sock_path, sizeof(addr.sun_path) - 1);

    if (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        fprintf(stderr,
            "%s: cannot connect to Haven Shizuku bridge (%s): %s\n",
            prog, sock_path, strerror(errno));
        close(sock);
        return 1;
    }

    uint16_t rows = 24, cols = 80;
    if (use_pty) {
        query_winsize(&rows, &cols);
    }

    const char *term = getenv("TERM");
    if (!term || !term[0]) term = "xterm-256color";
    size_t term_len = strlen(term);
    if (term_len > 256) term_len = 256;

    int rem_argc = argc - arg_idx;
    if (rem_argc < 0) rem_argc = 0;
    if (rem_argc > 1024) {
        fprintf(stderr, "%s: too many arguments\n", prog);
        close(sock);
        return 1;
    }

    /* Handshake: "HSH1" (4) + usePty (1) + rows (2) + cols (2) + termLen (2) + term + argc (2) */
    uint8_t fixed_hdr[11];
    memcpy(fixed_hdr, "HSH1", 4);
    fixed_hdr[4] = (uint8_t)(use_pty ? 1 : 0);
    put_u16_be(fixed_hdr + 5, rows);
    put_u16_be(fixed_hdr + 7, cols);
    put_u16_be(fixed_hdr + 9, (uint16_t)term_len);

    if (write_all(sock, fixed_hdr, sizeof(fixed_hdr)) < 0 ||
        write_all(sock, term, term_len) < 0) {
        fprintf(stderr, "%s: handshake failed: %s\n", prog, strerror(errno));
        close(sock);
        return 1;
    }

    uint8_t argc_buf[2];
    put_u16_be(argc_buf, (uint16_t)rem_argc);
    if (write_all(sock, argc_buf, sizeof(argc_buf)) < 0) {
        fprintf(stderr, "%s: handshake failed: %s\n", prog, strerror(errno));
        close(sock);
        return 1;
    }

    for (int i = 0; i < rem_argc; i++) {
        const char *s = argv[arg_idx + i] ? argv[arg_idx + i] : "";
        size_t slen = strlen(s);
        if (slen > 131072u) {
            fprintf(stderr, "%s: argument %d exceeds 128 KiB limit\n", prog, i);
            close(sock);
            return 1;
        }
        uint8_t len_buf[4];
        put_u32_be(len_buf, (uint32_t)slen);
        if (write_all(sock, len_buf, sizeof(len_buf)) < 0 ||
            (slen > 0 && write_all(sock, s, slen) < 0)) {
            fprintf(stderr, "%s: handshake failed: %s\n", prog, strerror(errno));
            close(sock);
            return 1;
        }
    }

    if (use_pty && isatty(STDIN_FILENO)) {
        if (tcgetattr(STDIN_FILENO, &g_orig_termios) == 0) {
            struct termios raw = g_orig_termios;
            cfmakeraw(&raw);
            if (tcsetattr(STDIN_FILENO, TCSANOW, &raw) == 0) {
                g_termios_saved = 1;
                atexit(restore_tty);
            }
        }
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = on_sigwinch;
        sigemptyset(&sa.sa_mask);
        sa.sa_flags = SA_RESTART;
        sigaction(SIGWINCH, &sa, NULL);
    }

    uint8_t *io_buf = (uint8_t *)malloc(MAX_FRAME_LEN);
    if (!io_buf) {
        restore_tty();
        close(sock);
        return 1;
    }

    int stdin_open = 1;
    int exit_code = 1;

    while (1) {
        if (g_winch_pending) {
            g_winch_pending = 0;
            if (send_resize(sock) < 0) break;
        }

        struct pollfd fds[2];
        nfds_t nfds = 0;
        fds[nfds].fd = sock;
        fds[nfds].events = POLLIN;
        fds[nfds].revents = 0;
        nfds++;

        if (stdin_open) {
            fds[nfds].fd = STDIN_FILENO;
            fds[nfds].events = POLLIN;
            fds[nfds].revents = 0;
            nfds++;
        }

        int pr = poll(fds, nfds, -1);
        if (pr < 0) {
            if (errno == EINTR) continue;
            break;
        }

        /* Socket incoming frames */
        if (fds[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            uint8_t hdr[5];
            int rr = read_exact(sock, hdr, sizeof(hdr));
            if (rr != 0) {
                /* Unexpected EOF or read error before MSG_EXIT */
                restore_tty();
                if (rr < 0) {
                    fprintf(stderr, "%s: bridge read failed: %s\n", prog, strerror(errno));
                }
                break;
            }
            uint8_t mtype = hdr[0];
            uint32_t mlen = get_u32_be(hdr + 1);
            if (mlen > MAX_FRAME_LEN) {
                restore_tty();
                fprintf(stderr, "%s: invalid frame size %u\n", prog, mlen);
                break;
            }
            if (mlen > 0) {
                if (read_exact(sock, io_buf, mlen) != 0) {
                    restore_tty();
                    break;
                }
            }

            if (mtype == MSG_STDOUT) {
                if (mlen > 0) (void)write_all(STDOUT_FILENO, io_buf, mlen);
            } else if (mtype == MSG_STDERR) {
                if (mlen > 0) (void)write_all(STDERR_FILENO, io_buf, mlen);
            } else if (mtype == MSG_ERROR) {
                restore_tty();
                fprintf(stderr, "%s: ", prog);
                if (mlen > 0) (void)write_all(STDERR_FILENO, io_buf, mlen);
                if (mlen == 0 || io_buf[mlen - 1] != '\n') {
                    (void)write_all(STDERR_FILENO, "\n", 1);
                }
            } else if (mtype == MSG_EXIT) {
                if (mlen == 4) {
                    int32_t code = (int32_t)get_u32_be(io_buf);
                    exit_code = (code >= 0 && code <= 255) ? (int)code : 1;
                }
                restore_tty();
                break;
            }
        }

        /* Stdin -> bridge */
        if (stdin_open && nfds > 1 && (fds[1].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t n = read(STDIN_FILENO, io_buf, MAX_FRAME_LEN);
            if (n < 0) {
                if (errno == EINTR) continue;
                stdin_open = 0;
                (void)send_frame(sock, MSG_STDIN_EOF, NULL, 0);
            } else if (n == 0) {
                stdin_open = 0;
                (void)send_frame(sock, MSG_STDIN_EOF, NULL, 0);
            } else {
                if (send_frame(sock, MSG_STDIN, io_buf, (uint32_t)n) < 0) {
                    break;
                }
            }
        }
    }

    free(io_buf);
    restore_tty();
    close(sock);
    return exit_code;
}
