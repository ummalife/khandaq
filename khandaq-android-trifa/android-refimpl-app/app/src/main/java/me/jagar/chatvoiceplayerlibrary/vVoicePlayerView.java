    /*
    This class is a part of Chat Voice Player View Android Library
    Developed by: Jagar Yousef
    Date: 2019
    */

package me.jagar.chatvoiceplayerlibrary;
import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import org.khandaq.messenger.R;
import com.zoffcc.applications.trifa.ChatVoiceSessionHelper;
import com.zoffcc.applications.trifa.VFileMediaDataSource;

import java.io.IOException;

import info.guardianproject.iocipher.File;
import info.guardianproject.iocipher.RandomAccessFile;


    public class vVoicePlayerView extends LinearLayout
            implements ChatVoiceSessionHelper.ActiveVoicePlayer,
            com.zoffcc.applications.trifa.ChatVoicePlaybackManager.Listener {

    // KHANDAQ: this bubble plays files from the encrypted IOCipher VFS (incoming voice)
    private static final boolean IS_VFS = true;

    private int playPaueseBackgroundColor, shareBackgroundColor, viewBackgroundColor,
            seekBarProgressColor, seekBarThumbColor, progressTimeColor, timingBackgroundColor,
            visualizationPlayedColor, visualizationNotPlayedColor, playProgressbarColor;
    private float viewCornerRadius, playPauseCornerRadius, shareCornerRadius;
    private boolean showShareButton, showTiming, enableVirtualizer;
    private GradientDrawable playPauseShape, shareShape, viewShape;
    private Context context;
    private String vaudiopath;
    private String shareTitle = "Share Voice";

    private LinearLayout main_layout, padded_layout, container_layout;
    private ImageView imgPlay, imgPause, imgShare;
    private SeekBar seekBar;
    private ProgressBar progressBar;
    private TextView txtProcess;
    private MediaPlayer mediaPlayer;
    private ProgressBar pb_play;

    // KHANDAQ: playback moved to the global ChatVoicePlaybackManager (single MediaPlayer app-wide,
    // survives row recycling + chat switches, remembers per-file position).
    @Override
    public void pauseForHandoff() {
        // no-op: exclusivity is guaranteed by ChatVoicePlaybackManager
    }

    private PlayerVisualizerSeekbar seekbarV;
    private Uri contentUri = null;

    public vVoicePlayerView(Context context) {
        super(context);
        LayoutInflater.from(context).inflate(R.layout.voiceplayerview_main_view, this);
        this.context = context;
    }

    public vVoicePlayerView(Context context,  AttributeSet attrs) {
        super(context, attrs);
        initViews(context, attrs);
        this.context = context;
    }

    public vVoicePlayerView(Context context,  AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initViews(context, attrs);
        this.context = context;
    }

    private void initViews(Context context, AttributeSet attrs) {

        TypedArray typedArray = context.getTheme().obtainStyledAttributes(attrs,
                R.styleable.VoicePlayerView, 0, 0);

        viewShape = new GradientDrawable();
        playPauseShape = new GradientDrawable();
        shareShape = new GradientDrawable();

        try{
            showShareButton = typedArray.getBoolean(R.styleable.VoicePlayerView_showShareButton, true);
            showTiming = typedArray.getBoolean(R.styleable.VoicePlayerView_showTiming, true);
            viewCornerRadius = typedArray.getFloat(R.styleable.VoicePlayerView_viewCornerRadius, 0);
            playPauseCornerRadius = typedArray.getFloat(R.styleable.VoicePlayerView_playPauseCornerRadius, 0);
            shareCornerRadius = typedArray.getFloat(R.styleable.VoicePlayerView_shareCornerRadius, 0);
            playPaueseBackgroundColor = typedArray.getColor(R.styleable.VoicePlayerView_playPauseBackgroundColor, getResources().getColor(R.color.md_pink_100));
            shareBackgroundColor = typedArray.getColor(R.styleable.VoicePlayerView_shareBackgroundColor, getResources().getColor(R.color.md_pink_500));
            viewBackgroundColor = typedArray.getColor(R.styleable.VoicePlayerView_viewBackground, getResources().getColor(R.color.md_white_1000));
            seekBarProgressColor = typedArray.getColor(R.styleable.VoicePlayerView_seekBarProgressColor, getResources().getColor(R.color.md_pink_500));
            seekBarThumbColor = typedArray.getColor(R.styleable.VoicePlayerView_seekBarThumbColor, getResources().getColor(R.color.md_pink_500));
            progressTimeColor = typedArray.getColor(R.styleable.VoicePlayerView_progressTimeColor, Color.GRAY);
            shareTitle = typedArray.getString(R.styleable.VoicePlayerView_shareText);
            enableVirtualizer = typedArray.getBoolean(R.styleable.VoicePlayerView_enableVisualizer, false);
            timingBackgroundColor = typedArray.getColor(R.styleable.VoicePlayerView_timingBackgroundColor, getResources().getColor(android.R.color.transparent));
            visualizationNotPlayedColor = typedArray.getColor(R.styleable.VoicePlayerView_visualizationNotPlayedColor, getResources().getColor(R.color.gray));
            visualizationPlayedColor = typedArray.getColor(R.styleable.VoicePlayerView_visualizationPlayedColor, getResources().getColor(R.color.md_pink_500));
            playProgressbarColor= typedArray.getColor(R.styleable.VoicePlayerView_playProgressbarColor, getResources().getColor(R.color.md_pink_500));



        }finally {
            typedArray.recycle();
        }


        LayoutInflater.from(context).inflate(R.layout.voiceplayerview_main_view, this);
        main_layout = this.findViewById(R.id.collectorLinearLayout);
        padded_layout = this.findViewById(R.id.paddedLinearLayout);
        container_layout = this.findViewById(R.id.containerLinearLayout);
        imgPlay = this.findViewById(R.id.imgPlay);
        imgPause = this.findViewById(R.id.imgPause);
        imgShare = this.findViewById(R.id.imgShare);
        seekBar = this.findViewById(R.id.seekBar);
        progressBar = this.findViewById(R.id.progressBar);
        txtProcess = this.findViewById(R.id.txtTime);
        seekbarV = this.findViewById(R.id.seekBarV);
        pb_play = this.findViewById(R.id.pb_play);


        viewShape.setColor(viewBackgroundColor);
        viewShape.setCornerRadius(viewCornerRadius);
        playPauseShape.setColor(playPaueseBackgroundColor);
        playPauseShape.setCornerRadius(playPauseCornerRadius);
        shareShape.setColor(shareBackgroundColor);
        shareShape.setCornerRadius(shareCornerRadius);

        imgPlay.setBackground(playPauseShape);
        imgPause.setBackground(playPauseShape);
        imgShare.setBackground(shareShape);
        main_layout.setBackground(viewShape);
        seekBar.getProgressDrawable().setColorFilter(seekBarProgressColor, PorterDuff.Mode.SRC_IN);
        seekBar.getThumb().setColorFilter(seekBarThumbColor, PorterDuff.Mode.SRC_IN);

        GradientDrawable timingBackground = new GradientDrawable();
        timingBackground.setColor(timingBackgroundColor);
        timingBackground.setCornerRadius(25);
        txtProcess.setBackground(timingBackground);
        txtProcess.setPadding(16, 0, 16, 0);
        txtProcess.setTextColor(progressTimeColor);


        pb_play.getIndeterminateDrawable().setColorFilter(
                playProgressbarColor,
                PorterDuff.Mode.SRC_IN);



        if (!showShareButton)
            imgShare.setVisibility(GONE);
        if (!showTiming)
            txtProcess.setVisibility(INVISIBLE);

        if (enableVirtualizer){
            seekbarV.setVisibility(VISIBLE);
            seekBar.setVisibility(GONE);
            seekbarV.getProgressDrawable().setColorFilter(getResources().getColor(android.R.color.transparent), PorterDuff.Mode.SRC_IN);
            seekbarV.getThumb().setColorFilter(getResources().getColor(android.R.color.transparent), PorterDuff.Mode.SRC_IN);
            seekbarV.setColors(visualizationPlayedColor, visualizationNotPlayedColor);
        }

    }


    //Set the audio source — playback state lives in ChatVoicePlaybackManager

    public void setAudio(String vpath){
        bindAudio(vpath);
    }

    // KHANDAQ: bind = wire listeners + render current global state. NEVER touches the global
    // playback (a row re-bind used to stop the audio and reset progress to 0).
    private void bindAudio(String vpath) {
        vaudiopath = vpath;

        seekBar.setOnSeekBarChangeListener(seekBarListener);
        imgPlay.setOnClickListener(imgPlayClickListener);
        imgPause.setOnClickListener(imgPauseClickListener);
        imgShare.setOnClickListener(imgShareClickListener);
        if (seekbarV.getVisibility() == VISIBLE && vaudiopath != null){
            seekbarV.setOnSeekBarChangeListener(seekBarListener);
            seekbarV.vupdateVisualizer(new info.guardianproject.iocipher.File(vaudiopath));
        }

        com.zoffcc.applications.trifa.ChatVoicePlaybackManager.attach(this);
        renderFromManager();
    }

    // KHANDAQ: paint play/pause + progress + time from the global manager state.
    private void renderFromManager() {
        if (vaudiopath == null) {
            return;
        }
        final int dur = com.zoffcc.applications.trifa.ChatVoicePlaybackManager.durationMs(vaudiopath, IS_VFS);
        final boolean mine = com.zoffcc.applications.trifa.ChatVoicePlaybackManager.isActivePath(vaudiopath);
        final boolean playing = mine && com.zoffcc.applications.trifa.ChatVoicePlaybackManager.isPlaying();
        final int pos = com.zoffcc.applications.trifa.ChatVoicePlaybackManager.positionMs(vaudiopath);
        applyPlaybackUi(playing, pos, dur);
    }

    private void applyPlaybackUi(final boolean playing, final int rawPos, final int dur) {
        if (dur > 0) {
            seekBar.setMax(dur);
            if (seekbarV.getVisibility() == VISIBLE) {
                seekbarV.setMax(dur);
            }
        }
        imgPause.setVisibility(playing ? View.VISIBLE : View.GONE);
        imgPlay.setVisibility(playing ? View.GONE : View.VISIBLE);

        // at (or within 400ms of) the end → show as reset
        final int pos = (dur > 0 && rawPos > 0 && dur - rawPos <= 400) ? 0 : rawPos;
        seekBar.setProgress(pos);
        if (seekbarV.getVisibility() == VISIBLE) {
            seekbarV.setProgress(pos);
            if (dur > 0) {
                seekbarV.updatePlayerPercent((float) pos / dur);
            }
        }
        if (dur > 0) {
            txtProcess.setText(pos > 0
                    ? convertSecondsToHMmSs(pos / 1000) + " / " + convertSecondsToHMmSs(dur / 1000)
                    : convertSecondsToHMmSs(dur / 1000));
        }
    }

    // KHANDAQ: manager tick (UI thread) — live progress if this bubble's file is the active one,
    // otherwise the remembered position of this file.
    @Override
    public void onVoicePlaybackTick(final String activePath, final boolean playing, final int positionMs) {
        if (vaudiopath == null) {
            return;
        }
        final boolean mine = vaudiopath.equals(activePath);
        final int dur = seekBar.getMax();
        final int pos = mine ? positionMs
                : com.zoffcc.applications.trifa.ChatVoicePlaybackManager.positionMs(vaudiopath);
        applyPlaybackUi(mine && playing, pos, dur);
    }



    //Components' listeners

    OnClickListener imgPlayClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            try{
                ChatVoiceSessionHelper.onVoicePlaybackStarting();
                com.zoffcc.applications.trifa.ChatVoicePlaybackManager.play(vaudiopath, IS_VFS);
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    };



    private SeekBar.OnSeekBarChangeListener seekBarListener = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(final SeekBar seekBar, final int progress, boolean fromUser) {
            if (fromUser) {
                try {
                    com.zoffcc.applications.trifa.ChatVoicePlaybackManager.seekTo(vaudiopath, progress);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            try{
                ChatVoiceSessionHelper.onVoicePlaybackStarting();
                com.zoffcc.applications.trifa.ChatVoicePlaybackManager.play(vaudiopath, IS_VFS);
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    };

    OnClickListener imgPauseClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            try{
                com.zoffcc.applications.trifa.ChatVoicePlaybackManager.pause();
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    };

    OnClickListener imgShareClickListener = new OnClickListener() {
        @Override
        public void onClick(View view) {
        }
    };

    // KHANDAQ: the old per-view update() polling loop is gone — progress comes from
    // ChatVoicePlaybackManager ticks (onVoicePlaybackTick above).

    //Convert long milli seconds to a formatted String to display it

    private static String convertSecondsToHMmSs(long seconds) {
        long s = seconds % 60;
        long m = (seconds / 60) % 60;
        long h = (seconds / (60 * 60)) % 24;
        // KHANDAQ (Figma voice bubble): short "m:ss" (drop leading hours when 0) instead of verbose "00:00:00"
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%d:%02d", m, s);
    }

    // KHANDAQ: rows get detached/recycled all the time while scrolling and when leaving the chat —
    // that must NOT stop the global playback anymore. Playback lifetime is owned by
    // ChatVoicePlaybackManager; these lifecycle hooks are intentionally no-ops now.

    public void onStop()
    {
    }

    public void onPause(){
    }


  // Programmatically functions

   public void setViewBackgroundShape(int color, float radius){
       GradientDrawable shape = new GradientDrawable();
       shape.setColor(getResources().getColor(color));
       shape.setCornerRadius(radius);
       main_layout.setBackground(shape);
   }
    public void setShareBackgroundShape(int color, float radius){
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(getResources().getColor(color));
        shape.setCornerRadius(radius);
        imgShare.setBackground(shape);
    }
    public void setPlayPaueseBackgroundShape(int color, float radius){
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(getResources().getColor(color));
        shape.setCornerRadius(radius);
        imgPause.setBackground(shape);
        imgPlay.setBackground(shape);
    }
    public void setSeekBarStyle(int progressColor, int thumbColor){
        seekBar.getProgressDrawable().setColorFilter(getResources().getColor(progressColor), PorterDuff.Mode.SRC_IN);
        seekBar.getThumb().setColorFilter(getResources().getColor(thumbColor), PorterDuff.Mode.SRC_IN);
    }
    public void setTimingVisibility(boolean visibility){
       if (!visibility)
           txtProcess.setVisibility(INVISIBLE);
       else
           txtProcess.setVisibility(VISIBLE);
    }
    public void setShareButtonVisibility(boolean visibility){
        if (!visibility)
            imgShare.setVisibility(GONE);
        else
            imgShare.setVisibility(VISIBLE);
    }
    public void setShareText(String shareText){
       shareTitle = shareText;
    }

    public void showPlayProgressbar(){
        imgPlay.setVisibility(GONE);
        pb_play.setVisibility(VISIBLE);
    }

    public void hidePlayProgresbar(){
        pb_play.setVisibility(GONE);
        imgPlay.setVisibility(VISIBLE);
    }

    // KHANDAQ: re-bind of a recycled row. Used to stop the audio and reset progress to 0 — now it
    // only re-wires the view to the global playback state.
    public void refreshPlayer(String vpath){
        bindAudio(vpath);
        seekBar.invalidate();
        seekbarV.invalidate();
        this.invalidate();
    }


    public void refreshVisualizer() {
        if (seekbarV.getVisibility() == VISIBLE){
            seekbarV.vupdateVisualizer(new info.guardianproject.iocipher.File(vaudiopath));
        }
    }

    public ProgressBar getPlayProgressbar(){
        return pb_play;
    }

        public int getPlayPaueseBackgroundColor() {
            return playPaueseBackgroundColor;
        }

        public void setPlayPaueseBackgroundColor(int playPaueseBackgroundColor) {
            this.playPaueseBackgroundColor = playPaueseBackgroundColor;
        }

        public int getShareBackgroundColor() {
            return shareBackgroundColor;
        }

        public void setShareBackgroundColor(int shareBackgroundColor) {
            this.shareBackgroundColor = shareBackgroundColor;
        }

        public int getViewBackgroundColor() {
            return viewBackgroundColor;
        }

        public void setViewBackgroundColor(int viewBackgroundColor) {
            this.viewBackgroundColor = viewBackgroundColor;
        }

        public int getSeekBarProgressColor() {
            return seekBarProgressColor;
        }

        public void setSeekBarProgressColor(int seekBarProgressColor) {
            this.seekBarProgressColor = seekBarProgressColor;
        }

        public int getSeekBarThumbColor() {
            return seekBarThumbColor;
        }

        public void setSeekBarThumbColor(int seekBarThumbColor) {
            this.seekBarThumbColor = seekBarThumbColor;
        }

        public int getProgressTimeColor() {
            return progressTimeColor;
        }

        public void setProgressTimeColor(int progressTimeColor) {
            this.progressTimeColor = progressTimeColor;
        }

        public int getTimingBackgroundColor() {
            return timingBackgroundColor;
        }

        public void setTimingBackgroundColor(int timingBackgroundColor) {
            this.timingBackgroundColor = timingBackgroundColor;
        }

        public int getVisualizationPlayedColor() {
            return visualizationPlayedColor;
        }

        public void setVisualizationPlayedColor(int visualizationPlayedColor) {
            this.visualizationPlayedColor = visualizationPlayedColor;
        }

        public int getVisualizationNotPlayedColor() {
            return visualizationNotPlayedColor;
        }

        public void setVisualizationNotPlayedColor(int visualizationNotPlayedColor) {
            this.visualizationNotPlayedColor = visualizationNotPlayedColor;
        }

        public int getPlayProgressbarColor() {
            return playProgressbarColor;
        }

        public void setPlayProgressbarColor(int playProgressbarColor) {
            this.playProgressbarColor = playProgressbarColor;
        }

        public float getViewCornerRadius() {
            return viewCornerRadius;
        }

        public void setViewCornerRadius(float viewCornerRadius) {
            this.viewCornerRadius = viewCornerRadius;
        }

        public float getPlayPauseCornerRadius() {
            return playPauseCornerRadius;
        }

        public void setPlayPauseCornerRadius(float playPauseCornerRadius) {
            this.playPauseCornerRadius = playPauseCornerRadius;
        }

        public float getShareCornerRadius() {
            return shareCornerRadius;
        }

        public void setShareCornerRadius(float shareCornerRadius) {
            this.shareCornerRadius = shareCornerRadius;
        }

        public boolean isShowShareButton() {
            return showShareButton;
        }

        public void setShowShareButton(boolean showShareButton) {
            this.showShareButton = showShareButton;
        }

        public boolean isShowTiming() {
            return showTiming;
        }

        public void setShowTiming(boolean showTiming) {
            this.showTiming = showTiming;
        }

        public boolean isEnableVirtualizer() {
            return enableVirtualizer;
        }

        public void setEnableVirtualizer(boolean enableVirtualizer) {
            this.enableVirtualizer = enableVirtualizer;
        }

        public GradientDrawable getPlayPauseShape() {
            return playPauseShape;
        }

        public void setPlayPauseShape(GradientDrawable playPauseShape) {
            this.playPauseShape = playPauseShape;
        }

        public GradientDrawable getShareShape() {
            return shareShape;
        }

        public void setShareShape(GradientDrawable shareShape) {
            this.shareShape = shareShape;
        }

        public GradientDrawable getViewShape() {
            return viewShape;
        }

        public void setViewShape(GradientDrawable viewShape) {
            this.viewShape = viewShape;
        }

        public void setContext(Context context) {
            this.context = context;
        }

        public String getPath() {
            return vaudiopath;
        }

        public void setPath(String vpath) {
            this.vaudiopath = vpath;
        }

        public String getShareTitle() {
            return shareTitle;
        }

        public void setShareTitle(String shareTitle) {
            this.shareTitle = shareTitle;
        }

        public LinearLayout getMain_layout() {
            return main_layout;
        }

        public void setMain_layout(LinearLayout main_layout) {
            this.main_layout = main_layout;
        }

        public LinearLayout getPadded_layout() {
            return padded_layout;
        }

        public void setPadded_layout(LinearLayout padded_layout) {
            this.padded_layout = padded_layout;
        }

        public LinearLayout getContainer_layout() {
            return container_layout;
        }

        public void setContainer_layout(LinearLayout container_layout) {
            this.container_layout = container_layout;
        }

        public ImageView getImgPlay() {
            return imgPlay;
        }

        public void setImgPlay(ImageView imgPlay) {
            this.imgPlay = imgPlay;
        }

        public ImageView getImgPause() {
            return imgPause;
        }

        public void setImgPause(ImageView imgPause) {
            this.imgPause = imgPause;
        }

        public ImageView getImgShare() {
            return imgShare;
        }

        public void setImgShare(ImageView imgShare) {
            this.imgShare = imgShare;
        }

        public SeekBar getSeekBar() {
            return seekBar;
        }

        public void setSeekBar(SeekBar seekBar) {
            this.seekBar = seekBar;
        }

        public ProgressBar getProgressBar() {
            return progressBar;
        }

        public void setProgressBar(ProgressBar progressBar) {
            this.progressBar = progressBar;
        }

        public TextView getTxtProcess() {
            return txtProcess;
        }

        public void setTxtProcess(TextView txtProcess) {
            this.txtProcess = txtProcess;
        }

        public MediaPlayer getMediaPlayer() {
            return mediaPlayer;
        }

        public void setMediaPlayer(MediaPlayer mediaPlayer) {
            this.mediaPlayer = mediaPlayer;
        }

        public ProgressBar getPb_play() {
            return pb_play;
        }

        public void setPb_play(ProgressBar pb_play) {
            this.pb_play = pb_play;
        }

        public PlayerVisualizerSeekbar getSeekbarV() {
            return seekbarV;
        }

        public void setSeekbarV(PlayerVisualizerSeekbar seekbarV) {
            this.seekbarV = seekbarV;
        }

        public OnClickListener getImgPlayClickListener() {
            return imgPlayClickListener;
        }

        public void setImgPlayClickListener(OnClickListener imgPlayClickListener) {
            this.imgPlayClickListener = imgPlayClickListener;
        }

        public SeekBar.OnSeekBarChangeListener getSeekBarListener() {
            return seekBarListener;
        }

        public void setSeekBarListener(SeekBar.OnSeekBarChangeListener seekBarListener) {
            this.seekBarListener = seekBarListener;
        }

        public OnClickListener getImgPauseClickListener() {
            return imgPauseClickListener;
        }

        public void setImgPauseClickListener(OnClickListener imgPauseClickListener) {
            this.imgPauseClickListener = imgPauseClickListener;
        }

        public OnClickListener getImgShareClickListener() {
            return imgShareClickListener;
        }

        public void setImgShareClickListener(OnClickListener imgShareClickListener) {
            this.imgShareClickListener = imgShareClickListener;
        }

        public Uri getContentUri() {
            return contentUri;
        }

        public void setContentUri(Uri contentUri) {
            this.contentUri = contentUri;
        }
    }


