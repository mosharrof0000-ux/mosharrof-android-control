package org.mosharrof.androidcontrol

import javax.crypto.SecretKey

internal object KeyAliasPolicy {
    fun resolve(
        aliasExists: Boolean,
        existingKey: SecretKey?,
        unavailableMessage: String,
        createKey: () -> SecretKey
    ): SecretKey {
        if (aliasExists) {
            return existingKey
                ?: throw IllegalStateException(unavailableMessage)
        }
        return createKey()
    }
}
