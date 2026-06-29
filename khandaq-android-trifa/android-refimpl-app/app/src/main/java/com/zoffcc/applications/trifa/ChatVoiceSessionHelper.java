package com.zoffcc.applications.trifa;

/**
 * Keeps voice message recording and playback mutually exclusive in chat screens.
 */
public final class ChatVoiceSessionHelper
{
    private ChatVoiceSessionHelper()
    {
    }

    // KHANDAQ (#20): Telegram-style single voice playback across BOTH player implementations
    // (incoming bubbles use vVoicePlayerView, outgoing use VoicePlayerView) — they share this one
    // registry so starting any voice pauses whichever one was playing before.
    public interface ActiveVoicePlayer
    {
        void pauseForHandoff();
    }

    private static java.lang.ref.WeakReference<ActiveVoicePlayer> sActiveVoicePlayer = null;

    public static void becomeActiveVoicePlayer(final ActiveVoicePlayer player)
    {
        final ActiveVoicePlayer prev = (sActiveVoicePlayer != null) ? sActiveVoicePlayer.get() : null;
        if (prev != null && prev != player)
        {
            try
            {
                prev.pauseForHandoff();
            }
            catch (Exception ignored)
            {
            }
        }
        sActiveVoicePlayer = new java.lang.ref.WeakReference<>(player);
    }

    /** Call before starting microphone capture. */
    public static void onVoiceRecordingStarting()
    {
        stopAllVisibleVoicePlayback();
    }

    /** Call before starting voice message playback. */
    public static void onVoicePlaybackStarting()
    {
        cancelActiveVoiceRecording();
    }

    public static void stopAllVisibleVoicePlayback()
    {
        try
        {
            if (MainActivity.message_list_fragment != null)
            {
                MainActivity.message_list_fragment.stopVisibleVoicePlayback();
            }
        }
        catch (Exception ignored)
        {
        }

        try
        {
            if (MainActivity.group_message_list_fragment != null)
            {
                MainActivity.group_message_list_fragment.stopVisibleVoicePlayback();
            }
        }
        catch (Exception ignored)
        {
        }
    }

    public static void cancelActiveVoiceRecording()
    {
        try
        {
            if (MessageListActivity.ml_is_recording)
            {
                MessageListActivity.cancelVoiceRecording();
            }
        }
        catch (Exception ignored)
        {
        }

        try
        {
            if (GroupMessageListActivity.ml_is_recording)
            {
                GroupMessageListActivity.signal_group_voice_recording_cancel();
            }
        }
        catch (Exception ignored)
        {
        }
    }
}
