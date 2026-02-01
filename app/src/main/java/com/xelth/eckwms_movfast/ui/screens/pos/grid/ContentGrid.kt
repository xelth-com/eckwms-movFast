package com.xelth.eckwms_movfast.ui.screens.pos.grid

enum class SlotType { FULL, DEAD }

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

class ContentGrid(
    val rows: Int,
    val cols: Int,
    val layoutType: String = "symmetrical"
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
                val isUsable = slotType == SlotType.FULL
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
