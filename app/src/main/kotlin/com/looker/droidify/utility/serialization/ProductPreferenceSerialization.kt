package com.looker.droidify.utility.serialization

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.looker.droidify.model.ProductPreference
import com.looker.droidify.utility.common.extension.forEachKey

fun ProductPreference.serialize(generator: JsonGenerator) {
    generator.writeBooleanField("ignoreUpdates", ignoreUpdates)
    generator.writeNumberField("ignoreVersionCode", ignoreVersionCode)
    if (preferredRepositoryId > 0L) {
        generator.writeNumberField("preferredRepositoryId", preferredRepositoryId)
    }
    if (note.isNotEmpty()) {
        generator.writeStringField("note", note)
    }
}

fun JsonParser.productPreference(): ProductPreference {
    var ignoreUpdates = false
    var ignoreVersionCode = 0L
    var preferredRepositoryId = 0L
    var note = ""
    forEachKey {
        when {
            it.boolean("ignoreUpdates") -> ignoreUpdates = valueAsBoolean
            it.number("ignoreVersionCode") -> ignoreVersionCode = valueAsLong
            it.number("preferredRepositoryId") -> preferredRepositoryId = valueAsLong
            it.string("note") -> note = valueAsString
            else -> skipChildren()
        }
    }
    return ProductPreference(ignoreUpdates, ignoreVersionCode, preferredRepositoryId, note)
}
