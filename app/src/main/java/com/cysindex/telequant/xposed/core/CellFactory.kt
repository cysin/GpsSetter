package com.cysindex.telequant.xposed.core

import android.os.Parcel
import android.os.SystemClock
import android.telephony.CellInfo
import com.cysindex.telequant.spoof.CellRecord
import com.highcapable.yukihookapi.hook.log.YLog
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Produces the cell list an app sees: one [CellInfo] per recorded cell, each
 * carrying that record's own values.
 *
 * The hooks used to rewrite whatever the device happened to report, always with
 * the first record in the recording. Three things followed from that, all of
 * them visible to anything that looks at more than one cell:
 *
 *  - every cell in the list reported the same MCC/MNC/CID/PCI, which is not a
 *    thing a radio produces — neighbours differ by definition;
 *  - a recording of five towers surfaced one, so the neighbour set that cell
 *    positioning actually resolves against was never delivered;
 *  - an LTE record landed on whatever type the device reported, so an
 *    `CellIdentityNr` came back holding an LTE EARFCN in its NR ARFCN field.
 *
 * The list is built from the recording instead, and each object is bound to the
 * record it was built from so the getter hooks can answer per instance rather
 * than from a single global record.
 *
 * Objects are cloned from a real one of the same type wherever possible: that
 * keeps every field this code does not set at whatever the platform put there.
 * Only when the device reports no cell of that type at all — no service, no SIM,
 * or simply a different radio — is one constructed from nothing.
 */
object CellFactory {

    private val classNames = mapOf(
        "lte" to "android.telephony.CellInfoLte",
        "nr" to "android.telephony.CellInfoNr",
        "gsm" to "android.telephony.CellInfoGsm",
        "wcdma" to "android.telephony.CellInfoWcdma",
        "tdscdma" to "android.telephony.CellInfoTdscdma",
        "cdma" to "android.telephony.CellInfoCdma"
    )

