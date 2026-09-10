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

/* Exit only the current process; a failed exec must not take the
 * terminal session down with a JNI-level error. */
static void child_exec_passt(const char *passt, const char *log_path,
			     const char *fds)
{
	const char *gw = getenv("PASST_GW");
	const char *dns_env = getenv("PASST_DNS");

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
	if (!dns_env || !*dns_env)
		dns_env = "1.1.1.1";

	if (getenv("PASST_DEBUG")) {
		if (gw)
			execl(passt, "passt", "-d", "-f", "-F", fds, "-g", gw,
			      "--dns", dns_env, (char *)NULL);
		else
			execl(passt, "passt", "-d", "-f", "-F", fds, "--dns",
			      dns_env, (char *)NULL);
	} else if (gw) {
		execl(passt, "passt", "-f", "-F", fds, "-g", gw, "--dns",
		      dns_env, (char *)NULL);
	} else {
		execl(passt, "passt", "-f", "-F", fds, "--dns", dns_env,
		      (char *)NULL);
	}
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

	char **kargv = calloc((size_t)(argc - 2), sizeof(char *));
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