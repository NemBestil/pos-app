# LAN printer discovery and Xprinter IP recovery

The existing `NetworkPrinter` bridge keeps its normal IP scan and event payloads.
Its optional Xprinter extension is implemented in
`resources/android/NetworkPrinterPlugin.java` and `XprinterDiscovery.java`.

`status()` advertises `supportsXprinterRepair: true`. New hosted POS opts in with
`scan({scanId, includeXprinterDiscovery: true})`; omitted/false opt-in preserves
the original scan for old POS. Older APKs omit the capability, so new POS retains
the old complete-only scanner without calling repair.

Extended scanning sends the first `XP0001FIND` broadcast before the ordinary sweep
and collects replies for five seconds in parallel. It keeps the latest packet per
MAC, coalesces ordinary/UDP discoveries by normalized MAC, and emits progressive
`scanResults`. The legacy `printers` array always contains ordinary results only;
UDP-only candidates are separate `udpPrinters`, so old POS cannot accidentally
persist a wrong-subnet IP. Cancelled scans expose no repair candidates. UDP failure
sets advisory `udpUnavailable` and leaves normal scanning running.

`repairXprinter({mac, macConfirmed: true})` accepts only a UDP-only candidate from
a completed scan. The hosted UI requires an explicit comparison with the network
printout (four quick FEED presses per the requested operator procedure). Native
code consumes the cached candidate, freshly rediscovers the MAC, and checks its
announced TCP port before changing anything. A healthy connection is returned
without SAVE. Otherwise one targeted `XP0001SAVE` requests automatic IP, followed
by rediscovery/TCP verification for up to 90 seconds. Error or uncertain timeout
requires a new full scan before another write. Cancellation closes the socket,
stopping observation without attempting to undo a previously sent request.

The protocol codec preserves MAC, static address/mask/gateway and print port from
FOUND. SAVE is 33 bytes (`XP0001SAVE` + FOUND `[11:33]` + `01`); the embedded field
remains `22 00`. Do not append NUL/newline or send ASCII hex. Preserve the print
port's little-endian bytes. Findings originated in the official Android SDK 3.5.8
and a real XP-80 test on 13 November 2026, recorded in the `xprinter-test` workspace.

UDP sockets bind wildcard on an ephemeral source port and are bound individually
to an IPv4 Wi-Fi/Ethernet `Network`; VPN/cellular networks are excluded. The
printer broadcasts replies back to the request's source port. TCP verification
uses the same network and sends no receipt bytes. A temporary Wi-Fi multicast lock
is released on completion/cancellation. `scripts/android-patch.sh` owns the normal
`ACCESS_NETWORK_STATE` permission and source symlinks; no host IP/subnet change,
raw Ethernet access or root is required. The current target SDK is 36. Revisit
Android's `ACCESS_LOCAL_NETWORK` runtime permission when raising target to 37+.

Success means that the exact MAC was rediscovered and its print port connected.
It includes raw `reportedDhcp`, `automaticIpRequested`, and
`persistentDhcpVerified: false`. On the tested XP-80 the DHCP-request byte obtained
a usable IP but DHCP later reported disabled. Never translate connectivity success
into a claim of persistent DHCP. The printer may restart and print its acquired IP.

This feature does not change app version requirements, forwarder/socket contracts,
print delivery, native origins, terminals or notifications. It is local source work,
not an APK publication. Either the APK or hosted POS can roll out first.

Validation: generate/copy the shell, sync Capacitor, run `scripts/android-patch.sh`,
then compile/assemble with JDK 21. Packet fixtures and an isolated emulator build
verify the codec and bridge/UI; actual broadcast behavior, selected-MAC changes,
multiple printers, Wi-Fi/Ethernet/cancellation/timeout, persistence after reboot and
four old/new POS/APK combinations still require hardware acceptance. The matching
POS repository's `docs/xprinter-network-repair.md` is the detailed continuation
plan and ownership/contract guide. No synthetic-printer fixture or UI test harness
is shipped in application source.

Local verification on 14 November 2026 passed JDK 21 profile compilation/assembly,
five captured-packet checks and the real bridge on an Android 9 emulator. Two
synthetic printer identities exercised selected-MAC SAVE, fresh discovery/TCP
verification, the confirmation gate, consumed-candidate rejection, legacy opt-out,
already-reachable no-write behavior and scan cancellation. The separate UI fixture
checked confirmation, progress, success/error, deduplication, capability fallback,
translations and phone/tablet layout. These simulations do not establish broadcast
delivery through a physical AP or real XP-80 DHCP persistence.
