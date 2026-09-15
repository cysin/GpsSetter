package com.cysindex.telequant.xposed.hooks

import android.telephony.CellInfo
import com.cysindex.telequant.spoof.CellRecord
import com.cysindex.telequant.xposed.core.SpoofEngine
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.hasClass
import com.highcapable.yukihookapi.hook.factory.hasMethod
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.type.java.BooleanType
import com.highcapable.yukihookapi.hook.type.java.IntType
import com.highcapable.yukihookapi.hook.type.java.LongType
import com.highcapable.yukihookapi.hook.type.java.StringClass

/**
 * Tier B: cell identity, for apps that locate off tower databases rather than GNSS.
 *
 * The previous implementation *blanked* these — MCC forced to 460, everything
 * else set to 0, `toString()` returning "-". That is not what an unavailable
 * cell looks like: the platform uses [CellInfo.UNAVAILABLE] (Integer.MAX_VALUE)
 * for absent fields, and LAC 0 is a reserved value under 3GPP. The combination
 * "MCC 460 + CID 0 + LAC 0" is one the platform never emits, and it flatly
 * contradicted the real SIM's operator strings, which were left untouched.
 *
 * Here the values come from the active environment instead, and the operator
 * identity is rewritten to match so the two cannot disagree.
 */
object CellHooks : YukiBaseHooker() {

    private val identityClasses = listOf(
        "android.telephony.CellIdentityLte",
        "android.telephony.CellIdentityNr",
        "android.telephony.CellIdentityGsm",
        "android.telephony.CellIdentityWcdma",
        "android.telephony.CellIdentityTdscdma",
        "android.telephony.CellIdentityCdma"
    )

    private val signalClasses = listOf(
        "android.telephony.CellSignalStrengthLte",
        "android.telephony.CellSignalStrengthNr",
        "android.telephony.CellSignalStrengthGsm",
        "android.telephony.CellSignalStrengthWcdma",
        "android.telephony.CellSignalStrengthTdscdma",
        "android.telephony.CellSignalStrengthCdma"
    )

    override fun onHook() {
        if (!SpoofEngine.isEnabled) {
            // Settings can change while the app runs, so this is only a fast
            // path for the common case; each hook re-checks before acting.
        }
        hookCellIdentities()
        hookSignalStrengths()
        hookTelephonyManager()
        hookSubscriptionInfo()
    }

    private fun primaryCell(): CellRecord? {
        val snapshot = SpoofEngine.current()
        // hasCells folds in the per-signal switch, so turning cell spoofing off
        // leaves the real identity untouched rather than half-rewritten.
        if (!snapshot.enabled || !snapshot.hasCells) return null
        return snapshot.cells.firstOrNull()
    }

    /**
     * Rewrites the getters on whatever real objects the app receives, rather
     * than constructing CellInfo instances. Those have hidden constructors that
     * differ across API levels and OEM forks; editing in place is far more
     * robust across the Android 8.1–17 range Vector supports.
     */
    private fun hookCellIdentities() {
        identityClasses.forEach { className ->
            if (!className.hasClass()) return@forEach
            val clazz = className.toClass()

            clazz.hookIntGetter("getCid") { it.cid?.toInt() }
            clazz.hookIntGetter("getCi") { it.cid?.toInt() }
            clazz.hookIntGetter("getLac") { it.lac }
            clazz.hookIntGetter("getTac") { it.tacOrLac() }
            clazz.hookIntGetter("getPci") { it.pci }
            clazz.hookIntGetter("getPsc") { it.psc }
            clazz.hookIntGetter("getBsic") { it.bsic }
            clazz.hookIntGetter("getMcc") { it.mcc }
            clazz.hookIntGetter("getMnc") { it.mnc }
            clazz.hookIntGetter("getEarfcn") { it.arfcn }
            clazz.hookIntGetter("getNrarfcn") { it.arfcn }
            clazz.hookIntGetter("getArfcn") { it.arfcn }
            clazz.hookIntGetter("getUarfcn") { it.arfcn }

            // NR's cell identity does not fit in an Int.
            if (clazz.hasMethod { name = "getNci"; emptyParam(); returnType = LongType }) {
                clazz.method { name = "getNci"; emptyParam(); returnType = LongType }.hook {
                    before { primaryCell()?.cid?.let { result = it } }
                }
            }

            clazz.hookCellString("getMccString") { it.mcc?.let { m -> "%03d".format(m) } }
            clazz.hookCellString("getMncString") { it.mnc?.let { m -> "%02d".format(m) } }
            clazz.hookCellString("getOperatorAlphaLong") { it.operatorLong }
            clazz.hookCellString("getOperatorAlphaShort") { it.operatorShort }
        }
    }

    private fun hookSignalStrengths() {
        signalClasses.forEach { className ->
            if (!className.hasClass()) return@forEach
            val clazz = className.toClass()
            clazz.hookIntGetter("getDbm") { it.dbm }
            clazz.hookIntGetter("getAsuLevel") { it.asu }
            clazz.hookIntGetter("getLevel") { it.level }
            clazz.hookIntGetter("getRsrp") { it.dbm }
        }
    }

