package com.example.passportrecognizer

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView


class HistoryAdapter : RecyclerView.Adapter<HistoryViewHolder>() {
    private var dataModels = mutableListOf<Data>()

    /*  // ... конструктор, ViewHolder и другие методы адаптера
      fun setData(dataModels: mutableListOf<Data>?) {
          this.dataModels = dataModels
      }
  */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder =
        HistoryViewHolder(parent)

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(dataModels[position])
    }

    override fun getItemCount(): Int {
        return dataModels.size
    }
}