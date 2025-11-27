#include <jni.h>
#include <android/log.h>
#include <map>
#include <string>
#include <cstring>
#include <cmath>
#include <algorithm>

#define LOG_TAG "NativeFilter_CPP"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Using std::fmax and std::fmin for clamping
#define CLAMP(x, min_val, max_val) std::fmax(min_val, std::fmin(max_val, x))

// --- FILTER HEADERS ---
#include "filters/BlueArchitecture.hpp"
#include "filters/HardBoost.hpp"
#include "filters/LongBeachMorning.hpp"
#include "filters/LushGreen.hpp"
#include "filters/MagicHour.hpp"
#include "filters/NaturalBoost.hpp"
#include "filters/OrangeAndBlue.hpp"
#include "filters/SoftBlackAndWhite.hpp"
#include "filters/Waves.hpp"
#include "filters/BlueHour.hpp"
#include "filters/ColdChrome.hpp"
#include "filters/CrispAutumn.hpp"
#include "filters/DarkAndSomber.hpp"

// --- GLOBAL CONSTANTS and TYPEDEFS ---
static const int LUT_DIM = 33;
// FIXED: Correctly defined as a pointer to a 4D array: [dim][dim][dim][3]
using LutDataPointer = const float (*)[LUT_DIM][LUT_DIM][LUT_DIM][3];

// --- GLOBAL STATE ---
static LutDataPointer gCurrentLUT = nullptr;
static std::map<std::string, LutDataPointer> gFilterMap;


// ----------------------------------------------------------------------
// C++ Utility: Trilinear Interpolation
// ----------------------------------------------------------------------
static void apply_lut(float r_in, float g_in, float b_in, float r_out[3]) {
    if (!gCurrentLUT) {
        r_out[0] = r_in; r_out[1] = g_in; r_out[2] = b_in;
        return;
    }

    float r_coord = r_in * (LUT_DIM - 1);
    float g_coord = g_in * (LUT_DIM - 1);
    float b_coord = b_in * (LUT_DIM - 1);

    int x = (int)r_coord; int y = (int)g_coord; int z = (int)b_coord;
    float x_d = r_coord - x; float y_d = g_coord - y; float z_d = b_coord - z;

    int x1 = std::min(x + 1, LUT_DIM - 1);
    int y1 = std::min(y + 1, LUT_DIM - 1);
    int z1 = std::min(z + 1, LUT_DIM - 1);

    for (int i = 0; i < 3; ++i) {
        // FIXED: The `(*gCurrentLUT)` syntax is now correct because LutDataPointer is a pointer to the entire array.
        float c00 = (*gCurrentLUT)[z][y][x][i] * (1.0f - x_d) + (*gCurrentLUT)[z][y][x1][i] * x_d;
        float c10 = (*gCurrentLUT)[z][y1][x][i] * (1.0f - x_d) + (*gCurrentLUT)[z][y1][x1][i] * x_d;
        float c01 = (*gCurrentLUT)[z1][y][x][i] * (1.0f - x_d) + (*gCurrentLUT)[z1][y][x1][i] * x_d;
        float c11 = (*gCurrentLUT)[z1][y1][x][i] * (1.0f - x_d) + (*gCurrentLUT)[z1][y1][x1][i] * x_d;

        float c0 = c00 * (1.0f - y_d) + c10 * y_d;
        float c1 = c01 * (1.0f - y_d) + c11 * y_d;

        float c = c0 * (1.0f - z_d) + c1 * z_d;

        r_out[i] = CLAMP(c, 0.0f, 1.0f);
    }
}


