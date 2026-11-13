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

#include "airpanels.h"

#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/qrwidget.h"
#include "src/widget/translator.h"

#include <QApplication>
#include <QBoxLayout>
#include <QDialog>
#include <QGraphicsOpacityEffect>
#include <QKeyEvent>
#include <QLabel>
#include <QLinearGradient>
#include <QMouseEvent>
#include <QPainter>
#include <QPainterPath>
#include <QScrollArea>
#include <QScrollBar>
#include <QStyle>
#include <QTimer>
#include <QVariantAnimation>

#include <algorithm>
#include <tuple>

using AirStrings::Text;
using AirTokens::Color;
using AirTokens::TextRole;
namespace Elevation = AirTokens::Elevation;
namespace Motion = AirTokens::Motion;
namespace Radius = AirTokens::Radius;
namespace Space = AirTokens::Space;
namespace Stroke = AirTokens::Stroke;

namespace {
const AirStyle& air()
{
    return AirStyle::instance();
}

/** The dimmed conversation stays in view under an open dossier at least this high. */
constexpr int keepInView = Space::s64;

/** The widget that has the focus now, unless it is panel or inside it. */
QWidget* focusOutside(const QWidget* panel)
{
    QWidget* const now = QApplication::focusWidget();
    return now && now != panel && !panel->isAncestorOf(now) ? now : nullptr;
}

/**
 * Gives the focus back to before as a panel folds away, if the panel holds it: a click elsewhere
 * meanwhile keeps its own focus.
 */
void giveFocusBack(const QWidget* panel, QWidget* before)
{
    QWidget* const now = QApplication::focusWidget();
    if (!now || (now != panel && !panel->isAncestorOf(now))) {
        return;
    }
    if (before && before->isVisible() && before->isEnabled() && before->focusPolicy() != Qt::NoFocus) {
        before->setFocus(Qt::OtherFocusReason);
    }
}

/** The height of a page laid out for width, measured afresh. */
int pageHeight(QWidget* page, int width)
{
    // Measured before the first show, the sections need their final fonts and margins, and the
    // nested layouts their sizes afresh: a hidden page never takes its layout requests in.
    page->ensurePolished();
    page->layout()->activate();
    return page->layout()->hasHeightForWidth() ? page->layout()->totalHeightForWidth(width)
                                               : page->sizeHint().height();
}

/** The shadow under a drop panel: it fades with the panel and lets the pointer through. */
class Shade : public QWidget
{
public:
    explicit Shade(QWidget* parent)
        : QWidget(parent)
    {
        setAttribute(Qt::WA_TransparentForMouseEvents);
        hide();
    }

    void setStrength(qreal strength_)
    {
        strength = strength_;
        update();
    }

protected:
    void paintEvent(QPaintEvent*) override
    {
        QPainter painter(this);
        painter.setOpacity(strength);
        QLinearGradient gradient(0, 0, 0, height());
        QColor dark = air().color(Color::Shadow);
        gradient.setColorAt(0, dark);
        dark.setAlpha(0);
        gradient.setColorAt(1, dark);
        painter.fillRect(rect(), gradient);
    }

private:
    qreal strength = 1;
};

/** Runs the unfolding of a panel from where it is to opening ? 1 : 0. */
QVariantAnimation* unfolding(QWidget* panel, qreal from, bool opening)
{
    auto* const animation = new QVariantAnimation(panel);
    animation->setStartValue(from);
    animation->setEndValue(opening ? 1.0 : 0.0);
    // The mask unfolds over the reveal duration; with less motion everything only fades.
    animation->setDuration(air().reducedMotion() ? air().duration(Motion::reducedMaxDuration)
                                                 : air().duration(Motion::reveal));
    animation->setEasingCurve(air().easeOut());
    return animation;
}

/** Shows progress of a panel: a mask from the top, or with less motion its opacity. */
int showProgress(QWidget* panel, qreal progress, bool keepMaskWhenOpen)
{
    if (air().reducedMotion()) {
        panel->clearMask();
        if (progress >= 1.0 && !keepMaskWhenOpen) {
            panel->setGraphicsEffect(nullptr);
        } else {
            auto* effect = qobject_cast<QGraphicsOpacityEffect*>(panel->graphicsEffect());
            if (!effect) {
                effect = new QGraphicsOpacityEffect(panel);
                panel->setGraphicsEffect(effect);
            }
            effect->setOpacity(progress);
        }
        return panel->height();
    }
    if (panel->graphicsEffect()) {
        panel->setGraphicsEffect(nullptr);
    }
    const int shown = qRound(panel->height() * progress);
    if (progress >= 1.0 && !keepMaskWhenOpen) {
        panel->clearMask();
    } else {
        panel->setMask(QRegion(0, 0, panel->width(), std::max(1, shown)));
    }
    return shown;
}
} // namespace

