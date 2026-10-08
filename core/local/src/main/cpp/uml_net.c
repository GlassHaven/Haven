/* Launcher that wires UML's vector fd transport to passt over a unix
 * socketpair. passt's -F (fd) mode speaks the QEMU stream format (a 4-byte
 * length prefix per frame); UML sends raw ethernet frames, so we set
 * PASST_RAW_L2 and use a locally patched passt that sends/receives bare
 * frames when that variable is set.
 *
 * usage: uml-net <passt> <passt-log> <linux> <kernel-args...>
 *
 * One SOCK_SEQPACKET socketpair (the vector transports are datagram
 * oriented: recvmsg/writev per frame). passt runs foreground on fd 100;
 * the kernel gets the other end on fd 101 plus vec0:transport=fd,fd=101.
 *
 * Run as the pty child of PtyBridge.nativeForkPty: stdout/stderr are the
 * guest console (UML's stdio console works on a pollable pty), and the
 * launcher's own errors are visible in the terminal tab. The kernel
 * inherits the console, so no con0/con kernel arguments are needed.
 *
 * Environment (set by the Kotlin side):
 *   PASST_NO_SANDBOX=1  required in the Android app sandbox (passt's
 *                       privilege drop dies with SIGSYS otherwise)
 *   PASST_GW            optional passt -g; omit in app contexts, where
 *                       passt runs in local mode (an explicit -g would
 *                       override the local-mode gateway off-subnet)
 *   PASST_DNS           optional, default 1.1.1.1
 *   PASST_DEBUG         set to run passt with -d
 *   PASST_TFWD          comma-separated TCP ports (e.g. "5951,5952") to
 *                       forward into the guest as one
 *                       "-t 127.0.0.1/<ports>" spec
 *   PASST_UFWD          same for UDP ("-u")
 *   TMPDIR              chdir'ed into before exec'ing the kernel (UML
 *                       wants a writable cwd for its .uml/<umid>/ dir)
 */
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <unistd.h>
#include <signal.h>

/* Exit only the current process; errors here must not take the terminal
 * session down with a JNI-level error. */

#define FWD_HOST "127.0.0.1/"

/* Build a passt -t/-u spec from a comma-separated port list ("5951,5952")
 * in env_name. passt parses "[ADDR/]"PORTS per option and a bare
 * ports-only spec binds all interfaces (sock_l4_dualstack_any binds ::
 * with IPV6_V6ONLY=0), so the launcher always emits the explicit
 * "127.0.0.1/" prefix — the loopback-default posture; LAN exposure would
 * be a deliberate knob, not a missing prefix. Returns NULL when the env
 * var is unset or empty (no forwarding), and exits loudly on anything
 * malformed rather than silently starting a guest that hangs on its
 * listeners. */
static char *passt_fwd_spec(const char *env_name)
{
	const char *ports = getenv(env_name);
	const char *p = ports;
	char *out;
	size_t n;

	if (!ports || !*ports)
		return NULL;

	while (1) {
		long port = 0;
		if (*p < '0' || *p > '9')
			goto bad;
		while (*p >= '0' && *p <= '9') {
			port = port * 10 + (*p - '0');
			p++;
		}
		if (port < 1 || port > 65535)
			goto bad;
		if (*p == ',') {
			p++;
			continue;
		}
		break;
	}

	n = strlen(FWD_HOST) + strlen(ports) + 1;
	out = malloc(n);
	if (!out)
		return NULL;
	snprintf(out, n, FWD_HOST "%s", ports);
	return out;

bad:
	fprintf(stderr,
		"uml-net: %s: malformed port list "
		"(comma-separated 1-65535, got \"%s\")\n", env_name, ports);
	_exit(2);
}

static void child_exec_passt(const char *passt, const char *log_path,
			     const char *fds)
{
	/* Parsing must precede the log redirect so a malformed value reports
	 * on the guest console, not only in the passt log file. */
	char *tfwd = passt_fwd_spec("PASST_TFWD");
	char *ufwd = passt_fwd_spec("PASST_UFWD");

	/* passt's stderr (its log) to its own file so it does not
	 * interleave with the UML console */
	int lf = open(log_path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
	if (lf >= 0) {
		dup2(lf, 2);
		if (lf > 2)
			close(lf);
	}
	/* If Haven dies, passt must not outlive it. */
	prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);
	setenv("PASST_RAW_L2", "1", 1);

	const char *gw = getenv("PASST_GW");
	const char *dns_env = getenv("PASST_DNS");
	if (!dns_env || !*dns_env)
		dns_env = "1.1.1.1";

	/* execl is variadic and the flags are becoming additive (fwd specs,
	 * optional -d/-g), so the argv is assembled and execv'd instead of
	 * 2^n execl spellings. */
	char *av[14];
	int i = 0;
	av[i++] = "passt";
	if (getenv("PASST_DEBUG"))
		av[i++] = "-d";
	av[i++] = "-f";
	av[i++] = "-F";
	av[i++] = (char *)fds;
	if (gw) {
		av[i++] = "-g";
		av[i++] = (char *)gw;
	}
	av[i++] = "--dns";
	av[i++] = (char *)dns_env;
	if (tfwd) {
		av[i++] = "-t";
		av[i++] = tfwd;
	}
	if (ufwd) {
		av[i++] = "-u";
		av[i++] = ufwd;
	}
	av[i] = NULL;

	execv(passt, av);
	perror("execv passt");
	_exit(127);
}

int main(int argc, char **argv)
{
	int sv[2];
	pid_t pid;

	if (argc < 4) {
		fprintf(stderr,
			"usage: uml-net <passt> <passt-log> <linux> <kernel-args...>\n");
		return 2;
	}

	if (socketpair(AF_UNIX, SOCK_SEQPACKET, 0, sv) < 0) {
		perror("socketpair");
		return 1;
	}

	pid = fork();
	if (pid == 0) {
		char fds[16];
		close(sv[1]);
		if (dup2(sv[0], 100) < 0)
			_exit(127);
		snprintf(fds, sizeof(fds), "%d", 100);
		child_exec_passt(argv[1], argv[2], fds);
	}
	if (pid < 0) {
		perror("fork");
		return 1;
	}

	close(sv[0]);
	if (dup2(sv[1], 101) < 0) {
		perror("dup2");
		return 1;
	}

	/* UML checks TMPDIR for writability and uses the cwd for its
	 * .uml/<umid>/ control dir; Haven's process cwd is read-only "/". */
	const char *cwd = getenv("TMPDIR");
	if (!cwd || !*cwd)
		cwd = getenv("HOME");
	if (cwd && chdir(cwd) < 0)
		perror("chdir TMPDIR");

	/* If Haven dies, the kernel must not survive as an orphan
	 * holding the rootfs disk. */
	prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);

	/* argv entries + NULL terminator: argc-2 entries are filled in. */
	char **kargv = calloc((size_t)(argc - 1), sizeof(char *));
	if (!kargv)
		return 1;
	kargv[0] = argv[3];
	for (int i = 4; i < argc; i++)
		kargv[i - 3] = argv[i];
	kargv[argc - 3] = "vec0:transport=fd,fd=101";

	execv(argv[3], kargv);
	perror("execv linux");
	return 1;
}