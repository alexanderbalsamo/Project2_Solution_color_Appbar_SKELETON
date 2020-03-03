package com.example.solution_color;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import android.os.Environment;
import android.provider.MediaStore;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;
import androidx.preference.SeekBarPreference;

import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.library.bitmap_utilities.BitMap_Helpers;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends AppCompatActivity implements OnSharedPreferenceChangeListener {

    //these are constants and objects that I used, use them if you wish
    private static final String DEBUG_TAG = "Balsamo";
    private static final String ORIGINAL_FILE = "origfile.png";
    private static final String PROCESSED_FILE = "procfile.png";

    private static final int TAKE_PICTURE = 1;
    private static final double SCALE_FROM_0_TO_255 = 2.55;
    private static final int DEFAULT_COLOR_PERCENT = 3;
    private static final int DEFAULT_BW_PERCENT = 15;
    private static final int CAMERA_REQUEST_CODE = 12345;

    //preferences
    private int saturation = DEFAULT_COLOR_PERCENT;
    private int bwPercent = DEFAULT_BW_PERCENT;
    private String shareSubject;
    private String shareText;

    //need these to track changes
    private SharedPreferences myPreference;
    private SharedPreferences.OnSharedPreferenceChangeListener listener = null;
    private boolean enablePreferenceListener;

    //where images go
    private String originalImagePath;   //where orig image is
    private String processedImagePath;  //where processed image is
    private Uri outputFileUri;          //tells camera app where to store image

    //used to measure screen size
    int screenheight;
    int screenwidth;

    private ImageView myImage;

    //these guys will hog space
    Bitmap bmpOriginal;                 //original image
    Bitmap bmpThresholded;              //the black and white version of original image
    Bitmap bmpThresholdedColor;         //the colorized version of the black and white image

    // Permissions Here
    private static final String[] PERMISSIONS = {Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.CAMERA};
    private static final int PERMS_REQ_CODE = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar)findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        //dont display these
        getSupportActionBar().setDisplayShowTitleEnabled(false);

        FloatingActionButton fab = findViewById(R.id.buttonTakePicture);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!verifyPermissions())
                    return;
                doTakePicture();
            }
        });

        //get the default image
        myImage = (ImageView) findViewById(R.id.imageView1);

        //TODO manage the preferences and the shared preference listeners
        //TODO and get the values already there getPrefValues(settings);
        //TODO use getPrefValues(SharedPreferences settings)

        // Set up preferences and listener
        if (myPreference == null) {
            myPreference = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        }
        if (listener == null){
            listener = this;
        }
        myPreference.registerOnSharedPreferenceChangeListener(listener);

        getPrefValues(myPreference);

        // Fetch screen height and width,
        DisplayMetrics metrics = this.getResources().getDisplayMetrics();
        screenheight = metrics.heightPixels;
        screenwidth = metrics.widthPixels;

        try {
            setUpFileSystem();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void setImage() {
        //prefer to display processed image if available
        bmpThresholded = Camera_Helpers.loadAndScaleImage(processedImagePath, screenheight, screenwidth);
        if (bmpThresholded != null) {
            myImage.setImageBitmap(bmpThresholded);
            Log.d(DEBUG_TAG, "setImage: myImage.setImageBitmap(bmpThresholded) set");
            return;
        }

        //otherwise fall back to unprocessd photo
        bmpOriginal = Camera_Helpers.loadAndScaleImage(originalImagePath, screenheight, screenwidth);
        if (bmpOriginal != null) {
            myImage.setImageBitmap(bmpOriginal);
            Log.d(DEBUG_TAG, "setImage: myImage.setImageBitmap(bmpOriginal) set");
            return;
        }

        //worst case get from default image
        //save this for restoring
        bmpOriginal = BitMap_Helpers.copyBitmap(myImage.getDrawable());
        //TODO scaling original install of image (5% extra credit)
        Log.d(DEBUG_TAG, "setImage: bmpOriginal copied");
    }

    //TODO use this to set the following member preferences whenever preferences are changed.
    //TODO Please ensure that this function is called by your preference change listener
    private void getPrefValues(SharedPreferences settings) {
        shareSubject = settings.getString("shareSubject", "Default");
        shareText = settings.getString("shareText", "Default");
        saturation = settings.getInt("saturation", DEFAULT_COLOR_PERCENT);
        bwPercent = settings.getInt("bwPercent", DEFAULT_BW_PERCENT);
    }

    private void setPrefValues(SharedPreferences settings) {
        SharedPreferences.Editor editor  = settings.edit();
        editor.putString("shareSubject", String.valueOf(R.string.sharemessage));
        editor.putString("shareText", String.valueOf(R.string.shareTitle));

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }


    private void setUpFileSystem() throws IOException {
        if (!verifyPermissions()){
            return;
        }

        //get some paths
        // Create the File where the photo should go
        File photoFile = createImageFile(ORIGINAL_FILE);
        originalImagePath = photoFile.getAbsolutePath();

        File processedfile = createImageFile(PROCESSED_FILE);
        processedImagePath = processedfile.getAbsolutePath();

        //worst case get from default image
        //save this for restoring
        if (bmpOriginal == null)
            bmpOriginal = BitMap_Helpers.copyBitmap(myImage.getDrawable());

        setImage();
    }

    String mCurrentPhotoPath;
    private File createImageFile(final String fn) throws IOException {
        try {

            // get external directories that the media scanner scans
            File[] storageDir = getExternalMediaDirs();

            //create a file
            File imagefile = new File(storageDir[0], fn+".png");

            //make sure directory is there, (it should be)
            if (!storageDir[0].exists()) {
                if (!storageDir[0].mkdirs()) {
                    Log.e(DEBUG_TAG, "Failed to create file in: " + storageDir[0]);
                    return null;
                }
            }

            //make file where image will be stored
            imagefile.createNewFile();

            // Save a file: path for use with ACTION_VIEW intents
            mCurrentPhotoPath = imagefile.getAbsolutePath();
            return imagefile;

        } catch (IOException ex) {
            // Error occurred while creating the File
            Toast.makeText(this, "Horrible biz here, IOException occurred", Toast.LENGTH_SHORT).show();
            return null;
        }
    }

    //DUMP for students
    ////////////////////////////////////////////////////////////////////////////////////////////////////////
    // permissions

    /***
     * callback from requestPermissions
     * @param permsRequestCode  user defined code passed to requestpermissions used to identify what callback is coming in
     * @param permissions       list of permissions requested
     * @param grantResults      //results of those requests
     */
    @Override
    public void onRequestPermissionsResult(int permsRequestCode, String[] permissions, int[] grantResults) {
        boolean allGranted = true;
        switch (permsRequestCode){
            case PERMS_REQ_CODE:
                for (int result: grantResults){
                    allGranted = allGranted&&(result==PackageManager.PERMISSION_GRANTED);
                }
                break;
        }
        if (allGranted){
            try {
                setUpFileSystem();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean verifyPermissions() {
        //loop through all permissions seeing if they are ALL granted
        //if ALL granted, return true
        boolean allGranted = true;
        for (String permission:PERMISSIONS){
            //a single false causes allGranted to be false
            allGranted = allGranted && (ActivityCompat.checkSelfPermission(this, permission ) == PackageManager.PERMISSION_GRANTED);
        }

        if (!allGranted){
            // Missing some permissions, nudge user
            for (String permission : PERMISSIONS){
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                    Snackbar.make(findViewById(android.R.id.content), permission+"Need Permissions Granted to Proceed!", Snackbar.LENGTH_LONG).show();
                }
            }
            // ask for permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(PERMISSIONS, PERMS_REQ_CODE);
            }
        }

        //and return false until they are granted
        return allGranted;
    }

    //take a picture and store it on external storage
    public void doTakePicture() {
        // create intent to take picture with camera and specify storage
        // location so we can easily get it
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

        //create a place to store the photo
        File file = new File(Environment.getExternalStorageDirectory(), "implicit.jpg");
        outputFileUri = Uri.fromFile(file);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, outputFileUri);

        if (intent.resolveActivity(getPackageManager()) != null) {
            // Create the File where the photo should go
            File photoFile = null;
            try {
                photoFile = createImageFile(ORIGINAL_FILE);
            } catch (IOException ex) {
                // Error occurred while creating the File
                Toast.makeText(this, "Horrible biz here, IOException occurred", Toast.LENGTH_SHORT).show();
                return;
            }

            // Continue only if the File was successfully created
            //  see https://developer.android.com/reference/androidx/core/content/FileProvider
            if (photoFile != null) {
                Uri photoURI = FileProvider.getUriForFile(this,
                        "com.example.solution_color.fileprovider",
                        photoFile);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
                startActivityForResult(intent, TAKE_PICTURE);
            }
       }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == TAKE_PICTURE) {
            takepicture(resultCode);
        }
    }

    private void takepicture(int resultCode) {
        if (resultCode == RESULT_OK) {
            setImage();
            //TODO picture only saves after processing for some reason

            // get rid of image so we don't hog memory
            File file = new File(mCurrentPhotoPath);
            boolean deleted = file.delete();
        }
    }

    /**
     * delete original and processed images, then rescan media paths to pick up that they are gone.
     */
    private void doReset() {
        //do we have needed permissions?
        if (!verifyPermissions()) {
            return;
        }
        //delete the files
        Camera_Helpers.delSavedImage(originalImagePath);
        Camera_Helpers.delSavedImage(processedImagePath);
        bmpThresholded = null;
        bmpOriginal = null;

        myImage.setImageResource(R.drawable.gutters);
        myImage.setScaleType(ImageView.ScaleType.FIT_CENTER);//what the hell? why both
        myImage.setScaleType(ImageView.ScaleType.FIT_XY);

        //worst case get from default image
        //save this for restoring
        bmpOriginal = BitMap_Helpers.copyBitmap(myImage.getDrawable());

    }

    public void doSketch() {
        //do we have needed permissions?
        if (!verifyPermissions()) {
            return;
        }

        //sketchify the image
        if (bmpOriginal == null){
            Log.e(DEBUG_TAG, "doSketch: bmpOriginal = null");
            return;
        }
        bmpThresholded = BitMap_Helpers.thresholdBmp(bmpOriginal, bwPercent);

        //set image
        myImage.setImageBitmap(bmpThresholded);

        //save to file for possible email
        Camera_Helpers.saveProcessedImage(bmpThresholded, processedImagePath);
        scanSavedMediaFile(processedImagePath);
    }

    public void doColorize() {
        //TODO only works after sketch
        //do we have needed permissions?
        if (!verifyPermissions()) {
            return;
        }

        //colorize the image
        if (bmpOriginal == null){
            Log.e(DEBUG_TAG, "doColorize: bmpOriginal = null");
            return;
        }
        //if not thresholded yet then do nothing
        if (bmpThresholded == null){
            Log.e(DEBUG_TAG, "doColorize: bmpThresholded not thresholded yet");
            return;
        }

        //otherwise color the bitmap
        bmpThresholdedColor = BitMap_Helpers.colorBmp(bmpOriginal, saturation);

        //takes the thresholded image and overlays it over the color one
        //so edges are well defined
        BitMap_Helpers.merge(bmpThresholdedColor, bmpThresholded);

        //set background to new image
        myImage.setImageBitmap(bmpThresholdedColor);

        //save to file for possible email
        Camera_Helpers.saveProcessedImage(bmpThresholdedColor, processedImagePath);
        scanSavedMediaFile(processedImagePath);
    }

    public void doShare() {
        //do we have needed permissions?
        if (!verifyPermissions()) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_EMAIL, "alexander.balsamo.18@cnu.edu");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Project2 Picture!");
        intent.putExtra(Intent.EXTRA_TEXT, "Here is my Processed Photo from my very own camera app.");
        File file = new File(Environment.getExternalStorageDirectory(), mCurrentPhotoPath);
        if (!file.exists() || !file.canRead()){
            return;
        }
        Uri uri = Uri.fromFile(file);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
        startActivity(Intent.createChooser(intent, "Send Email"));

        //TODO share the processed image with appropriate subject, text and file URI
        //TODO the subject and text should come from the preferences set in the Settings Activity

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        switch (id){

            case R.id.revertButt:
                doReset();
                break;

            case R.id.editButt:
                doSketch();
                break;

            case R.id.viewButt:
                doColorize();
                break;

            case R.id.shareButt:
                doShare();
                break;

            case R.id.action_settings:
                Intent settingsIntent = new Intent(this,SettingsActivity.class);
                startActivity(settingsIntent);
                break;
        }
        return super.onOptionsItemSelected(item);   //default
    }

    //TODO set up pref changes
    @Override
    public void onSharedPreferenceChanged(SharedPreferences arg0, String arg1) {
        Toast.makeText(MainActivity.this, "Changed " + arg1, Toast.LENGTH_SHORT).show();    }



    /**
     * Notifies the OS to index the new image, so it shows up in Gallery.
     * see https://www.programcreek.com/java-api-examples/index.php?api=android.media.MediaScannerConnection
     */
    private void scanSavedMediaFile( final String path) {
        // silly array hack so closure can reference scannerConnection[0] before it's created
        final MediaScannerConnection[] scannerConnection = new MediaScannerConnection[1];
        try {
            MediaScannerConnection.MediaScannerConnectionClient scannerClient = new MediaScannerConnection.MediaScannerConnectionClient() {
                public void onMediaScannerConnected() {
                    scannerConnection[0].scanFile(path, null);
                }

                @Override
                public void onScanCompleted(String path, Uri uri) {

                }

            };
            scannerConnection[0] = new MediaScannerConnection(this, scannerClient);
            scannerConnection[0].connect();
        } catch (Exception ignored) {
        }
    }
}

