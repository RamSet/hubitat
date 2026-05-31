/**
 *  Spruce Connect — Hubitat cloud-to-cloud app for the WiFi Spruce controller.
 *  IMPORT URL: https://raw.githubusercontent.com/RamSet/hubitat/main/spruce-connect.groovy
 *
 *  NOTE: this app is only needed for the WiFi Spruce controller
 *  (PS-SPRWIFI16-01). The Zigbee Spruce Controller SST (PS-SPRZ16-01) does
 *  not use it. If you don't have a WiFi controller paired, the app can be
 *  removed safely.
 *
 *  Changelog:
 *   v1.11 (2026-05) RamSet:
 *                   - Restored preference-page icons using OpenMoji
 *                     (https://openmoji.org, CC-BY-SA 4.0) PNGs served from
 *                     GitHub raw.
 *
 *   v1.10 (2026-05) RamSet cleanup:
 *                   - Stripped dead http://www.plaidsystems.com/smartthings/*.png
 *                     image URLs that were 404-ing every time a preference page
 *                     rendered.
 *                   - Added importUrl.
 *
 *  Copyright 2019 Plaid Systems
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *________________________________________________________________________________________________
 *
 *
 * Spruce Web Connect Cloud-to-Cloud
 *  v1.0 - 04/01/18 - convert to cloud-cloud
 *  v1.1 - 06/05/18 - correct IOS error, rename page to pageController
 *  v1.2 - 06/20/19 - temperature units, add time resume delay for contact
 * ^^^^^ SmartThings Update Log ^^^^^^
 *
 * Hubitat specific based on v1.2
 *  v1.3 - 11/2019
 *       - gateway must be labeled smartthings until a later date
 *       - pause() changed to hold(), hubitat throws error when pause command executed
 *       - update events to master
 *
 * BAB updates:
 * v1.3bab - Create more descriptive device.labels for Controller, Zones and Spruce Pause devices
 *         - (NOTE: Spruce wifi master now uses the Pause device.deviceNetworkId instead of device.name)
 * v1.4bab - Added debug on/off, cleaned up settings collection pages
 * v1.5bab - Handle sensor device rename
 *		   - (NOTE: Sensor names are defined by Hubitat devices - Spruce Cloud App edits will be overwritten)
 * v1.6bab - Enable instances (not currently supported by Spruce Cloud), with custom label names
 * v1.7bab - Create/Rename all controllers, zones and schedules when installed AND when updated
 *		   - Don't delete existing children - rename them if name or label changes in the Spruce Cloud
 *		   - (NOTE: Contyroller, Zone & Schedule names are defined by the Spruce Cloud App - local edits will be overwritten)
 *		   - Refresh sensors 30 seconds after initialization (resend all states to Spruce Cloud)
 *		   - Add 30-second timeout to all http calls
 *		   - Remove Pause device if not configured
 *		   - Remove unused/deleted zones & schedules after creating/renaming the current ones
 * v1.8bab - sensorUpdater displays both battery and batteryRaw (transmit value)
 * v1.9bab - zone_state() - don't update masterDevice with zone commands
 *
 */

definition(
    name: "Spruce Connect",
    namespace: "plaidsystems",
    author: "Plaid Systems",
    importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/spruce-connect.groovy",
    description: "Connect Spruce Controller and Sensors to Hubitat Elevation",
    category: "",
    iconUrl:   "https://raw.githubusercontent.com/hfg-gmuend/openmoji/master/color/618x618/1F332.png",
    iconX2Url: "https://raw.githubusercontent.com/hfg-gmuend/openmoji/master/color/618x618/1F332.png",
    iconX3Url: "https://raw.githubusercontent.com/hfg-gmuend/openmoji/master/color/618x618/1F332.png",
    oauth: true,
    singleInstance: true
)
String getVersionText()     {return "Spruce Connect v1.9bab\nRelease: 06.19.2020"}
String getClientId()        {return "hubitat"}
String getClientSecret()    {return "6137b7f5efabd2f8a82f80c95f6dd50807426f72"}

//-----------------pages----------------------------
preferences (oauthPage: "authPage") {
	page(name: "authPage")
 	page(name: "pageConnect")
	page(name: "pageController")
    page(name: "pageDevices")
    page(name: "pageUnsetKey")
}

def authPage(){

    if(!atomicState.accessToken) atomicState.accessToken = createAccessToken()	//set = so token is saved to atomicState 
    
    if(!atomicState.authToken){
    	log.debug "pageConnect"
		pageConnect()
    }
    else if (!controllerSelected()){
    	log.debug "pageController"
    	pageController()
    }
    else pageDevices()
}

def controllerSelected(){	
	if (settings.controller != null) return true
    return false
}

def pageConnect(){
    if(!atomicState.authToken){        
        log.debug atomicState.accessToken
        def redirectUrl = oauthInitUrl()
        
        log.debug "redirectUrl ${redirectUrl}"
        dynamicPage(name: "pageConnect", title: "<b>Connect Account<b/>",  uninstall: false, install:false) {
            section {
                href url: redirectUrl, style:"embedded", required:false, title:"<b>Connect Spruce Account</b>", image: 'https://raw.githubusercontent.com/hfg-gmuend/openmoji/master/color/618x618/1F332.png', description: "Login to grant access"
            }
        }
    }    
    else pageController()
}

def pageController(){
    if (atomicState.authToken && getControllerList()){
    	def select_device = getSpruceDevices()
        log.debug select_device
        dynamicPage(name: "pageController", uninstall: true, install:false, nextPage: "pageDevices") {            
            section("<b>Spruce Controller<b>") {
            	input(name: "controller", title:"<b>Select Spruce Controller:</b>", type: "enum", required:true, multiple:false, description: "Click to choose", options:select_device)
    		}
            section("${getVersionText()}"){}
        }
    }    
    else pageDevices()
}

