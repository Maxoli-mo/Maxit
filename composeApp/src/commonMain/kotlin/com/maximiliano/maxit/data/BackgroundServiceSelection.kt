package com.maximiliano.maxit.data

internal fun selectBackgroundServiceInstance(
    instances: List<ServiceInstance>,
    requestedId: String? = null,
    preferRemote: Boolean = false,
    isUsable: (ServiceInstance) -> Boolean,
): ServiceInstance? {
    // Explicit targets report their own credential error; automatic targets skip signed-out accounts.
    requestedId?.let { id -> instances.firstOrNull { it.instanceId == id }?.let { return it } }
    val usable = instances.filter(isUsable)
    return if (preferRemote) {
        usable.firstOrNull { !Service.fromId(it.serviceId).isOnDevice } ?: usable.firstOrNull()
    } else {
        usable.firstOrNull()
    }
}
