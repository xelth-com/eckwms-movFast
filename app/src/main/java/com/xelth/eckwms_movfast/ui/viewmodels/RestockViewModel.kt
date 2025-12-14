package com.xelth.eckwms_movfast.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xelth.eckwms_movfast.EckwmsApp
import kotlinx.coroutines.launch

data class RestockItem(val barcode: String, var quantity: Int, var note: String)

class RestockViewModel(application: Application) : AndroidViewModel(application) {
    private val _restockList = MutableLiveData<List<RestockItem>>(emptyList())
    val restockList: LiveData<List<RestockItem>> = _restockList

    private val repository = (application as EckwmsApp).repository
    // WorkflowEngine will be integrated here

    fun loadWorkflow() {
        // In a real app, this would load from assets or network
        // For now, it's a placeholder
    }

    fun triggerScan() {
        // This will be handled by the workflow engine
        // For now, simulate adding a new item
        val newList = _restockList.value.orEmpty().toMutableList()
        val newItem = RestockItem("SIM-BARCODE-${newList.size + 1}", 1, "")
        newList.add(newItem)
        _restockList.value = newList
    }

    fun submitOrder() {
        viewModelScope.launch {
            val list = _restockList.value.orEmpty()
            if (list.isNotEmpty()) {
                // This will be replaced by a call to repository.submitDocument
            }
        }
    }
}
