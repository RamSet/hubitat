import groovy.transform.Field

@Field String coreSource = new File('libraries/hap-core/hap-core.groovy').getText('UTF-8')

String slice(String start, String end) {
    int first = coreSource.indexOf(start)
    assert first >= 0
    int last = coreSource.indexOf(end, first)
    assert last > first
    coreSource.substring(first, last)
}

Map harness(boolean transport = false) {
    def context = [state: [services: [:], discoveredPort: 38607],
                   settings: [ip: '192.0.2.53', accPairingId: '11:22:33:44:55:66'],
                   scheduled: [:], updates: [:], connects: [], discoveries: [], health: [], commands: [], dispatched: []]
    def variables = new Binding([
        state: context.state, settings: context.settings,
        RE_BACKOFF_SEC: [60, 120, 300], OFFLINE_AFTER_FAILS: 2,
        SWEEP_INTERVAL_SEC: 1800, RELOCATE_MAX_TRIES: 3, now: { -> 3600000L },
        log: [warn: { message -> }, error: { message -> throw new AssertionError(message.toString()) }],
        interfaces: [rawSocket: [close: { -> }]],
        device: [updateSetting: { key, value -> context.updates[key] = value.value }],
        runIn: { delay, handler -> context.scheduled[handler] = delay },
        unschedule: { handler -> context.scheduled.remove(handler) },
        logInfo: { message -> }, rep: { message -> }, sendEvent: { event -> },
        setHealth: { value -> context.health << value }, isPaired: { -> true },
        mdnsThen: { operation -> context.discoveries << operation },
        sendHubCommand: { command -> context.commands << command },
        liveConnect: { -> context.connects << context.state.discoveredPort },
        pairConnect: { -> context.dispatched << ['pairsetup', null] },
        hapStart: { operation, body -> context.dispatched << [operation, body] },
        parseLanMessage: { message -> [payload: message] }
    ])
    String methods = 'import groovy.transform.Field\n@Field static final String MDNS_PTR_QUERY = "000000000001000000000000045f686170045f746370056c6f63616c00000c8001"\n' +
        slice('def relocateCallback(message)', '// ===== pair-setup') +
        slice('private int reBackoff()', 'void liveConnect()') +
        slice('def verifyWatch()', '// HELD SESSION') +
        slice('void clearLocalPairing()', '// byte-level chunked') +
        '\nbyte[] hex(String value) { value.decodeHex() }\nString hx(byte[] value) { value.encodeHex().toString() }\n'
    def loader = new GroovyClassLoader()
    if (transport) {
        loader.parseClass('''
            package hubitat.device
            enum Protocol { LAN }
            class HubAction {
                enum Type { LAN_TYPE_UDPCLIENT }
                enum Encoding { HEX_STRING }
                String action
                Map options
                HubAction(String action, Protocol protocol, Map options) {
                    this.action = action
                    this.options = options
                }
            }
        ''')
        methods = slice('String mdnsQuery()', 'def relocateCallback(message)') + methods
    }
    context.core = new GroovyShell(loader, variables).parse(methods)
    context
}

byte[] dnsName(String value) {
    def output = new ByteArrayOutputStream()
    value.split('\\.').each { label ->
        byte[] encoded = label.getBytes('UTF-8')
        output.write(encoded.length)
        output.write(encoded)
    }
    output.write(0)
    output.toByteArray()
}

byte[] srvData(int port, String target) {
    def output = new ByteArrayOutputStream()
    def data = new DataOutputStream(output)
    data.writeShort(0)
    data.writeShort(0)
    data.writeShort(port)
    data.write(dnsName(target))
    output.toByteArray()
}

byte[] txtData(List<String> entries) {
    def output = new ByteArrayOutputStream()
    entries.each { entry ->
        byte[] encoded = entry.getBytes('UTF-8')
        output.write(encoded.length)
        output.write(encoded)
    }
    output.toByteArray()
}

