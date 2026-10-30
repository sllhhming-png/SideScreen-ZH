package com.sidescreen.app

/** Each callback closure captures one immutable filtering policy and this setup's mutable native identity. */
class VendorCallbackOwner(private val filtered: Boolean) {
    @Volatile private var bound: Any? = null
    fun bind(codec: Any) { if (filtered) bound = codec }
    fun clear() { bound = null }
    fun accepts(codec: Any): Boolean = !filtered || codec === bound
}
