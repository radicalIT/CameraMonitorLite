package dev.yaky.usbcamviewer;

import static android.content.pm.PackageManager.PERMISSION_DENIED;

import android.Manifest;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.WindowManager;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.serenegiant.usb.DeviceFilter;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import java.util.List;

import android.view.MotionEvent; // Do obsługi dotyku
import android.view.ScaleGestureDetector; // Do wykrywania gestu "szczypania"

public class MainActivity extends AppCompatActivity {

    private USBMonitor mUsbMonitor;
    private UVCCamera mCamera;
    private SurfaceView mSurfaceView;
    private Surface mSurface;
    private ScaleGestureDetector mScaleGestureDetector;
    private float mScaleFactor = 1.0f;
    private float mPosX = 0f;
    private float mPosY = 0f;
    private float mLastTouchX;
    private float mLastTouchY;
    private static final int INVALID_POINTER_ID = -1;
    private int mActivePointerId = INVALID_POINTER_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Request access to camera and to record audio
        // (both are required to automatically handle USB cameras)
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PERMISSION_DENIED
        || ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PERMISSION_DENIED ) {
            ActivityCompat.requestPermissions(this, new String[]{
                    android.Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            }, 0);
        }

        // Set window as edge-to-edge fullscreen
        EdgeToEdge.enable(this);
        var flags = WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
        getWindow().setFlags(flags, flags);

        setContentView(R.layout.activity_main);
        mSurfaceView = findViewById(R.id.camera_surface_view);

        mUsbMonitor = new USBMonitor(this, mUsbMonitorOnDeviceConnectListener);

        mScaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 1. Obsługa zoomu (priorytet)
        mScaleGestureDetector.onTouchEvent(event);

        final int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                final int pointerIndex = event.getActionIndex();
                final float x = event.getX(pointerIndex);
                final float y = event.getY(pointerIndex);

                // Zapamiętujemy, gdzie dotknęliśmy
                mLastTouchX = x;
                mLastTouchY = y;
                mActivePointerId = event.getPointerId(0);
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                final int pointerIndex = event.findPointerIndex(mActivePointerId);
                if (pointerIndex == -1) break;

                final float x = event.getX(pointerIndex);
                final float y = event.getY(pointerIndex);

                // Obliczamy różnicę ruchu
                final float dx = x - mLastTouchX;
                final float dy = y - mLastTouchY;

                // W onTouchEvent w case ACTION_MOVE:
                if (!mScaleGestureDetector.isInProgress() && mScaleFactor > 1.0f) {
                    mPosX += dx;
                    mPosY += dy;
                    // Tu usunąłem logikę "maxDx/clamp", żeby nie blokowała zoomu na krawędziach
                    mSurfaceView.setTranslationX(mPosX);
                    mSurfaceView.setTranslationY(mPosY);
                }

                mLastTouchX = x;
                mLastTouchY = y;
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                mActivePointerId = INVALID_POINTER_ID;
                break;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                // Obsługa sytuacji, gdy podnosimy jeden z palców (żeby obraz nie skakał)
                final int pointerIndex = event.getActionIndex();
                final int pointerId = event.getPointerId(pointerIndex);
                if (pointerId == mActivePointerId) {
                    final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
                    mLastTouchX = event.getX(newPointerIndex);
                    mLastTouchY = event.getY(newPointerIndex);
                    mActivePointerId = event.getPointerId(newPointerIndex);
                }
                break;
            }
        }
        return true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Initialize and start the USB monitor
        mUsbMonitor.register();
        // Request access to the first USB camera
        final List<DeviceFilter> filter = DeviceFilter.getDeviceFilters(this, R.xml.device_filter);
        final List<UsbDevice> usbDevices = mUsbMonitor.getDeviceList(filter.get(0));
        if (usbDevices.isEmpty()) return;
        final UsbDevice firstUsbDevice = usbDevices.get(0);
        mUsbMonitor.requestPermission(firstUsbDevice);
        // Next step is the onConnect event in the USBMonitor
    }

    @Override
    protected void onStop() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.close();
        }
        if (mSurface != null) {
            mSurface.release();
        }
        mUsbMonitor.unregister();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mUsbMonitor.destroy();
        mUsbMonitor = null;
        super.onDestroy();
    }

    private final USBMonitor.OnDeviceConnectListener mUsbMonitorOnDeviceConnectListener = new USBMonitor.OnDeviceConnectListener() {

        @Override
        public void onAttach(UsbDevice device) {
        }

        @Override
        public void onDettach(UsbDevice device) {
        }

        @Override
        public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
            UVCCamera camera = new UVCCamera();
            camera.open(ctrlBlock);

            var previewSize = camera.getSupportedSizeList().get(0);

            try {
                camera.setPreviewSize(previewSize.width, previewSize.height, UVCCamera.FRAME_FORMAT_MJPEG);
            } catch (final IllegalArgumentException e) {
                try {
                    // fallback to YUV mode
                    camera.setPreviewSize(previewSize.width, previewSize.height, UVCCamera.DEFAULT_PREVIEW_MODE);
                } catch (final IllegalArgumentException e1) {
                    camera.destroy();
                    return;
                }
            }
            mSurface = mSurfaceView.getHolder().getSurface();
            camera.setPreviewDisplay(mSurface);
            camera.startPreview();

            mCamera = camera;
        }

        @Override
        public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
            mCamera.stopPreview();
            mCamera.close();
            mCamera = null;
        }

        @Override
        public void onCancel(UsbDevice device) {
        }
    };

    // Klasa obsługująca gest przybliżania/oddalania
    // Klasa obsługująca gest przybliżania/oddalania z uwzględnieniem punktu skupienia (Focus Point)
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleChange = detector.getScaleFactor();
            float newScale = mScaleFactor * scaleChange;

            // Ograniczenia zoomu (1.0x - 5.0x)
            newScale = Math.max(1.0f, Math.min(newScale, 5.0f));

            // Obliczamy faktyczną zmianę skali po ograniczeniach
            // (ważne, gdybyśmy dotarli do granicy 5.0x lub 1.0x)
            float finalScaleRatio = newScale / mScaleFactor;

            // Aktualizujemy główny czynnik skali
            mScaleFactor = newScale;

            // --- MATEMATYKA MAGICZNA ---
            // Obliczamy środek gestu (gdzie są palce)
            float focusX = detector.getFocusX();
            float focusY = detector.getFocusY();

            // Obliczamy odległość palców od środka ekranu
            float dx = focusX - (mSurfaceView.getWidth() / 2f);
            float dy = focusY - (mSurfaceView.getHeight() / 2f);

            // Przesuwamy obraz, aby skompensować "uciekanie" punktu pod palcami.
            // Wzór: Przesunięcie -= (PozycjaPalca - Środek - AktualnePrzesunięcie) * (ZmianaSkali - 1)
            mPosX -= (dx - mPosX) * (finalScaleRatio - 1);
            mPosY -= (dy - mPosY) * (finalScaleRatio - 1);

            // Aplikujemy zmiany
            mSurfaceView.setScaleX(mScaleFactor);
            mSurfaceView.setScaleY(mScaleFactor);
            mSurfaceView.setTranslationX(mPosX);
            mSurfaceView.setTranslationY(mPosY);

            // Jeśli wróciliśmy do 1.0x, resetujemy pozycję idealnie do zera, żeby obraz był wycentrowany
            if (mScaleFactor <= 1.0f) {
                mPosX = 0f;
                mPosY = 0f;
                mSurfaceView.setTranslationX(0f);
                mSurfaceView.setTranslationY(0f);
            }

            return true;
        }
    }
}