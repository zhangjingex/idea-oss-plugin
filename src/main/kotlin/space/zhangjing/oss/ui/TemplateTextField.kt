package space.zhangjing.oss.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextField
import space.zhangjing.oss.utils.VariablesUtils
import java.awt.Color
import java.awt.Graphics
import java.awt.Shape
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.text.BadLocationException
import javax.swing.text.DefaultHighlighter
import javax.swing.text.Highlighter

class TemplateTextField(
    private val tooltipProvider: (String) -> String,
    private val isValidVariable: (String) -> Boolean = {
        it in VariablesUtils.allVariableKeys()
    }
) : JBTextField() {

    private val variableRegex = VariablesUtils.variablePattern
    private val correctVariableColor = JBColor.GREEN.darker()
    private val wrongVariableColor = JBColor.RED.darker()
    private val highlighter: Highlighter = getHighlighter()

    init {
        // 添加文本变化监听
        document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = highlightVariables()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = highlightVariables()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = highlightVariables()
        })

        // 鼠标移动时显示 tooltip
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                updateTooltip(e.x, e.y)
            }
        })

        addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent?) {
                toolTipText = null
            }
        })
    }

    private fun highlightVariables() {
        val text = text
        highlighter.removeAllHighlights()

        variableRegex.findAll(text).forEach { match ->
            val variableName = match.groupValues[1]
            val isCorrect = isValidVariable(variableName)

            try {
                highlighter.addHighlight(
                    match.range.first,
                    match.range.last + 1,
                    if (isCorrect) {
                        VariableHighlightPainter(correctVariableColor)
                    } else {
                        VariableHighlightPainter(wrongVariableColor)
                    }
                )
            } catch (e: BadLocationException) {
                // 忽略位置错误
            }
        }
    }

    private fun updateTooltip(x: Int, y: Int) {
        try {
            val offset = viewToModel2D(java.awt.Point(x, y))
            val text = document.getText(0, document.length)

            val match = variableRegex.findAll(text).firstOrNull {
                offset in it.range
            }

            if (match != null) {
                val variableName = match.groupValues[1]
                toolTipText = tooltipProvider(variableName)
            } else {
                toolTipText = null
            }
        } catch (e: Exception) {
            toolTipText = null
        }
    }

    /**
     * 自定义高亮绘制器
     */
    private class VariableHighlightPainter(color: Color) : DefaultHighlighter.DefaultHighlightPainter(color) {
        override fun paintLayer(
            g: Graphics,
            offs0: Int,
            offs1: Int,
            bounds: Shape,
            c: javax.swing.text.JTextComponent,
            view: javax.swing.text.View
        ): Shape? {
            // 可以自定义绘制逻辑，比如添加圆角等
            return super.paintLayer(g, offs0, offs1, bounds, c, view)
        }
    }
}