def pageDevices(){
	if (atomicState.authToken && controllerSelected() && getControllerSettings()){
      log.debug atomicState.zoneUpdate
      log.debug "pageDevices"
        dynamicPage(name: "pageDevices", title:"<h3><b>${getVersionText()}</b></h3>", uninstall: true, install:true) {
        	if(atomicState.zoneUpdate == true) section("<b>Device changes found, device tiles will be updated!</b>\n\nDevice tiles and automations must be updated!\n"){}
            section("<b>Settings for Connected devices\nConnected controller:</b> ${settings.controller}\n<b>Connected zones:</b> ${zoneList()}") {
				label title: "<b>Enter a name for this instance</b>", required: false, submitOnChange: true
			}
			section("<b>Notifications</b>") {
                input "sendPushMessage", "capability.notification", title: "<b>Notification Devices</b>", descriptionText: "Select Hubitat notification devices", multiple: true, required: false
                input(name: "notifications", title:"<b>Notifications to Send</b>", type: "enum", required:false, multiple:true, description: "Select desired notifications", options: ['Schedule','Zone','Valve Fault'])                
            }
            section("<b>Hubitat Spruce Sensors</b>") {
                paragraph "Select local Spruce Sensors that will be reported to Spruce Cloud:"
                input "sensors", "device.SpruceSensor", title: "<b>Spruce Moisture Sensors:</b>", required: false, multiple: true
            }
            section("<b>Pause and resume</b>") {
                paragraph "Use the Spruce Pause control with Hubitat Rule Machine, or add contact sensors without any rules to pause & resume irrigation."
                input(name: "pause", title:"<b>Create the Spruce Pause control</b>", type: 'bool', defaultValue: false)
            	input "contacts", "capability.contactSensor", title: "<b>Contact Sensors</b>", descriptionText: "Select contact sensors that will pause watering when open", required: false, multiple: true
				if (settings.contacts) input "delay", "number", title: "<b>Resume Watering Delay</b> (in minutes, default=5, max=119)", 
					descriptionText:"Delay after the contact${contacts?.size()>1?'s are':' is'} closed</b>\n(Delay in minutes default=5, max=119)", defaultValue: 5, required: false, range: '0..119'
            }
            section('') {
                input "debugOn", "bool", title: "<b>Enable debug logging</b>", defaultValue: true
		        input "infoOn", "bool", title: "<b>Enable descriptionText logging?</b>", defaultValue: true
            }
            section("${getVersionText()}"){}
        }   
    }    
    else {
    	atomicState.authToken = null
    	authPage()
    }
}

def zoneList(){
	def tempZoneMap = atomicState.zoneMap
    def zoneString = ""
	def comma = ''
    tempZoneMap.sort().each{
        def zone_name = "Zone ${it.key}"
        if ("${tempZoneMap[it.key]['zone_name']}" != 'null') zone_name = "${tempZoneMap[it.key]['zone_name']}"
    	zoneString += (comma + zone_name)
        if (!comma) comma = ", "
    }
    return zoneString;
}

mappings {
  path("/event/:command") {
    action: [
      POST: "event"
    ]
  }  
  path("/zonestate/:command") {  
    action: [
      POST: "zone_state"
    ]
  }
  path("/rain/:command") {
    action: [
      POST: "rain"
    ]
  }
  path("/pause/:command") {
    action: [
      POST: "hold"
    ]
  }
  path("/initialize") {
  	action: [
    	GET: "oauthInitUrl"
    ]
  }
  path("/oauth/callback") {
  	action: [
    	GET: "callback"
    ]
  }   
}


//***************** install *******************************

def installed() {
	log.debug "Installed with settings: ${settings}"    
    state.counter = state.counter ? state.counter + 1 : 1
        
    if (state.counter == 1) initialize()    
}

def updated() {
	log.debug "Updated with settings: ${settings}"	
    
	if (state.counter == 1){
    	unsubscribe()
    	initialize()
    }
    
}

def initialize() {	
    log.debug "${app.label} - Initializing..."
	if (infoOn) log.info "${app.label} - info logging enabled"
	if (debugOn) log.debug "${app.label} - debug logging enabled for 30 minutes"
	atomicState.versionText = getVersionText()
	atomicState.clientId = getClientId()
	atomicState.clientSecret = getClientSecret()
	
    atomicState.zones_on = 0
    
	if (settings.sensors) {getSensors(); runIn(30, sensorUpdater, [overwrite: true]); }
    if (settings.contacts) getContacts()
    
    //add devices to web, check for schedules
    if(atomicState.accessToken){
    	if (debugOn) log.debug getApiServerUrl()
        addDevices()					// Add/update Sensors to DB
		atomicState.zoneUpdate = true	// Check for new or renamed zones
		atomicState.manUpdate = true	// Check for new of renamed manual schedules
    	createChildDevices()        
	}  
	
    if (debugOn) runIn(1800, debugOff, [overwrite: true])
}

def debugOff(){
    log.debug "${app.label} - debug logging disabled..."
    app.updateSetting("debugOn",[value:"false",type:"bool"])
    settings.debugOn = false
}

def uninstalled() {
    if (debugOn) log.debug "uninstall"
    removeChildDevices()   
}

//get zone device list
def getSpruceDevices(){	
   def controllers = []
   
   def tempSwitch = atomicState.switches   
   tempSwitch.each{
       controllers.add(it.key)
   }   
   return controllers       
}

//sensor subscriptions
def getSensors(){    
    if (debugOn) log.debug "getSensors: " + settings.sensors    
    
    def tempSensors = [:]
    settings.sensors.each{
    	tempSensors[it]= (it.device.zigbeeId)
        }
    atomicState.sensors = tempSensors
    
    subscribe(settings.sensors, "humidity", sensorHandler)
    subscribe(settings.sensors, "temperature", sensorHandler)
    subscribe(settings.sensors, "battery", sensorHandler)    
}

