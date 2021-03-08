package com.carebase.cameraxdemo;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private final static String TAG = "CameraXDemo";
    private final static String FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS";
    private final static int REQUEST_CODE_PERMISSIONS = 10;
    private final static String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};

    private ImageCapture imageCapture;
    private File outputDirectory;
    private ExecutorService cameraExecutor;

    private PreviewView viewFinder;

    private interface LumaListener {
        void update(Double luma);
    }

    private class LuminosityAnalyzer implements ImageAnalysis.Analyzer {
        private final LumaListener lumaListener;

        public LuminosityAnalyzer(LumaListener lumaListener) {
            this.lumaListener = lumaListener;
        }

        private byte[] toByteArray(ByteBuffer byteBuffer) {
            byteBuffer.rewind();
            byte[] data = new byte[byteBuffer.remaining()];
            byteBuffer.get(data);
            return data;
        }

        @Override
        public void analyze(@NonNull ImageProxy image) {
            ByteBuffer byteBuffer = image.getPlanes()[0].getBuffer();
            byte[] data = toByteArray(byteBuffer);
            int[] pixels = new int[data.length];
            for(int i=0;i<data.length;i++){
                pixels[i] = Byte.valueOf(data[i]).intValue() & 0xFF;
            }
            double average = 0;
            for (int p : pixels){
                average += Integer.valueOf(p).doubleValue();
            }
            average /= pixels.length;
            lumaListener.update(average);

            image.close();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // request camera permissions
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,REQUIRED_PERMISSIONS,REQUEST_CODE_PERMISSIONS);
        }

        // set up the listener for the take photo button
        Button cameraCaptureButton = findViewById(R.id.camera_capture_button);
        cameraCaptureButton.setOnClickListener((v) -> takePhoto());

        viewFinder = findViewById(R.id.viewFinder);

        outputDirectory = getOutputDirectory();

        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this,
                        "Permissions not granted by the user.",
                        Toast.LENGTH_SHORT).show();
                // finish();
            }
        }
    }

    private void takePhoto() {
        // get a stable reference of the modifiable image capture use case
        if (imageCapture == null) {
            return;
        }

        // create time stamped output file to hold image
        File photoFile = new File(outputDirectory, new SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()) + ".jpg");

        // create output options object which contains file + metadata
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        // set up image capture listener, which is triggered after photo has been taken
        imageCapture.takePicture(outputOptions,ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {

            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                Uri savedUri = Uri.fromFile(photoFile);
                String message = "Photo capture succeeded: " + savedUri.toString();
                Toast.makeText(getBaseContext(),message,Toast.LENGTH_LONG).show();
                Log.d(TAG,message);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "Photo capture failed: " + exception.getMessage(),exception);
            }
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                // bind the lifecycle of cameras to the lifecycle owner
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // preview use case
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                // image capture use case
                imageCapture = new ImageCapture.Builder().build();

                ImageAnalysis imageAnalyzer = new ImageAnalysis.Builder().build();
                imageAnalyzer.setAnalyzer(cameraExecutor,new LuminosityAnalyzer((luma) -> {
                    Log.d(TAG, "Average luminosity: " + luma.toString());
                }));

                // select back camera as default
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                // unbind use cases before rebinding
                cameraProvider.unbindAll();

                // bind use cases to camera
                cameraProvider.bindToLifecycle(this,cameraSelector,preview,imageCapture,imageAnalyzer);
            } catch (Exception e) {
                Log.e(TAG, "Use case binding failed", e);
            }
        },ContextCompat.getMainExecutor(this));
    }

    private boolean allPermissionsGranted() {
        boolean permissionsGranted = true;
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(getBaseContext(),permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsGranted = false;
            }
        }
        return permissionsGranted;
    }

    private File getOutputDirectory() {
        return getExternalFilesDir(Environment.DIRECTORY_PICTURES);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}