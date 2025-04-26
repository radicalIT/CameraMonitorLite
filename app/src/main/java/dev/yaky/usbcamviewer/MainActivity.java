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

public class MainActivity extends AppCompatActivity {

    private USBMonitor mUsbMonitor;
    private UVCCamera mCamera;
    private SurfaceView mSurfaceView;
    private Surface mSurface;

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
        mSurfaceView = (SurfaceView)findViewById(R.id.camera_surface_view);

        mUsbMonitor = new USBMonitor(this, mUsbMonitorOnDeviceConnectListener);
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
}