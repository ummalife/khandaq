/**
 * QR scanner for Add Friend — CameraX + ML Kit (replaces ZXing CaptureActivity on Android 15+).
 */
package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

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

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_scan);
        previewView = findViewById(R.id.preview_view);
        startCamera();
    }

    private void startCamera()
    {
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() ->
        {
            try
            {
                bindCamera(future.get());
            }
            catch (Exception e)
            {
                Log.e(TAG, "startCamera failed", e);
                showCameraError();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera(@NonNull ProcessCameraProvider provider)
    {
        final Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        final ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        final BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        final BarcodeScanner scanner = BarcodeScanning.getClient(options);

        analysis.setAnalyzer(ContextCompat.getMainExecutor(this), imageProxy -> analyzeFrame(scanner, imageProxy));

        provider.unbindAll();
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFrame(@NonNull BarcodeScanner scanner, @NonNull ImageProxy imageProxy)
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

        scanner.process(image)
                .addOnSuccessListener(barcodes ->
                {
                    if (scanned || barcodes.isEmpty())
                    {
                        return;
                    }
                    final String value = barcodes.get(0).getRawValue();
                    if (value == null || value.isEmpty())
                    {
                        return;
                    }
                    scanned = true;
                    final Intent result = new Intent();
                    result.putExtra(EXTRA_RESULT, value);
                    setResult(RESULT_OK, result);
                    finish();
                })
                .addOnFailureListener(e -> Log.w(TAG, "barcode scan failed", e))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void showCameraError()
    {
        Toast.makeText(this, R.string.qr_scan_camera_error, Toast.LENGTH_LONG).show();
        finish();
    }
}
