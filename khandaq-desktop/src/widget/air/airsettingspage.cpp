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

#include "airsettingspage.h"

#include "src/core/core.h"
#include "src/model/air/airpreferences.h"
#include "src/model/profile/iprofileinfo.h"
#if UPDATE_CHECK_ENABLED
#include "src/net/updatecheck.h"
#endif
#include "src/persistence/history.h"
#include "src/persistence/profile.h"
#include "src/persistence/profilefile.h"
#include "src/persistence/settings.h"
#include "src/persistence/smileypack.h"
#if !DESKTOP_NOTIFICATIONS
#include "src/platform/traynotify.h"
#endif
#include "src/widget/air/airchatparts.h"
#include "src/widget/air/airlocal.h"
#include "src/widget/air/airnewcontact.h"
#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airsettings.h"
#include "src/widget/air/airsource.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/air/airyou.h"
#include "src/widget/contentlayout.h"
#include "src/widget/style.h"
#include "src/widget/translator.h"

#include <tox/tox.h>

#include <QAbstractButton>
#include <QActionGroup>
#include <QApplication>
#include <QBoxLayout>
#include <QClipboard>
#include <QDesktopServices>
#include <QDir>
#include <QFile>
#include <QFileDialog>
#include <QFileInfo>
#include <QFontDialog>
#include <QFontInfo>
#include <QGraphicsOpacityEffect>
#include <QKeyEvent>
#include <QLabel>
#include <QMenu>
#include <QPainter>
#include <QPointer>
#include <QSaveFile>
#include <QScrollArea>
#include <QScrollBar>
#include <QSpinBox>
#include <QStackedWidget>
#include <QStandardPaths>
#include <QStyle>
#include <QSysInfo>
#include <QTextEdit>
#include <QTextStream>
#include <QUrl>
#include <QVariantAnimation>
#include <QWindow>
#include <QtMath>

#include <algorithm>
#include <tuple>

using AirStrings::Text;
using AirTokens::Color;
using AirTokens::TextRole;
namespace Radius = AirTokens::Radius;
namespace Size = AirTokens::Size;
namespace Space = AirTokens::Space;
namespace Stroke = AirTokens::Stroke;

namespace {
const AirStyle& air()
{
    return AirStyle::instance();
}

const char roleProperty[] = "airRole";
const char colorProperty[] = "airColor";

/** The smallest and largest smiley the size field takes, in pixels. */
constexpr int smileySizeMin = 8;
constexpr int smileySizeMax = 128;
/** The longest wait before Away, in minutes: two days. */
constexpr int awayMinutesMax = 2 * 24 * 60;

std::function<QString()> words(Text text)
{
    return [text] { return AirStrings::text(text); };
}

/** The names of the sections, in the order of AirSettingsPage::Section. */
const char* const sectionNames[] = {"appearance", "notifications", "privacy", "calls",
                                    "transfer",   "network",       "window",  "about"};
constexpr int sectionCount = static_cast<int>(sizeof(sectionNames) / sizeof(sectionNames[0]));

/** The file the app writes its log to, as the old Advanced tab read it. */
QString logPath(Settings& settings)
{
    return settings.getPaths().getAppCacheDirPath() + QStringLiteral("khandaq.log");
}

/** The words of "%1 MB" round its number: what stands before it and what after it. */
QPair<QString, QString> aroundNumber(const QString& pattern)
{
    const int at = pattern.indexOf(QStringLiteral("%1"));
    if (at < 0) {
        return {QString(), QLatin1Char(' ') + pattern};
    }
    return {pattern.left(at), pattern.mid(at + 2)};
}

/**
 * The width text takes in font, rounded up: a width rounded down leaves the text a fraction of a
 * pixel short of its room, and the last letters go under an ellipsis.
 */
int textWidth(const QFont& font, const QString& text)
{
    return qCeil(QFontMetricsF(font).horizontalAdvance(text));
}

/**
 * Makes menu a list of choices of which one is taken: the taken one is marked in the way of the
 * style (a check or a radio dot), and a list longer than the screen scrolls in every style.
 */
QActionGroup* choiceGroup(QMenu& menu)
{
    AirPaint::scrollWhenLong(menu);
    auto* const group = new QActionGroup(&menu);
    group->setExclusive(true);
    return group;
}

/** Whether system notifications can show here: through snorenotify, or the tray icon carries them. */
bool trayCarriesNotifications(const Settings& settings)
{
#if DESKTOP_NOTIFICATIONS
    std::ignore = settings;
    return true;
#else
    return settings.getShowSystemTray() && TrayNotify::systemCarries();
#endif
}

/**
 * Puts its items side by side while they fit in the width, and one under another when they do not,
 * so no row of a section runs past the edge of a narrow pane. Side by side, the words of a row take
 * the room its control leaves; one under another, the words take the whole width and every other
 * item keeps to its own width at the start edge.
 */
class FitLayout : public QLayout
{
public:
    enum class Kind
    {
        /** The words of a row first, its control at the end. */
        Row,
        /** Buttons one after another from the start edge. */
        Buttons,
    };

    FitLayout(Kind kind_, int across_, int down_, QWidget* parent = nullptr)
        : QLayout(parent)
        , kind{kind_}
        , across{across_}
        , down{down_}
    {
        setContentsMargins(0, 0, 0, 0);
    }

    ~FitLayout() override
    {
        while (QLayoutItem* const item = takeAt(0)) {
            delete item;
        }
    }

    FitLayout(const FitLayout&) = delete;
    FitLayout& operator=(const FitLayout&) = delete;

    /** The words of a row: a layout of labels, which wrap when the room is short. */
    void addWords(QLayout* words)
    {
        addChildLayout(words);
        addItem(words);
    }

    void addItem(QLayoutItem* item) override
    {
        items.append(item);
        invalidate();
    }

    int count() const override
    {
        return items.size();
    }

    QLayoutItem* itemAt(int index) const override
    {
        return items.value(index);
    }

    QLayoutItem* takeAt(int index) override
    {
        if (index < 0 || index >= items.size()) {
            return nullptr;
        }
        QLayoutItem* const item = items.takeAt(index);
        invalidate();
        return item;
    }

    Qt::Orientations expandingDirections() const override
    {
        return kind == Kind::Row ? Qt::Horizontal : Qt::Orientations();
    }

    bool hasHeightForWidth() const override
    {
        return true;
    }

    int heightForWidth(int width) const override
    {
        return arrange(QRect(0, 0, width, 0), false);
    }

    /** Side by side. */
    QSize sizeHint() const override
    {
        const QList<QLayoutItem*> shown = shownItems();
        int width = across * std::max(0, shown.size() - 1);
        int height = 0;
        for (QLayoutItem* const item : shown) {
            const QSize hint = item->sizeHint();
            width += hint.width();
            height = std::max(height, hint.height());
        }
        return withMargins(QSize(width, height));
    }

    /** One under another: as wide as the widest item at its least. */
    QSize minimumSize() const override
    {
        int width = 0;
        int height = 0;
        for (QLayoutItem* const item : shownItems()) {
            const QSize least = item->minimumSize();
            width = std::max(width, least.width());
            height = std::max(height, least.height());
        }
        return withMargins(QSize(width, height));
    }

    void setGeometry(const QRect& rect) override
    {
        QLayout::setGeometry(rect);
        arrange(rect, true);
    }

private:
    QList<QLayoutItem*> shownItems() const
    {
        QList<QLayoutItem*> shown;
        for (QLayoutItem* const item : items) {
            if (!item->isEmpty()) {
                shown.append(item);
            }
        }
        return shown;
    }

    QSize withMargins(const QSize& size) const
    {
        const QMargins margins = contentsMargins();
        return size + QSize(margins.left() + margins.right(), margins.top() + margins.bottom());
    }

    static int heightAt(QLayoutItem* item, int width)
    {
        return item->hasHeightForWidth() ? item->heightForWidth(width) : item->sizeHint().height();
    }

    /** Places the items in rect when apply is set; returns the height they take there. */
    int arrange(const QRect& rect, bool apply) const
    {
        const QList<QLayoutItem*> shown = shownItems();
        if (shown.isEmpty()) {
            return 0;
        }
        const QMargins margins = contentsMargins();
        const QRect area = rect.marginsRemoved(margins);
        const QWidget* const owner = parentWidget();
        const Qt::LayoutDirection direction =
            owner ? owner->layoutDirection() : QApplication::layoutDirection();
        const auto place = [apply, direction, &area](QLayoutItem* item, const QRect& box) {
            if (apply) {
                item->setGeometry(QStyle::visualRect(direction, area, box));
            }
        };
        const bool row = kind == Kind::Row;
        const int count = shown.size();
        std::vector<int> widths;
        int needed = across * (count - 1);
        int others = 0;
        for (int index = 0; index < count; ++index) {
            const int width = shown[index]->sizeHint().width();
            widths.push_back(width);
            const bool words = row && index == 0;
            needed += words ? shown[index]->minimumSize().width() : width;
            others += words ? 0 : width + across;
        }
        int height = 0;
        if (needed <= area.width()) {
            if (row) {
                widths[0] = area.width() - others;
            }
            std::vector<int> heights;
            for (int index = 0; index < count; ++index) {
                heights.push_back(heightAt(shown[index], widths[index]));
                height = std::max(height, heights.back());
            }
            int x = area.left();
            for (int index = 0; index < count; ++index) {
                place(shown[index], QRect(x, area.top() + (height - heights[index]) / 2,
                                          widths[index], heights[index]));
                x += widths[index] + across;
            }
        } else {
            int y = area.top();
            for (int index = 0; index < count; ++index) {
                QLayoutItem* const item = shown[index];
                const int width = row && index == 0
                                      ? area.width()
                                      : std::max(item->minimumSize().width(),
                                                 std::min(item->sizeHint().width(), area.width()));
                const int itemHeight = heightAt(item, width);
                place(item, QRect(area.left(), y, width, itemHeight));
                y += itemHeight + down;
            }
            height = y - down - area.top();
        }
        return height + margins.top() + margins.bottom();
    }

    QList<QLayoutItem*> items;
    Kind kind;
    int across;
    int down;
};

/** A section in the column on the start side: the chosen one stands on the selected ground. */
class NavButton : public QAbstractButton
{
public:
    explicit NavButton(QWidget* parent)
        : QAbstractButton(parent)
    {
        setCheckable(true);
        setAutoExclusive(true);
        setCursor(Qt::PointingHandCursor);
        setFocusPolicy(Qt::StrongFocus);
        setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
        setAttribute(Qt::WA_Hover);
        AirPaint::followTheme(this);
    }

