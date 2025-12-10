/**
 * Copy this file into your Android project and call init(). If your project
 * contains fonts and/or certificates in assets, uncomment copyFonts() and/or
 * copyCaCertificates() lines in init().
 */
package org.freedesktop.gstreamer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import android.content.Context;
import android.content.res.AssetManager;
import android.system.Os;

public class GStreamer {
    private static native void nativeInit(Context context) throws Exception;
    
    private static boolean libraryLoaded = false;

    public static void init(Context context) throws Exception {
        // Load the native libraries first
        if (!libraryLoaded) {
            try {
                // Load GStreamer core library first
                System.loadLibrary("gstreamer_android");
                android.util.Log.i("GStreamer", "libgstreamer_android.so loaded");
                
                // Load our native library which contains the nativeInit JNI implementation
                System.loadLibrary("orbistream_native");
                android.util.Log.i("GStreamer", "liborbistream_native.so loaded");
                
                libraryLoaded = true;
                android.util.Log.i("GStreamer", "All GStreamer native libraries loaded successfully");
            } catch (UnsatisfiedLinkError e) {
                android.util.Log.e("GStreamer", "Failed to load native library: " + e.getMessage());
                throw new Exception("Failed to load native libraries: " + e.getMessage());
            }
        }
        
        copyFonts(context);
        copyCaCertificates(context);
        nativeInit(context);
    }

    private static void copyFonts(Context context) {
        AssetManager assetManager = context.getAssets();
        File filesDir = context.getFilesDir();
        File fontsFCDir = new File (filesDir, "fontconfig");
        File fontsDir = new File (fontsFCDir, "fonts");
        File fontsCfg = new File (fontsFCDir, "fonts.conf");

        fontsDir.mkdirs();

        try {
            /* Copy the config file */
            copyFile (assetManager, "fontconfig/fonts.conf", fontsCfg);
            /* Copy the fonts */
            for(String filename : assetManager.list("fontconfig/fonts/truetype")) {
                File font = new File(fontsDir, filename);
                copyFile (assetManager, "fontconfig/fonts/truetype/" + filename, font);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void copyCaCertificates(Context context) {
        AssetManager assetManager = context.getAssets();
        File filesDir = context.getFilesDir();
        File sslDir = new File (filesDir, "ssl");
        File certsDir = new File (sslDir, "certs");
        File certs = new File (certsDir, "ca-certificates.crt");

        certsDir.mkdirs();

        try {
            /* Copy the certificates file */
            copyFile (assetManager, "ssl/certs/ca-certificates.crt", certs);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void copyFile(AssetManager assetManager, String assetPath, File outFile) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        IOException exception = null;

        if (outFile.exists())
            outFile.delete();

        try {
            in = assetManager.open(assetPath);
            out = new FileOutputStream(outFile);

            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } catch (IOException e) {
            exception = e;
        } finally {
            if (in != null)
                try {
                    in.close();
                } catch (IOException e) {
                    if (exception == null)
                        exception = e;
                }
            if (out != null)
                try {
                    out.close();
                } catch (IOException e) {
                    if (exception == null)
                        exception = e;
                }
            if (exception != null)
                throw exception;
        }
    }
}
