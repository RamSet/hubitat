import groovy.transform.Field

metadata {
    definition(name: "Ecobee HAP Thermostat", namespace: "RamSet", author: "RamSet") {
        capability "Thermostat"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "MotionSensor"
        capability "PresenceSensor"
        capability "Refresh"
        capability "Configuration"
        command "setActivity", [[name:"climate",type:"NUMBER",description:"0=home 1=away 2=sleep ..."]]
        command "resumeProgram"
        command "setCharacteristic", [[name:"aid.iid",type:"STRING"],[name:"value",type:"STRING"]]
        command "pair"
        command "discover"
        attribute "customParams", "string"
        attribute "hapStatus", "string"
    }
    preferences {
        input "ip",   "string", title: "Thermostat IP address", required: true
        input "port", "number", title: "HAP port (mDNS _hap._tcp)", required: true
        if (!state.paired) {
            input "setupCode", "string", title: "HomeKit setup code (XXX-XX-XXX) — enter it and click Save Preferences to pair", required: false
            input "accLtpk",      "string", title: "Advanced: Accessory LTPK (hex) — leave blank to pair with code", required: false
            input "accPairingId", "string", title: "Advanced: Accessory Pairing ID", required: false
            input "iosLtsk",      "string", title: "Advanced: Controller LTSK (hex)", required: false
            input "iosPairingId", "string", title: "Advanced: Controller Pairing ID", required: false
        }
        input "pollMins", "number", title: "Refresh interval (minutes)", defaultValue: 5
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
// in-memory receive/plaintext buffers (the /accessories response is large; keep it off state)
@Field static StringBuilder RX = new StringBuilder()
@Field static StringBuilder PLAIN = new StringBuilder()

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
    unschedule()
    int pm=(settings.pollMins?:5) as int; if(pm>0){ schedule("0 */${pm} * * * ?","refresh") }
    if(settings.setupCode && !state.paired){ log.info "HAP: setup code entered — pairing"; runIn(1,"pair") }
    else { runIn(2,"refresh") }
}
def configure(){ refresh() }

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
def refresh(){ if(state.sensors==null){ hapStart("discover", null) } else { hapStart("read", null) } }
def discover(){ hapStart("discover", null) }
def pair(){
    if(!settings.setupCode){ log.error "Enter the HomeKit setup code first"; return }
    if(!settings.ip || !settings.port){ log.error "Set IP and port first"; return }
    state.op="pairsetup"; state.sess=false; state.psstage="2"; RX.setLength(0); PLAIN.setLength(0)
    sendEvent(name:"hapStatus", value:"pairing")
    try { interfaces.rawSocket.connect([byteInterface:true], settings.ip, (settings.port as int)) }
    catch(e){ log.error "connect: $e"; return }
    sendHttpTlv("/pair-setup", tlv([[6,[1] as byte[]],[0,[0] as byte[]]]))   // State=M1, Method=PairSetup
}
void routePS(Map tv){ if(state.psstage=="2") psM2(tv) else if(state.psstage=="4") psM4(tv) else psM6(tv) }
void psM2(Map tv){
    if(tv[7]!=null){ sendEvent(name:"hapStatus",value:"pair err M2 ${hx(tv[7])}"); log.error "pair M2 ${hx(tv[7])}"; interfaces.rawSocket.close(); return }
    byte[] salt=tv[2]; byte[] Bb=tv[3]; java.math.BigInteger B=beBig(Bb)
    java.math.BigInteger a=beBig(rnd32()); byte[] Ab=bigBe(SRP_G.modPow(a,SRP_N),384)
    java.math.BigInteger u=beBig(sha512(cat(Ab,Bb)))
    java.math.BigInteger x=beBig(sha512(cat(salt, sha512(("Pair-Setup:"+settings.setupCode).getBytes("UTF-8")))))
    java.math.BigInteger base=B.subtract(SRP_K.multiply(SRP_G.modPow(x,SRP_N))).mod(SRP_N)
    byte[] K=sha512(bigBe(base.modPow(a.add(u.multiply(x)),SRP_N),384))
    byte[] hN=sha512(bigBe(SRP_N,384)); byte[] hg=sha512([5] as byte[]); byte[] hxor=new byte[64]; for(int i=0;i<64;i++) hxor[i]=(byte)(hN[i]^hg[i])
    byte[] M1=sha512(cat(hxor, sha512("Pair-Setup".getBytes("UTF-8")), salt, Ab, Bb, K))
    state.srpK=hx(K); state.srpA=hx(Ab); state.srpM1=hx(M1); state.psstage="4"; RX.setLength(0)
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
    state.psSeed=hx(seed); state.psPid=pid; state.psEncKey=hx(encKey); state.psstage="6"; RX.setLength(0)
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
    sendEvent(name:"hapStatus", value:"paired"); log.info "HAP: paired OK, keys stored"
    interfaces.rawSocket.close(); runIn(3,"refresh")
}
def setThermostatMode(String m){ def v=[off:0,heat:1,cool:2,auto:3][m?.toLowerCase()]; if(v!=null) writeChar(TAID,18,v) else log.warn "bad mode $m" }
def off(){ setThermostatMode("off") }
def heat(){ setThermostatMode("heat") }
def cool(){ setThermostatMode("cool") }
def auto(){ setThermostatMode("auto") }
def emergencyHeat(){ setThermostatMode("heat") }
def setHeatingSetpoint(t){ writeChar(TAID,23, round1(hubToC(t as BigDecimal))) }
def setCoolingSetpoint(t){ writeChar(TAID,22, round1(hubToC(t as BigDecimal))) }
def setThermostatSetpoint(t){ writeChar(TAID,20, round1(hubToC(t as BigDecimal))) }
def setActivity(climate){ writeChar(TAID,40, (climate as int)) }
def resumeProgram(){ writeChar(TAID,48, true) }
def setThermostatFanMode(String m){ writeChar(TAID,75, (m?.toLowerCase()=="on")?1:0) }
def fanOn(){ setThermostatFanMode("on") }
def fanAuto(){ setThermostatFanMode("auto") }
def fanCirculate(){ setThermostatFanMode("on") }
def setCharacteristic(String aidIid, String value){ def parts=aidIid.split("\\."); def v = value.isNumber()? (value.contains(".")? (value as BigDecimal):(value as Integer)) : value; writeChar(parts[0] as long, parts[1] as int, v) }
def setSchedule(s){}

BigDecimal round1(BigDecimal v){ return (v*10).setScale(0, java.math.RoundingMode.HALF_UP)/10 }
boolean isF(){ return (location?.temperatureScale ?: "F") == "F" }
def hubToC(BigDecimal t){ isF()? ((t-32)*5/9) : t }
def cToHub(v){ if(v==null) return null; def c=(v as BigDecimal); return isF()? round1(c*9/5+32) : round1(c) }

void writeChar(long aid, int iid, val){
    def jv = (val instanceof Boolean) ? val : ((val instanceof Number)? val : "\"${val}\"")
    state.writeJson = "{\"characteristics\":[{\"aid\":${aid},\"iid\":${iid},\"value\":${jv}}]}"
    hapStart("write", state.writeJson)
}

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
        if(acc.aid==TAID) return
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
    log.info "HAP: discovered ${sensors.size()} remote sensor(s)"
}
def hapStart(String op, String body){
    state.op=op; state.inCtr=0; state.outCtr=0; RX.setLength(0); PLAIN.setLength(0)
    state.sess=false; state.vstage="m2"
    def ek=genEph(); state.ephPriv=ek.priv; state.ephPub=ek.pub
    sendEvent(name:"hapStatus", value:"connecting")
    try { interfaces.rawSocket.connect([byteInterface:true], settings.ip, (settings.port as int)) }
    catch(e){ log.error "connect: $e"; rep("ERR connect $e"); return }
    sendHttpTlv("/pair-verify", tlv([[6,[1] as byte[]],[3,hex(state.ephPub)]]))
}
void sendHttpTlv(String path, byte[] b){ String h="POST ${path} HTTP/1.1\r\nHost: ${settings.ip}\r\nContent-Type: application/pairing+tlv8\r\nContent-Length: ${b.length}\r\nConnection: keep-alive\r\n\r\n"; interfaces.rawSocket.sendMessage(hx(cat(h.getBytes("UTF-8"),b))) }
void sendEncrypted(String req){ byte[] plain=req.getBytes("UTF-8"); def o=new java.io.ByteArrayOutputStream(); long ctr=state.outCtr
    for(int i=0;i<plain.length;i+=1024){ int n=Math.min(1024,plain.length-i); byte[] ch=new byte[n]; for(int j=0;j<n;j++) ch[j]=plain[i+j]
        byte[] aad=le16(n); byte[] ct=chachaEnc(hex(state.c2a),nctr(ctr),ch,aad); ctr++; o.write(aad,0,2); o.write(ct,0,ct.length) }
    state.outCtr=ctr; interfaces.rawSocket.sendMessage(hx(o.toByteArray())) }
