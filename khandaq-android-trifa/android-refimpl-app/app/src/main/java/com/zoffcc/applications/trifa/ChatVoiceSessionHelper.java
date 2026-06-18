package com.zoffcc.applications.trifa;

/**
 * Keeps voice message recording and playback mutually exclusive in chat screens.
 */
public final class ChatVoiceSessionHelper
{
    private ChatVoiceSessionHelper()
    {
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