String packet(List records, String question = null) {
    def output = new ByteArrayOutputStream()
    def data = new DataOutputStream(output)
    data.writeShort(0)
    data.writeShort(0x8400)
    data.writeShort(question ? 1 : 0)
    data.writeShort(records.size())
    data.writeShort(0)
    data.writeShort(0)
    if (question) {
        data.write(dnsName(question))
        data.writeShort(33)
        data.writeShort(1)
    }
    records.each { record ->
        data.write(record.owner instanceof byte[] ? record.owner : dnsName(record.owner))
        data.writeShort(record.type)
        data.writeShort(0x8001)
        data.writeInt(record.containsKey('ttl') ? record.ttl : 120)
        data.writeShort(record.data.length)
        data.write(record.data)
    }
    output.toByteArray().encodeHex().toString()
}

List accessory(String instance, String host, String ip, int port, String identity) {
    String owner = instance + '._hap._tcp.local'
    [
        [owner: owner, type: 33, data: srvData(port, host)],
        [owner: owner, type: 16, data: txtData(['sf=0', 'id=' + identity])],
        [owner: host, type: 1, data: ip.split('\\.').collect { it.toInteger() } as byte[]]
    ]
}

def ipContract = harness(true)
ipContract.settings.mdnsServiceName = 'Upstairs'
ipContract.state.mdnsInstance = 'old-name._hap._tcp.local'
ipContract.core.mdnsThen('live')
assert ipContract.commands[0].options.destinationAddress == '192.0.2.53:5353'
assert ipContract.commands[0].action == '000000000001000000000000045f686170045f746370056c6f63616c00000c8001'
println 'PASS: configured and learned names do not replace the original IP-directed first query'

def retry = harness()
retry.core.startLive()
assert retry.connects == [38607]
(1..3).each { attempt ->
    retry.core.verifyWatch()
    assert !retry.state.reFails
    assert retry.state.vtry == attempt
    assert !retry.health
    assert retry.scheduled.startLive == 30 * attempt
    retry.core.startLive()
}
assert retry.discoveries == ['live']
retry.state.sess = true
retry.core.verifyWatch()
assert retry.state.vtry == 3
assert !retry.health
println 'PASS: handshake failures trigger rediscovery; established sessions do not count as failures'

def upstairs = accessory('ThermostatOne', 'thermostat-one.local', '192.0.2.53', 46557, '11:22:33:44:55:66')
def downstairs = accessory('ThermostatTwo', 'thermostat-two.local', '192.0.2.52', 40649, '66:55:44:33:22:11')
def ecobee = [owner: 'ThermostatOne._example._tcp.local', type: 33, data: srvData(1201, 'thermostat-one.local')]
def discovery = harness()
assert discovery.core.parseMdns(packet(upstairs + [ecobee])).port == 46557
def combined = packet(upstairs + downstairs + [ecobee])
assert discovery.core.parseMdns(combined, '11:22:33:44:55:66') ==
    [ip: '192.0.2.53', port: 46557, sf: 0, id: '11:22:33:44:55:66', instance: 'thermostatone._hap._tcp.local']
assert discovery.core.parseMdns(combined, '66:55:44:33:22:11') ==
    [ip: '192.0.2.52', port: 40649, sf: 0, id: '66:55:44:33:22:11', instance: 'thermostattwo._hap._tcp.local']
assert !discovery.core.parseMdns(combined).port
assert !discovery.core.parseMdns(combined, '00:00:00:00:00:00').port
assert discovery.core.parseMdns(packet((upstairs + downstairs).reverse()), '11:22:33:44:55:66').port == 46557
println 'PASS: service, identity, address association, and record ordering'

