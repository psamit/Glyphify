package com.frank.glyphify.glyph.notificationmanager

import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.frank.glyphify.Constants.CHANNEL_ID
import com.frank.glyphify.Constants.GLYPH_DEFAULT_INTENSITY
import com.frank.glyphify.Constants.GLYPH_MAX_INTENSITY
import com.frank.glyphify.Constants.GLYPH_MID_INTENSITY
import com.frank.glyphify.R
import com.frank.glyphify.Util.fromStringToNum
import com.frank.glyphify.Util.loadPreferences
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphException
import com.nothing.ketchum.GlyphFrame
import com.nothing.ketchum.GlyphManager
import kotlinx.coroutines.*
import java.math.BigInteger

class ExtendedEssentialService: NotificationListenerService() {
    private var mGM: GlyphManager? = null
    private var mCallback: GlyphManager.Callback? = null

    private var numZones: Int = 0
    private var intensity: Int = GLYPH_MID_INTENSITY

    private lateinit var glyphsMapping: MutableList<Triple<Int, List<BigInteger>, Boolean>>
    private var activeNotifications = mutableMapOf<Int, MutableList<String>>()
    private var activeZonesStatic = mutableListOf<Int>()
    private var activeZonesPulse = mutableListOf<Int>()

    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var serviceJob: Job? = null
    private lateinit var wakeLock: PowerManager.WakeLock

    private lateinit var extendedEssentialReceiver: ExtendedEssentialReceiver

    private fun init() {
        mCallback = object : GlyphManager.Callback {
            override fun onServiceConnected(componentName: ComponentName) {
                if (Common.is20111()) mGM?.register(Common.DEVICE_20111)
                if (Common.is22111()) mGM?.register(Common.DEVICE_22111)
                if (Common.is23111()) mGM?.register(Common.DEVICE_23111)

                try {
                    mGM?.openSession()
                    mGM?.turnOff()
                }
                catch (e: GlyphException) {
                    e.printStackTrace()
                }
            }

            override fun onServiceDisconnected(componentName: ComponentName) {
                mGM?.turnOff()
                mGM?.closeSession()
            }
        }
    }

    /**
     * Used to pass from a simplified glyph addressing to the Nothing's glyph addressing
     * @param index: simplified index for glyph zones
     * @return list of ints containing the actual glyph zones
     * */
    private fun getGlyphMapping(index: Int): List<Int> {
        var glyphIndexes: List<Int>
        when(index) {
            2 -> {
                if(Common.is20111()) {
                    glyphIndexes = (Glyph.Code_20111.C1..Glyph.Code_20111.C4).toList()
                }
                else if(Common.is23111()) {
                    glyphIndexes = (Glyph.Code_23111.C_1..Glyph.Code_23111.C_24).toList()
                }
                else glyphIndexes = listOf(index)
            }
            3 -> {
                if(Common.is20111()) {
                    glyphIndexes = (Glyph.Code_20111.D1_1..Glyph.Code_20111.D1_8).toList()
                }
                else if(Common.is22111()) {
                    glyphIndexes = (Glyph.Code_22111.C1_1..Glyph.Code_22111.C1_16).toList()
                }
                else glyphIndexes = listOf(index)
            }
            9 -> {
                glyphIndexes = (Glyph.Code_22111.D1_1..Glyph.Code_22111.D1_8).toList()
            }
            else -> glyphIndexes = listOf(index)
        }

        return glyphIndexes
    }