    QSize sizeHint() const override
    {
        QFont font = air().font(TextRole::Label);
        font.setWeight(QFont::DemiBold);
        return QSize(textWidth(font, text()) + 2 * Space::s12, Size::control);
    }

protected:
    void paintEvent(QPaintEvent*) override
    {
        QPainter painter(this);
        painter.setRenderHint(QPainter::Antialiasing);
        const QRectF box(rect());
        if (isChecked() || underMouse()) {
            painter.setPen(Qt::NoPen);
            painter.setBrush(air().color(isChecked() ? Color::Selected : Color::Sunken));
            painter.drawRoundedRect(box, Radius::control, Radius::control);
        }
        QFont font = air().font(TextRole::Label);
        if (isChecked()) {
            font.setWeight(QFont::DemiBold);
        }
        painter.setFont(font);
        painter.setPen(air().color(isChecked() ? Color::Ink : Color::Ink2));
        const QRect room = rect().adjusted(Space::s12, 0, -Space::s12, 0);
        const int flags = static_cast<int>(
            QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter));
        painter.drawText(room, flags,
                         painter.fontMetrics().elidedText(text(), Qt::ElideRight, room.width()));
        if (AirPaint::keyboardFocus(this)) {
            AirPaint::focusRing(painter, box, Radius::control);
        }
    }
};

/**
 * kq-seg: the choices on a sunken track, the chosen one raised and drawn round by a hairline. When
 * they do not fit on one line (a long translation) they stand one under another at full width.
 */
class Segment : public QWidget
{
public:
    explicit Segment(QWidget* parent)
        : QWidget(parent)
    {
        track = new QBoxLayout(QBoxLayout::LeftToRight, this);
        track->setContentsMargins(Space::s2, Space::s2, Space::s2, Space::s2);
        track->setSpacing(0);
        setSizePolicy(QSizePolicy::Maximum, QSizePolicy::Fixed);
        AirPaint::followTheme(this);
    }

    void setChoices(int count, const std::function<void(int)>& chosen)
    {
        for (int index = 0; index < count; ++index) {
            auto* const choice = new Choice(this);
            choice->setObjectName(QStringLiteral("choice%1").arg(index));
            connect(choice, &QAbstractButton::clicked, this, [this, index, chosen] {
                setCurrent(index);
                chosen(index);
            });
            track->addWidget(choice);
            choices.append(choice);
        }
    }

    void setTexts(const QStringList& texts)
    {
        for (int index = 0; index < choices.size() && index < texts.size(); ++index) {
            choices[index]->setText(texts[index]);
            choices[index]->updateGeometry();
        }
        restack();
    }

    void setCurrent(int index)
    {
        for (int at = 0; at < choices.size(); ++at) {
            choices[at]->setChecked(at == index);
        }
    }

    /** The width the choices take side by side. */
    int lineWidth() const
    {
        int width = 2 * Space::s2;
        for (const Choice* const choice : choices) {
            width += choice->sizeHint().width();
        }
        return width;
    }

    /** Puts the choices side by side when room takes them, else one under another. */
    void fitInto(int room)
    {
        const bool stack = room > 0 && lineWidth() > room;
        if (stack == stacked) {
            return;
        }
        stacked = stack;
        restack();
    }

protected:
    void paintEvent(QPaintEvent*) override
    {
        QPainter painter(this);
        painter.setRenderHint(QPainter::Antialiasing);
        painter.setPen(Qt::NoPen);
        painter.setBrush(air().color(Color::Sunken));
        painter.drawRoundedRect(QRectF(rect()), Radius::field, Radius::field);
    }

private:
    class Choice : public QAbstractButton
    {
    public:
        explicit Choice(QWidget* parent)
            : QAbstractButton(parent)
        {
            setCheckable(true);
            setCursor(Qt::PointingHandCursor);
            setFocusPolicy(Qt::StrongFocus);
            setAttribute(Qt::WA_Hover);
        }

        QSize sizeHint() const override
        {
            return QSize(textWidth(air().font(TextRole::Label), text()) + 2 * Space::s12,
                         Size::control - 2 * Space::s2);
        }

    protected:
        void paintEvent(QPaintEvent*) override
        {
            QPainter painter(this);
            painter.setRenderHint(QPainter::Antialiasing);
            const qreal radius = Radius::field - Space::s2;
            const QRectF box = QRectF(rect()).adjusted(0.5, 0.5, -0.5, -0.5);
            if (isChecked()) {
                AirPaint::box(painter, box, radius, air().color(Color::Raised),
                              air().color(Color::LineStrong));
            }
            painter.setFont(air().font(TextRole::Label));
            painter.setPen(air().color(isChecked() || underMouse() ? Color::Ink : Color::Ink2));
            painter.drawText(rect(), Qt::AlignCenter, text());
            if (AirPaint::keyboardFocus(this)) {
                AirPaint::focusRing(painter, box, radius);
            }
        }

        void nextCheckState() override
        {
            // A chosen option stays chosen: another choice moves it.
            setChecked(true);
        }
    };

    void restack()
    {
        track->setDirection(stacked ? QBoxLayout::TopToBottom : QBoxLayout::LeftToRight);
        setSizePolicy(stacked ? QSizePolicy::Expanding : QSizePolicy::Maximum, QSizePolicy::Fixed);
        for (Choice* const choice : choices) {
            choice->setSizePolicy(stacked ? QSizePolicy::Expanding : QSizePolicy::Preferred,
                                  QSizePolicy::Fixed);
        }
        // At full width the segment takes the row; side by side it keeps to its start edge.
        if (QWidget* const holder = parentWidget()) {
            if (QLayout* const layout = holder->layout()) {
                layout->setAlignment(this, stacked ? Qt::Alignment() : Qt::AlignLeading);
            }
        }
        updateGeometry();
    }

    QBoxLayout* track = nullptr;
    QVector<Choice*> choices;
    bool stacked = false;
};

/** The row that holds a segment: it tells the segment how much room it has. */
class SegmentLine : public QWidget
{
public:
    explicit SegmentLine(QWidget* parent)
        : QWidget(parent)
    {
    }

    Segment* segment = nullptr;

protected:
    void resizeEvent(QResizeEvent* event) override
    {
        if (segment) {
            segment->fitInto(width());
        }
        QWidget::resizeEvent(event);
    }
};

/** kq-input round a number field: ink round it while it has the focus. */
class InputBox : public QWidget
{
public:
    InputBox(QSpinBox* inner_, QWidget* parent)
        : QWidget(parent)
        , inner{inner_}
    {
        auto* const box = new QHBoxLayout(this);
        box->setContentsMargins(Space::s12, 0, Space::s12, 0);
        inner->setParent(this);
        box->addWidget(inner);
        inner->installEventFilter(this);
        setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
        setAttribute(Qt::WA_Hover);
        setFocusProxy(inner);
        connect(&AirStyle::instance(), &AirStyle::changed, this, [this] { restyle(); });
        restyle();
    }

    QSize sizeHint() const override
    {
        return QSize(inner->sizeHint().width() + 2 * Space::s12, Size::field);
    }

protected:
    void paintEvent(QPaintEvent*) override
    {
        QPainter painter(this);
        painter.setRenderHint(QPainter::Antialiasing);
        QColor border = air().color(Color::LineStrong);
        if (inner->hasFocus()) {
            border = air().color(Color::Ink);
        } else if (underMouse() && isEnabled()) {
            border = air().color(Color::Ink3);
        }
        const QRectF box = QRectF(rect()).adjusted(0.5, 0.5, -0.5, -0.5);
        AirPaint::box(painter, box, Radius::field,
                      air().color(isEnabled() ? Color::Surface : Color::Sunken), border);
    }

    bool eventFilter(QObject* watched, QEvent* event) override
    {
        if (watched == inner
            && (event->type() == QEvent::FocusIn || event->type() == QEvent::FocusOut)) {
            update();
        }
        return QWidget::eventFilter(watched, event);
    }

    void changeEvent(QEvent* event) override
    {
        if (event->type() == QEvent::EnabledChange) {
            restyle();
        }
        QWidget::changeEvent(event);
    }

private:
    void restyle()
    {
        inner->setFont(air().font(TextRole::Body));
        const QColor ink = air().color(isEnabled() ? Color::Ink : Color::Ink3);
        // The window's legacy style sheet frames every spin box; this sheet wins over it.
        inner->setStyleSheet(
            QStringLiteral("QSpinBox { background: transparent; border: none;"
                           " padding: 0; margin: 0; color: %1;"
                           " selection-background-color: %2; selection-color: %3; }")
                .arg(ink.name(QColor::HexArgb), air().color(Color::Selected).name(QColor::HexArgb),
                     air().color(Color::Ink).name(QColor::HexArgb)));
        updateGeometry();
        update();
    }

    QSpinBox* inner;
};

/** A choice that opens a menu: its value in a field, with the chevron at the end. */
class MenuButton : public QAbstractButton
{
public:
    explicit MenuButton(QWidget* parent)
        : QAbstractButton(parent)
    {
        setCursor(Qt::PointingHandCursor);
        setFocusPolicy(Qt::StrongFocus);
        setAttribute(Qt::WA_Hover);
        // In a narrow pane the value is cut short, but the field keeps its chevron and a few letters.
        setSizePolicy(QSizePolicy::Maximum, QSizePolicy::Fixed);
        AirPaint::followTheme(this);
    }

    QSize sizeHint() const override
    {
        return QSize(std::min(textWidth(air().font(TextRole::Body), text()) + chrome(),
                              Size::listColumn - Space::s64),
                     Size::field);
    }

    QSize minimumSizeHint() const override
    {
        return QSize(chrome() + Space::s32, Size::field);
    }

protected:
    void paintEvent(QPaintEvent*) override
    {
        QPainter painter(this);
        painter.setRenderHint(QPainter::Antialiasing);
        const QRectF box = QRectF(rect()).adjusted(0.5, 0.5, -0.5, -0.5);
        const Color border =
            isEnabled() && (isDown() || underMouse()) ? Color::Ink3 : Color::LineStrong;
        AirPaint::box(painter, box, Radius::field,
                      air().color(isEnabled() ? Color::Surface : Color::Sunken), air().color(border));
        const bool rtl = layoutDirection() == Qt::RightToLeft;
        const int chevron = Size::iconSmall;
        const int reserve = Space::s8 + chevron;
        const QRect room = rtl ? rect().adjusted(Space::s12 + reserve, 0, -Space::s12, 0)
                               : rect().adjusted(Space::s12, 0, -Space::s12 - reserve, 0);
        painter.setFont(air().font(TextRole::Body));
        painter.setPen(air().color(isEnabled() ? Color::Ink : Color::Ink3));
        const int flags = static_cast<int>(
            QStyle::visualAlignment(layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter));
        // A language names itself in its own script: it keeps its own direction in the field.
        AirPaint::ownText(painter, room, flags,
                          painter.fontMetrics().elidedText(text(), Qt::ElideRight, room.width()));
        const QPixmap icon = air().icon(QStringLiteral("chevron-down"), air().color(Color::Ink2),
                                        chevron, devicePixelRatioF());
        const int x = rtl ? Space::s12 : width() - Space::s12 - chevron;
        painter.drawPixmap(x, (height() - chevron) / 2, icon);
        if (AirPaint::keyboardFocus(this)) {
            AirPaint::focusRing(painter, box, Radius::field);
        }
    }

private:
    /** The room round the value: the padding at both ends and the chevron. */
    static int chrome()
    {
        return 2 * Space::s12 + Space::s8 + Size::iconSmall;
    }
};

/**
 * The still dot before the state of the node: green while messages leave, the grey of waiting
 * while the node looks for the network, red only when there is no network. It is as high as the
 * first line of the title beside it, so it keeps to that line when the title wraps.
 */
