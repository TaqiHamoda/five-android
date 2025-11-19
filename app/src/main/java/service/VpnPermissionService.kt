/*
 * This file is part of Blokada.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright © 2021 Blocka AB. All rights reserved.
 *
 * @author Karol Gusak (karol@blocka.net)
 */

package service

import android.app.Activity
import android.net.VpnService
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import utils.Logger
import kotlin.coroutines.resume

object VpnPermissionService {

    private val log = Logger("VpnPerm")
    private val context = ContextService

    private var permissionContinuation: CancellableContinuation<Boolean>? = null
        @Synchronized set
        @Synchronized get

    fun hasPermission(): Boolean {
        return VpnService.prepare(context.requireContext()) == null
    }

    suspend fun askPermission(): Boolean {
        return suspendCancellableCoroutine { cont ->
            permissionContinuation = cont
            log.w("Asking for VPN permission")
            val activity = context.requireContext()
            if (activity !is Activity) {
                log.e("No activity context available")
                cont.resume(false)
                return@suspendCancellableCoroutine
            }

            VpnService.prepare(activity)?.let { intent ->
                activity.startActivityForResult(intent, 0)
            } ?: cont.resume(true)

            cont.invokeOnCancellation {
                permissionContinuation = null
                log.w("VPN permission request cancelled.")
            }
        }
    }

    fun resultReturned(resultCode: Int) {
        if (resultCode == -1) {
            permissionContinuation?.resume(true)
        } else {
            log.w("VPN permission not granted, returned code $resultCode")
            permissionContinuation?.resume(false)
        }
        permissionContinuation = null
    }

}