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
 *   - mDNS unicast _hap._tcp port discovery (the HAP port is dynamic)
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
 * Version: 0.8.0
 *
 * Changelog:
 *  v0.8.0 - Tracks the package release; engine helpers stable.
 *  v0.6.x - Added accessory-info parsing (accInfo), auto-recovery heartbeat (ensureUp), clean
 *           socket close handling.
 *  v0.5.0 - Tolerant keepalive watchdog (reconnect only after N consecutive misses).
 *  v0.4.x - On-demand mode plumbing (startSession/discoverOnce/pollRead), one-shot connect
 *           watchdog, serialized connects (connInFlight guard).
 *  v0.3.0 - RemovePairing (unpair) + clearLocalPairing.
 *  v0.1.0 - Initial: SRP-6a pair-setup, pair-verify, ChaCha20 session, X25519/Ed25519/TLV8,
 *           mDNS port discovery, persistent subscribed session, /accessories + /characteristics.
 *
 * Copyright 2026 RamSet — Apache License 2.0, provided as-is, no warranty.
 */

library(
    author: "RamSet",
    category: "utility",
    description: "HomeKit Accessory Protocol (HAP) controller engine: pair-setup/verify, ChaCha20 session, X25519/Ed25519/SRP6a, TLV8, mDNS, /accessories + /characteristics.",
    name: "hapCore",
    namespace: "RamSet",
    importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/libraries/hap-core/hap-core.groovy",
    documentationLink: "https://github.com/RamSet/hubitat-homekit-import"
)

import groovy.transform.Field

// ===== in-memory buffers (keyed by device.id — @Field static is shared across ALL instances) =====
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

boolean isPaired(){ return (state.paired==true || settings?.iosLtsk) ? true : false }
// On-demand mode: connect → verify → read/write → close per action, plus periodic polling. No held
// session/subscriptions. Use for accessories that hard-close the HAP connection on a short timer (e.g.
// Meross MSG100 drops it ~every 45s regardless of traffic, which makes a persistent session impossible).
boolean onDemand(){ return settings?.sessionMode=="On-demand (poll)" }
int pollSecs(){ return Math.max(1,(settings?.pollMins ?: 5) as int)*60 }

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
byte[] rnd32(){ byte[] raw=new byte[32]
    try{ def kp=java.security.KeyPairGenerator.getInstance("X25519").generateKeyPair(); byte[] enc=kp.getPrivate().getEncoded(); for(int i=0;i<32;i++) raw[i]=enc[enc.length-32+i] }
    catch(Throwable e){ state.entc=(state.entc?:0)+1; byte[] hh=sha512((""+now()+":"+state.entc+":"+(settings?.iosLtsk?:'x')).getBytes("UTF-8")); for(int i=0;i<32;i++) raw[i]=hh[i] }
    return raw }
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
    if(tries < 2){   // the port can change after a reboot/power-cycle, so getting the CURRENT one matters
        state.mdnsTries=tries+1
        log.warn "HAP: mDNS port detect timed out — retry ${state.mdnsTries}/2"
        mdnsThen(op); return
    }
    state.mdnsTries=0
    log.warn "HAP: mDNS port detect timed out; using last-known port"; dispatchOp(op)
}
def mdnsCallback(message){
    try {
        String desc = message.toString(); if(settings.debugLog) log.debug "HAP mdns raw: ${desc}"
        def m = null; try { m = parseLanMessage(desc) } catch(ig){}
        String h = ((m?.payload ?: m?.body ?: desc) ?: "").toString().toLowerCase().replaceAll("[^0-9a-f]","")
        def r = parseMdns(h)
        if(r.port){ device.updateSetting("port",[value:r.port,type:"number"]); state.discoveredPort=r.port; state.mdnsTries=0; logInfo "HAP: detected port ${r.port}" }
        else log.warn "HAP: no SRV in mDNS reply"
        unschedule("mdnsTimeout")
        def op=state.afterMdns; state.afterMdns=null; if(op) dispatchOp(op)
    } catch(e){ log.error "mdnsCallback: ${e}" }
}
// minimal mDNS/DNS answer walker -> [ip, port, sf]
Map parseMdns(String h){
    byte[] b; try { b=hex(h) } catch(e){ return [:] }
    def res=[ip:null, port:null, sf:-1]
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
            int si=t.indexOf("sf="); if(si>=0 && si+3<t.length()){ try{ res.sf=Integer.parseInt(t.substring(si+3,si+4)) }catch(ig){} }
        }
        p=rd+rdlen
    }
    return res
}
int skipName(byte[] b, int p){ while(p<b.length){ int l=b[p]&0xff; if(l==0) return p+1; if((l&0xC0)==0xC0) return p+2; p+=1+l }; return p }
void dispatchOp(String op){ if(op=="pairsetup") pairConnect() else if(op=="live") liveConnect() else if(op in ["read","discover","write","unpair"]) hapStart(op, op=="write"? state.writeJson : null) }
int hapPort(){ return (state.discoveredPort ?: settings.port ?: 0) as int }