class StateDot : public QWidget
{
public:
    explicit StateDot(QWidget* parent)
        : QWidget(parent)
    {
        setFixedSize(Size::dot, air().lineHeight(TextRole::Heading));
        AirPaint::followTheme(this);
    }

    void setColor(Color color_)
    {
        color = color_;
        update();
    }

protected:
    void paintEvent(QPaintEvent*) override
    {
        QPainter painter(this);
        painter.setRenderHint(QPainter::Antialiasing);
        painter.setPen(Qt::NoPen);
        painter.setBrush(air().color(color));
        painter.drawEllipse(QRectF(0, (height() - Size::dot) / 2.0, Size::dot, Size::dot));
    }

private:
    Color color = Color::Ink3;
};

/**
 * Keeps the old form of the call devices at least as wide as its grid of labels and lists needs.
 * Its own scroller scrolls only down and holds the grid to its width, so in a narrow pane the grid
 * was squeezed until the labels were cut; now the pane scrolls sideways instead. The grid asks
 * again after a change of language, and the least width follows.
 */
class GridWidth : public QObject
{
public:
    GridWidth(QWidget* form_, QScrollArea* scroller_)
        : QObject(form_)
        , form{form_}
        , scroller{scroller_}
    {
        scroller->widget()->installEventFilter(this);
        fit();
    }

protected:
    bool eventFilter(QObject* watched, QEvent* event) override
    {
        if (event->type() == QEvent::LayoutRequest && scroller->widget() == watched) {
            fit();
        }
        return QObject::eventFilter(watched, event);
    }

private:
    void fit()
    {
        const QMargins around = form->layout() ? form->layout()->contentsMargins() : QMargins();
        form->setMinimumWidth(scroller->widget()->minimumSizeHint().width()
                              + scroller->verticalScrollBar()->sizeHint().width()
                              + 2 * scroller->frameWidth() + around.left() + around.right());
    }

    QWidget* form;
    QScrollArea* scroller;
};
} // namespace

// ---- AirSettingsPage ----

AirSettingsPage::AirSettingsPage(Settings& settings_, Profile& profile_, IProfileInfo& profileInfo_,
                                 AirLocal& airLocal_, QWidget* callsForm, UpdateCheck* updateCheck,
                                 QWidget* parent)
    : QWidget(parent)
    , settings{settings_}
    , profile{profile_}
    , profileInfo{profileInfo_}
    , airLocal{airLocal_}
{
    setObjectName(QStringLiteral("settingsPage"));
    auto* const columns = new QHBoxLayout(this);
    columns->setContentsMargins(0, 0, 0, 0);
    columns->setSpacing(0);

    // The sections, on the start side, under the name of the page.
    navigation = new QWidget(this);
    navigation->setObjectName(QStringLiteral("settingsSections"));
    navigationColumn = new QVBoxLayout(navigation);
    navigationColumn->setContentsMargins(Space::s16, Space::s32, Space::s16, Space::s32);
    navigationColumn->setSpacing(Space::s2);
    navigationTitle = label(TextRole::Display, Color::Ink, navigation, words(Text::Settings));
    navigationTitle->setWordWrap(false);
    navigationTitle->setContentsMargins(Space::s12, 0, Space::s12, Space::s16);
    navigationColumn->addWidget(navigationTitle);
    columns->addWidget(navigation);

    stack = new QStackedWidget(this);
    columns->addWidget(stack, 1);

    buildAppearance();
    buildNotifications();
    buildPrivacy();
    buildCalls(callsForm);
    buildTransfer();
    buildNetwork();
    buildWindow();
    buildAbout(updateCheck);
    navigationColumn->addStretch(1);
    wordings.push_back(
        [this] { navigation->setAccessibleName(AirStrings::text(Text::SettingsSections)); });

    connect(&AirStyle::instance(), &AirStyle::changed, this, &AirSettingsPage::restyle);
    Translator::registerHandler(std::bind(&AirSettingsPage::retranslate, this), this);
    restyle();
    retranslate();
    refresh();
    alignLabels();
    showSection(Section::Appearance);
}

AirSettingsPage::~AirSettingsPage()
{
    Translator::unregister(this);
}

AirSettingsPage::Page& AirSettingsPage::addPage(Section section, Text title)
{
    const QLatin1String name(sectionNames[static_cast<int>(section)]);
    Page page;
    page.section = section;
    page.title = title;

    auto* const button = new NavButton(navigation);
    button->setObjectName(QStringLiteral("settingsSection_") + name);
    button->installEventFilter(this);
    page.button = button;
    navigationColumn->addWidget(button);
    connect(button, &QAbstractButton::clicked, this, [this, section] { showSection(section); });
    wordings.push_back([button, title] { button->setText(AirStrings::text(title)); });

    page.scroller = new QScrollArea(stack);
    page.scroller->setObjectName(QStringLiteral("settingsPane_") + name);
    page.scroller->setFrameShape(QFrame::NoFrame);
    page.scroller->setWidgetResizable(true);
    page.scroller->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    // Tab stops on the controls of the section, which the pane brings into view, not on the pane.
    page.scroller->setFocusPolicy(Qt::NoFocus);
    page.content = new QWidget(page.scroller);
    page.column = new QVBoxLayout(page.content);
    page.column->setSpacing(Space::s32);
    page.scroller->setWidget(page.content);
    stack->addWidget(page.scroller);

    page.column->addWidget(label(TextRole::Heading, Color::Ink, page.content, words(title)));
    pages.push_back(page);
    return pages.back();
}

QVBoxLayout* AirSettingsPage::group(Page& page, const Words& title, const QString& name)
{
    // The rows keep to the measure of text; on a wide window the rest stays empty at the end.
    auto* const holder = new QWidget(page.content);
    holder->setObjectName(QStringLiteral("settingsGroup_") + name);
    holder->setMaximumWidth(Size::textMeasure);
    auto* const box = new QVBoxLayout(holder);
    box->setContentsMargins(0, 0, 0, 0);
    box->setSpacing(Space::s12);
    if (title) {
        box->addWidget(label(TextRole::Label, Color::Ink3, holder, title));
    }
    auto* const rows = new QVBoxLayout();
    rows->setSpacing(Space::s16);
    box->addLayout(rows);
    auto* const measure = new QHBoxLayout();
    measure->setContentsMargins(0, 0, 0, 0);
    measure->addWidget(holder, 1);
    measure->addStretch(0);
    page.column->addLayout(measure);
    return rows;
}

QLabel* AirSettingsPage::label(TextRole role, Color color, QWidget* parent, const Words& text)
{
    auto* const made = new QLabel(parent);
    made->setTextFormat(Qt::PlainText);
    made->setWordWrap(true);
    made->setProperty(roleProperty, static_cast<int>(role));
    made->setProperty(colorProperty, static_cast<int>(color));
    made->setFont(air().font(role));
    AirPaint::textColor(made, air().color(color));
    if (text) {
        wordings.push_back([made, text] { made->setText(text()); });
    }
    return made;
}

QWidget* AirSettingsPage::row(QVBoxLayout* rows, const Words& title, const Words& detail,
                              QWidget* control)
{
    QWidget* const parent = rows->parentWidget() ? rows->parentWidget() : this;
    auto* const line = new QWidget(parent);
    // The control stands at the end of the row, or under the words when the pane is too narrow.
    auto* const box = new FitLayout(FitLayout::Kind::Row, Space::s16, Space::s8, line);
    auto* const textColumn = new QVBoxLayout();
    textColumn->setSpacing(Space::s2);
    QLabel* const titleLabel = label(TextRole::Body, Color::Ink, line, title);
    textColumn->addWidget(titleLabel);
    if (detail) {
        // Said only where the row has a consequence; empty, the line goes.
        QLabel* const detailLabel = label(TextRole::Caption, Color::Ink3, line, nullptr);
        textColumn->addWidget(detailLabel);
        details.emplace_back(detailLabel, detail);
    }
    box->addWords(textColumn);
    if (control) {
        control->setParent(line);
        box->addWidget(control);
        titleLabel->setBuddy(control);
    }
    rows->addWidget(line);
    return line;
}

AirSwitch* AirSettingsPage::switchRow(QVBoxLayout* rows, const Words& title, const Words& detail,
                                      const std::function<bool()>& read,
                                      const std::function<void(bool)>& write,
                                      const std::function<bool()>& enabled)
{
    auto* const toggle = new AirSwitch(this);
    QWidget* const line = row(rows, title, detail, toggle);
    wordings.push_back([toggle, title] { toggle->setAccessibleName(title()); });
    readers.push_back([toggle, read] { toggle->setChecked(read()); });
    connect(toggle, &AirSwitch::clicked, this, [this, write](bool checked) {
        write(checked);
        refreshStates();
    });
    if (enabled) {
        enablers.push_back([line, enabled] { line->setEnabled(enabled()); });
    }
    return toggle;
}

QWidget* AirSettingsPage::menuRow(QVBoxLayout* rows, const Words& title, const Words& value,
                                  const std::function<void(QWidget* anchor)>& open)
{
    auto* const button = new MenuButton(this);
    QWidget* const line = row(rows, title, nullptr, button);
    readers.push_back([button, value] {
        button->setText(value());
        button->updateGeometry();
    });
    wordings.push_back([button, title, value] {
        button->setAccessibleName(title());
        button->setText(value());
        button->updateGeometry();
    });
    connect(button, &QAbstractButton::clicked, this, [button, open] { open(button); });
    return line;
}

QWidget* AirSettingsPage::segmentRow(QVBoxLayout* rows, const Words& title,
                                     const std::vector<Words>& choices,
                                     const std::function<int()>& read,
                                     const std::function<void(int)>& write)
{
    QWidget* const parent = rows->parentWidget() ? rows->parentWidget() : this;
    auto* const line = new SegmentLine(parent);
    auto* const box = new QVBoxLayout(line);
    box->setContentsMargins(0, 0, 0, 0);
    box->setSpacing(Space::s8);
    box->addWidget(label(TextRole::Body, Color::Ink, line, title));
    auto* const segment = new Segment(line);
    line->segment = segment;
    segment->setChoices(static_cast<int>(choices.size()), [this, write](int index) {
        write(index);
        refreshStates();
    });
    box->addWidget(segment, 0, Qt::AlignLeading);
    wordings.push_back([segment, choices, title] {
        QStringList texts;
        for (const Words& choice : choices) {
            texts.append(choice());
        }
        segment->setTexts(texts);
        segment->setAccessibleName(title());
    });
    readers.push_back([segment, read] { segment->setCurrent(read()); });
    rows->addWidget(line);
    return line;
}

QLabel* AirSettingsPage::note(QVBoxLayout* rows, const Words& text)
{
    QWidget* const parent = rows->parentWidget() ? rows->parentWidget() : this;
    QLabel* const made = label(TextRole::Caption, Color::Ink3, parent, text);
    rows->addWidget(made);
    return made;
}