// ---- AirFoldPanel ----

AirFoldPanel::AirFoldPanel(QWidget* parent)
    : QWidget(parent)
{
    // It takes the focus when it opens, so Esc reaches it; Tab goes on to what it holds, as the
    // panel itself shows no focus.
    setFocusPolicy(Qt::ClickFocus);
    hide();

    panel = new QWidget(this);
    panel->setAutoFillBackground(true);
    auto* const panelLayout = new QVBoxLayout(panel);
    panelLayout->setContentsMargins(0, 0, 0, 0);
    scroller = new QScrollArea(panel);
    scroller->setFrameShape(QFrame::NoFrame);
    scroller->setWidgetResizable(true);
    scroller->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    // Tab stops on the controls of the page, which the scroller brings into view, not on itself.
    scroller->setFocusPolicy(Qt::NoFocus);
    panelLayout->addWidget(scroller);

    content = new QWidget(scroller);
    auto* const sections = new QVBoxLayout(content);
    sections->setContentsMargins(Space::s16, Space::s16, Space::s16, Space::s16);
    sections->setSpacing(Space::s16);
    scroller->setWidget(content);

    parent->installEventFilter(this);
    // The panel takes the height of its sections; a change inside them measures it again.
    content->installEventFilter(this);
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirFoldPanel::restyle);
    restyle();
}

QWidget* AirFoldPanel::page() const
{
    return content;
}

QVBoxLayout* AirFoldPanel::pageLayout() const
{
    return static_cast<QVBoxLayout*>(content->layout());
}

void AirFoldPanel::arrange(int width)
{
    std::ignore = width;
}

void AirFoldPanel::aboutToFold() {}

void AirFoldPanel::restyle()
{
    QPalette palette = panel->palette();
    palette.setColor(QPalette::Window, air().color(Color::Surface));
    panel->setPalette(palette);
    scroller->viewport()->setAutoFillBackground(false);
    content->setAutoFillBackground(false);
    update();
}

bool AirFoldPanel::isOpen() const
{
    return opened;
}

void AirFoldPanel::open()
{
    if (opened) {
        return;
    }
    opened = true;
    focusBefore = focusOutside(this);
    covered.cover(parentWidget(), this);
    place();
    show();
    raise();
    setFocus(Qt::OtherFocusReason);
    animate(true);
    emit openChanged(true);
}

void AirFoldPanel::close()
{
    if (!opened) {
        return;
    }
    opened = false;
    covered.uncover();
    giveFocusBack(this, focusBefore);
    focusBefore = nullptr;
    aboutToFold();
    animate(false);
    emit openChanged(false);
}

void AirFoldPanel::animate(bool opening)
{
    if (motion) {
        motion->stop();
    }
    QVariantAnimation* const animation = unfolding(this, progress, opening);
    connect(animation, &QVariantAnimation::valueChanged, this, [this](const QVariant& value) {
        progress = value.toReal();
        applyProgress();
    });
    connect(animation, &QVariantAnimation::finished, this, [this, opening] {
        progress = opening ? 1.0 : 0.0;
        applyProgress();
        if (!opening) {
            hide();
        }
    });
    motion = animation;
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}

