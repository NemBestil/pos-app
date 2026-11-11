# POS 1.3 and 1.4 compatibility

App 1.4.8 builds on the published `apk-1.4.7` source (`b0007f46d0a9dce08b9fe2e70d85583174904e4d`), adding Samport v1 and manual IP detection without changing installation discovery, native whoami or socket protocols. There is no alternate-backend resolver or native whoami endpoint selection. No APK update is required for the shared domain or server-side routing.

## Discovery and launch

`app/app.vue` first requests `https://<id>.nbpos.eu/api/public/installation`, retaining the original `https://<id>.pos3.nemkasse.com` fallback. `NGROK` uses only `https://nbpos3.ngrok.dev`. Verification, saved-installation metadata refresh and launch all use this resolver. The cluster service-router sends explicitly registered POS 1.4 installations to Cloudflare before considering a same-slug POS 1.3 installation.

`capacitor.config.ts` already allows `*.nbpos.eu`. `BridgeReinjector.java` uses that list for native bridge injection, so loading POS 1.4 at the same origin retains native plugin access. The hosted POS supplies its own origin to `ForwarderService.start`; native WebSocket/HTTP forwarding and customer-display URLs follow that installation.

The selected installation's app-version gate still applies. POS 1.4 currently publishes minimum app version `1.4.7` and target/inclusive maximum `1.4.8`; the original APK remains supported for v2 terminals. POS release numbers and Android app release numbers are independent. Do not increase the app requirement merely because the backend moves to Cloudflare.

## Native contracts

Both POS generations retain these contracts, already supported by app 1.4.7:

| Feature                            | Contract                                                                                                |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------- |
| Installation/version/branding      | `GET /api/public/installation`                                                                          |
| Android service credentials        | `POST /api/auth/android-sync-token`                                                                     |
| Native service WebSocket           | `/api/_internal/android-forwarder`, bearer token, protocols 3/4                                         |
| Payment terminals                  | `terminal.*`, signed requests/responses, delivery/lifecycle tokens and existing reconciliation          |
| LAN/Bluetooth/USB receipt printing | `print.job` / `print.result` / `print.result-ack`, native-cut protocol 4                                |
| Notifications and sounds           | `takeaway.event`, `table-booking.event`, `client.state` and `configuration.snapshot.notificationSounds` |
| Browser live updates               | `/api/query-invalidations`                                                                              |

See [receipt delivery](receipt-printing.md), [terminal monitoring](payment-terminals.md) and [USB access](usb-access.md) for hardware behavior.

## Whoami compatibility

App 1.4.7 hardcodes `https://whoami.nemkasse.com` in `ForwarderService.java` and sends its signed token as `network.location`. It ignores the optional `configuration.snapshot.networkAttestationUrl` field. Serving POS 1.4's own `/api/public/whoami` therefore does not change where this native service fetches its token. The hosted browser uses the configured same-origin endpoint independently of the APK.

Production POS 1.4 retains the verified POS 1.3/legacy-whoami signing key through private `WHOAMI_SIGNING_TOKEN` on its control and customer Workers. Both the external native report and same-origin browser report therefore validate, including the existing signature, issuer and expiry checks. Native reports populate the device overview's public IP/city/country. The key stays on the servers; it is never supplied to an APK or browser.

App 1.4.7 works with this shared signing setup without a native change. Keep the external signer available for installed apps and coordinate future key rotation across both signers and verifiers. Changing only the control Worker's key does not update existing customer Workers. Different keys cause a rejected `network.location` report without closing the registered socket or disrupting printing, terminal requests or notifications; never bypass signature checks. Do not describe the original release as supporting configurable native whoami URLs.

## Original 1.4.7 baseline verification

After rollback, compare application code, native sources, Capacitor configuration, dependencies and Android settings with `apk-1.4.7`; documentation is the only remaining source difference. Generate the Nuxt shell, sync Capacitor, apply `scripts/android-patch.sh` and compile Android with JDK 21. Generated Capacitor configuration must also contain only the original allowed domains.

Isolated runs of the POS repository's existing compiled-worker checks verified both outcomes: a report with a different signing key is rejected, and a current legacy-signed report with the shared key populates native device IP/location. Configuration, signed terminal identity/replay checks, native receipt delivery/ACKs and takeaway/booking notifications passed on protocols 3/4. POS resources and hardware were simulated locally; only the public legacy-whoami report was fetched from production. Live signatures from legacy-whoami, a POS 1.3 hostname and the POS 1.4 Koi endpoint were also verified with the same key after the private secret update. This does not replace testing a physical tablet's printer, terminal, notifications or secondary display.

The original rollback did not publish an APK. App 1.4.8 now adds the terminal transport/setup changes documented in [terminal monitoring](payment-terminals.md#samport-v1-and-app-148); publish it before the hosted target changes.