    /**
     * Get contact name given an Uri
     * @param contactUri
     * @return contact's name if exists, null otherwise
     * */
    private fun getContactName(contactUri: Uri): String? {
        val projection = arrayOf(ContactsContract.Contacts.DISPLAY_NAME)
        val cursor: Cursor? = contentResolver.query(contactUri, projection, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
            }
        }
        return null
    }

    /**
     * Analyzes a notification to understand if it's a message, what action has been performed on it
     * and the sender's name
     * @param sbn: StatusBarNotification object
     * @return null if the notification is not a supported message, Pair<Int, String?> where Int
     * is a code to distinguish what the user did with the notification and String contains the sender's
     * name
     * */
    private fun isMessage(sbn: StatusBarNotification): Pair<Int, String?>? {
        val packageName = sbn.packageName

        // check if the notification is from a messaging app
        if(packageName !in listOf(
                "com.google.android.apps.messaging", "com.whatsapp", "org.telegram.messenger")) {
            return null
        }

        val senderName = sbn.notification.extras.getString(Notification.EXTRA_TITLE)
        if (senderName == null || senderName in listOf("WhatsApp", "Telegram")) return null

        val peopleList = sbn.notification.extras.getParcelableArrayList("android.people.list", Person::class.java)
        var contactName: String? = null
        peopleList?.forEach { person ->
            if(person.uri != null) {
                contactName = getContactName(Uri.parse(person.uri))
            }
        }

        if(contactName == null) return Pair(0, senderName) // notification was removed
        else if (contactName != senderName) return Pair(1, contactName) // replied
        else return Pair(2, senderName) // notification received
    }

    private fun extractGlyphMappingFromContractName(contactName: String): Triple<Int, BigInteger, Boolean>? {
        val cursor = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            ContactsContract.Contacts.DISPLAY_NAME + " = ?",
            arrayOf(contactName),
            null
        )

        var mappingTriple: Triple<Int, BigInteger, Boolean>? = null

        if (cursor != null) {
            val contactIdIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)

            while (cursor.moveToNext() && mappingTriple == null) {
                val contactId = BigInteger(cursor.getString(contactIdIndex))
                val foundTriple = (glyphsMapping as List<Triple<Int, List<BigInteger>, Boolean>>)
                    .find { it.second.contains(contactId) }
                if (foundTriple != null) mappingTriple = Triple(foundTriple.first, contactId, foundTriple.third)
            }

            cursor.close()
        }

        return mappingTriple
    }

    private fun extractGlyphMappingFromPackageName(packageName: String): Triple<Int, BigInteger, Boolean>? {
        val appId = fromStringToNum(packageName)
        val foundTriple = (glyphsMapping as List<Triple<Int, List<BigInteger>, Boolean>>)
            .find { it.second.contains(appId) }

        return if (foundTriple != null) Triple(foundTriple.first, appId, foundTriple.third) else null
    }

    private fun showGlyphNotifications() {
        var frameStatic: GlyphFrame.Builder
        var framePulse: GlyphFrame.Builder

        if(mGM == null) return

        frameStatic = mGM!!.glyphFrameBuilder
        for(zone in activeZonesStatic) {
            frameStatic = frameStatic.buildChannel(zone, intensity)
        }

        // launch kotlin coroutine to play pulsing animation
        if (activeZonesPulse.isNotEmpty() && serviceJob?.isActive != true) {
            serviceJob = serviceScope.launch {
                try {
                    val wakeLockTime = 2 * 60 * 1000
                    val delayBetweenAnimations = 4000L
                    var perStepDelay = 25L
                    val stepSize = 100 * intensity/GLYPH_MAX_INTENSITY

                    // acquire wakelock to prevent phone from sleeping when animation is displayed
                    if(!wakeLock.isHeld) wakeLock.acquire(wakeLockTime.toLong())

                    // display animation a bunch of times
                    for(i in 1 until (wakeLockTime / (intensity * perStepDelay / stepSize + delayBetweenAnimations))) {

                        for (light in intensity downTo 0 step stepSize) {

                            framePulse = frameStatic
                            activeZonesPulse.forEach { zone ->
                                framePulse = framePulse.buildChannel(zone, light)
                            }

                            mGM!!.toggle(framePulse.build())
                            delay(perStepDelay)
                        }

                        delay(delayBetweenAnimations)
                    }

                    // then switch to static glyph, this avoid acquiring wakelock for too long
                    framePulse = frameStatic
                    activeZonesPulse.forEach { zone ->
                        framePulse = framePulse.buildChannel(zone)
                    }
                    mGM!!.toggle(framePulse.build())
                }
                finally {
                    if(wakeLock.isHeld) wakeLock.release()
                }
            }
        }
        else {
            // if coroutine is executing and no pulsing notifications are present, stop the coroutine
            if(activeZonesPulse.isEmpty() && serviceJob?.isActive == true) {
                serviceJob?.cancel()
                if(wakeLock.isHeld) wakeLock.release()
            }

            // no pulsing glyph, turn on glyph for static effect if any
            if(activeZonesStatic.isNotEmpty()) {
                mGM!!.toggle(frameStatic.build())
            }
            else {
                mGM!!.turnOff()
            }
        }
    }

    private fun removeNotification(contactMapping: Triple<Int, String, Boolean>) {
        // turn off glyph only if there are no notifications from any of the mapped contacts
        activeNotifications[contactMapping.first]?.remove(contactMapping.second)
        if(activeNotifications[contactMapping.first]?.isEmpty() == true) {

            val newZones = getGlyphMapping(contactMapping.first)
            var valueRemoved = false

            if (activeZonesPulse.removeAll(newZones)) {
                valueRemoved = true
            }
            if (activeZonesStatic.removeAll(newZones)) {
                valueRemoved = true
            }

            // avoid useless calls if nothing changed
            if (valueRemoved && extendedEssentialReceiver.isPhoneLocked()) showGlyphNotifications()
        }
    }

    private fun updateActiveZones(index: Int, eeMode: Boolean) {
        val newZones = getGlyphMapping(index)
        var modified = false

        if(eeMode) {
            newZones.filter { it !in activeZonesPulse }.forEach {
                activeZonesPulse.add(it)
                modified = true
            }
        }
        else {
            newZones.filter { it !in activeZonesStatic }.forEach {
                activeZonesStatic.add(it)
                modified = true
            }
        }

        // avoid useless calls if nothing changed
        if(modified && extendedEssentialReceiver.isPhoneLocked()) {
            showGlyphNotifications()
        }
    }

    private fun testAndAdd2ActiveNotifications(glyphMapping: Triple<Int, String, Boolean>) {
        if(!activeNotifications.containsKey(glyphMapping.first)
            || activeNotifications[glyphMapping.first]?.contains(glyphMapping.second) == false) {

            activeNotifications.getOrPut(glyphMapping.first) { mutableListOf() }.add(glyphMapping.second)
            updateActiveZones(glyphMapping.first, glyphMapping.third)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // delay initialization of GlyphManager by 3 seconds
        // for some reason GlyphManager doesn't work if initialized at device boot
        Handler(Looper.getMainLooper()).postDelayed({
            init()
            val localGM = GlyphManager.getInstance(applicationContext)
            localGM?.init(mCallback)
            mGM = localGM
        }, 3 * 1000)

        // fetch number of zones and load current contacts glyph mapping
        if(Common.is20111()) numZones = 5
        else if (Common.is22111()) numZones = 11
        else if (Common.is23111()) numZones = 3
        glyphsMapping = loadPreferences(this, numZones)
        activeNotifications = mutableMapOf()

        // register receiver to detect if the phone is locked
        extendedEssentialReceiver = ExtendedEssentialReceiver()
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_USER_PRESENT)
        registerReceiver(extendedEssentialReceiver, filter)

        // wake lock to display pulsing animation when phone is locked
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Glyhpify::NotificationListener"
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // get glyph mapping for app if it exists and update active zones
        var glyphAppMapping = extractGlyphMappingFromPackageName(sbn.packageName)
        var appMapping: Triple<Int, String, Boolean>? = null

        // package name plus notification key to keep track of all notification from an app
        if(glyphAppMapping != null) {
            appMapping = Triple(glyphAppMapping.first, sbn.key, glyphAppMapping.third)
            testAndAdd2ActiveNotifications(appMapping)
        }

        // if the notification is from a conversation also get contact mapping and update zones
        val message = isMessage(sbn) ?: return

        if(message.second != null) {

            // given contact name, get corresponding contact mapping (index, contactId, pulseBoolean)
            var glyphContactMapping = extractGlyphMappingFromContractName(message.second as String) ?: return
            val contactMapping = Triple(glyphContactMapping.first, sbn.key, glyphContactMapping.third)

            if(message.first == 2) {    // new notification, try to add contact to active contacts
                testAndAdd2ActiveNotifications(contactMapping)
            }
            else if(message.first == 1) {
                // the title of the notification and contactName are different, this is a reply to
                // a notification and thus should be removed

                if(appMapping != null) removeNotification(appMapping)
                removeNotification(contactMapping)
            }

        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if(sbn != null) {
            val appMapping = extractGlyphMappingFromPackageName(sbn.packageName)
            if(appMapping != null) {
                removeNotification(Triple(appMapping.first, sbn.key, appMapping.third))
            }

            val message = isMessage(sbn) ?: return
            val contactMapping = extractGlyphMappingFromContractName(message.second as String) ?: return
            removeNotification(Triple(contactMapping.first, sbn.key, contactMapping.third))
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()

        // start foreground service, needed for pulse animation to work reliably
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(this.getString(R.string.title_foreground_notification))
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_sunlight)
            .build()

        startForeground(2, notification)
    }

    override fun onDestroy() {
        serviceJob?.cancel()
        serviceScope.cancel()

        try {
            mGM?.turnOff()
            mGM?.closeSession()
        }
        catch (e: GlyphException) {
            e.printStackTrace()
        }
        mGM?.unInit()

        unregisterReceiver(extendedEssentialReceiver)

        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent != null) {
            val action = intent.action
            if(action == "PHONE_LOCKED") {
                showGlyphNotifications()
            }
            else if(action == "PHONE_UNLOCKED") {
                serviceJob?.cancel()
                mGM?.turnOff()
            }
            else if(action == "UPDATE_MAPPING") {
                glyphsMapping = loadPreferences(this, numZones)
            }
            else if(action == "UPDATE_INTENSITY") {
                intensity = intent.extras?.getInt("intensity")?: GLYPH_DEFAULT_INTENSITY
            }
            else if(action == "SHOW_GLYPHS"
                && extendedEssentialReceiver.isPhoneLocked()) {
                showGlyphNotifications()
            }
        }

        return START_STICKY
    }
}