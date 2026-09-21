package com.cysindex.telequant.config

import android.net.Uri
import com.cysindex.telequant.BuildConfig

/**
 * How a hooked process asks this App for the settings when it cannot read them
 * from a file.
 *
 * Under Vector the module's preferences are redirected by the framework to a
 * world-readable directory, and the hook simply opens them. A rootless
 * framework has no daemon to do that: LSPatch answers `getPrefsPath` with this
 * App's own private directory, which the patched app's uid may not even
 * traverse. So the settings have to be handed over deliberately, and this is
 * the door — read-only, one document, and shut unless the user opens it.
 */
object ConfigContract {

    const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.config"

    /** What a reader observes to be told the settings changed. */
    val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/config")

    /** Returns the active settings, under the [ConfigKeys] names. */
    const val METHOD_READ = "read"

    /**
     * Records that a hooked process is alive and reading. It is what replaces
     * the activation check in the drawer: a patched app cannot make
     * `YukiHookAPI.Status.isModuleActive` true, because this App itself is not
     * the one being hooked.
     */
    const val METHOD_PING = "ping"

    /** True when the door is open. A provider that answers `false` is a
     *  deliberate refusal, not a missing App, so the reader stops asking. */
    const val EXTRA_AVAILABLE = "available"
}
