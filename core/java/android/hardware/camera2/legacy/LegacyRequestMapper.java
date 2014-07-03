/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware.camera2.legacy;

import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.utils.ListUtils;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.android.internal.util.Preconditions.*;
import static android.hardware.camera2.CaptureRequest.*;

/**
 * Provide legacy-specific implementations of camera2 CaptureRequest for legacy devices.
 */
public class LegacyRequestMapper {
    private static final String TAG = "LegacyRequestMapper";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    /** The default normalized camera area spans the entire size of the preview viewport */
    private static final Camera.Area CAMERA_AREA_DEFAULT =
            new Camera.Area(
                    new Rect(/*left*/-1000, /*top*/-1000, /*right*/1000, /*bottom*/1000),
                    /*weight*/1);

    /**
     * Set the legacy parameters using the {@link LegacyRequest legacy request}.
     *
     * <p>The legacy request's parameters are changed as a side effect of calling this
     * method.</p>
     *
     * @param legacyRequest a non-{@code null} legacy request
     */
    public static void convertRequestMetadata(LegacyRequest legacyRequest) {
        CameraCharacteristics characteristics = legacyRequest.characteristics;
        CaptureRequest request = legacyRequest.captureRequest;
        Size previewSize = legacyRequest.previewSize;
        Camera.Parameters params = legacyRequest.parameters;

        /*
         * scaler.cropRegion
         */
        {
            Rect activeArraySize = characteristics.get(
                    CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Rect activeArraySizeOnly = new Rect(
                    /*left*/0, /*top*/0,
                    activeArraySize.width(), activeArraySize.height());

            Rect userCropRegion = request.get(SCALER_CROP_REGION);

            if (userCropRegion == null) {
                userCropRegion = activeArraySizeOnly;
            }

            if (VERBOSE) {
                Log.v(TAG, "convertRequestToMetadata - user crop region was " + userCropRegion);
            }

            Rect reportedCropRegion = new Rect();
            Rect previewCropRegion = new Rect();
            int zoomIndex = ParameterUtils.getClosestAvailableZoomCrop(params, activeArraySizeOnly,
                    previewSize, userCropRegion,
                    /*out*/reportedCropRegion, /*out*/previewCropRegion);

            if (VERBOSE) {
                Log.v(TAG, "convertRequestToMetadata - zoom calculated to: " +
                        "zoomIndex = " + zoomIndex +
                        ", reported crop region = " + reportedCropRegion +
                        ", preview crop region = " + previewCropRegion);
            }
            if (params.isZoomSupported()) {
                params.setZoom(zoomIndex);
            } else if (VERBOSE) {
                Log.v(TAG, "convertRequestToMetadata - zoom is not supported");
            }
        }


        /*
         * control.ae*
         */
        // control.aeAntibandingMode
        {
        String legacyMode;
            Integer antiBandingMode = request.get(CONTROL_AE_ANTIBANDING_MODE);
            if (antiBandingMode != null) {
                legacyMode = convertAeAntiBandingModeToLegacy(antiBandingMode);
            } else {
                legacyMode = ListUtils.listSelectFirstFrom(params.getSupportedAntibanding(),
                        new String[] {
                            Parameters.ANTIBANDING_AUTO,
                            Parameters.ANTIBANDING_OFF,
                            Parameters.ANTIBANDING_50HZ,
                            Parameters.ANTIBANDING_60HZ,
                        });
            }

            if (legacyMode != null) {
                params.setAntibanding(legacyMode);
            }
        }

        /*
         * control.aeRegions
         * -- ORDER OF EXECUTION MATTERS:
         * -- This must be done after the crop region (zoom) was already set in the parameters
         */
        {
            MeteringRectangle[] aeRegions = request.get(CONTROL_AE_REGIONS);
            int maxNumMeteringAreas = params.getMaxNumMeteringAreas();
            if (aeRegions !=  null && maxNumMeteringAreas > 0) {
                // Add all non-zero weight regions to the list
                List<MeteringRectangle> meteringRectangleList = new ArrayList<>();
                for (MeteringRectangle rect : aeRegions) {
                    if (rect.getMeteringWeight() != MeteringRectangle.METERING_WEIGHT_DONT_CARE) {
                        meteringRectangleList.add(rect);
                    }
                }

                // Ignore any regions beyond our maximum supported count
                int countMeteringAreas =
                        Math.min(maxNumMeteringAreas, meteringRectangleList.size());
                List<Camera.Area> meteringAreaList = new ArrayList<>(countMeteringAreas);
                Rect activeArray = characteristics.get(
                        CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

                for (int i = 0; i < countMeteringAreas; ++i) {
                    MeteringRectangle rect = meteringRectangleList.get(i);

                    Camera.Area area = convertMeteringRectangleToLegacy(activeArray, rect);
                    meteringAreaList.add(area);
                }

                params.setMeteringAreas(meteringAreaList);

                if (maxNumMeteringAreas < meteringRectangleList.size()) {
                    Log.w(TAG,
                            "convertRequestToMetadata - Too many requested AE regions, "
                                    + "ignoring all beyond the first " + maxNumMeteringAreas);
                }
            } else {
                if (maxNumMeteringAreas > 0) {
                    params.setMeteringAreas(Arrays.asList(CAMERA_AREA_DEFAULT));
                } else {
                    params.setMeteringAreas(null);
                }
            }
        }

        // control.aeTargetFpsRange
        Range<Integer> aeFpsRange = request.get(CONTROL_AE_TARGET_FPS_RANGE);
        if (aeFpsRange != null) {
            int[] legacyFps = convertAeFpsRangeToLegacy(aeFpsRange);
            params.setPreviewFpsRange(legacyFps[Parameters.PREVIEW_FPS_MIN_INDEX],
                    legacyFps[Parameters.PREVIEW_FPS_MAX_INDEX]);
        }

        /*
         * control
         */

        // control.aeMode, flash.mode
        mapAeAndFlashMode(request, /*out*/params);

        // control.awbLock
        Boolean awbLock = request.get(CONTROL_AWB_LOCK);
        params.setAutoWhiteBalanceLock(awbLock == null ? false : awbLock);

    }

    private static void mapAeAndFlashMode(CaptureRequest r, /*out*/Parameters p) {
        int flashMode = getOrDefault(r, FLASH_MODE, FLASH_MODE_OFF);
        int aeMode = getOrDefault(r, CONTROL_AE_MODE, CONTROL_AE_MODE_ON);

        List<String> supportedFlashModes = p.getSupportedFlashModes();

        /*
         * Map all of the control.aeMode* enums, but ignore AE_MODE_OFF since we never support it
         */

        // Ignore flash.mode controls unless aeMode == ON
        if (aeMode == CONTROL_AE_MODE_ON) {
            // Flash is OFF by default
            p.setFlashMode(Parameters.FLASH_MODE_OFF);

            if (flashMode == FLASH_MODE_TORCH &&
                    ListUtils.listContains(supportedFlashModes, Parameters.FLASH_MODE_TORCH)) {
                p.setFlashMode(Parameters.FLASH_MODE_TORCH);
            } else if (flashMode == FLASH_MODE_SINGLE &&
                    ListUtils.listContains(supportedFlashModes, Parameters.FLASH_MODE_ON)) {
                p.setFlashMode(Parameters.FLASH_MODE_ON);
            }
        } else if (aeMode == CONTROL_AE_MODE_ON_ALWAYS_FLASH &&
                ListUtils.listContains(supportedFlashModes, Parameters.FLASH_MODE_ON)) {
            p.setFlashMode(Parameters.FLASH_MODE_ON);
        } else if (aeMode == CONTROL_AE_MODE_ON_AUTO_FLASH &&
                ListUtils.listContains(supportedFlashModes, Parameters.FLASH_MODE_AUTO)) {
            p.setFlashMode(Parameters.FLASH_MODE_AUTO);
        } else if (aeMode == CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE &&
                ListUtils.listContains(supportedFlashModes, Parameters.FLASH_MODE_RED_EYE)) {
            p.setFlashMode(Parameters.FLASH_MODE_RED_EYE);
        } else {
            // Default to aeMode == ON, flash = OFF
            p.setFlashMode(Parameters.FLASH_MODE_OFF);
        }
    }

    /**
     * Returns null if the anti-banding mode enum is not supported.
     */
    private static String convertAeAntiBandingModeToLegacy(int mode) {
        switch (mode) {
            case CONTROL_AE_ANTIBANDING_MODE_OFF: {
                return Parameters.ANTIBANDING_OFF;
            }
            case CONTROL_AE_ANTIBANDING_MODE_50HZ: {
                return Parameters.ANTIBANDING_50HZ;
            }
            case CONTROL_AE_ANTIBANDING_MODE_60HZ: {
                return Parameters.ANTIBANDING_60HZ;
            }
            case CONTROL_AE_ANTIBANDING_MODE_AUTO: {
                return Parameters.ANTIBANDING_AUTO;
            }
            default: {
                return null;
            }
        }
    }

    private static int[] convertAeFpsRangeToLegacy(Range<Integer> fpsRange) {
        int[] legacyFps = new int[2];
        legacyFps[Parameters.PREVIEW_FPS_MIN_INDEX] = fpsRange.getLower();
        legacyFps[Parameters.PREVIEW_FPS_MAX_INDEX] = fpsRange.getUpper();
        return legacyFps;
    }

    private static Camera.Area convertMeteringRectangleToLegacy(
            Rect activeArray, MeteringRectangle meteringRect) {
        // TODO: use matrix transform magic here

        Rect rect = new Rect();

        // TODO: Take the cropRegion (zooming) into account here

        // TODO: crop to be within [-1000, 1000] range for both X and Y if the values end up too big
        //return new Camera.Area(rect, meteringRect.getMeteringWeight());

        Log.w(TAG, "convertMeteringRectangleToLegacy - TODO: support metering rects");
        return CAMERA_AREA_DEFAULT;
    }

    private static <T> T getOrDefault(CaptureRequest r, CaptureRequest.Key<T> key, T defaultValue) {
        checkNotNull(r, "r must not be null");
        checkNotNull(key, "key must not be null");
        checkNotNull(defaultValue, "defaultValue must not be null");

        T value = r.get(key);
        if (value == null) {
            return defaultValue;
        } else {
            return value;
        }
    }
}