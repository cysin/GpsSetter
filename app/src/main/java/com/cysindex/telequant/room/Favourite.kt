package com.cysindex.telequant.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Favourite(
    @PrimaryKey(autoGenerate = false)
    val id: Long? = null,
    val address: String?,
    val lat: Double?,
    val lng: Double?
)