QWidget* AirSettingsPage::numberRow(QVBoxLayout* rows, const Words& title, const Words& pattern,
                                    const QString& name, int minimum, int maximum,
                                    const std::function<int()>& read,
                                    const std::function<void(int)>& write, const Words& special)
{
    auto* const field = new QSpinBox(this);
    field->setObjectName(name);
    field->setButtonSymbols(QAbstractSpinBox::NoButtons);
    field->setFrame(false);
    field->setRange(minimum, maximum);
    QWidget* const line = row(rows, title, nullptr, new InputBox(field, this));
    wordings.push_back([field, title, pattern, special] {
        if (pattern) {
            const QPair<QString, QString> parts = aroundNumber(pattern());
            field->setPrefix(parts.first);
            field->setSuffix(parts.second);
        }
        if (special) {
            field->setSpecialValueText(special());
        }
        field->setAccessibleName(title());
    });
    readers.push_back([field, read] {
        const QSignalBlocker blocker(field);
        field->setValue(read());
    });
    connect(field, &QSpinBox::editingFinished, this, [field, write] { write(field->value()); });
    return line;
}

// ---- Appearance ----

void AirSettingsPage::buildAppearance()
{
    Page& page = addPage(Section::Appearance, Text::SettingsAppearance);
    QVBoxLayout* const look = group(page, nullptr, QStringLiteral("look"));
    segmentRow(
        look, words(Text::SettingsTheme),
        {words(Text::ThemeSystem), words(Text::ThemeLight), words(Text::ThemeDark)},
        [this] { return static_cast<int>(settings.getThemeMode()); },
        [this](int index) {
            // AirStyle follows the setting: the window cross-fades into the new theme.
            settings.setThemeMode(static_cast<Settings::ThemeMode>(index));
        });
    menuRow(
        look, words(Text::SettingsLanguage),
        [this] { return Air::languageName(settings.getTranslation()); },
        [this](QWidget* anchor) { openLanguageMenu(anchor); });
    segmentRow(
        look, words(Text::SettingsList),
        {words(Text::SettingsListRegular), words(Text::SettingsListCompact)},
        [this] { return settings.getCompactLayout() ? 1 : 0; },
        [this](int index) { settings.setCompactLayout(index == 1); });

    QVBoxLayout* const chats =
        group(page, words(Text::SettingsConversations), QStringLiteral("chats"));
    menuRow(
        chats, words(Text::SettingsFont),
        [this] {
            const QFont font = settings.getChatMessageFont();
            if (font == Style::getFont(Style::Font::Big)) {
                return AirStrings::text(Text::SettingsFontApp);
            }
            return AirStrings::text(Text::SettingsFontValue)
                .arg(font.family())
                .arg(QFontInfo(font).pixelSize());
        },
        [this](QWidget* anchor) { openFontMenu(anchor); });
    segmentRow(
        chats, words(Text::SettingsFormatting),
        {words(Text::SettingsFormattingPlain), words(Text::SettingsFormattingMarks),
         words(Text::SettingsFormattingHidden)},
        [this] { return static_cast<int>(settings.getStylePreference()); },
        [this](int index) { settings.setStylePreference(static_cast<Settings::StyleType>(index)); });
    switchRow(
        chats, words(Text::SettingsSmileys), nullptr, [this] { return settings.getUseEmoticons(); },
        [this](bool on) { settings.setUseEmoticons(on); });
    QWidget* const pack = menuRow(
        chats, words(Text::SettingsSmileyPack),
        [this] {
            const QString chosen = settings.getSmileyPack();
            for (const auto& entry : SmileyPack::listSmileyPacks()) {
                if (entry.second == chosen) {
                    return entry.first;
                }
            }
            return QFileInfo(chosen).dir().dirName();
        },
        [this](QWidget* anchor) { openSmileyMenu(anchor); });
    QWidget* const sizeRow = numberRow(
        chats, words(Text::SettingsSmileySize), words(Text::SettingsPixels),
        QStringLiteral("settingsSmileySize"), smileySizeMin, smileySizeMax,
        [this] { return settings.getEmojiFontPointSize(); },
        [this](int pixels) { settings.setEmojiFontPointSize(pixels); });
    enablers.push_back([this, pack, sizeRow] {
        pack->setEnabled(settings.getUseEmoticons());
        sizeRow->setEnabled(settings.getUseEmoticons());
    });
    switchRow(
        chats, words(Text::SettingsNameColors), nullptr,
        [this] { return settings.getEnableGroupChatsColor(); },
        [this](bool on) { settings.setEnableGroupChatsColor(on); });
    switchRow(
        chats, words(Text::SettingsGroupEvents), words(Text::SettingsGroupEventsDetail),
        [this] { return settings.getShowGroupJoinLeaveMessages(); },
        [this](bool on) { settings.setShowGroupJoinLeaveMessages(on); });
    switchRow(
        chats, words(Text::SettingsStatusChanges), nullptr,
        [this] { return settings.getStatusChangeNotificationEnabled(); },
        [this](bool on) { settings.setStatusChangeNotificationEnabled(on); });
#ifdef SPELL_CHECKING
    switchRow(
        chats, words(Text::SettingsSpelling), nullptr,
        [this] { return settings.getSpellCheckingEnabled(); },
        [this](bool on) { settings.setSpellCheckingEnabled(on); });
#endif
    page.column->addStretch(1);
}

QPoint AirSettingsPage::menuPoint(QWidget* anchor) const
{
    // Under the field, from its start edge.
    const QRect field = anchor->rect();
    const QPoint start =
        layoutDirection() == Qt::RightToLeft ? field.bottomRight() : field.bottomLeft();
    return anchor->mapToGlobal(start + QPoint(0, Space::s4));
}

void AirSettingsPage::openLanguageMenu(QWidget* anchor)
{
    QMenu menu(anchor);
    QActionGroup* const choices = choiceGroup(menu);
    const QString current = settings.getTranslation();
    for (const QString& locale : Air::interfaceLanguages()) {
        QAction* const action = choices->addAction(Air::languageName(locale));
        action->setCheckable(true);
        action->setChecked(locale == current);
        action->setData(locale);
    }
    menu.addActions(choices->actions());
    const QPointer<AirSettingsPage> self(this);
    QAction* const chosen = menu.exec(menuPoint(anchor));
    if (!self || !chosen) {
        return;
    }
    const QString locale = chosen->data().toString();
    if (locale == settings.getTranslation()) {
        return;
    }
    // Every screen takes the new language at once, and the direction of Arabic with it, under a
    // cross-fade of the window as a new theme comes (DESIGN.md, "Settings on the phone").
    AirStyle::instance().changeUnderCrossFade([this, locale] {
        settings.setTranslation(locale);
        Translator::translate(locale);
        refresh();
    });
}

void AirSettingsPage::openFontMenu(QWidget* anchor)
{
    QMenu menu(anchor);
    QActionGroup* const choices = choiceGroup(menu);
    const bool asApp = settings.getChatMessageFont() == Style::getFont(Style::Font::Big);
    QAction* const appFont = choices->addAction(AirStrings::text(Text::SettingsFontApp));
    appFont->setCheckable(true);
    appFont->setChecked(asApp);
    QAction* const another = choices->addAction(AirStrings::text(Text::SettingsFontChoose));
    another->setCheckable(true);
    another->setChecked(!asApp);
    menu.addActions(choices->actions());
    const QPointer<AirSettingsPage> self(this);
    QAction* const chosen = menu.exec(menuPoint(anchor));
    if (!self || !chosen) {
        return;
    }
    if (chosen == appFont) {
        settings.setChatMessageFont(Style::getFont(Style::Font::Big));
        refresh();
    } else {
        chooseChatFont();
    }
}

void AirSettingsPage::openSmileyMenu(QWidget* anchor)
{
    QMenu menu(anchor);
    QActionGroup* const choices = choiceGroup(menu);
    for (const auto& entry : SmileyPack::listSmileyPacks()) {
        QAction* const action = choices->addAction(entry.first);
        action->setCheckable(true);
        action->setChecked(entry.second == settings.getSmileyPack());
        action->setData(entry.second);
    }
    menu.addActions(choices->actions());
    const QPointer<AirSettingsPage> self(this);
    QAction* const chosen = menu.exec(menuPoint(anchor));
    if (!self || !chosen) {
        return;
    }
    settings.setSmileyPack(chosen->data().toString());
    refresh();
}

void AirSettingsPage::chooseChatFont()
{
    const QPointer<AirSettingsPage> self(this);
    bool ok = false;
    const QFont chosen = QFontDialog::getFont(&ok, settings.getChatMessageFont(), window(),
                                              AirStrings::text(Text::SettingsFont));
    if (!self || !ok) {
        return;
    }
    // Kept by its size in pixels, as the old Appearance tab kept it.
    QFont font = chosen;
    font.setPixelSize(std::max(1, QFontInfo(chosen).pixelSize()));
    settings.setChatMessageFont(font);
    refresh();
}

// ---- Notifications ----

void AirSettingsPage::buildNotifications()
{
    Page& page = addPage(Section::Notifications, Text::SettingsNotifications);
    QVBoxLayout* const first = group(page, nullptr, QStringLiteral("notify"));
    switchRow(
        first, words(Text::SettingsNotify), nullptr, [this] { return settings.getNotify(); },
        [this](bool on) { settings.setNotify(on); });

    const auto notifyOn = [this] { return settings.getNotify(); };
    QVBoxLayout* const shown = group(page, nullptr, QStringLiteral("shown"));
    switchRow(
        shown, words(Text::SettingsPreview), nullptr, [this] { return !settings.getNotifyHide(); },
        [this](bool on) { settings.setNotifyHide(!on); }, notifyOn);
    note(shown, words(Text::SettingsPreviewNote));
#if DESKTOP_NOTIFICATIONS
    const Words systemDetail = nullptr;
#else
    // Without snorenotify the tray icon carries them: the row says why its switch is off.
    const Words systemDetail = [this] {
        if (trayCarriesNotifications(settings)) {
            return QString();
        }
        return AirStrings::text(TrayNotify::systemCarries() ? Text::SettingsSystemNoticesTray
                                                            : Text::SettingsSystemNoticesNoTray);
    };
#endif
    switchRow(
        shown, words(Text::SettingsSystemNotices), systemDetail,
        [this] { return settings.getDesktopNotify(); },
        [this](bool on) { settings.setDesktopNotify(on); },
        [this] { return settings.getNotify() && trayCarriesNotifications(settings); });
    switchRow(
        shown, words(Text::SettingsOpenWindow), nullptr, [this] { return settings.getShowWindow(); },
        [this](bool on) { settings.setShowWindow(on); }, notifyOn);

    QVBoxLayout* const sound = group(page, nullptr, QStringLiteral("sound"));
    switchRow(
        sound, words(Text::SettingsSound), nullptr, [this] { return settings.getNotifySound(); },
        [this](bool on) { settings.setNotifySound(on); }, notifyOn);
    switchRow(
        sound, words(Text::SettingsBusySound), nullptr, [this] { return settings.getBusySound(); },
        [this](bool on) { settings.setBusySound(on); },
        [this] { return settings.getNotify() && settings.getNotifySound(); });

    QVBoxLayout* const groups =
        group(page, words(Text::SettingsGroupsTitle), QStringLiteral("groups"));
    // Kept inverted in the settings file, so that files of older builds keep their meaning.
    switchRow(
        groups, words(Text::SettingsMentionsOnly), words(Text::SettingsMentionsOnlyDetail),
        [this] { return !settings.getGroupAlwaysNotify(); },
        [this](bool on) { settings.setGroupAlwaysNotify(!on); }, notifyOn);
    page.column->addStretch(1);
}

