package com.arcsoft.idcardveridemo.camera;

import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 相机辅助类，和{@link CameraListener}共同使用，获取nv21数据等操作
 */
public class CameraHelper implements Camera.PreviewCallback {
    private static final String TAG = "CameraHelper";
    private boolean needTakePicture = false;

    private TakePictureType takePictureType;
    private Camera mCamera;
    private int mCameraId;
    private Point previewViewSize;
    private View previewDisplayView;
    private Camera.Size previewSize;
    private Point specificPreviewSize;
    private int displayOrientation = 0;
    private int rotation;
    private int additionalRotation;
    private boolean isMirror = false;

    private Integer specificCameraId = null;
    private CameraListener cameraListener;

    private CameraHelper(Builder builder) {
        previewDisplayView = builder.previewDisplayView;
        specificCameraId = builder.specificCameraId;
        cameraListener = builder.cameraListener;
        rotation = builder.rotation;
        additionalRotation = builder.additionalRotation;
        takePictureType = builder.takePictureType;
        previewViewSize = builder.previewViewSize;
        specificPreviewSize = builder.specificPreviewSize;
        if (builder.previewDisplayView instanceof TextureView) {
            isMirror = builder.isMirror;
        } else if (isMirror) {
            throw new IllegalArgumentException("mirror is effective only when the preview is on a textureView");
        }
    }

    public void init() {
        if (previewDisplayView != null) {
            if (previewDisplayView instanceof TextureView) {
                ((TextureView) this.previewDisplayView).setSurfaceTextureListener(textureListener);
            } else if (previewDisplayView instanceof SurfaceView) {
                ((SurfaceView) previewDisplayView).getHolder().addCallback(surfaceCallback);
            }
        }

        if (isMirror && previewDisplayView != null) {
            previewDisplayView.setScaleX(-1);
        }
    }


