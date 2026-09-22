package com.shir.stems;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.PowerManager;

public class SeparationService extends IntentService {
    public static final String ACTION_PROGRESS = "com.shir.stems.PROGRESS";
    public static final String ACTION_DONE = "com.shir.stems.DONE";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_OUTPUT = "output";
    private PowerManager.WakeLock wakeLock;

    public SeparationService() { super("ShirSeparationService"); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SHIR:separation");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();

            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            Intent open=new Intent(this,MainActivity.class);
            PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n=new Notification.Builder(this)
                    .setContentTitle("SHIR")
                    .setContentText("מפריד את השיר לערוצים אופליין…")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
            nm.notify(77,n);
        } catch(Exception ignored) {}
        return super.onStartCommand(intent,flags,startId);
    }

    @Override protected void onHandleIntent(Intent intent) {
        final String input=intent.getStringExtra("input");
        final String model=intent.getStringExtra("model");
        final String output=intent.getStringExtra("output");
        if(input==null || model==null || output==null) return;

        new java.io.File(output).mkdirs();

        final boolean[] success = new boolean[]{false};
        Thread worker=new Thread(new Runnable() {
            @Override public void run() {
                try {
                    success[0] = NativeSeparator.nativeSeparate(input,model,output);
                } catch(Throwable t) {
                    success[0] = false;
                }
            }
        });
        worker.start();

        while(worker.isAlive()) {
            Intent b=new Intent(ACTION_PROGRESS);
            b.putExtra(EXTRA_PROGRESS,NativeSeparator.nativeProgress());
            b.putExtra(EXTRA_STATUS,NativeSeparator.nativeStatus());
            sendBroadcast(b);
            try { Thread.sleep(500); } catch(InterruptedException ignored) {}
        }

        String nativeStatus = NativeSeparator.nativeStatus();
        java.io.File mix = new java.io.File(output, "mix.wav");
        if (success[0] && mix.exists() && mix.length() > 44) {
            Intent done=new Intent(ACTION_DONE);
            done.putExtra(EXTRA_PROGRESS,1.0f);
            done.putExtra(EXTRA_STATUS,"done");
            done.putExtra(EXTRA_OUTPUT,output);
            sendBroadcast(done);
        } else {
            Intent failed=new Intent(ACTION_PROGRESS);
            failed.putExtra(EXTRA_PROGRESS,NativeSeparator.nativeProgress());
            failed.putExtra(EXTRA_STATUS, nativeStatus == null ? "native_error" : nativeStatus);
            sendBroadcast(failed);
        }

        try { ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(77); } catch(Exception ignored) {}
    }

    @Override public void onDestroy() {
        if(wakeLock!=null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