// ---- Privacy ----

void AirSettingsPage::buildPrivacy()
{
    Page& page = addPage(Section::Privacy, Text::SettingsPrivacy);
    QVBoxLayout* const first = group(page, nullptr, QStringLiteral("privacy"));
    switchRow(
        first, words(Text::SettingsTyping), nullptr,
        [this] { return settings.getTypingNotification(); },
        [this](bool on) { settings.setTypingNotification(on); });
    switchRow(
        first, words(Text::SettingsHistory), words(Text::SettingsHistoryDetail),
        [this] { return settings.getEnableLogging(); },
        [this](bool on) {
            // The outgoing queue is kept apart from the history (Outbox): turning the
            // history off keeps every message that still waits for its person.
            settings.setEnableLogging(on);
            if (!on) {
                askHistoryErase();
            }
        });

    QVBoxLayout* const blocked = group(page, nullptr, QStringLiteral("blocked"));
    blocked->addWidget(new AirBlockedKeys(airLocal, page.content));

    QVBoxLayout* const hidden = group(page, nullptr, QStringLiteral("hidden"));
    hidden->addWidget(new AirHiddenMembers(settings, page.content));
    page.column->addStretch(1);
}

void AirSettingsPage::askHistoryErase()
{
    const QPointer<AirSettingsPage> self(this);
    AirDialog::confirm(this, AirStrings::text(Text::SettingsHistoryEraseTitle),
                       AirStrings::text(Text::SettingsHistoryEraseMessage),
                       AirStrings::text(Text::SettingsHistoryEraseAction), AirDialog::Verb::Danger,
                       [self] {
                           AirSettingsPage* const page = self.data();
                           if (!page) {
                               return;
                           }
                           if (History* const history = page->profile.getHistory()) {
                               history->eraseHistory();
                           }
                           // The edits and reactions on the erased entries go with them.
                           page->airLocal.forgetEntries();
                           page->say(AirStrings::text(Text::SettingsHistoryErased));
                       });
}

// ---- Calls ----

void AirSettingsPage::buildCalls(QWidget* form)
{
    Page& page = addPage(Section::Calls, Text::SettingsCalls);
    deviceForm = form;
    if (form) {
        // The devices and their levels, as the Audio and video tab had them; it scrolls itself.
        form->setParent(page.content);
        page.column->addWidget(form, 1);
        // Its grid of labels and lists is wider than a narrow window leaves the pane: the pane
        // scrolls sideways then, rather than cutting it off.
        page.scroller->setHorizontalScrollBarPolicy(Qt::ScrollBarAsNeeded);
        for (QScrollArea* const inner : form->findChildren<QScrollArea*>()) {
            inner->setFocusPolicy(Qt::NoFocus);
            if (inner->widget() && inner->parentWidget() == form) {
                new GridWidth(form, inner);
            }
        }
    } else {
        page.column->addStretch(1);
    }
}

// ---- Profile transfer ----

void AirSettingsPage::buildTransfer()
{
    Page& page = addPage(Section::Transfer, Text::SettingsTransfer);
    QVBoxLayout* const file = group(page, nullptr, QStringLiteral("file"));
    auto* const actions = new FitLayout(FitLayout::Kind::Buttons, Space::s8, Space::s8);
    auto* const exportButton = new AirButton(AirButton::Kind::Plain, page.content);
    exportButton->setObjectName(QStringLiteral("settingsExport"));
    auto* const importButton = new AirButton(AirButton::Kind::Plain, page.content);
    importButton->setObjectName(QStringLiteral("settingsImport"));
    actions->addWidget(exportButton);
    actions->addWidget(importButton);
    file->addLayout(actions);
    note(file, words(Text::YouExportNote));
    wordings.push_back([exportButton, importButton] {
        exportButton->setText(AirStrings::text(Text::YouExport));
        importButton->setText(AirStrings::text(Text::SettingsImport));
    });
    connect(exportButton, &AirButton::clicked, this,
            [this] { AirYouPage::askExport(this, profileInfo); });
    connect(importButton, &AirButton::clicked, this, &AirSettingsPage::askImport);

    QVBoxLayout* const portable = group(page, nullptr, QStringLiteral("portable"));
    switchRow(
        portable, words(Text::SettingsPortable), words(Text::SettingsPortableDetail),
        [this] { return settings.getMakeToxPortable(); },
        [this](bool on) { settings.setMakeToxPortable(on); });
    page.column->addStretch(1);
}

void AirSettingsPage::askImport()
{
    const QString message =
        AirStrings::text(Text::SettingsImportMessage) + QLatin1Char(' ')
        + AirStrings::text(profileInfo.isEncrypted() ? Text::YouLogoutMessage
                                                     : Text::YouLogoutMessageNoPassword);
    const QPointer<AirSettingsPage> self(this);
    AirDialog::confirm(this, AirStrings::text(Text::SettingsImportTitle), message,
                       AirStrings::text(Text::SettingsImportAction), AirDialog::Verb::Ink, [self] {
                           AirSettingsPage* const page = self.data();
                           if (!page) {
                               return;
                           }
                           const QString path =
                               QFileDialog::getOpenFileName(page->window(),
                                                            AirStrings::text(Text::SettingsImport),
                                                            QDir::homePath(),
                                                            AirStrings::text(Text::YouExportFilter));
                           // The dialog runs its own loop: the page may be gone when it closes.
                           AirSettingsPage* const still = self.data();
                           if (still && !path.isEmpty()) {
                               still->importProfile(path);
                           }
                       });
}

void AirSettingsPage::importProfile(const QString& path)
{
    moveProfileIn(this, profileInfo, path, true);
}

bool AirSettingsPage::moveProfileIn(QWidget* anchor, IProfileInfo& info, const QString& path, bool asked)
{
    // Read first, so a file that is no profile is said here, before anything is logged out of.
    // The profile takes a name of its own on this device: no profile here is replaced by it.
    const ProfileFile::Contents contents = ProfileFile::readFile(path, QString());
    using Problem = ProfileFile::Contents::Problem;
    if (contents.problem != Problem::None && contents.problem != Problem::NeedsPassword) {
        AirDialog::notice(anchor, AirStrings::text(Text::SettingsImportFailedTitle),
                          AirStrings::importProblemText(contents.problem), contents.code);
        return false;
    }
    if (asked) {
        info.logoutToImport(path);
        return true;
    }
    const QString message = AirStrings::text(Text::SettingsImportMessage) + QLatin1Char(' ')
                            + AirStrings::text(info.isEncrypted() ? Text::YouLogoutMessage
                                                                  : Text::YouLogoutMessageNoPassword);
    IProfileInfo* const target = &info;
    // The verb is the one of the phones for a profile file shared with the app: Move. What it does
    // is theirs too, logging out first and showing the file's name and ID before it is moved in.
    AirDialog::confirm(anchor, AirStrings::text(Text::SettingsImportTitle), message,
                       AirStrings::text(Text::SettingsImportMove), AirDialog::Verb::Ink,
                       [target, path] { target->logoutToImport(path); });
    return true;
}

// ---- Network ----

void AirSettingsPage::buildNetwork()
{
    Page& page = addPage(Section::Network, Text::SettingsNetwork);
    buildNodeState(page);
    const auto noProxy = [this] { return settings.getProxyType() == Settings::ProxyType::ptNone; };
    const auto udpOn = [this, noProxy] { return !settings.getForceTCP() && noProxy(); };
    QVBoxLayout* const links = group(page, nullptr, QStringLiteral("links"));
    // UDP and a proxy do not go together: the proxy could not hide where UDP comes from.
    switchRow(
        links, words(Text::SettingsUdp),
        [noProxy] { return noProxy() ? QString() : AirStrings::text(Text::SettingsUdpProxy); },
        udpOn, [this](bool on) { settings.setForceTCP(!on); }, noProxy);
    switchRow(
        links, words(Text::SettingsIpv6), nullptr, [this] { return settings.getEnableIPv6(); },
        [this](bool on) { settings.setEnableIPv6(on); });
    switchRow(
        links, words(Text::SettingsLan), nullptr,
        [this, udpOn] { return settings.getEnableLanDiscovery() && udpOn(); },
        [this](bool on) { settings.setEnableLanDiscovery(on); }, udpOn);

    QVBoxLayout* const proxy = group(page, nullptr, QStringLiteral("proxy"));
    segmentRow(
        proxy, words(Text::SettingsProxy),
        {words(Text::SettingsProxyNone), [] { return QStringLiteral("SOCKS5"); },
         [] { return QStringLiteral("HTTP"); }},
        [this] { return static_cast<int>(settings.getProxyType()); },
        [this](int index) {
            settings.setProxyType(static_cast<Settings::ProxyType>(index));
            refresh();
        });
    auto* const address = new AirTextField(AirTextField::Kind::Line, this);
    address->setObjectName(QStringLiteral("settingsProxyAddress"));
    // At most as wide as a menu field; in a narrow pane it narrows with the pane.
    address->setMaximumWidth(Size::listColumn - Space::s64);
    QWidget* const addressRow = row(proxy, words(Text::SettingsProxyAddress), nullptr, address);
    QWidget* const portRow = numberRow(
        proxy, words(Text::SettingsProxyPort), nullptr, QStringLiteral("settingsProxyPort"), 0,
        65535, [this] { return static_cast<int>(settings.getProxyPort()); },
        [this](int port) { settings.setProxyPort(static_cast<quint16>(port)); });
    wordings.push_back([address] {
        address->edit()->setAccessibleName(AirStrings::text(Text::SettingsProxyAddress));
    });
    readers.push_back([this, address] {
        if (!address->edit()->hasFocus()) {
            address->setText(settings.getProxyAddr());
        }
    });
    connect(address, &AirTextField::changed, this,
            [this, address] { settings.setProxyAddr(address->text().trimmed()); });
    // The address and the port are asked for only once a proxy is chosen.
    enablers.push_back([addressRow, portRow, noProxy] {
        addressRow->setVisible(!noProxy());
        portRow->setVisible(!noProxy());
    });
    note(proxy, words(Text::SettingsNetworkRestart));

    // Download attachments, as on the phones, with the limit and the folder of this computer.
    QVBoxLayout* const files = group(page, nullptr, QStringLiteral("files"));
    const auto mode = [this] {
        return Air::attachmentMode(settings.getAutoSaveEnabled(),
                                   static_cast<qint64>(settings.getMaxAutoAcceptSize()));
    };
    segmentRow(
        files, words(Text::SettingsAttachments),
        {words(Text::SettingsAttachmentsAlways), words(Text::SettingsAttachmentsSmall),
         words(Text::SettingsAttachmentsNever)},
        [mode] { return static_cast<int>(mode()); },
        [this](int index) {
            const Air::AttachmentRule rule =
                Air::attachmentRule(static_cast<Air::AttachmentMode>(index),
                                    static_cast<qint64>(settings.getMaxAutoAcceptSize()));
            settings.setAutoSaveEnabled(rule.autoSave);
            settings.setMaxAutoAcceptSize(static_cast<size_t>(rule.maxSize));
            refresh();
        });
    QWidget* const limitRow = numberRow(
        files, words(Text::SettingsAttachmentsLimit), words(Text::SizeMegabytes),
        QStringLiteral("settingsAttachmentLimit"), 1, Air::smallAttachmentMaxMegabytes,
        [this] {
            const qint64 bytes = static_cast<qint64>(settings.getMaxAutoAcceptSize());
            return static_cast<int>(
                std::max<qint64>(1, (bytes + Air::bytesPerMegabyte / 2) / Air::bytesPerMegabyte));
        },
        [this](int megabytes) {
            settings.setMaxAutoAcceptSize(static_cast<size_t>(megabytes * Air::bytesPerMegabyte));
        });
    enablers.push_back(
        [limitRow, mode] { limitRow->setVisible(mode() == Air::AttachmentMode::Small); });
    auto* const changeFolder = new AirButton(AirButton::Kind::Plain, this);
    changeFolder->setObjectName(QStringLiteral("settingsDownloadFolder"));
    QWidget* const folderRow = row(
        files, words(Text::SettingsSaveTo),
        [this] {
#ifdef Q_OS_WIN
            const bool tildeForHome = false;
#else
            const bool tildeForHome = true;
#endif
            return Air::folderLabel(settings.getGlobalAutoAcceptDir(),
                                    QStandardPaths::writableLocation(QStandardPaths::DownloadLocation),
                                    AirStrings::text(Text::SettingsDownloadsFolder),
                                    QDir::homePath(), tildeForHome);
        },
        changeFolder);
    enablers.push_back(
        [folderRow, mode] { folderRow->setEnabled(mode() != Air::AttachmentMode::Never); });
    wordings.push_back(
        [changeFolder] { changeFolder->setText(AirStrings::text(Text::SettingsChange)); });
    connect(changeFolder, &AirButton::clicked, this, &AirSettingsPage::chooseDownloadFolder);
    note(files, words(Text::SettingsAttachmentsNote));
    // A group file comes whole and has no Accept: it is saved as it comes, whatever the choice.
    note(files, words(Text::SettingsAttachmentsGroups));

    // The technical log, for support.
    QVBoxLayout* const logGroup = group(page, words(Text::SettingsLog), QStringLiteral("log"));
    auto* const logActions = new FitLayout(FitLayout::Kind::Buttons, Space::s8, Space::s8);
    auto* const saveLogButton = new AirButton(AirButton::Kind::Plain, page.content);
    saveLogButton->setObjectName(QStringLiteral("settingsSaveLog"));
    auto* const copyLogButton = new AirButton(AirButton::Kind::Plain, page.content);
    copyLogButton->setObjectName(QStringLiteral("settingsCopyLog"));
    logActions->addWidget(saveLogButton);
    logActions->addWidget(copyLogButton);
    logGroup->addLayout(logActions);
    note(logGroup, words(Text::SettingsLogNote));
    wordings.push_back([saveLogButton, copyLogButton] {
        saveLogButton->setText(AirStrings::text(Text::SettingsLogSave));
        copyLogButton->setText(AirStrings::text(Text::SettingsLogCopy));
    });
    connect(saveLogButton, &AirButton::clicked, this, &AirSettingsPage::saveLog);
    connect(copyLogButton, &AirButton::clicked, this, &AirSettingsPage::copyLog);

    QVBoxLayout* const restore = group(page, nullptr, QStringLiteral("restore"));
    auto* const restoreButton = new AirButton(AirButton::Kind::Danger, page.content);
    restoreButton->setObjectName(QStringLiteral("settingsRestore"));
    restore->addWidget(restoreButton, 0, Qt::AlignLeading);
    wordings.push_back(
        [restoreButton] { restoreButton->setText(AirStrings::text(Text::SettingsRestore)); });
    connect(restoreButton, &AirButton::clicked, this, &AirSettingsPage::askRestoreNetwork);
    page.column->addStretch(1);
}

