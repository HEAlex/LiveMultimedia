package com.constantinnovationsinc.livemultimedia.previews;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Looper;
import android.os.Process;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import android.view.TextureView.SurfaceTextureListener;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.FrameLayout.LayoutParams;

import java.io.File;
import java.io.IOException;
import java.lang.RuntimeException;
import java.util.ArrayList;
import java.util.List;

import com.constantinnovationsinc.livemultimedia.activities.LiveMultimediaActivity;
import com.constantinnovationsinc.livemultimedia.callbacks.FramesReadyCallback;
import com.constantinnovationsinc.livemultimedia.cameras.JellyBeanCamera;
import com.constantinnovationsinc.livemultimedia.encoders.AudioEncoder;
import com.constantinnovationsinc.livemultimedia.encoders.GPUEncoder;
import com.constantinnovationsinc.livemultimedia.handlers.CameraHandler;
import com.constantinnovationsinc.livemultimedia.handlers.VideoEncoderHandler;
import com.constantinnovationsinc.livemultimedia.handlers.AudioEncoderHandler;
import com.constantinnovationsinc.livemultimedia.recorders.AVRecorder;
import com.constantinnovationsinc.livemultimedia.servers.VideoServer;
import com.constantinnovationsinc.livemultimedia.threads.AudioEncoderThread;
import com.constantinnovationsinc.livemultimedia.threads.CameraThread;
import com.constantinnovationsinc.livemultimedia.threads.VideoEncoderThread;
import com.constantinnovationsinc.livemultimedia.utilities.DeviceNetwork;
import com.constantinnovationsinc.livemultimedia.R;

/****************************************************************************************************************
 * This class provides the camera app preview that Android requires if you going to operate the hardware camera
 * This preview serves two purposes. Firstly it allows the user to see what the camera sees and it also allows
 * the app to capture video frame from the preview window.
 ***************************************************************************************************************/
public class VideoPreview extends TextureView implements SurfaceTextureListener, FramesReadyCallback {
    private static final String TAG = VideoPreview.class.getSimpleName();
    private static final String START_CAPTURE_FRAMES_SOUND = "StartCaptureSound";
    private static final String START_ENCODERS_SOUND = "StartEncodersSound";

    private JellyBeanCamera mCamera = null;
    private AVRecorder mAVRecorder = null;
    private Context mContext = null;
    private CameraHandler mCameraHandler = null;
    private VideoEncoderHandler mVideoEncoderHandler = null;
    private AudioEncoderHandler mAudioEncoderHandler = null;
    private Handler mWebServerHandler = null;
    private HandlerThread mWebServerThread = null;
    private CameraThread mCameraThread = null;
    private VideoEncoderThread mVideoEncoderThread = null;
    private AudioEncoderThread mAudioEncodingThread = null;

    private VideoServer mWebServer = null;
    private AudioEncoder mAudioEncoder = null;
    private int mRatioWidth = 0;
    private int mRatioHeight = 0;

    public int mRotation = -1;
    public Boolean recordStarted = false;
    public FramesReadyCallback mVideoFramesReadylistener = null;

    public VideoPreview(Context context) {
        super(context);
        mContext = context;
    }

    public VideoPreview(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    public VideoPreview(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
    }

    public void prepare() {
        Log.d(TAG, "Camera created as well as SurfaceTextureListener!");
        mCamera = new JellyBeanCamera(mContext, this);
        setSurfaceTextureListener(this);
        setFrameReadyListener(this);
    }

    public void halt() {
        Log.d(TAG, "Camera released and SurfaceTextureListener is not null!");
        setSurfaceTextureListener(null);
        if (mCamera != null) {
            mCamera.stopCamera();
            mCamera = null;
        }
    }

    /******************************************
     * release() - fully releases everything
     *****************************************/
    public void release() {
        // clear handlers
        mWebServerHandler = null;
        mVideoEncoderHandler = null;
        mAudioEncoderHandler = null;
        mCameraHandler = null;
        // stop the threads
        if (mCameraThread != null) {
            mCameraThread.quitSafely();
        }
        if (mVideoEncoderThread != null) {
            mVideoEncoderThread.quitSafely();
        }
        if (mAudioEncodingThread != null) {
            mAudioEncodingThread.quitSafely();
        }
        if (mWebServerThread != null) {
            mWebServerThread.quitSafely();
        }
        // clear threads
        mWebServerThread = null;
        mVideoEncoderHandler = null;
        mWebServerHandler = null;
        mCameraThread = null;
    }

    /**
     * **********************************************************************************************
     * Sets the aspect ratio for this view. The size of the view will be measured based on the ratio
     * calculated from the parameters. Note that the actual sizes of parameters don't matter, that
     * is, calling setAspectRatio(2, 3) and setAspectRatio(4, 6) make the same result.
     *
     * @param width  Relative horizontal size
     * @param height Relative vertical size
     ***********************************************************************************************/
    public void setAspectRatio(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Size cannot be negative.");
        }
        mRatioWidth = width;
        mRatioHeight = height;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (0 == mRatioWidth || 0 == mRatioHeight) {
            setMeasuredDimension(width, height);
        } else {
            if (width < (height * (mRatioWidth / mRatioHeight))) {
                setMeasuredDimension(width, width * mRatioHeight / mRatioWidth);
            } else {
                setMeasuredDimension(height * mRatioWidth / mRatioHeight, height);
            }
        }
    }

