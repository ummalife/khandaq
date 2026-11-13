/*
    Copyright © 2026 The Khandaq Project Contributors

    This file is part of Khandaq, a Qt-based graphical interface for Tox.
*/

#include "chatquotepreview.h"

#include "src/widget/air/airstyle.h"
#include "src/widget/air/airtokens.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/translator.h"

#include <QEvent>
#include <QFontMetrics>
#include <QPainter>
#include <QStyle>

#include <algorithm>

using AirTokens::Color;
using AirTokens::TextRole;
namespace Radius = AirTokens::Radius;
namespace Size = AirTokens::Size;
namespace Space = AirTokens::Space;

namespace {
/** Two lines never hold more: a long selection is cut before it is laid out. */
constexpr int maxLaidOut = 1024;
constexpr int maxLines = 2;

const AirStyle& air()
{
    return AirStyle::instance();
}
} // namespace

ChatQuotePreview::ChatQuotePreview(QWidget* parent)
    : QWidget(parent)
{
    setObjectName(QStringLiteral("quotePreview"));
    setVisible(false);
    QSizePolicy policy(QSizePolicy::Preferred, QSizePolicy::Fixed);
    policy.setHeightForWidth(true);
    setSizePolicy(policy);

    closeButton = new AirIconButton(QStringLiteral("x"), this);
    closeButton->setObjectName(QStringLiteral("quotePreviewClose"));
    connect(closeButton, &AirIconButton::clicked, this, [this]() {
        clearQuote();
        emit cleared();
    });

    AirPaint::followTheme(this);
    connect(&AirStyle::instance(), &AirStyle::changed, this, &ChatQuotePreview::restyle);
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

    setAccessibleDescription(quote);
    setVisible(true);
    updateGeometry();
    update();
    emit changed();
}

void ChatQuotePreview::clearQuote()
{
    const bool had = !quote.isEmpty();
    quote.clear();
    setAccessibleDescription(QString());
    setVisible(false);
    if (had) {
        emit changed();
    }
}

bool ChatQuotePreview::hasQuote() const
{
    return !quote.isEmpty();
}

QString ChatQuotePreview::quoteText() const
{
    return quote;
}

QStringList ChatQuotePreview::shownLines(int width) const
{
    return AirPaint::linesOf(quote.left(maxLaidOut), air().font(TextRole::Body),
                             textRect(width).width(), maxLines);
}

bool ChatQuotePreview::hasHeightForWidth() const
{
    return true;
}

int ChatQuotePreview::heightForWidth(int width) const
{
    const int lines = std::max(1, static_cast<int>(shownLines(width).size()));
    return Space::s8 + air().lineHeight(TextRole::Caption) + Space::s2
           + lines * air().lineHeight(TextRole::Body) + Space::s8;
}

QSize ChatQuotePreview::sizeHint() const
{
    const int width = this->width() > 0 ? this->width() : Size::textMeasure;
    return QSize(width, heightForWidth(width));
}

QSize ChatQuotePreview::minimumSizeHint() const
{
    return QSize(Space::s12 + Space::s48 + Space::s8 + Size::control + Space::s4,
                 heightForWidth(width()));
}

QRect ChatQuotePreview::textRect(int width) const
{
    const int room = width - Space::s12 - Space::s8 - Size::control - Space::s4;
    const QRect logical(Space::s12, Space::s8, std::max(0, room), std::max(0, height() - Space::s16));
    return QStyle::visualRect(layoutDirection(), QRect(0, 0, width, height()), logical);
}

void ChatQuotePreview::placeButton()
{
    const QRect logical(width() - Space::s4 - Size::control, (height() - Size::control) / 2,
                        Size::control, Size::control);
    closeButton->setGeometry(QStyle::visualRect(layoutDirection(), rect(), logical));
}

void ChatQuotePreview::retranslateUi()
{
    title = tr("Quote");
    setAccessibleName(title);
    const QString cancel = tr("Cancel quote");
    closeButton->setToolTip(cancel);
    closeButton->setAccessibleName(cancel);
    update();
}

void ChatQuotePreview::restyle()
{
    updateGeometry();
    update();
}

void ChatQuotePreview::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    // Set off by its ground, as every card is: no frame and no stripe at its side.
    AirPaint::box(painter, QRectF(rect()), Radius::field, air().color(Color::Sunken), QColor());

    const QRect area = textRect(width());
    const int start = QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft) | Qt::AlignVCenter
                      | Qt::TextSingleLine;
    const QFont caption = air().font(TextRole::Caption);
    const int captionLine = air().lineHeight(TextRole::Caption);
    painter.setFont(caption);
    painter.setPen(air().color(Color::Ink3));
    painter.drawText(QRect(area.x(), area.y(), area.width(), captionLine), start,
                     QFontMetrics(caption).elidedText(title, Qt::ElideRight, area.width()));

    painter.setFont(air().font(TextRole::Body));
    painter.setPen(air().color(Color::Ink2));
    const int bodyLine = air().lineHeight(TextRole::Body);
    int top = area.y() + captionLine + Space::s2;
    for (const QString& line : shownLines(width())) {
        AirPaint::ownText(painter, QRect(area.x(), top, area.width(), bodyLine), start, line);
        top += bodyLine;
    }
}

void ChatQuotePreview::resizeEvent(QResizeEvent* event)
{
    QWidget::resizeEvent(event);
    placeButton();
    // The words may take one line more or less at the new width.
    if (heightForWidth(width()) != height()) {
        updateGeometry();
    }
}

void ChatQuotePreview::changeEvent(QEvent* event)
{
    QWidget::changeEvent(event);
    if (event->type() == QEvent::LayoutDirectionChange) {
        placeButton();
        update();
    }
}