// ===== pair-setup (SRP-6a) =====
def pair(){
    if(!settings.setupCode){ log.error "Enter the HomeKit setup code first"; return }
    if(!settings.ip){ log.error "Set the accessory IP first"; return }
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
        String err = tv[7]!=null ? "TLV error ${hx(tv[7])}" : (head.split('\r\n')[0])
        log.warn "HAP: RemovePairing failed (${err}). If the accessory is offline, use Forget and reset HomeKit on the device."
        sendEvent(name:"hapStatus", value:"unpair failed")
    }
}
// clear only OUR side of the pairing (keys + session state). Does NOT notify the accessory.
void clearLocalPairing(){
    state.paired=false; state.live=false
    ["iosLtsk","iosPairingId","accLtpk","accPairingId","setupCode"].each{ device.removeSetting(it) }
    ["c2a","a2c","shared","services","discoveredPort","writeJson"].each{ state.remove(it) }
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
// persistent-mode recovery heartbeat: if paired but not live and nothing in flight, re-establish.
// Auto-recovers after the accessory frees a wedged slot (e.g. post-reboot) without any user action.
def ensureUp(){ if(isPaired() && !onDemand() && state.live!=true && !state.connInFlight){ dlog("ensureUp -> reconnect"); startSession() } }
def schedulePoll(){ unschedule("pollRead"); runIn(pollSecs(),"pollRead") }
def pollRead(){ if(isPaired() && onDemand()){ if(state.services==null) discoverOnce() else refresh(); schedulePoll() } }
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
    try { interfaces.rawSocket.connect([byteInterface:true], settings.ip, hapPort()) }
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
    if(state.live && (l.contains("close") || l.contains("error"))){ state.live=false; sendEvent(name:"hapStatus", value:"reconnecting"); log.warn "HAP: live socket dropped (${s}); reconnecting"; runIn(8,"startLive") }
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
    state.sess=true; rxbuf().setLength(0); plainbuf().setLength(0); state.inCtr=0; state.wretry=0; state.lastRx=now()
    unschedule("oneshotWatch")
    sendEvent(name:"hapStatus", value:"session")
    if(state.op=="live"){
        state.live=true; state.vtry=0; state.kaMiss=0; state.connInFlight=null; unschedule("verifyWatch")
        sendEvent(name:"hapStatus", value:"live"); logInfo "HAP: live session up — subscribing to events"
        dlog("session up (live) -> subscribe + get")
        unschedule("liveKeepalive"); runIn(KEEPALIVE_SEC,"liveKeepalive")   // hold the connection warm (some accessories idle-close fast)
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
        if(state.op!="live" && ln<1024){ finish(); return }
    }
    if(state.op=="live") processLiveStream()
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
    def j; try{ j=new groovy.json.JsonSlurper().parseText(body) }catch(e){ rep("ERR json ${e}; head=${head.split('\r\n')[0]}"); return }
    if(state.op=="discover"){ onAccessories(j); runIn(1,"startSession"); return }
    onCharacteristics(j)
}

// ===== live event mode (persistent session + subscriptions) =====
def startLive(){ if(!isPaired()){ log.warn "HAP: not paired"; return }; unschedule("liveKeepalive"); unschedule("kaWatch"); mdnsThen(state.services==null ? "discover" : "live") }
void liveConnect(){
    if(hapPort()<=0){ log.warn "HAP: no port"; return }
    if(state.connInFlight && (now()-(state.connAt?:0) < 14000)){ rep("liveConnect skip: ${state.connInFlight} in-flight"); return }   // don't stack overlapping connects (wedges single-slot accessories)
    state.connInFlight="live"; state.connAt=now()
    state.op="live"; state.inCtr=0; state.outCtr=0; rxbuf().setLength(0); plainbuf().setLength(0); state.sess=false; state.vstage="m2"; state.live=false
    def ek=genEph(); state.ephPriv=ek.priv; state.ephPub=ek.pub
    sendEvent(name:"hapStatus", value:"connecting (live)")
    try { interfaces.rawSocket.connect([byteInterface:true], settings.ip, hapPort()) }
    catch(e){ log.error "live connect: $e"; state.connInFlight=null; runIn(30,"startLive"); return }
    sendHttpTlv("/pair-verify", tlv([[6,[1] as byte[]],[3,hex(state.ephPub)]]))
    unschedule("verifyWatch"); runIn(12,"verifyWatch")   // pair-verify must complete in 10s or we retry (Meross often stalls at M2)
}
// pair-verify watchdog: if the handshake didn't reach a session, close + retry with capped backoff,
// re-resolving the port via mDNS each time (the port and the single connection slot can both go stale).
def verifyWatch(){
    if(!state.sess){
        // exponential-ish backoff to 5 min: a wedged accessory needs QUIET time to recover its HAP
        // server, not a reconnect every minute (hammering keeps its single connection slot churning)
        state.vtry=(state.vtry?:0)+1; int b=Math.min(300, 30*(state.vtry as int))
        log.warn "HAP: pair-verify timed out (no M2) — retry ${state.vtry} in ${b}s"
        try{ interfaces.rawSocket.close() }catch(e){}; state.connInFlight=null
        runIn(b,"startLive")
    }
}
// PURE LISTEN (like a real HomeKit controller): we NEVER poll. The ev:true subscription delivers
// real-time updates; polling (GET /characteristics) is exactly what makes cheap chips drop the session,
// so we don't do it at all. This watchdog only RECONNECTS (gentle, not a poll) if the link has been
// totally silent for a long time — recovering a silently-dead connection without antagonising the device.
// Active accessories send events, so this never fires during use; it only refreshes a long-idle session.
def liveKeepalive(){
    if(state.live && state.sess){
        if((now() - (state.lastRx?:0L)) >= (SILENCE_RECONNECT_SEC*1000L)){
            log.warn "HAP: silent ${SILENCE_RECONNECT_SEC}s — refreshing session (reconnect, no poll)"
            state.live=false; unschedule("liveKeepalive"); try{ interfaces.rawSocket.close() }catch(e){}; state.connInFlight=null
            runIn(4,"liveConnect"); return
        }
        runIn(KEEPALIVE_SEC,"liveKeepalive")
    } else { startLive() }
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