void AirSettingsPage::buildNodeState(Page& page)
{
    // What the state means for messages, whether this computer has internet and how the link
    // goes; no addresses and no probe codes. The technical log is further down, for support.
    QVBoxLayout* const rows = group(page, nullptr, QStringLiteral("state"));
    nodeGroup = rows->parentWidget();

    auto* const heading = new QVBoxLayout();
    heading->setSpacing(Space::s4);
    auto* const titleRow = new QHBoxLayout();
    titleRow->setSpacing(Space::s8);
    nodeDot = new StateDot(nodeGroup);
    nodeTitle = label(TextRole::Heading, Color::Ink, nodeGroup, nullptr);
    nodeTitle->setObjectName(QStringLiteral("settingsNodeTitle"));
    titleRow->addWidget(nodeDot, 0, Qt::AlignTop);
    titleRow->addWidget(nodeTitle, 1);
    heading->addLayout(titleRow);
    nodeMeaning = label(TextRole::Body, Color::Ink2, nodeGroup, nullptr);
    nodeMeaning->setObjectName(QStringLiteral("settingsNodeMeaning"));
    heading->addWidget(nodeMeaning);
    rows->addLayout(heading);

    const auto value = [this](const char* name) {
        QLabel* const made = label(TextRole::Body, Color::Ink2, nodeGroup, nullptr);
        made->setObjectName(QLatin1String(name));
        made->setWordWrap(false);
        return made;
    };
    nodeInternet = value("settingsNodeInternet");
    nodeLink = value("settingsNodeLink");
    nodeCount = value("settingsNodeCount");
    row(rows, words(Text::NetworkInternet), nullptr, nodeInternet);
    row(rows, words(Text::NetworkLink), nullptr, nodeLink);
    row(rows, words(Text::NetworkNodes), nullptr, nodeCount);
    note(rows, words(Text::NetworkNodesNote));

    // Only while the node looks for the network with internet at hand: then it helps.
    auto* const reconnect = new AirButton(AirButton::Kind::Plain, nodeGroup);
    reconnect->setObjectName(QStringLiteral("settingsReconnect"));
    reconnect->hide();
    rows->addWidget(reconnect, 0, Qt::AlignLeading);
    reconnectButton = reconnect;
    wordings.push_back([reconnect] { reconnect->setText(AirStrings::text(Text::NetworkReconnect)); });
    connect(reconnect, &AirButton::clicked, this, [this] {
        if (reconnectNode) {
            reconnectNode();
        }
        say(AirStrings::text(Text::NetworkReconnectToast));
    });

    // The words follow the language and the settings as the rest of the page does.
    wordings.push_back([this] { refreshNode(false); });
    readers.push_back([this] { refreshNode(false); });
}

void AirSettingsPage::setNode(AirSource* source, std::function<void()> reconnect)
{
    if (nodeSource) {
        disconnect(nodeSource, nullptr, this, nullptr);
    }
    nodeSource = source;
    reconnectNode = std::move(reconnect);
    if (nodeSource) {
        connect(nodeSource, &AirSource::networkChanged, this, [this] { refreshNode(true); });
    }
    nodeShown = false;
    refreshNode(false);
}

void AirSettingsPage::refreshNode(bool crossFade)
{
    if (!nodeGroup) {
        return;
    }
    const Air::NetworkState state =
        nodeSource ? nodeSource->networkState() : Air::NetworkState::Connecting;
    const Air::NetworkReport report = Air::NetworkReport::make(state);
    const Air::Nodes nodes = nodeSource ? nodeSource->nodes() : Air::Nodes();
    if (crossFade && nodeShown && !(report == shownNode) && nodeGroup->isVisible()) {
        crossFadeNode();
    }
    shownNode = report;
    nodeShown = true;

    static_cast<StateDot*>(nodeDot)->setColor(report.alarm  ? Color::Danger
                                              : report.live ? Color::Live
                                                            : Color::Ink3);
    // Red words only when there is no network, as the line under your name.
    const Color titleColor = report.alarm ? Color::Danger : Color::Ink;
    nodeTitle->setProperty(colorProperty, static_cast<int>(titleColor));
    AirPaint::textColor(nodeTitle, air().color(titleColor));
    nodeTitle->setText(AirStrings::networkTitle(state));
    nodeMeaning->setText(AirStrings::networkMeaning(state));
    nodeInternet->setText(
        AirStrings::text(report.internet ? Text::NetworkInternetYes : Text::NetworkInternetNo));
    nodeLink->setText(AirStrings::networkLinkText(report.link));
    nodeCount->setText(QString::number(nodes.total()));
    reconnectButton->setVisible(report.offersReconnect);
}

void AirSettingsPage::crossFadeNode()
{
    // One change at a time: a fade still on its way ends where it was going.
    if (nodeFade) {
        nodeFade->setCurrentTime(nodeFade->totalDuration());
    }
    QWidget* const holder = nodeGroup->parentWidget();
    if (!holder) {
        return;
    }
    // The words as they stand, without a ground, so the new ones show through as they fade.
    const qreal ratio = nodeGroup->devicePixelRatioF();
    QPixmap before(nodeGroup->size() * ratio);
    before.setDevicePixelRatio(ratio);
    before.fill(Qt::transparent);
    nodeGroup->render(&before, QPoint(), QRegion(), QWidget::DrawChildren);

    auto* const old = new QLabel(holder);
    old->setAttribute(Qt::WA_TransparentForMouseEvents);
    // The picture stays where the words stood, whatever the direction of the page.
    old->setAlignment(Qt::AlignTop | Qt::AlignLeft | Qt::AlignAbsolute);
    old->setPixmap(before);
    old->setGeometry(nodeGroup->geometry());
    auto* const oldOpacity = new QGraphicsOpacityEffect(old);
    old->setGraphicsEffect(oldOpacity);
    old->show();
    old->raise();
    auto* const newOpacity = new QGraphicsOpacityEffect(nodeGroup);
    newOpacity->setOpacity(0.0);
    nodeGroup->setGraphicsEffect(newOpacity);

    const QPointer<QLabel> oldWords(old);
    const QPointer<QGraphicsOpacityEffect> newEffect(newOpacity);
    const QPointer<QWidget> group(nodeGroup);
    auto* const animation = new QVariantAnimation(this);
    animation->setStartValue(0.0);
    animation->setEndValue(1.0);
    animation->setDuration(air().duration(AirTokens::Motion::state));
    connect(animation, &QVariantAnimation::valueChanged, this,
            [oldWords, oldOpacity, newEffect, group](const QVariant& progress) {
                if (oldWords) {
                    oldOpacity->setOpacity(1.0 - progress.toReal());
                    // The old words are cut to the group as it stands now: a button that went
                    // away does not lie over the rows that moved up under it.
                    if (group) {
                        oldWords->setGeometry(group->geometry());
                    }
                }
                if (newEffect) {
                    newEffect->setOpacity(progress.toReal());
                }
            });
    // The old words and the effect go once the new words stand alone, also when a change that
    // follows cuts the fade short (it runs it to its end).
    connect(animation, &QVariantAnimation::finished, this, [oldWords, group, newEffect] {
        if (oldWords) {
            oldWords->deleteLater();
        }
        if (group && newEffect && group->graphicsEffect() == newEffect) {
            group->setGraphicsEffect(nullptr);
        }
    });
    nodeFade = animation;
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}

