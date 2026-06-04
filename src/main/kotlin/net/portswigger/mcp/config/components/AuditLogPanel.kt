package net.portswigger.mcp.config.components

import net.portswigger.mcp.config.Design
import net.portswigger.mcp.varlayer.VarLayer
import java.awt.Dimension
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.Box.createHorizontalGlue
import javax.swing.Box.createHorizontalStrut
import javax.swing.Box.createVerticalStrut
import javax.swing.table.DefaultTableModel

/**
 * Audit log tab: bounded in-memory event log (500 entries max).
 * Records variable promotions, refreshes, reveals, bypass events, and JWT expiries.
 */
class AuditLogPanel(private val varLayer: VarLayer) : JPanel() {

    private val columns = arrayOf("Time", "Event", "Variable", "Details")
    private val tableModel = object : DefaultTableModel(columns, 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JTable(tableModel)
    private val statusLabel = JLabel("0 events")
    private val refreshBtn = Design.createOutlinedButton("Refresh")
    private val clearBtn = Design.createOutlinedButton("Clear log")

    private val timeFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

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
        add(JLabel("Audit Log").apply {
            font = Design.Typography.headlineMedium
            foreground = Design.Colors.onSurface
            alignmentX = LEFT_ALIGNMENT
        })
        add(createVerticalStrut(Design.Spacing.SM))
        add(JLabel(
            "<html>Last 500 events. Capped circular buffer, in-memory only " +
            "(~125 KB ceiling).</html>"
        ).apply {
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
            alignmentX = LEFT_ALIGNMENT
        })
        add(createVerticalStrut(Design.Spacing.LG))

        table.fillsViewportHeight = true
        table.rowHeight = (Design.Typography.bodyMedium.size * 1.8).toInt().coerceAtLeast(20)
        table.tableHeader.font = Design.Typography.labelMedium
        table.columnModel.getColumn(0).preferredWidth = 110
        table.columnModel.getColumn(1).preferredWidth = 140
        table.columnModel.getColumn(2).preferredWidth = 100
        table.columnModel.getColumn(3).preferredWidth = 360

        val scrollPane = JScrollPane(table).apply {
            alignmentX = LEFT_ALIGNMENT
            preferredSize = Dimension(720, 320)
            maximumSize = Dimension(Int.MAX_VALUE, 380)
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
            varLayer.auditLog.clear()
            refresh()
        }
    }

    fun refresh() {
        tableModel.rowCount = 0
        val events = varLayer.auditLog.all().reversed()  // newest first
        for (e in events) {
            tableModel.addRow(arrayOf(
                timeFmt.format(e.timestamp),
                e.event.name,
                e.varName ?: "—",
                e.details
            ))
        }
        statusLabel.text = "${events.size} event(s)"
    }
}
