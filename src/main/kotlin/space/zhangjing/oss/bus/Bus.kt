package space.zhangjing.oss.bus

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.Topic


val CREDENTIAL_CHANGED_TOPIC: Topic<CredentialChangedListener> =
    Topic.create(
        "Credential settings changed",
        CredentialChangedListener::class.java
    )


fun <L : Any, T : Topic<L>> T.subscribe(
    parentDisposable: Disposable,
    handler: L
) {
    ApplicationManager.getApplication()
        .messageBus
        .connect(parentDisposable)
        .subscribe(this, handler)
}