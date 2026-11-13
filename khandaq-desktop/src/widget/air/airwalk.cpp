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

#include "airwalk.h"

#ifdef KHANDAQ_SCREENSHOT_HOOK

#include <QAbstractButton>
#include <QAbstractScrollArea>
#include <QAccessible>
#include <QApplication>
#include <QDebug>
#include <QEvent>
#include <QImage>
#include <QKeyEvent>
#include <QLabel>
#include <QLayout>
#include <QMap>
#include <QPixmap>
#include <QPair>
#include <QPointer>
#include <QScrollArea>
#include <QTimer>
#include <QWidget>

#include <algorithm>
#include <functional>
#include <memory>

namespace {

/** Tab stops a walk follows at most: more than any screen has, so a loop shows as one. */
constexpr int maxTabStops = 150;
/**
 * Pixels round a control that count as its focus ring: a ring may sit just outside it, or round the
 * frame a field stands in, as far as the frame's padding.
 */
constexpr int ringRoom = 16;
/** Pixels a measure may be off by before it counts: rounding of a fractional scale. */
constexpr int slack = 1;

/** Class and object name, with the nearest named parent for a widget without a name of its own. */
QString describe(const QWidget* widget)
{
    const QString kind = QString::fromLatin1(widget->metaObject()->className());
    if (!widget->objectName().isEmpty()) {
        return kind + QLatin1Char('#') + widget->objectName();
    }
    for (const QWidget* up = widget->parentWidget(); up && !up->isWindow(); up = up->parentWidget()) {
        if (!up->objectName().isEmpty()) {
            return up->objectName() + QLatin1Char('>') + kind;
        }
    }
    return kind;
}

QString spokenName(QWidget* widget)
{
    QAccessibleInterface* const face = QAccessible::queryAccessibleInterface(widget);
    return face ? face->text(QAccessible::Name) : QString();
}

QString rectText(const QRect& rect)
{
    return QStringLiteral("%1,%2 %3x%4").arg(rect.x()).arg(rect.y()).arg(rect.width()).arg(rect.height());
}

QString sizeText(const QSize& size)
{
    return QStringLiteral("%1x%2").arg(size.width()).arg(size.height());
}

/** One line: newlines in names or tooltips would split it. */
QString oneLine(QString text)
{
    text.replace(QLatin1Char('\n'), QLatin1Char(' '));
    return text;
}

void say(const QString& label, const QString& what)
{
    qDebug().noquote() << QStringLiteral("Walk audit: %1 | %2").arg(label, oneLine(what));
}

QRect inWindow(const QWidget* widget, const QWidget* window)
{
    return QRect(widget->mapTo(window, QPoint(0, 0)), widget->size());
}

bool isIconButton(const QWidget* widget)
{
    const auto* const button = qobject_cast<const QAbstractButton*>(widget);
    return button && button->text().isEmpty()
           && (widget->inherits("AirIconButton") || !button->icon().isNull());
}

bool laidOutIn(const QLayout* layout, const QWidget* widget)
{
    for (int i = 0; i < layout->count(); ++i) {
        QLayoutItem* const item = layout->itemAt(i);
        if (item->widget() == widget || (item->layout() && laidOutIn(item->layout(), widget))) {
            return true;
        }
    }
    return false;
}

/** Content of a scroll area moves under its viewport: being outside it is how it scrolls. */
bool scrolls(const QWidget* parent)
{
    return parent->objectName() == QLatin1String("qt_scrollarea_viewport")
           || qobject_cast<const QAbstractScrollArea*>(parent) != nullptr;
}

/** What a label lacks to show all of its text, or nothing when it has the room. */
QString labelShortfall(const QLabel* label)
{
    if (label->text().isEmpty()) {
        return QString();
    }
    if (label->wordWrap()) {
        const int needed = label->heightForWidth(label->width());
        return needed > label->height() + slack
                   ? QStringLiteral("wraps to %1 px in %2").arg(needed).arg(label->height())
                   : QString();
    }
    const QSize needed = label->sizeHint();
    return needed.width() > label->width() + slack || needed.height() > label->height() + slack
               ? QStringLiteral("needs %1 in %2").arg(sizeText(needed), sizeText(label->size()))
               : QString();
}

/** Pixels that differ between two pictures of one place. */
int changedPixels(const QImage& one, const QImage& other)
{
    const QImage a = one.convertToFormat(QImage::Format_ARGB32);
    const QImage b = other.convertToFormat(QImage::Format_ARGB32);
    if (a.size() != b.size()) {
        return a.width() * a.height();
    }
    int changed = 0;
    for (int y = 0; y < a.height(); ++y) {
        const auto* const lineA = reinterpret_cast<const QRgb*>(a.constScanLine(y));
        const auto* const lineB = reinterpret_cast<const QRgb*>(b.constScanLine(y));
        for (int x = 0; x < a.width(); ++x) {
            if (lineA[x] != lineB[x]) {
                ++changed;
            }
        }
    }
    return changed;
}

/**
 * A scroll area that does not scroll sideways cuts off what is wider than its view: the content's
 * least width against the view's, with the widest parts that set it.
 */
void cutContent(const QScrollArea* area, const QString& label)
{
    const QWidget* const content = area->widget();
    if (!content || area->horizontalScrollBarPolicy() != Qt::ScrollBarAlwaysOff) {
        return;
    }
    const int least = content->minimumSizeHint().width();
    const int view = area->viewport()->width();
    if (least <= view + slack) {
        return;
    }
    QList<QPair<int, QString>> parts;
    for (const QWidget* const part : content->findChildren<QWidget*>()) {
        if (part->isVisibleTo(content) && part->findChildren<QWidget*>().isEmpty()) {
            const int width = std::max(part->minimumSizeHint().width(), part->minimumWidth());
            parts.append(qMakePair(width, describe(part)));
        }
    }
    std::sort(parts.begin(), parts.end(),
              [](const QPair<int, QString>& a, const QPair<int, QString>& b) {
                  return a.first > b.first;
              });
    QStringList widest;
    for (int i = 0; i < parts.size() && i < 3; ++i) {
        widest.append(QStringLiteral("%1 %2").arg(parts[i].second).arg(parts[i].first));
    }
    say(label, QStringLiteral("issue cut-content | %1 | least %2 in a view of %3 | %4")
                   .arg(describe(area))
                   .arg(least)
                   .arg(view)
                   .arg(widest.join(QStringLiteral(", "))));
}

void auditLayout(QWidget* window, const QString& label)
{
    const QList<QWidget*> widgets = window->findChildren<QWidget*>();
    for (QWidget* const widget : widgets) {
        if (widget->isWindow() || !widget->isVisibleTo(window)) {
            continue;
        }
        const QString who = describe(widget);
        if (isIconButton(widget)) {
            if (spokenName(widget).isEmpty()) {
                say(label, QStringLiteral("issue icon-unnamed | %1").arg(who));
            }
            if (widget->toolTip().isEmpty()) {
                say(label,
                    QStringLiteral("issue icon-no-tip | %1 | name=%2").arg(who, spokenName(widget)));
            }
        } else if (const auto* const text = qobject_cast<const QLabel*>(widget)) {
            const QString shortfall = labelShortfall(text);
            if (!shortfall.isEmpty()) {
                say(label, QStringLiteral("issue clipped-text | %1 | %2 | %3")
                               .arg(who, shortfall, text->text().left(80)));
            }
        } else if (const auto* const button = qobject_cast<const QAbstractButton*>(widget)) {
            QSize needed = button->sizeHint();
            if (button->hasHeightForWidth()) {
                // Its words wrap: what counts is the height they take at the width it has.
                needed = QSize(button->width(), button->heightForWidth(button->width()));
            }
            if (needed.width() > button->width() + slack || needed.height() > button->height() + slack) {
                say(label, QStringLiteral("issue clipped-button | %1 | needs %2 in %3 | %4")
                               .arg(who, sizeText(needed), sizeText(button->size()), button->text()));
            }
        } else if (const auto* const area = qobject_cast<const QScrollArea*>(widget)) {
            cutContent(area, label);
        } else if (!qobject_cast<const QAbstractScrollArea*>(widget)) {
            const QSize least = widget->minimumSizeHint();
            const QSizePolicy policy = widget->sizePolicy();
            const bool narrow = least.width() > widget->width() + slack
                                && policy.horizontalPolicy() != QSizePolicy::Ignored;
            const bool low = least.height() > widget->height() + slack
                             && policy.verticalPolicy() != QSizePolicy::Ignored;
            if (narrow || low) {
                say(label, QStringLiteral("issue squeezed | %1 | least %2 in %3")
                               .arg(who, sizeText(least), sizeText(widget->size())));
            }
        }
        QWidget* const parent = widget->parentWidget();
        if (parent && !scrolls(parent)
            && !parent->rect().adjusted(-slack, -slack, slack, slack).contains(widget->geometry())) {
            say(label, QStringLiteral("issue outside | %1 | at %2 in %3 %4")
                           .arg(who, rectText(widget->geometry()), describe(parent),
                                sizeText(parent->size())));
        }
        const QLayout* const layout = widget->layout();
        if (!layout) {
            continue;
        }
        QList<QWidget*> laid;
        for (QWidget* const child :
             widget->findChildren<QWidget*>(QString(), Qt::FindDirectChildrenOnly)) {
            if (!child->isWindow() && child->isVisibleTo(window) && laidOutIn(layout, child)) {
                laid.append(child);
            }
        }
        for (int i = 0; i < laid.size(); ++i) {
            for (int j = i + 1; j < laid.size(); ++j) {
                const QRect both = laid[i]->geometry().intersected(laid[j]->geometry());
                if (both.width() > slack && both.height() > slack) {
                    say(label, QStringLiteral("issue overlap | %1 | %2 | %3")
                                   .arg(describe(laid[i]), describe(laid[j]), rectText(both)));
                }
            }
        }
    }
}

void auditTabs(QWidget* window, const QString& label)
{
    const QPointer<QWidget> start = QApplication::focusWidget();
    const bool keyboardBefore = window->testAttribute(Qt::WA_KeyboardFocusChange);
    QList<QWidget*> reached;
    for (int stop = 1; stop <= maxTabStops; ++stop) {
        QWidget* const from = QApplication::focusWidget();
        QWidget* const target = from && from->window() == window ? from : window;
        QKeyEvent press(QEvent::KeyPress, Qt::Key_Tab, Qt::NoModifier);
        QApplication::sendEvent(target, &press);
        QKeyEvent release(QEvent::KeyRelease, Qt::Key_Tab, Qt::NoModifier);
        QApplication::sendEvent(QApplication::focusWidget() ? QApplication::focusWidget() : window,
                                &release);
        QWidget* const now = QApplication::focusWidget();
        if (!now || now->window() != window) {
            say(label, QStringLiteral("tab %1 | the focus left the window").arg(stop));
            break;
        }
        if (now == from) {
            say(label, QStringLiteral("tab %1 | stays on %2").arg(stop).arg(describe(now)));
            break;
        }
        if (reached.contains(now)) {
            say(label, QStringLiteral("tab %1 | back to %2").arg(stop).arg(describe(now)));
            break;
        }
        reached.append(now);
        const QRect area = inWindow(now, window).adjusted(-ringRoom, -ringRoom, ringRoom, ringRoom)
                           & window->rect();
        window->setAttribute(Qt::WA_KeyboardFocusChange);
        const QImage focused = window->grab(area).toImage();
        now->clearFocus();
        const QImage plain = window->grab(area).toImage();
        now->setFocus(Qt::TabFocusReason);
        say(label,
            QStringLiteral("tab %1 | %2 | name=%3 | tip=%4 | at %5 | ring %6")
                .arg(stop)
                .arg(describe(now), spokenName(now), now->toolTip(), rectText(inWindow(now, window)))
                .arg(changedPixels(focused, plain)));
    }
    window->setAttribute(Qt::WA_KeyboardFocusChange, keyboardBefore);
    if (start) {
        start->setFocus(Qt::OtherFocusReason);
    } else if (QWidget* const left = QApplication::focusWidget()) {
        left->clearFocus();
    }
}

/** Counts the paint events of one window's widgets while it is installed. */
class PaintCounter : public QObject
{
public:
    explicit PaintCounter(QWidget* window_)
        : window{window_}
    {
    }

