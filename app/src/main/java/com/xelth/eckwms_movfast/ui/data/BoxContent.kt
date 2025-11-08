package com.xelth.eckwms_movfast.ui.data

import kotlinx.serialization.Serializable

@Serializable
data class BoxDetails(val expectedItems: Int)

sealed class BoxDetailsResult {
    data class Success(val details: BoxDetails) : BoxDetailsResult()
    data class Error(val message: String) : BoxDetailsResult()
}

data class BoxContentStatus(val total: Int, var scanned: Int)
