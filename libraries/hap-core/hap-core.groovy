/*
 * HAP Core — HomeKit Accessory Protocol controller engine (Hubitat Library)
 *
 * Reusable, device-agnostic HAP CONTROLLER core, extracted from the proven
 * RamSet ecobee-hap-thermostat driver. It contains everything needed to pair
 * with and talk to a LAN/Wi-Fi HomeKit accessory, with no knowledge of any
 * particular device type:
 *   - SRP-6a pair-setup (3072-bit, SHA-512) + pair-verify (X25519 ECDH, Ed25519)
 *   - ChaCha20-Poly1305 encrypted session (HKDF-SHA512 keys, LE framing, per-dir nonce)
 *   - hand-rolled X25519 / Ed25519 / SRP6a in BigInteger (sandbox blocks the JCE
 *     KeyAgreement/Signature/SecureRandom paths; Hubitat is adding them in 2.5.1)
 *   - TLV8 encode/decode
 *   - IP-directed mDNS _hap._tcp port discovery with multicast fallback (the HAP port is dynamic)
 *   - persistent rawSocket session, event subscriptions, keepalive watchdog + reconnect
 *   - generic /accessories fetch and /characteristics read/write
 *
 * The INCLUDING DRIVER must implement these callbacks (concatenated at compile time):
 *   void   onAccessories(def j)      // parsed /accessories JSON arrived (discovery)
 *   void   onCharacteristics(def j)  // parsed /characteristics JSON arrived (read/event)
 *   String readIds()                 // CSV of "aid.iid" to GET on connect / refresh / keepalive
 *   String subscribeBody()           // full PUT /characteristics body that sets ev:true
 * Optional: void onPaired()          // called once after a successful pair-setup
 *
 * Include in a driver with:  #include RamSet.hapCore
 *
 * Author: RamSet
 * Version: 0.11.1
 *
 * Changelog:
 *  v0.11.1 - Resolve the merge of targeted mDNS recovery with atomic operation claiming.
 *  v0.10.17 - Improve HAP discovery and recovery: preserve configured-IP-first discovery with targeted multicast
 *            fallback; associate SRV, TXT, identity, and address records by service; target known service names
 *            during relocation; accept any reply whose TXT id matches the paired accessory wherever it arrived
 *            from, so a DHCP-moved accessory recovers its address, and apply source-address filtering only to
 *            replies that cannot be identified; treat Hubitat's first non-matching UDP response as an immediate
 *            miss; count handshake timeouts separately for periodic port rediscovery without changing offline
 *            health timing; retain learned service names and clear them when pairing is forgotten.
 *  v0.11.0 - Pairs accessories that reject the standard pair-setup method, and reads back accessories that frame
 *            their HTTP response in pieces. Three fixes, all found on an iSmartGate bridge (GitHub issue #1):
 *            (1) PAIR-SETUP METHOD: M1 goes out as Method 0 (Pair Setup) as before; if the accessory answers 0x01
 *            "unknown" — which the iSmartGate does while advertising sf=1 — it retries once with Method 1 (Pair
 *            Setup with Auth) and remembers the method that worked. Deliberately NOT keyed off the accessory's ff
 *            flag: accessories that advertise ff=1 (an ecobee, for one) pair fine with Method 0, and switching
 *            them would risk what already works. A "Pair-setup method" preference pins one for support.
 *            (2) ONE-SHOT RESPONSE FRAMING: a response now ends on its real HTTP framing (Content-Length reached,
 *            or the chunked terminator) instead of on "a decrypted frame under 1024 bytes", which is a guess about
 *            how the accessory splits its frames. An accessory that sends headers in one frame and the body in the
 *            next ended the response at the headers, so /accessories parsed an empty body ("Text must not be null
 *            or empty") and no children were created. Falls back to the old guess when a response carries neither
 *            framing, so accessories that work today are untouched.
 *            (3) DUPLICATE MDNS DISPATCH: an mDNS reply and its timeout could both claim the same pending op,
 *            because driver state is only saved when an execution ends — sending pair-setup M1 twice on two
 *            sockets. The op is now claimed atomically, so exactly one path acts on it.
 *  v0.10.16 - Reboot-changed-port recovery. A HomeKit accessory's HAP port is DYNAMIC and frequently changes when
 *            the accessory reboots (observed live: an ecobee came back on 42600 after being on 57857). 0.10.14/15's
 *            cheap-reconnect path connected straight to the last-known port and would never re-resolve it until the
 *            30-min sweep window — so a rebooted accessory sat unreachable for up to half an hour even though it was
 *            back and answering mDNS. Now, every 3rd consecutive reconnect failure re-resolves the port via unicast
 *            mDNS first (cheap when the host is up; catches the new port immediately). The expensive multicast subnet
 *            SWEEP stays gated to ≤1/30min (moved into mdnsTimeout, where it belongs) so an offline accessory still
 *            can't pin hub load. Net: transient drops reconnect instantly, a rebooted accessory recovers within a
 *            couple of retry cycles on its new port, and a truly-offline one decays to a quiet cheap retry.
 *  v0.10.15 - Recovery latency fix for 0.10.14: the reconnect backoff cap was lowered from 30 min to 5 min, so an
 *            accessory that comes back online reconnects within ~5 min instead of possibly waiting out a long
 *            backoff. This is SAFE because the expensive /24 subnet sweep is gated independently (SWEEP_INTERVAL_SEC,
 *            30 min) — a stale retry is just a ~ms NoRouteToHost connect to the last-known ip:port, so retrying it
 *            every few minutes costs nothing. Load protection stays with the sweep circuit-breaker; the cheap retry
 *            no longer has to be starved to protect the hub. (Observed live on an offline ecobee: 0.10.14 backed off
 *            to the 30-min cap and left the thermostat unreconnected for up to half an hour after it returned.)
 *  v0.10.14 - OFFLINE ACCESSORY NO LONGER PINS HUB LOAD. When an accessory is powered off / off the network, the
 *            recovery path used to run the FULL discovery ladder (2×mDNS unicast + an 8s /24 multicast subnet sweep +
 *            TCP connect) every ~60s forever — ~23s of blocking I/O per minute (~40% duty), enough to trip
 *            hubLoadSevere and stall the hub's stats sampler. Four changes: (1) EXPONENTIAL BACKOFF on consecutive
 *            reconnect failures (60→120→300→600→900→1800s, reset on the next successful session) instead of a flat
 *            60s; (2) CHEAP PATH FIRST — a known accessory reconnects straight to its last-known ip:port (fails in ms
 *            with NoRouteToHost when offline) instead of paying mDNS+sweep up front; (3) the expensive multicast
 *            subnet sweep is now a CIRCUIT BREAKER — it runs only when there is no cached port, or at most once per
 *            30 min, not every cycle; (4) unicast mDNS port-detect timeout trimmed 6s→4s. Also: healthStatus flips
 *            to "offline" after 2 failed reconnects (a direct signal, minutes) rather than waiting on lastRx
 *            staleness (up to ~32 min in passive mode), and back to "online" on session up. Minor: a log line no
 *            longer uses an apostrophe that the hub's log viewer double-HTML-escaped ("couldn&amp;apos;t").
 *  v0.10.13 - healthStatus now populates reliably: setHealth() emits the event not only on an online/offline
 *            change but also when the device's healthStatus attribute doesn't yet match state (e.g. a driver that
 *            only just declared the attribute) — so it stops reading blank. Logs still fire only on a real flip.
 *            Pairs with HomeKit HAP Accessory driver 0.13.3 which adds the attribute declaration.
 *  v0.10.12 - TCP keepalive on the session socket (Hubitat 2.5.1.145+), enabling a passive persistent model for
 *            cheap chips. The persistent + one-shot connects now request SO_KEEPALIVE (tcpKeepIdle/Interval/Count)
 *            so the OS holds the socket warm and detects a dead peer — what real HomeKit controllers use instead of
 *            polling. SAFE ON EVERY BUILD: on firmware without the fix the options throw getMethod (sandbox-blocked)
 *            and we catch it + fall back to the historic plain connect. This lets a Meross-class chip hold a
 *            persistent session when its read-probe is turned down/off (set "Keepalive/liveness probe" low or 0 for
 *            such chips) — proven live: an MSG100 that dropped in ~90s while polled held 16+ min passively and still
 *            answered a read. The ecobee is unaffected (its ~10-min drop is a firmware session cap keepalive can't
 *            fix; it stays on the recovery model).
 *  v0.10.11 - AUTO-RECOVERY FIX (forum #202: session died, never recovered, dead until a manual Save). The
 *            reconnect backstop `ensureUp` bailed on `state.live==true` — but a zombie session leaves that flag
 *            stuck TRUE while no data flows, so the backstop skipped the dead session on every 10-min tick and
 *            nothing ever reconnected it. Proven live: on a healthy device the probe loop (liveKeepalive) is torn
 *            down on every death and recovery rides on a SINGLE liveConnect runIn backed only by ensureUp; if that
 *            liveConnect fizzles and the live flag is left true, ensureUp was blind to it. Now ensureUp treats
 *            "live" as real ONLY if data is also fresh (lastRx within offlineAfterSecs); a stale-despite-live
 *            session is reconnected. Also: liveConnect's "no port" exit used to return with nothing scheduled
 *            (a wedge) — it now re-resolves the port via mDNS and retries. Both changes are additive/defensive.
 *  v0.10.10 - Log level: the routine live-session reconnect lines are now INFO, not WARN — "…session dead
 *            despite live flag — reconnecting" (keepalive-unanswered / long-silence) and "live socket dropped
 *            (…); reconnecting". Packet capture confirmed these are NORMAL self-healing events: some accessories
 *            (e.g. ecobee4) silently stop servicing the HAP session every ~8-10 min — no decrypt error, no TCP
 *            close — and the driver reconnects within ~10s each time. On an idle multi-sensor thermostat that's
 *            a WARN every few minutes for a link that's actually fine. Genuine unreachability is still surfaced
 *            (and stays a WARN) via healthStatus=offline, which only trips after several missed intervals. Turn
 *            on info logging to see the reconnects; the real fix (prevent the drop) needs TCP keepalive (blocked).
 *
 * Copyright 2026 RamSet — Apache License 2.0, provided as-is, no warranty.
 */

library(
    author: "RamSet",
    category: "utility",
    description: "HomeKit Accessory Protocol (HAP) controller engine: pair-setup/verify, ChaCha20 session, X25519/Ed25519/SRP6a, TLV8, mDNS, /accessories + /characteristics.",
    name: "hapCore",
    namespace: "RamSet",
    importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/libraries/hap-core/hap-core.groovy",
    documentationLink: "https://github.com/RamSet/hubitat"
)

import groovy.transform.Field

// ===== in-memory buffers (keyed by device.id — @Field static is shared across ALL instances) =====
// Pending post-lookup op, claimed atomically. An mDNS reply and its timeout can run at the same instant (and a
// reply to a retried query can arrive after the first reply), and driver `state` is only written when each
// execution ENDS — so both read the same pending op and both dispatch it, which sent pair-setup M1 twice on two
// sockets. replace() hands the op to exactly one caller: the first gets it, later callers get the "" sentinel.
// state.afterMdns / state.afterRelocate stay as the after-restart fallback, when this map is empty.
@Field static java.util.concurrent.ConcurrentHashMap OPCLAIM = new java.util.concurrent.ConcurrentHashMap()
@Field static Map RXBUF = [:]
@Field static Map PLAINBUF = [:]
StringBuilder rxbuf(){ if(RXBUF[device.id]==null) RXBUF[device.id]=new StringBuilder(); return RXBUF[device.id] }
StringBuilder plainbuf(){ if(PLAINBUF[device.id]==null) PLAINBUF[device.id]=new StringBuilder(); return PLAINBUF[device.id] }

