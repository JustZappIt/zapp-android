package co.electriccoin.zcash.ui.common.extension

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.reflect.KMutableProperty0

fun CoroutineScope.launchSingle(
    jobProperty: KMutableProperty0<Job?>,
    block: suspend CoroutineScope.() -> Unit
): Job? {
    if (jobProperty.get()?.isActive == true) return null
    return launch(block = block).also { jobProperty.set(it) }
}