String owner = 'Upstairs._HAP._TCP.local'
def compressed = packet([[owner: 'c00c'.decodeHex(), type: 33, data: srvData(46557, 'thermostat-one.local')]], owner)
assert discovery.core.parseMdns(compressed).port == 46557
def compressedTarget = packet([
    [owner: owner, type: 33, data: '00000000b5ddc00c'.decodeHex()],
    upstairs[2]
], 'thermostat-one.local')
assert discovery.core.parseMdns(compressedTarget).ip == '192.0.2.53'
assert !discovery.core.parseMdns(packet([[owner: 'c00c'.decodeHex(), type: 33, data: srvData(1201, 'host.local')]])).port
assert !discovery.core.parseMdns(packet([[owner: owner, type: 33, ttl: 0, data: srvData(46557, 'host.local')]])).port
assert !discovery.core.parseMdns(packet([[owner: owner, type: 33, data: '000000000001'.decodeHex()]])).port
assert !discovery.core.parseMdns(packet(upstairs + [[owner: owner, type: 16, data: 'ff00'.decodeHex()]])).port
for (int length = 0; length < combined.length(); length += 2) {
    assert !discovery.core.parseMdns(combined.substring(0, length)).port
}
println 'PASS: compression, pointer loops, goodbye records, and malformed/truncated packets'

discovery.state.afterMdns = 'live'
discovery.scheduled.mdnsTimeout = 4
discovery.core.mdnsCallback(packet([ecobee]))
assert !discovery.state.afterMdns
assert !discovery.scheduled.containsKey('mdnsTimeout')
assert discovery.connects == [38607]
discovery.state.afterMdns = 'live'
discovery.core.mdnsCallback(packet(downstairs))
assert !discovery.state.afterMdns
assert !discovery.scheduled.containsKey('mdnsTimeout')
assert discovery.connects == [38607, 38607]
def matchedDiscovery = harness()
matchedDiscovery.state.afterMdns = 'live'
matchedDiscovery.core.mdnsCallback(combined)
assert matchedDiscovery.state.discoveredPort == 46557
assert matchedDiscovery.updates.port == 46557
assert matchedDiscovery.connects == [46557]
assert !matchedDiscovery.scheduled.containsKey('mdnsTimeout')
matchedDiscovery.core.mdnsCallback(packet(accessory('ThermostatOne', 'thermostat-one.local', '192.0.2.53', 38607, '11:22:33:44:55:66')))
assert matchedDiscovery.state.discoveredPort == 46557
def downstairsDiscovery = harness()
downstairsDiscovery.settings.accPairingId = '66:55:44:33:22:11'
downstairsDiscovery.settings.ip = '192.0.2.52'
downstairsDiscovery.state.discoveredPort = 38791
downstairsDiscovery.state.afterMdns = 'live'
downstairsDiscovery.core.mdnsCallback(combined)
assert downstairsDiscovery.state.discoveredPort == 40649
assert downstairsDiscovery.updates.port == 40649
assert downstairsDiscovery.connects == [40649]
println 'PASS: first non-matching mDNS replies are treated as misses; matching replies dispatch immediately'

def emptyReply = harness()
emptyReply.state.afterMdns = 'live'
emptyReply.state.mdnsMulticast = true
emptyReply.scheduled.mdnsTimeout = 5
emptyReply.core.mdnsCallback(new Expando(payload: 'not-dns'))
assert !emptyReply.state.afterMdns
assert !emptyReply.scheduled.containsKey('mdnsTimeout')
assert emptyReply.connects == [38607]

def emptyRelocation = harness()
emptyRelocation.state.afterRelocate = 'live'
emptyRelocation.scheduled.relocateTimeout = 8
emptyRelocation.core.relocateCallback(new Expando(payload: 'not-dns'))
assert !emptyRelocation.state.afterRelocate
assert !emptyRelocation.scheduled.containsKey('relocateTimeout')
assert emptyRelocation.connects == [38607]
println 'PASS: empty or malformed first replies fail fast in both discovery callbacks'

