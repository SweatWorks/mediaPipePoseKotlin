package com.example.pose

import android.content.ContentValues.TAG
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.mediapipe.components.CameraHelper.CameraFacing
import com.google.mediapipe.components.CameraHelper.OnCameraStartedListener
import com.google.mediapipe.components.CameraXPreviewHelper
import com.google.mediapipe.components.ExternalTextureConverter
import com.google.mediapipe.components.FrameProcessor
import com.google.mediapipe.components.PermissionHelper
import com.google.mediapipe.formats.proto.LandmarkProto
import com.google.mediapipe.framework.AndroidAssetUtil
import com.google.mediapipe.framework.PacketGetter
import com.google.mediapipe.glutil.EglManager
import com.google.protobuf.InvalidProtocolBufferException


class MainActivity : AppCompatActivity() {
    private lateinit var previewFrameTexture: SurfaceTexture
    private lateinit var previewDisplayView: SurfaceView
    private lateinit var cameraHelper: CameraXPreviewHelper
    private lateinit var eglManager: EglManager
    private lateinit var converter: ExternalTextureConverter
    private lateinit var processor: FrameProcessor


    companion object {
        private const val OUTPUT_LANDMARKS_STREAM_NAME = "pose_landmarks"

        init {
            // Load all native libraries needed by the app.
            println("Executing init function for native libs")
            System.loadLibrary("opencv_java3")
            System.loadLibrary("mediapipe_jni")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewDisplayView = SurfaceView(this)
        setupPreviewDisplayView()
        AndroidAssetUtil.initializeNativeAssetManager(this)
        eglManager = EglManager(null)
        val assetManager = assets
        val files = assetManager.list("")

        if (files != null) {
            for (file in files) {
                Log.d("Files in assets", file)
            }
        }

        processor = FrameProcessor(
            this,
            eglManager.nativeContext,
            "pose_tracking_gpu.binarypb",
            "input_video",
            "output_video"
        )
        PermissionHelper.checkAndRequestCameraPermissions(this);

        Log.d(TAG, "Setting log level to VERBOSE");

        processor.addPacketCallback(OUTPUT_LANDMARKS_STREAM_NAME) { packet ->
            Log.v(TAG, "Received pose landmarks packet.")
            try {
                val poseLandmarks =
                    PacketGetter.getProto(packet, LandmarkProto.NormalizedLandmarkList::class.java)
                Log.v(
                    TAG, "[TS:${packet.timestamp}] ${getPoseLandmarksDebugString(poseLandmarks)}"
                )
            } catch (exception: InvalidProtocolBufferException) {
                Log.e(TAG, "Failed to get proto.", exception)
            }
        }


    }

    private fun setupPreviewDisplayView() {
        previewDisplayView.visibility = View.GONE
        val viewGroup: ViewGroup = findViewById(R.id.preview_display_layout)
        viewGroup.addView(previewDisplayView)
        previewDisplayView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                processor.videoSurfaceOutput.setSurface(holder.surface);
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                val viewSize = Size(width, height)
                val displaySize = cameraHelper.computeDisplaySizeFromViewSize(viewSize)
                Log.d("displaySize", displaySize.toString())
                converter.setSurfaceTextureAndAttachToGLContext(
                    previewFrameTexture,
                    displaySize.width,
                    displaySize.height
                )
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                processor.videoSurfaceOutput.setSurface(null);
            }
        })
    }

    override fun onResume() {
        super.onResume()
        Log.d("lifeCycle", "onResume")
        converter = ExternalTextureConverter(eglManager.context)
        converter.setConsumer(processor);
        if (PermissionHelper.cameraPermissionsGranted(this)) {
            startCamera()
        }
    }

    private fun startCamera() {
        cameraHelper = CameraXPreviewHelper()
        cameraHelper.setOnCameraStartedListener(
            OnCameraStartedListener { surfaceTexture: SurfaceTexture? ->
                Log.d("surfaceTexture", surfaceTexture.toString());
                if (surfaceTexture != null) {
                    previewFrameTexture = surfaceTexture
                }
                // Make the display view visible to start showing the preview.
                previewDisplayView.visibility = View.VISIBLE
            })

        var appInfo: ApplicationInfo =
            packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)

        Log.d("AppInfo", appInfo.metaData.getBoolean("cameraFacingFront").toString())
        cameraHelper.startCamera(
            this,
            if (appInfo.metaData.getBoolean("cameraFacingFront")) CameraFacing.FRONT else CameraFacing.BACK,  /*unusedSurfaceTexture=*/
            null
        )
    }

    override fun onPause() {
        super.onPause()
        Log.d("lifeCycle", "onPause")
        converter.close()
        previewDisplayView.visibility = View.GONE
    }

    override fun onStop() {
        super.onStop()
        Log.d("lifeCycle", "onStop")
    }

    private fun getPoseLandmarksDebugString(poseLandmarks: LandmarkProto.NormalizedLandmarkList): String {
        var poseLandmarkStr = "Pose landmarks: ${poseLandmarks.landmarkCount}\n"
        var landmarkIndex = 0
        for (landmark in poseLandmarks.landmarkList) {
            poseLandmarkStr += "\tLandmark [$landmarkIndex]: (${landmark.x}, ${landmark.y}, ${landmark.z})\n"
            ++landmarkIndex
        }
        return poseLandmarkStr
    }

}