//contact subscriptions
def getContacts(){    
    if (debugOn) log.debug "getContacts: " + settings.contacts    
    
    def tempContacts = [:]
    settings.contacts.each{
    	tempContacts[it]= (it.device.zigbeeId)
        }
    atomicState.contacts = tempContacts
    
    subscribe(settings.contacts, "contact", contactHandler)    
}

//------------------create and remove child tiles------------------------------

//create zone tiles children
private void createChildDevices(){
	if (debugOn) log.debug "create zone children ${atomicState.zoneUpdate} with ${app.id}"
    if(atomicState.zoneUpdate == true){
    	//removeChildDevices()
    
        //set Spruce Controller Name  
		String controllerName = settings.controller
		if (!controllerName.contains("Spruce")) controllerName = "Spruce " + controllerName
		if (!controllerName.contains("Controller")) controllerName = controllerName + " Controller"
		def existingChild = getChildDevice("${app.id}.0") // childDevices?.find(it.value.deviceNetworkId == "${app.id}.0")	// The controller
		String name = "Spruce WiFi Controller"
		if (!existingChild) {
			if (infoOn) log.info "${app.label} - Creating Controller: ${controllerName} / ${name} (${app.id}0)"
        	addChildDevice("plaidsystems", "Spruce wifi master", "${app.id}.0", null, [completedSetup: true, label: "${controllerName}", isComponent: false, name: name])
		} else {
			boolean renamed = false
			if (existingChild.name != name) {
				existingChild.name = name
				renamed = true
			}
			if (existingChild.label != controllerName) {
				existingChild.label = controllerName
				renamed = true
			}
			if (renamed) {
				if (infoOn) log.info "${app.label} - Renamed Controller ${controllerName} / ${name} (${app.id}.0)"
			} else {
				if (infoOn) log.info "${app.label} - Controller ${controllerName} / ${name} (${app.id}.0) already exists"
			}
		}

        //Create children
		String prefix = controllerName.endsWith(' Controller') ? controllerName - " Controller" : controllerName
        def tempZoneMap = atomicState.zoneMap
		if (debugOn) log.debug "tempZoneMap: ${tempZoneMap}"
        tempZoneMap.each{
			String label = "${prefix} ${it.key} - ${tempZoneMap[it.key]['zone_name']}"
			name = "${controllerName} Zone ${it.key}"
			String childDNI = "${app.id}.${it.key}"
			existingChild = getChildDevice(childDNI) //getChildDevices().find(it.deviceNetworkId == childDNI)
			if (!existingChild) {
				if (infoOn) log.info "${app.label} - Adding Spruce wifi zone: ${label} / ${name} (${childDNI})"
				addChildDevice("plaidsystems", "Spruce wifi zone", childDNI, null, [completedSetup: true, label: label, isComponent: false, name: name])
			} else {
				boolean renamed = false
				if (existingChild.name != name) {
					existingChild.name = name
					renamed = true
				}
				if (existingChild.label != label) {
					existingChild.label = label
					renamed = true
				}
				if (renamed) {
					if (infoOn) log.info "${app.label} - Renamed Spruce wifi zone ${label} / ${name} (${childDNI})"
				} else {
					if (infoOn) log.info "${app.label} - Spruce wifi zone ${label} / ${name} (${childDNI}) already exists"
				}
			}
        }
		//remove any unused child zones
		childDevices?.each() {
			if (it.typeName == "Spruce wifi zone") {
				def index = it.deviceNetworkId.toString().tokenize('.').last()
				if (!tempZoneMap || !tempZoneMap[index]) {
					if (infoOn) log.info "${app.label} - Removing unused Spruce wifi zone ${it.label} / ${it.name} (${it.deviceNetworkId})"
					deleteChildDevice(it.deviceNetworkId)
				}
			}
		}
    }
    if (atomicState.manUpdate == true) child_schedules("${app.id}.0")      
}

//remove zone tiles children - SHOULD ONLY BE CALLED WHEN REMOVING THIS INSTANCE!!!
private removeChildDevices() {
	if (debugOn) log.debug "remove ALL children"
	
    //get and delete children avoids duplicate children
    def children = getChildDevices()  
	if (debugOn) log.debug "removing ${children}"
    if(children != null){
        children.each{
        	deleteChildDevice(it.deviceNetworkId)
        }
    }       
}

//add child device tiles to master
def child_schedules(dni){
	if (debugOn) log.debug "get child tiles for master"
	def childDevice = getChildDevice(dni) 	// childDevices.find{it.deviceNetworkId == dni}
    if (debugOn) log.debug "${childDevice}"
    
	String controllerName = settings.controller
	if (!controllerName.startsWith("Spruce ")) controllerName = "Spruce " + controllerName
	if (!controllerName.endsWith(" Controller")) controllerName = controllerName + " Controller"
	
    // Pause Device for this Controller
	if(settings.pause == true) {
		if (!childDevice.getChildDevice("${app.id}.99")) {
			childDevice.createScheduleDevices("${app.id}", 99, 0, "${controllerName} Pause")
			if (infoOn) log.info "${app.label} - Added ${controllerName} Pause device (${app.id}.99)"
		} else {
			if (infoOn) log.info "${app.label} - ${controllerName} Pause device (${app.id}.99) already exists"
		}
	} else {
		if (childDevice.getChildDevice("${app.id}.99")) {
			if (infoOn) log.info "${app.label} - Removing ${controllerName} Pause device (${app.id}.99)"
			childDevice.removeScheduleDevice("${app.id}", 99)
		}
	}
    
    //add manual schedules
    def manualschs = atomicState.manualMap   
    manualschs?.each{        
    	childDevice.createScheduleDevices("${app.id}", it.key, manualschs[it.key]["scheduleid"], manualschs[it.key]["name"])
		if (infoOn) log.info "${app.label} - Added ${manualschs[it.key]["name"]} Schedule device (${app.id}.${it.key})"
    }
	
	//and then remove any manual schedules that are no longer enabled
	if (!manualschs && (settings.pause == false)) {
		if (infoOn) log.info "${app.label} - Removing all children of ${childDevice}"
		childDevice.removeScheduleDevices()	// remove all the Controller's children
	} else {
		childDevice.getChildDevices()?.each {
			if (it.deviceNetworkId != "${app.id}.99") {		// Pause device should have already been deleted above...
				def index = it.deviceNetworkId.toString().tokenize('.').last()
				if (!manualschs || !manualschs[index]) {
					if (infoOn) log.info "${app.label} - Removing unused Schedule ${it.label} / ${it.name} (${it.deviceNetworkId})"
					childDevice.removeScheduleDevice("${app.id}", index)
				}
			}
		}
	}
}