// ===== curve constants =====
@Field static java.math.BigInteger P  = new java.math.BigInteger("57896044618658097711785492504343953926634992332820282019728792003956564819949")
@Field static java.math.BigInteger L  = new java.math.BigInteger("7237005577332262213973186563042994240857116359379907606001950938285454250989")
@Field static java.math.BigInteger D  = new java.math.BigInteger("37095705934669439343138083508754565189542113879843219016388785533085940283555")
@Field static java.math.BigInteger BX = new java.math.BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202")
@Field static java.math.BigInteger BY = new java.math.BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")
@Field static java.math.BigInteger SQRTM1 = new java.math.BigInteger("19681161376707505956807079304988542015446066515923890162744021073123829784752")
@Field static java.math.BigInteger TWO  = java.math.BigInteger.valueOf(2)
// SRP-6a (HAP): RFC 3526 3072-bit group, g=5, precomputed k
@Field static java.math.BigInteger SRP_N = new java.math.BigInteger("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",16)
@Field static java.math.BigInteger SRP_G = java.math.BigInteger.valueOf(5)
@Field static java.math.BigInteger SRP_K = new java.math.BigInteger("a9c2e2559bf0ebb53f0cbbf62282906bede7f2182f00678211fbd5bde5b285033a4993503b87397f9be5ec02080fedbc0835587ad039060879b8621e8c3659e0",16)

// Live-session liveness-probe interval (seconds). A real HomeKit controller SUBSCRIBES and then
// LISTENS — it does not poll. Cheap accessories (e.g. Meross mt7687) actually drop the session if
// you GET /characteristics every 10-30s, which is why ours died ~60s in while Apple's holds for days.
// So we keep this rare: events deliver real-time updates; this probe only exists to notice a dead
// socket and reconnect. (Raw idle TCP to the device held 100s+ untouched — idle isn't the problem.)
@Field static int KEEPALIVE_SEC = 300            // how often the silence-watchdog checks (it does NOT poll)
@Field static int SILENCE_RECONNECT_SEC = 1800   // pure-listen: if totally silent this long, RECONNECT (never poll)
// ---- offline reconnect: exponential backoff + sweep circuit-breaker (an offline accessory must NOT pin hub load) ----
// Consecutive live-reconnect failures grow the retry gap so a powered-off/unreachable accessory decays from ~1/min
// to ~1/30min instead of hammering the FULL discovery ladder every 60s (23s blocking I/O = ~40% duty -> hubLoadSevere).
@Field static List<Integer> RE_BACKOFF_SEC = [60,120,300]   // retry cadence by consecutive failures, cap 5m (see note)
// Why cap the RETRY at only 5m when the accessory could be gone for hours? Because the expensive part — the /24
// multicast subnet sweep — is gated SEPARATELY by SWEEP_INTERVAL_SEC below, so a stale retry is just a ~ms
// NoRouteToHost connect to the last-known ip:port. Keeping that on a short leash means a returning accessory
// reconnects within 5 min instead of waiting out a 30-min backoff. Load protection comes from the sweep breaker,
// not from starving the cheap retry.
@Field static int SWEEP_INTERVAL_SEC = 1800      // the expensive /24 multicast subnet sweep runs at most this often (load protection)
@Field static int RELOCATE_MAX_TRIES = 3         // browses per sweep, since the hub delivers only the first reply and another responder can win the race
@Field static int OFFLINE_AFTER_FAILS = 2        // flip healthStatus=offline after this many failed reconnects (direct signal, not lastRx staleness)
@Field static final String MDNS_PTR_QUERY = "000000000001000000000000045f686170045f746370056c6f63616c00000c8001"

boolean isPaired(){ return (state.paired==true || settings?.iosLtsk) ? true : false }
// On-demand mode: connect → verify → read/write → close per action, plus periodic polling. No held
// session/subscriptions. Use for accessories that hard-close the HAP connection on a short timer (e.g.
// Meross MSG100 drops it ~every 45s regardless of traffic, which makes a persistent session impossible).
boolean onDemand(){ return settings?.sessionMode=="On-demand (poll)" }
int pollSecs(){ return Math.max(1,(settings?.pollMins ?: 5) as int)*60 }
// Persistent-mode safety refresh: if no frame has arrived within this window, RECONNECT (re-subscribe +
// fresh read of every characteristic) instead of doing a bare GET — a GET on the held session is what makes
// cheap chips (Meross) drop the link. 0 = off (fall back to the long SILENCE_RECONNECT_SEC watchdog).
// Floored at 20s: each reconnect is a full pair-verify handshake (hub CPU) on the accessory's single slot.
// Probe/keepalive interval (seconds). Held-session liveness: every interval we send ONE tiny characteristic
// read to keep the pipe warm and PROVE the session still works. Default 30s (safely under the ~45-60s a cheap
// chip idle-closes at). 0 = disable probing -> legacy long-silence reconnect only. Floor 15s.
int safetySecs(){ def v=settings?.safetyRefreshSecs; if(v==null) return 30; int s=(v as int); return s<=0 ? 0 : Math.max(15,s) }
int kaEvery(){ int w=safetySecs(); return w>0 ? w : KEEPALIVE_SEC }

// AccessoryInformation (HomeKit service 3E) -> logical key. Universal metadata present on every accessory;
// values come back in the /accessories response, so no extra read is needed. identifyIid holds the iid of
// the write-only Identify characteristic (type 14) for the optional identify() command.
@Field static Map INFO_CHARS = ["20":"manufacturer","21":"model","30":"serialNumber","52":"firmware","53":"hardware","14":"identifyIid"]
Map accInfo(acc){
    def m=[:]; def sv=acc.services?.find{ hapCode(it.type)=="3E" }
    sv?.characteristics?.each{ c-> def code=hapCode(c.type); def k=INFO_CHARS[code]; if(k){ m[k]= (code=="14") ? c.iid : c.value } }
    return m
}

// ===== byte / crypto helpers =====
byte[] hex(String s){ hubitat.helper.HexUtils.hexStringToByteArray(s) }
String hx(byte[] b){ hubitat.helper.HexUtils.byteArrayToHexString(b).toLowerCase() }
byte[] cp(byte[] a){ byte[] r=new byte[a.length]; for(int i=0;i<a.length;i++) r[i]=a[i]; return r }
byte[] cat(byte[]... arrs){ int n=0; arrs.each{n+=it.length}; byte[] r=new byte[n]; int p=0; arrs.each{a-> for(int j=0;j<a.length;j++) r[p++]=a[j]}; return r }
byte[] le16(int n){ return [(byte)(n&0xff),(byte)((n>>8)&0xff)] as byte[] }
byte[] le64(long n){ byte[] r=new byte[8]; for(int i=0;i<8;i++){ r[i]=(byte)(n&0xff); n=n>>8 }; return r }
byte[] nlabel(String s){ return cat([0,0,0,0] as byte[], s.getBytes("UTF-8")) }
byte[] nctr(long c){ return cat([0,0,0,0] as byte[], le64(c)) }
byte[] sha512(byte[] m){ java.security.MessageDigest.getInstance("SHA-512").digest(m) }
byte[] hmac512(byte[] key, byte[] msg){ def mac=javax.crypto.Mac.getInstance("HmacSHA512"); mac.init(new javax.crypto.spec.SecretKeySpec(key,"HmacSHA512")); return mac.doFinal(msg) }
byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int len){ byte[] prk=hmac512(salt,ikm); byte[] okm=new byte[0]; byte[] t=new byte[0]; int i=1; while(okm.length<len){ t=hmac512(prk,cat(t,info,[(byte)i] as byte[])); okm=cat(okm,t); i++ }; byte[] r=new byte[len]; for(int j=0;j<len;j++) r[j]=okm[j]; return r }
byte[] chachaEnc(byte[] key, byte[] nonce, byte[] pt, byte[] aad){ def c=javax.crypto.Cipher.getInstance("ChaCha20-Poly1305"); c.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"ChaCha20"),new javax.crypto.spec.IvParameterSpec(nonce)); if(aad?.length) c.updateAAD(aad); return c.doFinal(pt) }
byte[] chachaDec(byte[] key, byte[] nonce, byte[] ct, byte[] aad){ def c=javax.crypto.Cipher.getInstance("ChaCha20-Poly1305"); c.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"ChaCha20"),new javax.crypto.spec.IvParameterSpec(nonce)); if(aad?.length) c.updateAAD(aad); return c.doFinal(ct) }
java.math.BigInteger leBig(byte[] b){ byte[] r=new byte[b.length]; for(int i=0;i<b.length;i++) r[i]=b[b.length-1-i]; return new java.math.BigInteger(1,r) }
byte[] bigLe(java.math.BigInteger n, int len){ byte[] o=new byte[len]; java.math.BigInteger t=n; for(int i=0;i<len;i++){ o[i]=(byte)(t.and(java.math.BigInteger.valueOf(255)).intValue()); t=t.shiftRight(8) }; return o }
java.math.BigInteger beBig(byte[] b){ return new java.math.BigInteger(1,b) }
byte[] bigBe(java.math.BigInteger n, int len){ byte[] t=n.toByteArray(); byte[] r=new byte[len]; int src=(t.length>len)?t.length-len:0; int copy=t.length-src; for(int i=0;i<copy;i++) r[len-copy+i]=t[src+i]; return r }
byte[] x25519(byte[] kIn, byte[] uIn){
    byte[] k=cp(kIn); k[0]=(byte)(k[0]&248); k[31]=(byte)(k[31]&127); k[31]=(byte)(k[31]|64)
    java.math.BigInteger kk=leBig(k); byte[] um=cp(uIn); um[31]=(byte)(um[31]&127)
    java.math.BigInteger x1=leBig(um).mod(P), x2=java.math.BigInteger.ONE, z2=java.math.BigInteger.ZERO, x3=x1, z3=java.math.BigInteger.ONE
    java.math.BigInteger a24=java.math.BigInteger.valueOf(121665); int swap=0
    for(int t=254;t>=0;t--){ int kt=kk.testBit(t)?1:0; swap^=kt; if(swap==1){def s=x2;x2=x3;x3=s; s=z2;z2=z3;z3=s}; swap=kt
        def A=x2.add(z2).mod(P),AA=A.multiply(A).mod(P),B=x2.subtract(z2).mod(P),BB=B.multiply(B).mod(P),E=AA.subtract(BB).mod(P)
        def C=x3.add(z3).mod(P),Dd=x3.subtract(z3).mod(P),DA=Dd.multiply(A).mod(P),CB=C.multiply(B).mod(P)
        x3=DA.add(CB).mod(P); x3=x3.multiply(x3).mod(P); z3=DA.subtract(CB).mod(P); z3=z3.multiply(z3).mod(P).multiply(x1).mod(P)
        x2=AA.multiply(BB).mod(P); z2=E.multiply(AA.add(a24.multiply(E)).mod(P)).mod(P) }
    if(swap==1){def s=x2;x2=x3;x3=s; s=z2;z2=z3;z3=s}; return bigLe(x2.multiply(z2.modInverse(P)).mod(P),32)
}
List edAdd(List p1, List p2){ def X1=p1[0],Y1=p1[1],Z1=p1[2],T1=p1[3],X2=p2[0],Y2=p2[1],Z2=p2[2],T2=p2[3]
    def A=Y1.subtract(X1).multiply(Y2.subtract(X2)).mod(P),B=Y1.add(X1).multiply(Y2.add(X2)).mod(P),C=T1.multiply(TWO).multiply(D).multiply(T2).mod(P),Dd=Z1.multiply(TWO).multiply(Z2).mod(P)
    def E=B.subtract(A),F=Dd.subtract(C),G=Dd.add(C),H=B.add(A); return [E.multiply(F).mod(P),G.multiply(H).mod(P),F.multiply(G).mod(P),E.multiply(H).mod(P)] }
