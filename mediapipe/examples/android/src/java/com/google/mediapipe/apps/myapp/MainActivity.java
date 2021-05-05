package com.google.mediapipe.apps.myapp;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mediapipe.components.CircularEncoder;
import com.google.mediapipe.framework.AndroidAssetUtil;
import com.google.mediapipe.components.FrameProcessor;
import com.google.mediapipe.components.CameraXPreviewHelper;
import com.google.mediapipe.components.CameraHelper;
import com.google.mediapipe.components.PermissionHelper;
import com.google.mediapipe.components.ExternalTextureConverter;
import com.google.mediapipe.glutil.EglManager;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;


/** Bare-bones main activity. */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT = 1;

    // Flips the camera-preview frames vertically by default, before sending them into FrameProcessor
    // to be processed in a MediaPipe graph, and flips the processed frames back when they are
    // displayed. This maybe needed because OpenGL represents images assuming the image origin is at
    // the bottom-left corner, whereas MediaPipe in general assumes the image origin is at the
    // top-left corner.
    // NOTE: use "flipFramesVertically" in manifest metadata to override this behavior.
    private static final boolean FLIP_FRAMES_VERTICALLY = true;

    // Number of output frames allocated in ExternalTextureConverter.
    // NOTE: use "converterNumBuffers" in manifest metadata to override number of buffers. For
    // example, when there is a FlowLimiterCalculator in the graph, number of buffers should be at
    // least `max_in_flight + max_in_queue + 1` (where max_in_flight and max_in_queue are used in
    // FlowLimiterCalculator options). That's because we need buffers for all the frames that are in
    // flight/queue plus one for the next frame from the camera.
    private static final int NUM_BUFFERS = 2;
    private ImageButton btnTakePicture;
    private ImageButton btnSwitchCamera;
    private ImageButton btnGallery;
    private ImageButton mbtnRecordVideo;
    private boolean isRecording = false;

    private MediaRecorder mMediaRecorder;
    private File mVideoFolder;
    private String mVideoFileName;


    static {
        // Load all native libraries needed by the app.
        System.loadLibrary("mediapipe_jni");
        try {
            System.loadLibrary("opencv_java3");
        } catch (java.lang.UnsatisfiedLinkError e) {
            // Some example apps (e.g. template matching) require OpenCV 4.
            System.loadLibrary("opencv_java4");
        }
    }

    // Sends camera-preview frames into a MediaPipe graph for processing, and displays the processed
    // frames onto a {@link Surface}.
    protected FrameProcessor processor;
    // Handles camera access via the {@link CameraX} Jetpack support library.
    protected CameraXPreviewHelper cameraHelper;

    // {@link SurfaceTexture} where the camera-preview frames can be accessed.
    private SurfaceTexture previewFrameTexture;
    // {@link SurfaceView} that displays the camera-preview frames processed by a MediaPipe graph.
    private SurfaceView previewDisplayView;

    // Creates and manages an {@link EGLContext}.
    private EglManager eglManager;
    // Converts the GL_TEXTURE_EXTERNAL_OES texture from Android camera into a regular texture to be
    // consumed by {@link FrameProcessor} and the underlying MediaPipe graph.
    private ExternalTextureConverter converter;

    // ApplicationInfo for retrieving metadata defined in the manifest.
    private ApplicationInfo applicationInfo;

    private CameraHelper.CameraFacing cameraFacing = CameraHelper.CameraFacing.FRONT;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        createVideoFolder();

        mMediaRecorder = new MediaRecorder();

        try {
            applicationInfo =
                    getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Cannot find application info: " + e);
        }

        mbtnRecordVideo = findViewById(R.id.btnRecordVideo);
        mbtnRecordVideo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isRecording) {
                    isRecording = false;
                    mbtnRecordVideo.setImageResource(R.drawable.ic_take_video);
                } else {
                    checkWriteStoragePermission();
                }
            }
        });

        previewDisplayView = new SurfaceView(this);
        setupPreviewDisplayView();

        // Initialize asset manager so that MediaPipe native libraries can access the app assets, e.g.,
        // binary graphs.
        AndroidAssetUtil.initializeNativeAssetManager(this);

        eglManager = new EglManager(null);

        processor =
                new FrameProcessor(
                        this,
                        eglManager.getNativeContext(),
                        applicationInfo.metaData.getString("binaryGraphName"),
                        applicationInfo.metaData.getString("inputVideoStreamName"),
                        applicationInfo.metaData.getString("outputVideoStreamName"));
        processor
                .getVideoSurfaceOutput()
                .setFlipY(
                        applicationInfo.metaData.getBoolean("flipFramesVertically", FLIP_FRAMES_VERTICALLY));


    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PermissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT) {
            if(grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                try {
                    createVideoFileName();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                Toast.makeText(this,
                        "You can now save your videos", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this,
                        "App needs to save videos", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        converter = new ExternalTextureConverter(
                eglManager.getContext(),
                applicationInfo.metaData.getInt("converterNumBuffers", NUM_BUFFERS));
        converter.setFlipY(
                applicationInfo.metaData.getBoolean("flipFramesVertically", FLIP_FRAMES_VERTICALLY));
        converter.setConsumer(processor);

        if (PermissionHelper.cameraPermissionsGranted(this)) {
            startCamera(cameraFacing);
        } else {
            PermissionHelper.checkAndRequestCameraPermissions(this);
        }
    }


    @Override
    protected void onPause() {
        super.onPause();
        converter.close();
        // Hide preview display until we re-open the camera again.
        previewDisplayView.setVisibility(View.GONE);
    }

    protected void onCameraStarted(SurfaceTexture surfaceTexture) {
        previewFrameTexture = surfaceTexture;
        // Make the display view visible to start showing the preview. This triggers the
        // SurfaceHolder.Callback added to (the holder of) previewDisplayView.
        previewDisplayView.setVisibility(View.VISIBLE);
    }

    public void setSwitchCam(CameraHelper.CameraFacing cameraFacing)  {
        converter = new ExternalTextureConverter(
                eglManager.getContext(),
                applicationInfo.metaData.getInt("converterNumBuffers", NUM_BUFFERS));
        converter.setFlipY(
                applicationInfo.metaData.getBoolean("flipFramesVertically", FLIP_FRAMES_VERTICALLY));
        converter.setConsumer(processor);
        startCamera(cameraFacing);
    }
    public void startCamera(CameraHelper.CameraFacing cameraFacing) {
        cameraHelper = new CameraXPreviewHelper();
        cameraHelper.setOnCameraStartedListener(
                surfaceTexture -> {
                    onCameraStarted(surfaceTexture);
                });
        cameraHelper.startCamera(this, cameraFacing, /*unusedSurfaceTexture=*/ null);

        btnSwitchCamera = findViewById(R.id.btnSwitchCamera);
        btnSwitchCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if(cameraFacing == CameraHelper.CameraFacing.FRONT){
                    try {
                        previewFrameTexture.release();
                        converter.close();
                        previewDisplayView.setVisibility(View.GONE);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    CameraHelper.CameraFacing cameraFacing = CameraHelper.CameraFacing.BACK;
                    setSwitchCam(cameraFacing);
                }
                else {
                    try {
                        previewFrameTexture.release();
                        converter.close();
                        previewDisplayView.setVisibility(View.GONE);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    CameraHelper.CameraFacing cameraFacing = CameraHelper.CameraFacing.FRONT;
                    setSwitchCam(cameraFacing);
                }


            }
        });

    }

    protected Size computeViewSize(int width, int height) {
        return new Size(width, height);
    }

    protected void onPreviewDisplaySurfaceChanged(
            SurfaceHolder holder, int format, int width, int height) {
        // (Re-)Compute the ideal size of the camera-preview display (the area that the
        // camera-preview frames get rendered onto, potentially with scaling and rotation)
        // based on the size of the SurfaceView that contains the display.
        Size viewSize = computeViewSize(width, height);
        Size displaySize = cameraHelper.computeDisplaySizeFromViewSize(viewSize);
        boolean isCameraRotated = cameraHelper.isCameraRotated();

        // Connect the converter to the camera-preview frames as its input (via
        // previewFrameTexture), and configure the output width and height as the computed
        // display size.
        converter.setSurfaceTextureAndAttachToGLContext(
                previewFrameTexture,
                isCameraRotated ? displaySize.getHeight() : displaySize.getWidth(),
                isCameraRotated ? displaySize.getWidth() : displaySize.getHeight());
    }

    private void setupPreviewDisplayView() {
        previewDisplayView.setVisibility(View.GONE);
        ViewGroup viewGroup = findViewById(R.id.preview_display_layout);
        viewGroup.addView(previewDisplayView);

        previewDisplayView
                .getHolder()
                .addCallback(
                        new SurfaceHolder.Callback() {
                            @Override
                            public void surfaceCreated(SurfaceHolder holder) {
                                processor.getVideoSurfaceOutput().setSurface(holder.getSurface());
                            }

                            @Override
                            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                                onPreviewDisplaySurfaceChanged(holder, format, width, height);
                            }

                            @Override
                            public void surfaceDestroyed(SurfaceHolder holder) {
                                processor.getVideoSurfaceOutput().setSurface(null);
                            }
                        });
    }

    private void createVideoFolder() {
        File movieFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        mVideoFolder = new File(movieFile, "AnonymizeMe Videos");
        if (! mVideoFolder.exists()) {
            mVideoFolder.mkdirs();
        }
    }

    private File createVideoFileName() throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format( new Date());
        String prepend = "VIDEO_" + timestamp + "_";
        File videoFile= File.createTempFile(prepend, ".mp4", mVideoFolder);
        mVideoFileName = videoFile.getAbsolutePath();
        return videoFile;
    }

    private void checkWriteStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED) {
                isRecording = true;
                mbtnRecordVideo.setImageResource(R.drawable.ic_take_picture);
                try {
                    createVideoFileName();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    Toast.makeText(this, "app needs to save videos", Toast.LENGTH_SHORT).show();
                }
                requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT);
            }
        } else {
            isRecording = true;
            mbtnRecordVideo.setImageResource(R.drawable.ic_take_picture);
            try {
                createVideoFileName();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void setupMediaRecorder() throws IOException {
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mMediaRecorder.setOutputFile(mVideoFileName);
        mMediaRecorder.setVideoEncodingBitRate(1000000);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setVideoSize(cameraHelper.getFrameSize().getWidth(), cameraHelper.getFrameSize().getHeight());
        mMediaRecorder.setOrientationHint(cameraHelper.getFrameRotation());
        mMediaRecorder.setAudioEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.prepare();
    }
}