// ----------------------------------------------------------------------
// JNI: Frame Processing
// ----------------------------------------------------------------------

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_nmerza_cameraapp_NativeFilter_processFrame(
        JNIEnv* env,
        jobject /* this */,
        jobject yBuffer,
        jobject uBuffer,
        jobject vBuffer,
        jint width,
        jint height,
        jint strideY,
        jint strideUV,
        jint pixelStrideUV) // IMPORTANT: This new parameter is needed for robust processing.
{
    uint8_t* y_ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    uint8_t* u_ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    uint8_t* v_ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuffer));

    if (!y_ptr || !u_ptr || !v_ptr) return nullptr;

    const int num_pixels = width * height;
    const int output_size = num_pixels * 4;
    uint8_t* rgba_out = new uint8_t[output_size];

    float r_norm, g_norm, b_norm;
    float filtered_rgb[3];

    for (int j = 0; j < height; ++j) {
        for (int i = 0; i < width; ++i) {
            int y_index = j * strideY + i;
            int uv_x = i / 2;
            int uv_y = j / 2;

            // FIXED: This logic now handles both Planar (I420) and Semi-Planar (NV21/NV12) YUV formats.
            int uv_index = uv_y * strideUV + uv_x * pixelStrideUV;

            float Y = (float)(y_ptr[y_index] & 0xFF);
            float U = (float)(u_ptr[uv_index] & 0xFF);
            float V = (float)(v_ptr[uv_index] & 0xFF);

            float C = Y - 16.0f;
            float D = U - 128.0f;
            float E = V - 128.0f;

            // FIXED: Added +128.0f for rounding before division to prevent image darkening.
            float r_val = (298.0f * C + 409.0f * E + 128.0f) / 256.0f;
            float g_val = (298.0f * C - 100.0f * D - 208.0f * E + 128.0f) / 256.0f;
            float b_val = (298.0f * C + 516.0f * D + 128.0f) / 256.0f;

            // Normalize to 0.0-1.0 for the LUT
            r_norm = CLAMP(r_val / 255.0f, 0.0f, 1.0f);
            g_norm = CLAMP(g_val / 255.0f, 0.0f, 1.0f);
            b_norm = CLAMP(b_val / 255.0f, 0.0f, 1.0f);

            apply_lut(r_norm, g_norm, b_norm, filtered_rgb);

            uint8_t R = static_cast<uint8_t>(std::round(filtered_rgb[0] * 255.0f));
            uint8_t G = static_cast<uint8_t>(std::round(filtered_rgb[1] * 255.0f));
            uint8_t B = static_cast<uint8_t>(std::round(filtered_rgb[2] * 255.0f));

            int output_index = (j * width + i) * 4;
            rgba_out[output_index + 0] = R;
            rgba_out[output_index + 1] = G;
            rgba_out[output_index + 2] = B;
            rgba_out[output_index + 3] = 255;
        }
    }

    jbyteArray output_array = env->NewByteArray(output_size);
    if (output_array == nullptr) {
        delete[] rgba_out;
        return nullptr;
    }

    env->SetByteArrayRegion(output_array, 0, output_size, reinterpret_cast<jbyte*>(rgba_out));
    delete[] rgba_out;

    return output_array;
}


// ----------------------------------------------------------------------
// JNI: Filter Management
// ----------------------------------------------------------------------

void initialize_lut_map() {
    gFilterMap["None"] = nullptr;
    gFilterMap["Blue Architecture"] = &BlueArchitecture;
    gFilterMap["HardBoost"] = &HardBoost;
    gFilterMap["LongBeachMorning"] = &LongBeachMorning;
    gFilterMap["LushGreen"] = &LushGreen;
    gFilterMap["MagicHour"] = &MagicHour;
    gFilterMap["NaturalBoost"] = &NaturalBoost;
    gFilterMap["OrangeAndBlue"] = &OrangeAndBlue;
    gFilterMap["SoftBlackAndWhite"] = &SoftBlackAndWhite;
    gFilterMap["Waves"] = &Waves;
    gFilterMap["BlueHour"] = &BlueHour;
    gFilterMap["ColdChrome"] = &ColdChrome;
    gFilterMap["CrispAutumn"] = &CrispAutumn;
    gFilterMap["DarkAndSomber"] = &DarkAndSomber;

    LOGD("Initialized %zu filters in the LUT map.", gFilterMap.size());
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    initialize_lut_map();
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nmerza_cameraapp_NativeFilter_setActiveFilter(
        JNIEnv* env,
        jobject /* this */,
        jstring filterName_) {

    const char *filterNameCStr = env->GetStringUTFChars(filterName_, 0);
    std::string filterName(filterNameCStr);
    env->ReleaseStringUTFChars(filterName_, filterNameCStr);

    auto it = gFilterMap.find(filterName);

    if (it != gFilterMap.end()) {
        gCurrentLUT = it->second;
        LOGD("Switched filter to: %s", filterName.c_str());
        return JNI_TRUE;
    } else {
        LOGD("Error: Filter not found: %s", filterName.c_str());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nmerza_cameraapp_NativeFilter_loadLut(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray lutData,
        jint size) {
    LOGD("loadLut is unused. Using static .hpp files.");
    return JNI_TRUE;
}
