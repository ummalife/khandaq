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

#include "../chatlinecontent.h"

#include "src/model/air/airconversation.h"
#include "src/model/air/airmessagemarks.h"
#include "src/widget/air/airtokens.h"

#include <QFont>
#include <QIcon>
#include <QString>
#include <QStringList>
#include <QVector>

class SmileyPack;

/**
 * One line of quiet text in the transcript: the time of an entry, its author above the text, a day
 * mark. Painted with the token colours of the current theme at paint time, so a theme change only
 * needs a repaint.
 */
class AirLabel : public ChatLineContent
{
    Q_OBJECT
public:
    enum class Ink
    {
        Ink,
        Ink2,
        Ink3,
        /** The author's tone. */
        Tone,
    };

    /**
     * @param copied Whether copying a selection of lines takes this text: the time and the author
     * are taken, a day mark is not.
     */
    AirLabel(const QString& text, AirTokens::TextRole role, Ink ink, int weight, bool copied,
             int tone = 0);

    void setText(const QString& text);
    /** The width the text takes on one line. */
    qreal naturalWidth() const;
    /**
     * Where the text stands in the label: at the start edge of the interface, as wide as it shows
     * (cut short when the label is narrower).
     */
    QRectF textRect() const;
    /**
     * KHANDAQ (0.2.58): the text opens a menu on a click (an author's name in a group, whose
     * member menu it opens): the pointer turns to a hand over it.
     */
    void setOpensMenu(bool opens);
    bool opensMenu() const;

    void setWidth(float width) override;
    QRectF boundingRect() const override;
    void paint(QPainter* painter, const QStyleOptionGraphicsItem* option, QWidget* widget) override;
    QString getText() const override;
    QString getSpokenText() const override;
    qreal getAscent() const override;

protected:
    void hoverMoveEvent(QGraphicsSceneHoverEvent* event) override;
    void hoverLeaveEvent(QGraphicsSceneHoverEvent* event) override;

private:
    QFont font() const;

    QString text;
    AirTokens::TextRole role;
    Ink ink;
    int weight;
    bool copied;
    int tone;
    qreal width = 0;
    bool opens = false;
};

/** The state mark of one of our messages: a clock, one check, two checks, or the red alert. */
class AirMark : public ChatLineContent
{
    Q_OBJECT
public:
    explicit AirMark(Air::EntryStatus status);

    void setStatus(Air::EntryStatus status);
    Air::EntryStatus getStatus() const;

    void setWidth(float width) override;
    QRectF boundingRect() const override;
    void paint(QPainter* painter, const QStyleOptionGraphicsItem* option, QWidget* widget) override;

private:
    Air::EntryStatus status;
};

/**
 * A call in the transcript: the phone glyph, then "Call, 4:12" in ink, or "Missed call" and
 * "No answer" in ink-2, in the body text.
 */
class AirCallLine : public ChatLineContent
{
    Q_OBJECT
public:
    AirCallLine(const QString& text, bool answered);

    void setText(const QString& text);

    void setWidth(float width) override;
    QRectF boundingRect() const override;
    void paint(QPainter* painter, const QStyleOptionGraphicsItem* option, QWidget* widget) override;
    QString getText() const override;
    qreal getAscent() const override;

private:
    QString text;
    bool answered;
    qreal width = 0;
};

/**
 * KHANDAQ (26.11.2026): the first line of a private message of a group in its feed
 * (DESIGN-group-contacts-private-mentions.md, part 2): a small lock and who gets it ("Privately to
 * Maryam: other members don't see this"), both in the private colour and the caption, the words
 * wrapped on at most two lines. The entry stands on the private-soft ground (ChatLine).
 */
class AirPrivateNote : public ChatLineContent
{
    Q_OBJECT
public:
    explicit AirPrivateNote(const QString& note);

    void setWidth(float width) override;
    QRectF boundingRect() const override;
    void paint(QPainter* painter, const QStyleOptionGraphicsItem* option, QWidget* widget) override;
    QString getText() const override;
    QString getSpokenText() const override;
    qreal getAscent() const override;

private:
    void fit();

    QString note;
    qreal width = 0;
    QStringList lines;
};

/**
 * The quote of a reply above its words (stage 3, item 10), as khandaq-ios ChatReplyQuoteView draws
 * it: on the sunken ground with the small radius, who wrote the quoted message in the caption at
 * weight 600, then the quoted words on at most two lines, both in ink-2. The plate is as wide as its
 * words, at most the text column. A reply that quotes no words shows no quote: it takes no room.
 */
class AirQuote : public ChatLineContent
{
    Q_OBJECT
public:
    AirQuote(const QString& name, const QString& preview);

    /** Returns whether the room it takes changed. */
    bool setQuote(const QString& name, const QString& preview);

    void setWidth(float width) override;
    QRectF boundingRect() const override;
    void paint(QPainter* painter, const QStyleOptionGraphicsItem* option, QWidget* widget) override;
    QString getSpokenText() const override;
    qreal getAscent() const override;

private:
    void fit();

    QString name;
    QString preview;
    qreal width = 0;
    /** The quoted words as they are drawn, and the width of the plate around them. */
    QStringList lines;
    qreal plateWidth = 0;
};

/**
 * What happened to an entry after it came (stage 3, item 10), under its text: the reactions, then
 * "edited" in the caption and ink-3, a pixel lower. A reaction is a chip, as the phones draw it
 * (AirTranscript.styleReactionChip on Android, AirReactionChipsView on iOS): its emoji and, once more
 * than one person chose it, the count in the caption and ink, on the raised plate with a hairline
 * and round ends. The chips follow each other from the start edge on one line and stop where the
 * column ends. An emoji takes the picture messages give it, from the smiley pack, so a heart is the
 * same heart in both; one the pack has no picture of is set in the colour emoji font of the system,
 * as messages set the sequences the pack has none of (0.2.61: it was the text font's glyph in ink-2,
 * a small grey ✍ for the ✍️ of an iPhone). An entry with neither takes no room.
 */
class AirEntryNotes : public ChatLineContent
{
    Q_OBJECT
public:
    /** smileys: the pack whose pictures messages show, or null when they show emoji as text. */
    AirEntryNotes(const Air::EntryMarks& marks, const SmileyPack* smileys);

    /** Returns whether the room it takes changed. */
    bool setNotes(const Air::EntryMarks& marks);

    /** The plates of the reactions that fit in the width, in this item, from the start edge. */
    QVector<QRectF> plates() const;

    void setWidth(float width) override;
    QRectF boundingRect() const override;
    void paint(QPainter* painter, const QStyleOptionGraphicsItem* option, QWidget* widget) override;
    QString getSpokenText() const override;
    qreal getAscent() const override;

private:
    /** One reaction as it is drawn: the emoji, its picture when the pack has one, its count. */
    struct Shown
    {
        QString emoji;
        QIcon picture;
        // Every member is set by the braces that make it: an aggregate, which an initializer here
        // would stop it being in C++11.
        // cppcheck-suppress uninitMemberVarNoCtor
        int count;
    };

    qreal height() const;
    QIcon pictureOf(const QString& emoji) const;

    const SmileyPack* smileys;
    bool edited = false;
    QVector<Air::Reaction> reactions;
    QVector<Shown> shown;
    qreal width = 0;
};
