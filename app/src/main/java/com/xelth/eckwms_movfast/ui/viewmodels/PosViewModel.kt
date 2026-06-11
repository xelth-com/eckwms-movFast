package com.xelth.eckwms_movfast.ui.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xelth.eckwms_movfast.core.domain.usecase.AddItemToOrderUseCase
import com.xelth.eckwms_movfast.core.domain.usecase.GetActiveOrderUseCase
import com.xelth.eckwms_movfast.core.domain.usecase.GetCompletedTransactionsUseCase
import com.xelth.eckwms_movfast.core.domain.usecase.RemoveItemFromOrderUseCase
import com.xelth.eckwms_movfast.debug.DebugAction
import com.xelth.eckwms_movfast.debug.DebugEventBus
import com.xelth.eckwms_movfast.ui.model.CategoryItem
import com.xelth.eckwms_movfast.ui.model.OrderLineItem
import com.xelth.eckwms_movfast.ui.model.Receipt
import com.xelth.eckwms_movfast.ui.model.ReceiptItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import androidx.compose.runtime.Immutable
import com.xelth.eckwms_movfast.ui.model.ProductItem
import com.xelth.eckwms_movfast.ui.screens.pos.grid.GridConfig
import com.xelth.eckwms_movfast.ui.screens.pos.grid.GridManager
import com.xelth.eckwms_movfast.ui.screens.pos.grid.PRIORITIES
import com.xelth.eckwms_movfast.ui.screens.pos.grid.RenderCell
import com.xelth.eckwms_movfast.ui.screens.pos.grid.SlotType
import com.xelth.eckwms_movfast.ui.screens.pos.grid.SystemElement
import android.util.Log
import com.xelth.eckwms_movfast.data.local.dao.ReferenceDao
import com.xelth.eckwms_movfast.data.local.entity.ProductEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.floor

