package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local cache of CRM entities (company/person/opportunity) fetched from the
 * server after a SmartTag scan. Enables offline browsing of previously
 * scanned entities in CrmEntityScreen.
 */
@Entity(tableName = "crm_entities")
data class CrmEntityEntity(
    @PrimaryKey val id: String,          // entity UUID (SmartTag payload)
    val entityType: String,              // company | person | opp
    val name: String,                    // display name from server
    val status: String,                  // last known status
    val dataJson: String,                // full server JSON for detail display
    val fetchedAt: Long                  // epoch millis of last successful fetch
)
