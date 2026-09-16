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
                   settings: [ip: '192.168.1.53', accPairingId: 'C4:D3:2D:CC:9C:06'],
                   scheduled: [:], updates: [:], connects: [], discoveries: [], health: [], commands: []]
    def variables = new Binding([
        state: context.state, settings: context.settings,
        RE_BACKOFF_SEC: [60, 120, 300], OFFLINE_AFTER_FAILS: 2,
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
        parseLanMessage: { message -> [payload: message] }
    ])
    String methods = slice('def relocateCallback(message)', '// ===== pair-setup') +
        slice('private int reBackoff()', 'void liveConnect()') +
        slice('def verifyWatch()', '// HELD SESSION') +
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
        methods = slice('String mdnsQuery()', 'def mdnsTimeout()') + methods
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

def retry = harness()
retry.core.startLive()
assert retry.connects == [38607]
(1..3).each { attempt ->
    retry.core.verifyWatch()
    assert retry.state.reFails == attempt
    assert retry.scheduled.startLive > 0
    retry.core.startLive()
}
assert retry.discoveries == ['live']
retry.state.sess = true
retry.core.verifyWatch()
assert retry.state.reFails == 3
println 'PASS: handshake failures trigger rediscovery; established sessions do not count as failures'

def upstairs = accessory('Upstairs', 'ecobee-ares.local', '192.168.1.53', 46557, 'C4:D3:2D:CC:9C:06')
def downstairs = accessory('Downstairs', 'ecobee-ares-2.local', '192.168.1.52', 40649, '33:E1:81:68:F6:41')
def ecobee = [owner: 'Upstairs._ecobee._tcp.local', type: 33, data: srvData(1201, 'ecobee-ares.local')]
def discovery = harness()
assert discovery.core.parseMdns(packet(upstairs + [ecobee])).port == 46557
def combined = packet(upstairs + downstairs + [ecobee])
assert discovery.core.parseMdns(combined, 'c4:d3:2d:cc:9c:06') ==
    [ip: '192.168.1.53', port: 46557, sf: 0, id: 'C4:D3:2D:CC:9C:06', instance: 'upstairs._hap._tcp.local']
assert discovery.core.parseMdns(combined, '33:E1:81:68:F6:41') ==
    [ip: '192.168.1.52', port: 40649, sf: 0, id: '33:E1:81:68:F6:41', instance: 'downstairs._hap._tcp.local']
assert !discovery.core.parseMdns(combined).port
assert !discovery.core.parseMdns(combined, '00:00:00:00:00:00').port
assert discovery.core.parseMdns(packet((upstairs + downstairs).reverse()), 'C4:D3:2D:CC:9C:06').port == 46557
println 'PASS: service, identity, address association, and record ordering'

String owner = 'Upstairs._HAP._TCP.local'
def compressed = packet([[owner: 'c00c'.decodeHex(), type: 33, data: srvData(46557, 'ecobee-ares.local')]], owner)
assert discovery.core.parseMdns(compressed).port == 46557
def compressedTarget = packet([
    [owner: owner, type: 33, data: '00000000b5ddc00c'.decodeHex()],
    upstairs[2]
], 'ecobee-ares.local')
assert discovery.core.parseMdns(compressedTarget).ip == '192.168.1.53'
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
discovery.core.mdnsCallback(packet(downstairs))
assert discovery.state.afterMdns == 'live'
assert discovery.scheduled.mdnsTimeout == 4
assert discovery.state.discoveredPort == 38607
assert !discovery.connects
discovery.core.mdnsCallback(combined)
assert discovery.state.discoveredPort == 46557
assert discovery.updates.port == 46557
assert discovery.connects == [46557]
assert !discovery.scheduled.containsKey('mdnsTimeout')
discovery.core.mdnsCallback(packet(accessory('Upstairs', 'ecobee-ares.local', '192.168.1.53', 38607, 'C4:D3:2D:CC:9C:06')))
assert discovery.state.discoveredPort == 46557
def downstairsDiscovery = harness()
downstairsDiscovery.settings.accPairingId = '33:E1:81:68:F6:41'
downstairsDiscovery.settings.ip = '192.168.1.52'
downstairsDiscovery.state.discoveredPort = 38791
downstairsDiscovery.state.afterMdns = 'live'
downstairsDiscovery.core.mdnsCallback(combined)
assert downstairsDiscovery.state.discoveredPort == 40649
assert downstairsDiscovery.updates.port == 40649
assert downstairsDiscovery.connects == [40649]
println 'PASS: unrelated replies keep waiting, current ports replace cached ports, late replies are ignored'

