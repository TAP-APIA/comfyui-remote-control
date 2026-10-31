package com.example.comfyui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 通用列表适配器：用于「工作流」和「参数配置」两个列表，
 * 每项展示名称，并支持选择、重命名、删除。
 */
class NamedItemAdapter<T>(
    private val items: MutableList<T>,
    private val nameOf: (T) -> String,
    private val onSelect: (T) -> Unit,
    private val onRename: (T) -> Unit,
    private val onDelete: (T) -> Unit
) : RecyclerView.Adapter<NamedItemAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(android.R.id.text1)
        val btnRename: ImageButton = view.findViewById(R.id.btn_rename)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_workflow, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = nameOf(item)

        holder.itemView.setOnClickListener {
            onSelect(items[holder.adapterPosition])
        }

        holder.btnRename.setOnClickListener {
            onRename(items[holder.adapterPosition])
        }

        holder.btnDelete.setOnClickListener {
            onDelete(items[holder.adapterPosition])
        }
    }

    override fun getItemCount() = items.size
}
