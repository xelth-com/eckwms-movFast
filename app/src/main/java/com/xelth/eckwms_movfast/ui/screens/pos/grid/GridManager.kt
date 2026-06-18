package com.xelth.eckwms_movfast.ui.screens.pos.grid

import android.util.Log

/**
 * Priority constants for content placement.
 */
object PRIORITIES {
    // Fixed system buttons (Scan / Photo / 🎤) — outrank ALL content so their
    // reserved hex slots are never evicted by mode content. See placeSystemButtons.
    const val SYSTEM_FIXED = 200
    const val TABLE_BUTTON = 95
    const val PAYMENT_BUTTON = 95
    const val PINPAD_BUTTON = 95
    const val SCAN_BUTTON = 95
    const val RESTOCK_BUTTON = 95
    const val AI_BUTTON = 95
    const val SETTINGS_BUTTON = 95
    const val NEW_PRODUCTS = 82
    const val CATEGORY_PRIORITY = 80
    const val CATEGORY_NAVIGATION = 70
    const val DEFAULT = 50
    const val MIN = 1
}

/**
 * Main orchestrator for hexagonal grid UI.
 */
class GridManager(val config: GridConfig, val dimensions: Pair<Int, Int>, val layoutType: String) {
    val contentGrid = ContentGrid(dimensions.first, dimensions.second, layoutType)
    private val geometryRenderer = GeometryRenderer(config)
    private var renderCache: List<RenderCell>? = null
    private var isDirty = true

    fun clearAndReset() {
        contentGrid.clearAllContent()
        markDirty()
    }

    fun placeItems(contentItems: List<Any>, priority: Int, maxItems: Int = Int.MAX_VALUE) {
        if (contentItems.isEmpty()) return

        val allUsableSlots = contentGrid.getUsableSlots()
        var itemsPlaced = 0
        val evictedItems = mutableListOf<Pair<Any?, Int>>()

        for (item in contentItems) {
            if (itemsPlaced >= maxItems) break

            val targetSlot = allUsableSlots.firstOrNull { canPlaceAt(it, priority) }

            if (targetSlot != null) {
                if (!targetSlot.isEmpty && targetSlot.priority < priority) {
                    evictedItems.add(Pair(targetSlot.content, targetSlot.priority))
                }
                targetSlot.setContent(item, priority)
                itemsPlaced++
            }
        }

        if (evictedItems.isNotEmpty()) {
            val emptySlots = contentGrid.getUsableEmptySlots()
            var evictedIndex = 0
            for (slot in emptySlots) {
                if (evictedIndex >= evictedItems.size) break
                val (content, p) = evictedItems[evictedIndex]
                slot.setContent(content, p)
                evictedIndex++
            }
        }

        markDirty()
    }

    private fun canPlaceAt(slot: ContentSlot?, newPriority: Int): Boolean {
        if (slot == null || !slot.isUsable) return false
        return slot.isEmpty || slot.priority < newPriority
    }

    fun placeSystemElements(systemElements: List<SystemElement>, isLeftHanded: Boolean = false) {
        systemElements.forEach { element ->
            val adjustedCol = if (isLeftHanded && (element.type == "payment" || element.type == "table")) {
                when {
                    element.col >= contentGrid.cols - 2 -> 0
                    element.col >= contentGrid.cols - 3 -> 1
                    else -> element.col
                }
            } else {
                element.col
            }

            val targetSlot = contentGrid.getSlot(element.row, adjustedCol)
            if (canPlaceAt(targetSlot, element.priority)) {
                targetSlot?.setContent(element.content, element.priority)
            } else {
                val nearestSlot = findNearestEmptySlot(element.row, adjustedCol)
                nearestSlot?.setContent(element.content, element.priority)
            }
        }
        markDirty()
    }

    private fun findNearestEmptySlot(targetRow: Int, targetCol: Int): ContentSlot? {
        val emptySlots = contentGrid.getUsableEmptySlots()
        if (emptySlots.isEmpty()) return null

        return emptySlots.minByOrNull {
            calculateDistance(targetRow, targetCol, it.row, it.col, config)
        }
    }

    fun getRenderStructure(): List<RenderCell> {
        if (renderCache != null && !isDirty) {
            return renderCache!!
        }
        renderCache = geometryRenderer.renderContentGrid(contentGrid)
        isDirty = false
        return renderCache!!
    }

    private fun markDirty() {
        isDirty = true
        renderCache = null
    }
}

data class SystemElement(
    val row: Int,
    val col: Int,
    val content: Any,
    val priority: Int,
    val type: String
)