    /**
     * Hidden-API exemptions, installed once per process.
     *
     * Constructing a CellInfo and a ScanResult both need constructors the
     * platform hides. The exemption is scoped to the two package prefixes that
     * need it rather than lifting the restriction process-wide: this runs inside
     * somebody else's app, and widening its API surface further than the module
     * needs is not this module's call to make.
     */
    private val exempted: Boolean by lazy {
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/telephony/", "Landroid/net/wifi/")
        }.onFailure { YLog.warn("hidden api exemption failed: $it") }.getOrDefault(false)
    }

    fun ensureExemptions() {
        exempted
    }

    // --- per-instance binding -------------------------------------------------

    /**
     * Weak, and compared by identity. [CellInfo] and its identity classes
     * override equals(), so two towers with the same values are equal to each
     * other — a hash map would collapse them onto one record and undo the point
     * of building the list per record.
     */
    private class Binding(target: Any, val record: CellRecord) {
        private val ref = WeakReference(target)
        fun target(): Any? = ref.get()
    }

    private val bindings = CopyOnWriteArrayList<Binding>()

    fun bind(target: Any, record: CellRecord) {
        bindings.removeIf { it.target() == null }
        bindings.add(Binding(target, record))
    }

    /** The record this object was built from, or null if this module did not build it. */
    fun recordFor(target: Any): CellRecord? {
        bindings.forEach { if (it.target() === target) return it.record }
        return null
    }

    // --- construction ---------------------------------------------------------

    fun build(real: List<CellInfo>, records: List<CellRecord>): List<CellInfo> {
        ensureExemptions()
        val byType = real.groupBy { it.javaClass.name }
        val out = ArrayList<CellInfo>(records.size)
        records.forEach { record ->
            val className = classNames[record.type.lowercase()] ?: classNames.getValue("lte")
            val info = byType[className]?.firstOrNull()?.let { clone(it) } ?: construct(className)
            if (info == null) {
                YLog.warn("no way to build a ${record.type} cell on this platform")
                return@forEach
            }
            stamp(info, record)
            out += info
        }
        return out
    }

    /**
     * A deep copy through the platform's own serialisation. The copy
     * constructors are hidden and their signatures move between releases;
     * Parcelable is the one round trip the platform guarantees for itself.
     */
    private fun clone(source: CellInfo): CellInfo? = runCatching {
        val parcel = Parcel.obtain()
        try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            CellInfo.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }.onFailure { YLog.warn("cell clone failed: $it") }.getOrNull()

    private fun construct(className: String): CellInfo? = runCatching {
        val clazz = Class.forName(className)
        val direct = runCatching {
            clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }.getOrNull()
        (direct ?: HiddenApiBypass.newInstance(clazz)) as? CellInfo
    }.onFailure { YLog.debug("cannot construct $className: $it") }.getOrNull()

    private fun stamp(info: CellInfo, record: CellRecord) {
        bind(info, record)
        info.setPrivate("mRegistered", record.registered)
        info.setPrivate("mTimeStamp", SystemClock.elapsedRealtimeNanos())
        // CONNECTION_PRIMARY_SERVING / CONNECTION_NONE.
        info.setPrivate("mCellConnectionStatus", if (record.registered) 1 else 0)

        runCatching { info.cellIdentity }.getOrNull()?.let {
            bind(it, record)
            stampIdentity(it, record)
        }
        runCatching { info.cellSignalStrength }.getOrNull()?.let {
            bind(it, record)
            stampSignal(it, record)
        }
    }

    /**
     * Writes the record into the object's own fields.
     *
     * Hooking the getters is not enough on its own: toString(), writeToParcel()
     * and anything reflecting over the object read the fields directly, so a
     * hooked getter leaves the string form still naming the real tower. Field
     * names are stable across the CellIdentity subclasses.
     */
    fun stampIdentity(identity: Any, cell: CellRecord) {
        identity.setPrivate("mMccStr", cell.mcc?.let { "%03d".format(it) })
        identity.setPrivate("mMncStr", cell.mnc?.let { "%02d".format(it) })
        identity.setPrivate("mAlphaLong", cell.operatorLong)
        identity.setPrivate("mAlphaShort", cell.operatorShort)
        cell.lac?.let {
            identity.setPrivate("mTac", it)
            identity.setPrivate("mLac", it)
        }
        cell.pci?.let { identity.setPrivate("mPci", it) }
        cell.psc?.let { identity.setPrivate("mPsc", it) }
        cell.bsic?.let { identity.setPrivate("mBsic", it) }
        cell.arfcn?.let {
            identity.setPrivate("mNrArfcn", it)
            identity.setPrivate("mEarfcn", it)
            identity.setPrivate("mArfcn", it)
            identity.setPrivate("mUarfcn", it)
        }
        cell.cid?.let {
            identity.setPrivate("mNci", it)
            identity.setPrivate("mCi", it.toInt())
            identity.setPrivate("mCid", it.toInt())
        }
    }

    /**
     * Signal strength, for the same reason. Each radio type names its own
     * measurement, so every candidate is attempted and the ones this class does
     * not have are skipped.
     */
    fun stampSignal(signal: Any, cell: CellRecord) {
        signal.setPrivate("mLevel", cell.level)
        listOf("mSsRsrp", "mCsiRsrp", "mRsrp", "mRscp", "mDbm", "mRssi").forEach {
            signal.setPrivate(it, cell.dbm)
        }
    }

    /** Walks the hierarchy: these fields live on the subclasses. */
    private fun Any.setPrivate(fieldName: String, value: Any?) {
        // A null means "the recording did not capture this"; clearing the field
        // would be worse than leaving what is already there.
        if (value == null) return
        var c: Class<*>? = javaClass
        while (c != null) {
            val field = runCatching { c!!.getDeclaredField(fieldName) }.getOrNull()
            if (field != null) {
                runCatching {
                    field.isAccessible = true
                    field.set(this, value)
                }
                return
            }
            c = c.superclass
        }
    }
}
