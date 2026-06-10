/**
 * Decode a QR code from a gallery image (ML Kit).
 */
package com.zoffcc.applications.trifa;

import android.content.Context;
import android.net.Uri;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.io.IOException;

public final class QrImageDecoder
{
    public interface Callback
    {
        void onSuccess(String value);

        void onFailure();
    }

    private QrImageDecoder()
    {
    }

    public static void decodeFromUri(final Context context, final Uri uri, final Callback callback)
    {
        final InputImage image;
        try
        {
            image = InputImage.fromFilePath(context, uri);
        }
        catch (IOException e)
        {
            callback.onFailure();
            return;
        }

        final BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        final BarcodeScanner scanner = BarcodeScanning.getClient(options);

        scanner.process(image)
                .addOnSuccessListener(barcodes ->
                {
                    if ((barcodes != null) && (!barcodes.isEmpty()))
                    {
                        final String value = barcodes.get(0).getRawValue();
                        if ((value != null) && (!value.isEmpty()))
                        {
                            callback.onSuccess(value);
                            return;
                        }
                    }
                    callback.onFailure();
                })
                .addOnFailureListener(e -> callback.onFailure());
    }
}
