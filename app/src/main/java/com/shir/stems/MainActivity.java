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
import java.io.OutputStream;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO=41;
    private static final int SAVE_MIX=42;
    private static final int BG=Color.rgb(11,12,16);
    private static final int PANEL=Color.rgb(25,31,39);
    private static final int PANEL2=Color.rgb(31,40,51);
    private static final int ACCENT=Color.rgb(102,252,241);
    private static final int TEXT=Color.rgb(244,247,250);
    private static final int MUTED=Color.rgb(145,160,173);

    private LinearLayout root, stemList;
    private TextView song,status,time,orientationButton;
    private ProgressBar progress;
    private Button playAll,separate,choose,saveButton;
    private String inputPath,outputPath;
    private final String[] names={"DRUMS","BASS","OTHER","VOCALS"};
    private final MediaPlayer[] players=new MediaPlayer[4];
    private MediaPlayer mixPlayer;
    private final boolean[] muted=new boolean[4];
    private final float[] volumes={1f,1f,1f,1f};
    private int focusIndex=0;
    private boolean prepared=false;
    private long startedAt=0;
    private static final Set<String> SUPPORTED_EXTS = new HashSet<String>();
    static { SUPPORTED_EXTS.add("mp3"); SUPPORTED_EXTS.add("wav"); SUPPORTED_EXTS.add("wave"); SUPPORTED_EXTS.add("ogg"); SUPPORTED_EXTS.add("opus"); SUPPORTED_EXTS.add("flac"); SUPPORTED_EXTS.add("wv"); SUPPORTED_EXTS.add("mpc"); SUPPORTED_EXTS.add("mpp"); }

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
                status.setText("ההפרדה הסתיימה • מכין את השיר לניגון");
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
        GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); return g;
    }

    private TextView section(String s){
        TextView t=tv(s,14); t.setTextColor(ACCENT);
        t.setPadding(dp(4),dp(10),dp(4),dp(4)); return t;
    }

    private void buildUi(){
        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setPadding(dp(14),dp(12),dp(14),dp(12));

        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(14),dp(8),dp(8),dp(8));
        header.setBackground(bg(PANEL,18));

        LinearLayout titleBox=new LinearLayout(this); titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title=tv("SHIR",22); title.setTextColor(ACCENT);
        TextView sub=tv("מפריד ערוצים אופליין",11); sub.setTextColor(MUTED);
        titleBox.addView(title,new LinearLayout.LayoutParams(-1,dp(30)));
        titleBox.addView(sub,new LinearLayout.LayoutParams(-1,dp(22)));
        header.addView(titleBox,new LinearLayout.LayoutParams(0,dp(60),1));

        orientationButton=bt("↔  מסך רחב"); orientationButton.setTextSize(12);
        orientationButton.setBackground(bg(PANEL2,12)); orientationButton.setOnClickListener(v->toggleOrientation());
        header.addView(orientationButton,new LinearLayout.LayoutParams(dp(120),dp(52)));

        choose=bt("בחר שיר"); choose.setTextColor(BG); choose.setBackground(bg(ACCENT,12));
        choose.setOnClickListener(v->pickAudio());
        header.addView(choose,new LinearLayout.LayoutParams(dp(110),dp(52)));
        root.addView(header,new LinearLayout.LayoutParams(-1,dp(78)));

        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0,dp(10),0,0);

        LinearLayout songCard=new LinearLayout(this); songCard.setOrientation(LinearLayout.VERTICAL);
        songCard.setPadding(dp(14),dp(10),dp(14),dp(10)); songCard.setBackground(bg(PANEL,16));
        song=tv("לא נבחר קובץ",17);
        status=tv("בחר שיר כדי להתחיל.",12); status.setTextColor(MUTED);
        songCard.addView(song,new LinearLayout.LayoutParams(-1,dp(38)));
        songCard.addView(status,new LinearLayout.LayoutParams(-1,dp(32)));
        content.addView(songCard,new LinearLayout.LayoutParams(-1,dp(92)));

        content.addView(section("פעולות"),new LinearLayout.LayoutParams(-1,dp(38)));
        LinearLayout actions=new LinearLayout(this); actions.setPadding(dp(10),dp(8),dp(10),dp(8));
        actions.setBackground(bg(PANEL,16));
        playAll=bt("▶  נגן"); playAll.setEnabled(false); playAll.setBackground(bg(PANEL2,12));
        separate=bt("✦  הפרד ערוצים"); separate.setEnabled(false);
        separate.setBackground(bg(ACCENT,12)); separate.setTextColor(BG);
        saveButton=bt("💾  שמור לקבצים"); saveButton.setEnabled(false); saveButton.setBackground(bg(PANEL2,12));
        actions.addView(playAll,new LinearLayout.LayoutParams(0,dp(56),1));
        actions.addView(separate,new LinearLayout.LayoutParams(0,dp(56),1));
        actions.addView(saveButton,new LinearLayout.LayoutParams(0,dp(56),1));
        playAll.setOnClickListener(v->toggleAll());
        separate.setOnClickListener(v->startSeparation());
        saveButton.setOnClickListener(v->saveMixToFiles());
        content.addView(actions,new LinearLayout.LayoutParams(-1,dp(72)));

        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000); content.addView(progress,new LinearLayout.LayoutParams(-1,dp(18)));

        LinearLayout timeCard=new LinearLayout(this); timeCard.setGravity(Gravity.CENTER_VERTICAL);
        timeCard.setPadding(dp(12),0,dp(12),0); timeCard.setBackground(bg(PANEL,12));
        TextView tl=tv("נגן",12); tl.setTextColor(MUTED);
        time=tv("00:00 / 00:00",13); time.setGravity(Gravity.CENTER);
        timeCard.addView(tl,new LinearLayout.LayoutParams(0,dp(42),1));
        timeCard.addView(time,new LinearLayout.LayoutParams(dp(170),dp(42)));
        content.addView(timeCard,new LinearLayout.LayoutParams(-1,dp(42)));

        content.addView(section("ערוצים • עוצמה, השתקה וניגון"),new LinearLayout.LayoutParams(-1,dp(46)));
        stemList=new LinearLayout(this); stemList.setOrientation(LinearLayout.VERTICAL);
        for(int x=0;x<4;x++) addStem(x);
        content.addView(stemList,new LinearLayout.LayoutParams(-1,-2));

        TextView hint=tv("↑ ↓ בחירה   1–4 ערוץ   ENTER נגן/עצור   ← → עוצמה",11);
        hint.setTextColor(MUTED); hint.setGravity(Gravity.CENTER);
        hint.setPadding(0,dp(12),0,dp(8));
        content.addView(hint,new LinearLayout.LayoutParams(-1,dp(42)));
        scroll.addView(content); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root); updateOrientationLabel(); updateFocus();
    }

    private void addStem(final int index){
        LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL); card.setPadding(dp(8),dp(7),dp(8),dp(7));
        card.setBackground(bg(PANEL2,14));
        Button b=bt((index+1)+"   "+hebrewStem(index)); b.setTextSize(14);
        b.setBackground(bg(Color.rgb(38,48,60),10));
        b.setOnClickListener(v->{focusIndex=index;updateFocus();toggleStem(index);});
        card.addView(b,new LinearLayout.LayoutParams(dp(120),dp(58)));
        final Button mute=bt("השתק"); mute.setTextSize(11); mute.setBackground(bg(PANEL,10));
        mute.setOnClickListener(v->{muted[index]=!muted[index];mute.setText(muted[index]?"הפעל":"השתק");applyVolume(index);});
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(82),dp(58)); mp.setMargins(dp(7),0,dp(4),0);
        card.addView(mute,mp);
        SeekBar bar=new SeekBar(this); bar.setMax(100); bar.setProgress(100);
        bar.setContentDescription("עוצמת "+hebrewStem(index));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int p,boolean fromUser){volumes[index]=p/100f;applyVolume(index);}
            @Override public void onStartTrackingTouch(SeekBar s){}
            @Override public void onStopTrackingTouch(SeekBar s){}
        });
        card.addView(bar,new LinearLayout.LayoutParams(0,dp(58),1));
        card.setTag(index); LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(72));
        cp.setMargins(0,0,0,dp(7)); stemList.addView(card,cp);
    }

    private String hebrewStem(int i){
        if(i==0)return "תופים"; if(i==1)return "בס"; if(i==2)return "כלי נגינה"; return "שירה";
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
        boolean portrait=!sp.getBoolean("portrait",false); sp.edit().putBoolean("portrait",portrait).apply();
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
                Uri u=data.getData();
                String selectedName = u.getLastPathSegment()==null ? "קובץ נבחר" : u.getLastPathSegment();
                String ext = extensionOf(selectedName);
                if(!SUPPORTED_EXTS.contains(ext)){
                    status.setText("פורמט לא נתמך להפרדה: "+(ext.length()==0?"לא ידוע":ext.toUpperCase(Locale.US)));
                    Toast.makeText(this,"SHIR תומך ב‑MP3, WAV, OGG/OPUS, FLAC, WV ו‑MPC. AAC/M4A אינם נתמכים במנוע האופליין.",Toast.LENGTH_LONG).show();
                    return;
                }
                inputPath=copyUriToCache(u);
                song.setText(selectedName);
                status.setText("מוכן • אפשר לנגן או להפריד");
                playAll.setEnabled(true); separate.setEnabled(true);
                prepareInputPlayer();
            }catch(Exception e){Toast.makeText(this,"לא הצלחתי לקרוא את הקובץ",Toast.LENGTH_LONG).show();}
        } else if(r==SAVE_MIX && result==RESULT_OK && data!=null && data.getData()!=null){
            copyFileToUri(new File(outputPath,"mix.wav"),data.getData());
        }
    }

    private String copyUriToCache(Uri uri)throws Exception{
        File dir=new File(getCacheDir(),"inputs"); dir.mkdirs();
        String ext="wav";
        String mime=getContentResolver().getType(uri);
        if(mime!=null){
            if(mime.equals("audio/mpeg")||mime.equals("audio/mp3")) ext="mp3";
            else if(mime.equals("audio/mp4")||mime.equals("audio/x-m4a")) ext="m4a";
            else if(mime.equals("audio/ogg")) ext="ogg";
            else if(mime.equals("audio/flac")) ext="flac";
            else if(mime.equals("audio/wav")||mime.equals("audio/x-wav")) ext="wav";
        }
        String name=null;
        try{ name=uri.getLastPathSegment(); }catch(Exception ignored){}
        if(name!=null){
            String candidate=extensionOf(name);
            if(SUPPORTED_EXTS.contains(candidate)) ext=candidate;
        }
        if(!SUPPORTED_EXTS.contains(ext)) throw new Exception("פורמט שמע אינו נתמך במנוע האופליין");
        File out=new File(dir,"input_"+System.currentTimeMillis()+"."+ext);
        InputStream in=getContentResolver().openInputStream(uri);
        if(in==null)throw new Exception("לא ניתן לפתוח את קובץ השיר");
        FileOutputStream fos=null;
        try{
            fos=new FileOutputStream(out);
            byte[] buf=new byte[64*1024]; int n; while((n=in.read(buf))!=-1)fos.write(buf,0,n);
            fos.flush();
        } finally {
            try{in.close();}catch(Exception ignored){}
            try{if(fos!=null)fos.close();}catch(Exception ignored){}
        }
        if(!out.exists() || out.length()<44) { try{out.delete();}catch(Exception ignored){} throw new Exception("קובץ השיר ריק או פגום"); }
        return out.getAbsolutePath();
    }

    private String extensionOf(String name){
        if(name==null)return "";
        int dot=name.lastIndexOf('.');
        if(dot<0 || dot==name.length()-1)return "";
        return name.substring(dot+1).toLowerCase(Locale.US);
    }

    private String ensureModel()throws Exception{
        File dir=new File(getFilesDir(),"models"); dir.mkdirs();
        File model=new File(dir,"ggml-htdemucs-4s-f16.bin");
        if(model.exists() && model.length()>80000000)return model.getAbsolutePath();
        InputStream in=getAssets().open("ggml-htdemucs-4s-f16.bin"); FileOutputStream out=new FileOutputStream(model);
        byte[] buf=new byte[1024*1024]; int n; while((n=in.read(buf))!=-1)out.write(buf,0,n);
        in.close(); out.close(); return model.getAbsolutePath();
    }

    private void startSeparation(){
        if(inputPath==null)return;
        String ext=extensionOf(inputPath);
        if(!SUPPORTED_EXTS.contains(ext)){
            status.setText("פורמט הקלט אינו נתמך.");
            return;
        }
        try{
            final String model=ensureModel();
            outputPath=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),"SHIR/"+System.currentTimeMillis()).getAbsolutePath();
            new File(outputPath).mkdirs();
            status.setText("מכין מנוע הפרדה מקומי…"); progress.setProgress(0);
            Intent s=new Intent(this,SeparationService.class);
            s.putExtra("input",inputPath); s.putExtra("model",model); s.putExtra("output",outputPath);
            startService(s); separate.setEnabled(false);
        }catch(Exception e){
            status.setText("המודל חסר — יש להשתמש בגרסת האפליקציה המלאה.");
            Toast.makeText(this,e.toString(),Toast.LENGTH_LONG).show();
        }
    }

    private String statusText(String s,float p){
        if(s==null)s="processing";
        if("loading".equals(s))return "טוען מודל אופליין…";
        if("decoding".equals(s))return "מפענח את השיר…";
        if("resampling".equals(s))return "מתאים את איכות הדגימה…";
        if("separating".equals(s))return String.format(Locale.US,"מפריד ערוצים… %.1f%%",p*100);
        if("done".equals(s))return "ההפרדה הושלמה.";
        if("model_error".equals(s))return "שגיאה בטעינת המודל.";
        if("decode_error".equals(s))return "לא ניתן לקרוא את קובץ השיר.";
        if("write_error".equals(s))return "לא ניתן לשמור את הערוצים.";
        if("native_error".equals(s))return "שגיאה במנוע ההפרדה.";
        if("unsupported_format".equals(s))return "פורמט שמע זה אינו נתמך.";
        if("input_too_long".equals(s))return "השיר ארוך מדי למכשיר הזה.";
        if("out_of_memory".equals(s))return "אין מספיק זיכרון להפרדה.";
        if("invalid_audio".equals(s))return "קובץ השמע פגום או לא תקין.";
        if(s!=null && s.startsWith("מנוע:"))return s;
        return "מעבד… "+String.format(Locale.US,"%.1f%%",p*100);
    }

    private void prepareInputPlayer(){
        releaseMixPlayer();
        if(inputPath==null)return;
        try{
            mixPlayer=new MediaPlayer();
            mixPlayer.setDataSource(inputPath);
            mixPlayer.setOnPreparedListener(mp->{prepared=true;playAll.setEnabled(true);status.setText("השיר מוכן לניגון.");updateTime();});
            mixPlayer.setOnCompletionListener(mp->{playAll.setText("▶  נגן");updateTime();});
            mixPlayer.setOnErrorListener((mp,what,extra)->{status.setText("לא ניתן לנגן את הקובץ הזה במכשיר.");prepared=false;return true;});
            mixPlayer.prepareAsync();
        }catch(Exception e){prepared=false;status.setText("לא ניתן להכין את הנגן.");}
    }

    private void loadSeparatedPlayers(){
        releasePlayers(); releaseMixPlayer(); prepared=false;
        File mix=new File(outputPath,"mix.wav");
        if(!mix.exists()){
            status.setText("ההפרדה הסתיימה אבל קובץ הניגון לא נוצר.");
            return;
        }
        try{
            mixPlayer=new MediaPlayer();
            mixPlayer.setDataSource(mix.getAbsolutePath());
            mixPlayer.setOnPreparedListener(mp->{
                prepared=true; playAll.setEnabled(true); saveButton.setEnabled(true);
                status.setText("הכול מוכן • אפשר לנגן ולשמור לקבצים."); updateTime();
            });
            mixPlayer.setOnCompletionListener(mp->{playAll.setText("▶  נגן");updateTime();});
            mixPlayer.setOnErrorListener((mp,what,extra)->{
                prepared=false; status.setText("קובץ השיר שנוצר אינו נתמך בנגן המכשיר.");
                return true;
            });
            mixPlayer.prepareAsync();
        }catch(Exception e){status.setText("שגיאה בהכנת הנגן: "+e.getMessage());}
        for(int i=0;i<4;i++)prepareStemPlayer(i);
    }

    private void prepareStemPlayer(final int idx){
        File f=new File(outputPath,names[idx].toLowerCase(Locale.US)+".wav"); if(!f.exists())return;
        try{
            MediaPlayer mp=new MediaPlayer(); players[idx]=mp; mp.setDataSource(f.getAbsolutePath());
            mp.setOnErrorListener((p,w,x)->{try{p.reset();}catch(Exception ignored){} return true;});
            mp.prepareAsync();
        }catch(Exception e){players[idx]=null;}
    }

    private void toggleAll(){
        if(!prepared || mixPlayer==null)return;
        try{
            if(mixPlayer.isPlaying()){mixPlayer.pause();for(MediaPlayer p:players)if(p!=null&&p.isPlaying())p.pause();playAll.setText("▶  נגן");}
            else{mixPlayer.start();for(MediaPlayer p:players)if(p!=null){try{p.seekTo(mixPlayer.getCurrentPosition());p.start();}catch(Exception ignored){}}playAll.setText("❚❚  עצור");startedAt=System.currentTimeMillis();updateTime();}
        }catch(Exception e){status.setText("לא ניתן להפעיל את הנגן.");}
    }

    private void toggleStem(int i){
        MediaPlayer p=players[i];
        if(p==null){status.setText("הערוץ עדיין לא מוכן.");return;}
        try{if(p.isPlaying())p.pause();else{if(mixPlayer!=null)try{p.seekTo(mixPlayer.getCurrentPosition());}catch(Exception ignored){}p.start();}}
        catch(Exception e){status.setText("לא ניתן לנגן את הערוץ.");}
    }

    private void applyVolume(int i){
        MediaPlayer p=players[i]; if(p==null)return;
        float v=muted[i]?0f:volumes[i]; try{p.setVolume(v,v);}catch(Exception ignored){}
    }

    private void updateTime(){
        if(mixPlayer!=null){
            int pos=0,dur=0; try{pos=mixPlayer.getCurrentPosition();dur=mixPlayer.getDuration();}catch(Exception ignored){}
            time.setText(formatMs(pos)+" / "+formatMs(dur));
            if(mixPlayer.isPlaying())time.postDelayed(()->updateTime(),500);
        }
    }

    private String formatMs(int ms){int sec=Math.max(0,ms/1000);return String.format(Locale.US,"%02d:%02d",sec/60,sec%60);}

    private void saveMixToFiles(){
        if(outputPath==null)return;
        File mix=new File(outputPath,"mix.wav");
        if(!mix.exists()){Toast.makeText(this,"אין עדיין שיר מוכן לשמירה.",Toast.LENGTH_LONG).show();return;}
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("audio/wav");
        i.putExtra(Intent.EXTRA_TITLE,"SHIR - שיר מעורב.wav");
        try{startActivityForResult(i,SAVE_MIX);}catch(Exception e){copyMixToPublicMusic(mix);}
    }

    private void copyFileToUri(File source,Uri uri){
        try{
            InputStream in=new java.io.FileInputStream(source); OutputStream out=getContentResolver().openOutputStream(uri);
            byte[] buf=new byte[64*1024]; int n; while((n=in.read(buf))!=-1)out.write(buf,0,n);
            in.close();out.close();Toast.makeText(this,"השיר נשמר בהצלחה בקבצים.",Toast.LENGTH_LONG).show();
        }catch(Exception e){Toast.makeText(this,"השמירה נכשלה: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }

    private void copyMixToPublicMusic(File mix){
        try{
            File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),"SHIR");dir.mkdirs();
            File dst=new File(dir,"SHIR-שיר-מעורב.wav");InputStream in=new java.io.FileInputStream(mix);OutputStream out=new FileOutputStream(dst);
            byte[] buf=new byte[64*1024];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);in.close();out.close();
            Toast.makeText(this,"השיר נשמר בתיקיית Music/SHIR.",Toast.LENGTH_LONG).show();
        }catch(Exception e){Toast.makeText(this,"השמירה נכשלה.",Toast.LENGTH_LONG).show();}
    }

    private void releaseMixPlayer(){
        if(mixPlayer!=null){try{mixPlayer.release();}catch(Exception ignored){}mixPlayer=null;} prepared=false;
    }

    private void releasePlayers(){
        for(int i=0;i<players.length;i++){if(players[i]!=null){try{players[i].release();}catch(Exception ignored){}players[i]=null;}}
    }

    @Override public boolean onKeyDown(int key,KeyEvent e){
        if(key==KeyEvent.KEYCODE_DPAD_DOWN){focusIndex=Math.min(3,focusIndex+1);updateFocus();return true;}
        if(key==KeyEvent.KEYCODE_DPAD_UP){focusIndex=Math.max(0,focusIndex-1);updateFocus();return true;}
        if(key>=KeyEvent.KEYCODE_1&&key<=KeyEvent.KEYCODE_4){focusIndex=key-KeyEvent.KEYCODE_1;updateFocus();toggleStem(focusIndex);return true;}
        if(key==KeyEvent.KEYCODE_DPAD_LEFT){volumes[focusIndex]=Math.max(0f,volumes[focusIndex]-0.05f);applyVolume(focusIndex);return true;}
        if(key==KeyEvent.KEYCODE_DPAD_RIGHT){volumes[focusIndex]=Math.min(1f,volumes[focusIndex]+0.05f);applyVolume(focusIndex);return true;}
        if(key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_DPAD_CENTER){toggleAll();return true;}
        return super.onKeyDown(key,e);
    }

    @Override protected void onDestroy(){
        try{unregisterReceiver(receiver);}catch(Exception ignored){}
        releasePlayers();releaseMixPlayer();super.onDestroy();
    }
}