List edMul(java.math.BigInteger s, List pt){ List q=[java.math.BigInteger.ZERO,java.math.BigInteger.ONE,java.math.BigInteger.ONE,java.math.BigInteger.ZERO]; List pp=pt; java.math.BigInteger k=s; while(k.signum()>0){ if(k.testBit(0)) q=edAdd(q,pp); pp=edAdd(pp,pp); k=k.shiftRight(1) }; return q }
List edBase(){ return [BX,BY,java.math.BigInteger.ONE,BX.multiply(BY).mod(P)] }
byte[] edEnc(List pt){ def zi=pt[2].modInverse(P); def x=pt[0].multiply(zi).mod(P); def y=pt[1].multiply(zi).mod(P); return bigLe(x.testBit(0)?y.setBit(255):y,32) }
List edDecode(byte[] b){ java.math.BigInteger ye=leBig(b); int sign=ye.testBit(255)?1:0; java.math.BigInteger y=ye.clearBit(255)
    def u=y.multiply(y).subtract(java.math.BigInteger.ONE).mod(P),v=D.multiply(y).multiply(y).add(java.math.BigInteger.ONE).mod(P)
    def w=u.multiply(v.modInverse(P)).mod(P),x=w.modPow(P.add(java.math.BigInteger.valueOf(3)).divide(java.math.BigInteger.valueOf(8)),P)
    if(x.multiply(x).mod(P)!=w) x=x.multiply(SQRTM1).mod(P); if(((x.testBit(0))?1:0)!=sign) x=P.subtract(x); return [x,y,java.math.BigInteger.ONE,x.multiply(y).mod(P)] }
byte[] edPub(byte[] seed){ byte[] h=sha512(seed); byte[] a32=new byte[32]; for(int i=0;i<32;i++) a32[i]=h[i]; a32[0]=(byte)(a32[0]&248); a32[31]=(byte)(a32[31]&127); a32[31]=(byte)(a32[31]|64); return edEnc(edMul(leBig(a32),edBase())) }
byte[] edSign(byte[] seed, byte[] M){ byte[] h=sha512(seed); byte[] a32=new byte[32],pre=new byte[32]; for(int i=0;i<32;i++){a32[i]=h[i];pre[i]=h[i+32]}
    a32[0]=(byte)(a32[0]&248);a32[31]=(byte)(a32[31]&127);a32[31]=(byte)(a32[31]|64); java.math.BigInteger s=leBig(a32)
    byte[] A=edEnc(edMul(s,edBase())); java.math.BigInteger r=leBig(sha512(cat(pre,M))).mod(L); byte[] R=edEnc(edMul(r,edBase()))
    java.math.BigInteger k=leBig(sha512(cat(R,A,M))).mod(L); java.math.BigInteger S=r.add(k.multiply(s)).mod(L); return cat(R,bigLe(S,32)) }
boolean edVerify(byte[] A, byte[] M, byte[] sig){ byte[] R=new byte[32],Sb=new byte[32]; for(int i=0;i<32;i++){R[i]=sig[i];Sb[i]=sig[i+32]}
    java.math.BigInteger S=leBig(Sb); if(S.compareTo(L)>=0) return false; java.math.BigInteger k=leBig(sha512(cat(R,A,M))).mod(L)
    return hx(edEnc(edMul(S,edBase())))==hx(edEnc(edAdd(edDecode(R),edMul(k,edDecode(A))))) }
byte[] tlv(List items){ def o=new java.io.ByteArrayOutputStream(); items.each{ int t=it[0]; byte[] v=it[1]; int i=0; while(true){ int n=Math.min(255,v.length-i); o.write(t); o.write(n); for(int j=0;j<n;j++) o.write(v[i+j]); i+=n; if(i>=v.length) break; if(n<255) break } }; return o.toByteArray() }
Map tdec(byte[] b){ def d=[:]; int i=0; while(i<b.length){ int t=b[i]&0xff; int l=b[i+1]&0xff; byte[] v=new byte[l]; for(int j=0;j<l;j++) v[j]=b[i+2+j]; i+=2+l; d[t]=(d[t]!=null)?cat(d[t],v):v }; return d }
byte[] rnd32(){
    // Entropy WITHOUT SecureRandom or KeyPairGenerator — both are AST-blocked on some hub firmware versions,
    // and a blocked class reference fails the driver SAVE (compile-time check, so a try/catch can't guard it).
    // UUID.randomUUID() is SecureRandom-backed internally but doesn't reference a blocked class, so it passes
    // the sandbox on every version; mixed with time, a persistent counter, an object hash, and a rolling chain.
    state.entc = ((state.entc ?: 0) as long) + 1
    String seed = "" + now() + ":" + state.entc + ":" + java.util.UUID.randomUUID().toString() + ":" + java.util.UUID.randomUUID().toString() + ":" + (new Object().hashCode()) + ":" + (state.rndChain ?: "")
    byte[] h = sha512(seed.getBytes("UTF-8"))
    byte[] raw = new byte[32]; for(int i=0;i<32;i++) raw[i]=h[i]
    state.rndChain = hx(raw)   // chain forward so back-to-back calls don't repeat even within the same millisecond
    return raw
}
Map genEph(){ byte[] raw=rnd32(); byte[] pub=x25519(raw, hex("0900000000000000000000000000000000000000000000000000000000000000")); return [priv:hx(raw),pub:hx(pub)] }
String uuidStr(){ String h=hx(rnd32()); return "${h[0..7]}-${h[8..11]}-${h[12..15]}-${h[16..19]}-${h[20..31]}" }

// HAP service/characteristic type codes are short-form: strip dashes, uppercase, strip leading zeros.
String hapCode(def x){ x?.toString()?.replace("-","")?.toUpperCase()?.replaceAll(/^0+/,"") }

// ===== logging / diagnostics =====
void rep(String m){ if(settings.debugLog) log.debug "HAP: ${m}" }
void logInfo(String m){ if(settings.infoLog!=false) log.info m }
String nowHM(){ try{ return new Date().format("HH:mm:ss", location.timeZone) }catch(e){ return "--:--:--" } }
void dlog(String m){
    if(!settings.debugLog) return
    def b = (state.diag instanceof List) ? state.diag : []
    b << "${nowHM()} ${m}".toString()
    while(b.size()>28) b.remove(0)
    state.diag = b
    sendEvent(name:"diag", value: b.join("\n"))
}
// generic structural dump of an /accessories response — for diagnosing what an unknown accessory exposes
void dumpAcc(j){
    log.info "===== HAP /accessories dump ====="
    j.accessories.each{ acc->
        log.info "ACC aid=${acc.aid}"
        acc.services.each{ sv->
            def parts=sv.characteristics.collect{ c-> "iid${c.iid} t=${hapCode(c.type)} [${(c.perms?:[]).join('/')}]=${(c.value!=null)? (c.value.toString().take(24)) : ''}" }
            log.info "  svc iid${sv.iid} t=${hapCode(sv.type)}: " + parts.join("  ")
        }
    }
    log.info "===== end dump ====="
}

// ===== mDNS port discovery (the HAP port is dynamic — always read it at connect) =====
private void armOpClaim(String kind, String op){ OPCLAIM.put("${device.id}:${kind}".toString(), op) }
private String takeOpClaim(String kind, def fallback){
    String prev = OPCLAIM.replace("${device.id}:${kind}".toString(), "")
    if(prev == null) return (fallback ?: null) as String
    return prev ?: null
}

