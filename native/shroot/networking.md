# Guest Networking

[Native architecture](README.md) | [Resource model](process-resources.md)

Namespace-local network configuration, supported nftables flow filtering and NAT. This is not an Android firewall or native-kernel isolation.

- [Launch Profiles](#launch-profiles)
- [Network Namespaces](#network-namespaces)
- [Namespace-Owned Sysctls](#namespace-owned-sysctls)
- [Namespace-Owned Sysfs](#namespace-owned-sysfs)

## Launch Profiles

`network_profile` owns the bounded native argument contract shared by image
run/exec/login, the low-level runner and supervisor: host (default), none,
bridge, the advanced routed backend, publications, DNS and per-family addressing. Java's
`GuestNetworkProfile` only carries immutable launch arguments; native code owns
syntax, preparation and lifetime. MCP and shell CLI use that same runner. OS
boot inherits the profile through its ordinary launch and namespace setup.

`network_launch` prepares topology before guest execution. Host is a no-op.
None enables private loopback without an uplink. Bridge retains a routed gateway
and a private guest namespace in one fabric: gateway bridge `10.88.0.1/24` and
`fd6d:6473:1::1/64`, guest `eth0` `10.88.0.2/24` and `fd6d:6473:1::2/64`,
loopback and per-family default routes. Independent launches reuse
these logical addresses without sharing ports or owners. The supervisor owns
both namespaces; the fabric's peer list remains weak, avoiding a reference cycle.
Routed remains a supported low-level mode, not an obsolete bridge alias.

The profile resolves `--subnet`, `--ip`, `--ip6` and `--gateway` once before
preparation. Both topology and publication rules consume the same typed family
configuration. Original strings are borrowed only to replay arguments through
launch adapters; packet paths never parse CLI values. Address-family duplication,
noncanonical prefixes, invalid endpoints and uplink overlap fail before allocation.
The default topology above remains unchanged. A named/shared network manager is
not implied by a per-launch profile: independent supervisors still own independent
fabrics.

Published TCP/UDP ports become shared typed DNAT rules reached from PREROUTING
and OUTPUT on the gateway;
private guest binds do not automatically publish. `network_filter_update`
shares validation, direct-flow compatibility, frontend reservation and commit
with the nftables adapter. It prepares all resources before state replacement;
the netlink adapter still publishes its ACK before committing. Failure aborts
all reservations. Teardown joins the existing forwarding owner and releases
ports on cancellation, guest failure or normal completion. No second proxy or
network thread is created for native/unfiltered data IO.

`network_resolver` runs once in filesystem-worker preparation. Explicit servers,
or bridge's filtered snapshot of the installed resolver, become a private
read-only file mount. Missing targets and unusable stub-only configurations fail
explicitly. `md_fs_mount_contents` uses an anonymous O_TMPFILE from the existing
store backend and the ordinary mount-view owner: no persistent name, inode
catalog entry, control FD or per-read resolver work. Last-FD closure reclaims the
backing file even after abrupt process exit. Persistent DNS installation and
launch-local resolver configuration remain separate operations.

The public [CLI contract](../../docs/shroot.md#network-modes) records limits and
isolation caveats. `test_launch_network.py` exercises independent native clients,
TCP/UDP publication, real resolver IO, outbound traffic and cleanup; native model
tests also cover partial reservation rollback and typed nft round trips.

## Network Namespaces

`network_model` owns a retained namespace identity and user-namespace
authority. Clone, unshare, setns and typed `/proc/PID/ns/net` handles use the same
resource transaction as the other namespace kinds. A new namespace is offline;
it never silently falls back to Android networking. Existing sockets retain
their original authority. Anonymous Unix socketpairs remain kernel IPC, with
namespace annotations retained through descriptor-table operations.

`network_calls` supplies bounded requests through the existing mapped transport;
`network_namespace` owns control-socket carriers and `network_route` owns the
NETLINK_ROUTE codec. Queries and CAP_NET_ADMIN-authorized mutations share one
model: loopback, bridge/veth, master assignment, IPv4/IPv6 addresses, routes and
typed namespace-FD moves. Replies preserve sequence, port identity, ACK and dump
completion. Topology changes share a fabric turn and mutation journal. Replies
are prepared before notifications become visible; notifications precede the
ACK, as consumers such as networkd correlate them with pending requests.
Failures before publication roll back all affected namespaces. Once a listener
can observe a change, a lost ACK cannot roll it back; overflow requests resync.
The journal exists only for mutations, not queries or data IO. Netlink carriers
provide kernel readiness and queue options without changing Android interfaces.
`network_message` supplies common bounded replies and ACKs; `network_generic`
publishes the nlctrl family catalogue without claiming absent kernel families.
Route rule queries expose the IPv4/IPv6 local/main/default policy; neighbor and
traffic-control dumps are empty and their unsupported mutations fail explicitly.
Strict queries validate complete headers and reject unsupported filters instead
of silently ignoring them. Route dumps accept the header's table selector and
the extended `RTA_TABLE` selector, with the latter taking precedence; unknown
tables yield an empty dump, not the main table. Strict dumps also filter by
protocol and route type; legacy dumps retain their unfiltered header semantics.
Kernel-peer connect/getpeername retain netlink
address semantics; non-kernel destinations are unsupported.
`network_packet` owns queued datagram framing and translates requested packet
information and kernel SCM_CREDENTIALS, including ancillary truncation. Protocol
membership and multicast delivery stay separate from native socketpair carriers.
Port identities are unique within each namespace/protocol until the last socket
description closes. User multicast snapshots sender credentials and PID ancestry
at enqueue, so delivery after sender exit cannot adopt a reused PID. Receivers
translate that snapshot in their own PID/user namespaces. Interface name/index,
flags and MTU ioctls use the socket's creation namespace, including the AF_UNIX
control sockets used by libc's `if_nametoindex`.
Flag and MTU setters enter the netlink mutation owner, preserving capability
checks, topology publication and observer notifications. Unsupported flag
changes fail explicitly; read-only flags are not overwritten by legacy ioctls.
Control descriptor selection joins the close/dup table turn, including capture
of a namespace FD, and ends before guest output copying.

Classic `SO_ATTACH_FILTER` programs are validated by the kernel and translated
once by `socket_filter` to account for the private message envelope. Payload
loads, lengths, branches and truncation retain guest-visible offsets; the kernel
filters subsequent deliveries without a supervisor callback. Original-program
queries, replacement, detach and locking belong to the socket description and
survive dup/fork. Unsupported ancillary pseudo-loads and programs requiring an
unavailable scratch register fail explicitly; translation beyond the kernel's
instruction bound is rejected. `test_socket_filter.c` compares native filtering
and `test_netlink_filter.py` checks the guest ABI.

`network_ethernet` supplies CAP_NET_RAW-checked `AF_PACKET` raw/datagram sockets
on the logical fabric. It reuses control queues, BPF and native guest-thread
readiness; it never opens Android packet sockets. Veth, loopback and bridge
forwarding publish real frames with `sockaddr_ll` provenance. An iterative walk
visits each interface at most once per direction, bounding cyclic topologies.
Link-local bridge control frames are not flooded to other ports. Protocol-zero
sockets remain inactive until a protocol is selected. Bindings and memberships
retain interface lifetimes across renames, but not namespace removal/recreation.
Virtual Ethernet links have no hardware multicast filter; memberships are
reference-counted with the socket, as on a software veth device. No FDB learning,
packet mmap/rings, host-interface capture or reconstruction of translated TCP/UDP
as Ethernet frames is claimed. Unimplemented packet options return an error.
These queues are independent of the ordinary TCP/UDP endpoint fast path.
`test_network_ethernet.py` covers raw/cooked delivery, filtering, source identity,
permissions and retained namespace authority with Base and lazy entry;
`test_network_ethernet.c` covers bridge cycles and binding retirement.

Default launches use native networking. Opt-in `--network routed` creates a
launch-owned fabric with an IPv4/IPv6 uplink in its initial namespace. Children start
offline; configured bridge/veth routes grant connectivity. `network_endpoint`
retains creation-namespace provenance, logical bind/peer addresses and private
port translation, never a data OFD. The weak fabric endpoint list unlinks before
final release. Namespace peers can use identical internal ports. Root-fabric
explicit binds (including port zero) and listeners reserve real Android ports
through separate forwarding frontends; automatic outgoing binds do not publish
a listener. Guest backing sockets use ephemeral native ports. Conflicts and
final release belong to the same endpoint/forwarding owners.
Local OUTPUT uses loopback interface metadata, independently of the FIB owner
used for source selection and scoped endpoint binding. DNAT reroutes the
translated destination through the same flow owner. Nftables element deletion
selects by key; an echoed value does not change the match or retain a verdict
target. Both contracts are covered by native network tests and the nspawn
command/publication lifecycle fixture.

`network_io` uses the shared exact-description capture and cancellable IO owner
for control, connect/accept and addressed TCP/UDP calls. No table/fabric turn
spans blocking IO or guest copying. Connect/accept jobs use the existing
completion event and interrupt/reap path; cancellation closes an undelivered
accepted FD. Ordinary stream IO remains native. Shared payload/vector helpers
serve both Unix ancillary transport and routed message IO; native networking
does not allocate these routed owners.

`network_connection` owns mediated TCP handshake completion and its readiness
overlay. The relay retains the actual upstream connect; acceptance by the local
carrier is not reported as a successful destination handshake. Blocking connects
wait through the cancellable IO owner, while nonblocking callers use readiness
and read-once `SO_ERROR`, shared by aliases. Errors returned directly by `connect`
are consumed there rather than reported again through `SO_ERROR`.

Permanent epoll subscriptions retain independent notifications; querying one
observer never drains their shared wake source. Poll/select temporarily wait on
the handshake completion gate, then return to native readiness. Neither this
protocol nor its metadata retains a guest socket OFD or intercepts stream
`read`/`write`. `test_network_connect.py` covers IPv4/IPv6 completion, refusal,
mixed queues, multiple observers, edge/one-shot rearming and native data IO.

Routed `recvmmsg` uses the same native batch receive as Unix sockets, including
`MSG_WAITFORONE`, timeouts, partial completion and cancellation. Endpoint capture
joins the descriptor-table turn; source addresses and packet information are
translated before each completed message is published. The batch retains one
native receive, not a loop of per-message receives. `test_routed_network.py`
checks 128-message IPv4/IPv6 batches, omitted source buffers, packet information,
peek/truncation, deferred socket errors and timeout handling in Base and lazy mode.

`sendto`/`recvfrom` without a peer-address operand also retain the original
kernel call, PC and signal mask, including on routed data endpoints. The
supervisor and both socket adapters share that classification. Netlink and
device-event carriers remain adapted; this shortcut neither translates an
address nor handles ancillary data. Keeping a blocking payload receive outside
the adapter lets a guest debugger interrupt and detach without waiting for
application data.

IPv4 `IP_PKTINFO` and IPv6 `IPV6_PKTINFO` use logical interface and destination addresses on receive,
including a missing `msg_name` and truncated ancillary buffers. Send accepts
local source/interface selection consistent with the supported route; unknown
interfaces, nonlocal sources and unsupported routing return explicit errors.
Native TTL/TOS ancillary settings remain available. `SO_BINDTODEVICE` and
`SO_BINDTOIFINDEX` belong to
the shared endpoint, retains interface identity through rename, and limits route
selection and incoming endpoint lookup. Accepted TCP sockets inherit it. An idle
bound socket can change the binding transactionally, releasing or restoring its
external port reservation; existing direct/relayed flows reject retargeting with
`EBUSY`. Bound flows use the existing relay so link deletion/down is checked;
unbound sockets retain their existing fast path. Unsupported outgoing ancillary
types fail explicitly. Netlink and device-event carriers do not yet support
`recvmmsg`. `SO_PRIORITY` retains the guest value with namespace-scoped
`CAP_NET_ADMIN` checks outside 0..6 and inheritance by accepted TCP sockets.
Guest priorities do not change Android qdisc scheduling; the logical fabric
does not currently implement priority queues. These options are control-plane
metadata, without additional work on ordinary payload reads or writes.

`network_multicast` owns IPv4/IPv6 memberships, output interface, loopback and
hop limits. UDP broadcast and multicast share the existing forwarding worker,
bounded queues and link traversal. Each receiving socket gets an independent
native datagram queue; reuse-address listeners retain their own subscriptions.
Filters and counters run once per namespace ingress, not once per subscriber.
Leave, final close, interface replacement and namespace teardown retire the same
membership owner. Ordinary unicast does not traverse multicast listeners.
`test_network_multicast.py` verifies namespace isolation, fanout, leave/loop,
ancillary information and unicast replies with Base and lazy entry.
This is not packet-level isolation: native ephemeral backing ports remain
reachable, and inherited native descriptions retain authority. Independent
supervisor transfer of these typed network owners is unsupported rather than
silently dropping their annotations.

`network_ip` supplies family-typed addresses while retaining the IPv4 scalar
fast path. `network_route_ip` owns shared address/route codecs, connected-route
deduplication, address lifetimes and route preference. `network_nexthop` retains
single-interface nexthop objects and their route references; unsupported grouped
or resilient nexthops fail explicitly. `network_autoconf` manages temporary IPv6
addresses derived from MANAGETEMPADDR /64 templates. Address expiry, deprecation,
temporary-address rotation and route expiry share the existing forwarding timer;
ordinary packet IO does not scan deadlines or read a clock for these controls.

`network_raw6` supplies namespace-owned raw ICMPv6 control sockets on the same
Ethernet fabric: router solicitation/advertisement transport, neighbor discovery,
local echo, scope, interface membership, traffic class and ancillary metadata. It does not open
Android raw sockets. Userspace networkd can process advertisements and install
SLAAC addresses/routes. This is not a kernel RA client: unsupported `accept_ra`
modes and unimplemented controls still fail explicitly.
It also supports raw UDPv6, including `IPV6_CHECKSUM`, on that fabric.
`network_raw4` provides raw IPv4 transport, `IP_HDRINCL`, checksums, source
selection and broadcast permission. Raw UDP and ordinary UDP share endpoint
lookup, live filtering and retained reverse NAT; replies reach both ordinary
UDP endpoints and admitted raw observers. No packet privileges are requested
from Android. General IP forwarding, fragmentation and arbitrary raw IPv6
protocols are not implied. `test_network_dhcp.py` runs an unmodified dhcpcd in
a separate guest namespace against a fixture server, checking IPv4/IPv6 lease
and renewal, DNS options, installed routes and bidirectional UDP. This exercises
DHCP transport, not a built-in Shroot DHCP server or Android network control.
IPv6 route metrics accept MTU and hop limit, with `QUICKACK=0` retaining native
adaptive TCP acknowledgements. `network_transport` combines route/interface
limits with application socket options at connection or addressed-send preparation.
IPv6 native sockets enforce MTU and hops; TCP also receives an MSS ceiling.
Forwarded sockets use the same owner. Ordinary connected payload IO remains
native. Nonzero IPv4 MTU/hop metrics, locked metrics, forced route QUICKACK and
other unsupported metrics fail rather than being acknowledged without effect.
Changing a route/interface ceiling or hop limit already selected by a connected
native endpoint returns `EBUSY`, with the topology/sysctl update rolled back.
The endpoint does not retain another data FD (which would delay close/EOF) and
ordinary `read`/`write` does not check route generations or refresh socket options.
This is connection-time transport policy, not full Linux live PMTU convergence.
Application transport options are copied to new relay sockets; changing them
while that endpoint has active relay mappings also returns `EBUSY`, rather than
updating only the local leg. Unmediated native sockets remain independently mutable.
UDP `AF_UNSPEC` disconnect resets the selected source and private flow mapping;
the original explicit binding remains distinct from the previous route's source.
External IPv6 TCP/UDP uses native backing sockets and requires an IPv6 route
permitted to the executor by Android/VPN policy; Shroot does not alter that policy.

### Nftables and Flow Forwarding

`network_filter` owns a lazy namespace-local ruleset and generation;
`network_nft` adapts `NETLINK_NETFILTER` requests to it. New namespaces begin
with an empty catalogue. Retained sockets still refer to their creation
namespace. No operation opens Android's netfilter socket or changes its firewall.
Queries and mutations require CAP_NET_ADMIN in the namespace's user owner.

The implemented ABI covers generation queries, tables, base/regular chains,
rules, exact-key/interval sets, verdict maps and register-value maps, including dumps, deletion,
userdata and handles. Ordered set lookup uses binary search; interval ends,
adjacent ranges and the maximum-key sentinel retain their wire representation.
`network_nft_objects` owns object operations; `network_nft_expression` validates
register use and translates the supported expression ABI to typed operations.
Complete begin/end batches use a private
snapshot; stale generation, invalid attributes, unsupported operations, encoding
failure or failed response publication cannot commit a partial batch. Only a
successful batch advances the generation. Mutation replies and state replacement
share the fabric lock; queries and ordinary data IO never clone the catalogue.
Rollback preserves individual request acknowledgments; rejected mutation echoes
and the successful end acknowledgment are not published. Value maps support up
to 16-byte keys and 64-byte values, including overlapping source/destination
registers and IPv4/IPv6 address-port tuples. External listener publication requires
finite exact-key map destinations; interval-map keys used to select external
ports or addresses are rejected explicitly.
Rtnetlink additionally publishes asynchronous topology notifications before its
ACK; its observed-state rollback boundary is described above.

Requests and replies are bounded at 64 KiB in the mapped adapter slot. A ruleset
has at most 256 tables, 256 chains, 1024 rules, 64 expressions per rule and 1024
elements per set. Overflow fails explicitly. Incomplete batches are rejected,
not retained across socket calls. Loops, excessive jump depth and transitive
NAT/FIB placement in incompatible hooks are rejected before commit, including
chains reached through verdict maps. These checks do not run on packet IO.

`network_filter_program` evaluates immediate/verdict, metadata, supported IPv4/IPv6
address/protocol and TCP/UDP port or ICMP type/code payload, compare/range/bitwise,
conntrack state/mark/status, local/source-route classification, reverse-path and
packet-type predicates, set, verdict-map and register-value lookup, single-target
DNAT/SNAT and masquerade operations. ACCEPT ends a chain, not a hook; later
base chains may deny it. RETURN applies the base policy. Rule counters and
optional base-chain policy counters retain their values in transactional
snapshots. Cached flow decisions retain counter references for one generation,
without walking rules for each transfer. A base-chain counter applies only when
evaluation reaches its policy, not an explicit ACCEPT/DROP rule. Counters measure
logical UDP datagrams/TCP chunks and payload bytes, not kernel packets, headers,
retransmissions or connection attempts rejected before relay admission.
TCP REJECT is supported through nft `reject with tcp reset` and revision-zero
xtables REJECT/TCP_RESET for IPv4/IPv6. Admission requires an explicit TCP
predicate in the same rule and a filter INPUT/FORWARD/OUTPUT context. Rejection
returns ECONNREFUSED during logical connection admission; relayed new and
established native streams close with SO_LINGER reset, so peers observe RST
rather than waiting for a DROP timeout. Existing direct streams retain the
ordinary policy-update rule: an update requiring mediation returns EBUSY.
UDP/ICMP REJECT modes remain explicitly unsupported, including general
`icmpx admin-prohibited`. UID 2000 cannot inject arbitrary host ICMP errors to
external peers; dropping an already received datagram is not an implementation
of REJECT. Logging, queues, dynamic sets, flowtables, unsupported table flags,
monitoring groups and other netfilter subsystems also return errors, not fake
success. Stock firewalld configurations requiring those modes remain uncertified.
The iptables-nft rule-compat attribute is validation metadata, not a packet
predicate; expressions still supply the matches. Revision-zero TCP/UDP
matches become typed transport predicates with inclusive source/destination
port ranges and inversion, without altering nft registers. DNAT/SNAT targets
(revisions 0, 1 and 2)
translate a single address/port into the same typed NAT operation. Revision
queries advertise these supported adapters and distinguish an absent extension
(`ENOENT`) from an unsupported revision (`EPROTONOSUPPORT`). This lets clients
negotiate an older supported ABI without misdiagnosing a missing module.
Revision-zero ICMP/ICMPv6 matches retain type/code bounds and inversion;
`pkttype` and `rpfilter` use the common packet and route model. FIB queries share
that model for address type, output interface/name and interface presence.
TCP flags/options, NAT address/port
ranges, randomization and other xtables extensions are rejected. Rule/chain deletion accepts their dumped metadata but
uses the live handle/reference graph, never the caller's stale use count.

`network_flow` records logical OUTPUT/PREROUTING/FORWARD/POSTROUTING/INPUT steps,
original/translated tuples and reverse established traffic through bridge/veth
topology. Each translating step retains its before/after tuple and hook priority.
A native listener's accepted local address remains local for FIB queries in its
owning namespace, even when Android's address is not a guest interface address.
This authority is attached to that flow, not a global address list. DNAT changes
the queried tuple; raw ingress and child namespaces do not inherit ownership.
Revalidation replays the retained mapping between base chains, not before the
entire hook: early raw rules see wire addresses, later filters see translated
addresses. Reverse translation uses the corresponding destination/source NAT
hook priority. A retained flow does not reselect NAT after an allocating rule
changes; filtering still uses the current ruleset. Removing a publication or
namespace still closes the flows owned by it. The admission generation
also preserves a binding, including no NAT, when rules change before the first
payload. This is flow-level conntrack, not emulation of kernel packet queues or
TCP state tracking.
`network_forward` owns real frontend ports and a lazy epoll reactor,
separately from namespace/ruleset storage. It never retains a guest data OFD.
Native Android loopback-to-loopback connections enter the logical OUTPUT path,
just like guest-local connections, not PREROUTING. Both OUTPUT and INPUT policy
still apply; DNAT cannot silently bypass a local proxy through a private bridge.
Other native arrivals retain PREROUTING. This classification occurs only when
admitting a flow, not on each payload transfer.
NAT publications are inferred from finite PREROUTING/OUTPUT port predicates by abstract evaluation,
not a trial packet with guessed source fields. Unbounded publication is rejected.
Every real flow still evaluates the full predicates, including source addresses.
Port reservations are prepared before ACK; failed encoding/publication rolls
them back. A bound-only TCP socket retains its native reservation while NAT
uses a separate acceptor at that address. Rollback or rule removal closes the
acceptor and restores bind-only state without releasing the port. A real guest
`listen` independently retains acceptance after NAT removal. Both bind-before-NAT
and NAT-before-bind use this common frontend lifetime; no per-application path
or extra per-payload syscall is involved.

Direct unfiltered TCP and outgoing native UDP keep native connected IO only when
neither direction has potentially denying hooks. Policy updates evaluate each
retained tuple in both directions, including first-payload and established
states. Unrelated rules do not revoke these streams. Denying policy or reached
mark mutations return atomic `EBUSY`: an update cannot retroactively move
a kernel stream into a relay. Counters do not change admission, so adding them
does not revoke an existing native stream. They account for observed mediated
traffic, not payload on already native connections; this is not complete kernel
packet accounting. New flows reaching counters use mediation. Direct path
records are bounded per endpoint and
retained until socket close, including accepted stream owners. Relayed flows
retain their original NAT binding, as do direct flows. Adding NAT chains and
counters therefore cannot revoke an existing unmediated flow; non-NAT filter
changes still undergo both-direction admission checks. Relayed flows
cache decisions by fabric policy generation and revalidate in both directions
after rules/topology changes. Private UDP uses the same relay even without
filter rules: its queued source identity must survive sending-socket closure.
The retained tuple does not authorize delivery to a reused native port; closed
peers cannot receive new traffic. Addressed UDP reuses its established translation
instead of interpreting NEW/NAT for each send. Removed/down interfaces and
disabled forwarding stop mediated traffic; established direct native IO is not
intercepted by such topology updates.

The reactor handles nonblocking native sockets, backpressure and TCP half-close.
TCP has two 64 KiB buffers per mediated flow. UDP preserves datagrams, queues
temporary EAGAIN up to 64 datagrams/256 KiB per direction, and expires idle
mappings after 30 seconds, including retained identity for unread queued data.
This is a bounded flow lifetime, not indefinite native datagram metadata retention.
Queue exhaustion drops datagrams, never reports a
fake protocol reply. Limits are 512 listeners and 1024 mediated flows per fabric.
Idle listeners have no polling timer. Weak namespace/event identities avoid
ownership cycles and stale FD-reuse events; namespace/endpoint teardown and
reactor join release all associated resources.

This is flow-level compatibility, not native packet conntrack or a host firewall.
Backing ports remain reachable outside the logical policy. There is no Android
firewall mutation, guest daemon, external library or shared-startup prerequisite.
The native model tests, stock nft traffic fixture and Podman bridge fixture
separately verify rules, data IO and lifecycle. Docker 29's nftables backend runs
through these same operations with userland-proxy disabled; its explicit
iptables-disabled userland-proxy profile remains a separate comparison.
`AF_BRIDGE` link queries expose bridge ports, hairpin mode and port isolation.
Ethernet forwarding and routed IP flows share the same bridge-port admission:
two isolated ports cannot exchange traffic, while delivery to the bridge host
and non-isolated ports remains available. Retained mediated flows recheck both
directions after policy changes. An update that would revoke an already opened
native direct stream fails atomically with EBUSY; it never reports an unenforced
policy as installed. Ordinary unfiltered, non-isolated streams retain native IO.
Detaching a port or moving it to another namespace clears its bridge settings.

## Namespace-Owned Sysctls

`network_sysctl` supplies the managed namespace's
`ip_unprivileged_port_start`, `ping_group_range`, `ip_local_port_range` and
`ip_forward`, plus per-interface `route_localnet`, `rp_filter` and `arp_notify`.
Validated updates share one namespace lock, including overlapping port bounds.
Opening captures the network owner; subsequent setns does not retarget that FD.
Writes require CAP_NET_ADMIN in its user owner, without touching host sysctls.
Host-network views retain native access and errors. Logical unprivileged-port
and ephemeral-range settings also govern routed endpoint allocation. Ping group
configuration does not imply an ICMP transport.
`bridge-nf-call-iptables=1` describes the shared IPv4 bridge filtering path;
disabling this separate hook model is unsupported, not silently accepted.
Forwarding starts enabled only in the explicitly selected routed launch root.
New/copied network namespaces default to zero. The boolean gates external
destination selection through a different routing owner, without blocking that
owner's own connections or intercepting data IO on established streams.
Interface controls resolve a name once to a namespace-local policy lifetime.
Rename retains the open description; deletion or movement out of its owner
yields ENODEV even after the same interface returns. Name reuse cannot redirect
it. Topology rollback restores the prior policy lifetime alongside its values.
`conf/default` initializes newly created interfaces;
`conf/all` participates in ingress policy. Strict and loose reverse-path checks
and loopback-source/destination policy apply at logical routing boundaries.
The fabric resolves live addresses directly, without an ARP neighbor cache;
`arp_notify` does not emit packets onto Android's physical network.

`tcp_keepalive_time`, `tcp_keepalive_intvl` and `tcp_keepalive_probes` belong to
the same namespace owner, with Linux defaults 7200/75/9. Socket creation applies
them to the native TCP description; accepted sockets inherit the listener's
options. Explicit per-socket overrides remain kernel-owned. No additional
read/write interception or retained data FD is introduced.
Changing a default while that namespace retains TCP endpoints returns `EBUSY`:
live propagation requires native description custody, not a cached value that
existing connections ignore. Writing the existing value is allowed. Values
outside the native TCP option domain (1..32767 seconds, 1..127 probes) return
`EOPNOTSUPP`; malformed/overflowing sysctl values return `EINVAL`.
`test_network_endpoint.c` checks namespace independence, native options and
atomic admission; `test_network_sysctl.py` covers proc IO, accepted sockets,
explicit overrides and release under Base and lazy entry.
Mounted proc enumerates only the selected namespace's interfaces and supported
controls. Unknown controls are not implemented.

Rtnetlink veth creation can assign a generated local name and create its peer
directly in a namespace selected by an owned namespace FD. Both endpoints share
one topology transaction, including rollback when response publication fails.
Renaming is independent of cross-namespace move support. IPv4/IPv6 routes retain
prefix, metric, preference, interface/nexthop and lifetime for lookup, queries,
notifications and matching deletion. Multiple routes use longest-prefix and
metric selection; arbitrary policy-routing table mutation remains unsupported.

Native rlimits retain the launch identity's real kernel ceilings. Virtual root
does not grant Android CAP_SYS_RESOURCE: requests exceeding a hard limit keep
the kernel's EPERM instead of reporting a fictitious successful increase.
Container launch limits must fit the inherited ceilings; the Podman fixture
records those ceilings and offers an explicit inherited-limit diagnostic mode.

Mounted proc controls are selected on the filesystem worker and retain a live
mount anchor. Readonly policy applies to future opens and already opened writers,
including after lazy detach. Ordinary proc spelling uses the same typed provider
without a logical mount anchor. The common descriptor host supplies dup/fork,
offsets and positional IO; numeric writes at a nonzero offset consume bytes without
changing the value. No additional service thread or per-IO FD is created.

`network_proc` supplies `tcp`, `tcp6`, `udp` and `udp6` through that same proc
descriptor contract. It snapshots only live tracked endpoints belonging to the
selected logical network namespace. Logical addresses, ports and guest owner IDs
come from the endpoint model; the matching native socket inode supplies kernel
state and queue counters. This is not a dump of Android's host sockets. The
IPv4 and IPv6 tables project their corresponding live endpoint families.
Unowned terminal entries such as TIME_WAIT sockets are not synthesized.
An open retains its selected namespace across setns; dup shares the offset,
and reading from offset zero refreshes the snapshot. Unmanaged host networking
keeps the native proc path and its permission failures.

## Namespace-Owned Sysfs

`sysfs_view` retains the network namespace selected at legacy mount or fsopen.
Mount authorization belongs to that network namespace's user owner. The limited
tree contains the selected namespace's `class/net` inventory, relative device/subsystem links,
identity/MTU/flags/operstate/carrier attributes and `fs/cgroup` for separate mounts.
Bridges expose read-only `bridge/stp_state=0`: forwarding has no STP state
machine, and netlink does not accept enabling one. Bridge-port sysfs attributes
remain unavailable; callers may fall back to their readiness delay.
`network_model` supplies the same atomic link snapshot to sysfs, ioctl and
netlink, including veth peer indices and operational state. A veth has carrier
only when both ends are up; a bridge needs an active port. Loopback retains
Linux's UNKNOWN state while up. Link-state changes notify affected peers and
bridges through the existing netlink source. Mounted views do not follow the reader's later setns.
Topology revisions refresh names during sysfs lookup/enumeration, without a
background observer or changes to ordinary filesystem paths. Rename preserves
attribute inode identities; removal and namespace moves retire open controls,
even when an interface with the same index later returns. Retired descriptors
retain metadata but return ENODEV for data. Only live links occupy the inventory.

The private name tree supplies stable native inode identities and ordinary
directory enumeration, not attribute data or Android device access. Typed control
files use the common IO/cursor contract; retained mount origins keep both the
tree and network owner alive. Bind/copy/detach share ordinary mount ownership.
Closing the final owner retires the temporary tree. An unknown attribute returns
ENOENT; unimplemented mutations and host-network sysfs return EOPNOTSUPP.
Cross-supervisor export is rejected rather than flattening the provider into a
native bind. No host mount, background refresh or per-read descriptor is needed.
