/*
 * Ecobee HAP Thermostat (Local)
 *
 * Description:
 *   Controls an ecobee thermostat 100% locally over the HomeKit Accessory
 *   Protocol (HAP) — no cloud account, no Apple hardware, no extra bridge or
 *   hub. The driver pairs directly with the thermostat using its 8-digit
 *   HomeKit setup code, then holds a persistent encrypted LAN session for
 *   mode, setpoints, temperature, humidity, operating state, fan, and resume.
 *   Remote room sensors are created automatically as child devices, and HAP
 *   event push keeps everything updated in real time. Pairing uses one of the
 *   thermostat's HomeKit slots; resetting HomeKit on the device frees a slot.
 *
 * Author: RamSet
 * Version: 0.12.3
 * Date: 2026-06-24
 *
 * Changelog:
 *  v0.12.3 - Added a Dump Accessories command (debug): fetches the thermostat's full HAP accessory map
 *           and logs a compact per-characteristic summary (aid / service type / iid / type / perms / value).
 *           Lets us diagnose unknown models (which sensors/services they expose) without guesswork.
 *
 *  v0.12.2 - Clearer logging when a thermostat has no sensors: explicitly states that no sensor child
 *           is created and that this is normal (e.g. ecobee3 lite, which has no built-in occupancy sensor),
 *           instead of the terse "discovered 0 remote sensor(s)". No behavior change.
 *
 *  v0.12.1 - Multi-instance support. The in-memory socket buffers were @Field static (shared across ALL
 *           instances), so two paired thermostats would corrupt each other's sessions — now keyed per
 *           device. Child sensor DNIs are namespaced with the parent device id (the thermostat's own
 *           sensor is always aid 1, so two thermostats both wanted DNI hap-1) and existing children are
 *           adopted so current single-thermostat installs aren't disrupted. You can now run 2+ ecobees.
 *
 *  v0.12.0 - Thermostat's own motion + occupancy are now exposed as their OWN child sensor device
 *           ("<thermostat> Sensor"), instead of capabilities on the thermostat. This keeps the parent a
 *           pure Thermostat (so it still exports to Apple HomeKit) while motion AND presence export
 *           separately via the child — works whether or not you have remote sensors. No more either/or.
 *
 *  v0.11.12 - Reverted the thermostat's own motion/occupancy to DISABLED by default. Enabling them makes
 *           Hubitat's HomeKit Integration unable to classify the device, so the thermostat stops exporting
 *           to Apple HomeKit entirely. The room-sensor children already provide motion/presence, so this is
 *           a no-loss default. Can still be enabled in code if you don't re-export the thermostat to HomeKit.
 *
 *  v0.11.11 - Removed setFanMinOnTime command + fanMinOnTime attribute: empirically confirmed (on real
 *           hardware) that iid52 is NOT the fan minimum runtime — changing it on the thermostat (to 5
 *           and 20 min/hr) never moved iid52. Fan min-runtime isn't exposed over HAP, and the command
 *           was writing an unidentified characteristic, so it's gone. (iid52 remains raw in customParams.)
 *
 *  v0.11.10 - Keepalive also re-reads the remaining undecoded custom characteristics (iid49/50/51/53)
 *           so their raw values stay live in customParams (groundwork for decoding/exposing them).
 *
 *  v0.11.9 - Expose more of the HomeKit surface: fanState (actual fan running — inactive/idle/blowing),
 *           thermostatAlert (ecobee alerts/reminders text), and the six per-profile setpoints
 *           (home/away/sleep heat & cool). All read-only; refreshed via subscription + keepalive.
 *
 *  v0.11.8 - Doc fix: corrected the note on disabling the thermostat's motion/occupancy — comment the
 *           capability lines out and SAVE (not Import, which overwrites edits), and note that the change
 *           is a manual code edit not preserved across a re-import or HPM update (must be redone).
 *
 *  v0.11.7 - The thermostat's own motion + occupancy (presence) are now ENABLED by default and
 *           reported live (subscribed + keepalive). To turn them off, comment out the two capability
 *           lines and click Save (not Import); the emits are capability-guarded, so commenting out fully
 *           disables them. (See the capability-line note — the edit is not preserved across updates.)
 *
 *  v0.11.6 - Consistency: EVERY write command now updates its attribute immediately (optimistic) —
 *           setpoints, comfort profile, humidifier, fan min-on-time (mode & fan already did) — so the
 *           UI never lags a command, even for characteristics the ecobee doesn't push events for.
 *           Remote-sensor battery added to the live subscription. (Driver relocated in the repo to
 *           drivers/ecobee-hap-thermostat/.)
 *
 *  v0.11.5 - thermostatMode now follows commands immediately (optimistic update, like fan mode in
 *           0.11.3) — fixes the mode showing stale (e.g. "off") after an app/rule sets it. Also the
 *           5-min keepalive now re-reads the FULL thermostat state, so any event missed during a
 *           session drop (mode, setpoints, operating state, fan) self-heals within ~5 min.
 *
 *  v0.11.4 - comfortProfile/holdEndsAt now self-refresh: the 5-min keepalive also reads the
 *           no-notify characteristics (comfort profile, hold-end, fan min-on-time), so they
 *           update on their own instead of only after a manual Refresh. (These chars can't be
 *           event-subscribed — they're read-only with no notify — so a periodic read is the fix.)
 *
 *  v0.11.3 - Fan mode now updates live: subscribe to TargetFanState (iid75) events so an external
 *           On/Auto change (e.g. from webCoRE or the thermostat) reflects without a manual Refresh;
 *           driver-issued fan commands also update the attribute immediately.
 *
 *  v0.11.2 - Fix setpoint reporting in heat/cool: the cooling/heating setpoint now reflects the
 *           actual target (HAP TargetTemperature) instead of the Auto-mode threshold, which made
 *           the displayed cool/heat point disagree with what the thermostat was really doing.
 *           (Thresholds are still used in Auto mode.) Matches the existing mode-aware writes.
 *
 *  v0.11.1 - Fix reversed fan mode: "On" was setting Auto and vice-versa (HAP TargetFanState
 *           is 0=Manual/On, 1=Auto — the driver had it inverted on both write and read).
 *
 *  v0.11.0 - Reliability: keepalive watchdog reconnects a stalled/zombie live session;
 *           connect retries on "connection refused" (the ecobee drops its HAP listener
 *           briefly after a failed pair attempt); mDNS port discovery retries before
 *           falling back to the last-known port (the port can change after a reboot).
 *           Adds comfortProfile + holdEndsAt attributes and a debug 'diag' flow trace.
 *
 *  v0.10.0 - Comfort profiles over local HAP: Set Comfort Profile (Home/Away/Sleep) and a
 *           comfortProfile attribute that reports the active one. Added humidifier target
 *           (Set Humidity Setpoint) and fan min-on-time controls, plus a generic Set
 *           Characteristic command for direct writes. None of this needs the cloud.
 *
 *  v0.9.0 - HomeKit event push is now the default: a paired thermostat auto-opens a
 *           persistent encrypted session, subscribes for instant updates, and
 *           self-recovers (5-minute keepalive + auto-reconnect). Reads and writes
 *           route over the live session. In-driver pairing (enter IP + setup code,
 *           Save to pair) and automatic port discovery, so nothing is configured by
 *           hand. Remote room sensors are exposed as child devices.
 *
 *  v0.3.0 - Initial release: fully-local control of the ecobee — pair-verify,
 *           ChaCha20-Poly1305 encrypted session, and thermostat read/write, all on
 *           the hub with no cloud and no additional hardware.
 *
 * HPM Metadata:
 * {
 *   "package": "Ecobee HAP Thermostat (Local)",
 *   "namespace": "RamSet",
 *   "author": "RamSet",
 *   "location": "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/ecobee-hap-thermostat/ecobee-hap-thermostat.groovy",
 *   "description": "Local HAP controller for an ecobee thermostat: mode, setpoints, temperature, humidity, operating state, fan, and remote sensors.",
 *   "required": true,
 *   "version": "0.12.3"
 * }
 *
 * Copyright 2026 RamSet
 * Licensed under the Apache License, Version 2.0. Provided as-is, without warranty
 * of any kind; you assume all risk of controlling real HVAC hardware with it.
 */

