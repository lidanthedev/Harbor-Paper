# Harbor-Paper — Problems We Hit and How We Fixed Them

This is the engineering log for the Paper port of
[RealTriassic/Harbor](https://github.com/RealTriassic/Harbor): what broke, why it
broke, and the exact change that fixed it. It is written so that the next person
(or the next you) can recognise the symptom, jump to the cause, and not spend a
day re-deriving it.

Everything here was observed on a real deployment: a **Pterodactyl** game server
container running a **Paper 1.20.4** server as an **unprivileged** user, with the
plugin rootfs at `plugins/Harbor/container`.

> **TL;DR of the hardest bug.** Backgrounded daemons (dropbear) died instantly
> and left an *empty* log, while the same command run in the foreground worked
> perfectly. Cause: we passed proot's `--kill-on-exit`, whose entire job is to
> kill every tracee when proot exits. `dropbear … &` started, the shell exited,
> proot exited, proot killed dropbear before it wrote one byte. See
> [Problem 9](#problem-9--backgrounded-daemon-dies-and-the-log-is-empty).

---

## Contents

| # | Area | Problem |
|---|---|---|
| 1 | Build | Maven absent, no admin rights to install it |
| 2 | Build | JDK needed without administrator access |
| 3 | Install | Alpine 3.18 `apk-tools-static` 404 from the CDN |
| 4 | Config | `saveDefaultConfig()` never upgrades an existing config |
| 5 | Install | `apk` bootstrap dies on `chroot(2)` in a restricted container |
| 6 | Exec | Host environment leaked into the rootfs (`$HOME=/home/container`) |
| 7 | Exec | `/sh` timed out on legitimate long-running commands |
| 8 | Exec | Large output deadlocked the pipe / risked OOM |
| 9 | Dropbear | **Backgrounded daemon dies, log file empty** (`--kill-on-exit`) |
| 10 | Dropbear | Launch could hang and never write its log (stdin / `/tmp`) |
| 11 | Dropbear | `dropbearkey` cannot write host keys (`getrandom` blocked) |
| 12 | Dropbear | `sshd` cannot start (`chroot("/var/empty")` denied) |
| 13 | Dropbear | Wrong subpackage for `dropbearconvert` |
| 14 | Dropbear | `authorized_keys` read from the wrong home |
| 15 | Dropbear | `-F` is mandatory in this container |
| 16 | Dropbear | A stale pid file disguised a real failure |
| 17 | Dropbear | No portable way to "try it for 3 seconds" |
| 18 | Dropbear | Port not reachable from outside even when bound |
| 19 | Access | `/sh` had no permission gate |

---

## Environment / topology

```
Pterodactyl container (unprivileged, seccomp-restricted)
└── /home/container                       <- "host" filesystem we control
    ├── plugins/Harbor/
    │   ├── config.yml
    │   └── container/                    <- Alpine minirootfs (ROOTFS_DIR)
    │       ├── .installed                <- marker
    │       ├── usr/local/bin/proot       <- static proot 5.3.0
    │       ├── etc/resolv.conf
    │       └── root/.harbor/dropbear.log <- service logs (persistent)
    └── Harbor-1.0.0.jar
```

Key constraint that drives most of this document: the process is **unprivileged**.
No `chroot(2)`, no `mount`, no binding port 22. `proot` (a `ptrace`-based
user-space `chroot`) is what makes a rootfs usable at all here.

---

## Build

### Problem 1 — Maven absent, no admin rights to install it

**Symptom.** Upstream's build instructions assume Maven. On this Windows host
`mvn` was not installed, and installing it via Chocolatey failed:
`Access to C:\ProgramData\chocolatey\lib-bad is denied`.

**Cause.** No administrator rights on the host; Chocolatey needs them for a
system-wide install.

**Fix.** Replaced Maven with the **Gradle wrapper**, which needs no install at
all. The wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) was bootstrapped
from upstream's `harbor-upstream/wrappers/Kotlin`, and the distribution was bumped
to a current Gradle.

- `build.gradle` — Groovy DSL, `paper-api` as `compileOnly` so the produced jar
  is directly droppable into `plugins/`.
- `gradle/wrapper/gradle-wrapper.properties` —
  `distributionUrl=…/gradle-8.10.2-bin.zip`.
- `pom.xml` deleted.

`compileOnly` matters: Paper's API is provided by the server at runtime, so
bundling it would bloat the jar and risk classloader conflicts. The result is a
~29 KB jar.

```bash
cd Harbor-Paper
./gradlew build          # Linux
.\gradlew.bat build      # Windows
# -> build/libs/Harbor-1.0.0.jar
```

### Problem 2 — JDK needed without administrator access

**Symptom.** No JDK on the machine, and the usual installers want admin.

**Fix.** Installed **Eclipse Temurin 17** as a plain zip to a per-user location
via the Adoptium API, then set `JAVA_HOME` and prepended `Path` at **User** scope
(no admin required):

- `C:\Users\lidan\.jdks\temurin-17`
- Verified `java` / `javac`.

Build command used throughout:

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-17"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat build --console=plain
```

---

## Install

### Problem 3 — Alpine 3.18 `apk-tools-static` 404 from the CDN

**Symptom.** Install failed downloading `apk-tools-static-2.14.0-r2.apk`:
HTTP 404.

**Cause.** Upstream pinned Alpine 3.18. The `dl-cdn.alpinelinux.org` CDN prunes
old package revisions; `2.14.0-r2` was no longer served even though the 3.18
release tree itself still existed. A pinned patch version is not permanent.

**Fix.** Moved the pins to a current Alpine, verified live before committing:

| Pin | Old (404) | New |
|---|---|---|
| `alpine-version` | 3.18 | 3.22 |
| `alpine-full-version` | 3.18.x | 3.22.6 |
| `apk-tools-version` | 2.14.0-r2 | 2.14.12-r0 |
| `proot-version` | 5.3.0 | 5.3.0 (unchanged) |

All four URLs (minirootfs + `apk-tools-static`, `x86_64` **and** `aarch64`) were
confirmed HTTP 200. Files: `src/main/resources/config.yml`,
`HarborManager.ensureInstalled()`.

**Lesson.** Never treat a CDN package revision as immutable. When pinning, expect
to bump. Bump the versions together.

### Problem 4 — `saveDefaultConfig()` never upgrades an existing config

**Symptom.** Even after fixing the pins in `config.yml`, servers that already had
a `plugins/Harbor/config.yml` kept using the old values and kept 404-ing.

**Cause.** Bukkit's `saveDefaultConfig()` only writes the bundled default **if no
config file exists**. An existing file is never overwritten — by design, so user
edits are not lost.

**Fix.** An explicit, in-code migration in `HarborManager.ensureInstalled()`
(detects the stale pins and rewrites them), so existing servers self-heal without
hand-editing:

```java
if (alpineVersion.startsWith("3.18") || apkToolsVersion.equals("2.14.0-r2")) {
    alpineVersion = "3.22";
    alpineFull = "3.22.6";
    apkToolsVersion = "2.14.12-r0";
    log("Migrating stale Alpine 3.18 pins to 3.22.6 / apk-tools-static 2.14.12-r0");
}
```

The same trap bites new keys: keys added later (the `dropbear:` block,
`startup-commands:`, and `command-timeout-seconds: 0`) do **not** appear in an
already-existing config. The code reads every one of them with a safe default
(`getBoolean("dropbear.enabled", true)`, `getLong("command-timeout-seconds", 0)`,
…), so a stale config still behaves sensibly — but if you want the new keys
visible, append them to `plugins/Harbor/config.yml` or delete it and restart.

### Problem 5 — `apk` bootstrap dies on `chroot(2)` in a restricted container

**Symptom.** The first install ran
`apk.static --root plugins/Harbor/container add alpine-base apk-tools` and failed.
The payload actually unpacked, but the command exited non-zero.

**Cause.** Alpine's `post-install` scripts and busybox/openrc **triggers** call
`chroot(2)`. A Pterodactyl container denies it: `Operation not permitted`. The
package *contents* install fine; only the scripts fail. Under proot, openrc
runlevels are meaningless anyway — there is no init.

**Fix.** Two layers in `HarborManager.ensureInstalled()`:

1. Pass `--no-scripts` to `apk.static`, so the chroot-calling scripts are skipped.
2. **Tolerate a non-zero exit** as long as the base system actually landed —
   check for `bin/sh` or `bin/busybox` in the rootfs:

```java
if (apkResult.exitCode != 0
        && !new File(rootfsDir, "bin/sh").isFile()
        && !new File(rootfsDir, "bin/busybox").isFile()) {
    throw new IllegalStateException("apk bootstrap failed (" + apkResult.exitCode + "):\n"
            + truncate(apkResult.output, 2000));
}
```

After this, the user confirmed the rootfs installed successfully and
`apk add dropbear` plus `/sh dropbear -h` both worked.

**Lesson.** Distinguish "the tool returned non-zero" from "the thing I wanted did
not happen". Assert on the outcome, not the exit code, when you know the exit code
is noisy for environmental reasons.

---

## Executing commands

### Problem 6 — Host environment leaked into the rootfs

**Symptom.** Inside the rootfs, `$HOME` was `/home/container` (the Pterodactyl
user's home on the *host*), not `/root`. Host `PATH` and `TERM` leaked in too.

**Cause.** `ProcessBuilder` inherits the parent's environment by default, and we
were not overriding it. The rootfs shares the host's PID/env namespace through
proot, so the host's `HOME` was visible inside.

**Fix.** `HarborManager.execInRootfs()` now **clears** the environment and pins
in-rootfs values explicitly:

```java
env.put("HOME", "/root");
env.put("PWD", "/root");
env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
env.put("SHELL", "/bin/sh");
env.put("USER", "root");
env.put("LOGNAME", "root");
env.put("TERM", "xterm");
env.put("LANG", "C.UTF-8");
env.put("LC_ALL", "C.UTF-8");
env.put("HOSTNAME", "harbor");
```

…and `run()` does `pb.environment().clear()` before `putAll(env)` (only
`PROOT_NO_SECCOMP=1` is added back). Verified with `/sh echo $HOME` → `/root`.

### Problem 7 — `/sh` timed out on legitimate long-running commands

**Symptom.** `command-timeout-seconds: 30` killed builds, `apk` upgrades, and
anything genuinely long.

**Fix.** Timeout semantics changed so **`0` (or negative) means no timeout**.
`run()` branches:

```java
boolean done = timeoutSeconds > 0
        ? p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        : waitForever(p);
```

On expiry the process is force-killed and the partial output plus
`[timed out after Ns]` is returned; with no timeout it simply waits.

`config.yml` now ships `command-timeout-seconds: 0`, and `ShCommand` defaults to
`0` as well. Verified empirically: an unlimited 3-second run completed with exit
`0` at ~3068 ms; a 2-second limit killed the same run with exit `-1`.

### Problem 8 — Large output deadlocked the pipe / risked OOM

**Symptom.** With long/unlimited runs, output could grow without bound. Worse,
if nobody drained the child's stdout, the OS pipe buffer (~64 KB) would fill and
the child would **block forever** — a classic `ProcessBuilder` deadlock.

**Fix.** In `run()`:

- stdout+stderr are merged (`redirectErrorStream(true)`) and drained on a
  **daemon thread** into a `ByteArrayOutputStream`, so the pipe never fills.
- The capture is **capped at 8 MiB** (`MAX_CAPTURE_BYTES`); excess bytes are
  dropped and a `...[output capture capped at …]` marker is appended.

Verified with 1 MB and 10 MB outputs: both completed without deadlock, and the
over-cap run was correctly capped.

---

## Dropbear (SSH into the rootfs)

Dropbear is what turns the rootfs into something you can actually SSH into, so
most of the hard bugs live here. The unprivileged container breaks the normal SSH
setup in several independent ways; each quirk and its compensation is covered
below.

### Problem 9 — Backgrounded daemon dies and the log is empty

This is the one that cost the most time. It is worth reading in full.

**Symptom.** On boot the plugin logged:

```
[Harbor] [dropbear] FAILED on port 3111 | 256 SHA256:vr5I6VZ… (ED25519) |
not listening on 3111, log /root/.harbor/dropbear.log: (empty);
```

The host key existed and had a valid fingerprint, but `dropbear` was not
listening, the process was gone, and the log file was **empty** — even though the
code truncated it and then started dropbear with its output redirected into it.
Checking the log by hand also failed:

```
$ cat /root/.harbor/dropbear.log
cat: can't open '/root/.harbor/dropbear.log': No such file or directory
```

And then the decisive clue from the operator:

> "but when I do `sh dropbear -F -E -p 3111 -r /etc/dropbear/dropbear_ed25519_host_key` it does work"

**The foreground command worked perfectly. The backgrounded one never ran.**

**Cause.** We passed proot's `--kill-on-exit` on every exec:

```
proot --rootfs=… --link2symlink --kill-on-exit --root-id --cwd=/root …
```

That flag's entire purpose is: **when proot exits, kill every tracee.** The
lifecycle of `dropbear … &` was therefore:

1. `sh -c "nohup dropbear … &"` backgrounds dropbear and the shell exits.
2. The one-shot proot session has nothing left to trace, so proot exits.
3. proot executes its kill step and **kills dropbear**.
4. dropbear dies before writing a single log line — hence an empty log and no
   listener.

The foreground run never hit this: dropbear stayed in the foreground, so the
session never reached the "proot exits" step, so nothing was killed. That is
exactly why the operator's manual command worked and ours did not.

**Fix.** **Removed `--kill-on-exit`** from the exec path
(`HarborManager.execInRootfs()`):

```java
cmd.add("--link2symlink");
// Deliberately NOT passing --kill-on-exit: its entire job is to kill
// every tracee when proot exits, which makes it impossible to leave a
// backgrounded daemon (dropbear) running after this one-shot session
// ends. Without it, `cmd &` survives the session as intended.
cmd.add("--root-id");
```

Current proot args:

```
--rootfs=<dir> --link2symlink --root-id --cwd=/root
--bind=/proc --bind=/dev --bind=/sys --bind=/tmp
```

**Consequence to be aware of.** With the flag gone, *anything* you background now
outlives its `/sh`. That is exactly what dropbear needs, but it also means an
accidental `/sh sleep 999 &` will linger. `/harbor dropbear off` still cleans up
dropbear specifically (`pkill -f dropbear`).

**How it was reasoned about.** proot 5.5 dropped `--kill-on-exit` from its manual,
so we read the semantics of the flag as pinned (proot 5.3.0, which we ship) and
matched it against the two observable facts: (a) the foreground command works,
(b) the backgrounded one leaves a truncated-but-empty log. Both are explained by
"something kills the tracee at session teardown", and `--kill-on-exit` is the only
thing in the command line that does that.

### Problem 10 — Launch could hang and never write its log (stdin / `/tmp`)

**Symptom.** Even before Problem 9 was understood, the launch had two latent
bugs of its own: the log was sometimes never created, and a launch could appear
to hang.

**Cause — two independent issues.**

1. **stdin was not redirected.** The original launch was
   `nohup dropbear … >/tmp/dropbear.log 2>&1 &`. The backgrounded process still
   inherited our stdout **pipe**. As long as any writer holds the pipe open, the
   reading side never sees EOF — so the session appeared to hang and the
   redirect was never flushed.
2. **The log lived in `/tmp`.** proot **re-binds `/tmp` per session**, so a log
   written by one exec was not reliably visible to the next — which is why a
   later `cat /tmp/dropbear.log` reported "No such file or directory".

**Fix.** A dedicated helper, `HarborManager.detach(name, command)`, used for every
long-running service. It redirects **all** file descriptors and writes the log
**inside the rootfs** (persistent across sessions):

```java
public String detach(String name, String command) {
    String log = "/root/.harbor/" + name + ".log";
    execShell("mkdir -p /root/.harbor");
    execShell(": > " + log); // truncate so each launch starts clean
    // pre-flight diagnostics (see Problem 17) …
    execShell("nohup " + command + " </dev/null >>" + log + " 2>&1 & "
            + "disown 2>/dev/null || true");
    execShell("sleep 1");
    return log;
}
```

- `</dev/null` — stdin detached, so nothing holds our pipe open; the session sees
  EOF and the launch returns promptly.
- `>/root/.harbor/<name>.log 2>&1` — stdout+stderr to a persistent in-rootfs file
  (not `/tmp`).
- `disown` — where the shell supports it, don't reap the job on exit.

Verified with a harness driving the same `run()` logic: the old pattern blocked,
the new one returned in ~26 ms with the service detached and still running.

### Problem 11 — `dropbearkey` cannot write host keys (`getrandom` blocked)

**Symptom.** `dropbearkey` failed to generate host keys.

**Cause.** The container's seccomp policy blocks `getrandom()`, which
`dropbearkey` needs. This is a known quirk of the unprivileged path.

**Fix.** Generate host keys with **OpenSSH** instead, which works, then convert
them (next problem). `Dropbear.setup()` runs `ssh-keygen -A`, with a targeted
fallback:

```java
manager.execShell("ssh-keygen -A 2>&1", 120);
if (!manager.fileExists("/etc/ssh/ssh_host_ed25519_key")) {
    manager.execShell("ssh-keygen -t ed25519 -N '' -f /etc/ssh/ssh_host_ed25519_key "
            + "-C 'harbor host key' 2>&1", 120);
}
```

Ed25519 is preferred; RSA is used only if Ed25519 is unavailable.

### Problem 12 — `sshd` cannot start (`chroot("/var/empty")` denied)

**Symptom.** `sshd` failed with
`chroot("/var/empty"): Operation not permitted`.

**Cause.** OpenSSH's `sshd` refuses to run without privilege separation, which
requires `chroot(2)` — denied in an unprivileged container.

**Fix.** Don't use `sshd`. Use **dropbear**, which does not need privilege
separation. This is the reason the whole dropbear path exists at all.

### Problem 13 — Wrong subpackage for `dropbearconvert`

**Symptom.** `dropbearconvert` was not found after installing dropbear.

**Cause.** The converter is not in the base `dropbear` package, and the obvious
guess (`dropbear-openrc`) is wrong — that package is the OpenRC *service script*,
not the converter.

**Fix.** Install the correct set explicitly:

```java
manager.execShell("apk add --no-cache dropbear dropbear-convert openssh-keygen");
```

…then convert OpenSSH → dropbear format:

```java
manager.execShell("dropbearconvert openssh dropbear " + srcKey + " " + dstKey);
```

`Dropbear.setup()` checks `hasBinary("dropbearconvert")` and `hasBinary("ssh-keygen")`
and records a note if either is still missing, rather than failing silently.

### Problem 14 — `authorized_keys` read from the wrong home

**Symptom.** Even with a correct key, key auth failed.

**Cause.** dropbear resolves the user's home from `/etc/passwd`, **not** `$HOME`.
Earlier attempts had written keys to the *shell* `$HOME`
(`/home/container/.ssh`), which dropbear never looks at. Root's real home is
`/root`.

**Fix.** Always write to `/root/.ssh/authorized_keys` with correct permissions,
and migrate any key left in the wrong place:

```java
manager.execShell("mkdir -p /root/.ssh && chmod 700 /root/.ssh");
manager.writeFileInRootfs("/root/.ssh/authorized_keys", pubKey.trim() + "\n", "600");
// else: copy anything left behind by earlier attempts
manager.execShell("[ -f /home/container/.ssh/authorized_keys ] && "
        + "cp /home/container/.ssh/authorized_keys /root/.ssh/authorized_keys "
        + "&& chmod 600 /root/.ssh/authorized_keys");
```

The file is written via a **base64 pipe** (`writeFileInRootfs`) to avoid quoting
problems with arbitrary key material.

### Problem 15 — `-F` is mandatory in this container

**Symptom.** Without `-F`, dropbear did not run correctly inside the container
(operator-confirmed).

**Cause.** Without `-F`, dropbear tries to **daemonise itself**. In this
container that detaching misbehaves — and it also conflicts with how we want to
manage the process.

**Fix.** Always pass **`-F`** (stay in the foreground) and do the detaching
ourselves with `nohup`/`&`/`disown` (Problem 10). The full argument list
(`Dropbear.java`):

```java
String args = "-F -E -K 60 -p " + port + " -r " + dstKey
        + " -P /root/.harbor/dropbear.pid";
if (!allowPw) {
    args += " -g"; // no root password logins; keys only
}
```

- `-F` foreground (required here)
- `-E` log to stderr (so it lands in our log file)
- `-K 60` keepalive
- `-P /root/.harbor/dropbear.pid` pid file inside the rootfs
- `-g` disable root password logins (keys only) unless
  `dropbear.allow-password-login: true`

### Problem 16 — A stale pid file disguised a real failure

**Symptom.** A genuine startup failure (e.g. port in use) looked like a clean
start because a pid file from a previous run was still present.

**Fix.** Remove the pid file **before** launching, so its presence afterwards
means something real:

```java
manager.execShell("rm -f /root/.harbor/dropbear.pid");
String logPath = manager.detach("dropbear", "dropbear " + args);
```

### Problem 17 — No portable way to "try it for 3 seconds"

**Symptom.** A daemon that cannot start (port already in use, bad host key)
exits immediately, but because we launch it **detached**, we never see *why* —
the failure happens after we've already moved on.

**Cause / trap.** The obvious tool, `timeout`, is **not reliably present in
busybox**, so `timeout 3 dropbear …` was not portable.

**Fix.** A pre-flight inside `detach()` that uses only POSIX shell builtins
(`$!`, `sleep`, `kill`):

```java
execInRootfsSafe("{ " + command + " </dev/null >>" + log + " 2>&1 & "
        + "p=$!; sleep 3; kill $p 2>/dev/null; } 2>/dev/null", 20);
```

- If the service **cannot** start, it exits within ~3 s and its error is now in
  the log.
- If it **can** start, it runs for 3 s, is killed, and the port is freed again —
  so the subsequent real launch has no `TIME_WAIT` rebind race.
- The log is truncated (`: >`) before the pre-flight and **appended** (`>>`) by
  both runs, so you get the pre-flight error *and* the live daemon's output.

`/harbor dropbear` reports the log **inline** on failure, so the operator sees the
actual reason (e.g. `Address in use`) without a second command.

### Problem 18 — Port not reachable from outside even when bound

**Symptom.** The deployment used port `3111` (not the default `25584`).

**Cause.** Binding a port inside the container is necessary but **not sufficient**:
in Pterodactyl the port must also be **allocated to the server** by the panel. A
successfully-bound but unallocated port is unreachable from outside.

**Fix / guidance.** Set `dropbear.port` to a port the panel actually allocates for
this server, and confirm the allocation in the panel. `dropbear.enabled`,
`dropbear.port`, `dropbear.authorized-key` and `dropbear.allow-password-login` are
all configurable under the `dropbear:` block.

---

## Access control

### Problem 19 — `/sh` had no permission gate

**Symptom.** Any player could run `/sh`.

**Cause.** No permission was declared or checked.

**Fix.** Declared `harbor.shell` (`default: op`) and `harbor.admin`
(`default: op`) in `plugin.yml`, attached `harbor.shell` to the `sh` command, and
enforced it in **both** the executor and the tab-completer so the command is
neither runnable nor discoverable without permission:

```java
if (!sender.hasPermission("harbor.shell")) {
    sender.sendMessage("§cNo permission.");
    return true;
}
```

Ops get it by default. `/harbor …` is gated on `harbor.admin`.

---

## Verification performed

| Check | Result |
|---|---|
| Gradle wrapper build | `BUILD SUCCESSFUL`; `Harbor-1.0.0.jar` ~29 KB |
| Jar contents | 6 classes + `config.yml` + `plugin.yml` |
| All pinned URLs (rootfs + apk-tools, x86_64 & aarch64) | HTTP 200 |
| `/sh echo $HOME` | `/root` (host leak fixed) |
| `/sh` unlimited timeout | 3 s run → exit 0 at ~3068 ms |
| `/sh` capped timeout | 2 s limit → killed, exit `-1` |
| `/sh` 1 MB / 10 MB output | no deadlock; over-cap run capped with marker |
| Backgrounded service survives session | verified via harness (old pattern blocked, new returns ~26 ms) |
| `--kill-on-exit` removed | only the explanatory comment remains in the source |
| Real deployment | rootfs installs; `apk add dropbear`; `/sh dropbear -h` OK |

---

## Known limitations

- **Linux only.** proot static binaries are Linux-only; the plugin refuses
  cleanly on Windows/macOS.
- **x86_64 / aarch64 only.**
- **No GoTTY / interactive shell.** Deliberately omitted to keep the plugin
  silent and to avoid opening extra ports. `/sh` is one-shot; use one-liners or
  background a service via `startup-commands`.
- **dropbear does not self-revive across a container restart.** It is started
  once per Harbor boot. If the *container* restarts (not just the server), run
  `/harbor dropbear` (or restart the server so boot tasks re-run).
- **Backgrounded processes now outlive their `/sh`** (by design, post-Problem 9).
  Clean up stray ones yourself, or `/harbor dropbear off` for dropbear.
- **`startup-commands` use a fixed 300 s timeout**, unlike `/sh` which is
  unlimited. Fine for typical boot tasks; worth revisiting if you need an
  unlimited boot command.
- **Stale configs.** New config keys are not injected into an existing
  `plugins/Harbor/config.yml`; code defaults keep things working, but append the
  keys or delete the file to see them.

---

## Appendix — where things live

| File | Responsibility |
|---|---|
| `HarborPlugin.java` | `onEnable`, silent async install → boot, `bootAsync`, `ensureInstalledAsync` |
| `HarborManager.java` | proot exec, install/download, env pinning, timeout + drain logic, `detach()` |
| `Dropbear.java` | packages, host keys, key conversion, `authorized_keys`, launch, reporting |
| `ShCommand.java` | `/sh` with `harbor.shell`, unlimited default timeout, chunked output |
| `HarborCommand.java` | `/harbor status\|install\|reinstall\|boot\|dropbear\|exec` |
| `src/main/resources/config.yml` | versions, timeout, `dropbear:` block, `startup-commands:` |
| `src/main/resources/plugin.yml` | commands + `harbor.shell` / `harbor.admin` |
| `build.gradle` | Gradle build, `paper-api` `compileOnly` |

### The five changes that mattered most

1. **Remove `--kill-on-exit`** — without it, nothing you background can survive
   (Problem 9).
2. **`detach()` with `</dev/null` and an in-rootfs log** — fixes both the hang and
   the vanishing log (Problem 10).
3. **`--no-scripts` + outcome-based bootstrap check** — makes install work in a
   container that denies `chroot(2)` (Problem 5).
4. **Clear and pin the environment** — stops the host's `$HOME`/`PATH` leaking in
   (Problem 6).
5. **Drain output on a thread with an 8 MiB cap** — no deadlocks, no OOM
   (Problems 7 and 8).

---

## License

GPL-3.0, same as upstream [RealTriassic/Harbor](https://github.com/RealTriassic/Harbor).
