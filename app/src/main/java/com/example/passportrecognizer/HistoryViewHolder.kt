package com.example.passportrecognizer

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HistoryViewHolder(
    parent: ViewGroup,
) : RecyclerView.ViewHolder(
    LayoutInflater.from(parent.context).inflate(R.layout.history_item, parent, false)
) {

    private val tvFulName: TextView = itemView.findViewById(R.id.tvFullName)
    private val tvDocNumb: TextView = itemView.findViewById(R.id.tvDocNumber)

    fun bind(item: Data) {
        tvFulName.setText("${item.surname} ${item.name} ${item.patronymic}")
        tvDocNumb.text = item.doc_numb

        itemView.setOnClickListener { /* clickListener.onTrackClick(track) */ }
    }
}