/*
    Copyright © 2026 The Khandaq Project Contributors

    This file is part of Khandaq, a Qt-based graphical interface for Tox.
*/

#pragma once

#include <QWidget>

class QLabel;
class QToolButton;

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

signals:
    void cleared();

public slots:
    void retranslateUi();

private:
    QString previewText(const QString& text) const;

private:
    QLabel* titleLabel;
    QLabel* textLabel;
    QToolButton* closeButton;
    QString quote;
};
