package net.portswigger.mcp.config.components

import net.portswigger.mcp.config.Design
import net.portswigger.mcp.varlayer.HeaderMode
import net.portswigger.mcp.varlayer.VarLayer
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
 * UX improvements over baseline:
 *   - Full raw value in the Preview column (no truncation — scroll to see more)
 *   - Horizontal scrollbar (AUTO_RESIZE_OFF so columns hold their width)
 *   - Double-click any column header → auto-fit that column to its widest content
 *   - Right-click any cell → context menu: Copy cell value / Copy full raw value
 *   - Hover over any cell → tooltip showing the complete value
 */
class SessionsPanel(private val varLayer: VarLayer) : JPanel() {

    private val columns = arrayOf("Variable", "Host", "Structured Summary", "Raw Value", "Size", "Seen", "Captured")
    private val tableModel = object : DefaultTableModel(columns, 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }

    // Anonymous JTable subclass to support per-cell tooltips without a custom renderer.
    private val table = object : JTable(tableModel) {
        override fun getToolTipText(e: MouseEvent): String? {
            val row = rowAtPoint(e.point)
            val col = columnAtPoint(e.point)
            if (row < 0 || col < 0) return null
            // For the Preview column, show the full raw value from the store.
            return when (col) {
                2 -> getValueAt(row, col)?.toString()  // structured summary
                3 -> rawValueForRow(row)                // raw value — full tooltip
                else -> getValueAt(row, col)?.toString()
            }
        }
    }

    private val scrollPane = JScrollPane(table)
    private val statusLabel = JLabel("(no variables yet)")
    private val refreshBtn = Design.createOutlinedButton("Refresh")
    private val clearBtn = Design.createOutlinedButton("Clear all")

    private val timeFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        updateColors()
        configureTable()
        buildPanel()
        setupDoubleClickHeader()
        setupContextMenu()
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

    // ---------------------------------------------------------------
    // Table configuration
    // ---------------------------------------------------------------

    private fun configureTable() {
        // AUTO_RESIZE_OFF: columns keep their set widths; horizontal scroll appears as needed.
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.fillsViewportHeight = true
        table.rowHeight = (Design.Typography.bodyMedium.size * 2).coerceAtLeast(22)
        table.tableHeader.font = Design.Typography.labelMedium

        // Initial column widths (user can auto-fit by double-clicking the header).
        table.columnModel.getColumn(0).preferredWidth = 100   // {{JWT}}
        table.columnModel.getColumn(1).preferredWidth = 160   // host
        table.columnModel.getColumn(2).preferredWidth = 340   // structured summary
        table.columnModel.getColumn(3).preferredWidth = 280   // raw value
        table.columnModel.getColumn(4).preferredWidth = 60    // size
        table.columnModel.getColumn(5).preferredWidth = 50    // seen count
        table.columnModel.getColumn(6).preferredWidth = 90    // timestamp

        // Enable horizontal scrollbar.
        scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
    }

    // ---------------------------------------------------------------
    // Double-click column header → auto-fit (Excel behaviour)
    // ---------------------------------------------------------------

