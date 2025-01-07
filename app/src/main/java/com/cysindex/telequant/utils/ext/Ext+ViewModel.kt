package com.cysindex.telequant.utils.ext

import android.content.Context
import android.location.Geocoder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.util.*

fun ViewModel.onIO(body: suspend () -> Unit): Job {
    return viewModelScope.launch(Dispatchers.IO) {
        body()
    }
}

fun ViewModel.onDefault(body: suspend () -> Unit): Job {
    return viewModelScope.launch(Dispatchers.Default) {
        body()
    }
}

fun ViewModel.onMain(body: suspend () -> Unit): Job {
    return viewModelScope.launch(Dispatchers.Main) {
        body()
    }
}