import groovy.transform.Field

metadata {
    definition(name: "Ecobee HAP Thermostat", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/ecobee-hap-thermostat/ecobee-hap-thermostat.groovy") {
        capability "Thermostat"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        // NOTE: the thermostat's own motion/occupancy are intentionally NOT capabilities on this device.
        // A Thermostat that also has MotionSensor/PresenceSensor can't be classified by Hubitat's HomeKit
        // Integration and silently drops out of the HomeKit export. Instead, the thermostat's built-in
        // sensor is exposed as its own child device (a motion/occupancy sensor) — see buildSensors().
        capability "Refresh"
        command "setDesiredTemperature", [[name:"Desired temperature*",type:"NUMBER",description:"Target temperature to set on the thermostat"]]
        command "raiseSetpoint"
        command "lowerSetpoint"
        command "resumeProgram"
        command "setComfortProfile", [[name:"profile*",type:"ENUM",constraints:["Home","Away","Sleep"]]]
        command "setHumiditySetpoint", [[name:"humidity %*",type:"NUMBER",description:"target humidity, 20-50"]]
        command "setCharacteristic", [[name:"aid.iid*",type:"STRING",description:"HAP characteristic, e.g. 1.40"],[name:"value*",type:"STRING",description:"value to write (number or string)"]]
        command "dumpAccessories"   // debug: logs this thermostat's full HAP accessory/service/characteristic map
        attribute "comfortProfile", "string"
        attribute "holdEndsAt", "string"
        attribute "humiditySetpoint", "number"
        attribute "fanState", "string"          // actual fan running state: inactive / idle / blowing (HAP iid76)
        attribute "thermostatAlert", "string"   // ecobee alerts/reminders text (HAP iid54)
        attribute "homeHeatSetpoint", "number"  // per-comfort-profile targets (HAP iid34-39, Home/Away/Sleep)
        attribute "homeCoolSetpoint", "number"
        attribute "awayHeatSetpoint", "number"
        attribute "awayCoolSetpoint", "number"
        attribute "sleepHeatSetpoint", "number"
        attribute "sleepCoolSetpoint", "number"
        attribute "customParams", "string"
        attribute "hapStatus", "string"
        attribute "diag", "string"
    }
    preferences {
        input "ip", "string", title: "Thermostat IP address", required: true
        if (!state.paired) {
            input "setupCode", "string", title: "HomeKit setup code — 8 digits, no dashes (e.g. 12345678). Enter and Save to pair.", required: false
        }
        input "debugLog", "bool", title: "Enable debug logging", defaultValue: false
    }
}

// ===== topology =====
@Field static int TAID = 1
// thermostat readable characteristic iids -> label
@Field static Map TCHARS = [
    17:"opStateRaw", 18:"modeRaw", 19:"temperatureC", 20:"setpointC", 21:"unitsRaw",
    22:"coolingSetpointC", 23:"heatingSetpointC", 24:"humidity", 25:"targetHumidity",
    66:"thermMotion", 65:"thermOccupancy",
    33:"c_iid33", 34:"c_iid34", 35:"c_iid35", 36:"c_iid36", 37:"c_iid37", 38:"c_iid38",
    39:"c_iid39", 41:"c_iid41", 49:"c_iid49", 50:"c_iid50", 51:"c_iid51", 52:"c_iid52",
    53:"c_iid53", 54:"c_iid54", 75:"c_iid75", 76:"c_iid76"
]
// sensors are discovered dynamically from /accessories into state.sensors
// in-memory receive/plaintext buffers (the /accessories response is large; keep it off state).
// Keyed by device.id so MULTIPLE thermostat instances each get their own buffers (@Field static is
// shared across all instances, so a bare StringBuilder would corrupt across two paired thermostats).
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