//add zone settings to child tile
def child_zones(dni){
	def childDevice = getChildDevice(dni)	// childDevices.find{it.deviceNetworkId == dni}
    if (debugOn) log.debug "${childDevice.deviceNetworkId}"
        
    def tempZoneMap = atomicState.zoneMap    
    tempZoneMap.each{    	
        if("${app.id}.${it.key}" == childDevice.deviceNetworkId){
        	if (debugOn) log.debug it.key
            childDevice.childSettings(it.key, tempZoneMap[it.key])
        }
    }    
}


//add devices to spruce webapp
def addDevices(){	
    //add sensors to web
    def key = atomicState.authToken
    def tempSensorMap = atomicState.sensors
    tempSensorMap.each{
    	//if (debugOn) log.debug "Add Sensor to DB ${it.key}"
    	def POSTparams = [
            uri: "https://api.spruceirrigation.com/v2/sensor",
            headers: [ 	"Authorization": "Bearer ${key}"],
            body: [
                device_id: it.value,
                sensor_name: it.key,                
                gateway: "smartthings" //gateway must be labeled smartthings until a later date
                ],
			timeout: 30
        ]
        //sendPost(POSTparams)
        try{
            httpPost(POSTparams) { resp -> //resp.data {
                if (infoOn) {
                	log.info "${app.label} - Add Sensor ${it.key} to DB: ${resp?.data?.message}"
				}
            }                
        } 
        catch (e) {
			log.warn "${app.label} - Add Sensor ${it.key} to DB error: $e"
        }
    }    

}

//***************** setup commands ******************************

//get controller list
def getControllerList(){
	def key = atomicState.authToken
    def tempMap = [:]
    def newuri =  "https://api.spruceirrigation.com/v2/controllers"    
    if (debugOn) log.debug newuri
    
    def GETparams = [
        uri: newuri,		
        headers: [ 	"Authorization": "Bearer ${key}"],
		timeout: 30
    ]

    try{ httpGet(GETparams) { resp ->	
    	if (debugOn) log.debug "resp-> ${resp.data}"        
        resp.data.each{        	
            tempMap += ["${resp.data[it.key]['controller_name']}": it.key]
        }        
      }
    }
    catch (e) {
        respMessage += "No schedules set, API error: $e"        
    }
    atomicState.switches = tempMap
    
    return true
}

//check for pre-set schedules
def getControllerSettings(){
	if (debugOn) log.debug "-----------settings----------------"
    def respMessage = ""
    def key = atomicState.authToken

	def controller_id
   	def tempSwitch = atomicState.switches    
    
    tempSwitch.each{
        if (debugOn) log.debug "key ${it.key} ${it.value}"
        if (it.key == settings.controller) controller_id = it.value
    }
   
    def newuri =  "https://api.spruceirrigation.com/v2/controller_settings?controller_id="
	newuri += controller_id
    if (debugOn) log.debug newuri
        
    def scheduleType
    def scheduleID = []
    def sensorMap = []
    def zoneID
    def tempSchMap = [:]    
    def tempManMap = [:]    
    def tempZoneMap = [:]
    def tempSensorMap = atomicState.sensors

    def GETparams = [
        uri: newuri,		
        headers: [ 	"Authorization": "Bearer ${key}"],
		timeout: 30
    ]

    try{ httpGet(GETparams) { resp ->	
        //get schedule list
        if (debugOn) log.debug "Get setting for ${resp.data.controller_name}"
        def i = 1
        def j = 1
        
        //def schedules = resp.data.schedules
        resp.data.schedules.each{
        	def schPath = resp.data.schedules[it.key]
        	if(schPath['schedule_enabled'] == "1"){
                if(schPath['schedule_type'] == "manual"){            	
                    tempManMap[i] = ['scheduleid' : it.key, 'name' : schPath['schedule_name']]
                    i++
                }
                else {            	
                    tempSchMap[j] = ['scheduleid' : it.key, 'name' : schPath['schedule_name']]
                    j++
                }
            }
        }
        
        resp.data.zone.each{
        	if(resp.data.zone[it.key]['zenabled'] == '1'){
            	
                def zoneData = resp.data.zone[it.key]
            	def ks = "${it.key}"
            	if (ks.toInteger()<10) ks = "0${it.key}"
                def zoneName = "Zone ${ks}"
                if ("${zoneData['zone_name']}" != 'null') zoneName = zoneData['zone_name']
                
                tempZoneMap["${ks}"] = [ 'zone_name': zoneName, 'landscape_type': zoneData['landscape_type'], 'nozzle_type': zoneData['nozzle_type'], 'soil_type': zoneData['soil_type'], 'gpm': zoneData['gpm'] ]
                
                //add sensor assignment
                if (zoneData['sensor']){
                	tempZoneMap["${ks}"]['sensor'] = zoneData['sensor']
                	tempSensorMap.each{
                        if (it.value == zoneData['sensor']) tempZoneMap["${ks}"]['sensor_name'] = it.key
                    }
                }
                
           	}
        }      
        
    }
    
    
    }
    catch (e) {
        respMessage += "No schedules set, API error: $e"
        if (refreshAuthToken()) getControllerSettings()
        else return false
    }
    
    if (debugOn) log.debug tempManMap
    
    if(atomicState.manualMap != null){
        if ("${tempManMap.sort()}" != "${atomicState.manualMap.sort()}") atomicState.manualUpdate = true
        else atomicState.manualUpdate = false

        //do we update zone child devices
        atomicState.zoneUpdate = false
        def tempMap = atomicState.zoneMap
        def names = ""
        def newnames = ""
        tempZoneMap.sort().each{
            newnames += tempZoneMap[it.key]['zone_name']
        }
        tempMap.sort().each{    
            names += tempMap[it.key]['zone_name']
        }    
        if(names != newnames) atomicState.zoneUpdate = true
    }
    else {
    	atomicState.zoneUpdate = true
        atomicState.manualUpdate = true
	}
        
    atomicState.scheduleMap = tempSchMap
    atomicState.manualMap = tempManMap    
    atomicState.zoneMap = tempZoneMap    
    
    return true
    
}


