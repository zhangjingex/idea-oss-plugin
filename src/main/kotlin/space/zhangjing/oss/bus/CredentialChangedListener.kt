package space.zhangjing.oss.bus

import space.zhangjing.oss.entity.Credential

interface CredentialChangedListener {
    fun credentialsChanged(credential: Credential) {
        credentialsChanged()
    }

    fun credentialsAdded(credential: Credential) {
        credentialsChanged()
    }

    fun credentialsDeleted(credential: Credential) {
        credentialsChanged()
    }

    fun credentialsChanged() {

    }
}

