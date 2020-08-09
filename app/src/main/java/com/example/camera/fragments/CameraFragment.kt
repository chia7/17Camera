package com.example.camera.fragments

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.renderscript.*
import android.util.Log
import android.view.*
import android.widget.ImageView
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.example.camera.R
import com.example.camera.utils.OrientationLiveData
import com.example.camera.utils.computeExifOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


class CameraFragment: Fragment()  {

    companion object {
        private val TAG = CameraFragment::class.java.simpleName

        private const val PERMISSIONS_REQUEST_CODE = 100

        private const val IMAGE_BUFFER_SIZE: Int = 3

        private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

        data class CombinedCaptureResult(
            val image: Image,
            val metadata: CaptureResult,
            val orientation: Int,
            val format: Int
        ) : Closeable {
            override fun close() = image.close()
        }

        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            val dir = File(Environment.getExternalStorageDirectory().absolutePath + "/17Photos")
            if(!dir.exists())   dir.mkdir()
            return File(dir, "IMG_${sdf.format(Date())}.$extension")
        }

        fun newInstance() = CameraFragment()
    }


    private lateinit var captureBtn: ImageView

    private lateinit var flipBtn: ImageView

    private lateinit var textureView: TextureView

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {
        }

        override fun onSurfaceTextureUpdated(p0: SurfaceTexture) = Unit

        override fun onSurfaceTextureDestroyed(p0: SurfaceTexture) = true

        override fun onSurfaceTextureAvailable(p0: SurfaceTexture, p1: Int, p2: Int) {
            openCamera()
        }
    }

    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private lateinit var cameraId: String

    private lateinit var frontCameraId: String

    private lateinit var backCameraId: String

    private var hasLensFacingFront = false

    private var lensFacingFront = false


    private lateinit var camera: CameraDevice

    private lateinit var characteristics: CameraCharacteristics

    private lateinit var imageReader: ImageReader

    private lateinit var captureSession: CameraCaptureSession

    private lateinit var previewRequestBuilder: CaptureRequest.Builder

    private lateinit var previewRequest: CaptureRequest

    private lateinit var relativeOrientation: OrientationLiveData


    private lateinit var cameraThread: HandlerThread

    private lateinit var cameraHandler: Handler

    private lateinit var imageReaderThread: HandlerThread

    private lateinit var imageReaderHandler: Handler

    private lateinit var rs: RenderScript

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        rs = RenderScript.create(context);
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        textureView = view.findViewById<TextureView>(R.id.textureView)
        captureBtn = view.findViewById<ImageView>(R.id.captureBtn)
        flipBtn = view.findViewById<ImageView>(R.id.flipBtn)

        initCamera()

        relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
            observe(viewLifecycleOwner, Observer {
                    orientation -> Log.d(TAG, "Orientation changed: $orientation")
            })
        }
    }

    private fun checkCameraPermission() {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
                ), PERMISSIONS_REQUEST_CODE
            )
        } else {
            connectCamera()
        }
    }

    private fun initCamera() {
        try {
            for (id in cameraManager.cameraIdList) {
                var facing = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)

                if(facing != null) {
                    when(facing) {
                        CameraCharacteristics.LENS_FACING_BACK -> {
                            backCameraId = id
                        }
                        CameraCharacteristics.LENS_FACING_FRONT -> {
                            hasLensFacingFront = true
                            frontCameraId = id
                        }
                    }
                }
            }
        } catch (e: CameraAccessException) {
            Log.d(TAG, "Error Camera Access: ", e)
        } catch (e: NullPointerException) {
            Log.d(TAG, "No Camera2 API: ", e)
        }

        characteristics = cameraManager.getCameraCharacteristics(backCameraId)
        val size = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.JPEG).maxBy { it.height * it.width }!!
        imageReader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.JPEG, IMAGE_BUFFER_SIZE)


        captureBtn.setOnClickListener {

            it.isEnabled = false

            lifecycleScope.launch(Dispatchers.IO) {
                takePhoto().use { result ->
                    Log.d(TAG, "Result received: $result")

                    // Save the result to disk
                    val output = saveResult(result)
                    Log.d(TAG, "Image saved: ${output.absolutePath}")

                    // If the result is a JPEG file, update EXIF metadata with orientation info
                    if (output.extension == "jpg") {
                        val exif = ExifInterface(output.absolutePath)
                        exif.setAttribute(
                            ExifInterface.TAG_ORIENTATION, result.orientation.toString()
                        )
                        exif.saveAttributes()
                        Log.d(TAG, "EXIF metadata saved: ${output.absolutePath}")
                    }
                }

                it.post { it.isEnabled = true }
            }
        }

        flipBtn.setOnClickListener {
            it.isEnabled = false
            switchCamera()
            it.post { it.isEnabled = true }
        }
    }

    private fun cameraPreview() {
        val surfaceTexture = textureView.surfaceTexture
        val size = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.JPEG).maxBy { it.height * it.width }!!
        surfaceTexture?.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(surfaceTexture)


        previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }

        camera.createCaptureSession(Arrays.asList(surface, imageReader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigureFailed(p0: CameraCaptureSession) {
                }

                override fun onConfigured(p0: CameraCaptureSession) {
                    if (p0 != null) {
                        captureSession = p0
                        previewRequestBuilder.set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        )
                        previewRequest = previewRequestBuilder.build()
                        captureSession.setRepeatingRequest(previewRequest, null, null)
                    }

                }

            }, null
        )
    }




    private suspend fun takePhoto(): CombinedCaptureResult = suspendCoroutine { cont ->

        // Flush any images left in the image reader
        @Suppress("ControlFlowWithEmptyBody")
        while (imageReader.acquireNextImage() != null) {}

        // Start a new image queue
        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.d(TAG, "Image available in queue: ${image.timestamp}")
            imageQueue.add(image)
        }, imageReaderHandler)

        val captureRequest = captureSession.device.createCaptureRequest(
            CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(imageReader.surface) }
        captureSession.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult) {
                super.onCaptureCompleted(session, request, result)
                val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                Log.d(TAG, "Capture result received: $resultTimestamp")

                // Set a timeout in case image captured is dropped from the pipeline
                val exc = TimeoutException("Image dequeuing took too long")
                val timeoutRunnable = Runnable { cont.resumeWithException(exc) }
                imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS)

                // Loop in the coroutine's context until an image with matching timestamp comes
                // We need to launch the coroutine context again because the callback is done in
                //  the handler provided to the `capture` method, not in our coroutine context
                @Suppress("BlockingMethodInNonBlockingContext")
                lifecycleScope.launch(cont.context) {
                    while (true) {

                        // Dequeue images while timestamps don't match
                        val image = imageQueue.take()
                        // TODO(owahltinez): b/142011420
                        // if (image.timestamp != resultTimestamp) continue
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            image.format != ImageFormat.DEPTH_JPEG &&
                            image.timestamp != resultTimestamp
                        ) continue
                        Log.d(TAG, "Matching image dequeued: ${image.timestamp}")

                        // Unset the image reader listener
                        imageReaderHandler.removeCallbacks(timeoutRunnable)
                        imageReader.setOnImageAvailableListener(null, null)

                        // Clear the queue of images, if there are left
                        while (imageQueue.size > 0) {
                            imageQueue.take().close()
                        }

                        // Compute EXIF orientation metadata
                        val rotation = relativeOrientation.value ?: 0
                        val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_FRONT
                        val exifOrientation = computeExifOrientation(rotation, mirrored)

                        // Build the result and resume progress
                        cont.resume(
                            CombinedCaptureResult(
                                image, result, exifOrientation, imageReader.imageFormat
                            )
                        )

                        // There is no need to break out of the loop, this coroutine will suspend
                    }
                }
            }
        }, cameraHandler)
    }

    private suspend fun saveResult(result: CombinedCaptureResult): File = suspendCoroutine { cont ->
        when (result.format) {

            // When the format is JPEG or DEPTH JPEG we can simply save the bytes as-is
            ImageFormat.JPEG, ImageFormat.DEPTH_JPEG -> {
                val buffer = result.image.planes[0].buffer
                var bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }

                bytes = addWaterMark(bytes)

                try {
                    val output = createFile(requireContext(), "jpg")
                    FileOutputStream(output).use { it.write(bytes) }
                    cont.resume(output)
                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write JPEG image to file", exc)
                    cont.resumeWithException(exc)
                }
            }

            // When the format is RAW we use the DngCreator utility library
            ImageFormat.RAW_SENSOR -> {
                val dngCreator = DngCreator(characteristics, result.metadata)
                try {
                    val output = createFile(requireContext(), "dng")
                    FileOutputStream(output).use { dngCreator.writeImage(it, result.image) }
                    cont.resume(output)
                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write DNG image to file", exc)
                    cont.resumeWithException(exc)
                }
            }

            // No other formats are supported by this sample
            else -> {
                val exc = RuntimeException("Unknown image format: ${result.image.format}")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }
    }

    private fun addWaterMark(bytes: ByteArray): ByteArray {

        val inputStream = ByteArrayInputStream(bytes)
        val bmp =  BitmapFactory.decodeStream(inputStream)

        val result = Bitmap.createBitmap(bmp.width, bmp.height, bmp.config)
        val canvas = Canvas(result)
        canvas.drawBitmap(bmp, 0f, 0f, null)
        var waterMark = BitmapFactory.decodeResource(context!!.resources, R.drawable.watermark)
        waterMark = Bitmap.createScaledBitmap(waterMark, 400, 400, false)
        canvas.drawBitmap(waterMark, bmp.width - 450f, bmp.height - 450f, null)

        val outputStream = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)

        return outputStream.toByteArray()
    }


    private fun chooseCamera() {
        if(lensFacingFront) {
            characteristics = cameraManager.getCameraCharacteristics(frontCameraId)
            cameraId = frontCameraId
        } else {
            characteristics = cameraManager.getCameraCharacteristics(backCameraId)
            cameraId = backCameraId
        }
    }

    private fun switchCamera() {
        if (!hasLensFacingFront) return

        closeCamera()

        lensFacingFront = !lensFacingFront
        chooseCamera()

        openCamera()
    }

    private fun connectCamera() {
        chooseCamera()
        try {
            cameraManager.openCamera(cameraId, deviceStateCallBack, cameraHandler)
        } catch (e: CameraAccessException) {
        } catch (e: InterruptedException) {
        }
    }

    private val deviceStateCallBack = object : CameraDevice.StateCallback() {
        override fun onOpened(p0: CameraDevice) {
            if (p0 != null) {
                camera = p0
                cameraPreview()
            }
        }

        override fun onDisconnected(p0: CameraDevice) {
            Log.w(TAG, "Camera $cameraId has been disconnected")
            p0.close()
            requireActivity().finish()
        }

        override fun onError(p0: CameraDevice, p1: Int) {
            val msg = when(p1) {
                ERROR_CAMERA_DEVICE -> "Fatal (device)"
                ERROR_CAMERA_DISABLED -> "Device policy"
                ERROR_CAMERA_IN_USE -> "Camera in use"
                ERROR_CAMERA_SERVICE -> "Fatal (service)"
                ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                else -> "Unknown"
            }
            val exc = RuntimeException("Camera $cameraId error: ($p1) $msg")
            Log.e(TAG, exc.message, exc)
        }

    }

    private fun openCamera() {
        checkCameraPermission()
    }

    private fun closeCamera() {
        if (this::captureSession.isInitialized) {
            captureSession.close()
        }
        if (this::camera.isInitialized) {
            camera.close()
        }
    }

    private fun startBackgroundThread() {
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread.looper)

        imageReaderThread = HandlerThread("imageReaderThread").apply { start() }
        imageReaderHandler = Handler(imageReaderThread.looper)
    }

    private fun stopBackgroundThread() {
        cameraThread.quitSafely()
        try {
            cameraThread.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error closing cameraThread", e)
        }

        imageReaderThread.quitSafely()
        try {
            imageReaderThread.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error closing imageReaderThread", e)
        }
    }


    override fun onResume() {
        super.onResume()

        startBackgroundThread()
        if(textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = surfaceListener
        }

    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }
}