    private int getCameraOri(int rotation) {
        int degrees = rotation * 90;
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
            default:
                break;
        }
        additionalRotation /= 90;
        additionalRotation *= 90;
        degrees += additionalRotation;
        int result;
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(mCameraId, info);
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }

    public void start() {
        synchronized (this) {
            if (mCamera != null) {
//                if (cameraListener != null) {
//                    cameraListener.onCameraError(new Exception("camera already opened"));
//                }
                return;
            }
            //相机数量为2则打开1,1则打开0,相机ID 1为前置，0为后置
            mCameraId = Camera.getNumberOfCameras() - 1;
            //若指定了相机ID且该相机存在，则打开指定的相机
            if (specificCameraId != null && specificCameraId <= mCameraId) {
                mCameraId = specificCameraId;
            }
            specificCameraId = mCameraId;

            //没有相机
            if (mCameraId == -1) {
                if (cameraListener != null) {
                    cameraListener.onCameraError(new Exception("camera not found"));
                }
                return;
            }

            mCamera = Camera.open(mCameraId);

            displayOrientation = getCameraOri(rotation);
            mCamera.setDisplayOrientation(displayOrientation);
            try {
                Camera.Parameters parameters = mCamera.getParameters();
                parameters.setPreviewFormat(ImageFormat.NV21);

                //预览大小设置
                previewSize = parameters.getPreviewSize();
                List<Camera.Size> supportedPreviewSizes = parameters.getSupportedPreviewSizes();
                if (supportedPreviewSizes != null && supportedPreviewSizes.size() > 0) {
                    previewSize = getBestSupportedSize(supportedPreviewSizes, previewViewSize);
                }
                parameters.setPreviewSize(previewSize.width, previewSize.height);

                //对焦模式设置
                List<String> supportedFocusModes = parameters.getSupportedFocusModes();
                if (supportedFocusModes != null && supportedFocusModes.size() > 0) {
                    if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                    } else if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                    } else if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                    }
                }
                mCamera.setParameters(parameters);
                if (previewDisplayView != null) {
                    if (previewDisplayView instanceof TextureView) {
                        mCamera.setPreviewTexture(((TextureView) previewDisplayView).getSurfaceTexture());
                    } else {
                        mCamera.setPreviewDisplay(((SurfaceView) previewDisplayView).getHolder());
                    }
                }
                mCamera.setPreviewCallback(this);
                mCamera.startPreview();
                if (cameraListener != null) {
                    cameraListener.onCameraOpened(mCamera, mCameraId, displayOrientation, isMirror);
                }
            } catch (Exception e) {
                if (cameraListener != null) {
                    e.printStackTrace();
                    cameraListener.onCameraError(e);
                }
            }
        }
    }

    public void stop() {
        synchronized (this) {

            if (mCamera == null) {
//                if (cameraListener != null) {
//                    cameraListener.onCameraError(new Exception("camera already released"));
//                }
                return;
            }
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            if (cameraListener != null) {
                cameraListener.onCameraClosed();
            }

        }
    }

    public boolean isStopped() {
        synchronized (this) {
            return mCamera == null;
        }
    }

    public void release() {
        synchronized (this) {
            stop();
            previewDisplayView = null;
            specificCameraId = null;
            cameraListener = null;
            previewViewSize = null;
            specificPreviewSize = null;
            previewSize = null;
        }
    }

    private Camera.Size getBestSupportedSize(List<Camera.Size> sizes, Point previewViewSize) {
        if (sizes == null || sizes.size() == 0) {
            return mCamera.getParameters().getPreviewSize();
        }
        Camera.Size[] tempSizes = sizes.toArray(new Camera.Size[0]);
        Arrays.sort(tempSizes, new Comparator<Camera.Size>() {
            @Override
            public int compare(Camera.Size o1, Camera.Size o2) {
                if (o1.width > o2.width) {
                    return -1;
                } else if (o1.width == o2.width) {
                    return o1.height > o2.height ? -1 : 1;
                } else {
                    return 1;
                }
            }
        });
        sizes = Arrays.asList(tempSizes);

        Camera.Size bestSize = sizes.get(0);
        float previewViewRatio;
        if (previewViewSize != null) {
            previewViewRatio = (float) previewViewSize.x / (float) previewViewSize.y;
        } else {
            previewViewRatio = (float) bestSize.width / (float) bestSize.height;
        }

        if (previewViewRatio > 1) {
            previewViewRatio = 1 / previewViewRatio;
        }
        boolean isNormalRotate = (additionalRotation % 180 == 0);

        for (Camera.Size s : sizes) {
            if (specificPreviewSize != null && specificPreviewSize.x == s.width && specificPreviewSize.y == s.height) {
                return s;
            }
            if (isNormalRotate) {
                if (Math.abs((s.height / (float) s.width) - previewViewRatio) < Math.abs(bestSize.height / (float) bestSize.width - previewViewRatio)) {
                    bestSize = s;
                }
            } else {
                if (Math.abs((s.width / (float) s.height) - previewViewRatio) < Math.abs(bestSize.width / (float) bestSize.height - previewViewRatio)) {
                    bestSize = s;
                }
            }
        }
        return bestSize;
    }

    public List<Camera.Size> getSupportedPreviewSizes() {
        return mCamera == null ? null : mCamera.getParameters().getSupportedPreviewSizes();
    }

    @Override
    public void onPreviewFrame(byte[] nv21, Camera camera) {
        if (cameraListener != null) {
            cameraListener.onPreview(nv21, camera);
            if (needTakePicture) {
                needTakePicture = false;
                takePicture(nv21.clone(), camera.getParameters().getPreviewSize());
            }
        }

    }

    public void takePicture() {
        needTakePicture = true;
    }

    private void takePicture(byte[] nv21, Camera.Size size) {
        if (takePictureType == null) {
            throw new IllegalArgumentException("takePictureType is null");
        }
        switch (takePictureType) {
            case JPG:
                if (cameraListener != null) {
                    YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, size.width, size.height, null);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    yuvImage.compressToJpeg(new Rect(0, 0, size.width, size.height), 100, baos);
                    byte[] bytes = baos.toByteArray();
                    try {
                        baos.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    cameraListener.onTakePicture(size.width, size.height, takePictureType, displayOrientation, bytes);
                }
                break;
            case NV21:
                if (cameraListener != null) {
                    cameraListener.onTakePicture(size.width, size.height, takePictureType, displayOrientation, nv21);
                }
                break;
            default:
                throw new IllegalArgumentException("takePictureType" + takePictureType + " is not supported");
        }
    }

    private TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
