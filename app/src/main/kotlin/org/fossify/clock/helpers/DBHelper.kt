package org.fossify.clock.helpers

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.text.TextUtils
import org.fossify.clock.extensions.cancelAlarmClock
import org.fossify.clock.extensions.createNewAlarm
import org.fossify.clock.models.Alarm
import org.fossify.clock.models.AlarmFolder
import org.fossify.commons.extensions.getIntValue
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.getStringValue
import org.fossify.commons.helpers.FRIDAY_BIT
import org.fossify.commons.helpers.MONDAY_BIT
import org.fossify.commons.helpers.SATURDAY_BIT
import org.fossify.commons.helpers.SUNDAY_BIT
import org.fossify.commons.helpers.THURSDAY_BIT
import org.fossify.commons.helpers.TUESDAY_BIT
import org.fossify.commons.helpers.WEDNESDAY_BIT

class DBHelper private constructor(
    val context: Context,
) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val ALARMS_TABLE_NAME = "contacts"  // wrong table name, ignore it
    private val FOLDERS_TABLE_NAME = "folders"
    private val COL_ID = "id"
    private val COL_TIME_IN_MINUTES = "time_in_minutes"
    private val COL_DAYS = "days"
    private val COL_IS_ENABLED = "is_enabled"
    private val COL_VIBRATE = "vibrate"
    private val COL_SOUND_TITLE = "sound_title"
    private val COL_SOUND_URI = "sound_uri"
    private val COL_LABEL = "label"
    private val COL_ONE_SHOT = "one_shot"
    private val COL_FOLDER_ID = "folder_id"
    private val COL_NAME = "name"
    private val COL_CREATED_AT = "created_at"

    private val mDb = writableDatabase

    companion object {
        private const val DB_VERSION = 4
        const val DB_NAME = "alarms.db"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var dbInstance: DBHelper? = null

        fun newInstance(context: Context): DBHelper {
            val appContext = context.applicationContext
            return dbInstance ?: synchronized(this) {
                dbInstance ?: DBHelper(appContext).also { dbInstance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $ALARMS_TABLE_NAME ($COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, $COL_TIME_IN_MINUTES INTEGER, $COL_DAYS INTEGER, " +
                "$COL_IS_ENABLED INTEGER, $COL_VIBRATE INTEGER, $COL_SOUND_TITLE TEXT, $COL_SOUND_URI TEXT, $COL_LABEL TEXT, $COL_ONE_SHOT INTEGER, " +
                "$COL_FOLDER_ID INTEGER DEFAULT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $FOLDERS_TABLE_NAME ($COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, $COL_NAME TEXT NOT NULL, $COL_CREATED_AT INTEGER NOT NULL)"
        )
        insertInitialAlarms(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $ALARMS_TABLE_NAME ADD COLUMN $COL_ONE_SHOT INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            try {
                db.execSQL("ALTER TABLE $ALARMS_TABLE_NAME ADD COLUMN $COL_FOLDER_ID INTEGER DEFAULT NULL")
            } catch (_: Exception) {
                // Column may already exist from previous folder infrastructure
            }
        }
    }

    private fun insertInitialAlarms(db: SQLiteDatabase) {
        val weekDays = MONDAY_BIT or TUESDAY_BIT or WEDNESDAY_BIT or THURSDAY_BIT or FRIDAY_BIT
        val weekDaysAlarm = context.createNewAlarm(420, weekDays)
        insertAlarm(weekDaysAlarm, db)

        val weekEnd = SATURDAY_BIT or SUNDAY_BIT
        val weekEndAlarm = context.createNewAlarm(540, weekEnd)
        insertAlarm(weekEndAlarm, db)
    }

    fun insertAlarm(alarm: Alarm, db: SQLiteDatabase = mDb): Int {
        val values = fillAlarmContentValues(alarm)
        return db.insert(ALARMS_TABLE_NAME, null, values).toInt()
    }

    fun updateAlarm(alarm: Alarm): Boolean {
        val selectionArgs = arrayOf(alarm.id.toString())
        val values = fillAlarmContentValues(alarm)
        val selection = "$COL_ID = ?"
        return mDb.update(ALARMS_TABLE_NAME, values, selection, selectionArgs) == 1
    }

    fun updateAlarmEnabledState(id: Int, isEnabled: Boolean): Boolean {
        val selectionArgs = arrayOf(id.toString())
        val values = ContentValues()
        values.put(COL_IS_ENABLED, isEnabled)
        val selection = "$COL_ID = ?"
        return mDb.update(ALARMS_TABLE_NAME, values, selection, selectionArgs) == 1
    }

    fun deleteAlarms(alarms: ArrayList<Alarm>) {
        alarms.filter { it.isEnabled }.forEach {
            context.cancelAlarmClock(it)
        }

        // Cancel the per-alarm upcoming-alarm notifications that were
        // posted by UpcomingAlarmReceiver. Without this, notifications
        // linger after the alarm is deleted.
        alarms.forEach { alarm ->
            context.notificationManager.cancel(UPCOMING_ALARM_NOTIFICATION_BASE + alarm.id)
        }

        val args = TextUtils.join(", ", alarms.map { it.id.toString() })
        val selection = "$ALARMS_TABLE_NAME.$COL_ID IN ($args)"
        mDb.delete(ALARMS_TABLE_NAME, selection, null)
    }

    fun getAlarmWithId(id: Int) = getAlarms().firstOrNull { it.id == id }

    fun getAlarmsWithUri(uri: String) = getAlarms().filter { it.soundUri == uri }

    private fun fillAlarmContentValues(alarm: Alarm): ContentValues {
        return ContentValues().apply {
            put(COL_TIME_IN_MINUTES, alarm.timeInMinutes)
            put(COL_DAYS, alarm.days)
            put(COL_IS_ENABLED, alarm.isEnabled)
            put(COL_VIBRATE, alarm.vibrate)
            put(COL_SOUND_TITLE, alarm.soundTitle)
            put(COL_SOUND_URI, alarm.soundUri)
            put(COL_LABEL, alarm.label)
            put(COL_ONE_SHOT, alarm.oneShot)
            if (alarm.folderId != null) put(COL_FOLDER_ID, alarm.folderId)
            else putNull(COL_FOLDER_ID)
        }
    }

    fun getEnabledAlarms() = getAlarms().filter { it.isEnabled }

    fun getAlarms(): ArrayList<Alarm> {
        val alarms = ArrayList<Alarm>()
        val cols = arrayOf(
            COL_ID,
            COL_TIME_IN_MINUTES,
            COL_DAYS,
            COL_IS_ENABLED,
            COL_VIBRATE,
            COL_SOUND_TITLE,
            COL_SOUND_URI,
            COL_LABEL,
            COL_ONE_SHOT,
            COL_FOLDER_ID
        )
        var cursor: Cursor? = null
        try {
            cursor = mDb.query(ALARMS_TABLE_NAME, cols, null, null, null, null, null)
            if (cursor?.moveToFirst() == true) {
                do {
                    try {
                        val id = cursor.getIntValue(COL_ID)
                        val timeInMinutes = cursor.getIntValue(COL_TIME_IN_MINUTES)
                        val days = cursor.getIntValue(COL_DAYS)
                        val isEnabled = cursor.getIntValue(COL_IS_ENABLED) == 1
                        val vibrate = cursor.getIntValue(COL_VIBRATE) == 1
                        val soundTitle = cursor.getStringValue(COL_SOUND_TITLE)
                        val soundUri = cursor.getStringValue(COL_SOUND_URI)
                        val label = cursor.getStringValue(COL_LABEL)
                        val oneShot = cursor.getIntValue(COL_ONE_SHOT) == 1
                        val folderIdIdx = cursor.getColumnIndex(COL_FOLDER_ID)
                        val folderId = if (cursor.isNull(folderIdIdx)) null else cursor.getIntValue(COL_FOLDER_ID)

                        val alarm = Alarm(
                            id = id,
                            timeInMinutes = timeInMinutes,
                            days = days,
                            isEnabled = isEnabled,
                            vibrate = vibrate,
                            soundTitle = soundTitle,
                            soundUri = soundUri,
                            label = label,
                            oneShot = oneShot,
                            folderId = folderId
                        )
                        alarms.add(alarm)
                    } catch (e: Exception) {
                        continue
                    }
                } while (cursor.moveToNext())
            }
        } finally {
            cursor?.close()
        }

        return alarms
    }

    // --- Folder operations ---

    fun getFolders(): List<AlarmFolder> {
        val folders = mutableListOf<AlarmFolder>()
        val cols = arrayOf(COL_ID, COL_NAME, COL_CREATED_AT)
        var cursor: Cursor? = null
        try {
            cursor = mDb.query(FOLDERS_TABLE_NAME, cols, null, null, null, null, "$COL_CREATED_AT ASC")
            if (cursor?.moveToFirst() == true) {
                do {
                    val id = cursor.getIntValue(COL_ID)
                    val name = cursor.getStringValue(COL_NAME)
                    val createdAtIdx = cursor.getColumnIndex(COL_CREATED_AT)
                    val createdAt = if (cursor.isNull(createdAtIdx)) System.currentTimeMillis() else cursor.getLong(createdAtIdx)
                    folders.add(AlarmFolder(id = id, name = name, createdAt = createdAt))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor?.close()
        }
        return folders
    }

    fun insertFolder(name: String): Int {
        val values = ContentValues().apply {
            put(COL_NAME, name)
            put(COL_CREATED_AT, System.currentTimeMillis())
        }
        return mDb.insert(FOLDERS_TABLE_NAME, null, values).toInt()
    }

    fun updateFolderName(folderId: Int, name: String): Boolean {
        val values = ContentValues().apply {
            put(COL_NAME, name)
        }
        return mDb.update(FOLDERS_TABLE_NAME, values, "$COL_ID = ?", arrayOf(folderId.toString())) == 1
    }

    fun deleteFolder(folderId: Int) {
        mDb.delete(FOLDERS_TABLE_NAME, "$COL_ID = ?", arrayOf(folderId.toString()))
    }

    fun getAlarmsInFolder(folderId: Int): List<Alarm> = getAlarms().filter { it.folderId == folderId }

    fun moveAlarmToFolder(alarmId: Int, folderId: Int?) {
        val values = ContentValues()
        if (folderId != null) values.put(COL_FOLDER_ID, folderId)
        else values.putNull(COL_FOLDER_ID)
        mDb.update(ALARMS_TABLE_NAME, values, "$COL_ID = ?", arrayOf(alarmId.toString()))
    }

}
