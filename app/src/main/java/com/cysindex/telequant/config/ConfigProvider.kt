package com.cysindex.telequant.config

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.cysindex.telequant.utils.PrefManager

/**
 * Hands the active settings to a process this App cannot otherwise reach.
 *
 * Under Vector the hooked process reads them from a file and never comes here.
 * Under a rootless framework there is no readable file — the module App's data
 * directory belongs to a different uid — so a patched app asks for them
 * instead, and this answers.
 *
 * Deliberately narrow. It serves one document, the settings currently in
 * effect; it accepts no writes, exposes no saved places, and answers nothing
 * at all unless the user has turned rootless mode on. The one thing it takes
 * back is which package is reading, because without root this App has no other
 * way to know the module is doing anything.
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = when (method) {
        ConfigContract.METHOD_READ -> read()
        ConfigContract.METHOD_PING -> checkIn()
        else -> Bundle()
    }

    private fun read(): Bundle {
        val answer = Bundle()
        // A refusal is stated rather than left as silence: a reader that is
        // told "no" stops asking, while a reader that gets nothing back cannot
        // tell a shut door from an App that is not installed.
        answer.putBoolean(ConfigContract.EXTRA_AVAILABLE, PrefManager.rootlessMode)
        if (!PrefManager.rootlessMode) return answer

        answer.putBoolean(ConfigKeys.STARTED, PrefManager.isStarted)
        answer.putLong(ConfigKeys.LAT, PrefManager.getLat.toRawBits())
        answer.putLong(ConfigKeys.LNG, PrefManager.getLng.toRawBits())
        answer.putString(ConfigKeys.ACCURACY, PrefManager.accuracy)
        answer.putString(ConfigKeys.JITTER_RADIUS, PrefManager.jitterRadius)
        answer.putString(ConfigKeys.JITTER_MODE, PrefManager.jitterMode)
        answer.putBoolean(ConfigKeys.GCJ02, PrefManager.gcj02Output)
        answer.putBoolean(ConfigKeys.SPOOF_CELL, PrefManager.spoofCell)
        answer.putBoolean(ConfigKeys.SPOOF_WIFI, PrefManager.spoofWifi)
        answer.putBoolean(ConfigKeys.SPOOF_BLUETOOTH, PrefManager.spoofBluetooth)
        answer.putBoolean(ConfigKeys.SPOOF_TIMEZONE, PrefManager.spoofTimeZone)
        answer.putLong(ConfigKeys.VERSION, PrefManager.configVersion)
        PrefManager.activeEnvironment?.let { answer.putString(ConfigKeys.ENVIRONMENT, it) }
        return answer
    }

    private fun checkIn(): Bundle {
        if (PrefManager.rootlessMode) {
            callingPackage?.let { PrefManager.recordHookCheckIn(it) }
        }
        return Bundle()
    }

    // Nothing else is served. The settings are a document, not a table, and a
    // door this narrow is easier to reason about than one with a query surface.

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ) = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
