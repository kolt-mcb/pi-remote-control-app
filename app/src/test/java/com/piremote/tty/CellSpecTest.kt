package com.piremote.tty

import org.junit.Assert.assertEquals
import org.junit.Test

/** OSC 1337 dimension parsing must clamp hostile values to Auto, not pass huge
 *  numbers into Compose layout (which throws on oversize Constraints). */
class CellSpecTest {
    @Test fun auto_and_blank() {
        assertEquals(CellSpec.Auto, CellSpec.parse(null))
        assertEquals(CellSpec.Auto, CellSpec.parse(""))
        assertEquals(CellSpec.Auto, CellSpec.parse("auto"))
    }

    @Test fun valid_values_parse() {
        assertEquals(CellSpec.Cells(40), CellSpec.parse("40"))
        assertEquals(CellSpec.Pixels(200), CellSpec.parse("200px"))
        assertEquals(CellSpec.Percent(50), CellSpec.parse("50%"))
    }

    @Test fun oversize_pixels_clamped_to_auto() {
        assertEquals(CellSpec.Auto, CellSpec.parse("10000000px"))
    }

    @Test fun oversize_cells_clamped_to_auto() {
        assertEquals(CellSpec.Auto, CellSpec.parse("9999999"))
    }

    @Test fun negative_and_zero_clamped_to_auto() {
        assertEquals(CellSpec.Auto, CellSpec.parse("-5"))
        assertEquals(CellSpec.Auto, CellSpec.parse("0"))
        assertEquals(CellSpec.Auto, CellSpec.parse("-10px"))
    }

    @Test fun percent_over_100_clamped_to_auto() {
        assertEquals(CellSpec.Auto, CellSpec.parse("500%"))
    }
}
