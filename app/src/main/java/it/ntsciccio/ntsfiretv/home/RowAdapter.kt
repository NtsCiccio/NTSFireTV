// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import it.ntsciccio.ntsfiretv.R

/**
 * A row of the lists (file browser, settings).
 *
 * @property key identifies the row (e.g. the path) to give it focus again after a reload.
 * @property action what to do when OK is pressed; null if the row is informational only.
 */
data class Row(
    val title: String,
    val subtitle: String? = null,
    val iconRes: Int = 0,
    val key: String? = null,
    val action: (() -> Unit)? = null,
)

/** Adapter for [android.widget.ListView] with [Row] rows and the `view_list_row` layout. */
class RowAdapter(private val context: Context) : BaseAdapter() {

    private var rows: List<Row> = emptyList()
    private val secondaryColor: Int = ThemeStore.current(context).textSecondary

    fun setRows(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    fun indexOfKey(key: String?): Int = if (key == null) -1 else rows.indexOfFirst { it.key == key }

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): Row = rows[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view: View = convertView ?: LayoutInflater.from(context).inflate(R.layout.view_list_row, parent, false)
        val row = rows[position]

        val icon: ImageView = view.findViewById(R.id.row_icon)
        val title: TextView = view.findViewById(R.id.row_title)
        val subtitle: TextView = view.findViewById(R.id.row_subtitle)

        if (row.iconRes != 0) {
            icon.setImageResource(row.iconRes)
            icon.visibility = View.VISIBLE
        } else {
            icon.visibility = View.GONE
        }
        title.text = row.title
        subtitle.text = row.subtitle
        subtitle.setTextColor(secondaryColor)
        subtitle.visibility = if (row.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        return view
    }
}
