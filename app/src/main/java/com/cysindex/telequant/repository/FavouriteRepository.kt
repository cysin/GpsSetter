package com.cysindex.telequant.repository


import androidx.annotation.WorkerThread
import com.cysindex.telequant.room.Favourite
import com.cysindex.telequant.room.FavouriteDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class FavouriteRepository @Inject constructor(private val favouriteDao: FavouriteDao) {

        val getAllFavourites: Flow<List<Favourite>>
        get() =  favouriteDao.getAllFavourites()

        @Suppress("RedundantSuspendModifier")
        @WorkerThread
        suspend fun addNewFavourite(favourite: Favourite) : Long {
            return favouriteDao.insertToRoomDatabase(favourite)
        }

        suspend fun deleteFavourite(favourite: Favourite) {
          favouriteDao.deleteSingleFavourite(favourite)
       }


       fun getSingleFavourite(id: Long) : Favourite {
       return favouriteDao.getSingleFavourite(id)

    }



}