void AirFoldPanel::applyProgress()
{
    // The panel of a dossier keeps its mask: its shadow is painted by the scrim around it.
    showProgress(panel, progress, true);
    update();
}

void AirFoldPanel::place()
{
    if (!parentWidget()) {
        return;
    }
    setGeometry(parentWidget()->rect());
    const int width = this->width();
    arrange(width);
    const int height = std::min(pageHeight(content, width), std::max(0, this->height() - keepInView));
    panel->setGeometry(0, 0, width, height);
    applyProgress();
}

void AirFoldPanel::scrollToEnd()
{
    QTimer::singleShot(0, this, [this] {
        QScrollBar* const bar = scroller->verticalScrollBar();
        bar->setValue(bar->maximum());
    });
}

void AirFoldPanel::resizeEvent(QResizeEvent* event)
{
    QWidget::resizeEvent(event);
    place();
}

bool AirFoldPanel::eventFilter(QObject* watched, QEvent* event)
{
    if (watched == parentWidget() && event->type() == QEvent::Resize && isVisible()) {
        place();
    } else if (watched == content && event->type() == QEvent::LayoutRequest && isVisible()) {
        // After the layout has taken the change in.
        QTimer::singleShot(0, this, [this] { place(); });
    }
    return QWidget::eventFilter(watched, event);
}

void AirFoldPanel::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    // The conversation under the dossier dims; it is not drawn again.
    painter.setOpacity(progress);
    painter.fillRect(rect(), air().color(Color::Scrim));

    // Under the panel's lower edge: the strong line that closes it, then the dialog shadow.
    const int shown = air().reducedMotion() ? panel->height() : qRound(panel->height() * progress);
    painter.fillRect(QRect(0, shown, width(), static_cast<int>(Stroke::hairline)),
                     air().color(Color::LineStrong));
    const int below = shown + static_cast<int>(Stroke::hairline);
    QLinearGradient shadow(0, below, 0, below + Elevation::dialog.y);
    QColor dark = air().color(Color::Shadow);
    shadow.setColorAt(0, dark);
    dark.setAlpha(0);
    shadow.setColorAt(1, dark);
    painter.fillRect(QRect(0, below, width(), Elevation::dialog.y), shadow);
}

void AirFoldPanel::mousePressEvent(QMouseEvent* event)
{
    // A click on the dimmed conversation closes the dossier.
    if (!panel->geometry().contains(event->pos())) {
        close();
        return;
    }
    QWidget::mousePressEvent(event);
}

void AirFoldPanel::keyPressEvent(QKeyEvent* event)
{
    if (event->key() == Qt::Key_Escape) {
        close();
        return;
    }
    QWidget::keyPressEvent(event);
}

// ---- AirDropPanel ----

AirDropPanel::AirDropPanel(QWidget* column_, QWidget* parent)
    : QWidget(parent)
    , column{column_}
{
    hide();

    auto* const outer = new QVBoxLayout(this);
    outer->setContentsMargins(0, 0, 0, 0);
    outer->setSpacing(0);

    // The head: the title and the close button.
    auto* const head = new QHBoxLayout();
    head->setContentsMargins(Space::s16, Space::s12, Space::s8, Space::s4);
    head->setSpacing(Space::s8);
    titleLabel = new QLabel(this);
    titleLabel->setTextFormat(Qt::PlainText);
    titleLabel->setWordWrap(false);
    closeButton = new AirIconButton(QStringLiteral("x"), this);
    head->addWidget(titleLabel, 1, Qt::AlignVCenter);
    head->addWidget(closeButton, 0, Qt::AlignVCenter);
    outer->addLayout(head);

    scroller = new QScrollArea(this);
    scroller->setFrameShape(QFrame::NoFrame);
    scroller->setWidgetResizable(true);
    scroller->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    // Tab stops on the fields of the form, which the scroller brings into view, not on itself.
    scroller->setFocusPolicy(Qt::NoFocus);
    outer->addWidget(scroller);

    content = new QWidget(scroller);
    auto* const form = new QVBoxLayout(content);
    form->setContentsMargins(Space::s16, Space::s4, Space::s16, Space::s20);
    form->setSpacing(0);
    scroller->setWidget(content);

    shade = new Shade(parent);

    connect(closeButton, &AirIconButton::clicked, this, &AirDropPanel::close);
    parent->installEventFilter(this);
    if (column) {
        column->installEventFilter(this);
    }
    // The panel takes the height of what it shows; a change inside measures it again.
    content->installEventFilter(this);
    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirDropPanel::restyle);
    Translator::registerHandler(std::bind(&AirDropPanel::retranslate, this), this);
    restyle();
    retranslate();
}