//***************** event handlers *******************************

def parse(description) {
	if (debugOn) log.debug(description)
}

def getScheduleName(scheduleid){	
    def scheduleName = scheduleid
    
    def tempSchedules = atomicState.manualMap
    tempSchedules.each{
    	if(tempSchedules[it.key]['scheduleid'] == scheduleid) scheduleName = "${tempSchedules[it.key]['name']}"
    }
    
    tempSchedules = atomicState.scheduleMap
    tempSchedules.each{
    	if(tempSchedules[it.key]['scheduleid'] == scheduleid) scheduleName = "${tempSchedules[it.key]['name']}"
    }
        
	return scheduleName
}
def sensorUpdater() {
	if (settings.sensors) {
		if (infoOn) log.info "${app.label} - Updating DB states for ${settings.sensors.size()} sensors"
		if (!atomicState.sensors) {
			def tempSensors = [:]
			settings.sensors.each{
				tempSensors[it] = (it.device.zigbeeId)
			}
			atomicState.sensors = tempSensors
		}
		def uri = "https://api.spruceirrigation.com/v2/"
		settings.sensors.each{
			def moisture = it.currentValue('humidity', true)
			def temperature = it.currentValue('temperature', true)
			if ( temperature && (temperatureScale == 'C')) temperature = temperature * 9/5 + 32
			def battery = it.currentValue('battery', true)
			def batteryRaw = battery?.toInteger() * 5 + 2500
			
			if (moisture) {
				if (infoOn) log.info "${app.label} - ${it.displayName}, moisture: ${moisture}"
				def POSTparams = [
					uri: uri + "moisture",
					body: [
						deviceid: it.device.zigbeeId,
						value: moisture                        
					],
					timeout: 30
				]
				sendPost(POSTparams)
			}
			if (temperature) {
				if (infoOn) log.info "${app.label} - ${it.displayName}, temperature: ${temperature}"
				def POSTparams = [
					uri: uri + "temperature",
					body: [
						deviceid: it.device.zigbeeId,
						value: temperature                        
					],
					timeout: 30
				]
				sendPost(POSTparams)
			}
			if (battery) {
				if (infoOn) log.info "${app.label} - ${it.displayName}, battery: ${battery} (${batteryRaw})"
				def POSTparams = [
					uri: uri + "battery",
					body: [
						deviceid: it.device.zigbeeId,
						value: batteryRaw                        
					],
					timeout: 30
				]
				sendPost(POSTparams)
			}
		}
		if (infoOn) log.info "${app.label} - Sensor updates finished"
	} else {
		if (infoOn) log.info "${app.label} - - No sensors configured"
	}
}
//sensor evts
def sensorHandler(evt) {
    if (infoOn) log.info "${app.label} - ${evt.device.displayName}, ${evt.name}: ${evt.value}"
    
    String device = atomicState.sensors["${evt.device}"] as String
	if (!device && settings.sensors) {
		// Oops - looks like we had a name change - rebuild the name/id Map
		def tempSensors = [:]
    	settings.sensors.each{
    		tempSensors[it]= (it.device.zigbeeId)
        }
    	atomicState.sensors = tempSensors
		device = tempSensors["${evt.device}"] as String
	}
	if (device) {
		def value = evt.value
		def uri = "https://api.spruceirrigation.com/v2/"

		if (evt.name == "humidity") {
			uri += "moisture"
		} else if (evt.name == "temperature"){
			uri += "temperature"
			if (evt.unit == "C") value = evt.value * 9/5 + 32 // was evt.value.toInteger() - no need to truncate the value like that
		} else if (evt.name == "battery"){
			//added for battery
			uri += "battery"
			value = evt.value.toInteger() * 5 + 2500
		}

		def POSTparams = [
			uri: uri,
			body: [
				deviceid: device,
				value: value                        
			],
			timeout: 30
		]

		sendPost(POSTparams)
	} else {
		if (debugOn) log.debug "No deviceId found for ${evt.device}."
	}
}

//contact evts
def contactHandler(evt) {
    log.info "${app.label} - contactHandler: ${evt.device}, ${evt.name}, ${evt.value}"
    
    def device = atomicState.contacts["${evt.device}"]    
    def value = evt.value
        
	def childDevice = getChildDevice("${app.id}.0")	// childDevices.find{it.deviceNetworkId == "${app.id}.0"}
    
    if (debugOn) log.debug "Found ${childDevice}"
    if (childDevice != null){    	
        def result = [name: evt.name, value: value, descriptionText: evt.name, isStateChange: true, displayed: false]
        if (debugOn) log.debug result
        childDevice.generateEvent(result)
    }
    
    int delay_secs = 0
    if (settings.delay) delay_secs = settings.delay * 60
    
    if (value == 'open') send_pause(0)
    else runIn(delay_secs, send_resume)
}