//            start();
            if (mCamera != null) {
                try {
                    mCamera.setPreviewTexture(surfaceTexture);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {

            stop();
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

        }
    };
    private SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {

            if (mCamera != null) {
                try {
                    mCamera.setPreviewDisplay(holder);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            stop();
        }
    };

    public void changeDisplayOrientation(int rotation) {
        if (mCamera != null) {
            this.rotation = rotation;
            displayOrientation = getCameraOri(rotation);
            mCamera.setDisplayOrientation(displayOrientation);
            if (cameraListener != null) {
                cameraListener.onCameraConfigurationChanged(mCameraId, displayOrientation);
            }
        }
    }

    public void switchCamera() {
        if (specificCameraId == null) {
            specificCameraId = mCameraId;
        }
        specificCameraId = 1 - specificCameraId;
        stop();
        start();
    }


    public static final class Builder {

        /**
         * 预览显示的view，目前仅支持surfaceView和textureView
         */
        private View previewDisplayView;

        /**
         * 是否镜像显示，只支持textureView
         */
        private boolean isMirror = false;
        /**
         * 指定的相机ID
         */
        private Integer specificCameraId;
        /**
         * 相机事件回调
         */
        private CameraListener cameraListener;
        /**
         * 屏幕的长宽，在选择最佳相机比例时用到
         */
        private Point previewViewSize;
        /**
         * 传入getWindowManager().getDefaultDisplay().getRotation()的值即可
         */
        private int rotation;
        /**
         * 指定的预览宽高，若系统支持则会以这个预览宽高进行预览
         */
        private Point specificPreviewSize;

        /**
         * 额外的旋转角度（用于适配一些定制设备）
         */
        private int additionalRotation;
        /**
         *
         */
        private TakePictureType takePictureType = TakePictureType.JPG;

        public Builder() {
        }


        public Builder previewOn(View val) {
            if (val instanceof SurfaceView || val instanceof TextureView) {
                previewDisplayView = val;
                return this;
            } else {
                throw new RuntimeException("you must preview on a textureView or a surfaceView");
            }
        }


        public Builder isMirror(boolean val) {
            isMirror = val;
            return this;
        }

        public Builder specificPreviewSize(Point val) {
            specificPreviewSize = val;
            return this;
        }

        public Builder previewViewSize(Point val) {
            previewViewSize = val;
            return this;
        }

        public Builder takePictureType(TakePictureType val) {
            takePictureType = val;
            return this;
        }

        public Builder rotation(int val) {
            rotation = val;
            return this;
        }

        public Builder additionalRotation(int val) {
            additionalRotation = val;
            return this;
        }

        public Builder specificCameraId(Integer val) {
            specificCameraId = val;
            return this;
        }

        public Builder cameraListener(CameraListener val) {
            cameraListener = val;
            return this;
        }

        public CameraHelper build() {
            if (previewViewSize == null) {
                Log.e(TAG, "previewViewSize is null, now use default specificPreviewSize");
            }
            if (cameraListener == null) {
                Log.e(TAG, "cameraListener is null, callback will not be called");
            }

            return new CameraHelper(this);
        }
    }

    public enum TakePictureType {
        NV21,
        JPG
    }

    public enum FlashMode {
        /**
         * 打开（拍照模式）
         */
        ON(Camera.Parameters.FLASH_MODE_ON),
        /**
         * 关闭
         */
        OFF(Camera.Parameters.FLASH_MODE_OFF),
        /**
         * 自动
         */
        AUTO(Camera.Parameters.FLASH_MODE_AUTO),
        /**
         * 手电筒模式
         */
        TORCH(Camera.Parameters.FLASH_MODE_TORCH),
        /**
         * 防红眼模式
         */
        RED_EYE(Camera.Parameters.FLASH_MODE_RED_EYE);
        String flashMode;

        FlashMode(String flashMode) {
            this.flashMode = flashMode;
        }
    }

    public List<String> getSupportedFlashMode() {
        return mCamera == null ? null : mCamera.getParameters().getSupportedFlashModes();
    }

    public FlashMode getFlashMode() {
        if (mCamera == null) {
            return null;
        }
        return FlashMode.valueOf(mCamera.getParameters().getFlashMode());
    }

    public boolean setFlashMode(FlashMode mode) {
        boolean success = false;
        if (mCamera != null) {
            List<String> supportedFlashModes = mCamera.getParameters().getSupportedFlashModes();
            if (supportedFlashModes != null) {
                if (supportedFlashModes.contains(mode.flashMode)) {
                    Camera.Parameters parameters = mCamera.getParameters();
                    parameters.setFlashMode(mode.flashMode);
                    mCamera.setParameters(parameters);
                    success = true;
                }
            }
        }
        return success;
    }

}
