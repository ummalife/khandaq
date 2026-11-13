/*
    Copyright © 2026 by The Khandaq Project

    This file is part of Khandaq, a Qt-based graphical interface for Tox.

    Khandaq is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Khandaq is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Khandaq.  If not, see <http://www.gnu.org/licenses/>.
*/

#pragma once

#include "src/model/air/airvoicedecoder.h"

#include <QElapsedTimer>
#include <QFutureWatcher>
#include <QTimer>
#include <QWidget>

#include <memory>

class AirButton;
class IAudioControl;
class IAudioSink;
class QLabel;

/**
 * kq-bar: the bar of a transfer, 6 px high, a round track on the sunken ground and the part done in
 * ink. It fills from the start edge of the interface.
 */
class AirProgressBar : public QWidget
{
    Q_OBJECT
public:
    explicit AirProgressBar(QWidget* parent = nullptr);

    /** 0 to 1. */
    void setProgress(double share);
    double progress() const;

    QSize sizeHint() const override;

protected:
    void paintEvent(QPaintEvent* event) override;

private:
    double share = 0;
};

/**
 * The wave of a voice note. The notes carry no samples to draw, so the bars are a fixed
 * pseudo-wave, the same on every client (ChatVoiceMessageView.swift); the part played is in ink. A
 * click moves the note to that point.
 */
class AirVoiceWave : public QWidget
{
    Q_OBJECT
public:
    explicit AirVoiceWave(QWidget* parent = nullptr);

    void setPlayed(double share);
    double played() const;

    QSize sizeHint() const override;

signals:
    /** The note should go on from share (0 to 1) of its length: a click, or the end of a drag. */
    void seekRequested(double share);

protected:
    void paintEvent(QPaintEvent* event) override;
    void mousePressEvent(QMouseEvent* event) override;
    void mouseMoveEvent(QMouseEvent* event) override;
    void mouseReleaseEvent(QMouseEvent* event) override;

private:
    double shareAt(const QPoint& point) const;
    void showShare(double value);

    double share = 0;
    /** A drag is on: the wave follows the pointer, not the player, until it is let go. */
    bool held = false;
};

/**
 * Plays voice notes, one at a time, inside the app: the note is read whole into one channel
 * (AirVoiceDecoder) and played through the audio output of calls. Where this build cannot read a
 * note (an FFmpeg without the M4A demuxer or the AAC decoder), or there is no audio output, the
 * note opens in the system's player instead.
 */
class AirVoicePlayer : public QObject
{
    Q_OBJECT
public:
    static AirVoicePlayer& instance();
    ~AirVoicePlayer() override;

    /** The output to play through; null stops whatever plays and leaves only the system player. */
    void setAudio(IAudioControl* audio);

    /** Plays the note at path from where it stands, or pauses it while it plays. */
    void toggle(const QString& path);
    /** Plays the note at path from share (0 to 1) of its length. */
    void seek(const QString& path, double share);
    /** Stops the note that plays, if any, and puts it back to its start. */
    void stop();

    /** The note at path plays, or is about to once it is read. */
    bool isPlaying(const QString& path) const;
    /** How far the note at path has played, in milliseconds; 0 for any other. */
    qint64 positionMs(const QString& path) const;
    /** The length of the note at path once it has been read, in milliseconds; -1 before. */
    qint64 lengthMs(const QString& path) const;

signals:
    /** The note at path started, paused, ended or went on. */
    void changed(const QString& path);
    /** The note at path can be played neither here nor by the system. */
    void failed(const QString& path);

private:
    AirVoicePlayer();
    void select(const QString& path);
    void read(const QString& path);
    void onRead();
    void playFrom(qint64 ms);
    /** Hands the output the sound of the next seconds, a short piece at a time. */
    void feed();
    void tick();
    void silence();
    void handOver(const QString& path);
    qint64 currentPositionMs() const;

    IAudioControl* audio = nullptr;
    std::unique_ptr<IAudioSink> sink;
    QString current;
    std::shared_ptr<AirVoiceDecoder::Pcm> pcm;
    QFutureWatcher<std::shared_ptr<AirVoiceDecoder::Pcm>> reading;
    QString readingPath;
    /** The note should play: it does, or it will once read. */
    bool wanted = false;
    /** Where the note stands while it does not play, or where the sound started from. */
    qint64 offsetMs = 0;
    /** A seek that waits for the note to be read, 0 to 1; negative for none. */
    double pendingShare = -1;
    /** The first sample handed to the output since the sound started, and the next one to hand. */
    size_t startSample = 0;
    size_t nextSample = 0;
    QElapsedTimer clock;
    QTimer ticker;
};

/**
 * A voice note in the transcript: Listen and Pause on a button of one width, the wave, and the
 * length in tabular figures (the time played while it plays). A note that is not here yet shows its
 * size, and its button waits.
 */
class AirVoiceNote : public QWidget
{
    Q_OBJECT
public:
    explicit AirVoiceNote(QWidget* parent = nullptr);
    ~AirVoiceNote() override;

    /** lengthMs comes from the file's header (-1 when unknown); sizeText shows until it is here. */
    void setNote(const QString& path, qint64 lengthMs, bool here, const QString& sizeText);

    /** What a screen reader says for it: "Voice message, 0:42". */
    QString spokenText() const;

private:
    void restyle();
    void retranslate();
    void refresh();
    void refresh(const QString& changedPath);
    void showFailure(const QString& failedPath);

    AirButton* button = nullptr;
    AirVoiceWave* wave = nullptr;
    QLabel* time = nullptr;
    QString path;
    QString sizeText;
    qint64 headerLengthMs = -1;
    bool here = false;
};

/**
 * A call in the head of a conversation (E9 on the desktop): a band under the name with the state in
 * words and the buttons of that state, so the transcript stays in view. Nothing on it moves; the
 * time of a call counts in tabular figures.
 */
class AirCallBand : public QWidget
{
    Q_OBJECT
public:
    enum class Phase
    {
        /** No call: the band is hidden. */
        None,
        /** Theirs rings: Decline, With video for a video call, Answer. */
        Incoming,
        /** Ours rings: End. */
        Outgoing,
        /** On a call: the time, the microphone and the sound, End. */
        Active,
        /** Waits for the person to come online, then calls: Cancel. */
        Waiting,
    };

    explicit AirCallBand(QWidget* parent = nullptr);
    ~AirCallBand() override;

    /**
     * person is the short name, for "Waiting for Yusuf to come online"; video says that theirs is a
     * video call, so With video is offered.
     */
    void setPhase(Phase phase, const QString& person = QString(), bool video = false);
    Phase phase() const;
    /** How long the call has been on, shown while Active. */
    void setSeconds(qint64 seconds);
    /** Whether our microphone and their sound are off, for the words of the two switches. */
    void setMuted(bool microphone, bool sound);

signals:
    void answerRequested(bool video);
    void declineRequested();
    void endRequested();
    void microphoneToggled();
    void soundToggled();
    void waitCancelled();

private:
    void restyle();
    void retranslate();

    Phase current = Phase::None;
    QString person;
    bool video = false;
    qint64 seconds = 0;
    bool microphoneMuted = false;
    bool soundMuted = false;

    QLabel* glyph = nullptr;
    QLabel* state = nullptr;
    QLabel* note = nullptr;
    AirButton* declineButton = nullptr;
    AirButton* videoButton = nullptr;
    AirButton* answerButton = nullptr;
    AirButton* microphoneButton = nullptr;
    AirButton* soundButton = nullptr;
    AirButton* endButton = nullptr;
    AirButton* cancelButton = nullptr;
};