def installed(){ updated() }
def updated(){
    unschedule(); state.live=false; state.diag=[]; state.connTry=0; state.mdnsTries=0; if(settings.debugLog) sendEvent(name:"diag", value:"")
    state.remove("sensors")   // force a fresh /accessories discovery on Save so sensor topology (incl. the thermostat's own sensor) rebuilds
    if(settings.setupCode && !isPaired()){ log.info "HAP: setup code entered — pairing"; runIn(1,"pair") }
    else if(isPaired()){ runIn(2,"startLive") }   // live event mode is the default once paired
}
boolean isPaired(){ return (state.paired==true || settings.iosLtsk) ? true : false }

// ===== helpers =====
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
byte[] rnd32(){ byte[] raw=new byte[32]
    try{ def kp=java.security.KeyPairGenerator.getInstance("X25519").generateKeyPair(); byte[] enc=kp.getPrivate().getEncoded(); for(int i=0;i<32;i++) raw[i]=enc[enc.length-32+i] }
    catch(Throwable e){ state.entc=(state.entc?:0)+1; byte[] hh=sha512((""+now()+":"+state.entc+":"+(settings.iosLtsk?:'x')).getBytes("UTF-8")); for(int i=0;i<32;i++) raw[i]=hh[i] }
    return raw }
Map genEph(){ byte[] raw=rnd32(); byte[] pub=x25519(raw, hex("0900000000000000000000000000000000000000000000000000000000000000")); return [priv:hx(raw),pub:hx(pub)] }
java.math.BigInteger beBig(byte[] b){ return new java.math.BigInteger(1,b) }
byte[] bigBe(java.math.BigInteger n, int len){ byte[] t=n.toByteArray(); byte[] r=new byte[len]; int src=(t.length>len)?t.length-len:0; int copy=t.length-src; for(int i=0;i<copy;i++) r[len-copy+i]=t[src+i]; return r }
String uuidStr(){ String h=hx(rnd32()); return "${h[0..7]}-${h[8..11]}-${h[12..15]}-${h[16..19]}-${h[20..31]}" }
void rep(String m){ if(settings.debugLog) log.debug "HAP: ${m}" }