//**************************** incoming commands **************************************

//*************** master child ***************

//refresh settings
void getChildren(){
	if (debugOn) log.debug "Update Settings"
    
	if (getControllerSettings()){
		def children = getChildDevices()
        children.each { child ->            
            child_zones(child.deviceNetworkId)
        }
    }
        
	def childDevice = getChildDevice("${app.id}.0")	// childDevices.find{it.deviceNetworkId == "${app.id}.0"}
    childDevice.updateChildren()
}

//big tile events
def event(){
	if (debugOn) log.debug "cloud event: ${params.command}"
    def command = params.command
	def event = command.split(',')
    
	def masterDevice = getChildDevice("${app.id}.0")	// childDevices.find{it.deviceNetworkId == "${app.id}.0"}
    if (masterDevice != null){  	
        def scheduleName = getScheduleName(event[2])
        def result = [name: 'status', value: "${event[0]}", descriptionText: "${scheduleName} starting\n${event[1]}", isStateChange: true, displayed: true]
        if (debugOn) log.debug result        
        masterDevice.generateEvent(result)
        
        def tempManualMap = atomicState.manualMap        
        tempManualMap.each{
            if ("${tempManualMap[it.key]['scheduleid']}" == "${event[2]}") masterDevice.zoneon("${app.id}.${it.key}")
        }
    }
    return [error: false, return_value: 1]
}



//rain sensor onoff
def rain(){
	if (debugOn) log.debug(params.command)
    // use the built-in request object to get the command parameter
    def command = params.command
    if (debugOn) log.debug "Spruce incoming rain=>>  ${command}"
    
    def zoneonoff = command.split(',')
            
    def name = 'rainsensor'
    def value = (zoneonoff[1].toInteger() == 1 ? 'on' : 'off')
    def message = "rain sensor is ${value}"
            
    def masterDevice = getChildDevice("${app.id}.0")	// childDevices.find{it.deviceNetworkId == "${app.id}.0"}
    
    def result = [name: name, value: value, descriptionText: "${masterDevice} ${message}", isStateChange: true, displayed: true]
    if (debugOn) log.debug result
    masterDevice.generateEvent(result)
    
    return [error: false, return_value: 1]
}

//pause onoff
//changed to hold, hubitat throws error when pause command executed
def hold(){
	if (debugOn) log.debug(params.command)
    // use the built-in request object to get the command parameter
    def command = params.command
    if (debugOn) log.debug "Spruce incoming pause=>>  ${command}"
    
    def zoneonoff = command.split(',')
            
    def name = 'pause'
    def value = (zoneonoff[1].toInteger() == 1 ? 'on' : 'off')
    def message = "pause is ${value}"
            
    def masterDevice = getChildDevice("${app.id}.0")	// childDevices.find{it.deviceNetworkId == "${app.id}.0"}
    
    def result = [name: name, value: value, descriptionText: "${masterDevice} ${message}", isStateChange: true, displayed: true]
    if (debugOn) log.debug result
    masterDevice.generateEvent(result)
    
    return [error: false, return_value: 1]
}

//*********** child zone devices *************