    private fun hookTelephonyManager() {
        val tm = "android.telephony.TelephonyManager".toClass()

        tm.apply {
            // getAllCellInfo needs no hook of its own: the CellIdentity getters
            // above rewrite whatever objects it returns. An empty list means no
            // service, and fabricating CellInfo there would mean calling hidden
            // constructors whose signatures vary by API level and OEM -- see the
            // note on hookCellIdentities.

            hookSnapshotString("getNetworkOperator") { it.operatorNumeric }
            hookSnapshotString("getSimOperator") { it.operatorNumeric }
            hookSnapshotString("getNetworkOperatorName") { it.operatorName }
            hookSnapshotString("getSimOperatorName") { it.operatorName }
            hookSnapshotString("getNetworkCountryIso") { it.countryIso }
            hookSnapshotString("getSimCountryIso") { it.countryIso }

            // Reporting an NR cell while the data network type says LTE is an
            // internal contradiction an app can test for directly.
            listOf("getDataNetworkType", "getVoiceNetworkType", "getNetworkType").forEach { m ->
                if (!hasMethod { name = m }) return@forEach
                method { name = m }.hookAll {
                    before {
                        val snapshot = SpoofEngine.current()
                        if (snapshot.enabled && snapshot.networkType != 0) {
                            result = snapshot.networkType
                        }
                    }
                }
            }

            // Legacy path: GsmCellLocation / CdmaCellLocation.
            if (hasMethod { name = "getCellLocation" }) {
                method { name = "getCellLocation" }.hookAll {
                    after { rewriteCellLocation(result) }
                }
            }
        }

        // GsmCellLocation's own getters, for code that holds the object.
        "android.telephony.gsm.GsmCellLocation".let { name ->
            if (!name.hasClass()) return@let
            name.toClass().apply {
                hookIntGetter("getCid") { it.cid?.toInt() }
                hookIntGetter("getLac") { it.lac }
                hookIntGetter("getPsc") { it.psc }
            }
        }
    }

    private fun rewriteCellLocation(location: Any?) {
        val cell = primaryCell() ?: return
        val obj = location ?: return
        runCatching {
            if (obj.javaClass.name.endsWith("GsmCellLocation")) {
                obj.javaClass.getMethod(
                    "setLacAndCid", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                ).invoke(obj, cell.tacOrLac() ?: CellInfo.UNAVAILABLE, cell.cid?.toInt() ?: CellInfo.UNAVAILABLE)
            }
        }
    }

    /**
     * Per-SIM identity. Previously untouched, so a dual-SIM device reported
     * subscription data that disagreed with everything TelephonyManager said.
     */
    private fun hookSubscriptionInfo() {
        val className = "android.telephony.SubscriptionInfo"
        if (!className.hasClass()) return
        className.toClass().apply {
            hookSnapshotString("getMccString") { it.operatorNumeric?.take(3) }
            hookSnapshotString("getMncString") { it.operatorNumeric?.drop(3) }
            hookSnapshotString("getCountryIso") { it.countryIso }
            hookSnapshotString("getCarrierName") { it.operatorName }
            hookSnapshotString("getDisplayName") { it.operatorName }
        }
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Absent values are reported as [CellInfo.UNAVAILABLE] rather than 0,
     * because 0 is a legal identifier and reads as a real measurement.
     */
    private fun Class<*>.hookIntGetter(methodName: String, pick: (CellRecord) -> Int?) {
        if (!hasMethod { name = methodName; emptyParam(); returnType = IntType }) return
        method { name = methodName; emptyParam(); returnType = IntType }.hook {
            before {
                val cell = primaryCell() ?: return@before
                result = pick(cell) ?: CellInfo.UNAVAILABLE
            }
        }
    }

    /** Reads a field off the primary cell record. */
    private fun Class<*>.hookCellString(methodName: String, pick: (CellRecord) -> String?) {
        if (!hasMethod { name = methodName; emptyParam(); returnType = StringClass }) return
        method { name = methodName; emptyParam(); returnType = StringClass }.hook {
            before { primaryCell()?.let { cell -> pick(cell)?.let { value -> result = value } } }
        }
    }

    /** Reads a field off the whole snapshot (operator identity, country). */
    private fun Class<*>.hookSnapshotString(
        methodName: String,
        pick: (SpoofEngine.Snapshot) -> String?
    ) {
        if (!hasMethod { name = methodName }) return
        method { name = methodName }.hookAll {
            before {
                val snapshot = SpoofEngine.current()
                if (snapshot.enabled) pick(snapshot)?.let { value -> result = value }
            }
        }
    }

    /** LTE/NR call it TAC, GSM/WCDMA call it LAC; the record stores one field. */
    private fun CellRecord.tacOrLac(): Int? = lac
}