// ===== public commands =====
// ---- flow diagnostics (read remotely via the 'diag' attribute / fullJson) ----
String nowHM(){ try{ return new Date().format("HH:mm:ss", location.timeZone) }catch(e){ return "--:--:--" } }
void dlog(String m){
    if(!settings.debugLog) return
    def b = (state.diag instanceof List) ? state.diag : []
    b << "${nowHM()} ${m}".toString()
    while(b.size()>28) b.remove(0)
    state.diag = b
    sendEvent(name:"diag", value: b.join("\n"))
}
// debug: fetch /accessories over the live session and log a compact structural map (for diagnosing unknown models)
def dumpAccessories(){
    if(state.live && state.sess){ state.dumpReq=true; log.info "HAP: requesting /accessories dump…"; sendEncrypted("GET /accessories HTTP/1.1\r\nHost: ${settings.ip}\r\n\r\n") }
    else { log.warn "HAP: not connected — open the session first (device must be paired and live)" }
}
void dumpAcc(j){
    def code={ x-> x?.toString()?.replace("-","")?.toUpperCase()?.replaceAll(/^0+/,"") }
    log.info "===== HAP /accessories dump (driver v0.12.3) ====="
    j.accessories.each{ acc->
        log.info "ACC aid=${acc.aid}"
        acc.services.each{ sv->
            def parts=sv.characteristics.collect{ c-> "iid${c.iid} t=${code(c.type)} [${(c.perms?:[]).join('/')}]=${(c.value!=null)? (c.value.toString().take(24)) : ''}" }
            log.info "  svc ${code(sv.type)}: " + parts.join("  ")
        }
    }
    log.info "===== end dump ====="
}
def refresh(){
    if(state.live && state.sess){
        String gids=readIds(); String req="GET /characteristics?id=${gids} HTTP/1.1\r\nHost: ${settings.ip}\r\n\r\n"
        dlog("TX get ids=${gids.split(',').size()} reqLen=${req.length()}")
        sendEncrypted(req)
    }
    else { dlog("refresh: not live/sess -> startLive"); startLive() }
}
// mDNS-detect the HAP port, then run the queued op (auto-corrects port changes; no manual port needed)
def mdnsThen(String op){
    if(!settings.ip){ log.warn "HAP: set IP first"; return }
    state.afterMdns = op
    String q="000000000001000000000000045f686170045f746370056c6f63616c00000c8001"
    sendHubCommand(new hubitat.device.HubAction(q, hubitat.device.Protocol.LAN,
        [destinationAddress:"${settings.ip}:5353",
         type:hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
         encoding:hubitat.device.HubAction.Encoding.HEX_STRING,
         timeout:5, callback:"mdnsCallback"]))
    runIn(6,"mdnsTimeout")
}
def mdnsTimeout(){
    def op=state.afterMdns; state.afterMdns=null; if(!op) return
    int tries=(state.mdnsTries?:0) as int
    if(tries < 2){   // retry the query — the port can change after a reboot/power-cycle, so getting the CURRENT one matters
        state.mdnsTries=tries+1
        log.warn "HAP: mDNS port detect timed out — retry ${state.mdnsTries}/2"
        mdnsThen(op); return
    }
    state.mdnsTries=0
    log.warn "HAP: mDNS port detect timed out; using last-known port"; dispatchOp(op)
}
def mdnsCallback(message){
    try {
        String desc = message.toString(); log.debug "HAP mdns raw: ${desc}"
        def m = null; try { m = parseLanMessage(desc) } catch(ig){}
        String h = ((m?.payload ?: m?.body ?: desc) ?: "").toString().toLowerCase().replaceAll("[^0-9a-f]","")
        def r = parseMdns(h)
        if(r.port){ device.updateSetting("port",[value:r.port,type:"number"]); state.discoveredPort=r.port; state.mdnsTries=0; log.info "HAP: detected port ${r.port}" }
        else log.warn "HAP: no SRV in mDNS reply"
        unschedule("mdnsTimeout")
        def op=state.afterMdns; state.afterMdns=null; if(op) dispatchOp(op)
    } catch(e){ log.error "mdnsCallback: ${e}" }
}
// minimal mDNS/DNS answer walker -> [ecobee, ip, port, sf]
Map parseMdns(String h){
    byte[] b; try { b=hex(h) } catch(e){ return [ecobee:false] }
    def res=[ecobee:false, ip:null, port:null, sf:-1]
    if(b==null || b.length<12) return res
    int qd=((b[4]&0xff)<<8)|(b[5]&0xff)
    int tot=(((b[6]&0xff)<<8)|(b[7]&0xff))+(((b[8]&0xff)<<8)|(b[9]&0xff))+(((b[10]&0xff)<<8)|(b[11]&0xff))
    int p=12
    for(int i=0;i<qd;i++){ p=skipName(b,p); p+=4 }
    for(int i=0;i<tot && p+10<=b.length;i++){
        p=skipName(b,p); if(p+10>b.length) break
        int type=((b[p]&0xff)<<8)|(b[p+1]&0xff)
        int rdlen=((b[p+8]&0xff)<<8)|(b[p+9]&0xff); int rd=p+10
        if(type==0x21 && rd+6<=b.length){ res.port=((b[rd+4]&0xff)<<8)|(b[rd+5]&0xff) }
        else if(type==0x01 && rdlen==4 && rd+4<=b.length){ res.ip="${b[rd]&0xff}.${b[rd+1]&0xff}.${b[rd+2]&0xff}.${b[rd+3]&0xff}" }
        else if(type==0x10){
            String t=""; int e=Math.min(rd+rdlen,b.length); for(int k=rd;k<e;k++) t+=(char)(b[k]&0xff); t=t.toLowerCase()
            if(t.contains("md=ecobee")) res.ecobee=true
            int si=t.indexOf("sf="); if(si>=0 && si+3<t.length()){ try{ res.sf=Integer.parseInt(t.substring(si+3,si+4)) }catch(ig){} }
        }
        p=rd+rdlen
    }
    return res
}
int skipName(byte[] b, int p){ while(p<b.length){ int l=b[p]&0xff; if(l==0) return p+1; if((l&0xC0)==0xC0) return p+2; p+=1+l }; return p }
void dispatchOp(String op){ if(op=="pairsetup") pairConnect() else if(op=="live") liveConnect() else if(op in ["read","discover","write"]) hapStart(op, op=="write"? state.writeJson : null) }
def pair(){
    if(!settings.setupCode){ log.error "Enter the HomeKit setup code first"; return }
    if(!settings.ip){ log.error "Set the thermostat IP first"; return }
    mdnsThen("pairsetup")
}
int hapPort(){ return (state.discoveredPort ?: settings.port ?: 0) as int }
void pairConnect(){
    if(hapPort()<=0){ log.error "HAP: no port (mDNS failed and none configured)"; return }
    state.op="pairsetup"; state.sess=false; state.psstage="2"; rxbuf().setLength(0); plainbuf().setLength(0)
    sendEvent(name:"hapStatus", value:"pairing")
    try { interfaces.rawSocket.connect([byteInterface:true], settings.ip, hapPort()) }
    catch(e){
        // the ecobee briefly drops its HAP listener right after a failed/aborted pair attempt — retry, same port
        if(e.toString().contains("refused") && (state.connTry?:0) < 4){
            state.connTry=((state.connTry?:0) as int)+1
            log.warn "HAP: connect refused (ecobee resetting after a failed attempt?) — retry ${state.connTry}/4 in 12s"
            sendEvent(name:"hapStatus", value:"connect refused — retry ${state.connTry}/4")
            runIn(12,"pairConnect"); return
        }
        log.error "connect: $e"; state.connTry=0; return
    }
    state.connTry=0
    sendHttpTlv("/pair-setup", tlv([[6,[1] as byte[]],[0,[0] as byte[]]]))   // State=M1, Method=PairSetup
}
void routePS(Map tv){ if(state.psstage=="2") psM2(tv) else if(state.psstage=="4") psM4(tv) else psM6(tv) }
void psM2(Map tv){
    if(tv[7]!=null){ sendEvent(name:"hapStatus",value:"pair err M2 ${hx(tv[7])}"); log.error "pair M2 ${hx(tv[7])}"; interfaces.rawSocket.close(); return }
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
    if(tv[7]!=null){ sendEvent(name:"hapStatus",value:"pair fail: bad code (${hx(tv[7])})"); log.error "pair M4 ${hx(tv[7])} (wrong/rotated code?)"; interfaces.rawSocket.close(); return }
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
    if(tv[7]!=null){ sendEvent(name:"hapStatus",value:"pair fail M6 ${hx(tv[7])}"); interfaces.rawSocket.close(); return }
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
    sendEvent(name:"hapStatus", value:"paired"); log.info "HAP: paired OK, keys stored"
    interfaces.rawSocket.close(); runIn(3,"startLive")
}
def setThermostatMode(String m){ String lm=m?.toLowerCase(); def v=[off:0,heat:1,cool:2,auto:3][lm]; if(v!=null){ writeChar(TAID,18,v); sendEvent(name:"thermostatMode", value:lm) } else log.warn "bad mode $m" }
def off(){ setThermostatMode("off") }
def heat(){ setThermostatMode("heat") }
def cool(){ setThermostatMode("cool") }
def auto(){ setThermostatMode("auto") }
def emergencyHeat(){ setThermostatMode("heat") }
// HAP: in heat/cool the active setpoint is TargetTemperature (iid20); thresholds (22/23) apply only in auto
def setHeatingSetpoint(t){ String m=device.currentValue("thermostatMode"); writeChar(TAID, (m=="auto")?23:20, round1(hubToC(t as BigDecimal))); sendEvent(name:"heatingSetpoint", value:t); if(m!="auto") sendEvent(name:"thermostatSetpoint", value:t) }
def setCoolingSetpoint(t){ String m=device.currentValue("thermostatMode"); writeChar(TAID, (m=="auto")?22:20, round1(hubToC(t as BigDecimal))); sendEvent(name:"coolingSetpoint", value:t); if(m!="auto") sendEvent(name:"thermostatSetpoint", value:t) }
def setThermostatSetpoint(t){ String m=device.currentValue("thermostatMode"); writeChar(TAID,20, round1(hubToC(t as BigDecimal))); sendEvent(name:"thermostatSetpoint", value:t); if(m=="cool") sendEvent(name:"coolingSetpoint", value:t); else if(m=="heat") sendEvent(name:"heatingSetpoint", value:t) }
def setDesiredTemperature(t){
    String m=device.currentValue("thermostatMode"); BigDecimal c=round1(hubToC(t as BigDecimal))
    if(m=="auto"){ writeChars([[TAID,22,c],[TAID,23,c]]); sendEvent(name:"coolingSetpoint", value:t); sendEvent(name:"heatingSetpoint", value:t) }
    else { writeChar(TAID,20,c); sendEvent(name:"thermostatSetpoint", value:t); if(m=="cool") sendEvent(name:"coolingSetpoint", value:t); else if(m=="heat") sendEvent(name:"heatingSetpoint", value:t) }
}
def raiseSetpoint(){ adjustSetpoint(1) }
def lowerSetpoint(){ adjustSetpoint(-1) }
void adjustSetpoint(BigDecimal d){
    String mode = device.currentValue("thermostatMode")
    if(mode=="cool" || mode=="heat"){ def sp=device.currentValue("thermostatSetpoint"); if(sp!=null){ BigDecimal nv=(sp as BigDecimal)+d; writeChar(TAID,20, round1(hubToC(nv))); sendEvent(name:"thermostatSetpoint", value:nv); sendEvent(name:(mode=="cool"?"coolingSetpoint":"heatingSetpoint"), value:nv) } }
    else if(mode=="auto"){
        def c=device.currentValue("coolingSetpoint"); def h=device.currentValue("heatingSetpoint")
        if(c!=null && h!=null){ BigDecimal nc=(c as BigDecimal)+d, nh=(h as BigDecimal)+d; writeChars([[TAID,22,round1(hubToC(nc))],[TAID,23,round1(hubToC(nh))]]); sendEvent(name:"coolingSetpoint", value:nc); sendEvent(name:"heatingSetpoint", value:nh) }
    } else { log.info "HAP: mode is off — nothing to adjust" }
}
def resumeProgram(){ writeChar(TAID,48, true) }
// ecobee comfort profiles over HAP iid40 (write) — confirmed mapping: Home=0, Sleep=1, Away=2 (3=manual hold, read-only)
def setComfortProfile(String p){ def v=[Home:0,Sleep:1,Away:2][p]; if(v!=null){ writeChar(TAID,40, v as int); sendEvent(name:"comfortProfile", value:p) } else log.warn "HAP: unknown comfort profile $p" }
def setHumiditySetpoint(h){ writeChar(TAID,25, (h as BigDecimal)); sendEvent(name:"humiditySetpoint", value:(h as int), unit:"%") }
def setCharacteristic(String aidIid, String value){ def p=aidIid.split("\\."); def v = value.isNumber()? (value.contains(".")? (value as BigDecimal):(value as Integer)) : value; writeChar(p[0] as long, p[1] as int, v) }
// HAP iid75 = TargetFanState: 0=Manual(fan ON/continuous), 1=Auto
def setThermostatFanMode(String m){ boolean on=(m?.toLowerCase()=="on"); writeChar(TAID,75, on?0:1); sendEvent(name:"thermostatFanMode", value: on?"on":"auto") }
def fanOn(){ setThermostatFanMode("on") }
def fanAuto(){ setThermostatFanMode("auto") }
def fanCirculate(){ setThermostatFanMode("on") }
def setSchedule(s){}