    bool eventFilter(QObject* watched, QEvent* event) override
    {
        if (event->type() == QEvent::Paint && watched->isWidgetType()) {
            const auto* const widget = static_cast<const QWidget*>(watched);
            if (window && widget->window() == window) {
                ++counts[describe(widget)];
            }
        }
        return false;
    }

    QPointer<QWidget> window;
    QMap<QString, int> counts;
};

} // namespace

namespace AirWalk {

void frames(QWidget* window, int count, int ms, const QString& prefix)
{
    auto taken = std::make_shared<int>(0);
    auto* const clock = new QTimer(window);
    clock->setInterval(ms);
    QObject::connect(clock, &QTimer::timeout, window, [window, clock, taken, count, prefix] {
        ++*taken;
        const QString frame = prefix + QStringLiteral("-%1.png").arg(*taken, 2, 10, QLatin1Char('0'));
        qDebug() << "Walk frame:" << frame;
        if (!window->grab().save(frame)) {
            qWarning() << "Walk frame not saved:" << frame;
        }
        if (*taken >= count || !window->isVisible()) {
            clock->stop();
            clock->deleteLater();
        }
    });
    clock->start();
}

void audit(QWidget* window, const QString& label)
{
    say(label, QStringLiteral("window %1 | least %2 | %3")
                   .arg(sizeText(window->size()), sizeText(window->minimumSize()),
                        window->layoutDirection() == Qt::RightToLeft ? QStringLiteral("rtl")
                                                                     : QStringLiteral("ltr")));
    auditLayout(window, label);
    auditTabs(window, label);
    say(label, QStringLiteral("done"));
}

void idle(QWidget* window, int ms, const QString& label)
{
    auto* const counter = new PaintCounter(window);
    qApp->installEventFilter(counter);
    QTimer::singleShot(ms, counter, [counter, label, ms] {
        qApp->removeEventFilter(counter);
        int total = 0;
        QStringList painted;
        for (auto it = counter->counts.constBegin(); it != counter->counts.constEnd(); ++it) {
            total += it.value();
            painted.append(QStringLiteral("%1 %2").arg(it.key()).arg(it.value()));
        }
        qDebug().noquote() << QStringLiteral("Walk idle: %1 | %2 paints in %3 ms | %4")
                                  .arg(label)
                                  .arg(total)
                                  .arg(ms)
                                  .arg(painted.join(QStringLiteral(", ")));
        counter->deleteLater();
    });
}

void least(QWidget* root, int depth, const QString& label)
{
    if (!root) {
        return;
    }
    std::function<void(QWidget*, int)> walk = [&](QWidget* widget, int level) {
        const QSizePolicy policy = widget->sizePolicy();
        qDebug().noquote() << QStringLiteral(
                                  "Walk least: %1 | %2%3 | least %4 | size %5 | policy %6,%7%8")
                                  .arg(label, QString(level * 2, QLatin1Char(' ')), describe(widget),
                                       sizeText(widget->minimumSizeHint()), sizeText(widget->size()))
                                  .arg(policy.horizontalPolicy())
                                  .arg(policy.verticalPolicy())
                                  .arg(widget->minimumSize().isNull()
                                           ? QString()
                                           : QStringLiteral(" | set least ")
                                                 + sizeText(widget->minimumSize()));
        if (level >= depth) {
            return;
        }
        for (QWidget* const child :
             widget->findChildren<QWidget*>(QString(), Qt::FindDirectChildrenOnly)) {
            if (!child->isWindow() && child->isVisibleTo(root)) {
                walk(child, level + 1);
            }
        }
    };
    walk(root, 0);
}

} // namespace AirWalk

#endif
