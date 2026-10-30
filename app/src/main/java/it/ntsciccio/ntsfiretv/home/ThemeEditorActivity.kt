// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import it.ntsciccio.ntsfiretv.R
import it.ntsciccio.ntsfiretv.system.StoragePermission

/**
 * On-TV theme editor: each item is changed with ◀ ▶ (or OK for the next value)
 * and the preview on the left updates immediately. "Salva" (Save) writes the JSON file to
 * `/sdcard/NTSFireTV/themes/` and activates the theme.
 *
 * Without [EXTRA_THEME_ID] it starts from a copy of the current theme (new theme),
 * otherwise it edits the given user theme.
 */
class ThemeEditorActivity : OverlayActivity() {

    /** Recreating it would lose the work in progress: it keeps the theme it was opened with. */
    override val recreateOnThemeChange = false

    private lateinit var listView: ListView
    private lateinit var adapter: RowAdapter
    private lateinit var theme: ThemeSpec
    private lateinit var fields: List<Field<*>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme_editor)

        val editId: String? = intent.getStringExtra(EXTRA_THEME_ID)
        val existing = editId?.let { ThemeStore.find(this, it) }
        theme = existing ?: ThemeStore.current(this).copy(id = "", name = getString(R.string.theme_new_name))

        listView = findViewById(R.id.list)
        adapter = RowAdapter(this)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> onRowClicked(position) }
        fields = buildFields()

        refresh(selection = 0)
        listView.requestFocus()
    }

    // Fire OS 6 (API 25) has no Android 13+ predictive back: onKeyDown is enough
    @SuppressLint("GestureBackNavigation")
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> +1
            else -> 0
        }
        val position = listView.selectedItemPosition
        val field = fields.getOrNull(position)
        if (delta != 0 && field != null && listView.hasFocus()) {
            field.step(delta)
            refresh(position)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (StoragePermission.isGrantedResult(requestCode, grantResults)) save()
    }

    private fun onRowClicked(position: Int) {
        when {
            position == fields.size -> save()
            fields[position] is NameField -> editName(position)
            else -> {
                fields[position].step(+1)
                refresh(position)
            }
        }
    }

    /** Redraws preview and rows keeping the selection on [selection]. */
    private fun refresh(selection: Int) {
        ThemeStyler.styleListScreen(this, theme)
        findViewById<TextView>(R.id.list_title).text = theme.name
        ThemeStyler.styleClock(findViewById(R.id.theme_preview_clock), theme)
        findViewById<TextView>(R.id.theme_preview_normal).background = ThemeStyler.tileShape(this, theme, focused = false)
        findViewById<TextView>(R.id.theme_preview_focused).background = ThemeStyler.tileShape(this, theme, focused = true)
        ThemeStyler.styleSecondary(findViewById(R.id.theme_preview_secondary), theme)

        val rows = fields.map { Row(title = it.title, subtitle = "◀  ${it.valueLabel()}  ▶") } +
                Row(title = getString(R.string.theme_editor_save), subtitle = getString(R.string.theme_editor_save_hint))
        adapter.setRows(rows)
        listView.setSelection(selection)
    }

    private fun editName(position: Int) {
        val input = EditText(this).apply {
            setText(theme.name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSelectAllOnFocus(true)
        }
        tvDialog()
            .setTitle(R.string.theme_field_name)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) theme = theme.copy(name = name)
                refresh(position)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun save() {
        if (!StoragePermission.ensure(this)) return
        val saved = ThemeStore.save(this, theme)
        if (saved == null) {
            toast(R.string.theme_save_error, long = true)
            return
        }
        ThemeStore.select(this, saved)
        toast(getString(R.string.theme_saved, saved.name))
        finish()
    }

    // ------------------------------------------------------------------ editor items

    /** An item: possible values (which may depend on the theme, e.g. on the accent color) and how to apply them. */
    private open inner class Field<T>(
        val title: String,
        private val options: (ThemeSpec) -> List<Pair<String, T>>,
        private val get: (ThemeSpec) -> T,
        private val set: (ThemeSpec, T) -> ThemeSpec,
    ) {
        open fun valueLabel(): String {
            val value = get(theme)
            return options(theme).firstOrNull { it.second == value }?.first ?: getString(R.string.theme_value_custom)
        }

        open fun step(delta: Int) {
            val list = options(theme)
            val index = list.indexOfFirst { it.second == get(theme) }
            val next = if (index < 0) 0 else (index + delta).mod(list.size)
            theme = set(theme, list[next].second)
        }
    }

    private inner class NameField : Field<String>(getString(R.string.theme_field_name), { emptyList() }, { it.name }, { t, _ -> t }) {
        override fun valueLabel(): String = theme.name
        override fun step(delta: Int) = Unit
    }

    private fun buildFields(): List<Field<*>> {
        val accents = listOf(
            "Bianco" to 0xFFFFFFFF.toInt(),
            "Arancione" to 0xFFFF8A3D.toInt(),
            "Rosa sakura" to 0xFFFF8FB3.toInt(),
            "Rosso" to 0xFFFF4D4D.toInt(),
            "Giallo" to 0xFFFFD54A.toInt(),
            "Verde" to 0xFF4CD97B.toInt(),
            "Turchese" to 0xFF2EC4B6.toInt(),
            "Blu" to 0xFF4DA3FF.toInt(),
            "Viola" to 0xFF9B7BFF.toInt(),
            "Corallo" to 0xFFFF6F61.toInt(),
            "Oro" to 0xFFE8B54A.toInt(),
            "Menta" to 0xFF6EE7B7.toInt(),
            "Ciano" to 0xFF22D3EE.toInt(),
            "Lavanda" to 0xFFC4B5FD.toInt(),
            "Fucsia" to 0xFFF472B6.toInt(),
        )
        val tileColors = listOf(
            "Grafite" to 0x1C1C1E,
            "Nero" to 0x000000,
            "Viola notte" to 0x2A1530,
            "Blu notte" to 0x0F2438,
            "Bosco" to 0x102A1E,
            "Cioccolato" to 0x2B1A10,
            "Bianco" to 0xFFFFFF,
        )
        val alphas = listOf("40%" to 0x66, "55%" to 0x8C, "70%" to 0xB3, "85%" to 0xD9, "100%" to 0xFF)
        val fonts = ThemeSpec.FONTS.map { (key, label) -> label to key }

        return listOf(
            NameField(),
            Field(getString(R.string.theme_field_accent), { accents }, { it.accent }, { t, v -> t.copy(accent = v) }),
            Field(
                getString(R.string.theme_field_tile_style),
                { ThemeSpec.TILE_STYLES.map { (key, label) -> label to key } },
                { it.tileStyle },
                { t, v -> t.copy(tileStyle = v) },
            ),
            Field(
                getString(R.string.theme_field_double_focus),
                { listOf("Sì" to true, "No" to false) },
                { it.doubleFocus },
                { t, v -> t.copy(doubleFocus = v) },
            ),
            Field(
                getString(R.string.theme_field_tile_color),
                { tileColors },
                { it.tile and 0xFFFFFF },
                { t, v ->
                    t.copy(
                        tile = withAlpha(v, t.tile ushr 24),
                        tileFocused = withAlpha(lighten(v), t.tileFocused ushr 24),
                        panel = withAlpha(v, t.panel ushr 24),
                    )
                },
            ),
            Field(
                getString(R.string.theme_field_tile_alpha),
                { alphas },
                { (it.tile ushr 24) and 0xFF },
                { t, v ->
                    t.copy(
                        tile = withAlpha(t.tile, v),
                        tileFocused = withAlpha(t.tileFocused, minOf(v + 0x33, 0xFF)),
                        panel = withAlpha(t.panel, minOf(v + 0x33, 0xFF)),
                    )
                },
            ),
            Field(
                getString(R.string.theme_field_border),
                { t ->
                    listOf(
                        "Nessuno" to 0,
                        "Chiaro tenue" to 0x40FFFFFF,
                        "Accento tenue" to withAlpha(t.accent, 0x66),
                    )
                },
                { it.tileBorder },
                { t, v -> t.copy(tileBorder = v) },
            ),
            Field(
                getString(R.string.theme_field_focus_border),
                { t -> listOf("Bianco" to 0xFFFFFFFF.toInt(), "Accento" to t.accent) },
                { it.focusBorder },
                { t, v -> t.copy(focusBorder = v) },
            ),
            Field(
                getString(R.string.theme_field_corners),
                { listOf(0, 6, 12, 16, 20, 28).map { "$it dp" to it } },
                { it.cornerRadiusDp },
                { t, v -> t.copy(cornerRadiusDp = v) },
            ),
            Field(getString(R.string.theme_field_clock_font), { fonts }, { it.clockFont }, { t, v -> t.copy(clockFont = v) }),
            Field(
                getString(R.string.theme_field_clock_size),
                { (32..64 step 4).map { "$it" to it } },
                { it.clockSizeSp },
                { t, v -> t.copy(clockSizeSp = v) },
            ),
            Field(
                getString(R.string.theme_field_clock_glow),
                { t -> listOf("Ombra nera" to 0xE6000000.toInt(), "Alone accento" to t.accent, "Nessuno" to 0) },
                { it.clockShadow },
                { t, v -> t.copy(clockShadow = v) },
            ),
            Field(getString(R.string.theme_field_title_font), { fonts }, { it.titleFont }, { t, v -> t.copy(titleFont = v) }),
            Field(
                getString(R.string.theme_field_secondary),
                { t -> listOf("Bianco tenue" to 0xB3FFFFFF.toInt(), "Tinta accento" to withAlpha(t.accent, 0xD9)) },
                { it.textSecondary },
                { t, v -> t.copy(textSecondary = v) },
            ),
            Field(
                getString(R.string.theme_field_wallpaper),
                { listOf(getString(R.string.theme_wallpaper_generated) to true, getString(R.string.theme_wallpaper_mine) to false) },
                { it.wallpaper != null },
                { t, v -> t.copy(wallpaper = if (v) t.wallpaper ?: WallpaperSpec.fromColors(t.tile, t.accent) else null) },
            ),
            Field(
                getString(R.string.theme_field_scrim),
                { listOf("Sì" to true, "No" to false) },
                { it.homeScrim },
                { t, v -> t.copy(homeScrim = v) },
            ),
            Field(
                getString(R.string.theme_field_overlay),
                { t -> listOf("Nero" to 0xD9000000.toInt(), "Tinta delle tile" to withAlpha(t.tile, 0xE0)) },
                { it.overlay },
                { t, v -> t.copy(overlay = v) },
            ),
        )
    }

    companion object {
        private const val EXTRA_THEME_ID = "theme_id"

        /** Opens the editor: with [themeId] edits that user theme, without it creates a new theme. */
        fun intent(context: Context, themeId: String?): Intent =
            Intent(context, ThemeEditorActivity::class.java).putExtra(EXTRA_THEME_ID, themeId)
    }
}