BigDecimal round1(BigDecimal v){ return (v*10).setScale(0, java.math.RoundingMode.HALF_UP)/10 }
boolean isF(){ return (location?.temperatureScale ?: "F") == "F" }
def hubToC(BigDecimal t){ isF()? ((t-32)*5/9) : t }
def cToHub(v){ if(v==null) return null; def c=(v as BigDecimal); return isF()? round1(c*9/5+32) : round1(c) }

void writeChars(List entries){
    def parts = entries.collect{ e-> def jv=(e[2] instanceof Boolean)? e[2] : ((e[2] instanceof Number)? e[2] : "\"${e[2]}\""); "{\"aid\":${e[0]},\"iid\":${e[1]},\"value\":${jv}}" }
    String b = "{\"characteristics\":[${parts.join(',')}]}"; state.writeJson = b
    if(state.live && state.sess){ sendEncrypted("PUT /characteristics HTTP/1.1\r\nHost: ${settings.ip}\r\nContent-Type: application/hap+json\r\nContent-Length: ${b.getBytes('UTF-8').length}\r\nConnection: keep-alive\r\n\r\n"+b); runIn(2,"liveKeepalive") }
    else { hapStart("write", b) }
}
void writeChar(long aid, int iid, val){ writeChars([[aid,iid,val]]) }

