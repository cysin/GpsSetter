package com.cysindex.telequant.ui.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cysindex.telequant.gsApp
import com.cysindex.telequant.repository.FavouriteRepository
import com.cysindex.telequant.room.AppDatabase
import com.cysindex.telequant.room.Favourite
import com.cysindex.telequant.utils.PrefManager
import com.cysindex.telequant.utils.ext.onIO
import com.cysindex.telequant.utils.ext.onMain
import com.highcapable.yukihookapi.YukiHookAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import timber.log.Timber

class MainViewModel(
    private val favouriteRepository: FavouriteRepository
) : ViewModel() {

    val getLat get() = PrefManager.getLat
    val getLng get() = PrefManager.getLng
    val isStarted get() = PrefManager.isStarted

    private val _allFavList = MutableStateFlow<List<Favourite>>(emptyList())
    val allFavList: StateFlow<List<Favourite>> = _allFavList

    fun doGetUserDetails() {
        onIO {
            favouriteRepository.getAllFavourites
                .catch { e ->
                    Timber.tag("TeleQuant").d(e, "Failed to load favourites")
                }
                .collectLatest { _allFavList.emit(it) }
        }
    }

    fun update(start: Boolean, la: Double, ln: Double) {
        PrefManager.update(start, la, ln)
    }

    /**
     * What is known about the module actually running somewhere.
     *
     * Under a rooted framework the module is loaded into this App as well, so
     * it can report on itself. Under a rootless one it cannot: the module runs
     * only inside apps that were patched, and this App is not one of them —
     * `isModuleActive` there is false however well spoofing is working. What
     * can be known instead is which package last read the settings, so that is
     * what gets shown.
     */
    sealed interface HookStatus {
        data object Unknown : HookStatus
        data object Active : HookStatus
        data class Reading(val packageName: String, val at: Long) : HookStatus
    }

    val hookStatus = MutableLiveData<HookStatus>()

    fun updateHookStatus() {
        onMain {
            hookStatus.value = when {
                YukiHookAPI.Status.isModuleActive -> HookStatus.Active
                else -> PrefManager.hookCheckIns.maxByOrNull { it.value }
                    ?.let { HookStatus.Reading(it.key, it.value) }
                    ?: HookStatus.Unknown
            }
        }
    }

    fun deleteFavourite(favourite: Favourite) = onIO {
        favouriteRepository.deleteFavourite(favourite)
    }

    /**
     * Inserts with a null id so SQLite assigns the rowid itself, and returns
     * it — or -1 if the insert was ignored.
     *
     * A suspend function rather than a LiveData result: the caller used to
     * register a fresh observer on every save, and observers on an Activity's
     * lifecycle accumulate — the third save produced three toasts, and each
     * new observer was immediately replayed the *previous* save's result.
     */
    suspend fun storeFavorite(
        address: String,
        lat: Double,
        lon: Double,
        environment: String? = null,
        capturedAt: Long = 0L
    ): Long = withContext(Dispatchers.IO) {
        favouriteRepository.addNewFavourite(
            Favourite(
                id = null,
                address = address,
                lat = lat,
                lng = lon,
                environment = environment,
                capturedAt = capturedAt
            )
        )
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel(
                    FavouriteRepository(AppDatabase.getInstance(gsApp).favouriteDao())
                )
            }
        }
    }
}
