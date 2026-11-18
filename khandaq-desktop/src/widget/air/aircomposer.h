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

#include "src/model/air/airconversation.h"

#include <QPointer>
#include <QWidget>

class AirButton;
class AirIconButton;
class AirProgressBar;
class QLabel;
class QTextEdit;
class QVariantAnimation;
class QVBoxLayout;

/**
 * kq-composer: the framed field with the paperclip, the emoji and "Send" of the same height, and
 * one quiet line under it about what happens to a message sent now ("Goes right away: Amina is
 * online"), with the room left once a tenth of a message remains. The field itself is the form's
 * own text edit, which keeps its keys and its paste handling.
 */
class AirComposer : public QWidget
{
    Q_OBJECT
public:
    AirComposer(QTextEdit* field, QWidget* parent = nullptr);

    /** Notes to yourself attach nothing. */
    void setAttachVisible(bool visible);
    /** What the paperclip says before a file is chosen: a group's limit. Empty for none. */
    void setAttachNote(const QString& note);
    /** KHANDAQ (0.2.60): the emoji button, hidden when there is no set to choose from. */
    void setEmojiVisible(bool visible);
    /** KHANDAQ (0.2.60): the microphone, hidden where no note can go (notes) or be written. */
    void setVoiceVisible(bool visible);
    /**
     * A voice note being recorded (kq-progress): in place of the field the microphone, "Recording a
     * voice message", its time and how much of the longest note it takes; the cross in place of the
     * paperclip cancels it (Esc too), Send sends it (Enter too). False brings the field back.
     */
    void setRecording(bool recording);
    bool isRecording() const;
    /** The time of the note being recorded, and its share of the longest note (0 to 1). */
    void setRecordingTime(qint64 ms, double share);
    /** An empty text hides the line; the change cross-fades without moving anything. */
    void setHint(const QString& text, Air::ComposeHint::Dot dot);
    /** "120 bytes left"; empty hides it. */
    void setCounter(const QString& text);
    /** Something that belongs to the next message (a quote) sits right above the field. */
    void addAbove(QWidget* widget);
    /**
     * kq-readonly in place of the field, while you may not write in a group: why, in one sentence,
     * and a button that writes to someone who may. An empty text brings the field back. A
     * non-empty unavailable keeps the button but disables it and says why under it (kq-why).
     */
    void setReadOnly(const QString& text, const QString& action,
                     const QString& unavailable = QString());
    bool isReadOnly() const;
    /** Send waits while the text cannot go as it is: a group message over its limit. */
    void setSendBlocked(bool blocked);

signals:
    void sendRequested();
    void attachRequested(const QPoint& globalPos);
    /** The emoji button, with the middle of its top edge: the choice opens above it. */
    void emojiRequested(const QPoint& globalPos);
    /** The microphone: start a voice note. */
    void voiceRequested();
    /** The cross or Esc while a note is recorded. */
    void voiceCancelRequested();
    /** Send or Enter while a note is recorded. */
    void voiceSendRequested();
    /** The button of the read-only bar. */
    void readOnlyActionRequested();

protected:
    void paintEvent(QPaintEvent* event) override;
    bool eventFilter(QObject* watched, QEvent* event) override;
    void keyPressEvent(QKeyEvent* event) override;

private:
    void restyle();
    /** The line under the field: a caption high while empty, as high as its words need. */
    void keepHintHeight();
    QRect frameRect() const;

    QTextEdit* field;
    AirIconButton* attach = nullptr;
    QString attachNote;
    AirIconButton* emoji = nullptr;
    AirIconButton* voice = nullptr;
    AirIconButton* cancelVoice = nullptr;
    bool attachShown = true;
    bool emojiShown = true;
    bool voiceShown = true;
    QWidget* recording = nullptr;
    QLabel* recordingIcon = nullptr;
    QLabel* recordingTitle = nullptr;
    QLabel* recordingTime = nullptr;
    AirProgressBar* recordingBar = nullptr;
    qint64 recordedMs = 0;
    AirButton* send = nullptr;
    QWidget* hintLine = nullptr;
    QWidget* dot = nullptr;
    QLabel* hint = nullptr;
    QLabel* counter = nullptr;
    QWidget* frame = nullptr;
    /** Everything that is for writing: what sits above the field, the field and the line under it. */
    QWidget* writing = nullptr;
    QWidget* readOnlyBar = nullptr;
    QLabel* readOnlyIcon = nullptr;
    QLabel* readOnlyText = nullptr;
    AirButton* readOnlyButton = nullptr;
    QLabel* readOnlyWhy = nullptr;
    QVBoxLayout* column = nullptr;
    Air::ComposeHint::Dot dotKind = Air::ComposeHint::Dot::None;
    QPointer<QVariantAnimation> fade;
};
