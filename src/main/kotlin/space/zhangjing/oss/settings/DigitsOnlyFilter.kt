package space.zhangjing.oss.settings

import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter

class DigitsOnlyFilter : DocumentFilter() {

    override fun insertString(
        fb: FilterBypass,
        offset: Int,
        string: String?,
        attr: AttributeSet?
    ) {
        if (string != null && string.matches(Regex("\\d+"))) {
            super.insertString(fb, offset, string, attr)
        }
    }

    override fun replace(
        fb: FilterBypass,
        offset: Int,
        length: Int,
        text: String?,
        attrs: AttributeSet?
    ) {
        if (text != null && text.matches(Regex("\\d*"))) {
            super.replace(fb, offset, length, text, attrs)
        }
    }
}
