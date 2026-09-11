package com.smartview.glassai.services.openclaw

/**
 * What OpenClawNodeService needs from a router: one suspend call per `node.invoke`.
 * OpenClawCommandRouter (Task 4) is the production implementation; tests use a fake.
 */
interface OpenClawCommandHandler {
    suspend fun handleCommand(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult
}
