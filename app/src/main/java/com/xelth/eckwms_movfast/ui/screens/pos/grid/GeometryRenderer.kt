package com.xelth.eckwms_movfast.ui.screens.pos.grid

/**
 * Represents a renderable cell in final grid structure.
 */
data class RenderCell(
    val id: String,
    val type: String,
    val logicalPosition: Pair<Int, Int>,
    var content: Any?,
    var cssPosition: Position,
    var geometryMetadata: Map<String, Any> = emptyMap()
)

/**
 * Transforms a logical content grid into a list of renderable cells with physical positions.
 */
class GeometryRenderer(private val config: GridConfig) {

    fun renderContentGrid(contentGrid: ContentGrid): List<RenderCell> {
        val renderCells = mutableListOf<RenderCell>()
        val allSlots = contentGrid.contentSlots.filter { it.slotType != SlotType.DEAD }

        for (slot in allSlots) {
            val metadata = mutableMapOf<String, Any>("slotType" to slot.slotType)

            val (type, content) = when (slot.slotType) {
                SlotType.FULL -> {
                    if (slot.isEmpty) {
                        metadata["priority"] = 0
                        "empty" to mapOf("type" to "empty", "disabled" to true)
                    } else {
                        metadata["priority"] = slot.priority
                        "full" to slot.content
                    }
                }
                SlotType.HALF_LEFT, SlotType.HALF_RIGHT -> {
                    if (!slot.isEmpty && slot.content != null) {
                        // Content placed explicitly (e.g. EXIT button)
                        metadata["priority"] = slot.priority
                        "full" to slot.content
                    } else {
                        metadata["isSystemButton"] = true
                        "system" to mapOf("type" to "system", "side" to slot.slotType)
                    }
                }
                else -> "dead" to null
            }

            val renderCell = RenderCell(
                id = slot.id,
                type = type,
                logicalPosition = Pair(slot.row, slot.col),
                content = content,
                cssPosition = virtualToPhysical(slot.row, slot.col, config),
                geometryMetadata = metadata
            )
            renderCells.add(renderCell)
        }
        return renderCells
    }
}
