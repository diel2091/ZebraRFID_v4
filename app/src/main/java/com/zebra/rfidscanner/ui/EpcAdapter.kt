package com.zebra.rfidscanner.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zebra.rfidscanner.data.TagEntry
import com.zebra.rfidscanner.databinding.ItemEpcBinding

class EpcAdapter : ListAdapter<TagEntry, EpcAdapter.ViewHolder>(DIFF) {

    class ViewHolder(private val binding: ItemEpcBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: TagEntry) {
            binding.tvEpc.text = entry.epc
            binding.tvCount.text = "x${entry.readCount}"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEpcBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<TagEntry>() {
            override fun areItemsTheSame(a: TagEntry, b: TagEntry) = a.id == b.id
            override fun areContentsTheSame(a: TagEntry, b: TagEntry) = a == b
        }
    }
}