AirDropPanel::~AirDropPanel()
{
    Translator::unregister(this);
    delete shade.data();
}

void AirDropPanel::setTitle(const QString& text)
{
    titleLabel->setText(text);
    titleLabel->setAlignment(
        QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter));
}

QLabel* AirDropPanel::title() const
{
    return titleLabel;
}

AirIconButton* AirDropPanel::closer() const
{
    return closeButton;
}

QWidget* AirDropPanel::page() const
{
    return content;
}

QVBoxLayout* AirDropPanel::pageLayout() const
{
    return static_cast<QVBoxLayout*>(content->layout());
}

void AirDropPanel::aboutToFold() {}

void AirDropPanel::retranslate()
{
    closeButton->setToolTip(AirStrings::text(Text::Close));
    closeButton->setAccessibleName(AirStrings::text(Text::Close));
}

void AirDropPanel::restyle()
{
    scroller->viewport()->setAutoFillBackground(false);
    content->setAutoFillBackground(false);
    // The legacy sheet of the window paints scroll areas; the panel paints its own ground.
    scroller->setStyleSheet(QStringLiteral("QScrollArea { background: transparent; }"));
    titleLabel->setFont(air().font(TextRole::Heading));
    AirPaint::textColor(titleLabel, air().color(Color::Ink));
    if (shade) {
        shade->update();
    }
    update();
}

bool AirDropPanel::isOpen() const
{
    return opened;
}

void AirDropPanel::unfold()
{
    if (opened) {
        return;
    }
    opened = true;
    focusBefore = focusOutside(this);
    covered.cover(column, this);
    place();
    if (shade) {
        shade->show();
        shade->raise();
    }
    show();
    raise();
    animate(true);
    emit openChanged(true);
}

void AirDropPanel::close()
{
    if (!opened) {
        return;
    }
    opened = false;
    covered.uncover();
    giveFocusBack(this, focusBefore);
    focusBefore = nullptr;
    aboutToFold();
    animate(false);
    emit openChanged(false);
}

void AirDropPanel::place()
{
    QWidget* const host = parentWidget();
    if (!host || !column) {
        return;
    }
    const QPoint origin = column->mapTo(host, QPoint(0, 0));
    const int width = column->width();
    const int available = std::max(0, host->height() - origin.y());
    const int headHeight = layout()->itemAt(0)->sizeHint().height();
    const int height = std::min(headHeight + pageHeight(content, width), available);
    setGeometry(origin.x(), origin.y(), width, height);
    applyProgress();
}

void AirDropPanel::animate(bool opening)
{
    if (motion) {
        motion->stop();
    }
    QVariantAnimation* const animation = unfolding(this, progress, opening);
    connect(animation, &QVariantAnimation::valueChanged, this, [this](const QVariant& value) {
        progress = value.toReal();
        applyProgress();
    });
    connect(animation, &QVariantAnimation::finished, this, [this, opening] {
        progress = opening ? 1.0 : 0.0;
        applyProgress();
        if (!opening) {
            hide();
            if (shade) {
                shade->hide();
            }
        }
    });
    motion = animation;
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}