    private fun setupDoubleClickHeader() {
        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val col = table.columnAtPoint(e.point)
                    if (col >= 0) autoFitColumn(col)
                }
            }
        })
    }

    private fun autoFitColumn(viewCol: Int) {
        var maxWidth = 50 // minimum

        // Measure the header cell.
        val headerComp = table.tableHeader.defaultRenderer
            .getTableCellRendererComponent(
                table,
                table.columnModel.getColumn(viewCol).headerValue,
                false, false, -1, viewCol
            )
        maxWidth = maxOf(maxWidth, headerComp.preferredSize.width + 16)

        // Measure every data cell.
        for (row in 0 until table.rowCount) {
            val comp = table.getCellRenderer(row, viewCol)
                .getTableCellRendererComponent(
                    table, table.getValueAt(row, viewCol),
                    false, false, row, viewCol
                )
            maxWidth = maxOf(maxWidth, comp.preferredSize.width + 16)
        }

        table.columnModel.getColumn(viewCol).preferredWidth = maxWidth
    }

    // ---------------------------------------------------------------
    // Right-click context menu
    // ---------------------------------------------------------------

    private fun setupContextMenu() {
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { maybeShowMenu(e) }
            override fun mouseReleased(e: MouseEvent) { maybeShowMenu(e) }
        })
    }

    private fun maybeShowMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val row = table.rowAtPoint(e.point)
        val col = table.columnAtPoint(e.point)
        if (row < 0) return
        table.setRowSelectionInterval(row, row)

        val menu = JPopupMenu()

        // Copy whatever is displayed in the clicked cell.
        val cellValue = table.getValueAt(row, col)?.toString() ?: ""
        menu.add(JMenuItem("Copy cell value").apply {
            addActionListener { copyToClipboard(cellValue) }
        })

        // Copy the complete untruncated raw value from the variable store.
        val rawValue = rawValueForRow(row)
        if (rawValue != null && rawValue != cellValue) {
            menu.add(JMenuItem("Copy full raw value").apply {
                addActionListener { copyToClipboard(rawValue) }
            })
        }

        menu.addSeparator()

        menu.add(JMenuItem("Auto-fit this column").apply {
            addActionListener { autoFitColumn(col) }
        })
        menu.add(JMenuItem("Auto-fit all columns").apply {
            addActionListener {
                for (c in 0 until table.columnCount) autoFitColumn(c)
            }
        })

        menu.show(table, e.x, e.y)
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Look up the full raw value for the variable on a given table row. */
    private fun rawValueForRow(row: Int): String? {
        val varCell = table.getValueAt(row, 0)?.toString() ?: return null
        val varName = varCell.removeSurrounding("{{", "}}")
        return varLayer.capturedVariables().find { it.name == varName }?.rawValue
    }

    private fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    // ---------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------

    private fun buildPanel() {
        add(JLabel("Active Session Variables").apply {
            font = Design.Typography.headlineMedium
            foreground = Design.Colors.onSurface
            alignmentX = LEFT_ALIGNMENT
        })
        add(createVerticalStrut(Design.Spacing.SM))
        add(JLabel("In-memory only — never persisted. Double-click a column header to auto-fit. Right-click a cell to copy.").apply {
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
            alignmentX = LEFT_ALIGNMENT
        })
        add(createVerticalStrut(Design.Spacing.LG))

        scrollPane.alignmentX = LEFT_ALIGNMENT
        scrollPane.preferredSize = Dimension(780, 300)
        scrollPane.maximumSize = Dimension(Int.MAX_VALUE, 340)
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

    // ---------------------------------------------------------------
    // Data refresh
    // ---------------------------------------------------------------

    fun refresh() {
        tableModel.rowCount = 0
        val vars = varLayer.capturedVariables().sortedByDescending { it.capturedAt }
        for (v in vars) {
            // Display follows the CURRENT effective mode, not the mode at promotion time.
            // This is correct even after the user changes mode in the policy table.
            val effectiveMode = varLayer.effectiveModeFor(v.name)
            val summaryDisplay = when (effectiveMode) {
                HeaderMode.STRUCTURED -> v.structuredSummary ?: "(no structured form)"
                HeaderMode.OPAQUE -> "(opaque)"
                HeaderMode.DISABLED -> "(disabled)"
            }
            tableModel.addRow(arrayOf(
                "{{${v.name}}}",
                v.host,
                summaryDisplay,
                v.rawValue,
                "${v.rawValue.length} B",
                v.seenCount,
                timeFmt.format(v.capturedAt)
            ))
        }
        statusLabel.text = if (vars.isEmpty()) "(no variables yet)"
                           else "${vars.size} variable(s) captured"
    }
}
