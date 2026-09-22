package com.shir.stems;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO=41;
    private LinearLayout root, stemList;
    private TextView song,status,time;
    private ProgressBar progress;
    private Button playAll,separate,choose;
    private String inputPath;
    private String outputPath;
    private final String[] names={"DRUMS","BASS","OTHER","VOCALS"};
    private final MediaPlayer[] players=new MediaPlayer[4];
    private final boolean[] muted=new boolean[4];
    private final float[] volumes={1f,1f,1f,1f};
    private int focusIndex=0;
    private boolean prepared=false;
    private long startedAt=0;

    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            if(SeparationService.ACTION_PROGRESS.equals(i.getAction())){
                float p=i.getFloatExtra(SeparationService.EXTRA_PROGRESS,0);
                String s=i.getStringExtra(SeparationService.EXTRA_STATUS);
                progress.setProgress((int)(p*1000));
                status.setText(statusText(s,p));
            } else if(SeparationService.ACTION_DONE.equals(i.getAction())){
                float p=i.getFloatExtra(SeparationService.EXTRA_PROGRESS,0);
                outputPath=i.getStringExtra(SeparationService.EXTRA_OUTPUT);
                progress.setProgress((int)(p*1000));
                status.setText("ההפרדה הסתיימה — מכין את הנגן");
                loadSeparatedPlayers();
            }
        }
    };

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        buildUi();
        registerReceiver(receiver,new IntentFilter(SeparationService.ACTION_PROGRESS));
        registerReceiver(receiver,new IntentFilter(SeparationService.ACTION_DONE));
    }

    private TextView tv(String s,float size){
        TextView t=new TextView(this);
        t.setText(s);t.setTextSize(size);t.setTextColor(Color.rgb(244,247,250));
        t.setPadding(18,10,18,10); return t;
    }

    private Button bt(String s){
        Button b=new Button(this);
        b.setText(s);b.setTextSize(14);b.setAllCaps(false);
        b.setTextColor(Color.rgb(244,247,250));b.setFocusable(true);return b;
    }

    private void buildUi(){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(11,12,16));root.setPadding(18,14,18,10);

        LinearLayout head=new LinearLayout(this);head.setOrientation(LinearLayout.HORIZONTAL);
        TextView h=tv("SHIR  •  OFFLINE STEM SEPARATOR",19);h.setTextColor(Color.rgb(102,252,241));
        head.addView(h,new LinearLayout.LayoutParams(0,56,1));
        choose=bt("בחר שיר");head.addView(choose,new LinearLayout.LayoutParams(140,56));
        root.addView(head);
        choose.setOnClickListener(v->pickAudio());

        song=tv("לא נבחר קובץ",16);root.addView(song,new LinearLayout.LayoutParams(-1,46));
        status=tv("הכול נשאר במכשיר.",13);status.setTextColor(Color.rgb(145,160,173));
        root.addView(status,new LinearLayout.LayoutParams(-1,44));

        LinearLayout controls=new LinearLayout(this);
        playAll=bt("▶ נגן הכול");playAll.setEnabled(false);
        separate=bt("✦ הפרד ערוצים");separate.setEnabled(false);
        controls.addView(playAll,new LinearLayout.LayoutParams(150,58));
        controls.addView(separate,new LinearLayout.LayoutParams(0,58,1));
        time=tv("00:00 / 00:00",13);time.setGravity(17);
        controls.addView(time,new LinearLayout.LayoutParams(160,58));
        root.addView(controls);
        playAll.setOnClickListener(v->toggleAll());
        separate.setOnClickListener(v->startSeparation());

        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);progress.setProgress(0);root.addView(progress,new LinearLayout.LayoutParams(-1,18));

        TextView label=tv("ערוצים  •  Solo / Mute / Volume",15);label.setTextColor(Color.rgb(102,252,241));
        root.addView(label,new LinearLayout.LayoutParams(-1,40));

        stemList=new LinearLayout(this);stemList.setOrientation(LinearLayout.VERTICAL);
        for(int x=0;x<4;x++) addStem(x);
        root.addView(stemList,new LinearLayout.LayoutParams(-1,0,1));

        TextView hint=tv("↑ ↓ בחירה   1–4 ערוץ   ENTER נגן/עצור   ←→ עוצמה   BACK חזרה",11);
        hint.setTextColor(Color.rgb(145,160,173));root.addView(hint,new LinearLayout.LayoutParams(-1,38));

        setContentView(root);updateFocus();
    }

    private void addStem(final int index){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        Button b=bt((index+1)+"  "+names[index]);b.setTag(index);
        b.setOnClickListener(v->{focusIndex=index;updateFocus();toggleStem(index);});
        row.addView(b,new LinearLayout.LayoutParams(125,64));

        final Button mute=bt("MUTE");row.addView(mute,new LinearLayout.LayoutParams(90,64));
        mute.setOnClickListener(v->{muted[index]=!muted[index];mute.setText(muted[index]?"UNMUTE":"MUTE");applyVolume(index);});

        SeekBar bar=new SeekBar(this);bar.setMax(100);bar.setProgress(100);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int p,boolean fromUser){
                volumes[index]=p/100f;applyVolume(index);
            }
            @Override public void onStartTrackingTouch(SeekBar s){}
            @Override public void onStopTrackingTouch(SeekBar s){}
        });
        row.addView(bar,new LinearLayout.LayoutParams(0,64,1));
        row.setTag(index);stemList.addView(row,new LinearLayout.LayoutParams(-1,66));
    }

    private void updateFocus(){
        for(int i=0;i<stemList.getChildCount();i++)
            stemList.getChildAt(i).setBackgroundColor(i==focusIndex?Color.rgb(38,70,75):Color.rgb(31,40,51));
    }

    private void pickAudio(){
        Intent i=new Intent(Intent.ACTION_GET_CONTENT);i.setType("audio/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK_AUDIO);
    }

    @Override protected void onActivityResult(int r,int result,Intent data){
        super.onActivityResult(r,result,data);
        if(r==PICK_AUDIO && result==RESULT_OK && data!=null && data.getData()!=null){
            try{
                Uri u=data.getData();
                inputPath=copyUriToCache(u);
                song.setText(u.getLastPathSegment()==null?"קובץ נבחר":u.getLastPathSegment());
                status.setText("מוכן • אפשר לנגן או להפריד");
                choose.setEnabled(true);playAll.setEnabled(true);separate.setEnabled(true);
            }catch(Exception e){Toast.makeText(this,"לא הצלחתי לקרוא את הקובץ",Toast.LENGTH_LONG).show();}
        }
    }

    private String copyUriToCache(Uri uri)throws Exception{
        File dir=new File(getCacheDir(),"inputs");dir.mkdirs();
        File out=new File(dir,"input_"+System.currentTimeMillis()+".audio");
        InputStream in=getContentResolver().openInputStream(uri);
        FileOutputStream fos=new FileOutputStream(out);
        byte[] buf=new byte[64*1024];int n;
        while((n=in.read(buf))!=-1)fos.write(buf,0,n);
        in.close();fos.close();return out.getAbsolutePath();
    }

    private String ensureModel()throws Exception{
        File dir=new File(getFilesDir(),"models");dir.mkdirs();
        File model=new File(dir,"ggml-htdemucs-4s-f16.bin");
        if(model.exists() && model.length()>80000000)return model.getAbsolutePath();
        InputStream in=getAssets().open("ggml-htdemucs-4s-f16.bin");
        FileOutputStream out=new FileOutputStream(model);
        byte[] buf=new byte[1024*1024];int n;
        while((n=in.read(buf))!=-1)out.write(buf,0,n);
        in.close();out.close();return model.getAbsolutePath();
    }

    private void startSeparation(){
        if(inputPath==null)return;
        try{
            final String model=ensureModel();
            outputPath=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),"SHIR/"+System.currentTimeMillis()).getAbsolutePath();
            new File(outputPath).mkdirs();
            status.setText("מכין מנוע הפרדה מקומי…");
            progress.setProgress(0);
            Intent s=new Intent(this,SeparationService.class);
            s.putExtra("input",inputPath);s.putExtra("model",model);s.putExtra("output",outputPath);
            startService(s);
            separate.setEnabled(false);
        }catch(Exception e){
            status.setText("מודל חסר — יש להוריד את גרסת ה־APK המלאה.");
            Toast.makeText(this,e.toString(),Toast.LENGTH_LONG).show();
        }
    }

    private String statusText(String s,float p){
        if(s==null)s="processing";
        if("loading".equals(s))return "טוען מודל אופליין…";
        if("decoding".equals(s))return "מפענח אודיו…";
        if("resampling".equals(s))return "מתאים דגימה ל־44.1 kHz…";
        if("separating".equals(s))return String.format(Locale.US,"מפריד ערוצים… %.1f%%",p*100);
        if("done".equals(s))return "הושלם.";
        return "מעבד… "+String.format(Locale.US,"%.1f%%",p*100);
    }

    private void loadSeparatedPlayers(){
        releasePlayers();prepared=false;final int[] ready={0};
        for(int i=0;i<4;i++){
            final int idx=i;File f=new File(outputPath,names[i].toLowerCase(Locale.US)+".wav");
            if(!f.exists())continue;
            MediaPlayer mp=new MediaPlayer();
            players[i]=mp;
            try{
                mp.setDataSource(f.getAbsolutePath());
                mp.setOnPreparedListener(x->{ready[0]++;if(ready[0]>=4 || ready[0]>=countExistingStems()){prepared=true;playAll.setEnabled(true);status.setText("מוכן לנגן את הערוצים.");}});
                mp.setOnCompletionListener(x->{if(idx==3)updateTime();});
                mp.prepareAsync();
            }catch(Exception e){players[i]=null;}
        }
    }

    private int countExistingStems(){
        int n=0;for(String name:names)if(new File(outputPath,name.toLowerCase(Locale.US)+".wav").exists())n++;return n;
    }

    private void toggleAll(){
        if(!prepared)return;
        boolean playing=false;for(MediaPlayer p:players)if(p!=null && p.isPlaying())playing=true;
        if(playing){for(MediaPlayer p:players)if(p!=null && p.isPlaying())p.pause();playAll.setText("▶ נגן הכול");}
        else{for(MediaPlayer p:players)if(p!=null)try{p.seekTo(0);p.start();}catch(Exception ignored){}playAll.setText("❚❚ עצור");startedAt=System.currentTimeMillis();updateTime();}
    }

    private void toggleStem(int i){
        MediaPlayer p=players[i];if(p==null){status.setText("הערוץ עדיין לא מוכן.");return;}
        try{if(p.isPlaying())p.pause();else p.start();}catch(Exception ignored){}
    }

    private void applyVolume(int i){
        MediaPlayer p=players[i];if(p==null)return;
        float v=muted[i]?0f:volumes[i];
        try{p.setVolume(v,v);}catch(Exception ignored){}
    }

    private void updateTime(){
        if(players[0]!=null){
            int pos=0,dur=0;try{pos=players[0].getCurrentPosition();dur=players[0].getDuration();}catch(Exception ignored){}
            time.setText(formatMs(pos)+" / "+formatMs(dur));
            if(playAll.isEnabled())time.postDelayed(()->updateTime(),500);
        }
    }

    private String formatMs(int ms){
        int sec=Math.max(0,ms/1000);return String.format(Locale.US,"%02d:%02d",sec/60,sec%60);
    }

    private void releasePlayers(){
        for(int i=0;i<players.length;i++){if(players[i]!=null){try{players[i].release();}catch(Exception ignored){}players[i]=null;}}
        prepared=false;
    }

    @Override public boolean onKeyDown(int key,KeyEvent e){
        if(key==KeyEvent.KEYCODE_DPAD_DOWN){focusIndex=Math.min(3,focusIndex+1);updateFocus();return true;}
        if(key==KeyEvent.KEYCODE_DPAD_UP){focusIndex=Math.max(0,focusIndex-1);updateFocus();return true;}
        if(key==KeyEvent.KEYCODE_1||key==KeyEvent.KEYCODE_2||key==KeyEvent.KEYCODE_3||key==KeyEvent.KEYCODE_4){
            focusIndex=key-KeyEvent.KEYCODE_1;updateFocus();toggleStem(focusIndex);return true;
        }
        if(key==KeyEvent.KEYCODE_DPAD_LEFT){volumes[focusIndex]=Math.max(0f,volumes[focusIndex]-0.05f);applyVolume(focusIndex);return true;}
        if(key==KeyEvent.KEYCODE_DPAD_RIGHT){volumes[focusIndex]=Math.min(1f,volumes[focusIndex]+0.05f);applyVolume(focusIndex);return true;}
        if(key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_DPAD_CENTER){toggleAll();return true;}
        return super.onKeyDown(key,e);
    }

    @Override protected void onDestroy(){
        unregisterReceiver(receiver);releasePlayers();super.onDestroy();
    }
}
