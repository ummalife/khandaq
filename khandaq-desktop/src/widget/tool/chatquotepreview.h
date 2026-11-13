/*
    Copyright © 2026 The Khandaq Project Contributors

    This file is part of Khandaq, a Qt-based graphical interface for Tox.
*/

#pragma once

#include <QWidget>

class AirIconButton;

/**
 * The quote above the composer: what Send will put in front of the message. It stands on the sunken
 * ground, set off by its colour alone (no frame and no stripe), with "Quote" over at most two lines
 * of the quoted words and a button that takes the quote away. Every value comes from the tokens.
 */
class ChatQuotePreview : public QWidget
{
    Q_OBJECT
public:
    explicit ChatQuotePreview(QWidget* parent = nullptr);
    ~ChatQuotePreview() override;

    void setQuoteText(const QString& text);
    void clearQuote();
    bool hasQuote() const;
    QString quoteText() const;

    /** The quoted words as they show: one line or two, the second cut short when they go on. */
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
};
