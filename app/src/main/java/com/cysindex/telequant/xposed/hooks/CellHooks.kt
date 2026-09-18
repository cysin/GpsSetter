package com.cysindex.telequant.xposed.hooks

import android.telephony.CellInfo
import com.cysindex.telequant.spoof.CellRecord
import com.cysindex.telequant.xposed.core.CellFactory
import com.cysindex.telequant.xposed.core.SpoofEngine
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.hasClass
import com.highcapable.yukihookapi.hook.factory.hasMethod
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.HookParam
import com.highcapable.yukihookapi.hook.type.java.BooleanType
import com.highcapable.yukihookapi.hook.type.java.IntType
import com.highcapable.yukihookapi.hook.type.java.LongType
import com.highcapable.yukihookapi.hook.type.java.StringClass
import java.util.Collections
import java.util.concurrent.Executor

/**
 * Tier B: cell identity, for apps that locate off tower databases rather than GNSS.
 *
 * The original implementation *blanked* these — MCC forced to 460, everything
 * else set to 0, `toString()` returning "-". That is not what an unavailable
 * cell looks like: the platform uses [CellInfo.UNAVAILABLE] (Integer.MAX_VALUE)
 * for absent fields, and LAC 0 is a reserved value under 3GPP. The combination
 * "MCC 460 + CID 0 + LAC 0" is one the platform never emits, and it flatly
 * contradicted the real SIM's operator strings, which were left untouched.
 *
 * Values now come from the active environment, the operator identity is
 * rewritten to match so the two cannot disagree, and the list itself is rebuilt
 * from the recording by [CellFactory] rather than stamped onto whatever the
 * device happened to report.
 *
 * Both directions are covered. Apps pull with `getAllCellInfo`, but the modern
 * path is to register a callback and be pushed to; a pull-only hook leaves the
 * push path delivering the real neighbour set.
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
        hookCellIdentities()
        hookSignalStrengths()
        hookTelephonyManager()
        hookServiceState()
        hookSubscriptionInfo()
    }

    /**
     * The record behind the object whose method is running.
     *
     * [CellFactory] binds each object it builds to the record it came from, so a
     * list of five towers answers as five different towers. An object this
     * module did not build — one that arrived through a path not covered here —
     * falls back to the primary cell, which is still better than leaking the
     * real one.
     */
    private fun HookParam.boundCell(): CellRecord? {
        val snapshot = SpoofEngine.current()
        // hasCells folds in the per-signal switch, so turning cell spoofing off
        // leaves the real identity untouched rather than half-rewritten.
        if (!snapshot.enabled || !snapshot.hasCells) return null
        return instance?.let { CellFactory.recordFor(it) } ?: snapshot.cells.firstOrNull()
    }

    /**
     * Rewrites the getters on whatever objects the app receives. Those classes
     * have hidden constructors that differ across API levels and OEM forks, so
     * editing in place is far more robust across the Android 8.1–17 range Vector
     * supports; [CellFactory] only constructs one when the device offers nothing
     * of that type to copy.
     */
    private fun hookCellIdentities() {
        identityClasses.forEach { className ->
            if (!className.hasClass()) return@forEach
            val clazz = className.toClass()

            clazz.hookIntGetter("getCid") { it.cid?.toInt() }
            clazz.hookIntGetter("getCi") { it.cid?.toInt() }
            clazz.hookIntGetter("getLac") { it.lac }
            clazz.hookIntGetter("getTac") { it.lac }
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
                    before { boundCell()?.cid?.let { result = it } }
                }
            }

            clazz.hookCellString("getMccString") { it.mcc?.let { m -> "%03d".format(m) } }
            clazz.hookCellString("getMncString") { it.mnc?.let { m -> "%02d".format(m) } }
            clazz.hookCellString("getOperatorAlphaLong") { it.operatorLong }
            clazz.hookCellString("getOperatorAlphaShort") { it.operatorShort }

            // Getters alone are not enough, for the same reason they were not
            // enough on Location: toString() reads the backing fields directly,
            // so a hooked getter leaves the string form still naming the real
            // tower and operator. Anything logging or serialising the object —
            // including dumpsys — sees through it, and the object then
            // contradicts the operator strings that *were* rewritten.
            clazz.hookFieldRewrite("toString", CellFactory::stampIdentity)
            clazz.hookFieldRewrite("hashCode", CellFactory::stampIdentity)
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
            clazz.hookFieldRewrite("toString", CellFactory::stampSignal)
        }
    }

    private fun hookTelephonyManager() {
        val tm = "android.telephony.TelephonyManager".toClass()

        tm.apply {
            // The pull path. An empty real list is still rebuilt: with no service
            // the app would otherwise see no towers at all, which is the one
            // case where a tower database has nothing to work with.
            if (hasMethod { name = "getAllCellInfo" }) {
                method { name = "getAllCellInfo" }.hookAll {
                    after {
                        val snapshot = SpoofEngine.current()
                        if (!snapshot.enabled || !snapshot.hasCells) return@after
                        @Suppress("UNCHECKED_CAST")
                        val real = (result as? List<*>)?.filterIsInstance<CellInfo>().orEmpty()
                        result = CellFactory.build(real, snapshot.cells)
                    }
                }
            }

            // The push path: listen() for the legacy PhoneStateListener,
            // registerTelephonyCallback() for its API 31 replacement, and
            // requestCellInfoUpdate() for a one-shot refresh. All three hand
            // over a callback object whose concrete class can be hooked; the
            // declared method cannot, being abstract on the platform's own type.
            listOf("listen", "registerTelephonyCallback", "requestCellInfoUpdate").forEach { m ->
                if (!hasMethod { name = m }) return@forEach
                method { name = m }.hookAll {
                    before {
                        args.filterNotNull()
                            .filterNot { it is Executor || it is Int }
                            .forEach { hookCallbackClass(it.javaClass) }
                    }
                }
            }

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
                        if (snapshot.enabled && snapshot.hasCells && snapshot.networkType != 0) {
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

    private val hookedCallbacks = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Hooks one concrete callback class, once, wherever it carries cell data.
     *
     * `PhoneStateListener.onCellInfoChanged` and friends are abstract on the
     * platform class, so there is no body for LSPlant to replace — the same
     * obstacle as `BroadcastReceiver.onReceive`. Catching the object as it
     * registers gives us the subclass that does have an implementation.
     */
    private fun hookCallbackClass(clazz: Class<*>) {
        if (!hookedCallbacks.add(clazz.name)) return
        runCatching {
            // onCellInfoChanged on the listeners, onCellInfo on CellInfoCallback.
            listOf("onCellInfoChanged", "onCellInfo").forEach { callback ->
                if (!clazz.hasMethod { name = callback }) return@forEach
                clazz.method { name = callback }.hookAll {
                    before {
                        val snapshot = SpoofEngine.current()
                        if (!snapshot.enabled || !snapshot.hasCells) return@before
                        val index = args.indexOfFirst { it is List<*> }
                        if (index < 0) return@before
                        val real = (args[index] as List<*>).filterIsInstance<CellInfo>()
                        args[index] = CellFactory.build(real, snapshot.cells)
                    }
                }
            }
            if (clazz.hasMethod { name = "onCellLocationChanged" }) {
                clazz.method { name = "onCellLocationChanged" }.hookAll {
                    before { args.firstOrNull()?.let { rewriteCellLocation(it) } }
                }
            }
        }.onFailure { YLog.debug("callback hook skipped for ${clazz.name}: $it") }
    }

    private fun rewriteCellLocation(location: Any?) {
        val snapshot = SpoofEngine.current()
        if (!snapshot.enabled || !snapshot.hasCells) return
        val cell = snapshot.cells.firstOrNull() ?: return
        val obj = location ?: return
        runCatching {
            if (obj.javaClass.name.endsWith("GsmCellLocation")) {
                obj.javaClass.getMethod(
                    "setLacAndCid", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                ).invoke(obj, cell.lac ?: CellInfo.UNAVAILABLE, cell.cid?.toInt() ?: CellInfo.UNAVAILABLE)
            }
        }
    }

    /**
     * The registration state an app reads straight off [ServiceState], which was
     * previously untouched: `getOperatorAlphaLong()` kept naming the real
     * carrier while `TelephonyManager.getNetworkOperatorName()` had already been
     * rewritten. Two carrier names in one process contradict each other more
     * loudly than either value alone gives away.
     */
    private fun hookServiceState() {
        val className = "android.telephony.ServiceState"
        if (!className.hasClass()) return
        className.toClass().apply {
            hookSnapshotString("getOperatorAlphaLong") { it.operatorName }
            hookSnapshotString("getOperatorAlphaShort") { it.operatorShort() }
            hookSnapshotString("getOperatorNumeric") { it.operatorNumeric }

            // STATE_IN_SERVICE. An app told the radio is out of service ignores
            // whatever the towers say.
            listOf("getState", "getDataRegistrationState", "getVoiceRegistrationState").forEach { m ->
                if (!hasMethod { name = m; emptyParam(); returnType = IntType }) return@forEach
                method { name = m; emptyParam(); returnType = IntType }.hook {
                    before { if (spoofingCells()) result = 0 }
                }
            }
            listOf("getRoaming", "isRoaming").forEach { m ->
                if (!hasMethod { name = m; emptyParam(); returnType = BooleanType }) return@forEach
                method { name = m; emptyParam(); returnType = BooleanType }.hook {
                    before { if (spoofingCells()) result = false }
                }
            }
        }

        // Carried inside ServiceState, and names the network the device is
        // registered on independently of the strings above.
        "android.telephony.NetworkRegistrationInfo".let { name ->
            if (!name.hasClass()) return@let
            name.toClass().hookSnapshotString("getRegisteredPlmn") { it.operatorNumeric }
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

    private fun spoofingCells(): Boolean =
        SpoofEngine.current().let { it.enabled && it.hasCells }

    private fun SpoofEngine.Snapshot.operatorShort(): String? =
        cells.firstOrNull()?.operatorShort ?: operatorName

    /**
     * Absent values are reported as [CellInfo.UNAVAILABLE] rather than 0,
     * because 0 is a legal identifier and reads as a real measurement.
     */
    private fun Class<*>.hookIntGetter(methodName: String, pick: (CellRecord) -> Int?) {
        if (!hasMethod { name = methodName; emptyParam(); returnType = IntType }) return
        method { name = methodName; emptyParam(); returnType = IntType }.hook {
            before {
                val cell = boundCell() ?: return@before
                result = pick(cell) ?: CellInfo.UNAVAILABLE
            }
        }
    }

    /** Reads a field off the record bound to this object. */
    private fun Class<*>.hookCellString(methodName: String, pick: (CellRecord) -> String?) {
        if (!hasMethod { name = methodName; emptyParam(); returnType = StringClass }) return
        method { name = methodName; emptyParam(); returnType = StringClass }.hook {
            before { boundCell()?.let { cell -> pick(cell)?.let { value -> result = value } } }
        }
    }

    /** Overwrites the object's own fields just before a method exposing them runs. */
    private fun Class<*>.hookFieldRewrite(methodName: String, stamp: (Any, CellRecord) -> Unit) {
        if (!hasMethod { name = methodName; emptyParam() }) return
        method { name = methodName; emptyParam() }.hook {
            before {
                val target = instance ?: return@before
                val cell = boundCell() ?: return@before
                runCatching { stamp(target, cell) }
            }
        }
    }

    /**
     * Reads a field off the whole snapshot (operator identity, country). Gated on
     * the cell switch: rewriting the carrier while leaving the towers real is
     * itself a contradiction.
     */
    private fun Class<*>.hookSnapshotString(
        methodName: String,
        pick: (SpoofEngine.Snapshot) -> String?
    ) {
        if (!hasMethod { name = methodName }) return
        method { name = methodName }.hookAll {
            before {
                val snapshot = SpoofEngine.current()
                if (snapshot.enabled && snapshot.hasCells) {
                    pick(snapshot)?.let { value -> result = value }
                }
            }
        }
    }
}