// ===== HAP session flow =====
String readIds(){
    def ids=[]; TCHARS.keySet().each{ ids << "${TAID}.${it}" }
    (state.sensors ?: []).each{ s-> [s.temp,s.occ,s.motion,s.batt,s.lowbatt,s.serial,s.name].each{ if(it!=null) ids << "${s.aid}.${it}" } }
    return ids.join(",")
}
void buildSensors(j){
    def code={ x-> x.replace("-","").toUpperCase().replaceAll(/^0+/,"") }
    def sensors=[]
    j.accessories.each{ acc->
        if(acc.aid==TAID){
            // the thermostat's OWN built-in motion/occupancy -> its own child sensor device, so the parent
            // stays a pure Thermostat (a Thermostat + MotionSensor/PresenceSensor can't be exported to HomeKit)
            def ts=[aid:TAID, isMain:true, temp:19]   // temp 19 = the thermostat's reading, gives the child a valid temp
            acc.services.each{ sv-> def sc=code(sv.type)
                sv.characteristics.each{ c-> def cc=code(c.type)
                    if(sc=="85" && cc=="22") ts.motion=c.iid
                    else if(sc=="86" && cc=="71") ts.occ=c.iid
                }
            }
            if(ts.motion || ts.occ) sensors << ts
            return
        }
        if(!acc.services.any{ code(it.type)=="8A" }) return   // remote sensor = has TemperatureSensor service
        def s=[aid:acc.aid]
        acc.services.each{ sv-> def sc=code(sv.type)
            sv.characteristics.each{ c-> def cc=code(c.type)
                if(sc=="8A" && cc=="11") s.temp=c.iid
                else if(sc=="86" && cc=="71") s.occ=c.iid
                else if(sc=="85" && cc=="22") s.motion=c.iid
                else if(sc=="96" && cc=="68") s.batt=c.iid
                else if(sc=="96" && cc=="79") s.lowbatt=c.iid
                else if(sc=="3E" && cc=="30") s.serial=c.iid
                else if(sc=="3E" && cc=="23") s.name=c.iid
            }
        }
        sensors << s
    }
    state.sensors=sensors
    if(sensors.isEmpty())
        log.info "HAP: this thermostat has no built-in occupancy/motion sensor and no remote sensors — no sensor child device is created (this is normal, e.g. ecobee3 lite)"
    else
        log.info "HAP: discovered ${sensors.findAll{!it.isMain}.size()} remote sensor(s)${sensors.any{it.isMain}?' + thermostat sensor':''}"
}
def hapStart(String op, String body){
    if(!settings.ip || hapPort()<=0){ log.warn "HAP: set IP first (port auto-detects)"; return }
    state.op=op; state.inCtr=0; state.outCtr=0; rxbuf().setLength(0); plainbuf().setLength(0)
    state.sess=false; state.vstage="m2"
    def ek=genEph(); state.ephPriv=ek.priv; state.ephPub=ek.pub
    sendEvent(name:"hapStatus", value:"connecting")
    try { interfaces.rawSocket.connect([byteInterface:true], settings.ip, hapPort()) }
    catch(e){ log.error "connect: $e"; rep("ERR connect $e"); return }
    sendHttpTlv("/pair-verify", tlv([[6,[1] as byte[]],[3,hex(state.ephPub)]]))
}
void sendHttpTlv(String path, byte[] b){ String h="POST ${path} HTTP/1.1\r\nHost: ${settings.ip}\r\nContent-Type: application/pairing+tlv8\r\nContent-Length: ${b.length}\r\nConnection: keep-alive\r\n\r\n"; interfaces.rawSocket.sendMessage(hx(cat(h.getBytes("UTF-8"),b))) }
void sendEncrypted(String req){ byte[] plain=req.getBytes("UTF-8"); def o=new java.io.ByteArrayOutputStream(); long ctr=state.outCtr
    for(int i=0;i<plain.length;i+=1024){ int n=Math.min(1024,plain.length-i); byte[] ch=new byte[n]; for(int j=0;j<n;j++) ch[j]=plain[i+j]
        byte[] aad=le16(n); byte[] ct=chachaEnc(hex(state.c2a),nctr(ctr),ch,aad); ctr++; o.write(aad,0,2); o.write(ct,0,ct.length) }
    state.outCtr=ctr; interfaces.rawSocket.sendMessage(hx(o.toByteArray())) }
