/*
    Copyright © 2026 The Khandaq Project Contributors

    This file is part of Khandaq, a Qt-based graphical interface for Tox.
*/

#include "chatquotepreview.h"

#include "src/widget/translator.h"

#include <QHBoxLayout>
#include <QLabel>
#include <QRegularExpression>
#include <QToolButton>
#include <QVBoxLayout>

namespace {
constexpr int QUOTE_PREVIEW_MAX_CHARS = 160;
}

ChatQuotePreview::ChatQuotePreview(QWidget* parent)
    : QWidget(parent)
{
    setObjectName(QStringLiteral("quotePreview"));
    setVisible(false);

    auto* accentBar = new QWidget(this);
    accentBar->setObjectName(QStringLiteral("quotePreviewAccent"));
    accentBar->setFixedWidth(3);

    auto* textColumn = new QVBoxLayout();
    textColumn->setSpacing(0);
    textColumn->setContentsMargins(0, 0, 0, 0);

    titleLabel = new QLabel(this);
    titleLabel->setObjectName(QStringLiteral("quotePreviewTitle"));

    textLabel = new QLabel(this);
    textLabel->setObjectName(QStringLiteral("quotePreviewText"));
    textLabel->setWordWrap(true);
    textLabel->setTextInteractionFlags(Qt::TextSelectableByMouse);

    textColumn->addWidget(titleLabel);
    textColumn->addWidget(textLabel);

    closeButton = new QToolButton(this);
    closeButton->setObjectName(QStringLiteral("quotePreviewClose"));
    closeButton->setText(QStringLiteral("×"));
    closeButton->setAutoRaise(true);
    connect(closeButton, &QToolButton::clicked, this, [this]() {
        clearQuote();
        emit cleared();
    });

    auto* layout = new QHBoxLayout(this);
    layout->setContentsMargins(8, 6, 6, 6);
    layout->setSpacing(8);
    layout->addWidget(accentBar);
    layout->addLayout(textColumn, 1);
    layout->addWidget(closeButton, 0, Qt::AlignTop);

    retranslateUi();
    Translator::registerHandler(std::bind(&ChatQuotePreview::retranslateUi, this), this);
}

ChatQuotePreview::~ChatQuotePreview()
{
    Translator::unregister(this);
}

void ChatQuotePreview::setQuoteText(const QString& text)
{
    quote = text.trimmed();
    if (quote.isEmpty()) {
        clearQuote();
        return;
    }

    textLabel->setText(previewText(quote));
    setVisible(true);
}

void ChatQuotePreview::clearQuote()
{
    quote.clear();
    textLabel->clear();
    setVisible(false);
}

bool ChatQuotePreview::hasQuote() const
{
    return !quote.isEmpty();
}

QString ChatQuotePreview::quoteText() const
{
    return quote;
}

QString ChatQuotePreview::previewText(const QString& text) const
{
    QString singleLine = text;
    singleLine.replace(QRegularExpression(QStringLiteral("[\\r\\n\\u2028\\u2029]+")),
                     QStringLiteral(" "));

    if (singleLine.size() > QUOTE_PREVIEW_MAX_CHARS) {
        singleLine = singleLine.left(QUOTE_PREVIEW_MAX_CHARS).trimmed() + QStringLiteral("…");
    }

    return singleLine;
}

void ChatQuotePreview::retranslateUi()
{
    titleLabel->setText(tr("Quote"));
    closeButton->setToolTip(tr("Cancel quote"));
}
