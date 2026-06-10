/**
 * QR scanner for Add Friend — CameraX + ML Kit.
 */
package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.content.Intent;
import android.widget.ImageButton;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

public class QrScanActivity extends AppCompatActivity
{
    private static final String TAG = "trifa.QrScanActivity";
    public static final String EXTRA_RESULT = "qr_result";

    private PreviewView previewView;
    private boolean scanned = false;
    private ProcessCameraProvider cameraProvider;
    private ExecutorService analysisExecutor;
    private BarcodeScanner barcodeScanner;

    private final ActivityResultLauncher<String> requestCameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted ->
            {
                if (granted)
                {
                    previewView.post(this::startCamera);
                }
                else
                {
                    Toast.makeText(this, R.string.qr_scan_permission_denied, Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_scan);

        previewView = findViewById(R.id.preview_view);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);

        final ImageButton closeButton = findViewById(R.id.qr_scan_close);
        closeButton.setOnClickListener(v -> finish());

        analysisExecutor = Executors.newSingleThreadExecutor();
        final BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        barcodeScanner = BarcodeScanning.getClient(options);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        {
            previewView.post(this::startCamera);
        }
        else
        {
            requestCameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        if ((cameraProvider != null) &&
            (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED))
        {
            previewView.post(this::bindCamera);
        }
    }

    @Override
    protected void onDestroy()
    {
        if (barcodeScanner != null)
        {
            barcodeScanner.close();
        }
        if (analysisExecutor != null)
        {
            analysisExecutor.shutdown();
        }
        super.onDestroy();
    }

    private void startCamera()
    {
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() ->
        {
            try
            {
                cameraProvider = future.get();
                bindCamera();
            }
            catch (Exception e)
            {
                Log.e(TAG, "startCamera failed", e);
                showCameraError();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera()
    {
        if ((cameraProvider == null) || scanned)
        {
            return;
        }

        final Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        final ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build();

        analysis.setAnalyzer(analysisExecutor, imageProxy -> analyzeFrame(imageProxy));

        cameraProvider.unbindAll();

        if (tryBind(cameraProvider, preview, analysis, CameraSelector.DEFAULT_BACK_CAMERA))
        {
            return;
        }

        if (tryBind(cameraProvider, preview, analysis, CameraSelector.DEFAULT_FRONT_CAMERA))
        {
            return;
        }

        Log.e(TAG, "bindCamera: no usable camera");
        showCameraError();
    }

    private boolean tryBind(@NonNull ProcessCameraProvider provider,
                            @NonNull Preview preview,
                            @NonNull ImageAnalysis analysis,
                            @NonNull CameraSelector selector)
    {
        try
        {
            provider.bindToLifecycle(this, selector, preview, analysis);
            return true;
        }
        catch (Exception e)
        {
            Log.w(TAG, "tryBind failed for " + selector, e);
            provider.unbindAll();
            return false;
        }
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFrame(@NonNull ImageProxy imageProxy)
    {
        if (scanned)
        {
            imageProxy.close();
            return;
        }

        if (imageProxy.getImage() == null)
        {
            imageProxy.close();
            return;
        }

        final InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(),
                imageProxy.getImageInfo().getRotationDegrees());

        barcodeScanner.process(image)
                .addOnSuccessListener(barcodes ->
                {
                    if (scanned || (barcodes == null) || barcodes.isEmpty())
                    {
                        return;
                    }
                    final String value = barcodes.get(0).getRawValue();
                    if ((value == null) || value.isEmpty())
                    {
                        return;
                    }
                    scanned = true;
                    runOnUiThread(() ->
                    {
                        final Intent result = new Intent();
                        result.putExtra(EXTRA_RESULT, value);
                        setResult(RESULT_OK, result);
                        finish();
                    });
                })
                .addOnFailureListener(e -> Log.w(TAG, "barcode scan failed", e))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void showCameraError()
    {
        runOnUiThread(() ->
        {
            Toast.makeText(this, R.string.qr_scan_camera_error, Toast.LENGTH_LONG).show();
            finish();
        });
    }
}