String mdnsQuery(){
    String instance=(settings.mdnsServiceName ?: state.mdnsInstance ?: "").toString().trim()
    if(!instance) return MDNS_PTR_QUERY
    String suffix="._hap._tcp.local"
    if(instance.toLowerCase().endsWith(suffix+".")) instance=instance.substring(0,instance.length()-1)
    if(instance.toLowerCase().endsWith(suffix)) instance=instance.substring(0,instance.length()-suffix.length())
    byte[] label=instance.getBytes("UTF-8")
    if(label.length<1 || label.length>63) throw new IllegalArgumentException("HomeKit mDNS service name must be 1-63 UTF-8 bytes")
    String owner=hx([label.length] as byte[])+hx(label)+"045f686170045f746370056c6f63616c00"
    return "000000000002000000000000"+owner+"00210001"+owner+"00100001"
}
def mdnsThen(String op){
    if(!settings.ip){ log.warn "HAP: set IP first"; return }
    state.mdnsTries=0; state.mdnsReplies=0; state.mdnsPayloads=0
    state.afterMdns = op; armOpClaim("mdns", op)
    mdnsSend(op, false)
}
void mdnsSend(String op, boolean multicast){
    String q=multicast ? mdnsQuery() : MDNS_PTR_QUERY
    state.afterMdns = op
    armOpClaim("mdns", op)
    state.mdnsMulticast=multicast
    runIn(4,"mdnsTimeout") // unicast SRV reply is sub-second when the accessory is up; 4s is ample and halves the pre-sweep cost
    sendHubCommand(new hubitat.device.HubAction(q, hubitat.device.Protocol.LAN,
        [destinationAddress:multicast ? "224.0.0.251:5353" : "${settings.ip}:5353",
         type:hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
         encoding:hubitat.device.HubAction.Encoding.HEX_STRING,
         timeout:4, callback:"mdnsCallback"]))
}
def mdnsTimeout(){
    def op=takeOpClaim("mdns", state.afterMdns); state.afterMdns=null; if(!op) return
    int tries=(state.mdnsTries?:0) as int
    if(!state.mdnsMulticast && tries < 2){   // the port can change after a reboot/power-cycle, so getting the CURRENT one matters
        state.mdnsTries=tries+1
        log.warn "HAP: mDNS port detect timed out — retry ${state.mdnsTries}/2"
        mdnsSend(op, false); return
    }
    if(!state.mdnsMulticast && (settings.mdnsServiceName || state.mdnsInstance)){
        try{ mdnsSend(op, true); return }
        catch(e){ log.warn "HAP: targeted mDNS unavailable (${e.message}); continuing recovery"; state.afterMdns=null }
    }
    state.mdnsTries=0
    // no reply at the pinned IP — the accessory may have a new DHCP address. The subnet SWEEP (relocate) is the
    // expensive part (multicast /24), so gate it to ≤1/SWEEP_INTERVAL_SEC: an offline accessory can't pin the hub
    // sweeping every cycle. Between sweeps, just try the last-known port and let the backoff pace retries.
    long sinceSweep = now() - (state.lastSweep ?: 0L)
    if(settings.accPairingId && sinceSweep >= SWEEP_INTERVAL_SEC*1000L){
        state.lastSweep = now(); state.relocTries = 0
        log.warn "HAP: no matching mDNS reply - trying extended multicast discovery"
        relocate(op); return
    }
    log.warn "HAP: mDNS port detect timed out (${state.mdnsReplies ?: 0} callbacks, ${state.mdnsPayloads ?: 0} hex payloads); using last-known port"
    if(!(settings.mdnsServiceName || state.mdnsInstance)) log.warn "HAP: set HomeKit mDNS service name to target this accessory; general browse may return another accessory first"
    dispatchOp(op)
}
// Accessory not answering at its saved IP? Browse the whole subnet (multicast mDNS) and match OUR accessory
// by its HomeKit id (accPairingId) to pick up a new DHCP-assigned IP, then reconnect. Best-effort — a DHCP
// reservation is the reliable fix, but this recovers automatically when the address drifts.
def relocate(String op){
    String q
    try{ q=(settings.mdnsServiceName || state.mdnsInstance) ? mdnsQuery() : MDNS_PTR_QUERY }
    catch(e){ log.warn "HAP: invalid mDNS service name; using general relocation search"; q=MDNS_PTR_QUERY }
    state.relocTries = ((state.relocTries ?: 0) as int) + 1
    state.afterRelocate = op; armOpClaim("relocate", op)
    runIn(8,"relocateTimeout")
    sendHubCommand(new hubitat.device.HubAction(q, hubitat.device.Protocol.LAN,
        [destinationAddress:"224.0.0.251:5353",
         type:hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
         encoding:hubitat.device.HubAction.Encoding.HEX_STRING,
         timeout:6, callback:"relocateCallback"]))
}
def relocateTimeout(){
    def op=takeOpClaim("relocate", state.afterRelocate); state.afterRelocate=null; if(!op) return
    log.warn "HAP: could not find the accessory on the network (it may be offline — a DHCP reservation is recommended); trying last-known IP"
    dispatchOp(op)
}
def relocateCallback(message){
    try{
        if(!state.afterRelocate) return
        def op=state.afterRelocate
        String h=mdnsPayload(message)
        if(!h){ rep "No DNS payload in relocation callback"; unschedule("relocateTimeout"); state.afterRelocate=null; dispatchOp(op); return }
        def r=parseMdns(h, (settings.accPairingId ?: "").toString())
        String want=(settings.accPairingId ?: "").toString().toUpperCase()
        if(want && r.id==want && r.ip && r.port){
            state.mdnsInstance=r.instance
            if(r.ip && r.ip != settings.ip){ device.updateSetting("ip",[value:r.ip,type:"string"]); logInfo "HAP: accessory moved — IP updated to ${r.ip}"; sendEvent(name:"hapStatus", value:"IP updated to ${r.ip}") }
            if(r.port){ device.updateSetting("port",[value:r.port,type:"number"]); state.discoveredPort=r.port }
            unschedule("relocateTimeout")
            def claimed=takeOpClaim("relocate", state.afterRelocate); state.afterRelocate=null; if(claimed) dispatchOp(claimed)
        } else {
            unschedule("relocateTimeout")
            // another HAP responder answered first and hid ours, so re-browse rather than burning the whole sweep window
            if(((state.relocTries ?: 0) as int) < RELOCATE_MAX_TRIES){ relocate(op); return }
            def claimed=takeOpClaim("relocate", state.afterRelocate); state.afterRelocate=null; if(claimed) dispatchOp(claimed)
        }
    }catch(e){
        log.error "relocateCallback: ${e}"
        def op=takeOpClaim("relocate", state.afterRelocate); unschedule("relocateTimeout"); state.afterRelocate=null; if(op) dispatchOp(op)
    }
}
def mdnsCallback(message){
    try {
        if(!state.afterMdns) return
        state.mdnsReplies=((state.mdnsReplies ?: 0) as int)+1
        String desc = message.toString(); if(settings.debugLog) log.debug "HAP mdns raw: ${desc}"
        String h=mdnsPayload(message)
        if(!h){ def op=takeOpClaim("mdns", state.afterMdns); unschedule("mdnsTimeout"); state.afterMdns=null; rep "No DNS payload in mDNS callback"; if(op) dispatchOp(op); return }
        state.mdnsPayloads=((state.mdnsPayloads ?: 0) as int)+1
        String sourceIp=mdnsSourceIp(message)
        String want=(settings.accPairingId ?: "").toString().toUpperCase()
        def r = parseMdns(h, want, (settings.ip ?: "").toString(), state.mdnsMulticast ? "" : sourceIp)
        // A TXT id match IS the identity proof, so honour it wherever the reply arrived from — that is the only
        // way a DHCP-moved accessory can be recovered. Address filtering is for replies we cannot identify.
        boolean identified = want && r.id && r.id==want
        if(!identified){
            if(sourceIp && sourceIp!=settings.ip){ def op=takeOpClaim("mdns", state.afterMdns); unschedule("mdnsTimeout"); state.afterMdns=null; rep "unidentified mDNS reply came from another IP"; if(op) dispatchOp(op); return }
            if(r.ip && r.ip!=settings.ip){ def op=takeOpClaim("mdns", state.afterMdns); unschedule("mdnsTimeout"); state.afterMdns=null; rep "unidentified mDNS address differs from configured IP"; if(op) dispatchOp(op); return }
        }
        if(!r.port){ def op=takeOpClaim("mdns", state.afterMdns); unschedule("mdnsTimeout"); state.afterMdns=null; rep "No matching HAP SRV in mDNS reply"; if(op) dispatchOp(op); return }
        state.mdnsInstance=r.instance
        if(identified && r.ip && r.ip!=settings.ip){
            device.updateSetting("ip",[value:r.ip,type:"string"]); logInfo "HAP: accessory moved — IP updated to ${r.ip}"; sendEvent(name:"hapStatus", value:"IP updated to ${r.ip}")
        }
        device.updateSetting("port",[value:r.port,type:"number"]); state.discoveredPort=r.port; state.mdnsTries=0; logInfo "HAP: detected port ${r.port}"
        unschedule("mdnsTimeout")
        def op=takeOpClaim("mdns", state.afterMdns); state.afterMdns=null; if(op) dispatchOp(op)
    } catch(e){
        log.error "mdnsCallback: ${e}"
        def op=takeOpClaim("mdns", state.afterMdns); unschedule("mdnsTimeout"); state.afterMdns=null; if(op) dispatchOp(op)
    }
}
String mdnsSourceIp(def message){
    def source=null
    if(!(message instanceof CharSequence)){
        try{ source=message.ip }catch(ignored){}
        if(!source){ try{ message=message.description }catch(ignored){} }
    }
    if(!source && message instanceof CharSequence){
        try{ source=parseLanMessage(message.toString())?.ip }catch(ignored){}
    }
    String value=source?.toString()
    if(value==~/(?i)[0-9a-f]{8}/){
        byte[] address=hex(value)
        return address.collect{ it&0xff }.join(".")
    }
    if(value==~/[0-9]{1,3}(\.[0-9]{1,3}){3}/ && value.split(/\./).every{ it.toInteger()<=255 }) return value
    return null
}
String mdnsPayload(def message){
    def payload=null
    if(!(message instanceof CharSequence)){
        try{ payload=message.payload }catch(ignored){}
        if(!payload){ try{ payload=message.body }catch(ignored){} }
        if(!payload){ try{ message=message.description }catch(ignored){} }
    }
    if(!payload && message instanceof CharSequence){
        String description=message.toString()
        try{ def parsed=parseLanMessage(description); payload=parsed?.payload ?: parsed?.body }catch(ignored){}
        if(!payload && description==~/(?i)(?:[0-9a-f]{2})+/) payload=description
    }
    String value=payload?.toString()?.trim()
    return value && value==~/(?i)(?:[0-9a-f]{2})+/ ? value : null
}
// minimal mDNS/DNS answer walker -> [ip, port, sf, id]
Map parseMdns(String h, String wantId="", String wantIp="", String sourceIp=""){
    byte[] b; try { b=hex(h) } catch(e){ return [:] }
    def res=[ip:null, port:null, sf:-1, id:null]
    if(b==null || b.length<12 || (b[2]&0x80)==0 || (b[3]&0x0f)!=0) return res
    int qd=((b[4]&0xff)<<8)|(b[5]&0xff)
    int tot=(((b[6]&0xff)<<8)|(b[7]&0xff))+(((b[8]&0xff)<<8)|(b[9]&0xff))+(((b[10]&0xff)<<8)|(b[11]&0xff))
    int p=12
    for(int question=0;question<qd;question++){
        def name=mdnsName(b,p); if(!name || name.next+4>b.length) return res
        p=name.next+4
    }
    def services=[:], addresses=[:]
    for(int record=0;record<tot;record++){
        def owner=mdnsName(b,p); if(!owner) return res
        p=owner.next; if(p+10>b.length) return res
        int type=((b[p]&0xff)<<8)|(b[p+1]&0xff)
        int recordClass=((b[p+2]&0x7f)<<8)|(b[p+3]&0xff)
        boolean alive=(b[p+4]|b[p+5]|b[p+6]|b[p+7])!=0
        int rdlen=((b[p+8]&0xff)<<8)|(b[p+9]&0xff); int rd=p+10
        if(rd+rdlen>b.length) return res
        if(recordClass==1 && alive){
            if(type==0x01 && rdlen==4){
                addresses[owner.name]="${b[rd]&0xff}.${b[rd+1]&0xff}.${b[rd+2]&0xff}.${b[rd+3]&0xff}"
            } else if(owner.name.endsWith("._hap._tcp.local")){
                def service=services[owner.name] ?: [sf:-1, instance:owner.name]
                if(type==0x21 && rdlen>=7){
                    def target=mdnsName(b,rd+6)
                    if(!target || target.next!=rd+rdlen) return res
                    service.port=((b[rd+4]&0xff)<<8)|(b[rd+5]&0xff)
                    service.target=target.name
                } else if(type==0x10){
                    int cursor=rd
                    while(cursor<rd+rdlen){
                        int length=b[cursor++]&0xff
                        if(cursor+length>rd+rdlen) return res
                        String entry=new String(b,cursor,length,"UTF-8")
                        int separator=entry.indexOf("=")
                        if(separator>0){
                            String key=entry.substring(0,separator).toLowerCase()
                            String value=entry.substring(separator+1)
                            if(key=="id") service.id=value.toUpperCase()
                            else if(key=="sf"){ try{ service.sf=Integer.parseInt(value) }catch(ignored){} }
                        }
                        cursor+=length
                    }
                }
                services[owner.name]=service
            }
        }
        p=rd+rdlen
    }
    String wanted=wantId.toUpperCase()
    boolean fromConfiguredIp=wantIp && sourceIp==wantIp
    def matches=services.values().findAll{ service->
        boolean identityMatches=wanted ? (service.id ? service.id==wanted : fromConfiguredIp) : true
        boolean addressMatches=wanted || !wantIp || addresses[service.target]==wantIp || (fromConfiguredIp && !addresses[service.target])
        service.port && identityMatches && addressMatches
    }
    if(matches.size()!=1) return res
    def selected=matches[0]
    return [ip:addresses[selected.target], port:selected.port, sf:selected.sf, id:selected.id, instance:selected.instance]
}
Map mdnsName(byte[] packet, int offset){
    def labels=[]
    def visited=[] as Set
    int cursor=offset, next=-1
    while(cursor<packet.length && visited.add(cursor)){
        int length=packet[cursor]&0xff
        if(length==0) return [name:labels.join(".").toLowerCase(), next:next<0 ? cursor+1 : next]
        if((length&0xc0)==0xc0){
            if(cursor+1>=packet.length) return null
            if(next<0) next=cursor+2
            cursor=((length&0x3f)<<8)|(packet[cursor+1]&0xff)
        } else {
            if((length&0xc0)!=0 || cursor+1+length>packet.length) return null
            labels << new String(packet,cursor+1,length,"UTF-8")
            cursor+=1+length
        }
    }
    return null
}
int skipName(byte[] b, int p){ while(p<b.length){ int l=b[p]&0xff; if(l==0) return p+1; if((l&0xC0)==0xC0) return p+2; p+=1+l }; return p }
void dispatchOp(String op){ if(op=="pairsetup") pairConnect() else if(op=="live") liveConnect() else if(op in ["read","discover","write","unpair"]) hapStart(op, op=="write"? state.writeJson : null) }
int hapPort(){ return (state.discoveredPort ?: settings.port ?: 0) as int }