def matchedRelocation = harness()
matchedRelocation.state.afterRelocate = 'live'
matchedRelocation.settings.ip = '192.0.2.99'
matchedRelocation.core.relocateCallback(combined)
assert matchedRelocation.updates.ip == '192.0.2.53'
assert matchedRelocation.updates.port == 46557
assert matchedRelocation.connects == [46557]
assert !matchedRelocation.state.afterRelocate
println 'PASS: relocation accepts a matching endpoint'

def wrappedReply = harness()
wrappedReply.state.afterMdns = 'live'
wrappedReply.core.binding.setVariable('parseLanMessage', { description ->
    assert description == 'udp-description'
    [payload: combined]
})
wrappedReply.core.mdnsCallback(new Expando(description: 'udp-description'))
assert wrappedReply.state.discoveredPort == 46557
assert wrappedReply.connects == [46557]
println 'PASS: callback extracts DNS from a response object description'

def multicast = harness(true)
multicast.core.mdnsThen('live')
assert multicast.commands.size() == 1
assert multicast.commands[0].options.destinationAddress == '192.0.2.53:5353'
assert multicast.commands[0].options.callback == 'mdnsCallback'
assert multicast.scheduled.mdnsTimeout >= multicast.commands[0].options.timeout
assert multicast.state.mdnsReplies == 0

String capturedDownstairs = packet(downstairs)
String capturedUpstairs = packet(upstairs)
multicast.core.mdnsCallback(new Expando(description: capturedDownstairs))
assert multicast.connects == [38607]
assert capturedDownstairs.length() > 0
assert capturedUpstairs.length() > 0
assert multicast.state.mdnsReplies == 1
assert multicast.state.mdnsPayloads == 1
assert multicast.core.parseMdns(capturedDownstairs, '66:55:44:33:22:11').port == 40649
def multicastMatch = harness(true)
multicastMatch.state.afterMdns = 'live'
multicastMatch.state.mdnsMulticast = true
multicastMatch.core.mdnsCallback(new Expando(description: capturedUpstairs))
assert multicastMatch.connects == [46557]
println 'PASS: normal discovery rejects a first unrelated reply; matching multicast replies still dispatch'

def initialPairing = harness()
initialPairing.settings.remove('accPairingId')
initialPairing.state.afterMdns = 'live'
initialPairing.core.mdnsCallback(new Expando(payload: capturedDownstairs))
assert initialPairing.connects == [38607]
initialPairing.core.mdnsCallback(new Expando(payload: capturedUpstairs))
assert initialPairing.connects == [38607]
assert initialPairing.core.parseMdns(combined, '', '192.0.2.52').port == 40649
assert !initialPairing.core.mdnsPayload(new Object())
assert !initialPairing.core.mdnsPayload('hubitat.device.HubResponse@deadbeef')
println 'PASS: initial pairing filters multicast replies by configured IP and rejects object text'

def targeted = harness(true)
targeted.settings.mdnsServiceName = 'Upstairs'
targeted.core.mdnsThen('live')
3.times { targeted.core.mdnsTimeout() }
String targetedOwner = dnsName('Upstairs._hap._tcp.local').encodeHex().toString()
assert targeted.commands.take(3).every { it.options.destinationAddress == '192.0.2.53:5353' }
assert targeted.commands[3].options.destinationAddress == '224.0.0.251:5353'
assert targeted.commands[3].action == '000000000002000000000000' + targetedOwner + '00210001' + targetedOwner + '00100001'
targeted.core.mdnsCallback(new Expando(payload: capturedUpstairs))
assert targeted.connects == [46557]
assert targeted.state.mdnsInstance == 'thermostatone._hap._tcp.local'
targeted.settings.remove('mdnsServiceName')
targeted.core.mdnsThen('live')
3.times { targeted.core.mdnsTimeout() }
assert targeted.commands[7].action.startsWith('000000000002000000000000')
targeted.settings.mdnsServiceName = 'Downstairs._hap._tcp.local.'
targeted.settings.accPairingId = '66:55:44:33:22:11'
targeted.settings.ip = '192.0.2.52'
targeted.core.mdnsThen('live')
3.times { targeted.core.mdnsTimeout() }
assert targeted.commands[11].action.contains(dnsName('Downstairs._hap._tcp.local').encodeHex().toString())
targeted.core.mdnsCallback(new Expando(payload: capturedDownstairs))
assert targeted.connects == [46557, 40649]
println 'PASS: targeted SRV/TXT discovery uses the configured or learned instance and refreshes both ports'

