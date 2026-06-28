package com.blueridge.parkwaynav.nav

import com.blueridge.parkwaynav.routing.BrpRoute
import kotlinx.coroutines.flow.MutableStateFlow

/** Holds the route currently being navigated so it survives screen transitions. */
object ActiveRoute {
    val current = MutableStateFlow<BrpRoute?>(null)
    fun set(route: BrpRoute?) { current.value = route }
    fun clear() { current.value = null }
}