// ===== pair-setup (SRP-6a) =====
def pair(){
    if(!settings.setupCode){ log.error "Enter the HomeKit setup code first"; return }
    if(!settings.ip){ log.error "Set the accessory IP first"; return }
    state.pairRetried = false   // a fresh request gets the method ladder again (remembered method stays)
    mdnsThen("pairsetup")
}
void pairConnect(){
    if(hapPort()<=0){ log.error "HAP: no port (mDNS failed and none configured)"; return }
    state.op="pairsetup"; state.sess=false; state.psstage="2"; rxbuf().setLength(0); plainbuf().setLength(0)
    sendEvent(name:"hapStatus", value:"pairing")
    try { interfaces.rawSocket.connect([byteInterface:true], settings.ip, hapPort()) }
    catch(e){
        // some accessories briefly drop their HAP listener after a failed/aborted pair attempt — retry, same port
        if(e.toString().contains("refused") && (state.connTry?:0) < 4){
            state.connTry=((state.connTry?:0) as int)+1
            log.warn "HAP: connect refused — retry ${state.connTry}/4 in 12s"
            sendEvent(name:"hapStatus", value:"connect refused — retry ${state.connTry}/4")
            runIn(12,"pairConnect"); return
        }
        log.error "connect: $e"; state.connTry=0; return
    }
    state.connTry=0
    int method = pairMethod()
    rep("pair-setup M1 method=${method}")
    sendHttpTlv("/pair-setup", tlv([[6,[1] as byte[]],[0,[method] as byte[]]]))   // State=M1, Method 0/1
}
// Which pair-setup method to send. Method 0 (Pair Setup) is the default and what every accessory paired here has
// used; some — an iSmartGate bridge, GitHub issue #1 — answer it with 0x01 "unknown" while advertising themselves
// as pairable, and pair only with Method 1 (Pair Setup with Auth). Choosing by the accessory's ff flag would move
// working accessories onto an untried path (an ecobee advertises ff=1 and pairs fine with Method 0), so instead we
// try 0 and retry once with 1 — see pairMethodAfterError(). The method that worked is remembered for this device.
private int pairMethod(){
    if(settings.pairMethod in ["0","1"]) return settings.pairMethod as int   // support override
    return ((state.pairMethodUse ?: 0) as int)
}
// Method to retry with after an M2 error, or -1 to report the error and stop.
private int pairMethodAfterError(int err, int usedMethod, boolean alreadyRetried, def override){
    if(override in ["0","1"]) return -1        // operator pinned a method: don't second-guess it
    if(err != 1 || alreadyRetried || usedMethod != 0) return -1
    return 1
}
void routePS(Map tv){ if(state.psstage=="2") psM2(tv) else if(state.psstage=="4") psM4(tv) else psM6(tv) }
// decode a HAP pairing error (kTLVType_Error, 0x07) into a plain-English message
String pairErr(byte[] e){
    int c = (e && e.length>0) ? (e[0]&0xff) : 0
    String m = [ 1:"unknown error",
                 2:"wrong setup code",
                 3:"backoff — too many attempts, wait a bit and retry",
                 4:"accessory is full (max pairings reached)",
                 5:"accessory locked after too many failed tries — power-cycle it, then retry",
                 6:"accessory already paired or not in pairing mode — remove it from Apple Home (or reset its HomeKit), then retry",
                 7:"accessory busy — try again in a moment" ][c]
    return m ?: "error 0x${hx(e)}"
}
void psM2(Map tv){
    if(tv[7]!=null){
        int err = (tv[7] && tv[7].length>0) ? (tv[7][0]&0xff) : 0
        int retry = pairMethodAfterError(err, pairMethod(), (state.pairRetried == true), settings.pairMethod)
        if(retry >= 0){
            state.pairRetried = true; state.pairMethodUse = retry
            log.warn "HAP: accessory rejected pair-setup method ${pairMethod()} (0x${hx(tv[7])}) — retrying with Pair Setup with Auth"
            try{ interfaces.rawSocket.close() }catch(ig){}
            runIn(2, "pairConnect"); return
        }
        String m=pairErr(tv[7]); sendEvent(name:"hapStatus",value:"pair failed: ${m}"); log.error "HAP pair-setup M2 error 0x${hx(tv[7])}: ${m}"; interfaces.rawSocket.close(); return }
    if(tv[2]==null || tv[3]==null){ sendEvent(name:"hapStatus",value:"pair fail: no M2 (device busy? wait & retry)"); log.error "M2 missing salt/key"; interfaces.rawSocket.close(); return }
    byte[] salt=tv[2]; byte[] Bb=tv[3]; java.math.BigInteger B=beBig(Bb)
    java.math.BigInteger a=beBig(rnd32()); byte[] Ab=bigBe(SRP_G.modPow(a,SRP_N),384)
    java.math.BigInteger u=beBig(sha512(cat(Ab,Bb)))
    String code=(settings.setupCode?:"").replaceAll("[^0-9]",""); if(code.length()==8) code="${code[0..2]}-${code[3..4]}-${code[5..7]}"
    java.math.BigInteger x=beBig(sha512(cat(salt, sha512(("Pair-Setup:"+code).getBytes("UTF-8")))))
    java.math.BigInteger base=B.subtract(SRP_K.multiply(SRP_G.modPow(x,SRP_N))).mod(SRP_N)
    byte[] K=sha512(bigBe(base.modPow(a.add(u.multiply(x)),SRP_N),384))
    byte[] hN=sha512(bigBe(SRP_N,384)); byte[] hg=sha512([5] as byte[]); byte[] hxor=new byte[64]; for(int i=0;i<64;i++) hxor[i]=(byte)(hN[i]^hg[i])
    byte[] M1=sha512(cat(hxor, sha512("Pair-Setup".getBytes("UTF-8")), salt, Ab, Bb, K))
    state.srpK=hx(K); state.srpA=hx(Ab); state.srpM1=hx(M1); state.psstage="4"; rxbuf().setLength(0)
    sendHttpTlv("/pair-setup", tlv([[6,[3] as byte[]],[3,Ab],[4,M1]]))
}
void psM4(Map tv){
    if(tv[7]!=null){ String m=pairErr(tv[7]); sendEvent(name:"hapStatus",value:"pair failed: ${m}"); log.error "HAP pair-setup M4 error 0x${hx(tv[7])}: ${m}"; interfaces.rawSocket.close(); return }
    byte[] expect=sha512(cat(hex(state.srpA), hex(state.srpM1), hex(state.srpK)))
    if(tv[4]==null || hx(tv[4])!=hx(expect)){ sendEvent(name:"hapStatus",value:"pair fail (server proof)"); interfaces.rawSocket.close(); return }
    byte[] K=hex(state.srpK)
    byte[] encKey=hkdf("Pair-Setup-Encrypt-Salt".getBytes("UTF-8"), K, "Pair-Setup-Encrypt-Info".getBytes("UTF-8"),32)
    byte[] iosX=hkdf("Pair-Setup-Controller-Sign-Salt".getBytes("UTF-8"), K, "Pair-Setup-Controller-Sign-Info".getBytes("UTF-8"),32)
    byte[] seed=rnd32(); byte[] ltpk=edPub(seed); String pid=uuidStr()
    byte[] sig=edSign(seed, cat(iosX, pid.getBytes("UTF-8"), ltpk))
    byte[] sub=tlv([[1,pid.getBytes("UTF-8")],[3,ltpk],[10,sig]])
    byte[] enc=chachaEnc(encKey, nlabel("PS-Msg05"), sub, null)
    state.psSeed=hx(seed); state.psPid=pid; state.psEncKey=hx(encKey); state.psstage="6"; rxbuf().setLength(0)
    sendHttpTlv("/pair-setup", tlv([[6,[5] as byte[]],[5,enc]]))
}
void psM6(Map tv){
    if(tv[7]!=null){ String m=pairErr(tv[7]); sendEvent(name:"hapStatus",value:"pair failed: ${m}"); log.error "HAP pair-setup M6 error 0x${hx(tv[7])}: ${m}"; interfaces.rawSocket.close(); return }
    byte[] dec=chachaDec(hex(state.psEncKey), nlabel("PS-Msg06"), tv[5], null); def t2=tdec(dec)
    byte[] accLtpk=t2[3]; byte[] accId=t2[1]; byte[] accSig=t2[10]
    byte[] accX=hkdf("Pair-Setup-Accessory-Sign-Salt".getBytes("UTF-8"), hex(state.srpK), "Pair-Setup-Accessory-Sign-Info".getBytes("UTF-8"),32)
    if(!edVerify(accLtpk, cat(accX, accId, accLtpk), accSig)){ sendEvent(name:"hapStatus",value:"pair fail (accessory verify)"); interfaces.rawSocket.close(); return }
    device.updateSetting("iosLtsk",[value:state.psSeed,type:"string"])
    device.updateSetting("iosPairingId",[value:state.psPid,type:"string"])
    device.updateSetting("accLtpk",[value:hx(accLtpk),type:"string"])
    device.updateSetting("accPairingId",[value:new String(accId,"UTF-8"),type:"string"])
    device.updateSetting("setupCode",[value:"",type:"string"])
    state.paired=true
    ["srpK","srpA","srpM1","psSeed","psEncKey","psPid","shared"].each{ state.remove(it) }   // tidy one-time pairing secrets
    sendEvent(name:"hapStatus", value:"paired"); logInfo "HAP: paired OK, keys stored"
    interfaces.rawSocket.close()
    if(metaClass.respondsTo(this,"onPaired")) onPaired()
    runIn(3,"startSession")
}