String homebridgeReply = packet(accessory('OtherBridge', 'other-bridge.local', '192.0.2.29', 53708, 'AA:BB:CC:DD:EE:FF'))
String upstairsTargetedReply = packet(upstairs)
String downstairsTargetedReply = packet(downstairs)
assert homebridgeReply.length() > 0
assert upstairsTargetedReply.length() > 0
assert downstairsTargetedReply.length() > 0
def singleReply = harness(true)
singleReply.core.mdnsThen('live')
singleReply.core.mdnsCallback(homebridgeReply)
assert singleReply.connects == [38607]
assert singleReply.state.discoveredPort == 38607
singleReply.settings.mdnsServiceName = 'Upstairs'
singleReply.core.mdnsThen('live')
3.times { singleReply.core.mdnsTimeout() }
singleReply.core.mdnsCallback(upstairsTargetedReply)
assert singleReply.connects == [38607, 46557]
singleReply.settings.mdnsServiceName = 'Downstairs'
singleReply.settings.accPairingId = '66:55:44:33:22:11'
singleReply.settings.ip = '192.0.2.52'
singleReply.core.mdnsThen('live')
3.times { singleReply.core.mdnsTimeout() }
singleReply.core.mdnsCallback(downstairsTargetedReply)
assert singleReply.connects == [38607, 46557, 40649]
singleReply.settings.mdnsServiceName = 'a' * 64
try {
    singleReply.core.mdnsQuery()
    assert false: 'Oversized DNS instance label must be rejected'
} catch (IllegalArgumentException expected) { }
println 'PASS: unrelated first reply is rejected; one targeted accessory reply is sufficient to refresh each port'

def legacyUnicast = harness(true)
legacyUnicast.settings.remove('accPairingId')
legacyUnicast.core.mdnsThen('live')
legacyUnicast.core.mdnsCallback([ip: 'c0000235', payload: packet(upstairs.take(1))])
assert legacyUnicast.connects == [46557]
assert !legacyUnicast.updates.containsKey('ip')
legacyUnicast.settings.accPairingId = '11:22:33:44:55:66'
legacyUnicast.core.mdnsThen('live')
legacyUnicast.core.mdnsCallback([ip: '192.0.2.53', payload: packet(upstairs.take(1))])
assert legacyUnicast.connects == [46557, 46557]
legacyUnicast.core.mdnsThen('live')
legacyUnicast.core.mdnsCallback([ip: 'c0000234', payload: packet(upstairs.take(1))])
assert legacyUnicast.connects.size() == 3
legacyUnicast.core.mdnsCallback([ip: 'c0000235', payload: capturedDownstairs])
assert legacyUnicast.connects.size() == 3
legacyUnicast.state.afterMdns = 'live'
legacyUnicast.state.mdnsMulticast = true
legacyUnicast.core.mdnsCallback([ip: 'c0000235', payload: packet(upstairs.take(1))])
assert legacyUnicast.connects.size() == 4
legacyUnicast.state.afterMdns = 'live'
legacyUnicast.core.mdnsCallback([ip: 'c0000235', payload: capturedUpstairs])
assert legacyUnicast.connects.size() == 5
assert legacyUnicast.core.mdnsSourceIp([ip: '999.1.1.1']) == null
println 'PASS: IP-directed SRV-only replies work without weakening multicast identity checks'

