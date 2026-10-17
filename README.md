# Harbor — Paper port (silent `/sh`, rootfs in plugin folder)

Paper port of [RealTriassic/Harbor](https://github.com/RealTriassic/Harbor).
Upstream script is cloned at `../harbor-upstream/harbor.sh` for reference.
This port moves `ROOTFS_DIR` from `/home/container` to `plugins/Harbor/container`
and wraps install + exec in a silent Paper plugin.

## What you get

- Silent: no banner, no GoTTY, no blocking. Install runs async on enable.
  Routine logs go to `fine` when `silent: true` (default).
- Normal commands:
  - `/sh <command>` — runs `/bin/sh -c "<command>"` inside Alpine via proot.
    Output is sent back to the sender only, chunked. Perm `harbor.shell` (op).
  - `/harbor status|install|reinstall|boot|dropbear|exec` — admin. Perm `harbor.admin` (op).
- Whole rootfs in plugin folder:
  - `plugins/Harbor/container/` (+ `.installed` marker, `usr/local/bin/proot`, `etc/resolv.conf`)
  - temp downloads in system tmp `harbor-Harbor/`
- Auto dropbear on every boot (see below), plus config `startup-commands`.

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
- Paper 1.13+ (built against 1.20.4, Java 17).

## Build (Gradle wrapper, no install needed)

```bash
cd Harbor-Paper
./gradlew build        # Linux
.\gradlew.bat build    # Windows
# jar: build/libs/Harbor-1.0.0.jar
```

Drop the jar in `plugins/`, restart. First enable downloads (~5MB Alpine
minirootfs + apk-tools-static + proot static) and runs
`apk.static --root plugins/Harbor/container add alpine-base apk-tools` once.

## Use

```
/sh cat /etc/alpine-release
/sh apk add -q htop && htop --version
/sh ls -la /root
/harbor status
/harbor boot
/harbor dropbear start
```

Each `/sh` is one-shot: `proot --rootfs=... --root-id --cwd=/root
--bind=/proc --bind=/dev --bind=/sys --bind=/tmp /bin/sh -c "<command>"`
with `PROOT_NO_SECCOMP=1`. proot's `--link2symlink` is **not** passed by default:
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

`command-timeout-seconds: 0` (default) means **no timeout** — `/sh` waits as long
as the command takes. Set it to a positive number to cap runs; on expiry the
process is force-killed and the partial output plus `[timed out after Ns]` is
returned. Output capture is capped at 8 MiB per command either way, so a runaway
command cannot exhaust memory (stdout is drained on a separate thread, so long
runs cannot deadlock on a full pipe).

## Config (`plugins/Harbor/config.yml`)

```yaml
silent: true
alpine-version: "3.22"
alpine-full-version: "3.22.6"
apk-tools-version: "2.14.12-r0"
proot-version: "5.3.0"
proot-link2symlink: false         # only for filesystems without hard links
command-timeout-seconds: 0        # 0 = no timeout
dns: ["1.1.1.1", "1.0.0.1"]
dropbear: { enabled: true, port: 25584, authorized-key: "", allow-password-login: false }
startup-commands: ["echo harbor booted at $(date)"]
```

Bump versions together to track Alpine releases. Upstream pins are kept as
defaults for compatibility.

## Notes vs upstream

- No GoTTY terminal sharing (would break silence / open ports on MC hosts).
- No interactive `/bin/sh` session — Pterodactyl console is replaced by `/sh`.
  If you need a shell loop, use `/sh ash` style one-liners or add a
  persistent session task.
- License: GPL-3.0 (same as upstream).