//turn on/off zones of child devices
def zone_state(){
	if (debugOn) log.debug "cloud zone_state(): ${params.command}"
    // use the built-in request object to get the command parameter
    def command = params.command
    //if (debugOn) log.debug "Spruce incoming zone=>>  ${command}"
    
    //check sz to filter out command
	// zoff,8,0,ok,ok
    def zoneonoff = command.split(',')
    def sz = zoneonoff.size()
    
    String ks
    def scheduleid
    def scheduleName
    def tempSchMap = atomicState.scheduleMap
    
	if (zoneonoff[1].toInteger() == 0) ks = "0"
    else if (zoneonoff[1].toInteger()<10) ks = "0${zoneonoff[1]}"
   	else ks = zoneonoff[1].toString()

	def childDevice = (ks != "0") ? getChildDevice("${app.id}.${ks}")	: null // "0" is masterDevice - don't send zone commands to it
    def masterDevice = getChildDevice("${app.id}.0") 
    String name = 'switch'
    String value
    String message
    def gpm
    def amp    
        
    int zone_on = atomicState.zones_on
	if (debugOn) log.debug "old zone_on count: ${zone_on}"
	//int on_count = 0
	//childDevices?.each() {
	//	if (it.typeName == "Spruce wifi zone") {
	//		if (it.currentValue('switch', true) == 'on') on_count++;
	//	}
	//}
	//if (on_count != zone_on) {
	//	log.warn "Zones on mismatch (before)! state: ${zone_on}, count: ${on_count}"
	//	zone_on = on_count
		//atomicState.zones_on = on_count
	//}
    switch(zoneonoff[0]) {
        case "zon":            
            value = 'on'
            message = "on"
            if (ks != "0") zone_on++
            if (zoneonoff[2].toInteger() != 0) message += " for ${Math.round(zoneonoff[2].toInteger()/60)} mins"            
            break
        case "zoff":
            value = 'off'
            message = "off"
            if (ks != "0") zone_on--
            break        
    }
    
    if (zone_on < 0) zone_on = 0
    if (ks == "0" && value == 'off') zone_on = 0
    if (debugOn) log.debug "new zone_on count: ${zone_on}"
    /*
    if (atomicState.zones_on == 0 && zone_on == 1) masterDevice.generateEvent([name: 'switch', value: "on", descriptionText: "${settings.controller} ${message}", isStateChange: true, displayed: true])
    else if (atomicState.zones_on >= 1 && zone_on == 0) masterDevice.generateEvent([name: 'switch', value: "off", descriptionText: "${settings.controller} ${message}", isStateChange: true, displayed: true])    
    */
    
    atomicState.zones_on = zone_on
	
	if (childDevice) {
		if (zoneonoff[0] == "zoff" && sz >= 5){
			gpm = [name: 'gpm', value: "${zoneonoff[3]}", descriptionText: "${childDevice} gpm flow ${zoneonoff[3]}", isStateChange: true, displayed: true]
			amp = [name: 'amp', value: "${zoneonoff[4]}", descriptionText: "${childDevice} valve check ${zoneonoff[4]}", isStateChange: true, displayed: true]
			childDevice.generateEvent(gpm)
			childDevice.generateEvent(amp)

			if ("${zoneonoff[3]}" != 'ok') note('Valve Fault', "${childDevice} gpm flow ${zoneonoff[3]}")
			if ("${zoneonoff[4]}" != 'ok') note('Valve Fault', "${childDevice} valve check ${zoneonoff[4]}")
		}

    	def zoneResult = [name: name, value: value, descriptionText: "${childDevice} ${message}", isStateChange: true, displayed: true]
    
		if (debugOn) log.debug "Updating child: ${childDevice} ${zoneResult}"
    	childDevice.generateEvent(zoneResult)
		
		//on_count = 0
		//if (zones_on == 0) {
		//	childDevices?.each() {
		//		if (it.typeName == "Spruce wifi zone") {
		//			if (it.currentValue('switch', true) == 'on') on_count++;
		//		}
		//	}
		//}
		//if (on_count != zone_on) {
		//	log.warn "Zones on mismatch (after)! state: ${zone_on}, count: ${on_count}"
			//zone_on = on_count
			//atomicState.zones_on = on_count
		//}
	}
    
	def masterResult = [name: name, value: value, descriptionText: "${childDevice?:masterDevice} ${message}", isStateChange: true, displayed: true]
    //master switch
    if (ks == "0"){    	
        masterResult = [name: 'switch', value: value, descriptionText: "${childDevice?:masterDevice} ${message}", isStateChange: true, displayed: true]
        masterDevice.generateEvent(masterResult)        
    }    
    //multiple zones on
    else if (ks != "0" && zone_on >= 1 && value == 'off'){
		if (debugOn) log.debug "Multiple zones are on: ${zone_on}"
    	masterResult = [name: 'zonehold', value: value, descriptionText: "${childDevice?:masterDevice} ${message}", isStateChange: true, displayed: true]
    	masterDevice.generateEvent(masterResult)
    }
    //zone
    else if (ks != "0"){
    	masterResult = [name: 'zone', value: value, descriptionText: "${childDevice?:masterDevice} ${message}", isStateChange: true, displayed: true]
    	masterDevice.generateEvent(masterResult)
    }
	if (debugOn) log.debug "Updating master (${ks}): ${masterDevice} ${masterResult}"
    if (sz >= 5) note('Zone',"${childDevice?:masterDevice} ${message}")
    return [error: false, return_value: 1]    
}

//*************************** outgoing commands ***************************

//turn on/off zones to cloud
void zoneOnOff(dni, onoff, level) {
    if (debugOn) log.debug "Cloud zoneOnOff ${dni} ${onoff} ${level}"
   	
    String zone_dni = "${dni}"
    def zone = zone_dni[-2..-1]
    
    if (debugOn) log.debug zone

    def POSTparams = [
        uri: 'https://api.spruceirrigation.com/v2/zone',
        body: [
            zone: zone,
            zonestate: onoff,
            zonetime: level*60
        ],
		timeout: 30
    ]

    sendPost(POSTparams)
    
}

void scheduleOnOff(name, onoff){
	def OnOff = 'stopped'
    if (onoff.toInteger() == 1) OnOff = 'started'
    def message = "Schedule ${name} ${OnOff}"
    if (debugOn) log.debug "scheduleOnOff ${name} ${OnOff}"
    
    note('Schedule',message)
    
    def tempManualMap = atomicState.manualMap
    def scheduleID
    tempManualMap.each{
    	if ("${tempManualMap[it.key]['name']}" == "${name}") scheduleID = "${tempManualMap[it.key]['scheduleid']}"
	}
    
    def POSTparams = [
                    uri: 'https://api.spruceirrigation.com/v2/schedule',
                    body: [
                        scheduleID: scheduleID,
                        onoff: onoff
                    ],
					timeout: 30
                ]

    sendPost(POSTparams)

}

void runAll(runtime){
	def POSTparams = [
                    uri: 'https://api.spruceirrigation.com/v2/runall',
                    body: [
                        zonetime: runtime
                    ],
					timeout: 30
                ]

    sendPost(POSTparams)
}

void send_pause(pausetime = 0){	
	def POSTparams = [
                    uri: 'https://api.spruceirrigation.com/v2/pause',                    
                    body: [
                        pausetime: pausetime*60
                    ],
					timeout: 30
                ]

    sendPost(POSTparams)
}

void send_resume(){
	def POSTparams = [
                    uri: 'https://api.spruceirrigation.com/v2/resume',
					timeout: 30
                ]

    sendPost(POSTparams)
}

void send_stop(){	
	def POSTparams = [
                    uri: 'https://api.spruceirrigation.com/v2/stop',
					timeout: 30
                ]

    sendPost(POSTparams)
}


//************* notifications to device, pushed if requested ******************
def note(type, message){	
	if (debugOn) log.debug "note ${type} ${message}"
	if(sendPushMessage && settings.notifications && settings.notifications.contains(type)) sendPushMessage.deviceNotification("${message}")
}

//************* post ******************