def learnedName = harness(true)
learnedName.settings.ip = '192.0.2.99'
learnedName.state.mdnsInstance = 'thermostatone._hap._tcp.local'
learnedName.core.mdnsThen('live')
3.times { learnedName.core.mdnsTimeout() }
assert learnedName.commands[3].options.destinationAddress == '224.0.0.251:5353'
learnedName.core.mdnsCallback([ip: 'c0000235', payload: capturedUpstairs])
assert learnedName.updates.ip == '192.0.2.53'
assert learnedName.updates.port == 46557
assert learnedName.connects == [46557]

def manualName = harness(true)
manualName.settings.ip = '192.0.2.99'
manualName.settings.mdnsServiceName = 'ThermostatOne'
manualName.core.mdnsThen('live')
3.times { manualName.core.mdnsTimeout() }
manualName.core.mdnsCallback([ip: 'c0000235', payload: capturedUpstairs])
assert manualName.updates.ip == '192.0.2.53'
assert manualName.updates.port == 46557
assert manualName.connects == [46557]

def unidentified = harness(true)
unidentified.settings.ip = '192.0.2.99'
unidentified.settings.remove('accPairingId')
unidentified.core.mdnsThen('live')
unidentified.core.mdnsCallback([ip: 'c0000235', payload: capturedUpstairs])
assert !unidentified.updates
unidentified.state.afterMdns = 'live'
unidentified.settings.accPairingId = '11:22:33:44:55:66'
unidentified.core.mdnsCallback([ip: 'c0000235', payload: capturedDownstairs])
assert !unidentified.updates
println 'PASS: a matching TXT id recovers a moved address; unidentifiable replies stay pinned to the configured IP'

def losingRace = harness(true)
losingRace.settings.ip = '192.0.2.99'
losingRace.core.relocate('live')
(1..2).each { attempt ->
    losingRace.core.relocateCallback(homebridgeReply)
    assert losingRace.commands.size() == attempt + 1
    assert losingRace.state.afterRelocate == 'live'
    assert !losingRace.connects
}
losingRace.core.relocateCallback(homebridgeReply)
assert losingRace.commands.size() == 3
assert !losingRace.state.afterRelocate
assert losingRace.connects == [38607]

def wonRace = harness(true)
wonRace.settings.ip = '192.0.2.99'
wonRace.core.relocate('live')
wonRace.core.relocateCallback(homebridgeReply)
wonRace.core.relocateCallback(capturedUpstairs)
assert wonRace.commands.size() == 2
assert wonRace.updates.ip == '192.0.2.53'
assert wonRace.connects == [46557]
println 'PASS: a lost relocation race re-browses within the sweep window instead of waiting it out'

['pairsetup', 'discover', 'read', 'write', 'unpair', 'live'].each { operation ->
    def caller = harness(true)
    caller.state.writeJson = '{"characteristics":[]}'
    caller.core.mdnsThen(operation)
    caller.core.mdnsCallback([ip: 'c0000235', payload: capturedUpstairs])
    assert caller.commands.size() == 1
    assert caller.commands[0].options.destinationAddress == '192.0.2.53:5353'
    assert !caller.updates.containsKey('ip')
    assert !caller.scheduled.containsKey('mdnsTimeout')
    if (operation == 'live') assert caller.connects == [46557]
    else assert caller.dispatched == [[operation, operation == 'write' ? caller.state.writeJson : null]]
    caller.core.mdnsTimeout()
    assert caller.commands.size() == 1
}
println 'PASS: all discovery operation types retain the unicast success path and dispatch exactly once'

