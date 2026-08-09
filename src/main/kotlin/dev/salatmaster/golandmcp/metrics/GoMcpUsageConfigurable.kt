package dev.salatmaster.golandmcp.metrics

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/**
 * A read-only view of how the agent has been using these tools this session.
 *
 * There is no configuration here on purpose. Which tools an MCP client may call is already
 * the IDE's own setting, under Settings | Tools | MCP Server | MCP Tool Filter, and a second
 * switch for the same thing would only be a way for the two to disagree.
 */
class GoMcpUsageConfigurable : Configurable {

    private var model: UsageTableModel? = null

    override fun getDisplayName(): String = "Go MCP++"

    override fun createComponent(): JComponent {
        val tableModel = UsageTableModel().also { model = it }
        val table = JBTable(tableModel).apply {
            setShowGrid(false)
            autoCreateRowSorter = true
            emptyText.text = "No tool has been called yet in this IDE session"
        }

        val note = JBLabel(
            "<html>Counts are held in memory for this IDE session only. Nothing is written to " +
                "disk and nothing leaves this machine.<br>A tool sitting at zero calls usually " +
                "means the agent has not been told it exists - check the client's MCP " +
                "configuration.</html>",
        ).apply { border = JBUI.Borders.emptyBottom(8) }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(JButton("Refresh").apply { addActionListener { tableModel.reload() } })
            add(
                JButton("Reset counters").apply {
                    addActionListener {
                        GoMcpToolMetrics.getInstance().reset()
                        tableModel.reload()
                    }
                },
            )
            border = JBUI.Borders.emptyTop(8)
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(note, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    /** Nothing here is editable, so there is never anything to apply. */
    override fun isModified(): Boolean = false

    override fun apply() = Unit

    override fun disposeUIResources() {
        model = null
    }

    private class UsageTableModel : AbstractTableModel() {

        private var rows: List<GoToolUsage> = load()

        fun reload() {
            rows = load()
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = COLUMNS.size

        override fun getColumnName(column: Int): String = COLUMNS[column]

        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex in 1..4) java.lang.Long::class.java else String::class.java

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.tool
                1 -> row.calls
                2 -> row.failures
                3 -> row.cancellations
                4 -> row.averageMillis
                else -> if (row.lastCallMillis == 0L) {
                    "never"
                } else {
                    DateFormatUtil.formatPrettyDateTime(row.lastCallMillis)
                }
            }
        }

        private companion object {
            val COLUMNS = arrayOf("Tool", "Calls", "Failures", "Cancelled", "Avg ms", "Last used")

            /** Every contributed tool, so the ones never called are visible as zeros. */
            fun load(): List<GoToolUsage> {
                val recorded = GoMcpToolMetrics.getInstance().snapshot().associateBy { it.tool }
                return GoMcpToolCatalog.toolNames
                    .map { name -> recorded[name] ?: GoToolUsage(name, 0, 0, 0, 0, 0) }
                    .sortedWith(compareByDescending<GoToolUsage> { it.calls }.thenBy { it.tool })
            }
        }
    }
}
