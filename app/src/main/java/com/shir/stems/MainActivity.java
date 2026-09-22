package com.shir.stems;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO=41;
    private static final int BG=Color.rgb(11,12,16);
    private static final int PANEL=Color.rgb(25,31,39);
    private static final int PANEL2=Color.rgb(31,40,51);
    private static final int ACCENT=Color.rgb(102,252,241);
    private static final int TEXT=Color.rgb(244,247,250);
    private static final int MUTED=Color.rgb(145,160,173);

    private LinearLayout root, stemList;
    private TextView song,status,time,orientationButton;
    private ProgressBar progress;
    private Button playAll,separate,choose;
    private String inputPath,outputPath;
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
                status.setText("ההפרדה הסתיימה • מכין את הנגן");
                loadSeparatedPlayers();
            }
        }
    };

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        SharedPreferences sp=getSharedPreferences("shir_ui",MODE_PRIVATE);
        boolean portrait=sp.getBoolean("portrait",false);
        setRequestedOrientation(portrait?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        buildUi();
        registerReceiver(receiver,new IntentFilter(SeparationService.ACTION_PROGRESS));
        registerReceiver(receiver,new IntentFilter(SeparationService.ACTION_DONE));
    }

    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}

    private TextView tv(String s,float size){
        TextView t=new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(TEXT);
        t.setGravity(Gravity.CENTER_VERTICAL); t.setPadding(dp(10),0,dp(10),0);
        return t;
    }

    private Button bt(String s){
        Button b=new Button(this);
        b.setText(s); b.setTextSize(14); b.setAllCaps(false);
        b.setTextColor(TEXT); b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(44)); b.setFocusable(true);
        return b;
    }

    private GradientDrawable bg(int color,float radius){
        GradientDrawable g=new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radius));
        return g;
    }

    private TextView section(String s){
        TextView t=tv(s,14); t.setTextColor(ACCENT); t.setPadding(dp(4),dp(10),dp(4),dp(4));
        return t;
    }

    private void buildUi(){
        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(14),dp(12),dp(14),dp(12));

        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14),dp(8),dp(8),dp(8));
        header.setBackground(bg(PANEL,18));

        LinearLayout titleBox=new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title=tv("SHIR",22); title.setTextColor(ACCENT);
        TextView sub=tv("OFFLINE STEM SEPARATOR",11); sub.setTextColor(MUTED);
        titleBox.addView(title,new LinearLayout.LayoutParams(-1,dp(30)));
        titleBox.addView(sub,new LinearLayout.LayoutParams(-1,dp(22)));
        header.addView(titleBox,new LinearLayout.LayoutParams(0,dp(60),1));

        orientationButton=bt("↔  מסך רחב");
        orientationButton.setTextSize(12);
        orientationButton.setBackground(bg(PANEL2,12));
        orientationButton.setOnClickListener(v->toggleOrientation());
        header.addView(orientationButton,new LinearLayout.LayoutParams(dp(120),dp(52)));

        choose=bt("בחר שיר");
        choose.setTextColor(BG);
        choose.setBackground(bg(ACCENT,12));
        choose.setOnClickListener(v->pickAudio());
        header.addView(choose,new LinearLayout.LayoutParams(dp(110),dp(52)));
        root.addView(header,new LinearLayout.LayoutParams(-1,dp(78)));

        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0,dp(10),0,0);

        LinearLayout songCard=new LinearLayout(this);
        songCard.setOrientation(LinearLayout.VERTICAL);
        songCard.setPadding(dp(14),dp(10),dp(14),dp(10));
        songCard.setBackground(bg(PANEL,16));
        song=tv("לא נבחר קובץ",17); song.setTextColor(TEXT);
        status=tv("בחר שיר כדי להתחיל.",12); status.setTextColor(MUTED);
        songCard.addView(song,new LinearLayout.LayoutParams(-1,dp(38)));
        songCard.addView(status,new LinearLayout.LayoutParams(-1,dp(32)));
        content.addView(songCard,new LinearLayout.LayoutParams(-1,dp(92)));

        content.addView(section("פעולות"),new LinearLayout.LayoutParams(-1,dp(38)));

        LinearLayout actions=new LinearLayout(this);
        actions.setPadding(dp(10),dp(8),dp(10),dp(8));
        actions.setBackground(bg(PANEL,16));
        playAll=bt("▶  נגן הכול"); playAll.setEnabled(false);
        separate=bt("✦  הפרד ערוצים"); separate.setEnabled(false);
        playAll.setBackground(bg(PANEL2,12));
        separate.setBackground(bg(ACCENT,12)); separate.setTextColor(BG);
        actions.addView(playAll,new LinearLayout.LayoutParams(0,dp(56),1));
        actions.addView(separate,new LinearLayout.LayoutParams(0,dp(56),1));
        playAll.setOnClickListener(v->toggleAll());
        separate.setOnClickListener(v->startSeparation());
        content.addView(actions,new LinearLayout.LayoutParams(-1,dp(72)));

        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000); progress.setProgress(0);
        content.addView(progress,new LinearLayout.LayoutParams(-1,dp(18)));

        LinearLayout timeCard=new LinearLayout(this);
        timeCard.setGravity(Gravity.CENTER_VERTICAL);
        timeCard.setPadding(dp(12),0,dp(12),0);
        timeCard.setBackground(bg(PANEL,12));
        TextView tl=tv("נגן",12); tl.setTextColor(MUTED);
        time=tv("00:00 / 00:00",13); time.setGravity(Gravity.CENTER);
        timeCard.addView(tl,new LinearLayout.LayoutParams(0,dp(42),1));
        timeCard.addView(time,new LinearLayout.LayoutParams(dp(170),dp(42)));
        content.addView(timeCard,new LinearLayout.LayoutParams(-1,dp(42)));

        content.addView(section("ערוצים • שליטה בעוצמה, MUTE וניגון"),new LinearLayout.LayoutParams(-1,dp(46)));

        stemList=new LinearLayout(this); stemList.setOrientation(LinearLayout.VERTICAL);
        for(int x=0;x<4;x++) addStem(x);
        content.addView(stemList,new LinearLayout.LayoutParams(-1,-2));

        TextView hint=tv("↑ ↓ בחירה   1–4 ערוץ   ENTER נגן/עצור   ← → עוצמה",11);
        hint.setTextColor(MUTED); hint.setGravity(Gravity.CENTER);
        hint.setPadding(0,dp(12),0,dp(8));
        content.addView(hint,new LinearLayout.LayoutParams(-1,dp(42)));

        scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        updateOrientationLabel();
        updateFocus();
    }

    private void addStem(final int index){
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(8),dp(7),dp(8),dp(7));
        card.setBackground(bg(PANEL2,14));

        Button b=bt((index+1)+"   "+names[index]);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setBackground(bg(Color.rgb(38,48,60),10));
        b.setOnClickListener(v->{focusIndex=index;updateFocus();toggleStem(index);});
        card.addView(b,new LinearLayout.LayoutParams(dp(105),dp(58)));

        final Button mute=bt("MUTE");
        mute.setTextSize(11);
        mute.setBackground(bg(PANEL,10));
        mute.setOnClickListener(v->{muted[index]=!muted[index];mute.setText(muted[index]?"UNMUTE":"MUTE");applyVolume(index);});
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(82),dp(58));
        mp.setMargins(dp(7),0,dp(4),0); card.addView(mute,mp);

        SeekBar bar=new SeekBar(this);
        bar.setMax(100); bar.setProgress(100);
        bar.setContentDescription("עוצמת "+names[index]);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int p,boolean fromUser){
                volumes[index]=p/100f; applyVolume(index);
            }
            @Override public void onStartTrackingTouch(SeekBar s){}
            @Override public void onStopTrackingTouch(SeekBar s){}
        });
        card.addView(bar,new LinearLayout.LayoutParams(0,dp(58),1));

        card.setTag(index);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(72));
        cp.setMargins(0,0,0,dp(7));
        stemList.addView(card,cp);
    }

    private void updateFocus(){
        if(stemList==null)return;
        for(int i=0;i<stemList.getChildCount();i++){
            View row=stemList.getChildAt(i);
            row.setBackground(bg(i==focusIndex?Color.rgb(37,67,72):PANEL2,14));
        }
    }

    private void toggleOrientation(){
        SharedPreferences sp=getSharedPreferences("shir_ui",MODE_PRIVATE);
        boolean portrait=sp.getBoolean("portrait",false);
        portrait=!portrait;
        sp.edit().putBoolean("portrait",portrait).apply();
        setRequestedOrientation(portrait?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        updateOrientationLabel();
    }

    private void updateOrientationLabel(){
        if(orientationButton==null)return;
        boolean portrait=getSharedPreferences("shir_ui",MODE_PRIVATE).getBoolean("portrait",false);
        orientationButton.setText(portrait?"↕  מסך ישר":"↔  מסך רחב");
    }

    private void pickAudio(){
        Intent i=new Intent(Intent.ACTION_GET_CONTENT); i.setType("audio/*");
        i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,PICK_AUDIO);
    }

    @Override protected void onActivityResult(int r,int result,Intent data){
        super.onActivityResult(r,result,data);
        if(r==PICK_AUDIO && result==RESULT_OK && data!=null && data.getData()!=null){
            try{
                Uri u=data.getData(); inputPath=copyUriToCache(u);
                song.setText(u.getLastPathSegment()==null?"קובץ נבחר":u.getLastPathSegment());
                status.setText("מוכן • אפשר לנגן או להפריד");
                playAll.setEnabled(true); separate.setEnabled(true);
            }catch(Exception e){
                Toast.makeText(this,"לא הצלחתי לקרוא את הקובץ",Toast.LENGTH_LONG).show();
            }
        }
    }

    private String copyUriToCache(Uri uri)throws Exception{
        File dir=new File(getCacheDir(),"inputs"); dir.mkdirs();
        File out=new File(dir,"input_"+System.currentTimeMillis()+".audio");
        InputStream in=getContentResolver().openInputStream(uri);
        FileOutputStream fos=new FileOutputStream(out);
        byte[] buf=new byte[64*1024]; int n;
        while((n=in.read(buf))!=-1)fos.write(buf,0,n);
        in.close(); fos.close(); return out.getAbsolutePath();
    }

    private String ensureModel()throws Exception{
        File dir=new File(getFilesDir(),"models"); dir.mkdirs();
        File model=new File(dir,"ggml-htdemucs-4s-f16.bin");
        if(model.exists() && model.length()>80000000)return model.getAbsolutePath();
        InputStream in=getAssets().open("ggml-htdemucs-4s-f16.bin");
        FileOutputStream out=new FileOutputStream(model);
        byte[] buf=new byte[1024*1024]; int n;
        while((n=in.read(buf))!=-1)out.write(buf,0,n);
        in.close(); out.close(); return model.getAbsolutePath();
    }

    private void startSeparation(){
        if(inputPath==null)return;
        try{
            final String model=ensureModel();
            outputPath=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),"SHIR/"+System.currentTimeMillis()).getAbsolutePath();
            new File(outputPath).mkdirs();
            status.setText("מכין מנוע הפרדה מקומי…"); progress.setProgress(0);
            Intent s=new Intent(this,SeparationService.class);
            s.putExtra("input",inputPath); s.putExtra("model",model); s.putExtra("output",outputPath);
            startService(s); separate.setEnabled(false);
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
        releasePlayers(); prepared=false; final int[] ready={0};
        for(int i=0;i<4;i++){
            final int idx=i; File f=new File(outputPath,names[i].toLowerCase(Locale.US)+".wav");
            if(!f.exists())continue;
            MediaPlayer mp=new MediaPlayer(); players[i]=mp;
            try{
                mp.setDataSource(f.getAbsolutePath());
                mp.setOnPreparedListener(x->{ready[0]++; if(ready[0]>=4 || ready[0]>=countExistingStems()){prepared=true; playAll.setEnabled(true); status.setText("מוכן לנגן את הערוצים.");}});
                mp.setOnCompletionListener(x->{if(idx==3)updateTime();});
                mp.prepareAsync();
            }catch(Exception e){players[i]=null;}
        }
    }

    private int countExistingStems(){
        int n=0; for(String name:names)if(new File(outputPath,name.toLowerCase(Locale.US)+".wav").exists())n++; return n;
    }

    private void toggleAll(){
        if(!prepared)return;
        boolean playing=false; for(MediaPlayer p:players)if(p!=null && p.isPlaying())playing=true;
        if(playing){
            for(MediaPlayer p:players)if(p!=null && p.isPlaying())p.pause();
            playAll.setText("▶  נגן הכול");
        }else{
            for(MediaPlayer p:players)if(p!=null)try{p.seekTo(0);p.start();}catch(Exception ignored){}
            playAll.setText("❚❚  עצור"); startedAt=System.currentTimeMillis(); updateTime();
        }
    }

    private void toggleStem(int i){
        MediaPlayer p=players[i]; if(p==null){status.setText("הערוץ עדיין לא מוכן.");return;}
        try{if(p.isPlaying())p.pause();else p.start();}catch(Exception ignored){}
    }

    private void applyVolume(int i){
        MediaPlayer p=players[i]; if(p==null)return;
        float v=muted[i]?0f:volumes[i]; try{p.setVolume(v,v);}catch(Exception ignored){}
    }

    private void updateTime(){
        if(players[0]!=null){
            int pos=0,dur=0; try{pos=players[0].getCurrentPosition();dur=players[0].getDuration();}catch(Exception ignored){}
            time.setText(formatMs(pos)+" / "+formatMs(dur));
            if(playAll.isEnabled())time.postDelayed(()->updateTime(),500);
        }
    }

    private String formatMs(int ms){
        int sec=Math.max(0,ms/1000); return String.format(Locale.US,"%02d:%02d",sec/60,sec%60);
    }

    private void releasePlayers(){
        for(int i=0;i<players.length;i++){
            if(players[i]!=null){try{players[i].release();}catch(Exception ignored){} players[i]=null;}
        }
        prepared=false;
    }

    @Override public boolean onKeyDown(int key,KeyEvent e){
        if(key==KeyEvent.KEYCODE_DPAD_DOWN){focusIndex=Math.min(3,focusIndex+1);updateFocus();return true;}
        if(key==KeyEvent.KEYCODE_DPAD_UP){focusIndex=Math.max(0,focusIndex-1);updateFocus();return true;}
        if(key>=KeyEvent.KEYCODE_1 && key<=KeyEvent.KEYCODE_4){
            focusIndex=key-KeyEvent.KEYCODE_1;updateFocus();toggleStem(focusIndex);return true;
        }
        if(key==KeyEvent.KEYCODE_DPAD_LEFT){volumes[focusIndex]=Math.max(0f,volumes[focusIndex]-0.05f);applyVolume(focusIndex);return true;}
        if(key==KeyEvent.KEYCODE_DPAD_RIGHT){volumes[focusIndex]=Math.min(1f,volumes[focusIndex]+0.05f);applyVolume(focusIndex);return true;}
        if(key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_DPAD_CENTER){toggleAll();return true;}
        return super.onKeyDown(key,e);
    }

    @Override protected void onDestroy(){
        try{unregisterReceiver(receiver);}catch(Exception ignored){}
        releasePlayers(); super.onDestroy();
    }
}
