package com.cysindex.telequant.room

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved place.
 *
 * [environment] is the recorded radio surroundings as JSON, or null when only
 * the coordinate was saved. Keeping it as a column rather than four related
 * tables is deliberate: it is written and read as one whole, it crosses to the
 * hooked process as JSON anyway, and the alternative is a join for something
 * that is never queried by its parts.
 */
@Entity
data class Favourite(
    @PrimaryKey(autoGenerate = false)
    val id: Long? = null,
    val address: String?,
    val lat: Double?,
    val lng: Double?,
    val environment: String? = null,
    /** When the environment was captured; 0 when there is none. */
    val capturedAt: Long = 0L
)
