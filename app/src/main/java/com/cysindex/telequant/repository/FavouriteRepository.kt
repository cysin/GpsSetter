package com.cysindex.telequant.repository

import androidx.annotation.WorkerThread
import com.cysindex.telequant.room.Favourite
import com.cysindex.telequant.room.FavouriteDao
import kotlinx.coroutines.flow.Flow

class FavouriteRepository(private val favouriteDao: FavouriteDao) {

    val getAllFavourites: Flow<List<Favourite>>
        get() = favouriteDao.getAllFavourites()

    @WorkerThread
    suspend fun addNewFavourite(favourite: Favourite): Long =
        favouriteDao.insertToRoomDatabase(favourite)

    suspend fun deleteFavourite(favourite: Favourite) {
        favouriteDao.deleteSingleFavourite(favourite)
    }
}
