package com.example.rockattitude

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RecordAdapter(
    private val list: MutableList<Record>,
    private val onEdit: (Record, Int) -> Unit
) : RecyclerView.Adapter<RecordAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvInfo: TextView = view.findViewById(R.id.tvInfo)
        val btnEdit: Button = view.findViewById(R.id.btnEdit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_record, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = list[position]
        holder.tvInfo.text = """
            ${r.time}
            走向: ${"%.1f".format(r.strike)}°
            倾角: ${"%.1f".format(r.dip)}°
            倾向: ${"%.1f".format(r.dipDirection)}°
            ${if (r.note.isNotBlank()) "备注: ${r.note}" else ""}
        """.trimIndent()
        holder.btnEdit.setOnClickListener { onEdit(r, position) }
    }

    override fun getItemCount() = list.size
}