class PosViewModel(
    private val getActiveOrderUseCase: GetActiveOrderUseCase,
    private val addItemToOrderUseCase: AddItemToOrderUseCase,
    private val removeItemFromOrderUseCase: RemoveItemFromOrderUseCase,
    private val getCompletedTransactionsUseCase: GetCompletedTransactionsUseCase,
    private val debugEventBus: DebugEventBus,
    private val referenceDao: ReferenceDao? = null
) : ViewModel() {

    companion object {
        private const val TAG = "ecKasseApp"
    }

    private val _allRenderCells = MutableStateFlow<List<RenderCell>>(emptyList())
    private val _mainRenderCells = MutableStateFlow<List<RenderCell>>(emptyList())
    val mainRenderCells = _mainRenderCells.asStateFlow()
    private val _overflowRightRenderCells = MutableStateFlow<List<RenderCell>>(emptyList())
    val overflowRightRenderCells = _overflowRightRenderCells.asStateFlow()
    private val _overflowLeftRenderCells = MutableStateFlow<List<RenderCell>>(emptyList())
    val overflowLeftRenderCells = _overflowLeftRenderCells.asStateFlow()

    private val _pageCount = MutableStateFlow(1)
    val pageCount = _pageCount.asStateFlow()

    // Real product data from Room (populated from ReferenceDao)
    // Categories are derived by grouping products by first letter of name
    private var categories = listOf<CategoryItem>()
    private var productsByCategory = mapOf<Int, List<ProductItem>>()

    // Fallback mock data used when the local product DB is empty
    private val fallbackCategories = List(50) { i -> CategoryItem(categoryId = i + 1, name = "Category ${i + 1}") }
    private val fallbackProducts = mapOf(
        1 to List(20) { i -> ProductItem(productId = i + 1, name = "Product ${i + 1}", price = (i + 1) * 2.5) },
        2 to List(15) { i -> ProductItem(productId = i + 21, name = "Beverage ${i + 1}", price = (i + 1) * 3.0) },
        3 to List(10) { i -> ProductItem(productId = i + 36, name = "Snack ${i + 1}", price = (i + 1) * 1.5) }
    )

    /** Currently active list of categories (real or fallback) */
    private val activeCategories: List<CategoryItem>
        get() = categories.ifEmpty { fallbackCategories }

    /** Currently active products map (real or fallback) */
    private val activeProducts: Map<Int, List<ProductItem>>
        get() = productsByCategory.ifEmpty { fallbackProducts }

    private val _containerWidth = MutableStateFlow(0.dp)
    private val _containerHeight = MutableStateFlow(0.dp)

    private val _userRequestedRows = MutableStateFlow(4)
    val userRequestedRows = _userRequestedRows.asStateFlow()

    private val _selectionAreaHeight = MutableStateFlow(320.dp)
    val selectionAreaHeight = _selectionAreaHeight.asStateFlow()

    private val _orderUiState = MutableStateFlow(OrderUiState())
    val orderUiState = _orderUiState.asStateFlow()

    private val _receiptsUiState = MutableStateFlow(ReceiptsUiState())
    val receiptsUiState = _receiptsUiState.asStateFlow()

    private val _contextMenuState = MutableStateFlow<ContextMenuState>(ContextMenuState.Hidden)
    val contextMenuState = _contextMenuState.asStateFlow()

    private val _selectionAreaState = MutableStateFlow<SelectionAreaState>(SelectionAreaState.Categories)
    val selectionAreaState = _selectionAreaState.asStateFlow()

    private val _currentRenderCells = MutableStateFlow<List<RenderCell>>(emptyList())
    val currentRenderCells = _currentRenderCells.asStateFlow()

    private val _isLeftHandedMode = MutableStateFlow(false)
    val isLeftHandedMode = _isLeftHandedMode.asStateFlow()

    var gridManager by mutableStateOf(createGridManager(GridConfig()))
        private set

    private var currentCategoryId: Int? = null

    init {
        // Load real products from Room if ReferenceDao is available
        if (referenceDao != null) {
            viewModelScope.launch {
                referenceDao.getAllProductsFlow()
                    .distinctUntilChanged()
                    .collect { productEntities ->
                        if (productEntities.isNotEmpty()) {
                            buildCatalogFromProducts(productEntities)
                            // Reload content with real data
                            loadContent()
                            updateCurrentRenderCells(
                                when (val state = _selectionAreaState.value) {
                                    is SelectionAreaState.Categories -> activeCategories
                                    is SelectionAreaState.Products -> state.products
                                }
                            )
                            Log.d(TAG, "Loaded ${productEntities.size} real products into ${categories.size} categories")
                        }
                    }
            }
        }

        viewModelScope.launch {
            combine(_containerWidth, _containerHeight, _userRequestedRows, _isLeftHandedMode) { width, height, rows, isLeftHanded ->
                Triple(width, height, rows)
            }.filter { it.first > 0.dp && it.second > 0.dp }
                .collect { (width, height, rows) ->
                    recalculateLayout(width, height, rows)
                }
        }

        viewModelScope.launch {
            _isLeftHandedMode.collect { isLeftHanded ->
                loadContent()
                updateCurrentRenderCells(
                    when (val state = _selectionAreaState.value) {
                        is SelectionAreaState.Categories -> activeCategories
                        is SelectionAreaState.Products -> state.products
                    }
                )
            }
        }

        viewModelScope.launch {
            debugEventBus.events.collect { intent ->
                handleDebugEvent(intent)
            }
        }

        viewModelScope.launch {
            getActiveOrderUseCase()
                .distinctUntilChanged()
                .collect { transaction ->
                    val orderItems = transaction?.items?.map { item ->
                        OrderLineItem(
                            itemId = item.itemId,
                            productId = item.productId,
                            productName = item.productName,
                            unitPrice = item.unitPrice,
                            quantity = item.quantity,
                            totalPrice = item.totalPrice,
                            formattedUnitPrice = String.format("%.2f", item.unitPrice),
                            formattedTotalPrice = String.format("%.2f", item.totalPrice)
                        )
                    } ?: emptyList()

                    _orderUiState.value = OrderUiState(
                        orderItems = orderItems.toImmutableList(),
                        totalAmount = transaction?.totalAmount ?: 0.0,
                        itemCount = transaction?.getItemCount() ?: 0
                    )
                }
        }

        viewModelScope.launch {
            getCompletedTransactionsUseCase()
                .distinctUntilChanged()
                .collect { transactions ->
                    val receipts = transactions.map { transaction ->
                        Receipt(
                            transactionId = transaction.transactionId,
                            createdAt = transaction.createdAt,
                            updatedAt = transaction.updatedAt,
                            items = transaction.items.map { item ->
                                ReceiptItem(
                                    itemId = item.itemId,
                                    productId = item.productId,
                                    productName = item.productName,
                                    unitPrice = item.unitPrice,
                                    quantity = item.quantity,
                                    totalPrice = item.totalPrice,
                                    formattedUnitPrice = String.format("%.2f", item.unitPrice),
                                    formattedTotalPrice = String.format("%.2f", item.totalPrice)
                                )
                            }.toImmutableList(),
                            totalAmount = transaction.totalAmount,
                            formattedTotalAmount = String.format("%.2f", transaction.totalAmount),
                            itemCount = transaction.getItemCount()
                        )
                    }

                    _receiptsUiState.value = ReceiptsUiState(
                        receipts = receipts.toImmutableList()
                    )
                }
        }
    }

    fun updateLayoutDimensions(width: Dp, height: Dp, density: Density) {
        if (_containerWidth.value != width || _containerHeight.value != height) {
            _containerWidth.value = width
            _containerHeight.value = height
        }
    }

    fun increaseRows() {
        _userRequestedRows.value = (_userRequestedRows.value + 1).coerceAtMost(12)
    }

    fun decreaseRows() {
        _userRequestedRows.value = (_userRequestedRows.value - 1).coerceAtLeast(1)
    }

    fun toggleHandMode() {
        _isLeftHandedMode.value = !_isLeftHandedMode.value
    }

    private fun createGridManager(config: GridConfig, rows: Int = 4, cols: Int = 7): GridManager {
        return GridManager(config, Pair(rows, cols), "asymmetrical")
    }

    private fun recalculateLayout(width: Dp, height: Dp, requestedRows: Int) {
        val containerWidth = width.value
        val containerHeight = height.value
        val buttonGap = 2f

        val virtualCols = 7

        val buttonWidth = containerWidth / 3.5f - buttonGap
        val buttonHeight = buttonWidth * (80f / 120f)

        val effectiveRowHeight = buttonHeight * 0.75f + buttonGap
        if (effectiveRowHeight <= 0) return

        val numRows = floor((containerHeight - buttonHeight * 0.25f) / effectiveRowHeight).toInt() + 1
        if (numRows <= 0) return

        val finalButtonWidth = buttonWidth
        val finalButtonHeight = buttonHeight

        val newConfig = GridConfig(
            cellWidth = finalButtonWidth.dp,
            cellHeight = finalButtonHeight.dp,
            buttonGap = buttonGap.dp,
            verticalOverlap = 0.75f
        )

        gridManager = createGridManager(newConfig, numRows.coerceAtLeast(1), 7)
        loadContent()

        _selectionAreaHeight.value = calculateHeightForRows(requestedRows, newConfig).dp
        updateVisibleContent(requestedRows)
    }

    private fun calculateHeightForRows(rows: Int, config: GridConfig): Float {
        if (rows <= 0) return 0f
        val buttonHeight = config.cellHeight.value
        val buttonGap = config.buttonGap.value
        val effectiveRowHeight = buttonHeight * config.verticalOverlap + buttonGap
        return (rows - 1) * effectiveRowHeight + buttonHeight
    }

    private fun updateVisibleContent(visibleRows: Int) {
        val slotsPerRow = 3
        val totalFullSlots = visibleRows * slotsPerRow

        val mainPanelCategories = activeCategories.take(totalFullSlots)
        val rightPanelCategories = activeCategories.drop(totalFullSlots).take(totalFullSlots)
        val leftPanelCategories = activeCategories.drop(totalFullSlots * 2).take(totalFullSlots)

        _mainRenderCells.value = createPanelWithContent(visibleRows, mainPanelCategories)
        _overflowRightRenderCells.value = if (rightPanelCategories.isNotEmpty()) {
            createPanelWithContent(visibleRows, rightPanelCategories)
        } else emptyList()
        _overflowLeftRenderCells.value = if (leftPanelCategories.isNotEmpty()) {
            createPanelWithContent(visibleRows, leftPanelCategories)
        } else emptyList()

        _pageCount.value = when {
            rightPanelCategories.isEmpty() -> 1
            leftPanelCategories.isEmpty() -> 2
            else -> 3
        }
    }

    private fun createPanelWithContent(visibleRows: Int, items: List<Any>): List<RenderCell> {
        if (items.isEmpty()) return emptyList()

        val tempGridManager = GridManager(gridManager.config, Pair(visibleRows, 7), "asymmetrical")
        tempGridManager.clearAndReset()

        when (items.first()) {
            is CategoryItem -> tempGridManager.placeItems(items as List<CategoryItem>, PRIORITIES.CATEGORY_NAVIGATION)
            is ProductItem -> tempGridManager.placeItems(items as List<ProductItem>, PRIORITIES.CATEGORY_NAVIGATION)
        }

        placeSystemButtons(tempGridManager)

        val allRenderCells = tempGridManager.getRenderStructure().filter { cell ->
            cell.logicalPosition.first < visibleRows
        }.map { cell ->
            val slotType = cell.geometryMetadata["slotType"] as? SlotType
            when {
                cell.type == "empty" && slotType == SlotType.FULL -> {
                    cell.copy(
                        content = mapOf("type" to "placeholder", "disabled" to true),
                        type = "placeholder"
                    )
                }
                slotType == SlotType.HALF_LEFT || slotType == SlotType.HALF_RIGHT -> {
                    val logicalSlot = determineLogicalSlot(cell.logicalPosition.first, cell.logicalPosition.second, visibleRows)
                    val customContent = getCustomHalfButtonContent(logicalSlot, items)
                    cell.copy(
                        content = customContent,
                        type = "system_half"
                    )
                }
                else -> cell
            }
        }

        return allRenderCells
    }

    private fun determineLogicalSlot(row: Int, col: Int, visibleRows: Int): LogicalSlot? {
        val isLeftSide = col == 0
        val isRightSide = col == 6

        val slot = when {
            (row == 0 || row == 1) && isLeftSide -> LogicalSlot.TOP_LEFT
            (row == 0 || row == 1) && isRightSide -> LogicalSlot.TOP_RIGHT
            row >= visibleRows - 2 && isLeftSide -> LogicalSlot.BOTTOM_LEFT
            row >= visibleRows - 2 && isRightSide -> LogicalSlot.BOTTOM_RIGHT
            isLeftSide -> LogicalSlot.MIDDLE_LEFT
            isRightSide -> LogicalSlot.MIDDLE_RIGHT
            else -> null
        }

        val hasHalfButton = (row % 2 == 0 && isLeftSide) || (row % 2 == 1 && isRightSide)

        if (!hasHalfButton) {
            return null
        }

        return slot
    }

    fun getCustomHalfButtonContent(logicalSlot: LogicalSlot?, items: List<Any>): Map<String, Any> {
        if (logicalSlot == null) {
            return createEmptyContent()
        }

        val isLeftHanded = _isLeftHandedMode.value

        return when (logicalSlot) {
            LogicalSlot.TOP_LEFT -> {
                if (isLeftHanded) createHomeContent() else createHandToggleContent()
            }
            LogicalSlot.TOP_RIGHT -> {
                if (isLeftHanded) createHandToggleContent() else createHomeContent()
            }
            LogicalSlot.BOTTOM_LEFT -> {
                if (isLeftHanded) createTimeContent() else createUserContent()
            }
            LogicalSlot.BOTTOM_RIGHT -> {
                if (isLeftHanded) createUserContent() else createTimeContent()
            }
            LogicalSlot.MIDDLE_LEFT, LogicalSlot.MIDDLE_RIGHT -> {
                createEmptyContent()
            }
        }
    }

    private fun createHandToggleContent(): Map<String, Any> {
        return mapOf("type" to "system", "subtype" to "hand_toggle", "label" to "", "action" to "toggle_hand_mode")
    }

    private fun createUserContent(): Map<String, Any> {
        return mapOf("type" to "system", "subtype" to "user", "label" to "👤", "action" to "user_menu")
    }

    private fun createTimeContent(): Map<String, Any> {
        return mapOf("type" to "system", "subtype" to "time", "label" to "⏰", "action" to "time_display")
    }

    private fun createEmptyContent(): Map<String, Any> {
        return mapOf("type" to "system", "subtype" to "empty", "label" to "", "action" to "none")
    }

    private fun createHomeContent(): Map<String, Any> {
        return mapOf("type" to "system", "subtype" to "home", "label" to "🏠", "action" to "home")
    }

    /**
     * Builds synthetic categories and product groups from real ProductEntity data.
     * Groups products by the first letter of their name to create browsable categories.
     */
    private fun buildCatalogFromProducts(productEntities: List<ProductEntity>) {
        // Group by first character of product name
        val grouped = productEntities
            .filter { it.active && it.listPrice > 0 }
            .groupBy { it.name.firstOrNull()?.uppercaseChar() ?: '#' }
            .entries
            .sortedBy { it.key }

        val newCategories = mutableListOf<CategoryItem>()
        val newProductsByCategory = mutableMapOf<Int, List<ProductItem>>()

        grouped.forEachIndexed { index, (letter, products) ->
            val categoryId = index + 1
            newCategories.add(CategoryItem(categoryId = categoryId, name = letter.toString()))
            newProductsByCategory[categoryId] = products.map { entity ->
                ProductItem(
                    productId = entity.id.hashCode().and(0x7FFFFFFF), // stable positive int from String id
                    name = entity.name,
                    price = entity.listPrice
                )
            }
        }

        categories = newCategories
        productsByCategory = newProductsByCategory
    }

    private fun loadContent() {
        gridManager.let {
            it.clearAndReset()
            when (val state = _selectionAreaState.value) {
                is SelectionAreaState.Categories -> {
                    it.placeItems(activeCategories, PRIORITIES.CATEGORY_NAVIGATION)
                    placeSystemButtons(it)
                    _allRenderCells.value = it.getRenderStructure()
                    updateVisibleContent(_userRequestedRows.value)
                    updateCurrentRenderCells(activeCategories)
                }
                is SelectionAreaState.Products -> {
                    it.placeItems(state.products, PRIORITIES.CATEGORY_NAVIGATION)
                    placeSystemButtons(it)
                    _allRenderCells.value = it.getRenderStructure()
                    updateVisibleContent(_userRequestedRows.value)
                    updateCurrentRenderCells(state.products)
                }
            }
        }
    }

    private fun placeSystemButtons(gridManager: GridManager) {
        val gridRows = gridManager.contentGrid.rows
        val gridCols = gridManager.contentGrid.cols
        val isLeftHanded = _isLeftHandedMode.value
        val visibleRows = _userRequestedRows.value

        val anchorRow = if (visibleRows > 8) gridRows - 1 else visibleRows - 1

        val systemElements = mutableListOf<SystemElement>()

        val systemButtons = listOf(
            Triple("Tisch", "table", PRIORITIES.TABLE_BUTTON),
            Triple("Pinpad", "pinpad", PRIORITIES.PINPAD_BUTTON),
            Triple("Bar", "payment", PRIORITIES.PAYMENT_BUTTON),
            Triple("Karte", "payment", PRIORITIES.PAYMENT_BUTTON)
        )

        systemButtons.forEachIndexed { index, (label, type, priority) ->
            val row = when (index) {
                0 -> (anchorRow - 0).coerceAtLeast(0).coerceAtMost(gridRows - 1)
                1 -> (anchorRow - 1).coerceAtLeast(0).coerceAtMost(gridRows - 1)
                2 -> (anchorRow - 0).coerceAtLeast(0).coerceAtMost(gridRows - 1)
                3 -> (anchorRow - 1).coerceAtLeast(0).coerceAtMost(gridRows - 1)
                else -> 0
            }

            val col = if (isLeftHanded) {
                if (index % 2 == 0) 0 else 1
            } else {
                if (index % 2 == 0) gridCols - 1 else gridCols - 2
            }

            systemElements.add(
                SystemElement(
                    row = row,
                    col = col,
                    content = mapOf(
                        "type" to "system",
                        "subtype" to "button",
                        "label" to label,
                        "action" to type
                    ),
                    priority = priority,
                    type = type
                )
            )
        }

        Log.d(TAG, "System Button Placement: ${systemElements.joinToString(", ") { element ->
            val contentMap = element.content as? Map<*, *>
            val label = contentMap?.get("label") as? String ?: "unknown"
            "$label -> (row=${element.row}, col=${element.col}, visibleRows=$visibleRows, anchorRow=$anchorRow)"
        }}")

        gridManager.placeSystemElements(systemElements, isLeftHanded)
    }

    fun onCategoryClick(categoryItem: CategoryItem) {
        currentCategoryId = categoryItem.categoryId
        val products = activeProducts[categoryItem.categoryId] ?: emptyList()
        _selectionAreaState.value = SelectionAreaState.Products(categoryItem.categoryId, products)
        updateCurrentRenderCells(products)
    }

    fun onProductClick(productItem: ProductItem) {
        viewModelScope.launch {
            val result = addItemToOrderUseCase(
                productId = productItem.productId,
                productName = productItem.name,
                unitPrice = productItem.price
            )

            if (result.isFailure) {
                result.exceptionOrNull()?.printStackTrace()
            }
        }
    }

    fun onBackToCategories() {
        currentCategoryId = null
        _selectionAreaState.value = SelectionAreaState.Categories
        updateCurrentRenderCells(activeCategories)
    }

    fun onSystemAction(action: String) {
        when (action) {
            "toggle_hand_mode" -> toggleHandMode()
            "user_menu" -> { /* User menu clicked */ }
            "time_display" -> { /* Time display clicked */ }
            "home" -> onBackToCategories()
            "table" -> { /* Table button clicked */ }
            "pinpad" -> { /* Pinpad button clicked */ }
            "payment" -> { /* Payment button clicked */ }
            "none" -> { /* Empty action */ }
            else -> { /* Unknown action */ }
        }
    }

    private fun updateCurrentRenderCells(items: List<Any>) {
        val visibleRows = _userRequestedRows.value
        val panelWithContent = createPanelWithContent(visibleRows, items)
        _currentRenderCells.value = panelWithContent
    }

    fun onOrderItemClick(itemId: String) {
        // Order item clicked
    }

    fun onOrderItemLongClick(itemId: String) {
        _contextMenuState.value = ContextMenuState.Visible(itemId)
    }

    fun hideContextMenu() {
        _contextMenuState.value = ContextMenuState.Hidden
    }

    fun onRemoveItemClick(itemId: String) {
        viewModelScope.launch {
            val result = removeItemFromOrderUseCase(itemId)
            if (result.isFailure) {
                result.exceptionOrNull()?.printStackTrace()
            }
            hideContextMenu()
        }
    }

    fun onEditItemClick(itemId: String) {
        hideContextMenu()
    }

    fun onReprintClick(transactionId: String) {
        // Reprint receipt - to be implemented with receipt printer
    }

    private fun handleDebugEvent(intent: android.content.Intent) {
        val command = intent.getStringExtra(DebugAction.EXTRA_COMMAND)
        Log.d(DebugAction.DEBUG_TAG, "PosViewModel handling debug event: $command")

        when (command) {
            DebugAction.COMMAND_SET_VISIBLE_ROWS -> {
                val rows = intent.getIntExtra(DebugAction.EXTRA_ROWS, -1)
                if (rows > 0) {
                    Log.d(DebugAction.DEBUG_TAG, "PosViewModel setting visible rows to: $rows")
                    _userRequestedRows.value = rows
                }
            }
        }
    }
}

enum class LogicalSlot {
    TOP_LEFT, TOP_RIGHT,
    MIDDLE_LEFT, MIDDLE_RIGHT,
    BOTTOM_LEFT, BOTTOM_RIGHT
}

@Immutable
data class OrderUiState(
    val orderItems: ImmutableList<OrderLineItem> = persistentListOf(),
    val totalAmount: Double = 0.0,
    val itemCount: Int = 0
)

@Immutable
data class ReceiptsUiState(
    val receipts: ImmutableList<Receipt> = persistentListOf()
)

sealed class SelectionAreaState {
    object Categories : SelectionAreaState()
    data class Products(val categoryId: Int, val products: List<ProductItem>) : SelectionAreaState()
}

sealed class ContextMenuState {
    object Hidden : ContextMenuState()
    data class Visible(val itemId: String) : ContextMenuState()
}
