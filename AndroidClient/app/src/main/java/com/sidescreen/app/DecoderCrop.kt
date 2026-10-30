package com.sidescreen.app

data class DecoderCrop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun effectiveSize(width: Int, height: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0 || left < 0 || top < 0 || right < left || bottom < top || right >= width || bottom >= height) return null
        return (right - left + 1) to (bottom - top + 1)
    }
}