    /********************************************************************************************
     * As the surface is being created the camera preview size can be set
     * This may be called multiple times during the app as the user starts and stops the camera
     * Each time a new surface may be created and a new preview window set
     * **********************************************************************************************/
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture image,
                                          int arg1,
                                          int arg2) {
        Log.d(TAG, "SurfaceTexture now Available!");
        final SurfaceTexture texture = image;
        createCameraThread( texture );
        setAlpha(1.0f);
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            setRotation(90.0f);
        }
    }


    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture arg0) {
        Log.d(TAG, "SurfaceTexture now Destroyed!");
        release();
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture arg0, int arg1,
                                            int arg2) {
        Log.d(TAG, "SurfaceTexture size has changed!");
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture arg0) {
    }

    public int getPreviewSizeWidth() {
        return (int) mCamera.getPreviewSizeWidth();
    }

    public int getPreviewSizeHeight() {
        return (int) mCamera.getPreviewSizeHeight();
    }

    public void setActiveCamera(int activeCam) {
        if (mCameraThread != null && mCameraThread.isAlive()) {
            mCameraThread.setActiveCameraId(activeCam);
        }
    }

    public void setFrameReadyListener(FramesReadyCallback listener) {
        mVideoFramesReadylistener = listener;
    }

    public synchronized void setRecordingState(Boolean state) {
        if (mCamera != null) {
            mCamera.setRecordingState(state);
        }
        if (state && mContext != null && mCamera != null) {
            startWebServer();
            createAVRecorder();
            recordAudio();
        }
    }

    public synchronized void createAVRecorder() {
        if (mCamera == null) {
            Log.e(TAG, "Null Camera in createAVRecorder() method!");
            return;
        }
        if (mCamera.mFrameCatcher == null) {
            Log.e(TAG, "Null FrameCatcher  in createAVRecorder() method!");
            return;
        }
        if (mCamera.mFrameCatcher.mRecording) {
            mAVRecorder = new AVRecorder(mContext);
            mAVRecorder.setSize((int) mCamera.getPreviewSizeWidth(), (int) mCamera.getPreviewSizeHeight());
            mAVRecorder.setEncodingWidth((int) mCamera.getPreviewSizeWidth());
            mAVRecorder.setEncodingHeight((int) mCamera.getPreviewSizeHeight());
            mAVRecorder.prepare();
            mCamera.setOnFramesReadyCallBack(this);
        }
    }

    public synchronized void createCameraThread( SurfaceTexture texture ) {
        if (mCameraThread != null) {
            mCamera.release();
            mCameraThread.quitSafely();
            mCameraThread = null;
            mCameraHandler = null;
            mCamera = null;
        }
        if (mCameraThread == null) {
            // recreate the camera object
            if (mCamera == null) {
                mCamera = new JellyBeanCamera(mContext, this);
            }
            mCameraThread = new CameraThread("CameraThread");
            mCameraThread.setCamera(mCamera);
            mCameraThread.setCameraTexture(texture);
            mCameraThread.start();
            mCameraHandler = new CameraHandler(mCameraThread.getLooper());
        }
    }

    public synchronized void recordAudio() {
        // record audio on the same threade you encode
        startAudioEncoder();
    }

    public synchronized void startAudioEncoder() {
        try {
            if (mAVRecorder != null) {
                mAudioEncoder = mAVRecorder.getAudioEncoder();
                if (mAudioEncoder != null) {
                    if (mAudioEncoderHandler == null) {
                        Thread.UncaughtExceptionHandler handler = new Thread.UncaughtExceptionHandler() {
                            public void uncaughtException(Thread th, Throwable ex) {
                                Log.e(TAG, "Uncaught exception: in Audio Encoder " + ex);
                            }
                        };
                        mAudioEncodingThread = new AudioEncoderThread("AudioEncoderThread");
                        mAudioEncodingThread.setUncaughtExceptionHandler(handler);
                        mAudioEncodingThread.start();
                        mAudioEncoderHandler = new AudioEncoderHandler(mAudioEncodingThread.getLooper());
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }
    }

    public synchronized void stopAudioRecording() {
        try {
            if (mAudioEncodingThread == null) {
                Log.e(TAG, "Audio Encoding thread is dead!!");
                return;
            }
            if (mAVRecorder != null) {
                if (mAudioEncodingThread != null) {
                    if (mAudioEncodingThread.isAlive()) {
                        if (mAudioEncoder != null) {
                            // just kill the thread;
                            mAudioEncodingThread.quitSafely();
                        } else {
                            Log.e(TAG, "Audio Encoder is null in stopAudioRecording!");
                        }
                    }
                } else {
                    Log.e(TAG, "Audio Encoding thread is not alive in stopAudioRecording()");
                }
            } else {
                Log.e(TAG, "AVRecorder is null in stopAudioRecording()");
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }
    }

    public synchronized void startVideoEncoder() throws IllegalArgumentException {
        if (mCamera == null) {
            throw new IllegalStateException("Camera is null in startVideoEncoder()");
        }
        if ( mAVRecorder == null) {
            throw new IllegalStateException("AVRecorder is null in startVideoEncoder()");
        }
        GPUEncoder encoder = mAVRecorder.getVideoEncoder();
        if (encoder == null) {
            throw new IllegalStateException("GPUEncoder is null in startVideoEncoder()");
        }
        if (mVideoEncoderHandler != null && mVideoEncoderHandler != null && mVideoEncoderThread != null && mVideoEncoderThread.isAlive()) {
            throw new IllegalStateException("Video Encoding process is still ongoing in startVideoEncoder");
        }
        // passed the shared memory reference from the catcher to the recorder
        mAVRecorder.setSharedMemFile(mCamera.mFrameCatcher.getSharedMemFile());
        stopAudioRecording();

        try {
            Thread.UncaughtExceptionHandler handler = new Thread.UncaughtExceptionHandler() {
                public void uncaughtException(Thread th, Throwable ex) {
                    Log.e(TAG, "Uncaught exception: in Video Encoder " + ex);
                }
            };
            mVideoEncoderThread = new VideoEncoderThread("GPUEncoderThread");
            mVideoEncoderThread.setUncaughtExceptionHandler(handler);
            if (mAVRecorder.getVideoEncoder() != null && mCamera != null) {
                mAVRecorder.getVideoEncoder().setSharedVideoFramesStore(mCamera.mFrameCatcher.getSharedMemFile());
            }
            mVideoEncoderThread.start();
            mVideoEncoderHandler = new VideoEncoderHandler(mVideoEncoderThread.getLooper());
            mAVRecorder.playSound(START_ENCODERS_SOUND);
            mAVRecorder.getVideoEncoder().runGPUEncoder();
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }
    }

    public synchronized void startWebServer() {
        try {
            String host = DeviceNetwork.getIPAddress(true);
            TextView text = new TextView(mContext);
            text.setText(host + ":8080");
            text.setX(0f);
            text.setY(20.0f);
            LiveMultimediaActivity activity = (LiveMultimediaActivity) mContext;
            if (activity != null) {
                FrameLayout layout = (FrameLayout) activity.findViewById(R.id.fragment_container);
                text.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
                layout.addView(text);
            }
            Thread.UncaughtExceptionHandler handler = new Thread.UncaughtExceptionHandler() {
                public void uncaughtException(Thread th, Throwable ex) {
                    Log.e(TAG, "Uncaught exception: in Start Web Server " + ex);
                }
            };
            mWebServerThread = new HandlerThread("WebServerThread");
            mWebServerThread.setUncaughtExceptionHandler(handler);
            mWebServerThread.start();
            mWebServerHandler = new Handler(mWebServerThread.getLooper());
            mWebServerHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        String host = DeviceNetwork.getIPAddress(true);
                        Log.w(TAG, "Device ip is " + host);
                        int port = 8080;
                        mWebServer = new VideoServer(host, port);
                        mWebServer.start();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }
    }

   /********************************************************
    * playerLazerSound 0 sound when frames are being captured
    *********************************************************/
    public synchronized void playLazerSound() {
        if (mAVRecorder != null) {
            mAVRecorder.playSound(START_CAPTURE_FRAMES_SOUND);
        } else {
            Log.e(TAG, "mACRecorder is null in the VideoPreview");
        }
    }


}