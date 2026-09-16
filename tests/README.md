# HAP Core Regression Tests

From the repository root, with Java and Groovy 2.4 installed:

```sh
groovy tests/hap-core-mdns.groovy
```

The standalone script loads the discovery and reconnect methods from the actual
library source and stubs Hubitat APIs. It checks multicast query construction,
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
HAP Core 0.10.19 supports instance-specific multicast SRV/TXT queries, tested
with a single received packet from each thermostat on the Ethernet interface.
The suite includes the rejected Homebridge packet and both targeted replies.

Update the shared library and the thermostat driver, then set **HomeKit mDNS
service name** to the exact advertised instance name (for this installation,
`Upstairs` or `Downstairs`) and click **Save Preferences**. This is not the DNS
hostname or a Hubitat device label. Successful discovery remembers the instance
for later queries. A configured name takes precedence; update it if the service
is renamed. No pairing credentials or fixed port values are changed.