package co.electriccoin.zcash.ui.common.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class FirebaseTopicSubscriptionClient(
    private val context: Context,
) : TopicSubscriptionClient {
    override suspend fun subscribe(topic: String) {
        requireFirebase().subscribeToTopic(topic).awaitCompletion()
    }

    override suspend fun unsubscribe(topic: String) {
        requireFirebase().unsubscribeFromTopic(topic).awaitCompletion()
    }

    private fun requireFirebase(): FirebaseMessaging {
        if (FirebaseApp.getApps(context).isEmpty()) {
            checkNotNull(FirebaseApp.initializeApp(context)) { "Firebase is not configured" }
        }
        return FirebaseMessaging.getInstance()
    }
}

private suspend fun com.google.android.gms.tasks.Task<Void>.awaitCompletion() =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            val error = task.exception
            if (task.isSuccessful) {
                continuation.resume(Unit)
            } else {
                continuation.resumeWithException(error ?: IllegalStateException("Firebase topic operation failed"))
            }
        }
    }
