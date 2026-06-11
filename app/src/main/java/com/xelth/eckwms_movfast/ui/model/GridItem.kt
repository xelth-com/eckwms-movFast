package com.xelth.eckwms_movfast.ui.model

sealed interface GridItem {
    val id: String
}

data class CategoryItem(
    val categoryId: Int,
    val name: String
) : GridItem {
    override val id: String get() = "category_$categoryId"
}

data class ProductItem(
    val productId: Int,
    val name: String,
    val price: Double
) : GridItem {
    override val id: String get() = "product_$productId"
}

data class SystemButton(
    override val id: String,
    val label: String,
    val action: () -> Unit
) : GridItem
