package com.xelth.eckwms_movfast.ui.screens.pos.grid

/**
 * Ported from ecKasseAnd
 * Defines slot types in hexagonal grid layout
 */
enum class SlotType { FULL, HALF_LEFT, HALF_RIGHT, DEAD }

data class ContentSlot(
    val row: Int,
    val col: Int,
    val isUsable: Boolean = true,
    var content: Any? = null,
    var isEmpty: Boolean = true,
    var priority: Int = 0,
    val slotType: SlotType
) {
    val id: String = "slot-$row-$col"

    fun setContent(newContent: Any?, newPriority: Int) {
        content = newContent
        isEmpty = false
        priority = newPriority
    }

    fun clearContent() {
        content = null
        isEmpty = true
        priority = 0
    }
}

/**
 * Hexagonal content grid manager
 * Ported from ecKasseAnd
 *
 * Pattern:
 * - Even rows (0,2,4): HALF_LEFT (col=0), FULL (col=1,3,5), DEAD (col=2,4,6)
 * - Odd rows (1,3,5): FULL (col=0,2,4), DEAD (col=1,3,5), HALF_RIGHT (last col)
 */
class ContentGrid(
    val rows: Int,
    val cols: Int,
    val layoutType: String = "asymmetrical"
) {
    val slots: List<List<ContentSlot>>
    val contentSlots: List<ContentSlot>

    init {
        val mutableSlots = mutableListOf<List<ContentSlot>>()
        val mutableContentSlots = mutableListOf<ContentSlot>()
        for (row in 0 until rows) {
            val rowSlots = mutableListOf<ContentSlot>()
            for (col in 0 until cols) {
                val slotType = determineSlotType(row, col, layoutType, cols)
                val isUsable = slotType == SlotType.FULL || slotType == SlotType.HALF_LEFT || slotType == SlotType.HALF_RIGHT
                val slot = ContentSlot(row, col, isUsable, slotType = slotType)
                rowSlots.add(slot)
                mutableContentSlots.add(slot)
            }
            mutableSlots.add(rowSlots)
        }
        slots = mutableSlots
        contentSlots = mutableContentSlots
    }

    private fun determineSlotType(row: Int, col: Int, layoutType: String, totalCols: Int): SlotType {
        return when (layoutType) {
            "asymmetrical" -> {
                if (row % 2 == 0) { // Even rows
                    when {
                        col == 0 -> SlotType.HALF_LEFT
                        col % 2 == 1 -> SlotType.FULL
                        else -> SlotType.DEAD
                    }
                } else { // Odd rows
                    when {
                        col == totalCols - 1 -> SlotType.HALF_RIGHT
                        col % 2 == 0 -> SlotType.FULL
                        else -> SlotType.DEAD
                    }
                }
            }
            else -> { // Symmetrical default
                val isDeadZone = (row % 2) == (col % 2)
                if (isDeadZone) SlotType.DEAD else SlotType.FULL
            }
        }
    }

    fun getSlot(row: Int, col: Int): ContentSlot? {
        return if (row in 0 until rows && col in 0 until cols) {
            slots[row][col]
        } else {
            null
        }
    }

    fun getUsableEmptySlots(): List<ContentSlot> {
        return contentSlots.filter { it.slotType == SlotType.FULL && it.isEmpty }.sortedWith(compareBy({ it.row }, { it.col }))
    }

    fun getUsableFilledSlots(): List<ContentSlot> {
        return contentSlots.filter { it.slotType == SlotType.FULL && !it.isEmpty }
    }

    fun getUsableSlots(): List<ContentSlot> {
        return contentSlots.filter { it.slotType == SlotType.FULL }.sortedWith(compareBy({ it.row }, { it.col }))
    }

    fun placeContentAt(row: Int, col: Int, content: Any?, priority: Int): Boolean {
        val slot = getSlot(row, col)
        if (slot != null && slot.isUsable && (slot.isEmpty || priority > slot.priority)) {
            slot.setContent(content, priority)
            return true
        }
        return false
    }

    fun clearAllContent() {
        contentSlots.forEach { it.clearContent() }
    }
}