void AirSettingsPage::chooseDownloadFolder()
{
    const QPointer<AirSettingsPage> self(this);
    const QString current = settings.getGlobalAutoAcceptDir();
    const QString folder =
        QFileDialog::getExistingDirectory(window(), AirStrings::text(Text::SettingsSaveTo),
                                          current.isEmpty() ? QStandardPaths::writableLocation(
                                                                  QStandardPaths::DownloadLocation)
                                                            : current);
    if (self && !folder.isEmpty()) {
        settings.setGlobalAutoAcceptDir(folder);
        refresh();
    }
}

QString AirSettingsPage::supportLog() const
{
    // States and counts only: the addresses toxcore knows stay out of the network part.
    QString log = nodeSource ? nodeSource->networkReport() : QString();
    QFile file(logPath(settings));
    if (file.open(QIODevice::ReadOnly | QIODevice::Text)) {
        log += QStringLiteral("--- app log ---\n");
        log += QString::fromUtf8(file.readAll());
    }
    return log;
}

void AirSettingsPage::saveLog()
{
    const QString failed = AirStrings::text(Text::SettingsLogFailedTitle);
    const QPointer<AirSettingsPage> self(this);
    QFileDialog dialog(window(), AirStrings::text(Text::SettingsLogSave),
                       QDir::home().filePath(QStringLiteral("khandaq.log")),
                       AirStrings::text(Text::SettingsLogFilter));
    dialog.setAcceptMode(QFileDialog::AcceptSave);
    dialog.setDefaultSuffix(QStringLiteral("log"));
    if (dialog.exec() != QDialog::Accepted || !self) {
        return;
    }
    const QString target = dialog.selectedFiles().value(0);
    if (target.isEmpty()) {
        return;
    }
    // The dialog asked before an existing file is replaced; the file is replaced whole or not at all.
    QSaveFile out(target);
    if (!out.open(QIODevice::WriteOnly | QIODevice::Text) || out.write(supportLog().toUtf8()) < 0
        || !out.commit()) {
        AirDialog::notice(this, failed, AirStrings::text(Text::YouNotWritable));
        return;
    }
    say(AirStrings::text(Text::SettingsLogSaved));
}

void AirSettingsPage::copyLog()
{
    QApplication::clipboard()->setText(supportLog(), QClipboard::Clipboard);
    say(AirStrings::text(Text::SettingsLogCopied));
}

void AirSettingsPage::askRestoreNetwork()
{
    const QPointer<AirSettingsPage> self(this);
    AirDialog::confirm(this, AirStrings::text(Text::SettingsRestoreTitle),
                       AirStrings::text(Text::SettingsRestoreMessage),
                       AirStrings::text(Text::SettingsRestoreAction), AirDialog::Verb::Danger, [self] {
                           AirSettingsPage* const page = self.data();
                           if (!page) {
                               return;
                           }
                           // What this section sets, back to the values a new profile has.
                           Settings& s = page->settings;
                           s.setForceTCP(false);
                           s.setEnableIPv6(true);
                           s.setEnableLanDiscovery(true);
                           s.setProxyType(Settings::ProxyType::ptNone);
                           s.setProxyAddr(QString());
                           s.setProxyPort(0);
                           page->refresh();
                           page->say(AirStrings::text(Text::SettingsRestored));
                       });
}

// ---- Startup and window ----

void AirSettingsPage::buildWindow()
{
    Page& page = addPage(Section::Window, Text::SettingsWindow);
    const auto trayOn = [this] { return settings.getShowSystemTray(); };
#ifdef QTOX_PLATFORM_EXT
    QVBoxLayout* const startup = group(page, words(Text::SettingsStartup), QStringLiteral("startup"));
    switchRow(
        startup, words(Text::SettingsAutorun), nullptr, [this] { return settings.getAutorun(); },
        [this](bool on) { settings.setAutorun(on); });
#endif

    QVBoxLayout* const tray = group(page, words(Text::SettingsTray), QStringLiteral("tray"));
    switchRow(tray, words(Text::SettingsTrayIcon), nullptr, trayOn, [this](bool on) {
        settings.setShowSystemTray(on);
        settings.saveGlobal();
        // System notifications without snorenotify need the icon: their switch follows it.
        refresh();
    });
    switchRow(
        tray, words(Text::SettingsStartInTray), nullptr,
        [this] { return settings.getAutostartInTray(); },
        [this](bool on) { settings.setAutostartInTray(on); }, trayOn);
    switchRow(
        tray, words(Text::SettingsMinimizeToTray), nullptr,
        [this] { return settings.getMinimizeToTray(); },
        [this](bool on) { settings.setMinimizeToTray(on); }, trayOn);
    switchRow(
        tray, words(Text::SettingsCloseToTray), nullptr, [this] { return settings.getCloseToTray(); },
        [this](bool on) { settings.setCloseToTray(on); }, trayOn);
    switchRow(
        tray, words(Text::SettingsLightIcon), nullptr, [this] { return settings.getLightTrayIcon(); },
        [this](bool on) {
            settings.setLightTrayIcon(on);
            emit trayIconChanged();
        },
        trayOn);

    QVBoxLayout* const windows = group(page, words(Text::SettingsWindows), QStringLiteral("windows"));
    switchRow(
        windows, words(Text::SettingsSeparate), nullptr,
        [this] { return settings.getSeparateWindow(); },
        [this](bool on) { settings.setSeparateWindow(on); });
    switchRow(
        windows, words(Text::SettingsWindowEach), nullptr,
        [this] { return settings.getDontGroupWindows(); },
        [this](bool on) { settings.setDontGroupWindows(on); },
        [this] { return settings.getSeparateWindow(); });

#ifdef QTOX_PLATFORM_EXT
    QVBoxLayout* const status =
        group(page, words(Text::SettingsStatusTitle), QStringLiteral("status"));
    numberRow(
        status, words(Text::SettingsAway), words(Text::SettingsMinutes),
        QStringLiteral("settingsAway"), 0, awayMinutesMax,
        [this] { return settings.getAutoAwayTime(); },
        [this](int minutes) { settings.setAutoAwayTime(minutes); }, words(Text::SettingsAwayNever));
#endif
    page.column->addStretch(1);
}

// ---- About ----

void AirSettingsPage::buildAbout(UpdateCheck* updateCheck)
{
    Page& page = addPage(Section::About, Text::SettingsAbout);
    const QString toxcore =
        QStringLiteral("%1.%2.%3").arg(tox_version_major()).arg(tox_version_minor()).arg(tox_version_patch());
    QVBoxLayout* const versions = group(page, nullptr, QStringLiteral("versions"));
    const struct
    {
        Text title;
        QString value;
        // Every member is set by the braces that make it: an aggregate, which an initializer here
        // would stop it being in C++11.
        // cppcheck-suppress uninitMemberVarNoCtor
        const char* name;
        // cppcheck-suppress uninitMemberVarNoCtor
        bool mono;
    } facts[] = {{Text::SettingsVersion, QStringLiteral(GIT_DESCRIBE), "settingsVersion", false},
                 {Text::SettingsCommit, QStringLiteral(GIT_VERSION), "settingsCommit", true},
                 {Text::SettingsToxcore, toxcore, "settingsToxcore", false},
                 {Text::SettingsQt, QStringLiteral(QT_VERSION_STR), "settingsQt", false}};
    for (const auto& fact : facts) {
        QLabel* const value =
            label(fact.mono ? TextRole::Mono : TextRole::Body, Color::Ink2, page.content, nullptr);
        value->setObjectName(QLatin1String(fact.name));
        value->setText(fact.value);
        value->setWordWrap(false);
        value->setTextInteractionFlags(Qt::TextSelectableByMouse);
        // A version reads left to right in every language.
        value->setLayoutDirection(Qt::LeftToRight);
        if (fact.mono) {
            // The full commit is longer than a narrow pane is wide. It never widens the pane: there
            // its end is cut, while the whole of it is still selected and copied, and is the tooltip.
            value->setMinimumWidth(1);
            value->setToolTip(fact.value);
        }
        row(versions, words(fact.title), nullptr, value);
    }

#if UPDATE_CHECK_ENABLED
    QVBoxLayout* const updates = group(page, nullptr, QStringLiteral("updates"));
    switchRow(
        updates, words(Text::SettingsCheckUpdates), nullptr,
        [this] { return settings.getCheckUpdates(); },
        [this](bool on) { settings.setCheckUpdates(on); });
    auto* const download = new AirButton(AirButton::Kind::Ink, page.content);
    download->setObjectName(QStringLiteral("settingsUpdate"));
    download->hide();
    updates->addWidget(download, 0, Qt::AlignLeading);
    wordings.push_back(
        [download] { download->setText(AirStrings::text(Text::SettingsUpdateDownload)); });
    if (updateCheck) {
        connect(updateCheck, &UpdateCheck::updateAvailable, download, [download](QString, QUrl link) {
            download->show();
            QObject::disconnect(download, &AirButton::clicked, nullptr, nullptr);
            QObject::connect(download, &AirButton::clicked, download,
                             [link] { QDesktopServices::openUrl(link); });
        });
    }
#else
    // Without the update check there is nothing to switch: the old box did nothing here.
    std::ignore = updateCheck;
#endif

    QVBoxLayout* const people = group(page, nullptr, QStringLiteral("people"));
    note(people, words(Text::SettingsOwner));
    note(people, words(Text::SettingsBasedOn));
    auto* const links = new FitLayout(FitLayout::Kind::Buttons, Space::s16, Space::s8);
    const struct
    {
        Words text;
        // Every member is set by the braces that make it: an aggregate, which an initializer here
        // would stop it being in C++11.
        // cppcheck-suppress uninitMemberVarNoCtor
        const char* url;
        // cppcheck-suppress uninitMemberVarNoCtor
        const char* name;
    } targets[] = {
        {words(Text::SettingsWebsite), "https://khandaq.org", "settingsWebsite"},
        {[] { return QStringLiteral("1sa.me"); }, "https://1sa.me/", "settingsOwnerSite"},
        {words(Text::SettingsContributors), "https://github.com/qTox/qTox/graphs/contributors",
         "settingsContributors"},
    };
    for (const auto& target : targets) {
        auto* const link = new AirButton(AirButton::Kind::Quiet, page.content);
        link->setObjectName(QLatin1String(target.name));
        const Words text = target.text;
        wordings.push_back([link, text] { link->setText(text()); });
        const QUrl url(QLatin1String(target.url));
        connect(link, &AirButton::clicked, link, [url] { QDesktopServices::openUrl(url); });
        links->addWidget(link);
    }
    people->addLayout(links);

    // A report starts with what support asks first: the system and the versions.
    auto* const report = new AirButton(AirButton::Kind::Plain, page.content);
    report->setObjectName(QStringLiteral("settingsReport"));
    people->addWidget(report, 0, Qt::AlignLeading);
    wordings.push_back([report] { report->setText(AirStrings::text(Text::SettingsReport)); });
    connect(report, &AirButton::clicked, this, [toxcore] {
        const QString body =
            QStringLiteral("##### Brief Description\n\n"
                           "OS: %1\n"
                           "Khandaq version: %2\n"
                           "Commit hash: %3\n"
                           "toxcore: %4\n"
                           "Qt: %5\n\n"
                           "##### Steps to reproduce\n\n"
                           "1. \n2. \n3. \n\n"
                           "##### Observed Behavior\n\n\n"
                           "##### Expected Behavior\n\n")
                .arg(QSysInfo::prettyProductName(), QStringLiteral(GIT_DESCRIBE),
                     QStringLiteral(GIT_VERSION), toxcore, QStringLiteral(QT_VERSION_STR));
        QUrl url(QStringLiteral("https://khandaq.org"));
        url.setQuery(QStringLiteral("body=") + QString::fromUtf8(QUrl::toPercentEncoding(body)));
        QDesktopServices::openUrl(url);
    });

    // The licence notice, in the words it is published in.
    QVBoxLayout* const licence = group(page, words(Text::SettingsLicense), QStringLiteral("licence"));
    QLabel* const licenceText = label(TextRole::Caption, Color::Ink2, page.content, nullptr);
    licenceText->setObjectName(QStringLiteral("settingsLicense"));
    licenceText->setText(QStringLiteral(
        "Khandaq Messenger, a modified work based on qTox. Copyright © 2026 Khandaq Project.\n"
        "Copyright © 2014-2020 by The qTox Project Contributors\n\n"
        "qTox is a Qt-based graphical interface for Tox.\n\n"
        "qTox is libre software: you can redistribute it and/or modify it under the terms of the "
        "GNU General Public License as published by the Free Software Foundation, either version 3 "
        "of the License, or (at your option) any later version.\n\n"
        "qTox is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without "
        "even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the "
        "GNU General Public License for more details.\n\n"
        "You should have received a copy of the GNU General Public License along with this "
        "program. If not, see https://www.gnu.org/copyleft/gpl.html."));
    licenceText->setTextInteractionFlags(Qt::TextSelectableByMouse);
    // Published in English only, it reads left to right in every language.
    licenceText->setLayoutDirection(Qt::LeftToRight);
    licence->addWidget(licenceText);
    // The full text the notice points to, a link as it was in the old About tab; the name of the
    // licence is not translated.
    auto* const gpl = new AirButton(AirButton::Kind::Quiet, page.content);
    gpl->setObjectName(QStringLiteral("settingsGpl"));
    gpl->setText(QStringLiteral("GNU GPL v3"));
    connect(gpl, &AirButton::clicked, gpl, [] {
        QDesktopServices::openUrl(QUrl(QStringLiteral("https://www.gnu.org/copyleft/gpl.html")));
    });
    licence->addWidget(gpl, 0, Qt::AlignLeading);
    page.column->addStretch(1);
}