def sendPost(POSTparams) {
    POSTparams.put( "headers", [ "Authorization": "Bearer ${atomicState.authToken}", "Content-Type": "application/x-www-form-urlencoded"] )
    //if (debugOn) log.debug POSTparams
	try{
        httpPost(POSTparams){
            resp ->
            //if (debugOn) log.debug "Post Response ${resp.data}"
			if ("${resp.data.error}" == 'true') note('error', "${resp.data.message}\n ${POSTparams}")
        }                
    } 
    catch (groovyx.net.http.HttpResponseException error) {
        if (error.statusCode == 401) {    // Unauthorized
            log.info "${app.label} - sendPost: Refreshing authorization token"
            refreshAuthToken()
            retryInitialRequest(POSTparams)
        } else {
            log.warn "send DB error, statusCode: ${error.statusCode}, error: $error"
        }
    }
    catch (error) {
        log.warn "send DB error: $error"
        refreshAuthToken()
        retryInitialRequest(POSTparams)
    }
}

def retryInitialRequest(POSTparams) {
	POSTparams.put( "headers", [ "Authorization": "Bearer ${atomicState.authToken}", "Content-Type": "application/x-www-form-urlencoded"] )
    try{
        httpPost(POSTparams){
            resp ->
            if ("${resp.data.error}" == 'true') note('error', "${resp.data.message}")              
        }                
    } 
    catch (error) {
        log.warn "retry send DB error: $error"        
    }
}

//********************** OAUTH ***************************/

def oauthInitUrl(){
	// Generate a random ID to use as a our state value. This value will be used to verify the response we get back from the third-party service.
    atomicState.oauthInitState = UUID.randomUUID().toString()
    atomicState.accessTokenPut = false
    
	def oauthParams = [
        response_type: "code",
        scope: "basic",
        client_id: getClientId(),
        client_secret: getClientSecret(),
        state: atomicState.oauthInitState,
        redirect_uri: getFullApiServerUrl() + "/oauth/callback?access_token=${atomicState.accessToken}"
    ]
	def apiEndpoint = "https://app.spruceirrigation.com/oauth"
    def oAuthInitURL = "${apiEndpoint}/authorize?${toQueryString(oauthParams)}"
    
    if (debugOn) log.debug "oAuthInitURL ${oAuthInitURL}"
    return "${oAuthInitURL}"
    
}

// The toQueryString implementation simply gathers everything in the passed in map and converts them to a string joined with the "&" character.
String toQueryString(Map m) {
        return m.collect { k, v -> "${k}=${URLEncoder.encode(v.toString())}" }.sort().join("&")
}

def callback() {
    if (debugOn) log.debug "callback()>> params: $params, params.code ${params.code}"
    
    def code = params.code
    def oauthState = params.state
    if (debugOn) log.debug "callback code ${code} state ${oauthState}"
    // Validate the response from the third party by making sure oauthState == state.oauthInitState as expected
    if (oauthState == atomicState.oauthInitState){
        def tokenParams = [
            uri: "https://app.spruceirrigation.com/oauth/token",        
            body: [
                    grant_type: "authorization_code",
                    code : "${code}",
                    scope : "basic",
                    client_id : getClientId(),
                    client_secret: getClientSecret(),
                    redirect_uri: getFullApiServerUrl() + "/oauth/callback?access_token=${atomicState.accessToken}"
                ],
			timeout: 30
        ]
        
        httpPost(tokenParams) { resp ->
        	atomicState.refreshToken = resp.data.refresh_token
            atomicState.authToken = resp.data.access_token           
        }
        if (debugOn) log.debug "tokens refresh ${atomicState.refreshToken} auth ${atomicState.authToken} put? ${atomicState.accessTokenPut}"
        
        //send access_token to spruce        
        if (atomicState.authToken && !atomicState.accessTokenPut) {
        	
            def accessToken_url = "https://api.spruceirrigation.com/hubitat/accesstoken"
            if (debugOn) log.debug accessToken_url
            
            def accessParams = [
            	uri: accessToken_url,
                headers: [ 	"Authorization": "Bearer ${atomicState.authToken}"],
                body: [
                	hubitat_endpoint: getFullApiServerUrl(),
                    hubitat_token: atomicState.accessToken
                ],
				timeout: 30
            ]
            
            try{
                httpPost(accessParams) { resp ->
            	    if (debugOn) log.debug resp.data
                    if (resp.data.error == false){
                        if (debugOn) log.debug "success"
                        atomicState.accessTokenPut = true
                        success()                        
                    }
                    else fail()
                }
            } catch(Exception e){
                log.error e
                fail()
            }
            
        } 
        else fail()
    } 
    else log.error "callback() failed. Validation of state did not match. oauthState != atomicState.oauthInitState"
    success()
}

private refreshAuthToken() {
    def refreshParams = [        
        uri: "https://app.spruceirrigation.com/oauth/token",
        body: [grant_type: "refresh_token", refresh_token: atomicState.refreshToken, client_id: getClientId(), client_secret: getClientSecret()],
		timeout: 	30
    ]
    try{
        def jsonMap
        if (debugOn) log.debug refreshParams
        httpPost(refreshParams) { resp ->
            if (debugOn) log.debug resp.data
            if(resp.status == 200)
            {
                jsonMap = resp.data
                if (resp.data) {
                    atomicState.refreshToken = resp.data.refresh_token
                    atomicState.authToken = resp.data.access_token
            	}
                
        	}
    	}
    }
    catch (error) {
        log.warn "token refresh error: $error"
        return false
    }
    return true
}

// Example success method
def success() {
        def message = """
                <h1>Your account is now connected to Hubitat</h1>
                <h2>Close this page and install the application again.<br>You will not be prompted for credentials next time.</h2>
        """
        displayMessageAsHtml(message)
}

// Example fail method
def fail() {
    def message = """
        <h1>There was an error connecting your account with Hubitat</h1>
        <h2>Please try again.</h2>
    """
    displayMessageAsHtml(message)
}

def displayMessageAsHtml(message) {
    def html = """
        <!DOCTYPE html>
        <html>
            <head>
            </head>
            <body bgcolor="#bdf1fd">            	
                <div align='center'>                
                    ${message}
                </div>
            </body>
        </html>
    """
    
    render contentType: 'text/html', data: html
}
