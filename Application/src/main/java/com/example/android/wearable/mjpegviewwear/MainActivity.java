/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.wearable.mjpegviewwear;

import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageApi.SendMessageResult;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.HashSet;

/**
 * Receives its own events using a listener API designed for foreground activities. Updates a data
 * item every second while it is open. Also allows user to take a photo and send that as an asset to
 * the paired wearable.
 */
public class MainActivity extends Activity implements
        MessageApi.MessageListener, NodeApi.NodeListener, ConnectionCallbacks,
        OnConnectionFailedListener {

    private static final boolean DEBUG = false;
    private static final String TAG = "MainActivity";

    /**
     * Request code for launching the Intent to resolve Google Play services errors.
     */
    private static final int REQUEST_RESOLVE_ERROR = 1000;

    private static final String START_ACTIVITY_PATH = "/start-activity";
    private static final String SEND_IMAGE_PATH = "/send-image";
    private static final String SEND_IMAGE_KEY = "image";
    private static final String NOTIFY_IMAGE_RECEIPT_PATH = "/notify-image-receipt";

    private GoogleApiClient mGoogleApiClient;
    private boolean mResolvingError = false;

    //for MJPEG Viewer
    private MjpegView mMv = null;
    String mURL;

    // for settings (network and resolution)
    private static final int REQUEST_SETTINGS = 0;

    private int mWidth = 640;
    private int mHeight = 480;

    private int mIp_ad1 = 192;
    private int mIp_ad2 = 168;
    private int mIp_ad3 = 2;
    private int mIp_ad4 = 1;
    private int mIp_port = 80;
    private String mIp_command = "?action=stream";

    private int mFrameSkip = 4;

    private boolean mSendableImage = true;

    final Handler mHandler = new Handler();

    private NotificationCompat.Builder mBuilder;
    int mNotificationId = 1;
    NotificationManager mNotifyMgr;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        LOGD(TAG, "onCreate");
        setContentView(R.layout.main_activity);

        mBuilder = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.running))
                .setOngoing(true);
        Intent resultIntent = new Intent(this, MainActivity.class);
        PendingIntent resultPendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        resultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                );
        mBuilder.setContentIntent(resultPendingIntent);
        mNotifyMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

        // for MJPEG View
        SharedPreferences preferences = getSharedPreferences("SAVED_VALUES", MODE_PRIVATE);
        mWidth = preferences.getInt("width", mWidth);
        mHeight = preferences.getInt("height", mHeight);
        mIp_ad1 = preferences.getInt("ip_ad1", mIp_ad1);
        mIp_ad2 = preferences.getInt("ip_ad2", mIp_ad2);
        mIp_ad3 = preferences.getInt("ip_ad3", mIp_ad3);
        mIp_ad4 = preferences.getInt("ip_ad4", mIp_ad4);
        mIp_port = preferences.getInt("ip_port", mIp_port);
        mIp_command = preferences.getString("ip_command", mIp_command);
        mFrameSkip = preferences.getInt("frameSkip", mFrameSkip);

        StringBuilder sb = new StringBuilder();
        String s_http = "http://";
        String s_dot = ".";
        String s_colon = ":";
        String s_slash = "/";
        sb.append(s_http);
        sb.append(mIp_ad1);
        sb.append(s_dot);
        sb.append(mIp_ad2);
        sb.append(s_dot);
        sb.append(mIp_ad3);
        sb.append(s_dot);
        sb.append(mIp_ad4);
        sb.append(s_colon);
        sb.append(mIp_port);
        sb.append(s_slash);
        sb.append(mIp_command);
        mURL = new String(sb);

        mMv = (MjpegView) findViewById(R.id.mv);
        if (mMv != null) {
            mMv.setResolution(mWidth, mHeight);
            mMv.setFrameSkip(mFrameSkip);
        }

        setTitle(R.string.title_connecting);
        new DoRead().execute(mURL);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!mResolvingError) {
            mGoogleApiClient.connect();
        }
    }

    @Override
    protected void onDestroy() {

        mNotifyMgr.cancel(mNotificationId);

        if (mMv != null) {
            if (mMv.isStreaming()) {
                mMv.stopPlayback();
            }
        }
        try {
            Thread.sleep(100);
        } catch (Exception e) {
        }
        if (!mResolvingError) {
            Wearable.MessageApi.removeListener(mGoogleApiClient, this);
            Wearable.NodeApi.removeListener(mGoogleApiClient, this);
            mGoogleApiClient.disconnect();
        }
        if (mMv != null) {
            mMv.freeCameraMemory();
        }
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.option_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.settings:
                Intent settings_intent = new Intent(MainActivity.this, SettingsActivity.class);
                settings_intent.putExtra("width", mWidth);
                settings_intent.putExtra("height", mHeight);
                settings_intent.putExtra("ip_ad1", mIp_ad1);
                settings_intent.putExtra("ip_ad2", mIp_ad2);
                settings_intent.putExtra("ip_ad3", mIp_ad3);
                settings_intent.putExtra("ip_ad4", mIp_ad4);
                settings_intent.putExtra("ip_port", mIp_port);
                settings_intent.putExtra("ip_command", mIp_command);
                settings_intent.putExtra("frameSkip", mFrameSkip);
                startActivityForResult(settings_intent, REQUEST_SETTINGS);
                return true;
            case R.id.start_wearable_activity:
                new StartWearableActivityTask().execute();
                return true;
        }
        return false;
    }

    @Override //ConnectionCallbacks
    public void onConnected(Bundle connectionHint) {
        LOGD(TAG, "Google API Client was connected");
        mResolvingError = false;
        Wearable.MessageApi.addListener(mGoogleApiClient, this);
        Wearable.NodeApi.addListener(mGoogleApiClient, this);
    }

    @Override //ConnectionCallbacks
    public void onConnectionSuspended(int cause) {
        LOGD(TAG, "Connection to Google API client was suspended");
    }

    @Override //OnConnectionFailedListener
    public void onConnectionFailed(ConnectionResult result) {
        if (mResolvingError) {
            // Already attempting to resolve an error.
            return;
        } else if (result.hasResolution()) {
            try {
                mResolvingError = true;
                result.startResolutionForResult(this, REQUEST_RESOLVE_ERROR);
            } catch (IntentSender.SendIntentException e) {
                // There was an error with the resolution intent. Try again.
                mGoogleApiClient.connect();
            }
        } else {
            Log.e(TAG, "Connection to Google API client has failed");
            mResolvingError = false;
            Wearable.MessageApi.removeListener(mGoogleApiClient, this);
            Wearable.NodeApi.removeListener(mGoogleApiClient, this);
        }
    }

    @Override //MessageListener
    public void onMessageReceived(final MessageEvent messageEvent) {
        if (messageEvent.getPath().equals(NOTIFY_IMAGE_RECEIPT_PATH)) {
            mSendableImage = true;
        }
    }

    @Override //NodeListener
    public void onPeerConnected(final Node peer) {
        LOGD(TAG, "onPeerConnected: " + peer);
    }

    @Override //NodeListener
    public void onPeerDisconnected(final Node peer) {
        LOGD(TAG, "onPeerDisconnected: " + peer);
    }

    private Collection<String> getNodes() {
        HashSet<String> results = new HashSet<String>();
        NodeApi.GetConnectedNodesResult nodes =
                Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();

        for (Node node : nodes.getNodes()) {
            results.add(node.getId());
        }

        return results;
    }

    private void sendStartActivityMessage(String node) {
        Wearable.MessageApi.sendMessage(
                mGoogleApiClient, node, START_ACTIVITY_PATH, new byte[0]).setResultCallback(
                new ResultCallback<SendMessageResult>() {
                    @Override
                    public void onResult(SendMessageResult sendMessageResult) {
                        if (!sendMessageResult.getStatus().isSuccess()) {
                            Log.e(TAG, "Failed to send message with status code: "
                                    + sendMessageResult.getStatus().getStatusCode());
                        }
                    }
                }
        );
    }

    private class StartWearableActivityTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... args) {
            mSendableImage = true;
            Collection<String> nodes = getNodes();
            for (String node : nodes) {
                sendStartActivityMessage(node);
            }
            return null;
        }
    }

    /**
     * As simple wrapper around Log.d
     */
    private static void LOGD(final String tag, String message) {
        if (Log.isLoggable(tag, Log.DEBUG)) {
            Log.d(tag, message);
        }
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_SETTINGS:
                if (resultCode == Activity.RESULT_OK) {
                    mWidth = data.getIntExtra("width", mWidth);
                    mHeight = data.getIntExtra("height", mHeight);
                    mIp_ad1 = data.getIntExtra("ip_ad1", mIp_ad1);
                    mIp_ad2 = data.getIntExtra("ip_ad2", mIp_ad2);
                    mIp_ad3 = data.getIntExtra("ip_ad3", mIp_ad3);
                    mIp_ad4 = data.getIntExtra("ip_ad4", mIp_ad4);
                    mIp_port = data.getIntExtra("ip_port", mIp_port);
                    mIp_command = data.getStringExtra("ip_command");
                    mFrameSkip = data.getIntExtra("frameSkip", mFrameSkip);

                    if (mMv != null) {
                        mMv.setResolution(mWidth, mHeight);
                        mMv.setFrameSkip(mFrameSkip);
                    }
                    SharedPreferences preferences = getSharedPreferences("SAVED_VALUES", MODE_PRIVATE);
                    SharedPreferences.Editor editor = preferences.edit();
                    editor.putInt("width", mWidth);
                    editor.putInt("height", mHeight);
                    editor.putInt("ip_ad1", mIp_ad1);
                    editor.putInt("ip_ad2", mIp_ad2);
                    editor.putInt("ip_ad3", mIp_ad3);
                    editor.putInt("ip_ad4", mIp_ad4);
                    editor.putInt("ip_port", mIp_port);
                    editor.putString("ip_command", mIp_command);
                    editor.putInt("frameSkip", mFrameSkip);

                    editor.commit();

                    new RestartApp().execute();
                }
                break;
        }
    }

    public void setImageError() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                setTitle(R.string.title_imageerror);
                return;
            }
        });
    }

    public class DoRead extends AsyncTask<String, Void, MjpegInputStream> {
        protected MjpegInputStream doInBackground(String... url) {
            //TODO: if camera has authentication deal with it and don't just not work
            HttpResponse res = null;
            DefaultHttpClient httpclient = new DefaultHttpClient();
            HttpParams httpParams = httpclient.getParams();
            HttpConnectionParams.setConnectionTimeout(httpParams, 5 * 1000);
            HttpConnectionParams.setSoTimeout(httpParams, 5 * 1000);
            if (DEBUG) Log.d(TAG, "1. Sending http request");
            try {
                res = httpclient.execute(new HttpGet(URI.create(url[0])));
                if (DEBUG)
                    Log.d(TAG, "2. Request finished, status = " + res.getStatusLine().getStatusCode());
                if (res.getStatusLine().getStatusCode() == 401) {
                    //You must turn off camera User Access Control before this will work
                    return null;
                }
                return new MjpegInputStream(res.getEntity().getContent());
            } catch (ClientProtocolException e) {
                if (DEBUG) {
                    e.printStackTrace();
                    Log.d(TAG, "Request failed-ClientProtocolException", e);
                }
                //Error connecting to camera
            } catch (IOException e) {
                if (DEBUG) {
                    e.printStackTrace();
                    Log.d(TAG, "Request failed-IOException", e);
                }
                //Error connecting to camera
            }
            return null;
        }

        protected void onPostExecute(MjpegInputStream result) {
            mMv.setSource(result);
            if (result != null) {
                result.setSkip(1);
                setTitle(R.string.app_name);
                mNotifyMgr.notify(mNotificationId, mBuilder.build());
            } else {
                setTitle(R.string.title_disconnected);
            }
            mMv.setDisplayMode(MjpegView.SIZE_BEST_FIT);
            mMv.showFps(false);
        }
    }

    public class RestartApp extends AsyncTask<Void, Void, Void> {
        protected Void doInBackground(Void... v) {
            MainActivity.this.finish();
            return null;
        }

        protected void onPostExecute(Void v) {
            startActivity((new Intent(MainActivity.this, MainActivity.class)));
        }
    }

    public void sendImage2Wear(Bitmap srcBmp) {
        if (mSendableImage) {

            Bitmap dstBmp = Bitmap.createBitmap(
                    srcBmp,
                    srcBmp.getWidth() / 2 - srcBmp.getHeight() / 2,
                    0,
                    srcBmp.getHeight(),
                    srcBmp.getHeight()
            );

            Bitmap dstBmp2 = Bitmap.createScaledBitmap(dstBmp, 280, 280, false);

            sendImage(toAsset(dstBmp2));
        }
    }

    /**
     * Builds an {@link com.google.android.gms.wearable.Asset} from a bitmap. The image that we get
     * back from the camera in "data" is a thumbnail size. Typically, your image should not exceed
     * 320x320 and if you want to have zoom and parallax effect in your app, limit the size of your
     * image to 640x400. Resize your image before transferring to your wearable device.
     */
    private static Asset toAsset(Bitmap bitmap) {
        ByteArrayOutputStream byteStream = null;
        try {
            byteStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteStream);
            return Asset.createFromBytes(byteStream.toByteArray());
        } finally {
            if (null != byteStream) {
                try {
                    byteStream.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    /**
     * Sends the asset that was created form the photo we took by adding it to the Data Item store.
     */
    private void sendImage(Asset asset) {
        PutDataMapRequest dataMap = PutDataMapRequest.create(SEND_IMAGE_PATH);
        dataMap.getDataMap().putAsset(SEND_IMAGE_KEY, asset);
        PutDataRequest request = dataMap.asPutDataRequest();
        Wearable.DataApi.putDataItem(mGoogleApiClient, request);
    }
}