// ===== remove pairing (HAP RemovePairing — the "exclude" of HomeKit) =====
// Cleanly releases this controller from the accessory so the slot is freed and the
// accessory becomes pairable again. Universal: works for any HAP accessory.
def unpair(){
    if(!isPaired()){ log.warn "HAP: not paired — nothing to release"; clearLocalPairing(); if(metaClass.respondsTo(this,"onUnpaired")) onUnpaired(); return }
    if(!settings.ip){ log.error "Set the accessory IP first"; return }
    logInfo "HAP: releasing pairing (RemovePairing) — accessory will become pairable again"
    state.live=false; unschedule("liveKeepalive"); unschedule("kaWatch")
    try{ interfaces.rawSocket.close() }catch(e){}
    runIn(2,"unpairStart")
}
def unpairStart(){ state.unpairDone=false; runIn(40,"unpairTimeout"); mdnsThen("unpair") }
def unpairTimeout(){ if(state.unpairDone!=true){ log.warn "HAP: unpair didn't complete (accessory unreachable?). Use Forget to clear locally, then reset HomeKit on the device."; sendEvent(name:"hapStatus", value:"unpair timeout") } }
// RemovePairing M1: State=1, Method=4 (RemovePairing), Identifier=our controller pairing id.
// Body bytes are all <=127 (small enums + an ASCII UUID), so the UTF-8 path in sendEncrypted is byte-safe.
String removePairingReq(){
    byte[] body=tlv([[6,[1] as byte[]],[0,[4] as byte[]],[1, settings.iosPairingId.getBytes("UTF-8")]])
    String bs=new String(body,"ISO-8859-1")
    return "POST /pairings HTTP/1.1\r\nHost: ${settings.ip}\r\nContent-Type: application/pairing+tlv8\r\nContent-Length: ${body.length}\r\nConnection: keep-alive\r\n\r\n"+bs
}
void finishUnpair(byte[] resp){
    state.unpairDone=true; unschedule("unpairTimeout")
    int bi=-1
    for(int i=0;i+3<resp.length;i++){ if((resp[i]&0xff)==13&&(resp[i+1]&0xff)==10&&(resp[i+2]&0xff)==13&&(resp[i+3]&0xff)==10){ bi=i; break } }
    String head = bi>=0 ? new String(resp,0,bi,"UTF-8") : new String(resp,"UTF-8")
    byte[] body = new byte[0]
    if(bi>=0 && bi+4<=resp.length){ int n=resp.length-(bi+4); body=new byte[n]; for(int i=0;i<n;i++) body[i]=resp[bi+4+i] }
    if(head.toLowerCase().contains("chunked")) body=dechunk(body)
    def tv = (body.length>=2) ? tdec(body) : [:]
    // HAP carries pairing-op errors in the TLV Error field (kTLVType_Error=7), not the HTTP status
    boolean ok = (tv[7]==null) && (head.contains("200")||head.contains("204"))
    if(ok){
        clearLocalPairing()
        if(metaClass.respondsTo(this,"onUnpaired")) onUnpaired()
        sendEvent(name:"hapStatus", value:"unpaired")
        logInfo "HAP: RemovePairing OK — accessory released and local keys cleared; it is now pairable again"
    } else {
        String err = tv[7]!=null ? pairErr(tv[7]) : (head.split("\r\n")[0])
        log.warn "HAP: RemovePairing failed (${err}). If the accessory is offline, use Forget and reset HomeKit on the device."
        sendEvent(name:"hapStatus", value:"unpair failed")
    }
}
// clear only OUR side of the pairing (keys + session state). Does NOT notify the accessory.
void clearLocalPairing(){
    state.paired=false; state.live=false
    ["iosLtsk","iosPairingId","accLtpk","accPairingId","setupCode"].each{ device.removeSetting(it) }
    ["c2a","a2c","shared","services","discoveredPort","writeJson","mdnsInstance","afterMdns","afterRelocate","mdnsMulticast"].each{ state.remove(it) }
    unschedule()
}
// byte-level chunked de-coder (the /pairings TLV reply is binary, so we can't use the string path)
byte[] dechunk(byte[] b){
    def o=new java.io.ByteArrayOutputStream(); int i=0
    while(i<b.length){
        int nl=-1; for(int k=i;k+1<b.length;k++){ if((b[k]&0xff)==13&&(b[k+1]&0xff)==10){ nl=k; break } }
        if(nl<0) break
        int n; try{ n=Integer.parseInt(new String(b,i,nl-i,"UTF-8").trim(),16) }catch(e){ break }
        if(n==0) break
        int start=nl+2; if(start+n>b.length) break
        o.write(b,start,n); i=start+n+2
    }
    return o.toByteArray()
}

// ===== generic /characteristics write =====
void writeChars(List entries){
    def parts = entries.collect{ e-> def jv=(e[2] instanceof Boolean)? e[2] : ((e[2] instanceof Number)? e[2] : "\"${e[2]}\""); "{\"aid\":${e[0]},\"iid\":${e[1]},\"value\":${jv}}" }
    String b = "{\"characteristics\":[${parts.join(',')}]}"; state.writeJson = b
    if(state.live && state.sess){ sendEncrypted("PUT /characteristics HTTP/1.1\r\nHost: ${settings.ip}\r\nContent-Type: application/hap+json\r\nContent-Length: ${b.getBytes('UTF-8').length}\r\nConnection: keep-alive\r\n\r\n"+b); runIn(2,"liveKeepalive") }
    else { hapStart("write", b) }
}
void writeChar(long aid, int iid, val){ writeChars([[aid,iid,val]]) }

// ===== one-shot session for read/discover/write when not live =====
def refresh(){
    if(state.live && state.sess){
        String gids=readIds(); String req="GET /characteristics?id=${gids} HTTP/1.1\r\nHost: ${settings.ip}\r\n\r\n"
        dlog("TX get ids=${gids.split(',').size()} reqLen=${req.length()}")
        sendEncrypted(req)
    }
    else if(onDemand()){ dlog("refresh: one-shot read"); hapStart("read", null) }
    else { dlog("refresh: not live/sess -> startLive"); startLive() }
}
// ===== connection mode entry point =====
// discover topology if needed, then either open a persistent session or set up polling
def startSession(){
    if(!isPaired()){ log.warn "HAP: not paired"; return }
    armHealth()   // start (or restart) the online/offline health tracker whenever a session is (re)established
    if(onDemand()){
        unschedule("liveKeepalive"); unschedule("kaWatch"); state.live=false
        sendEvent(name:"hapStatus", value:"on-demand")
        schedulePoll()   // ALWAYS armed, so discovery/reads keep retrying even when one attempt fails
        if(state.services==null){ logInfo "HAP: discovering accessory services…"; discoverOnce() } else refresh()
    } else {
        if(state.services==null){ logInfo "HAP: discovering accessory services…"; discoverOnce() } else startLive()
    }
}
def discoverOnce(){ mdnsThen("discover") }   // -> hapStart(discover) -> onAccessories -> finish -> startSession
// persistent-mode recovery heartbeat: if paired but not live, re-establish. CRITICAL: a connInFlight older
// than ~20s is a STALE latch left by a connect that died without clearing the flag (a discover/verify that
// neither closed cleanly nor tripped its watchdog). The old guard was a bare `!state.connInFlight`, so a stale
// latch blocked this backstop FOREVER — turning a transient death into "dead until the user hits Save". Now we
// clear a stale latch and reconnect; only an actually-in-progress connect (<20s old) is left alone.
def ensureUp(){
    if(!isPaired() || onDemand()) return
    // Do NOT blindly trust state.live: a zombie session leaves the flag TRUE while no data flows, and the old
    // `state.live==true` bail made this backstop skip such a session on every tick — turning a missed reconnect
    // into "dead until the user hits Save" (forum #202). Treat live as REAL only if data is also fresh; a stale
    // lastRx despite live=true is a dead session the probe loop missed, so reconnect it.
    long stale = now() - (state.lastRx ?: 0L)
    if(state.live==true && stale < offlineAfterSecs()*1000L) return
    if(state.connInFlight && (now()-(state.connAt?:0) < 20000)) return
    if(state.connInFlight){ dlog("ensureUp: clearing stale connInFlight=${state.connInFlight}"); state.connInFlight=null }
    dlog("ensureUp -> reconnect (live=${state.live}, ${(int)(stale/1000)}s since rx)"); startSession()
}
def schedulePoll(){ unschedule("pollRead"); runIn(pollSecs(),"pollRead") }
def pollRead(){ if(isPaired() && onDemand()){ if(state.services==null) discoverOnce() else refresh(); schedulePoll() } }
// Open the HAP socket with TCP keepalive (Hubitat 2.5.1.145+), then FALL BACK to a plain connect on any build
// where the extended socket options aren't allowed yet (older firmware throws SecurityException getMethod — the
// sandbox blocks the reflection). SO_KEEPALIVE holds the socket warm and lets the OS surface a dead peer, which is
// how a real HomeKit controller keeps a session alive WITHOUT polling — the thing that lets a cheap chip (Meross)
// hold a persistent session when its liveness probe is turned down/off. Safe everywhere: keepalive where supported,
// identical-to-before plain connect where not.
private void hapConnect(){
    try { interfaces.rawSocket.connect([byteInterface:true, keepAlive:true, tcpKeepIdle:25, tcpKeepInterval:10, tcpKeepCount:3], settings.ip, hapPort()) }
    catch(e){
        if(e?.toString()?.contains("getMethod")){ try{ interfaces.rawSocket.close() }catch(ig){}; interfaces.rawSocket.connect([byteInterface:true], settings.ip, hapPort()) }
        else throw e
    }
}
def hapStart(String op, String body){
    if(!settings.ip || hapPort()<=0){ log.warn "HAP: set IP first (port auto-detects)"; return }
    // single connection slot: never start a second connect while one is in flight (overlapping
    // connects corrupt the handshake and wedge single-slot accessories like the Meross)
    if(state.connInFlight && (now()-(state.connAt?:0) < 14000)){
        rep("hapStart skip ${op}: ${state.connInFlight} in-flight")
        if(op=="write" && (state.wretry?:0)<3){ state.wretry=(state.wretry?:0)+1; runIn(7,"retryWrite") }
        return
    }
    state.connInFlight=op; state.connAt=now()
    state.op=op; state.inCtr=0; state.outCtr=0; rxbuf().setLength(0); plainbuf().setLength(0)
    state.sess=false; state.vstage="m2"
    def ek=genEph(); state.ephPriv=ek.priv; state.ephPub=ek.pub
    sendEvent(name:"hapStatus", value:"connecting")
    try { hapConnect() }
    catch(e){ log.error "connect: $e"; rep("ERR connect $e"); return }
    sendHttpTlv("/pair-verify", tlv([[6,[1] as byte[]],[3,hex(state.ephPub)]]))
    unschedule("oneshotWatch"); runIn(12,"oneshotWatch")   // if verify hangs, close so we don't leave a half-open socket (which wedges single-slot accessories)
}
// one-shot (read/write/discover/unpair) connect watchdog: close on a stalled verify; retry a write once
def oneshotWatch(){
    if(!state.sess && state.op in ["read","write","discover","unpair"]){
        log.warn "HAP: ${state.op} connect/verify stalled — closing socket"
        try{ interfaces.rawSocket.close() }catch(e){}
        state.connInFlight=null
        sendEvent(name:"hapStatus", value:"${state.op} timeout")
        if(state.op=="write" && (state.wretry?:0)<3){ state.wretry=(state.wretry?:0)+1; runIn(10,"retryWrite") }
    }
}
def retryWrite(){ if(state.writeJson){ logInfo "HAP: retrying write"; hapStart("write", state.writeJson) } }
void sendHttpTlv(String path, byte[] b){ String h="POST ${path} HTTP/1.1\r\nHost: ${settings.ip}\r\nContent-Type: application/pairing+tlv8\r\nContent-Length: ${b.length}\r\nConnection: keep-alive\r\n\r\n"; interfaces.rawSocket.sendMessage(hx(cat(h.getBytes("UTF-8"),b))) }
void sendEncrypted(String req){ byte[] plain=req.getBytes("UTF-8"); def o=new java.io.ByteArrayOutputStream(); long ctr=state.outCtr
    for(int i=0;i<plain.length;i+=1024){ int n=Math.min(1024,plain.length-i); byte[] ch=new byte[n]; for(int j=0;j<n;j++) ch[j]=plain[i+j]
        byte[] aad=le16(n); byte[] ct=chachaEnc(hex(state.c2a),nctr(ctr),ch,aad); ctr++; o.write(aad,0,2); o.write(ct,0,ct.length) }
    state.outCtr=ctr; interfaces.rawSocket.sendMessage(hx(o.toByteArray())) }
