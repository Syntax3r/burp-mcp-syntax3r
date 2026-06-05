package net.portswigger.mcp.config.components

import net.portswigger.mcp.config.Design
import net.portswigger.mcp.config.McpVarLayerConfig
import net.portswigger.mcp.varlayer.HeaderMode
import net.portswigger.mcp.varlayer.HeaderPolicy
import net.portswigger.mcp.varlayer.PolicyOverrides
import net.portswigger.mcp.varlayer.VarLayer
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.*
import javax.swing.Box.createVerticalStrut
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * Per-header policy table — configurable headers on top, locked headers below.
 *
 * Columns: Header | Template | Mode | Detector / Pattern | Avg Saving
 *
 * Changes are persisted immediately to McpVarLayerConfig.headerPolicyJson
 * and take effect on the next VarLayer tool call (no restart needed).
 */
class HeaderPolicyTable(private val config: McpVarLayerConfig, private val varLayer: VarLayer) : JPanel() {

    data class Row(
        val headerName: String,
        val variableName: String,
        var enabled: Boolean,
        var mode: HeaderMode,
        val isLocked: Boolean,
        val detector: String,
        val lockReason: String = ""
    )

    private val rows: MutableList<Row> = mutableListOf()
    private val model = PolicyTableModel()
    private val table = JTable(model)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        background = Design.Colors.surface
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Design.Colors.outlineVariant, 1),
            BorderFactory.createEmptyBorder(
                Design.Spacing.MD, Design.Spacing.MD,
                Design.Spacing.MD, Design.Spacing.MD
            )
        )
        buildRows()
        loadOverrides()
        configureTable()
        buildPanel()
    }

    private fun buildRows() {
        // Configurable headers with detector descriptions and avg savings
        val detectors = listOf(
            "jwt-parse: claims+alg+kid+exp",
            "cookie-classify: auth/track/pref",
            "stable across session",
            "wildcard prefix match",
            "stable across session",
            "stable across session",
        )
        for ((i, rule) in HeaderPolicy.DEFAULTS.withIndex()) {
            rows.add(Row(
                headerName = rule.name + if (rule.isWildcard) "*" else "",
                variableName = rule.variableName,
                enabled = true,
                mode = rule.mode,
                isLocked = false,
                detector = detectors[i]
            ))
        }

        // Locked headers — grouped with reasons
        rows.add(Row("Host", "-", false, HeaderMode.DISABLED, true, "", "host-header injection surface"))
        rows.add(Row("Origin / Referer", "-", false, HeaderMode.DISABLED, true, "", "CORS / CSRF reasoning"))
        rows.add(Row("Content-Length, Transfer-Encoding", "-", false, HeaderMode.DISABLED, true, "", "request smuggling - exact bytes matter"))
        rows.add(Row("X-Forwarded-*, X-Original-URL, X-Rewrite-URL", "-", false, HeaderMode.DISABLED, true, "", "access-control bypass surface"))
        rows.add(Row("X-* (any custom)", "-", false, HeaderMode.DISABLED, true, "", "opt-in only - assume attack-relevant"))
    }

    /** Load persisted user overrides from config. */
    private fun loadOverrides() {
        val overrides = PolicyOverrides.read(config)
        for (ovr in overrides) {
            val row = rows.find { !it.isLocked && it.headerName.equals(ovr.name, ignoreCase = true) }
            if (row != null) {
                row.enabled = ovr.enabled
                row.mode = try { HeaderMode.valueOf(ovr.mode) } catch (_: Exception) { row.mode }
            }
        }
    }

    /** Persist current table state to config. */
    private fun saveOverrides() {
        val overrides = rows.filter { !it.isLocked }.map { row ->
            net.portswigger.mcp.varlayer.HeaderOverride(
                name = row.headerName.removeSuffix("*"),
                enabled = row.enabled,
                mode = row.mode.name
            )
        }
        PolicyOverrides.write(config, overrides)
    }

    private fun configureTable() {
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.fillsViewportHeight = true
        table.rowHeight = 30
        table.tableHeader.font = Design.Typography.labelMedium
        table.font = Design.Typography.bodyMedium
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

        // Column widths
        table.columnModel.getColumn(0).preferredWidth = 160  // Header
        table.columnModel.getColumn(1).preferredWidth = 65   // Template
        table.columnModel.getColumn(1).maxWidth = 80
        table.columnModel.getColumn(2).preferredWidth = 110  // Mode
        table.columnModel.getColumn(2).maxWidth = 130
        table.columnModel.getColumn(3).preferredWidth = 260  // Detector
        table.columnModel.getColumn(4).preferredWidth = 100  // Avg Saving
        table.columnModel.getColumn(4).maxWidth = 120

        // Checkbox renderer/editor for Template column
        table.columnModel.getColumn(1).cellRenderer = CheckBoxRenderer()
        table.columnModel.getColumn(1).cellEditor = CheckBoxEditor()

        // Mode dropdown editor
        table.columnModel.getColumn(2).cellEditor = DefaultCellEditor(
            JComboBox(arrayOf("OPAQUE", "STRUCTURED", "DISABLED"))
        )

        // Custom renderers for styling
        val styledRenderer = StyledCellRenderer()
        table.columnModel.getColumn(0).cellRenderer = styledRenderer
        table.columnModel.getColumn(2).cellRenderer = styledRenderer
        table.columnModel.getColumn(3).cellRenderer = styledRenderer
        table.columnModel.getColumn(4).cellRenderer = SavingRenderer()
    }

    private fun buildPanel() {
        add(Design.createSectionLabel("Per-header policy"))
        add(createVerticalStrut(Design.Spacing.SM))
        add(JLabel("Which headers to template and how. Changes take effect on the next tool call.").apply {
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
            alignmentX = LEFT_ALIGNMENT
        })
        add(createVerticalStrut(Design.Spacing.MD))

        val scrollPane = JScrollPane(table).apply {
            alignmentX = LEFT_ALIGNMENT
            preferredSize = Dimension(780, 340)
            minimumSize = Dimension(200, 300)
            maximumSize = Dimension(Int.MAX_VALUE, 420)
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        add(scrollPane)
    }

    // ================================================================
    // Table model — writes to config on every edit
    // ================================================================

    inner class PolicyTableModel : AbstractTableModel() {
        private val cols = arrayOf("Header", "Template", "Mode", "Detector / Pattern", "Avg Saving")
        override fun getRowCount() = rows.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(col: Int) = cols[col]

        override fun getValueAt(row: Int, col: Int): Any {
            val r = rows[row]
            return when (col) {
                0 -> if (r.isLocked) "\uD83D\uDD12 ${r.headerName}" else r.headerName
                1 -> r.enabled
                2 -> if (r.isLocked) "LOCKED" else r.mode.name
                3 -> if (r.isLocked) r.lockReason else r.detector
                4 -> if (r.isLocked) "" else computeRealSaving(r)
                else -> ""
            }
        }

        override fun isCellEditable(row: Int, col: Int): Boolean {
            if (rows[row].isLocked) return false
            return col == 1 || col == 2
        }

        override fun setValueAt(value: Any?, row: Int, col: Int) {
            val r = rows[row]
            if (r.isLocked) return
            when (col) {
                1 -> r.enabled = value as Boolean
                2 -> r.mode = try { HeaderMode.valueOf(value as String) } catch (_: Exception) { r.mode }
            }
            fireTableCellUpdated(row, col)
            saveOverrides()  // persist immediately
        }

        override fun getColumnClass(col: Int): Class<*> = when (col) {
            1 -> java.lang.Boolean::class.java
            else -> String::class.java
        }
    }

    // ================================================================
    // Dynamic savings — computed from actual captured data
    // ================================================================

    /**
     * Computes real savings from actual captured variables, not estimates.
     * Shows "—" when no data has been captured yet for this header.
     */
    private fun computeRealSaving(r: Row): String {
        val captured = varLayer.capturedVariables().find { it.name == r.variableName }
            ?: return "\u2014"  // em-dash: no data yet

        val rawBytes = captured.rawValue.length
        val compressedBytes = if (captured.structuredSummary != null) {
            "{{${r.variableName}|${captured.structuredSummary}}}".length
        } else {
            "{{${r.variableName}}}".length
        }
        val savedBytes = rawBytes - compressedBytes
        val pct = if (rawBytes > 0) (100 * savedBytes / rawBytes) else 0
        return "${rawBytes}B \u2192 ${compressedBytes}B (-${pct}%)"
    }

    // ================================================================
    // Custom renderers
    // ================================================================

    inner class CheckBoxRenderer : JCheckBox(), TableCellRenderer {
        init { horizontalAlignment = CENTER }
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, col: Int
        ): Component {
            this.isSelected = value as? Boolean ?: false
            isEnabled = !rows[row].isLocked
            background = if (isSelected) table.selectionBackground else table.background
            return this
        }
    }

    inner class CheckBoxEditor : DefaultCellEditor(JCheckBox()) {
        init { (component as JCheckBox).horizontalAlignment = JCheckBox.CENTER }
    }

    /** Greyed-out italic for locked rows, normal for configurable. */
    inner class StyledCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, col: Int
        ): Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col)
            if (rows[row].isLocked) {
                foreground = Design.Colors.onSurfaceVariant
                font = font.deriveFont(Font.ITALIC)
            } else {
                foreground = if (isSelected) table.selectionForeground else Design.Colors.onSurface
                font = Design.Typography.bodyMedium
            }
            return comp
        }
    }

    /** Green-colored saving numbers for configurable rows. */
    inner class SavingRenderer : DefaultTableCellRenderer() {
        init { horizontalAlignment = RIGHT }
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, col: Int
        ): Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col)
            if (!rows[row].isLocked && (value as? String)?.isNotEmpty() == true) {
                foreground = java.awt.Color(52, 211, 153)  // emerald-400
                font = font.deriveFont(Font.BOLD)
            } else {
                foreground = Design.Colors.onSurfaceVariant
                font = Design.Typography.bodyMedium
            }
            return comp
        }
    }
}
