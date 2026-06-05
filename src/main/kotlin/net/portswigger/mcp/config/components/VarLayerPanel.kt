package net.portswigger.mcp.config.components

import net.portswigger.mcp.config.Design
import net.portswigger.mcp.config.McpVarLayerConfig
import net.portswigger.mcp.varlayer.VarLayer
import javax.swing.*
import javax.swing.Box.createHorizontalGlue
import javax.swing.Box.createHorizontalStrut
import javax.swing.Box.createVerticalGlue
import javax.swing.Box.createVerticalStrut

/**
 * Variable Layer configuration panel.
 *
 * Surfaces:
 *   - Master enable toggle
 *   - Per-tab apply targets (History, Repeater, Intruder, Scanner)
 *   - Default mode (Opaque / Structured / Disabled)
 *   - Promotion threshold
 *   - Require-reveal-approval checkbox
 *
 * Header policy table (per-header mode dropdowns) is deferred to Phase 1D.
 */
class VarLayerPanel(
    private val config: McpVarLayerConfig,
    @Suppress("unused") private val varLayer: VarLayer
) : JPanel() {

    private val enabledToggle = Design.createToggleSwitch(config.enabled) { value ->
        config.enabled = value
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        updateColors()
        buildPanel()
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
        // ---- header ----
        add(JLabel("Session Variable Layer").apply {
            font = Design.Typography.headlineMedium
            foreground = Design.Colors.onSurface
            alignmentX = LEFT_ALIGNMENT
        })
        add(createVerticalStrut(Design.Spacing.SM))
        add(JLabel("Compress repeated header values (JWT, Cookie, User-Agent) into {{VAR}} placeholders to reduce token usage.").apply {
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
            alignmentX = LEFT_ALIGNMENT
        })
        add(createVerticalStrut(Design.Spacing.SM))
        add(JLabel("Locked headers (Host, Origin, Content-Length, X-Forwarded-*, etc.) always pass through raw.").apply {
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
            alignmentX = LEFT_ALIGNMENT
        })
        add(createVerticalStrut(Design.Spacing.LG))

        // ---- master section ----
        add(makeCard {
            add(Design.createSectionLabel("Master"))
            add(createVerticalStrut(Design.Spacing.MD))
            add(makeToggleRow("Enable session variable substitution", enabledToggle))
        })

        add(createVerticalStrut(Design.Spacing.MD))

        // ---- apply-to ----
        add(makeCard {
            add(Design.createSectionLabel("Apply to"))
            add(createVerticalStrut(Design.Spacing.MD))
            add(makeCheckbox("Proxy history tab", config.applyToHistory) { config.applyToHistory = it })
            add(createVerticalStrut(Design.Spacing.SM))
            add(makeCheckbox("Repeater tab", config.applyToRepeater) { config.applyToRepeater = it })
            add(createVerticalStrut(Design.Spacing.SM))
            add(makeCheckbox("Intruder (off by default — Intruder generates wild headers)", config.applyToIntruder) { config.applyToIntruder = it })
            add(createVerticalStrut(Design.Spacing.SM))
            add(makeCheckbox("Scanner (off by default — Scanner permutes headers aggressively)", config.applyToScanner) { config.applyToScanner = it })
        })

        add(createVerticalStrut(Design.Spacing.MD))

        // ---- default mode ----
        add(makeCard {
            add(Design.createSectionLabel("Default mode"))
            add(createVerticalStrut(Design.Spacing.SM))
            add(JLabel("How aggressively to compress headers. Per-header overrides are in the table below.").apply {
                font = Design.Typography.bodyMedium
                foreground = Design.Colors.onSurfaceVariant
                alignmentX = LEFT_ALIGNMENT
            })
            add(createVerticalStrut(Design.Spacing.MD))

            val group = ButtonGroup()
            val opaque = makeRadio("Opaque — maximum compression, attack scheme hidden", config.defaultMode == 0) {
                config.defaultMode = 0
            }
            val structured = makeRadio("Structured — claims/cookie-names visible to the model  (recommended)", config.defaultMode == 1) {
                config.defaultMode = 1
            }
            val disabled = makeRadio("Disabled — passthrough only (debug)", config.defaultMode == 2) {
                config.defaultMode = 2
            }
            group.add(opaque); group.add(structured); group.add(disabled)
            add(opaque); add(createVerticalStrut(Design.Spacing.SM))
            add(structured); add(createVerticalStrut(Design.Spacing.SM))
            add(disabled)
        })

        add(createVerticalStrut(Design.Spacing.MD))

        // ---- per-header policy table ----
        add(HeaderPolicyTable(config, varLayer))

        add(createVerticalStrut(Design.Spacing.MD))

        // ---- promotion + reveal ----
        add(makeCard {
            add(Design.createSectionLabel("Promotion & reveal"))
            add(createVerticalStrut(Design.Spacing.MD))

            val thresholdSpinner = JSpinner(SpinnerNumberModel(config.promotionThreshold, 1, 20, 1))
            thresholdSpinner.addChangeListener {
                config.promotionThreshold = thresholdSpinner.value as Int
            }
            val thresholdRow = Box.createHorizontalBox().apply {
                alignmentX = LEFT_ALIGNMENT
                add(JLabel("Promote a value to a variable after").apply {
                    font = Design.Typography.bodyMedium
                    foreground = Design.Colors.onSurface
                })
                add(createHorizontalStrut(Design.Spacing.SM))
                thresholdSpinner.maximumSize = thresholdSpinner.preferredSize
                add(thresholdSpinner)
                add(createHorizontalStrut(Design.Spacing.SM))
                add(JLabel("sightings (default 3)").apply {
                    font = Design.Typography.bodyMedium
                    foreground = Design.Colors.onSurfaceVariant
                })
                add(createHorizontalGlue())
            }
            add(thresholdRow)
            add(createVerticalStrut(Design.Spacing.MD))

            add(makeCheckbox(
                "Require my approval before revealing a variable's raw value",
                config.requireRevealApproval
            ) { config.requireRevealApproval = it })
        })

        add(createVerticalGlue())
    }

    // ------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------

    private fun makeCard(builder: JPanel.() -> Unit): JPanel = JPanel().apply {
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
        builder()
    }

    private fun makeToggleRow(text: String, toggle: JComponent): JComponent =
        Box.createHorizontalBox().apply {
            alignmentX = LEFT_ALIGNMENT
            add(JLabel(text).apply {
                font = Design.Typography.bodyLarge
                foreground = Design.Colors.onSurface
            })
            add(createHorizontalStrut(Design.Spacing.MD))
            add(createHorizontalGlue())
            add(toggle)
        }

    private fun makeCheckbox(text: String, initial: Boolean, onChange: (Boolean) -> Unit): JCheckBox =
        JCheckBox(text, initial).apply {
            alignmentX = LEFT_ALIGNMENT
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurface
            background = Design.Colors.surface
            addItemListener { onChange(isSelected) }
        }

    private fun makeRadio(text: String, initial: Boolean, onSelect: () -> Unit): JRadioButton =
        JRadioButton(text, initial).apply {
            alignmentX = LEFT_ALIGNMENT
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurface
            background = Design.Colors.surface
            addActionListener { if (isSelected) onSelect() }
        }
}