def socketStatus(String s){
    String l = s?.toLowerCase() ?: ""
    if(l.contains("close") || l.contains("error")) state.connInFlight=null
    if(state.live && (l.contains("close") || l.contains("error"))){ state.live=false; sendEvent(name:"hapStatus", value:"reconnecting"); logInfo "HAP: live socket dropped (${s}); reconnecting"; runIn(8,"startLive") }
    else if(!l.contains("close")) log.warn "socket: $s"
}

// ===== socket receive + framing =====
def parse(String message){
  try {
    rxbuf().append(message.toLowerCase())
    if(!state.sess){
        String buf=rxbuf().toString()
        int p=buf.indexOf("0d0a0d0a"); if(p<0) return
        String hh=new String(hex(buf.substring(0,p))); def m=(hh =~ /(?i)content-length:\s*(\d+)/); int cl=m.find()?(m.group(1) as int):0
        int need=p+8+cl*2; if(buf.length()<need) return
        byte[] body=hex(buf.substring(p+8,need)); rxbuf().delete(0,need); def tv=tdec(body)
        if(state.op=="pairsetup"){ routePS(tv) }
        else if(state.vstage=="m4"){ doM4(tv) } else { doM2(tv) }
    } else { handleSession() }
  } catch(Throwable e){
    // A decrypt/tag mismatch means the encrypted session desynced (accessory rebooted or re-keyed under us) —
    // every subsequent frame then fails to decrypt, so reconnect NOW (re-runs pair-verify for fresh keys) instead
    // of waiting out the ~30-min silence watchdog. This is EXPECTED and self-healing, so it logs at warn, NOT
    // error — only genuinely unexpected exceptions get error level. (More sensors => more event frames => more
    // chances to be mid-stream on a re-key, so on busy thermostats this fires often but harmlessly.)
    String es=e.toString()
    if(state.live && (es.contains("AEADBadTag") || es.contains("Tag mismatch") || es.contains("BadPadding"))){
        dlog("HAP: session desynced (decrypt failed) — reconnecting for fresh keys")   // benign self-healing re-key; debug-only (fires often on busy multi-sensor thermostats)
        rep("ERR parse ${state.op}/${state.vstage}: ${e.class.simpleName}: ${e.message}")
        state.live=false; state.sess=false; try{ interfaces.rawSocket.close() }catch(ig){}; state.connInFlight=null
        unschedule("liveKeepalive"); unschedule("kaWatch"); runIn(2,"startLive")
    } else {
        log.error "parse: ${e}"; rep("ERR parse ${state.op}/${state.vstage}: ${e.class.simpleName}: ${e.message}")
    }
  }
}
void doM2(Map tv){
    byte[] accPub=tv[3]; byte[] enc=tv[5]; byte[] shared=x25519(hex(state.ephPriv),accPub); state.shared=hx(shared)
    byte[] sk=hkdf("Pair-Verify-Encrypt-Salt".getBytes("UTF-8"),shared,"Pair-Verify-Encrypt-Info".getBytes("UTF-8"),32)
    def d1=tdec(chachaDec(sk,nlabel("PV-Msg02"),enc,null)); String accName=new String(d1[1],"UTF-8")
    if(!edVerify(hex(settings.accLtpk), cat(accPub,accName.getBytes("UTF-8"),hex(state.ephPub)), d1[10])){ rep("ERR acc sig"); interfaces.rawSocket.close(); return }
    byte[] iosInfo=cat(hex(state.ephPub),settings.iosPairingId.getBytes("UTF-8"),accPub)
    byte[] sub=tlv([[1,settings.iosPairingId.getBytes("UTF-8")],[10,edSign(hex(settings.iosLtsk),iosInfo)]])
    byte[] ct=chachaEnc(sk,nlabel("PV-Msg03"),sub,null); state.vstage="m4"; rxbuf().setLength(0)
    sendHttpTlv("/pair-verify", tlv([[6,[3] as byte[]],[5,ct]]))
}
void doM4(Map tv){
    if(tv[7]!=null){ String m=pairErr(tv[7]); sendEvent(name:"hapStatus",value:"session failed: ${m} (may need to re-pair)"); log.warn "HAP pair-verify error 0x${hx(tv[7])}: ${m}"; interfaces.rawSocket.close(); return }
    byte[] shared=hex(state.shared)
    state.c2a=hx(hkdf("Control-Salt".getBytes("UTF-8"),shared,"Control-Write-Encryption-Key".getBytes("UTF-8"),32))
    state.a2c=hx(hkdf("Control-Salt".getBytes("UTF-8"),shared,"Control-Read-Encryption-Key".getBytes("UTF-8"),32))
    state.sess=true; rxbuf().setLength(0); plainbuf().setLength(0); state.inCtr=0; state.wretry=0; state.lastRx=now()
    unschedule("oneshotWatch")
    sendEvent(name:"hapStatus", value:"session")
    if(state.op=="live"){
        state.live=true; state.vtry=0; state.kaMiss=0; state.connInFlight=null; unschedule("verifyWatch"); reOK(); setHealth("online")
        sendEvent(name:"hapStatus", value:"live"); logInfo "HAP: live session up — subscribing to events"
        dlog("session up (live) -> subscribe + get")
        unschedule("liveKeepalive"); state.probeAt=null; runIn(kaEvery(),"liveKeepalive")   // hold + liveness-probe the one session (probeAt reset so first tick doesn't false-fail)
        sendEncrypted(subscribeBody())
        String gids=readIds(); dlog("TX get(connect) ids=${gids.split(',').size()}")
        sendEncrypted("GET /characteristics?id=${gids} HTTP/1.1\r\nHost: ${settings.ip}\r\n\r\n")
    }
    else if(state.op=="read"){ sendEncrypted("GET /characteristics?id=${readIds()} HTTP/1.1\r\nHost: ${settings.ip}\r\n\r\n") }
    else if(state.op=="discover"){ sendEncrypted("GET /accessories HTTP/1.1\r\nHost: ${settings.ip}\r\n\r\n") }
    else if(state.op=="unpair"){ dlog("session up (unpair) -> RemovePairing"); sendEncrypted(removePairingReq()) }
    else { String b=state.writeJson; sendEncrypted("PUT /characteristics HTTP/1.1\r\nHost: ${settings.ip}\r\nContent-Type: application/hap+json\r\nContent-Length: ${b.getBytes('UTF-8').length}\r\nConnection: keep-alive\r\n\r\n"+b) }
}
void handleSession(){
    String buf=rxbuf().toString()
    while(buf.length()>=4){
        byte[] lh=hex(buf.substring(0,4)); int ln=(lh[0]&0xff)|((lh[1]&0xff)<<8); int need=4+(ln+16)*2
        if(buf.length()<need) break
        byte[] aad=hex(buf.substring(0,4)); byte[] blk=hex(buf.substring(4,need)); rxbuf().delete(0,need); buf=rxbuf().toString()
        byte[] pt=chachaDec(hex(state.a2c),nctr(state.inCtr),blk,aad); state.inCtr=(state.inCtr as long)+1; state.lastRx=now(); plainbuf().append(hx(pt))
        dlog("RXframe ln=${ln} inCtr=${state.inCtr} plain=${(int)(plainbuf().length()/2)}b rxLeft=${(int)(rxbuf().length()/2)}b")
        if(state.op!="live" && sessionResponseComplete(ln)){ finish(); return }
    }
    if(state.op=="live") processLiveStream()
}
// True once the decrypted buffer holds one COMPLETE HTTP response. The old test — "a frame shorter than 1024
// bytes ends the response" — was a guess about how the accessory chose to split its frames. An accessory that
// sends its header block in one small frame and the body in the next (iSmartGate, GitHub issue #1) ended the
// response at the headers, so /accessories parsed an empty body: "json ... Text must not be null or empty".
private boolean sessionResponseComplete(int lastFrameLen){
    String s = new String(hex(plainbuf().toString()), "ISO-8859-1")
    int he = s.indexOf("\r\n\r\n"); if(he < 0) return false          // headers still arriving
    String head = s.substring(0, he); int bodyStart = he + 4
    if(head.toLowerCase().contains("chunked")) return s.indexOf("0\r\n\r\n", bodyStart) >= 0
    def m = (head =~ /(?i)content-length:\s*(\d+)/)
    if(m.find()) return (s.length() - bodyStart) >= ((m.group(1) as int))
    return lastFrameLen < 1024    // no length and not chunked: keep the old end-of-response guess
}

void finish(){
    unschedule("oneshotWatch"); state.connInFlight=null
    byte[] resp=hex(plainbuf().toString()); String s=new String(resp,"UTF-8"); state.sess=false; state.vstage=null
    plainbuf().setLength(0); rxbuf().setLength(0)
    int bi=s.indexOf("\r\n\r\n"); String head=bi>=0? s.substring(0,bi):s; String body=bi>=0? s.substring(bi+4):""
    interfaces.rawSocket.close()
    if(state.op=="unpair"){ finishUnpair(resp); return }
    if(state.op=="write"){
        boolean ok = head.contains("204") || head.contains("200")
        sendEvent(name:"hapStatus", value: ok? "write ok":"write fail")
        rep("WRITE ${ok?'ok':'FAIL'} (${head.split('\r\n')[0]}) body=${state.writeJson}")
        runIn(3,"refresh"); return
    }
    if(head.toLowerCase().contains("chunked")){
        StringBuilder sb=new StringBuilder(); String rest=body
        while(rest.length()>0){ int nl=rest.indexOf("\r\n"); if(nl<0) break; int n=Integer.parseInt(rest.substring(0,nl).trim(),16); if(n==0) break; sb.append(rest.substring(nl+2,nl+2+n)); rest=rest.substring(nl+2+n+2) }
        body=sb.toString()
    }
    rep("ONESHOT ${head.split('\r\n')[0]} body=${body.length()}b")
    def j; try{ j=new groovy.json.JsonSlurper().parseText(body) }catch(e){ rep("ERR json ${e}; head=${head.split('\r\n')[0]}"); return }
    if(state.op=="discover"){ onAccessories(j); runIn(1,"startSession"); return }
    onCharacteristics(j)
}

