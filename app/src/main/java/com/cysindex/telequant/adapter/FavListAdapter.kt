package com.cysindex.telequant.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cysindex.telequant.R
import com.cysindex.telequant.room.Favourite
import com.cysindex.telequant.spoof.FakeEnvironment
import com.cysindex.telequant.utils.ext.radioSummary

class FavListAdapter(
    ) : ListAdapter<Favourite,FavListAdapter.ViewHolder>(FavListComparetor()) {

    var onItemClick : ((Favourite) -> Unit)? = null
    var onItemDelete : ((Favourite) -> Unit)? = null
    var onListChanged : ((Int) -> Unit)? = null

    override fun onCurrentListChanged(
        previousList: MutableList<Favourite>,
        currentList: MutableList<Favourite>
    ) {
        super.onCurrentListChanged(previousList, currentList)
        onListChanged?.invoke(currentList.size)
    }

   inner class ViewHolder(view: View): RecyclerView.ViewHolder(view) {

        private val address: TextView = view.findViewById(R.id.address)
        private val badge: TextView = view.findViewById(R.id.favourite_badge)
        private val row: View = view.findViewById(R.id.address_row)
        private val delete: ImageView = itemView.findViewById(R.id.del)

        fun bind(favorite: Favourite){
            address.text = favorite.address
            // Whether a place carries a recording decides what Start can offer
            // for it, so it has to be visible before the place is picked.
            val environment = FakeEnvironment.parse(favorite.environment)
            badge.text = environment?.let { badge.context.radioSummary(it) }
                ?: badge.context.getString(R.string.favourite_badge_position)
            delete.setOnClickListener {
                onItemDelete?.invoke(favorite)
            }
            row.setOnClickListener {
                onItemClick?.invoke(favorite)
            }
        }
    }

    class FavListComparetor : DiffUtil.ItemCallback<Favourite>() {
        override fun areItemsTheSame(oldItem: Favourite, newItem: Favourite): Boolean {
            return oldItem.address == newItem.address
        }

        override fun areContentsTheSame(oldItem: Favourite, newItem: Favourite): Boolean {
            return oldItem == newItem
        }

    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.fav_items, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        if (item != null){
            holder.bind(item)

        }

    }



}