def socketStatus(String s){ if(!s?.toLowerCase()?.contains("close")) log.warn "socket: $s" }

def parse(String message){
  try {
    RX.append(message.toLowerCase())
    if(!state.sess){
        String buf=RX.toString()
        int p=buf.indexOf("0d0a0d0a"); if(p<0) return
        String hh=new String(hex(buf.substring(0,p))); def m=(hh =~ /(?i)content-length:\s*(\d+)/); int cl=m.find()?(m.group(1) as int):0
        int need=p+8+cl*2; if(buf.length()<need) return
        byte[] body=hex(buf.substring(p+8,need)); RX.delete(0,need); def tv=tdec(body)
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
    byte[] ct=chachaEnc(sk,nlabel("PV-Msg03"),sub,null); state.vstage="m4"; RX.setLength(0)
    sendHttpTlv("/pair-verify", tlv([[6,[3] as byte[]],[5,ct]]))
}
void doM4(Map tv){
    if(tv[7]!=null){ rep("ERR verify ${hx(tv[7])}"); interfaces.rawSocket.close(); return }
    byte[] shared=hex(state.shared)
    state.c2a=hx(hkdf("Control-Salt".getBytes("UTF-8"),shared,"Control-Write-Encryption-Key".getBytes("UTF-8"),32))
    state.a2c=hx(hkdf("Control-Salt".getBytes("UTF-8"),shared,"Control-Read-Encryption-Key".getBytes("UTF-8"),32))
    state.sess=true; RX.setLength(0); PLAIN.setLength(0); state.inCtr=0
    sendEvent(name:"hapStatus", value:"session")
    if(state.op=="read"){ sendEncrypted("GET /characteristics?id=${readIds()} HTTP/1.1\r\nHost: ${settings.ip}\r\n\r\n") }
    else if(state.op=="discover"){ sendEncrypted("GET /accessories HTTP/1.1\r\nHost: ${settings.ip}\r\n\r\n") }
    else { String b=state.writeJson; sendEncrypted("PUT /characteristics HTTP/1.1\r\nHost: ${settings.ip}\r\nContent-Type: application/hap+json\r\nContent-Length: ${b.getBytes('UTF-8').length}\r\nConnection: keep-alive\r\n\r\n"+b) }
}
void handleSession(){
    String buf=RX.toString()
    while(buf.length()>=4){
        byte[] lh=hex(buf.substring(0,4)); int ln=(lh[0]&0xff)|((lh[1]&0xff)<<8); int need=4+(ln+16)*2
        if(buf.length()<need) return
        byte[] aad=hex(buf.substring(0,4)); byte[] blk=hex(buf.substring(4,need)); RX.delete(0,need); buf=RX.toString()
        byte[] pt=chachaDec(hex(state.a2c),nctr(state.inCtr),blk,aad); state.inCtr=(state.inCtr as long)+1; PLAIN.append(hx(pt))
        if(ln<1024){ finish(); return }
    }
}
void finish(){
    byte[] resp=hex(PLAIN.toString()); String s=new String(resp,"UTF-8"); state.sess=false; state.vstage=null
    PLAIN.setLength(0); RX.setLength(0)
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
    if(state.op=="discover"){ buildSensors(j); runIn(1,"refresh"); return }
    applyState(j)
}
void applyState(j){
    def vmap=[:]   // "aid.iid" -> value
    j.characteristics.each{ vmap["${it.aid}.${it.iid}"]= it.value }
    // ---- thermostat ----
    def g={ iid-> vmap["${TAID}.${iid}"] }
    if(g(19)!=null) sendEvent(name:"temperature", value: cToHub(g(19)), unit:"°${isF()?'F':'C'}")
    if(g(24)!=null) sendEvent(name:"humidity", value: g(24) as int, unit:"%")
    if(g(20)!=null) sendEvent(name:"thermostatSetpoint", value: cToHub(g(20)))
    if(g(22)!=null) sendEvent(name:"coolingSetpoint", value: cToHub(g(22)))
    if(g(23)!=null) sendEvent(name:"heatingSetpoint", value: cToHub(g(23)))
    if(g(18)!=null) sendEvent(name:"thermostatMode", value: [0:"off",1:"heat",2:"cool",3:"auto"][g(18) as int])
    if(g(17)!=null) sendEvent(name:"thermostatOperatingState", value: [0:"idle",1:"heating",2:"cooling"][g(17) as int])
    if(g(75)!=null) sendEvent(name:"thermostatFanMode", value: (g(75) as int)==1?"on":"auto")
    if(g(66)!=null) sendEvent(name:"motion", value: (g(66)? "active":"inactive"))
    if(g(65)!=null) sendEvent(name:"presence", value: ((g(65) as int)>0? "present":"not present"))
    sendEvent(name:"supportedThermostatModes", value: '["off","heat","cool","auto"]')
    sendEvent(name:"supportedThermostatFanModes", value: '["on","auto"]')
    // ---- all custom params -> attribute + log ----
    def params=[:]; TCHARS.each{ iid,label-> if(label.startsWith("c_")) params[label]= g(iid) }
    sendEvent(name:"customParams", value: groovy.json.JsonOutput.toJson(params))
    rep("READ temp=${cToHub(g(19))} hum=${g(24)} mode=${[0:'off',1:'heat',2:'cool',3:'auto'][g(18) as int]} op=${[0:'idle',1:'heating',2:'cooling'][g(17) as int]} params=${params}")
    // ---- discovered sensors -> child devices ----
    (state.sensors ?: []).each{ s->
        def val={ iid-> (iid!=null)? vmap["${s.aid}.${iid}"] : null }
        if(val(s.temp)==null) return
        String nm = val(s.name) ?: "Ecobee Sensor ${s.aid}"
        def dni="hap-${s.aid}"; def cd=getChildDevice(dni)
        if(!cd){ try{ cd=addChildDevice("RamSet","Ecobee HAP Remote Sensor",dni,[name:nm,label:nm]) }catch(e){ log.warn "child ${s.aid}: ${e}"; return } }
        if(val(s.serial)!=null) cd.sendEvent(name:"ecobeeId", value: val(s.serial))
        cd.sendEvent(name:"temperature", value: cToHub(val(s.temp)), unit:"°${isF()?'F':'C'}")
        if(val(s.occ)!=null) cd.sendEvent(name:"presence", value: ((val(s.occ) as int)>0?"present":"not present"))
        if(val(s.motion)!=null) cd.sendEvent(name:"motion", value: (val(s.motion)?"active":"inactive"))
        if(val(s.batt)!=null) cd.sendEvent(name:"battery", value: val(s.batt) as int, unit:"%")
        if(val(s.lowbatt)!=null) cd.sendEvent(name:"lowBattery", value: ((val(s.lowbatt) as int)==1?"true":"false"))
    }
}