// ===== live event mode (persistent session + subscriptions) =====
// ---- offline reconnect scheduling: one place that grows the retry gap on consecutive failures ----
private int reBackoff(){ int n=(state.reFails?:0) as int; return RE_BACKOFF_SEC[ Math.min(n, RE_BACKOFF_SEC.size()-1) ] }
// Every failed live reconnect funnels through here: count it, back off, and (after a couple) surface offline.
private void reFail(String why){
    state.reFails = ((state.reFails?:0) as int) + 1
    if((state.reFails as int) >= OFFLINE_AFTER_FAILS) setHealth("offline")   // direct signal — don't wait on lastRx staleness
    int d = reBackoff()
    logInfo "HAP: reconnect attempt ${state.reFails} failed (${why}) — next try in ${d}s"
    unschedule("startLive"); runIn(d, "startLive")
}
// A live session came up: clear the failure streak so the next outage starts from the short end of the ladder.
// The sweep window re-arms too — the accessory was just reachable, so a later move deserves an immediate browse.
private void reOK(){ if((state.reFails?:0) as int){ state.reFails=0 }; state.lastSweep=0L; state.remove("relocTries") }
// Try the CHEAP path first: known topology + cached port -> connect straight to the last-known ip:port (fails in
// ms with NoRouteToHost when the accessory is offline). The expensive multicast /24 subnet sweep (relocate, via
// mdnsThen) only runs when we have no port, or at most once per SWEEP_INTERVAL_SEC — so an offline accessory can't
// pin the hub sweeping every cycle. When the accessory is genuinely up, mDNS answers fast and this path is unchanged.
def startLive(){
    if(!isPaired()){ log.warn "HAP: not paired"; return }
    unschedule("liveKeepalive"); unschedule("kaWatch")
    boolean haveTopo = (state.services!=null && hapPort()>0)
    int n = (state.reFails?:0) as int
    int verifyFailures = (state.vtry?:0) as int
    // Fast path for a transient drop: reconnect straight to the last-known ip:port. But every 3rd consecutive
    // failure, re-resolve the port via unicast mDNS first — an accessory that rebooted often comes back on a NEW
    // dynamic HAP port, and hammering the stale cached port would never recover. Unicast mDNS is cheap when the host
    // is up; when it's down it times out and mdnsTimeout falls through to the SWEEP, which is itself gated to ≤1/30min.
    boolean reresolve = !haveTopo || (n>0 && n % 3 == 0) || (verifyFailures>0 && verifyFailures % 3 == 0)
    if(reresolve) mdnsThen(state.services==null ? "discover" : "live")
    else liveConnect()
}
void liveConnect(){
    if(hapPort()<=0){ log.warn "HAP: no port — re-resolving via mDNS"; state.lastSweep=0L; runIn(30,"startLive"); return }
    if(state.connInFlight && (now()-(state.connAt?:0) < 14000)){ rep("liveConnect skip: ${state.connInFlight} in-flight"); return }   // don't stack overlapping connects (wedges single-slot accessories)
    state.connInFlight="live"; state.connAt=now()
    state.op="live"; state.inCtr=0; state.outCtr=0; rxbuf().setLength(0); plainbuf().setLength(0); state.sess=false; state.vstage="m2"; state.live=false
    def ek=genEph(); state.ephPriv=ek.priv; state.ephPub=ek.pub
    sendEvent(name:"hapStatus", value:"connecting (live)")
    try { hapConnect() }
    catch(e){ log.warn "HAP: live connect failed: $e"; state.connInFlight=null; reFail("${e}"); return }
    sendHttpTlv("/pair-verify", tlv([[6,[1] as byte[]],[3,hex(state.ephPub)]]))
    unschedule("verifyWatch"); runIn(12,"verifyWatch")   // pair-verify must complete in 10s or we retry (Meross often stalls at M2)
}
// pair-verify watchdog: if the handshake didn't reach a session, close + retry with capped backoff,
// re-resolving the port via mDNS each time (the port and the single connection slot can both go stale).
// counting failures so startLive periodically re-resolves the port as well as retrying the connection.
def verifyWatch(){
    if(!state.sess){
        state.vtry=(state.vtry?:0)+1
        try{ interfaces.rawSocket.close() }catch(e){}; state.connInFlight=null
        int d=Math.min(120, 30*(state.vtry as int))
        logInfo "HAP: pair-verify timed out (no M2) — next try in ${d}s"
        unschedule("startLive"); runIn(d, "startLive")
    }
}
// HELD SESSION + LIVENESS PROBE (self-correcting — never trust the flag). A real HomeKit controller holds ONE
// session and gets instant ev:true events. On a cheap single-slot chip (Meross mt7687) an idle connection can
// silently die while state.live still reads true — pair-verify succeeding is NOT proof the session still works,
// and a silent half-open death fires no socketStatus close. So every interval we send a MINIMAL read of ONE
// characteristic (not the whole set — the heavy poll is what stressed cheap chips) and REQUIRE an inbound frame
// back. That keeps the pipe warm so the chip doesn't idle-close it (what Apple gets from TCP keepalive), AND
// proves liveness: an unanswered probe means the link is dead despite state.live, so we reconnect IMMEDIATELY
// instead of sitting on a stale flag until some far-off timer. Events still push instantly; the probe reply also
// happens to refresh the primary characteristic as a bonus. safetyRefreshSecs = probe interval (0 = disable).
def liveKeepalive(){
    if(!(state.live && state.sess)){ startLive(); return }
    int iv = safetySecs()
    if(iv<=0){   // probing disabled -> only reconnect after a very long total silence (legacy pure-listen)
        if((now()-(state.lastRx?:0L)) >= SILENCE_RECONNECT_SEC*1000L){ reconnectLive("silent ${SILENCE_RECONNECT_SEC}s"); return }
        runIn(KEEPALIVE_SEC,"liveKeepalive"); return
    }
    // Was the PREVIOUS probe answered? ANY inbound frame (probe reply OR a real event) since we sent it proves life.
    if(state.probeAt && (state.lastRx?:0L) < (state.probeAt as long)){
        reconnectLive("keepalive unanswered ${(int)((now()-(state.probeAt as long))/1000)}s — session dead despite live flag"); return
    }
    // Alive (or first tick): send one tiny keepalive read, then re-check next interval.
    String pid = primaryReadId()
    if(pid){ state.probeAt = now(); sendEncrypted("GET /characteristics?id=${pid} HTTP/1.1\r\nHost: ${settings.ip}\r\n\r\n"); dlog("KA probe ${pid}") }
    runIn(iv,"liveKeepalive")
}
// central live teardown + reconnect (used by the probe watchdog and any evidence of a dead session)
void reconnectLive(String why){
    logInfo "HAP: ${why} — reconnecting"
    state.live=false; state.probeAt=null; unschedule("liveKeepalive")
    try{ interfaces.rawSocket.close() }catch(e){}; state.connInFlight=null
    runIn(4,"liveConnect")
}
// smallest possible keepalive/liveness target: the first mapped characteristic (one aid.iid)
String primaryReadId(){ String r=readIds(); if(!r) return null; return r.split(',')[0] }

// ===== device health (online/offline): a stable, alertable signal derived from real data flow =====
// The 0.10.4 liveness probe keeps state.lastRx fresh every safetySecs on a healthy held session, so a STALE
// lastRx is a reliable "accessory unreachable" signal that won't false-trip on a merely-idle accessory (the
// probe answers). Hysteresis: offline only after several missed intervals so a routine reconnect blip doesn't
// flap it. With self-heal, a transient wedge recovers silently; a genuine power/network outage reads offline.
@Field static int HEALTH_CHECK_SEC = 60
Integer offlineAfterSecs(){
    if(onDemand()) return Math.max(180, pollSecs()*2 + 60)          // on-demand: allow >2 poll cycles first
    int iv = safetySecs()
    if(iv<=0) return SILENCE_RECONNECT_SEC + 120                    // probing disabled: only after the long-silence reconnect
    return Math.max(180, iv*4)                                      // held session w/ probe: ~4 intervals, floor 3 min
}
void armHealth(){ unschedule("healthCheck"); runIn(90,"healthCheck") }   // 90s grace so the first connect can land before we judge
def healthCheck(){
    if(!isPaired()){ setHealth("offline"); return }                // unpaired -> offline; stops until re-armed by startSession
    runIn(HEALTH_CHECK_SEC, "healthCheck")                          // re-arm FIRST so a hiccup can't break the chain
    long stale = now() - (state.lastRx ?: 0L)
    setHealth(stale <= offlineAfterSecs()*1000L ? "online" : "offline")
}
void setHealth(String s){
    boolean changed = (state.health != s)
    // Emit on a real change, OR when the attribute is stale/undeclared-until-now (so a driver that only just
    // declared healthStatus populates it instead of reading blank until the next actual online/offline flip).
    if(!changed && device.currentValue("healthStatus") == s) return
    state.health = s; sendEvent(name:"healthStatus", value:s)
    if(changed){
        if(s=="offline") log.warn "HAP: accessory OFFLINE — no data in >${offlineAfterSecs()}s (self-heal still retrying)"
        else logInfo "HAP: accessory online"
    }
}
void processLiveStream(){
    String s = new String(hex(plainbuf().toString()), "ISO-8859-1"); int consumed=0
    while(true){
        int he=s.indexOf("\r\n\r\n", consumed); if(he<0){ if(s.length()>consumed) dlog("PLS partial-header left=${s.length()-consumed}b"); break }
        String head=s.substring(consumed, he); int bodyStart=he+4; int msgEnd; String body=""
        if(head.toLowerCase().contains("chunked")){
            int term=s.indexOf("0\r\n\r\n", bodyStart); if(term<0){ dlog("PLS chunked-incomplete left=${s.length()-bodyStart}b"); break }
            String rest=s.substring(bodyStart, term); StringBuilder sb=new StringBuilder()
            while(rest.length()>0){ int nl=rest.indexOf("\r\n"); if(nl<0) break; int n=Integer.parseInt(rest.substring(0,nl).trim(),16); if(n==0) break; sb.append(rest.substring(nl+2,nl+2+n)); rest=rest.substring(nl+2+n+2) }
            body=sb.toString(); msgEnd=term+5
        } else {
            int cl=0; def mm=(head =~ /(?i)content-length:\s*(\d+)/); if(mm.find()) cl=mm.group(1) as int
            if(s.length()<bodyStart+cl){ dlog("PLS body-incomplete have=${s.length()-bodyStart}/${cl}"); break }
            body=s.substring(bodyStart, bodyStart+cl); msgEnd=bodyStart+cl
        }
        handleLiveMessage(head, body); consumed=msgEnd
    }
    if(consumed>0){ byte[] left=s.substring(consumed).getBytes("ISO-8859-1"); plainbuf().setLength(0); plainbuf().append(hx(left)) }
}
void handleLiveMessage(String head, String body){
    String fl=head.split('\r\n')[0]
    rep("LIVE ${fl} (${body.length()}b)")
    if(!body?.trim()){ dlog("HDL ${fl} body=0 (empty)"); return }
    def j; try{ j=new groovy.json.JsonSlurper().parseText(body) }catch(e){ dlog("HDL ${fl} body=${body.length()} PARSE-FAIL: ${e.message}"); return }
    if(j?.accessories){ dlog("HDL ${fl} -> ACCESSORIES"); onAccessories(j); return }
    if(j?.characteristics){ dlog("HDL ${fl} -> CHARS(${j.characteristics.size()})"); onCharacteristics(j) }
    else dlog("HDL ${fl} body=${body.length()} -> other-json")
}