def relocation = harness()
relocation.state.afterRelocate = 'live'
relocation.core.relocateCallback(packet(upstairs.take(2)))
assert relocation.state.afterRelocate == 'live'
relocation.settings.ip = '192.168.1.99'
relocation.core.relocateCallback(combined)
assert relocation.updates.ip == '192.168.1.53'
assert relocation.updates.port == 46557
assert relocation.connects == [46557]
println 'PASS: relocation requires a complete endpoint for the paired accessory'

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
assert multicast.commands[0].options.destinationAddress == '224.0.0.251:5353'
assert multicast.commands[0].options.callback == 'mdnsCallback'
assert multicast.scheduled.mdnsTimeout > multicast.commands[0].options.timeout
assert multicast.state.mdnsReplies == 0

String capturedDownstairs = '000084000001000100000004045F686170045F746370056C6F63616C00000C0001C00C000C00010000000A000D0A446F776E737461697273C00CC02D002100010000000A0016000000009EC90D65636F6265652D617265732D32C016C02D001000010000000A00540563233D32310566663D33331469643D33333A45313A38313A36383A46363A3431096D643D4543423630310670763D312E310673233D3436360473663D300463693D390B73683D3550554C49673D3D0466653D31C04C000100010000000A0004C0A80134C04C001C00010000000A0010FE80000000000000466132FFFE06777E'
String capturedUpstairs = '000084000001000100000004045F686170045F746370056C6F63616C00000C0001C00C000C00010000000A000B085570737461697273C00CC02D002100010000000A001400000000B5DD0B65636F6265652D61726573C016C02D001000010000000A00540563233D31340566663D33331469643D43343A44333A32443A43433A39433A3036096D643D4543423630310670763D312E310673233D3430390473663D300463693D390B73683D656A326957413D3D0466653D31C04A001C00010000000A0010FE80000000000000466132FFFEBB790DC04A000100010000000A0004C0A80135'
multicast.core.mdnsCallback(new Expando(description: capturedDownstairs))
assert !multicast.connects
multicast.core.mdnsCallback(new Expando(description: capturedUpstairs))
assert capturedDownstairs.length() == 232 * 2
assert capturedUpstairs.length() == 228 * 2
assert multicast.connects == [46557]
assert multicast.state.mdnsReplies == 2
assert multicast.state.mdnsPayloads == 2
assert multicast.core.parseMdns(capturedDownstairs, '33:E1:81:68:F6:41').port == 40649
println 'PASS: normal discovery uses multicast and parses captured replies from both ecobees'

def initialPairing = harness()
initialPairing.settings.remove('accPairingId')
initialPairing.state.afterMdns = 'live'
initialPairing.core.mdnsCallback(new Expando(payload: capturedDownstairs))
assert !initialPairing.connects
initialPairing.core.mdnsCallback(new Expando(payload: capturedUpstairs))
assert initialPairing.connects == [46557]
assert initialPairing.core.parseMdns(combined, '', '192.168.1.52').port == 40649
assert !initialPairing.core.mdnsPayload(new Object())
assert !initialPairing.core.mdnsPayload('hubitat.device.HubResponse@deadbeef')
println 'PASS: initial pairing filters multicast replies by configured IP and rejects object text'

