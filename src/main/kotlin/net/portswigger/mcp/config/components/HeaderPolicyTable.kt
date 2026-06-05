package net.portswigger.mcp.config.components

import net.portswigger.mcp.config.Design
import net.portswigger.mcp.varlayer.HeaderMode
import net.portswigger.mcp.varlayer.HeaderPolicy
import net.portswigger.mcp.varlayer.HeaderRule
import java.awt.Component
import java.awt.Dimension
import javax.swing.*
import javax.swing.Box.createHorizontalGlue
import javax.swing.Box.createVerticalStrut
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/**
 * Per-header policy table for the Variable Layer tab.
 *
 * Columns: Enabled | Header | Mode | Variable | Reason (locked headers)
 *
 * Top section: configurable headers from HeaderPolicy.DEFAULTS.
 *   - Enabled: checkbox (toggle on/off)
 *   - Mode: dropdown (Opaque / Structured)
 *
 * Bottom section: locked headers from HeaderPolicy.LOCKED.
 *   - Always shown greyed-out — cannot be enabled.
 *   - Reason column explains WHY it's locked (attack surface).
 */
class HeaderPolicyTable : JPanel() {

    data class Row(
        val headerName: String,
        val variableName: String,
        var enabled: Boolean,
        var mode: HeaderMode,
        val isLocked: Boolean,
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
        configureTable()
        buildPanel()
    }

    private fun buildRows() {
        // Configurable headers
        for (rule in HeaderPolicy.DEFAULTS) {
            rows.add(Row(
                headerName = rule.name + if (rule.isWildcard) "*" else "",
                variableName = rule.variableName,
                enabled = true,
                mode = rule.mode,
                isLocked = false
            ))
        }

        // Locked headers — grouped with reasons
        val lockedReasons = mapOf(
            "Host" to "Host-header injection, password-reset poisoning",
            "Origin" to "CORS misconfiguration testing",
            "Referer" to "CSRF, referrer-based access control",
            "Content-Length" to "HTTP request smuggling (CL.TE)",
            "Transfer-Encoding" to "HTTP request smuggling (TE.CL)",
            "X-Forwarded-For" to "IP-based access control bypass",
            "X-Forwarded-Host" to "Host-header injection via proxy",
            "X-Forwarded-Proto" to "HTTPS downgrade, mixed content",
            "X-Real-IP" to "IP spoofing, WAF bypass",
            "X-Original-URL" to "Path-based access control bypass",
            "X-Rewrite-URL" to "URL rewrite bypass (IIS)"
        )
        for ((name, reason) in lockedReasons) {
            rows.add(Row(
                headerName = name,
                variableName = "—",
                enabled = false,
                mode = HeaderMode.DISABLED,
                isLocked = true,
                lockReason = reason
            ))
        }
    }

    private fun configureTable() {
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.fillsViewportHeight = true
        table.rowHeight = 28
        table.tableHeader.font = Design.Typography.labelMedium
        table.font = Design.Typography.bodyMedium
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

        // Column widths
        table.columnModel.getColumn(0).preferredWidth = 60   // Enabled
        table.columnModel.getColumn(0).maxWidth = 70
        table.columnModel.getColumn(1).preferredWidth = 180  // Header
        table.columnModel.getColumn(2).preferredWidth = 110  // Mode
        table.columnModel.getColumn(2).maxWidth = 130
        table.columnModel.getColumn(3).preferredWidth = 90   // Variable
        table.columnModel.getColumn(3).maxWidth = 110
        table.columnModel.getColumn(4).preferredWidth = 300  // Reason / info

        // Custom renderers
        table.columnModel.getColumn(0).cellRenderer = CheckBoxRenderer()
        table.columnModel.getColumn(0).cellEditor = CheckBoxEditor()
        table.columnModel.getColumn(2).cellRenderer = ModeRenderer()
        table.columnModel.getColumn(2).cellEditor = ModeEditor()

        // Grey out locked rows
        table.setDefaultRenderer(Any::class.java, LockedRowRenderer())
    }

    private fun buildPanel() {
        add(Design.createSectionLabel("Per-header policy"))
        add(createVerticalStrut(Design.Spacing.SM))
        add(JLabel("Which headers to template and how. Locked headers are attack-critical and always pass through raw.").apply {
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
            alignmentX = LEFT_ALIGNMENT
        })
        add(createVerticalStrut(Design.Spacing.MD))

        val scrollPane = JScrollPane(table).apply {
            alignmentX = LEFT_ALIGNMENT
            preferredSize = Dimension(780, 320)
            minimumSize = Dimension(200, 280)
            maximumSize = Dimension(Int.MAX_VALUE, 400)
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }
        add(scrollPane)
    }

    // ================================================================
    // Table model
    // ================================================================

    inner class PolicyTableModel : AbstractTableModel() {
        private val colNames = arrayOf("Enabled", "Header", "Mode", "Variable", "Info")
        override fun getRowCount() = rows.size
        override fun getColumnCount() = colNames.size
        override fun getColumnName(col: Int) = colNames[col]

        override fun getValueAt(row: Int, col: Int): Any {
            val r = rows[row]
            return when (col) {
                0 -> r.enabled
                1 -> r.headerName
                2 -> r.mode.name
                3 -> r.variableName
                4 -> if (r.isLocked) "LOCKED — ${r.lockReason}" else ""
                else -> ""
            }
        }

        override fun isCellEditable(row: Int, col: Int): Boolean {
            if (rows[row].isLocked) return false
            return col == 0 || col == 2  // Enabled checkbox, Mode dropdown
        }

        override fun setValueAt(value: Any?, row: Int, col: Int) {
            val r = rows[row]
            if (r.isLocked) return
            when (col) {
                0 -> r.enabled = value as Boolean
                2 -> r.mode = HeaderMode.valueOf(value as String)
            }
            fireTableCellUpdated(row, col)
        }

        override fun getColumnClass(col: Int): Class<*> = when (col) {
            0 -> java.lang.Boolean::class.java
            else -> String::class.java
        }
    }

    // ================================================================
    // Custom renderers and editors
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

    inner class ModeRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, col: Int
        ): Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col)
            if (rows[row].isLocked) {
                text = "—"
                foreground = Design.Colors.onSurfaceVariant
            }
            return comp
        }
    }

    inner class ModeEditor : DefaultCellEditor(
        JComboBox(arrayOf("OPAQUE", "STRUCTURED", "DISABLED"))
    )

    inner class LockedRowRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, col: Int
        ): Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col)
            if (rows[row].isLocked) {
                foreground = Design.Colors.onSurfaceVariant
                font = font.deriveFont(java.awt.Font.ITALIC)
            } else {
                foreground = Design.Colors.onSurface
                font = Design.Typography.bodyMedium
            }
            return comp
        }
    }
}