def generic = harness(true)
generic.state.lastSweep = 3600000L
generic.state.remove('discoveredPort')
generic.settings.port = 12345
generic.core.mdnsThen('read')
3.times { generic.core.mdnsTimeout() }
assert generic.commands.size() == 3
assert generic.commands.every { it.options.destinationAddress == '192.0.2.53:5353' }
assert generic.dispatched == [['read', null]]
assert generic.core.hapPort() == 12345
assert !generic.state.afterMdns
assert !generic.state.afterRelocate
generic.core.mdnsTimeout()
assert generic.dispatched.size() == 1
println 'PASS: unnamed accessories retain three unicast attempts and cached-port fallback while relocation is throttled'

def bounded = harness(true)
bounded.settings.mdnsServiceName = 'Upstairs'
bounded.state.lastSweep = 3600000L
bounded.core.mdnsThen('live')
4.times { bounded.core.mdnsTimeout() }
assert bounded.commands.size() == 4
assert bounded.commands.count { it.options.destinationAddress == '224.0.0.251:5353' } == 1
assert bounded.connects == [38607]
assert !bounded.state.afterMdns
bounded.settings.mdnsServiceName = 'a' * 64
bounded.core.mdnsThen('live')
3.times { bounded.core.mdnsTimeout() }
assert bounded.commands.size() == 7
assert bounded.connects == [38607, 38607]
println 'PASS: targeted fallback is bounded and invalid names cannot break the IP-based recovery path'

def moved = harness(true)
moved.state.mdnsInstance = 'old-name._hap._tcp.local'
moved.settings.ip = '192.0.2.99'
moved.core.mdnsThen('live')
4.times { moved.core.mdnsTimeout() }
assert moved.commands.size() == 5
assert moved.commands[4].action == '000000000002000000000000' + dnsName('old-name._hap._tcp.local').encodeHex().toString() + '00210001' + dnsName('old-name._hap._tcp.local').encodeHex().toString() + '00100001'
assert moved.commands[4].options.callback == 'relocateCallback'
assert moved.state.lastSweep == 3600000L
3.times { moved.core.relocateCallback(capturedDownstairs) }
assert !moved.updates
assert !moved.state.afterRelocate
assert moved.connects == [38607]
def namedRelocation = harness(true)
namedRelocation.settings.mdnsServiceName = 'Upstairs'
namedRelocation.settings.ip = '192.0.2.99'
namedRelocation.core.relocate('live')
assert namedRelocation.commands[0].action == '000000000002000000000000' + dnsName('Upstairs._hap._tcp.local').encodeHex().toString() + '00210001' + dnsName('Upstairs._hap._tcp.local').encodeHex().toString() + '00100001'
namedRelocation.core.relocateCallback(capturedUpstairs)
assert namedRelocation.updates.ip == '192.0.2.53'
assert namedRelocation.updates.port == 46557
assert namedRelocation.connects == [46557]
assert namedRelocation.state.mdnsInstance == 'thermostatone._hap._tcp.local'
println 'PASS: relocation targets known names and general browsing is reserved for unnamed accessories'

def handshake = harness()
(1..6).each { attempt ->
    handshake.core.verifyWatch()
    assert handshake.scheduled.startLive == Math.min(120, attempt * 30)
    assert !handshake.state.reFails
    assert handshake.state.vtry == attempt
    assert !handshake.health
}
println 'PASS: handshake retry delays retain the original 120-second cap while counting failures'

def forgotten = harness()
forgotten.state.mdnsInstance = 'upstairs._hap._tcp.local'
forgotten.state.afterMdns = 'live'
forgotten.state.afterRelocate = 'live'
forgotten.core.binding.setVariable('device', [removeSetting: { key -> forgotten.settings.remove(key) }])
forgotten.core.binding.setVariable('unschedule', { -> forgotten.scheduled.clear() })
forgotten.core.clearLocalPairing()
assert !forgotten.state.mdnsInstance
assert !forgotten.state.afterMdns
assert !forgotten.state.afterRelocate
assert !forgotten.state.discoveredPort
println 'PASS: forgetting an accessory clears its learned discovery state'