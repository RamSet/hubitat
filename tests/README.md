# HAP Core Regression Tests

From the repository root, with Java and Groovy 2.4 installed:

```sh
groovy tests/hap-core-mdns.groovy
```

The standalone script loads the discovery and reconnect methods from the actual
library source and stubs Hubitat APIs. It checks IP-directed and multicast query construction,
DNS compression, record identity and address association, unrelated services,
malformed replies, response-object extraction, callback state, cached-port
replacement, initial-pairing IP selection, relocation, and rediscovery after
handshake timeouts. It includes actual multicast replies captured from both
ecobees after their direct-to-IP discovery queries timed out.
No hub, network access, or thermostat credentials are needed to run the tests.

The ecobee fixtures distinguish the dynamic `_hap._tcp` HomeKit ports from the
separate `_ecobee._tcp` service on port 1201. The fixture ports are examples, not
defaults to configure on a device. Hubitat sandbox compilation and callback
delivery still need verification on a hub. A timeout now reports callback and
hex-payload counts to distinguish absent replies from decoding/selection issues.

## First-Reply Discovery

UDP HubAction discovery can deliver only the first response. A fast Homebridge
can therefore prevent a general `_hap._tcp` browse from reaching either ecobee.
HAP Core supports instance-specific multicast SRV/TXT queries, tested
with a single received packet from each thermostat on the Ethernet interface.
The suite includes the rejected Homebridge packet and both targeted replies.

Update the shared library and the thermostat driver, then set **HomeKit mDNS
service name** to the exact advertised instance name (for this installation,
`Upstairs` or `Downstairs`) and click **Save Preferences**. This is not the DNS
hostname or a Hubitat device label. Successful discovery remembers the instance
for later fallback queries. A configured name takes precedence; update it if the
service is renamed. No pairing credentials or fixed port values are changed.

## Shared-Library Compatibility (0.10.20)

The 0.10.17-0.10.19 changes were not entirely behavior-neutral bug fixes.
Service-record association and failure counting fix defects, but making multicast
the primary transport changed the contract for every including driver. Requiring
TXT/A records also rejected previously usable unicast replies, normal discovery
could update the configured IP, and handshake retry timing changed.

Version 0.10.20 retains the fixes with these compatibility constraints:

- Every operation starts with the original PTR query to `settings.ip:5353`,
	regardless of configured or learned service names. Existing drivers do not need
	the new preference to keep using IP-directed discovery.
- The original two unicast retries remain. A configured or learned instance adds
	at most one targeted multicast attempt after those retries fail. This is an
	intentional fallback extension, not a replacement for IP-based behavior.
- Unicast accepts a unique HAP SRV without TXT/A records when the callback sender
	matches the configured IP. Conflicting identities and unrelated services are
	still rejected; multicast retains strict identity/IP association.
- Ordinary discovery only updates the port and learned instance, not the IP.
	Only the existing paired-ID relocation path can change the IP. Its general
	browse remains throttled to once per 30 minutes and can find renamed services.
- Cached-port precedence and fallback remain unchanged. Invalid optional names
	cannot stop IP discovery or cached-endpoint recovery.
- Handshake retries retain the original 30/60/90/120-second cadence and cap,
	while counting failures so every third failure triggers rediscovery.
- The four-second UDP timeout is unchanged; the scheduled watchdog runs at five
	seconds to allow the callback to finish. The watchdog is armed before sending.

Tests exercise the shared operation dispatch for pairsetup, discover, read, write,
unpair, and live modes, plus success, timeout, relocation, and forgetting state.
They do not execute complete encrypted sessions or prove compatibility with every
accessory. Live Hubitat validation remains required for both a working unicast
accessory and an ecobee using targeted fallback.