void AirDropPanel::applyProgress()
{
    const int shown = showProgress(this, progress, false);
    // One read of the guarded pointer: GCC cannot tell that a second one finds it still there.
    if (auto* const ground = static_cast<Shade*>(shade.data())) {
        ground->setGeometry(x(), y() + shown, width(), Elevation::dialog.y);
        ground->setStrength(progress);
    }
}

void AirDropPanel::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    // The panel hangs from the band: straight along the top, round at its lower corners, closed
    // by the strong hairline there.
    const QRectF box = QRectF(rect()).adjusted(0, 0, 0, -Stroke::hairline / 2.0);
    const qreal r = Radius::sheet;
    QPainterPath shape;
    shape.moveTo(box.topLeft());
    shape.lineTo(box.topRight());
    shape.lineTo(box.right(), box.bottom() - r);
    shape.arcTo(QRectF(box.right() - 2 * r, box.bottom() - 2 * r, 2 * r, 2 * r), 0, -90);
    shape.lineTo(box.left() + r, box.bottom());
    shape.arcTo(QRectF(box.left(), box.bottom() - 2 * r, 2 * r, 2 * r), -90, -90);
    shape.closeSubpath();
    painter.fillPath(shape, air().color(Color::Raised));

    QPainterPath edge;
    edge.moveTo(box.right(), box.bottom() - r);
    edge.arcTo(QRectF(box.right() - 2 * r, box.bottom() - 2 * r, 2 * r, 2 * r), 0, -90);
    edge.lineTo(box.left() + r, box.bottom());
    edge.arcTo(QRectF(box.left(), box.bottom() - 2 * r, 2 * r, 2 * r), -90, -90);
    painter.strokePath(edge, QPen(air().color(Color::LineStrong), Stroke::hairline));
}

void AirDropPanel::keyPressEvent(QKeyEvent* event)
{
    if (event->key() == Qt::Key_Escape) {
        close();
        return;
    }
    QWidget::keyPressEvent(event);
}

bool AirDropPanel::eventFilter(QObject* watched, QEvent* event)
{
    const bool around = watched == parentWidget() || watched == column.data();
    if (around && isVisible() && (event->type() == QEvent::Resize || event->type() == QEvent::Move)) {
        place();
    } else if (watched == content && event->type() == QEvent::LayoutRequest && isVisible()) {
        // After the layout has taken the change in.
        QTimer::singleShot(0, this, [this] { place(); });
    }
    return QWidget::eventFilter(watched, event);
}

// ---- QR code ----

void showAirQr(QWidget* parent, const QString& title, const QString& data, const QString& subtitle)
{
    QDialog dialog(parent ? parent->window() : nullptr);
    dialog.setWindowTitle(title);
    QPalette palette = dialog.palette();
    palette.setColor(QPalette::Window, air().color(Color::Raised));
    dialog.setPalette(palette);
    auto* const layout = new QVBoxLayout(&dialog);
    layout->setContentsMargins(Space::s24, Space::s24, Space::s24, Space::s24);
    layout->setSpacing(Space::s12);

    QRWidget qr;
    qr.setQRData(data);
    auto* const image = new QLabel(&dialog);
    const qreal dpr = dialog.devicePixelRatioF();
    QPixmap code =
        QPixmap::fromImage(qr.getImage()->scaledToWidth(qRound(240 * dpr), Qt::SmoothTransformation));
    code.setDevicePixelRatio(dpr);
    image->setPixmap(code);
    image->setAlignment(Qt::AlignCenter);
    layout->addWidget(image);
    auto* const caption = new QLabel(&dialog);
    caption->setTextFormat(Qt::PlainText);
    caption->setWordWrap(true);
    caption->setFont(air().font(TextRole::Caption));
    AirPaint::textColor(caption, air().color(Color::Ink2));
    caption->setText(subtitle);
    caption->setMaximumWidth(AirTokens::Size::dialog - 2 * Space::s24);
    layout->addWidget(caption);
    dialog.exec();
}
