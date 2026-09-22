package com.shir.stems;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO = 41;
    private LinearLayout root, stemList;
    private TextView title, status, time;
    private MediaPlayer player;
    private Uri currentUri;
    private Button playButton, selectButton, separateButton;
    private int focusIndex = 0;
    private final String[] stems = {"VOCALS", "DRUMS", "BASS", "OTHER"};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
    }

    private TextView text(String value, int size) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextColor(Color.rgb(244,247,250)); t.setTextSize(size);
        t.setPadding(22,14,22,14); return t;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value); b.setTextColor(Color.rgb(244,247,250)); b.setTextSize(14);
        b.setFocusable(true); b.setAllCaps(false);
        return b;
    }

    private void buildUi() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(11,12,16)); root.setPadding(24,20,24,18);

        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL);
        title = text("SHIR  •  OFFLINE STEMS",20); header.addView(title,new LinearLayout.LayoutParams(0,60,1));
        selectButton=button("בחר שיר"); header.addView(selectButton,new LinearLayout.LayoutParams(150,60));
        selectButton.setOnClickListener(v -> pickAudio());
        root.addView(header);

        status=text("בחר שיר כדי להתחיל. כל העיבוד מיועד להתבצע מקומית.",14); status.setTextColor(Color.rgb(145,160,173));
        root.addView(status,new LinearLayout.LayoutParams(-1,55));

        LinearLayout playerPanel=new LinearLayout(this); playerPanel.setOrientation(LinearLayout.VERTICAL);
        playerPanel.setBackgroundColor(Color.rgb(31,40,51));
        TextView song=text("אין קובץ נבחר",18); song.setId(1001); playerPanel.addView(song);
        LinearLayout controls=new LinearLayout(this);
        playButton=button("▶ נגן"); playButton.setEnabled(false); controls.addView(playButton,new LinearLayout.LayoutParams(140,60));
        separateButton=button("✦ הפרד ערוצים"); separateButton.setEnabled(false); controls.addView(separateButton,new LinearLayout.LayoutParams(0,60,1));
        time=text("00:00 / 00:00",13); time.setGravity(16); controls.addView(time,new LinearLayout.LayoutParams(170,60));
        playerPanel.addView(controls); root.addView(playerPanel,new LinearLayout.LayoutParams(-1,130));

        TextView st=text("ערוצים",16); st.setTextColor(Color.rgb(102,252,241)); root.addView(st,new LinearLayout.LayoutParams(-1,48));
        stemList=new LinearLayout(this); stemList.setOrientation(LinearLayout.VERTICAL);
        for(int i=0;i<stems.length;i++) addStem(i);
        root.addView(stemList,new LinearLayout.LayoutParams(-1,0,1));

        TextView hint=text("מקשים: ↑ ↓ בחירה   ENTER ניגון/פתיחה   1-4 בחירת ערוץ   BACK יציאה",12);
        hint.setTextColor(Color.rgb(145,160,173)); root.addView(hint,new LinearLayout.LayoutParams(-1,40));
        setContentView(root);
        updateFocus();

        playButton.setOnClickListener(v -> togglePlay());
        separateButton.setOnClickListener(v -> startSeparation());
    }

    private void addStem(int i) {
        Button b=button((i+1)+"   "+stems[i]+"     SOLO / MUTE / VOLUME");
        b.setTag(i); b.setOnClickListener(v -> { focusIndex=(Integer)v.getTag(); updateFocus(); });
        stemList.addView(b,new LinearLayout.LayoutParams(-1,64));
    }

    private void updateFocus() {
        for(int i=0;i<stemList.getChildCount();i++) {
            View v=stemList.getChildAt(i);
            v.setBackgroundColor(i==focusIndex?Color.rgb(46,64,72):Color.rgb(31,40,51));
        }
    }

    private void pickAudio() {
        Intent i=new Intent(Intent.ACTION_GET_CONTENT); i.setType("audio/*"); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,PICK_AUDIO);
    }

    @Override protected void onActivityResult(int req,int result,Intent data) {
        super.onActivityResult(req,result,data);
        if(req==PICK_AUDIO && result==RESULT_OK && data!=null && data.getData()!=null) {
            currentUri=data.getData(); TextView song=(TextView)findViewById(1001);
            song.setText(currentUri.getLastPathSegment()==null?"שיר נבחר":currentUri.getLastPathSegment());
            status.setText("מוכן לניגון ולעיבוד אופליין"); playButton.setEnabled(true); separateButton.setEnabled(true);
            preparePlayer();
        }
    }

    private void preparePlayer() {
        releasePlayer(); player=new MediaPlayer();
        try { player.setDataSource(this,currentUri); player.setOnPreparedListener(mp -> { playButton.setText("▶ נגן"); }); player.prepareAsync(); }
        catch(IOException e){ Toast.makeText(this,"לא ניתן לפתוח את הקובץ",Toast.LENGTH_SHORT).show(); }
    }

    private void togglePlay(){ if(player==null)return; if(player.isPlaying()){player.pause();playButton.setText("▶ נגן");}else{player.start();playButton.setText("❚❚ עצור");} }

    private void startSeparation(){
        status.setText("מנוע ההפרדה המקומי מוכן לשילוב מודל Demucs. בשלב זה לא מועלה שום אודיו לענן.");
        Toast.makeText(this,"העיבוד יבוצע אופליין במכשיר",Toast.LENGTH_LONG).show();
    }

    private void releasePlayer(){ if(player!=null){try{player.stop();}catch(Exception ignored){} player.release();player=null;} }
    @Override protected void onDestroy(){releasePlayer();super.onDestroy();}

    @Override public boolean onKeyDown(int key,KeyEvent e){
        if(key==KeyEvent.KEYCODE_DPAD_DOWN){focusIndex=Math.min(focusIndex+1,stemList.getChildCount()-1);updateFocus();return true;}
        if(key==KeyEvent.KEYCODE_DPAD_UP){focusIndex=Math.max(focusIndex-1,0);updateFocus();return true;}
        if(key==KeyEvent.KEYCODE_1){focusIndex=0;updateFocus();return true;}
        if(key==KeyEvent.KEYCODE_2){focusIndex=1;updateFocus();return true;}
        if(key==KeyEvent.KEYCODE_3){focusIndex=2;updateFocus();return true;}
        if(key==KeyEvent.KEYCODE_4){focusIndex=3;updateFocus();return true;}
        if(key==KeyEvent.KEYCODE_ENTER || key==KeyEvent.KEYCODE_DPAD_CENTER){togglePlay();return true;}
        return super.onKeyDown(key,e);
    }
}