def targeted = harness(true)
targeted.settings.mdnsServiceName = 'Upstairs'
targeted.core.mdnsThen('live')
String targetedOwner = dnsName('Upstairs._hap._tcp.local').encodeHex().toString()
assert targeted.commands[0].action == '000000000002000000000000' + targetedOwner + '00210001' + targetedOwner + '00100001'
targeted.core.mdnsCallback(new Expando(payload: capturedUpstairs))
assert targeted.connects == [46557]
assert targeted.state.mdnsInstance == 'upstairs._hap._tcp.local'
targeted.settings.remove('mdnsServiceName')
targeted.core.mdnsThen('live')
assert targeted.commands[1].action.startsWith('000000000002000000000000')
targeted.settings.mdnsServiceName = 'Downstairs._hap._tcp.local.'
targeted.settings.accPairingId = '33:E1:81:68:F6:41'
targeted.core.mdnsThen('live')
assert targeted.commands[2].action.contains(dnsName('Downstairs._hap._tcp.local').encodeHex().toString())
targeted.core.mdnsCallback(new Expando(payload: capturedDownstairs))
assert targeted.connects == [46557, 40649]
println 'PASS: targeted SRV/TXT discovery uses the configured or learned instance and refreshes both ports'

String homebridgeReply = '000084000001000700000000045F686170045F746370056C6F63616C00000C8001C00C000C00010000000A00120F486F6D656272696467652043454243C00CC02D001000010000000A00510663233D3538300466663D301469643D30453A33363A43443A33313A41463A46430D6D643D686F6D656272696467650670763D312E310473233D310473663D300463693D320B73683D742F32562F513D3DC02D002100010000000A001500000000D1CC0C316432343031383662303739C016C0AE000100010000000A0004C0A8011DC00C000C00010000000A001A17486F6D6562726964676520487562697461742032413638C00CC0D9001000010000000A00500563233D31330466663D301469643D30453A43333A34303A38443A44423A44460D6D643D686F6D656272696467650670763D312E310473233D310473663D300463693D320B73683D4D32365045673D3DC0D9002100010000000A000800000000C926C0AE'
String upstairsTargetedReply = '000084000002000200000002085570737461697273045F686170045F746370056C6F63616C0000210001C00C00100001C00C001000010000000A00540563233D31340566663D33331469643D43343A44333A32443A43433A39433A3036096D643D4543423630310670763D312E310673233D3430390473663D300463693D390B73683D656A326957413D3D0466653D31C00C002100010000000A001400000000B5DD0B65636F6265652D61726573C01FC0A2001C00010000000A0010FE80000000000000466132FFFEBB790DC0A2000100010000000A0004C0A80135'
String downstairsTargetedReply = '0000840000020002000000020A446F776E737461697273045F686170045F746370056C6F63616C0000210001C00C00100001C00C001000010000000A00540563233D32310566663D33331469643D33333A45313A38313A36383A46363A3431096D643D4543423630310670763D312E310673233D3436360473663D300463693D390B73683D3550554C49673D3D0466653D31C00C002100010000000A0016000000009EC90D65636F6265652D617265732D32C021C0A4000100010000000A0004C0A80134C0A4001C00010000000A0010FE80000000000000466132FFFE06777E'
assert homebridgeReply.length() == 355 * 2
assert upstairsTargetedReply.length() == 220 * 2
assert downstairsTargetedReply.length() == 224 * 2
def singleReply = harness(true)
singleReply.core.mdnsThen('live')
singleReply.core.mdnsCallback(homebridgeReply)
assert !singleReply.connects
assert singleReply.state.discoveredPort == 38607
singleReply.settings.mdnsServiceName = 'Upstairs'
singleReply.core.mdnsThen('live')
singleReply.core.mdnsCallback(upstairsTargetedReply)
assert singleReply.connects == [46557]
singleReply.settings.mdnsServiceName = 'Downstairs'
singleReply.settings.accPairingId = '33:E1:81:68:F6:41'
singleReply.settings.ip = '192.168.1.52'
singleReply.core.mdnsThen('live')
singleReply.core.mdnsCallback(downstairsTargetedReply)
assert singleReply.connects == [46557, 40649]
singleReply.settings.mdnsServiceName = 'a' * 64
try {
    singleReply.core.mdnsQuery()
    assert false: 'Oversized DNS instance label must be rejected'
} catch (IllegalArgumentException expected) { }
println 'PASS: actual Homebridge first reply is rejected; one targeted ecobee reply is sufficient to refresh each port'