// ---- The page ----

void AirSettingsPage::showIn(ContentLayout* contentLayout)
{
    contentLayout->setFlush(true);
    contentLayout->mainContent->layout()->addWidget(this);
    show();
    refresh();
}

bool AirSettingsPage::isShown() const
{
    if (!isVisible()) {
        return false;
    }
    if (QWindow* const handle = window()->windowHandle()) {
        handle->alert(0);
    }
    return true;
}

void AirSettingsPage::showSection(Section section)
{
    const QWidget* const before = stack->currentWidget();
    int from = -1;
    int to = -1;
    for (size_t at = 0; at < pages.size(); ++at) {
        const Page& page = pages[at];
        const bool chosen = page.section == section;
        page.button->setChecked(chosen);
        if (page.scroller == before) {
            from = static_cast<int>(at);
        }
        if (chosen) {
            to = static_cast<int>(at);
            stack->setCurrentWidget(page.scroller);
            page.scroller->verticalScrollBar()->setValue(0);
        }
    }
    if (from >= 0 && to >= 0 && from != to && isVisible()) {
        slideIn(pages[static_cast<size_t>(to)].scroller, to > from);
    }
}

void AirSettingsPage::slideIn(QWidget* pane, bool down)
{
    // One section at a time: a slide still on its way ends where it was going.
    if (slide) {
        slide->setCurrentTime(slide->totalDuration());
    }
    // The section rises into place from the side it lies on in the list, and fades in; with less
    // motion it only fades (DESIGN.md, "Motion").
    const bool travel = !air().reducedMotion();
    const int shift = travel ? (down ? Space::s24 : -Space::s24) : 0;
    const QPoint home = stack->contentsRect().topLeft();
    auto* const fade = new QGraphicsOpacityEffect(pane);
    fade->setOpacity(0.0);
    pane->setGraphicsEffect(fade);
    pane->move(home + QPoint(0, shift));
    const QPointer<QGraphicsOpacityEffect> effect(fade);
    auto* const animation = new QVariantAnimation(this);
    animation->setStartValue(0.0);
    animation->setEndValue(1.0);
    animation->setDuration(
        air().duration(travel ? AirTokens::Motion::layout : AirTokens::Motion::state));
    animation->setEasingCurve(air().easeOut());
    const QPointer<QWidget> target(pane);
    connect(animation, &QVariantAnimation::valueChanged, this,
            [target, effect, home, shift](const QVariant& value) {
                if (!target || !effect) {
                    return;
                }
                const qreal progress = value.toReal();
                effect->setOpacity(progress);
                target->move(home + QPoint(0, qRound(shift * (1.0 - progress))));
            });
    connect(animation, &QVariantAnimation::finished, this, [target, home] {
        if (target) {
            target->setGraphicsEffect(nullptr);
            target->move(home);
        }
    });
    slide = animation;
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}

AirSettingsPage::Section AirSettingsPage::currentSection() const
{
    for (const Page& page : pages) {
        if (stack->currentWidget() == page.scroller) {
            return page.section;
        }
    }
    return Section::Appearance;
}

bool AirSettingsPage::sectionNamed(const QString& name, Section* section)
{
    for (int index = 0; index < sectionCount; ++index) {
        if (name == QLatin1String(sectionNames[index])) {
            *section = static_cast<Section>(index);
            return true;
        }
    }
    return false;
}

void AirSettingsPage::stepSection(int step)
{
    const int count = static_cast<int>(pages.size());
    const Section current = currentSection();
    int index = 0;
    while (index < count && pages[static_cast<size_t>(index)].section != current) {
        ++index;
    }
    const int next = std::min(count - 1, std::max(0, index + step));
    const Page& page = pages[static_cast<size_t>(next)];
    showSection(page.section);
    page.button->setFocus(Qt::TabFocusReason);
}

void AirSettingsPage::refresh()
{
    for (const auto& read : readers) {
        read();
    }
    refreshStates();
}

void AirSettingsPage::refreshStates()
{
    for (const auto& enable : enablers) {
        enable();
    }
    for (const auto& detail : details) {
        const QString text = detail.second();
        detail.first->setText(text);
        detail.first->setVisible(!text.isEmpty());
    }
}

void AirSettingsPage::retranslate()
{
    for (const auto& put : wordings) {
        put();
    }
    refreshStates();
    arrangeNavigation();
}

void AirSettingsPage::restyle()
{
    // The legacy sheet of the window paints scroll areas white; the page paints its own ground.
    for (const Page& page : pages) {
        page.scroller->setStyleSheet(QStringLiteral("QScrollArea { background: transparent; }"));
        page.scroller->viewport()->setAutoFillBackground(false);
        page.content->setAutoFillBackground(false);
    }
    if (deviceForm) {
        // The window's legacy sheet draws its lists with no border and no sign of the focus. Here a
        // list keeps a clear border as wide as the focus ring, which takes the focus colour while
        // the list has the focus; with the padding it gives the list the size it had.
        deviceForm->setStyleSheet(
            QStringLiteral("QComboBox { border: %1px solid transparent; padding: %2px %3px; }"
                           " QComboBox:focus { border-color: %4; }")
                .arg(static_cast<int>(Stroke::focus))
                .arg(Space::s4)
                .arg(Space::s8)
                .arg(air().color(Color::Focus).name()));
    }
    for (QLabel* const made : findChildren<QLabel*>()) {
        const QVariant role = made->property(roleProperty);
        if (role.isValid()) {
            made->setFont(air().font(static_cast<TextRole>(role.toInt())));
            AirPaint::textColor(made,
                                air().color(static_cast<Color>(made->property(colorProperty).toInt())));
        }
    }
    // The heading of a section stands on the line of the page's name, by their baselines.
    const int lift = QFontMetrics(air().font(TextRole::Display)).ascent()
                     - QFontMetrics(air().font(TextRole::Heading)).ascent();
    const int top = navigationColumn->contentsMargins().top() + std::max(0, lift);
    for (const Page& page : pages) {
        page.column->setContentsMargins(Space::s32, top, Space::s32, Space::s48);
    }
    arrangeNavigation();
    update();
}

void AirSettingsPage::alignLabels()
{
    // Words stand at the start edge of the interface whatever their script, as on You.
    for (QLabel* const made : findChildren<QLabel*>()) {
        if (made->property(roleProperty).isValid()) {
            made->setAlignment(
                QStyle::visualAlignment(made->layoutDirection(), Qt::AlignLeft | Qt::AlignVCenter));
        }
    }
}

void AirSettingsPage::arrangeNavigation()
{
    // The column takes the width of its longest section, within the bounds of the list column.
    int widest = navigationTitle->sizeHint().width();
    for (const Page& page : pages) {
        widest = std::max(widest, page.button->sizeHint().width());
    }
    const QMargins margins = navigationColumn->contentsMargins();
    const int width = std::min(std::max(widest + margins.left() + margins.right(), Space::s64 * 3),
                               Size::listColumn - Space::s64);
    navigation->setFixedWidth(width);
}

void AirSettingsPage::say(const QString& text)
{
    if (isVisible()) {
        AirToast::post(this, text);
    }
}

void AirSettingsPage::paintEvent(QPaintEvent*)
{
    QPainter painter(this);
    painter.fillRect(rect(), air().color(Color::Surface));
    // The sections and what they hold are parted by a hairline, as the list and the chat are.
    const int x = layoutDirection() == Qt::RightToLeft ? navigation->x() - 1
                                                       : navigation->x() + navigation->width();
    painter.fillRect(QRect(x, 0, static_cast<int>(Stroke::hairline), height()),
                     air().color(Color::Line));
}

void AirSettingsPage::changeEvent(QEvent* event)
{
    // The labels have taken the new direction already: Qt turns the children first.
    if (event->type() == QEvent::LayoutDirectionChange) {
        alignLabels();
    }
    QWidget::changeEvent(event);
}

void AirSettingsPage::showEvent(QShowEvent* event)
{
    // What changed elsewhere (the tray menu, a group dossier) shows when the page does.
    refresh();
    QWidget::showEvent(event);
}

bool AirSettingsPage::eventFilter(QObject* watched, QEvent* event)
{
    // Up and Down go through the sections, as through the rows of a list.
    auto* const widget = qobject_cast<QWidget*>(watched);
    if (event->type() == QEvent::KeyPress && widget && navigation->isAncestorOf(widget)) {
        const int key = static_cast<QKeyEvent*>(event)->key();
        if (key == Qt::Key_Up || key == Qt::Key_Down) {
            stepSection(key == Qt::Key_Down ? 1 : -1);
            return true;
        }
    }
    return QWidget::eventFilter(watched, event);
}
