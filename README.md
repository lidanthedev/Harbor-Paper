# Harbor — Paper port (silent `/sh`, rootfs in plugin folder)

Paper port of [RealTriassic/Harbor](https://github.com/RealTriassic/Harbor).
Upstream script is cloned at `../harbor-upstream/harbor.sh` for reference.
This port moves `ROOTFS_DIR` from `/home/container` to `plugins/Harbor/container`
and wraps install + exec in a silent Paper plugin.

The same jar also runs **standalone**: launched directly (`java -jar`, e.g. as
`server.jar`) it needs no Paper at all — the console becomes a shell into the
same rootfs. See [Run as server.jar](#run-as-serverjar-standalone).

> ## ⚠️ `harbor.shell` is root-equivalent on the server
>
> `/sh` is **full read/write access to the server's filesystem — it is not a
> sandbox.** The rootfs shares the container's namespaces, the server directory is
> bound at `/mnt` (`real-pwd-mount`), and proot's path translation can be bypassed
> outright with a raw `openat2` syscall. So `harbor.shell` can read and modify
> anything the server user owns — `server.jar`, `server.properties`, `eula.txt`,
> plugins, worlds, other people's configs — and can signal PID 1 (the server).
>
> Grant it exactly like you would shell access on the host. **Never** give it to
> anyone you would not make an operator with filesystem access.
>
> Details, including the working escape proof:
> [`docs/CONTAINER-LIMITS.md`](docs/CONTAINER-LIMITS.md).

## What you get

- Silent: no banner, no GoTTY, no blocking. Install runs async on enable.
  Routine logs go to `fine` when `silent: true` (default).
- Normal commands:
  - `/sh <command>` — runs `/bin/sh -c "<command>"` inside Alpine via proot.
    Output is sent back to the sender only, chunked. Perm `harbor.shell` (op) —
    see the warning above: this is root-equivalent on the server filesystem.
  - `/harbor status|install|reinstall|boot|dropbear|exec` — admin. Perm `harbor.admin` (op).
- Whole rootfs in plugin folder:
  - `plugins/Harbor/container/` (+ `.installed` marker, `usr/local/bin/proot`, `etc/resolv.conf`)
  - temp downloads in system tmp `harbor-Harbor/`
- Auto dropbear on every boot (see below), plus config `startup-commands`.
- Dual mode, one jar: a Paper plugin in `plugins/`, or a standalone shell box
  when run directly as `server.jar` (no Bukkit involved — see below).

## Auto dropbear (unprivileged-container path)

On boot the plugin installs and starts dropbear for you, working around the
three quirks of an unprivileged container:

| Quirk | Compensation |
|---|---|
| `dropbearkey` dies on `getrandom()` (container seccomp) | host keys generated with OpenSSH `ssh-keygen -A` |
| dropbear needs its own key format + right subpackage | `apk add dropbear dropbear-convert` + `dropbearconvert openssh dropbear` |
| dropbear reads home from `/etc/passwd`, not `$HOME` | `authorized_keys` written to `/root/.ssh/authorized_keys` (not `/home/container/.ssh`) |

Then it starts `nohup dropbear -F -E -K 60 -p <port> -r <converted key> &`, and by
default passes `-g` (no root password logins — keys only).
`-F` is required: without it dropbear does not run correctly in this container, so
the process is kept in the foreground and detached with `nohup`/`&` instead of
letting dropbear daemonise itself.

`/sh` deliberately does **not** pass proot's `--kill-on-exit`. That flag kills
every tracee the moment proot exits, which makes it impossible to leave a
backgrounded daemon running — the daemon is killed before it can even write its
log. Without it, `cmd &` survives the one-shot session as expected. Before
detaching, a pre-flight run starts the service for ~3s and kills it, so a
genuine startup failure (port in use, bad host key) lands in
`/root/.harbor/dropbear.log` and is reported inline by `/harbor dropbear start`.

Dropbear is controlled by subcommands, so a restart never re-does package or key
work:

| Command | Does |
|---|---|
| `/harbor dropbear start` | kill + relaunch with the existing host key (fast) |
| `/harbor dropbear reinstall` | packages + host keys + `dropbearconvert` + `authorized_keys`, then start |
| `/harbor dropbear stop` | kill dropbear |

`start` on a rootfs that has never been set up falls back to `reinstall`
automatically. Note that `start`/`stop` drop any **active** SSH session — the
process being killed is the one serving it.

Add your client's public key to config and you get passwordless SSH:

```yaml
dropbear:
  enabled: true
  port: 25584
  authorized-key: "ssh-ed25519 AAAAC3...you@host"
  allow-password-login: false
```

Then, from your machine — a normal interactive shell:

```sh
ssh -p 25584 root@<panel-host>
```

…or a SOCKS tunnel, if you would rather route other traffic through it:

```sh
ssh -p 25584 root@<panel-host> -D 127.0.0.1:1080 -N -o ServerAliveInterval=60
```

## Startup commands

`startup-commands` run inside the rootfs, in order, in the background, once per
boot (after install + dropbear). Each entry goes through `/bin/sh -c` with
`$HOME=/root`; lines starting with `#` are comments.

```yaml
startup-commands:
  - "echo harbor booted at $(date)"
  - "python3 -m http.server 8000 --directory /root"
```

Run them again any time with `/harbor boot`.

## Requirements

- Linux server (`proot` static binaries are Linux-only). Gracefully refuses on Windows/macOS.
- `x86_64` or `aarch64`.
- System `tar`, `chmod`, `curl`-equivalent (Java downloads), internet.
- Paper 1.13+ (built against 1.20.4, Java 17) for plugin mode; standalone mode
  needs only Java 17+ and no Paper.

## Build (Gradle wrapper, no install needed)

```bash
cd Harbor-Paper
./gradlew build        # Linux
.\gradlew.bat build    # Windows
# jar: build/libs/Harbor-1.1.0.jar   (shaded: SnakeYAML bundled + relocated)
```

Drop the jar in `plugins/`, restart. First enable downloads (~5MB Alpine
minirootfs + apk-tools-static + proot static) and runs
`apk.static --root plugins/Harbor/container add alpine-base apk-tools` once.

The jar is shaded by the Shadow plugin (Gradle 8.10.2 → `com.gradleup.shadow`
8.3.5): SnakeYAML is bundled and relocated to
`dev.triassic.harbor.lib.snakeyaml`, so it never collides with Paper's own copy
in plugin mode. That relocation is what makes one file safe in both modes.

## Use

```
/sh cat /etc/alpine-release
/sh apk add -q htop && htop --version
/sh ls -la /root
/sh ls /mnt                 # the real server directory (see below)
/harbor status
/harbor boot
/harbor dropbear start
```

Each `/sh` is one-shot: `proot --rootfs=... --root-id --cwd=/root
--bind=/proc --bind=/dev --bind=/sys --bind=/tmp --bind=$PWD:/mnt
/bin/sh -c "<command>"` with `PROOT_NO_SECCOMP=1`.

The server's real working directory (`$PWD`, i.e. the `/home/container` volume)
is bound into the rootfs at `/mnt`, so `/sh` can work with the actual server
files directly:

```
/sh ls /mnt
/sh cat /mnt/server.properties
/sh cp /mnt/server.jar /root/backup.jar
```

Change the guest path with `real-pwd-mount:`, or set it to `""` to disable.
This is a convenience, not a new capability: proot is not a sandbox, and a raw
`openat2` reaches the same files anyway — see
[`docs/CONTAINER-LIMITS.md`](docs/CONTAINER-LIMITS.md) §9.

proot's `--link2symlink` is **not** passed by default:
it only helps on
filesystems that cannot create real hard links, and on a normal ext4/overlayfs
volume it breaks `apk` installs of packages that ship hardlinked binaries
(gcc/g++/binutils) and leaves un-removable `.l2s.*` chains. Enable it with
`proot-link2symlink: true` if your volume really lacks hard-link support. See
[`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) (Problem 21).

proot's `--kill-on-exit` is deliberately **not** passed: it kills every tracee
when proot exits, which would make backgrounded daemons (dropbear) impossible to
keep alive. See [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) for the full
story, including why this looked like an empty log file.

What the container actually lets you do (capabilities, cgroup limits, mounts,
network egress, namespaces, devices) was measured on a live deployment and is
written up in [`docs/CONTAINER-LIMITS.md`](docs/CONTAINER-LIMITS.md). Short
version: proot is a compatibility layer, **not** a sandbox — the rootfs shares
the server's namespaces, the session can see and signal PID 1, and a raw
`openat2` syscall bypasses proot's path translation entirely (read/write access
to the real server filesystem). The real limits come from the outer container:
no capabilities, a seccomp filter, a read-only image root, high ports only, no
`/dev/net/tun`, and IPv4-only egress.

`command-timeout-seconds: 0` (default) means **no timeout** — `/sh` waits as long
as the command takes. Set it to a positive number to cap runs; on expiry the
process is force-killed and the partial output plus `[timed out after Ns]` is
returned. Output capture is capped at 8 MiB per command either way, so a runaway
command cannot exhaust memory (stdout is drained on a separate thread, so long
runs cannot deadlock on a full pipe).

## Run as server.jar (standalone)

The same jar is a self-contained program. Name it `server.jar`, point the panel's
startup command at it, and Harbor runs with **no Paper and no Bukkit**:

```sh
java -jar server.jar
```

- It still creates and uses `plugins/Harbor/` next to the working directory
  (`plugins/Harbor/config.yml`, `plugins/Harbor/container/`), so an existing
  install is picked up unchanged.
- **Nothing is registered with Bukkit/Paper** — there is no server to register
  with.
- **Every line typed into the console is run in the rootfs**, exactly like `/sh`:

  ```
  ls /etc
  apk add -q htop && htop --version
  cat /mnt/server.properties
  ```

- `/harbor ...` is reserved for Harbor itself: `status`, `install`, `reinstall`,
  `boot`, `dropbear <start|reinstall|stop>`, `exec <command>`, `help`.
  `/sh <command>` is accepted as an alias for the shell, so muscle memory from
  plugin mode still works.
- dropbear is set up exactly as in plugin mode (same code path), so SSH works
  identically. The dropbear line is always printed at boot so you can see the
  port and fingerprint.
- `silent: true` still keeps Harbor's own fine-level chatter off; the `$ command`
  echo and the command output always show, since the console is the interface.

Useful when you want a Linux box on a Minecraft host without running Minecraft
at all — or to bootstrap the rootfs before ever starting Paper.

## Config (`plugins/Harbor/config.yml`)

```yaml
silent: true
alpine-version: "3.22"
alpine-full-version: "3.22.6"
apk-tools-version: "2.14.12-r0"
proot-version: "5.3.0"
proot-link2symlink: false         # only for filesystems without hard links
real-pwd-mount: "/mnt"            # expose the server dir ($PWD) here; "" = off
command-timeout-seconds: 0        # 0 = no timeout
dns: ["1.1.1.1", "1.0.0.1"]
dropbear: { enabled: true, port: 25584, authorized-key: "", allow-password-login: false }
startup-commands: ["echo harbor booted at $(date)"]
```

Bump versions together to track Alpine releases. Upstream pins are kept as
defaults for compatibility.

The same file is read in both modes: plugin mode reads it through Bukkit,
standalone mode parses it with the bundled SnakeYAML. The keys are identical,
and missing keys fall back to the defaults above.

## Notes vs upstream

- No GoTTY terminal sharing (would break silence / open ports on MC hosts).
- No interactive `/bin/sh` session — Pterodactyl console is replaced by `/sh`.
  If you need a shell loop, use `/sh ash` style one-liners or add a
  persistent session task. Standalone mode is the exception: there the console
  *is* the shell.
- License: GPL-3.0 (same as upstream).
