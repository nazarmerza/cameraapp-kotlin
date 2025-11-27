package com.nmerza.cameraapp

class NativeFilter {
    companion object {
        init {
            // Load the C++ library named 'camera_filter_lib'
            System.loadLibrary("cameraapp-native")
        }
    }

    // Function to process YUV data and return an RGBA byte array.
    external fun processFrame(
        yBuffer: java.nio.ByteBuffer,
        uBuffer: java.nio.ByteBuffer,
        vBuffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        strideY: Int,
        strideUV: Int,
        pixelStrideUV: Int
    ): ByteArray

    // Placeholder for LUT loading function.
    external fun loadLut(lutData: ByteArray, size: Int): Boolean

    // New function to switch the active filter by name
    external fun setActiveFilter(filterName: String): Boolean
}
