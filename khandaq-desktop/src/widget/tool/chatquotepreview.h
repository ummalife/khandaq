/*
    Copyright © 2026 The Khandaq Project Contributors

    This file is part of Khandaq, a Qt-based graphical interface for Tox.
*/

#pragma once

#include "src/model/air/airmodel.h"

#include <QWidget>

class AirIconButton;

/**
 * The quote above the composer: what Send will put in front of the message. It stands on the sunken
 * ground, set off by its colour alone (no frame and no stripe), with "Quote" over at most two lines
 * of the quoted words and a button that takes the quote away. Every value comes from the tokens.
 *
 * KHANDAQ (0.2.58): or a reply to an entry of the transcript, as on the phones: "In reply to Yusuf"
 * over one line of the quoted words, and Send puts the reply's header before the message
 * (Air::replyHead). One reply or quote waits at a time: a new one takes the place of the last.
 *
 * KHANDAQ (beta feedback 2026-11-22): or a saved message being edited (setEdit): "Editing" over
 * one line of its words as they were, its cross cancelling the edit. That is no quote: Send hands
 * over the field alone.
 */
class ChatQuotePreview : public QWidget
{
    Q_OBJECT
public:
    explicit ChatQuotePreview(QWidget* parent = nullptr);
    ~ChatQuotePreview() override;

    void setQuoteText(const QString& text);
    /**
     * A reply to the entry meta names. name is who wrote it as the bar says it; for an entry of
     * ours (self) the bar says "You". The words are quoted as the header will carry them.
     */
    void setReply(const Air::ReplyMeta& meta, const QString& name, bool self = false);
    /**
     * KHANDAQ (beta feedback 2026-11-22): a saved message is being edited; words are those it had.
     * It takes the place of a quote or a reply, and they take its place.
     */
    void setEdit(const QString& words);
    /** Takes away the quote, the reply or the edit. */
    void clearQuote();
    /** A quote or a reply waits for Send; an edit is neither. */
    bool hasQuote() const;
    /** KHANDAQ (beta feedback 2026-11-22): the bar says that a saved message is being edited. */
    bool isEdit() const;
    /** What waits is a reply (reply()), not a quote of the old kind. */
    bool isReply() const;
    /** The reply that waits; meaningful while isReply(). */
    const Air::ReplyMeta& reply() const;
    /** The quoted words: the quote, or the words a reply quotes. */
    QString quoteText() const;

    /**
     * The quoted words as they show: one line or two, the second cut short when they go on; a
     * reply shows one.
     */
    QStringList shownLines(int width) const;

    bool hasHeightForWidth() const override;
    int heightForWidth(int width) const override;
    QSize sizeHint() const override;
    QSize minimumSizeHint() const override;

signals:
    void cleared();
    /** A quote was set or cleared: what Send would hand over changed. */
    void changed();

public slots:
    void retranslateUi();

protected:
    void paintEvent(QPaintEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;
    void changeEvent(QEvent* event) override;

private:
    void restyle();
    /** Where the words go: the widget less its padding and the button at the end. */
    QRect textRect(int width) const;
    void placeButton();

private:
    AirIconButton* closeButton;
    QString quote;
    QString title;
    bool replying = false;
    bool editing = false;
    Air::ReplyMeta replyMeta;
    /** Who wrote the entry a reply answers, as the bar names them. */
    QString replyName;
    bool replyToSelf = false;
};