def socketStatus(String s){
    String l = s?.toLowerCase() ?: ""
    if(state.live && (l.contains("close") || l.contains("error"))){ state.live=false; sendEvent(name:"hapStatus", value:"reconnecting"); log.warn "HAP: live socket dropped (${s}); reconnecting"; runIn(8,"startLive") }
    else if(!l.contains("close")) log.warn "socket: $s"
}

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
  } catch(Throwable e){ log.error "parse: ${e}"; rep("ERR parse ${state.op}/${state.vstage}: ${e.class.simpleName}: ${e.message}") }
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
    if(tv[7]!=null){ rep("ERR verify ${hx(tv[7])}"); interfaces.rawSocket.close(); return }
    byte[] shared=hex(state.shared)
    state.c2a=hx(hkdf("Control-Salt".getBytes("UTF-8"),shared,"Control-Write-Encryption-Key".getBytes("UTF-8"),32))
    state.a2c=hx(hkdf("Control-Salt".getBytes("UTF-8"),shared,"Control-Read-Encryption-Key".getBytes("UTF-8"),32))
    state.sess=true; rxbuf().setLength(0); plainbuf().setLength(0); state.inCtr=0
    sendEvent(name:"hapStatus", value:"session")
    if(state.op=="live"){
        state.live=true; sendEvent(name:"hapStatus", value:"live"); log.info "HAP: live session up — subscribing to events"
        dlog("session up (live) -> subscribe + get")
        sendEncrypted(subscribeBody())
        String gids=readIds(); dlog("TX get(connect) ids=${gids.split(',').size()} reqLen=${("GET /characteristics?id=${gids} HTTP/1.1\r\nHost: ${settings.ip}\r\n\r\n").length()}")
        sendEncrypted("GET /characteristics?id=${gids} HTTP/1.1\r\nHost: ${settings.ip}\r\n\r\n")
    }
    else if(state.op=="read"){ sendEncrypted("GET /characteristics?id=${readIds()} HTTP/1.1\r\nHost: ${settings.ip}\r\n\r\n") }
    else if(state.op=="discover"){ sendEncrypted("GET /accessories HTTP/1.1\r\nHost: ${settings.ip}\r\n\r\n") }
    else { String b=state.writeJson; sendEncrypted("PUT /characteristics HTTP/1.1\r\nHost: ${settings.ip}\r\nContent-Type: application/hap+json\r\nContent-Length: ${b.getBytes('UTF-8').length}\r\nConnection: keep-alive\r\n\r\n"+b) }
}
void handleSession(){
    String buf=rxbuf().toString()
    while(buf.length()>=4){
        byte[] lh=hex(buf.substring(0,4)); int ln=(lh[0]&0xff)|((lh[1]&0xff)<<8); int need=4+(ln+16)*2
        if(buf.length()<need) break
        byte[] aad=hex(buf.substring(0,4)); byte[] blk=hex(buf.substring(4,need)); rxbuf().delete(0,need); buf=rxbuf().toString()
        byte[] pt=chachaDec(hex(state.a2c),nctr(state.inCtr),blk,aad); state.inCtr=(state.inCtr as long)+1; plainbuf().append(hx(pt))
        dlog("RXframe ln=${ln} inCtr=${state.inCtr} plain=${(int)(plainbuf().length()/2)}b rxLeft=${(int)(rxbuf().length()/2)}b")
        if(state.op!="live" && ln<1024){ finish(); return }
    }
    if(state.op=="live") processLiveStream()
}
void finish(){
    byte[] resp=hex(plainbuf().toString()); String s=new String(resp,"UTF-8"); state.sess=false; state.vstage=null
    plainbuf().setLength(0); rxbuf().setLength(0)
    int bi=s.indexOf("\r\n\r\n"); String head=bi>=0? s.substring(0,bi):s; String body=bi>=0? s.substring(bi+4):""
    interfaces.rawSocket.close()
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
    def j; try{ j=new groovy.json.JsonSlurper().parseText(body) }catch(e){ rep("ERR json ${e}; head=${head.split('\r\n')[0]}"); return }
    if(state.op=="discover"){ buildSensors(j); runIn(1,"startLive"); return }
    applyState(j)
}
// ===== live event mode (persistent session + subscriptions) =====
// live event mode is the default: discover sensors if needed, then open the persistent subscribed session
def startLive(){ if(!isPaired()){ log.warn "HAP: not paired"; return }; mdnsThen(state.sensors==null ? "discover" : "live") }
void liveConnect(){
    if(hapPort()<=0){ log.warn "HAP: no port"; return }
    state.op="live"; state.inCtr=0; state.outCtr=0; rxbuf().setLength(0); plainbuf().setLength(0); state.sess=false; state.vstage="m2"; state.live=false
    def ek=genEph(); state.ephPriv=ek.priv; state.ephPub=ek.pub
    sendEvent(name:"hapStatus", value:"connecting (live)")
    try { interfaces.rawSocket.connect([byteInterface:true], settings.ip, hapPort()) }
    catch(e){ log.error "live connect: $e"; runIn(15,"startLive"); return }
    sendHttpTlv("/pair-verify", tlv([[6,[1] as byte[]],[3,hex(state.ephPub)]]))
    unschedule("liveKeepalive"); runEvery5Minutes("liveKeepalive")
}
def liveKeepalive(){
    if(state.live && state.sess){
        state.kaCtr = state.inCtr
        // re-reads the full thermostat state every keepalive so ANY event missed during a session drop
        // (mode, setpoints, operating state, fan, comfort/hold) self-heals within ~5 min; doubles as the watchdog probe
        String ids=[17,18,19,20,22,23,24,25,33,34,35,36,37,38,39,41,49,50,51,52,53,54,65,66,75,76].collect{ "${TAID}.${it}" }.join(",")
        sendEncrypted("GET /characteristics?id=${ids} HTTP/1.1\r\nHost: ${settings.ip}\r\n\r\n")
        runIn(12,"kaWatch")
    } else { startLive() }
}
// watchdog: if the keepalive GET drew no frame back, the session is a zombie (socket half-open) — force reconnect
def kaWatch(){
    if(state.live && state.sess && (state.inCtr as long)==(state.kaCtr as long)){
        log.warn "HAP: keepalive got no response — session stale, reconnecting"
        dlog("keepalive no-resp inCtr=${state.inCtr} -> reconnect")
        state.live=false; try{ interfaces.rawSocket.close() }catch(e){}; runIn(2,"startLive")
    }
}
String subscribeBody(){
    def ev=[]; [17,18,19,20,22,23,24,25,65,66,75,76].each{ ev << "{\"aid\":${TAID},\"iid\":${it},\"ev\":true}" }
    (state.sensors ?: []).each{ s-> [s.temp,s.occ,s.motion,s.batt,s.lowbatt].each{ if(it!=null) ev << "{\"aid\":${s.aid},\"iid\":${it},\"ev\":true}" } }
    String b="{\"characteristics\":[${ev.join(',')}]}"
    return "PUT /characteristics HTTP/1.1\r\nHost: ${settings.ip}\r\nContent-Type: application/hap+json\r\nContent-Length: ${b.getBytes('UTF-8').length}\r\nConnection: keep-alive\r\n\r\n"+b
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
    if(j?.accessories){ dlog("HDL ${fl} body=${body.length()} -> ACCESSORIES"); if(state.dumpReq){ state.dumpReq=false; dumpAcc(j) }; buildSensors(j); return }
    if(j?.characteristics){ dlog("HDL ${fl} body=${body.length()} -> CHARS(${j.characteristics.size()})"); applyState(j) }
    else dlog("HDL ${fl} body=${body.length()} -> other-json")
}
void applyState(j){
    def vmap=[:]   // "aid.iid" -> value
    j.characteristics.each{ vmap["${it.aid}.${it.iid}"]= it.value }
    // ---- thermostat ----
    def g={ iid-> vmap["${TAID}.${iid}"] }
    if(g(19)!=null) sendEvent(name:"temperature", value: cToHub(g(19)), unit:"°${isF()?'F':'C'}")
    if(g(24)!=null) sendEvent(name:"humidity", value: g(24) as int, unit:"%")
    if(g(18)!=null) sendEvent(name:"thermostatMode", value: [0:"off",1:"heat",2:"cool",3:"auto"][g(18) as int])
    // Setpoint reporting is mode-aware (matches the writes): in heat/cool the real target is iid20
    // (TargetTemperature); iid22/iid23 (thresholds) only apply in Auto. Reporting iid22/23 in cool/heat
    // shows a stale Auto threshold instead of the actual target.
    String tmode = (g(18)!=null) ? [0:"off",1:"heat",2:"cool",3:"auto"][g(18) as int] : device.currentValue("thermostatMode")
    if(g(20)!=null) sendEvent(name:"thermostatSetpoint", value: cToHub(g(20)))
    if(tmode=="cool"){
        if(g(20)!=null) sendEvent(name:"coolingSetpoint", value: cToHub(g(20)))
        if(g(23)!=null) sendEvent(name:"heatingSetpoint", value: cToHub(g(23)))
    } else if(tmode=="heat"){
        if(g(20)!=null) sendEvent(name:"heatingSetpoint", value: cToHub(g(20)))
        if(g(22)!=null) sendEvent(name:"coolingSetpoint", value: cToHub(g(22)))
    } else {   // auto / off -> the two thresholds are the active setpoints
        if(g(22)!=null) sendEvent(name:"coolingSetpoint", value: cToHub(g(22)))
        if(g(23)!=null) sendEvent(name:"heatingSetpoint", value: cToHub(g(23)))
    }
    if(g(17)!=null) sendEvent(name:"thermostatOperatingState", value: [0:"idle",1:"heating",2:"cooling"][g(17) as int])
    if(g(75)!=null) sendEvent(name:"thermostatFanMode", value: (g(75) as int)==1?"auto":"on")
    if(g(33)!=null) sendEvent(name:"comfortProfile", value: [0:"Home",1:"Sleep",2:"Away",3:"Hold"][g(33) as int] ?: "Hold")
    if(g(41)!=null){ String h=g(41).toString().replaceAll(/S$/,""); sendEvent(name:"holdEndsAt", value: h.startsWith("2014-01-03")?"":h) }
    if(g(25)!=null) sendEvent(name:"humiditySetpoint", value: g(25) as int, unit:"%")
    if(g(76)!=null) sendEvent(name:"fanState", value: [0:"inactive",1:"idle",2:"blowing"][g(76) as int] ?: "unknown")
    if(g(54)!=null) sendEvent(name:"thermostatAlert", value: g(54).toString())
    // per-profile setpoints (HAP iid34-39 follow ecobee's fixed Home/Away/Sleep climate order)
    if(g(34)!=null) sendEvent(name:"homeHeatSetpoint",  value: cToHub(g(34)))
    if(g(35)!=null) sendEvent(name:"homeCoolSetpoint",  value: cToHub(g(35)))
    if(g(36)!=null) sendEvent(name:"awayHeatSetpoint",  value: cToHub(g(36)))
    if(g(37)!=null) sendEvent(name:"awayCoolSetpoint",  value: cToHub(g(37)))
    if(g(38)!=null) sendEvent(name:"sleepHeatSetpoint", value: cToHub(g(38)))
    if(g(39)!=null) sendEvent(name:"sleepCoolSetpoint", value: cToHub(g(39)))
    // thermostat's own motion (iid66) / occupancy (iid65) are routed to a child sensor device (see the
    // sensor loop below + buildSensors), NOT to parent capabilities — keeps the parent exportable to HomeKit.
    sendEvent(name:"supportedThermostatModes", value: '["off","heat","cool","auto"]')
    sendEvent(name:"supportedThermostatFanModes", value: '["on","auto"]')
    // ---- custom params -> attribute (only when present; events are partial) ----
    def params=[:]; TCHARS.each{ iid,label-> if(label.startsWith("c_") && g(iid)!=null) params[label]= g(iid) }
    if(params){ sendEvent(name:"customParams", value: groovy.json.JsonOutput.toJson(params)) }
    rep("READ temp=${cToHub(g(19))} hum=${g(24)} mode=${g(18)!=null?[0:'off',1:'heat',2:'cool',3:'auto'][g(18) as int]:'-'} op=${g(17)!=null?[0:'idle',1:'heating',2:'cooling'][g(17) as int]:'-'} params=${params}")
    // ---- discovered sensors -> child devices (update only present attrs; events are partial) ----
    (state.sensors ?: []).each{ s->
        def val={ iid-> (iid!=null)? vmap["${s.aid}.${iid}"] : null }
        // DNI namespaced with the parent device id so multiple thermostats don't collide (esp. the
        // thermostat's own sensor, always aid 1). Adopt a pre-v0.12.1 child ("hap-<aid>") if present so
        // existing single-thermostat installs keep their child instead of getting a duplicate.
        String dni="hap-${device.id}-${s.aid}"
        def cd=getChildDevice(dni) ?: getChildDevice("hap-${s.aid}")
        if(!cd){
            if(val(s.temp)==null && val(s.occ)==null && val(s.motion)==null) return   // need some initial data to create
            String nm = s.isMain ? "${device.displayName} Sensor" : (val(s.name) ?: "Ecobee Sensor ${s.aid}")
            try{ cd=addChildDevice("RamSet","Ecobee HAP Remote Sensor",dni,[name:nm,label:nm]) }catch(e){ log.warn "child ${s.aid}: ${e}"; return }
        }
        if(val(s.serial)!=null) cd.sendEvent(name:"ecobeeId", value: val(s.serial))
        if(val(s.temp)!=null) cd.sendEvent(name:"temperature", value: cToHub(val(s.temp)), unit:"°${isF()?'F':'C'}")
        if(val(s.occ)!=null) cd.sendEvent(name:"presence", value: ((val(s.occ) as int)>0?"present":"not present"))
        if(val(s.motion)!=null) cd.sendEvent(name:"motion", value: (val(s.motion)?"active":"inactive"))
        if(val(s.batt)!=null) cd.sendEvent(name:"battery", value: val(s.batt) as int, unit:"%")
        else if(s.isMain) cd.sendEvent(name:"battery", value: 100, unit:"%")   // thermostat is wired — report full
        if(val(s.lowbatt)!=null) cd.sendEvent(name:"lowBattery", value: ((val(s.lowbatt) as int)==1?"true":"false"))
    }
}
