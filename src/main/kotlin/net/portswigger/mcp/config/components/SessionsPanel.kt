package net.portswigger.mcp.config.components

import net.portswigger.mcp.config.Design
import net.portswigger.mcp.varlayer.VarLayer
import java.awt.Dimension
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.Box.createHorizontalGlue
import javax.swing.Box.createHorizontalStrut
import javax.swing.Box.createVerticalStrut
import javax.swing.table.DefaultTableModel

/**
 * Sessions tab: live view of variables currently captured in memory.
 *
 * Phase 1C: manual refresh button. Auto-refresh timer comes in Phase 1D
 * (we want the user in control of EDT load until we measure it).
 */
class SessionsPanel(private val varLayer: VarLayer) : JPanel() {

    private val columns = arrayOf("Variable", "Preview", "Size", "Seen", "Captured")
    private val tableModel = object : DefaultTableModel(columns, 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JTable(tableModel)
    private val statusLabel = JLabel("(no variables yet)")
    private val refreshBtn = Design.createOutlinedButton("Refresh")
    private val clearBtn = Design.createOutlinedButton("Clear all")

    private val timeFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        updateColors()
        buildPanel()
        refresh()
    }

    override fun updateUI() {
        super.updateUI()
        updateColors()
    }

    private fun updateColors() {
        background = Design.Colors.surface
        border = BorderFactory.createEmptyBorder(
            Design.Spacing.LG, Design.Spacing.LG,
            Design.Spacing.LG, Design.Spacing.LG
        )
    }

    private fun buildPanel() {
        add(JLabel("Active Session Variables").apply {
            font = Design.Typography.headlineMedium
            foreground = Design.Colors.onSurface
            alignmentX = LEFT_ALIGNMENT
        })
        add(createVerticalStrut(Design.Spacing.SM))
        add(JLabel("Captured variables live in memory only — never persisted to disk.").apply {
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
            alignmentX = LEFT_ALIGNMENT
        })
        add(createVerticalStrut(Design.Spacing.LG))

        table.fillsViewportHeight = true
        table.rowHeight = (Design.Typography.bodyMedium.size * 2).coerceAtLeast(22)
        table.tableHeader.font = Design.Typography.labelMedium
        // Column sizing hints
        table.columnModel.getColumn(0).preferredWidth = 100
        table.columnModel.getColumn(1).preferredWidth = 360
        table.columnModel.getColumn(2).preferredWidth = 70
        table.columnModel.getColumn(3).preferredWidth = 50
        table.columnModel.getColumn(4).preferredWidth = 90

        val scrollPane = JScrollPane(table).apply {
            alignmentX = LEFT_ALIGNMENT
            preferredSize = Dimension(720, 280)
            maximumSize = Dimension(Int.MAX_VALUE, 320)
        }
        add(scrollPane)
        add(createVerticalStrut(Design.Spacing.MD))

        val buttonsRow = Box.createHorizontalBox().apply {
            alignmentX = LEFT_ALIGNMENT
            add(statusLabel.apply {
                font = Design.Typography.bodyMedium
                foreground = Design.Colors.onSurfaceVariant
            })
            add(createHorizontalGlue())
            add(refreshBtn)
            add(createHorizontalStrut(Design.Spacing.SM))
            add(clearBtn)
        }
        add(buttonsRow)

        refreshBtn.addActionListener { refresh() }
        clearBtn.addActionListener {
            val result = JOptionPane.showConfirmDialog(
                this,
                "Clear all captured variables? Session state will be reset.\n" +
                "Policy config and audit log are preserved.",
                "Confirm clear",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (result == JOptionPane.OK_OPTION) {
                varLayer.clearCapturedVariables()
                refresh()
            }
        }
    }

    fun refresh() {
        tableModel.rowCount = 0
        val vars = varLayer.capturedVariables().sortedByDescending { it.capturedAt }
        for (v in vars) {
            val preview = if (v.rawValue.length > 60) v.rawValue.take(57) + "..." else v.rawValue
            tableModel.addRow(arrayOf(
                "{{${v.name}}}",
                preview,
                "${v.rawValue.length} B",
                v.seenCount,
                timeFmt.format(v.capturedAt)
            ))
        }
        statusLabel.text = if (vars.isEmpty()) "(no variables yet)"
                           else "${vars.size} variable(s) captured"
    }
}
