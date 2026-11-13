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

#include "airaccessible.h"

#include "src/chatlog/chatwidget.h"
#include "src/widget/air/airlistview.h"
#include "src/widget/air/airstrings.h"

#include <QAccessible>
#include <QAccessibleWidget>
#include <QHash>
#include <QPointer>
#include <QWidget>
#include <QWindow>

#include <memory>

namespace {

QWindow* windowOf(const QWidget* widget)
{
    const QWidget* const top = widget ? widget->window() : nullptr;
    return top ? top->windowHandle() : nullptr;
}

/** A rectangle of the viewport where it stands on the screen. */
QRect onScreen(const QWidget* viewport, const QRect& rect)
{
    return viewport ? QRect(viewport->mapToGlobal(rect.topLeft()), rect.size()) : QRect();
}

/** The interfaces a line made for its parts: they go when the line goes. */
void forget(const QHash<QString, QAccessible::Id>& made)
{
    for (const QAccessible::Id id : made) {
        QAccessible::deleteAccessibleInterface(id);
    }
}

// ---- the chat list ----

/** Accept (or Join) or Decline on a card: a button of its own, as on the phones. */
class CardButton : public QAccessibleInterface, public QAccessibleActionInterface
{
public:
    CardButton(AirListView* list_, const QString& id_, bool accept_, QAccessible::Id card_)
        : list{list_}
        , id{id_}
        , accept{accept_}
        , card{card_}
    {
    }

    bool isValid() const override
    {
        AirListView::SpokenLine line;
        return current(&line);
    }
    QObject* object() const override
    {
        return nullptr;
    }
    QWindow* window() const override
    {
        return windowOf(list);
    }
    QAccessibleInterface* parent() const override
    {
        // The card deletes its buttons before itself, so it is there while they are.
        return QAccessible::accessibleInterface(card);
    }
    QAccessibleInterface* child(int) const override
    {
        return nullptr;
    }
    int childCount() const override
    {
        return 0;
    }
    int indexOfChild(const QAccessibleInterface*) const override
    {
        return -1;
    }
    QAccessibleInterface* childAt(int, int) const override
    {
        return nullptr;
    }
    QString text(QAccessible::Text t) const override
    {
        AirListView::SpokenLine line;
        if (t != QAccessible::Name || !current(&line)) {
            return QString();
        }
        return accept ? line.acceptName : line.declineName;
    }
    void setText(QAccessible::Text, const QString&) override {}
    QRect rect() const override
    {
        AirListView::SpokenLine line;
        if (!current(&line)) {
            return QRect();
        }
        return onScreen(list->viewport(), accept ? line.acceptRect : line.declineRect);
    }
    QAccessible::Role role() const override
    {
        return QAccessible::Button;
    }
    QAccessible::State state() const override
    {
        QAccessible::State state;
        state.focusable = true;
        return state;
    }
    void* interface_cast(QAccessible::InterfaceType type) override
    {
        return type == QAccessible::ActionInterface ? static_cast<QAccessibleActionInterface*>(this)
                                                    : nullptr;
    }

    QStringList actionNames() const override
    {
        return {pressAction()};
    }
    void doAction(const QString& name) override
    {
        if (list && name == pressAction()) {
            list->pressSpoken(id, accept ? AirListView::SpokenPress::Accept
                                         : AirListView::SpokenPress::Decline);
        }
    }
    QStringList keyBindingsForAction(const QString&) const override
    {
        return {};
    }

private:
    bool current(AirListView::SpokenLine* line) const
    {
        return list && list->spokenLine(id, line) && line->type == Air::Row::Type::Request;
    }

    QPointer<AirListView> list;
    QString id;
    bool accept;
    QAccessible::Id card;
};

/** A heading, a card, a row or a message found: one line of the chat list. */
class ListLine : public QAccessibleInterface, public QAccessibleActionInterface
{
public:
    ListLine(AirListView* list_, const QString& id_)
        : list{list_}
        , id{id_}
    {
    }
    ~ListLine() override
    {
        forget(buttons);
    }

    QString lineId() const
    {
        return id;
    }

    bool isValid() const override
    {
        return list && list->hasSpokenLine(id);
    }
    QObject* object() const override
    {
        return nullptr;
    }
    QWindow* window() const override
    {
        return windowOf(list);
    }
    QAccessibleInterface* parent() const override
    {
        return list ? QAccessible::queryAccessibleInterface(list->viewport()) : nullptr;
    }
    int childCount() const override
    {
        AirListView::SpokenLine line;
        return current(&line) && line.type == Air::Row::Type::Request ? 2 : 0;
    }
    QAccessibleInterface* child(int index) const override
    {
        if (index < 0 || index >= childCount()) {
            return nullptr;
        }
        const QString key = index == 0 ? QStringLiteral("accept") : QStringLiteral("decline");
        const auto made = buttons.constFind(key);
        if (made != buttons.constEnd()) {
            return QAccessible::accessibleInterface(made.value());
        }
        auto* const self = const_cast<ListLine*>(this);
        QAccessibleInterface* const button =
            new CardButton(list, id, index == 0, QAccessible::uniqueId(self));
        buttons.insert(key, QAccessible::registerAccessibleInterface(button));
        return button;
    }
    int indexOfChild(const QAccessibleInterface* child) const override
    {
        for (int i = 0; i < childCount(); ++i) {
            if (this->child(i) == child) {
                return i;
            }
        }
        return -1;
    }
    QAccessibleInterface* childAt(int x, int y) const override
    {
        for (int i = 0; i < childCount(); ++i) {
            QAccessibleInterface* const button = child(i);
            if (button && button->rect().contains(x, y)) {
                return button;
            }
        }
        return nullptr;
    }
    QString text(QAccessible::Text t) const override
    {
        AirListView::SpokenLine line;
        return t == QAccessible::Name && current(&line) ? line.name : QString();
    }
    void setText(QAccessible::Text, const QString&) override {}
    QRect rect() const override
    {
        AirListView::SpokenLine line;
        return current(&line) ? onScreen(list->viewport(), line.rect) : QRect();
    }
    QAccessible::Role role() const override
    {
        AirListView::SpokenLine line;
        if (!current(&line)) {
            return QAccessible::NoRole;
        }
        switch (line.type) {
        case Air::Row::Type::Header:
            return QAccessible::StaticText;
        case Air::Row::Type::Request:
            return QAccessible::Grouping;
        case Air::Row::Type::Entry:
        case Air::Row::Type::Message:
            break;
        }
        return QAccessible::ListItem;
    }
    QAccessible::State state() const override
    {
        QAccessible::State state;
        AirListView::SpokenLine line;
        if (!current(&line)) {
            state.invalid = true;
            return state;
        }
        if (opens(line)) {
            state.selectable = true;
            state.selected = line.open;
            state.focusable = true;
            state.focused = line.cursor && list->hasFocus();
        }
        state.offscreen = !list->viewport()->rect().intersects(line.rect);
        return state;
    }
    void* interface_cast(QAccessible::InterfaceType type) override
    {
        AirListView::SpokenLine line;
        if (type == QAccessible::ActionInterface && current(&line) && opens(line)) {
            return static_cast<QAccessibleActionInterface*>(this);
        }
        return nullptr;
    }

    QStringList actionNames() const override
    {
        return {pressAction()};
    }
    void doAction(const QString& name) override
    {
        if (list && name == pressAction()) {
            list->pressSpoken(id, AirListView::SpokenPress::Line);
        }
    }
    QStringList keyBindingsForAction(const QString&) const override
    {
        return {};
    }

private:
    bool current(AirListView::SpokenLine* line) const
    {
        return list && list->spokenLine(id, line);
    }
    /** A row or a message found opens a conversation; headings and cards do not. */
    static bool opens(const AirListView::SpokenLine& line)
    {
        return line.type == Air::Row::Type::Entry || line.type == Air::Row::Type::Message;
    }

    QPointer<AirListView> list;
    QString id;
    mutable QHash<QString, QAccessible::Id> buttons;
};

/**
 * The viewport of the chat list: the painted lines first, then the widgets that stand in it (the
 * button of an empty list).
 */
class ListViewport : public QAccessibleWidget
{
public:
    ListViewport(QWidget* viewport, AirListView* list_)
        : QAccessibleWidget(viewport, QAccessible::List)
        , list{list_}
    {
    }
    ~ListViewport() override
    {
        forget(made);
    }

    QString text(QAccessible::Text t) const override
    {
        if (t == QAccessible::Name && list) {
            return list->accessibleName();
        }
        return QAccessibleWidget::text(t);
    }
    int childCount() const override
    {
        return ids().size() + QAccessibleWidget::childCount();
    }
    QAccessibleInterface* child(int index) const override
    {
        const QStringList lines = ids();
        if (index < 0) {
            return nullptr;
        }
        if (index < lines.size()) {
            return lineInterface(lines[index]);
        }
        return QAccessibleWidget::child(index - lines.size());
    }
    int indexOfChild(const QAccessibleInterface* child) const override
    {
        const QStringList lines = ids();
        for (int i = 0; i < lines.size(); ++i) {
            const auto found = made.constFind(lines[i]);
            if (found != made.constEnd() && QAccessible::accessibleInterface(found.value()) == child) {
                return i;
            }
        }
        const int widgetIndex = QAccessibleWidget::indexOfChild(child);
        return widgetIndex < 0 ? -1 : lines.size() + widgetIndex;
    }
    QAccessibleInterface* childAt(int x, int y) const override
    {
        if (QAccessibleInterface* const widget = QAccessibleWidget::childAt(x, y)) {
            return widget;
        }
        for (const QString& id : ids()) {
            QAccessibleInterface* const line = lineInterface(id);
            if (line && line->rect().contains(x, y)) {
                return line;
            }
        }
        return nullptr;
    }
    QAccessibleInterface* focusChild() const override
    {
        if (list && list->hasFocus()) {
            for (const QString& id : ids()) {
                AirListView::SpokenLine line;
                if (list->spokenLine(id, &line) && line.cursor) {
                    return lineInterface(id);
                }
            }
        }
        return QAccessibleWidget::focusChild();
    }

private:
    QStringList ids() const
    {
        return list ? list->spokenIds() : QStringList();
    }

    /** The interface of a line, made once and kept while the line stands. */
    QAccessibleInterface* lineInterface(const QString& id) const
    {
        prune();
        const auto found = made.constFind(id);
        if (found != made.constEnd()) {
            return QAccessible::accessibleInterface(found.value());
        }
        QAccessibleInterface* const line = new ListLine(list, id);
        made.insert(id, QAccessible::registerAccessibleInterface(line));
        return line;
    }

    /** Lines gone from the list take their interfaces with them. */
    void prune() const
    {
        for (auto it = made.begin(); it != made.end();) {
            if (list && list->hasSpokenLine(it.key())) {
                ++it;
                continue;
            }
            QAccessible::deleteAccessibleInterface(it.value());
            it = made.erase(it);
        }
    }

    QPointer<AirListView> list;
    mutable QHash<QString, QAccessible::Id> made;
};

// ---- the transcript ----

/** One line of a transcript: an entry, a day mark or a line nobody wrote. */
class LogLine : public QAccessibleInterface
{
public:
    LogLine(ChatWidget* log_, const ChatLine::Ptr& line_)
        : log{log_}
        , line{line_}
    {
    }

    bool holds(const ChatLine* other) const
    {
        const ChatLine::Ptr held = line.lock();
        return held && held.get() == other;
    }

    bool isValid() const override
    {
        return log && !line.expired();
    }
    QObject* object() const override
    {
        return nullptr;
    }
    QWindow* window() const override
    {
        return windowOf(log);
    }
    QAccessibleInterface* parent() const override
    {
        return log ? QAccessible::queryAccessibleInterface(log->viewport()) : nullptr;
    }
    QAccessibleInterface* child(int) const override
    {
        return nullptr;
    }
    int childCount() const override
    {
        return 0;
    }
    int indexOfChild(const QAccessibleInterface*) const override
    {
        return -1;
    }
    QAccessibleInterface* childAt(int, int) const override
    {
        return nullptr;
    }
    QString text(QAccessible::Text t) const override
    {
        const ChatLine::Ptr held = line.lock();
        return t == QAccessible::Name && held ? held->spokenText() : QString();
    }
    void setText(QAccessible::Text, const QString&) override {}
    QRect rect() const override
    {
        const ChatLine::Ptr held = line.lock();
        return held && log ? onScreen(log->viewport(), log->viewportRectOf(*held)) : QRect();
    }
    QAccessible::Role role() const override
    {
        return QAccessible::ListItem;
    }
    QAccessible::State state() const override
    {
        QAccessible::State state;
        const ChatLine::Ptr held = line.lock();
        if (!held || !log) {
            state.invalid = true;
            return state;
        }
        state.readOnly = true;
        state.offscreen = !log->viewport()->rect().intersects(log->viewportRectOf(*held));
        return state;
    }

private:
    QPointer<ChatWidget> log;
    std::weak_ptr<ChatLine> line;
};

/** The viewport of a transcript: its lines in order, as the log shows them. */
class LogViewport : public QAccessibleWidget
{
public:
    LogViewport(QWidget* viewport, ChatWidget* log_)
        : QAccessibleWidget(viewport, QAccessible::List)
        , log{log_}
    {
    }
    ~LogViewport() override
    {
        for (const QAccessible::Id id : made) {
            QAccessible::deleteAccessibleInterface(id);
        }
    }

    QString text(QAccessible::Text t) const override
    {
        if (t == QAccessible::Name) {
            return AirStrings::text(AirStrings::Text::TranscriptName);
        }
        return QAccessibleWidget::text(t);
    }
    int childCount() const override
    {
        return log ? static_cast<int>(log->spokenLines().size()) : 0;
    }
    QAccessibleInterface* child(int index) const override
    {
        if (!log || index < 0) {
            return nullptr;
        }
        const std::vector<ChatLine::Ptr> lines = log->spokenLines();
        return index < static_cast<int>(lines.size()) ? lineInterface(lines[index]) : nullptr;
    }
    int indexOfChild(const QAccessibleInterface* child) const override
    {
        if (!log || !child) {
            return -1;
        }
        const std::vector<ChatLine::Ptr> lines = log->spokenLines();
        for (size_t i = 0; i < lines.size(); ++i) {
            const auto found = made.constFind(lines[i].get());
            if (found != made.constEnd() && QAccessible::accessibleInterface(found.value()) == child) {
                return static_cast<int>(i);
            }
        }
        return -1;
    }
    QAccessibleInterface* childAt(int x, int y) const override
    {
        if (!log) {
            return nullptr;
        }
        for (const ChatLine::Ptr& line : log->spokenLines()) {
            if (onScreen(log->viewport(), log->viewportRectOf(*line)).contains(x, y)) {
                return lineInterface(line);
            }
        }
        return nullptr;
    }

private:
    QAccessibleInterface* lineInterface(const ChatLine::Ptr& line) const
    {
        prune();
        const auto found = made.constFind(line.get());
        if (found != made.constEnd()) {
            auto* const known = static_cast<LogLine*>(QAccessible::accessibleInterface(found.value()));
            if (known && known->holds(line.get())) {
                return known;
            }
            // Another line took the address of one that went.
            QAccessible::deleteAccessibleInterface(found.value());
            made.remove(line.get());
        }
        QAccessibleInterface* const fresh = new LogLine(log, line);
        made.insert(line.get(), QAccessible::registerAccessibleInterface(fresh));
        return fresh;
    }

    /** Lines the log no longer holds take their interfaces with them. */
    void prune() const
    {
        for (auto it = made.begin(); it != made.end();) {
            QAccessibleInterface* const known = QAccessible::accessibleInterface(it.value());
            if (known && known->isValid()) {
                ++it;
                continue;
            }
            QAccessible::deleteAccessibleInterface(it.value());
            it = made.erase(it);
        }
    }

    QPointer<ChatWidget> log;
    mutable QHash<const ChatLine*, QAccessible::Id> made;
};

/**
 * The viewport is known by being one, not by its object name: QGraphicsView, under ChatWidget, puts
 * in a viewport of its own that has none.
 */
QAccessibleInterface* factory(const QString&, QObject* object)
{
    if (!object || !object->isWidgetType()) {
        return nullptr;
    }
    auto* const viewport = static_cast<QWidget*>(object);
    if (auto* const list = qobject_cast<AirListView*>(object->parent())) {
        return list->viewport() == viewport ? new ListViewport(viewport, list) : nullptr;
    }
    if (auto* const log = qobject_cast<ChatWidget*>(object->parent())) {
        return log->viewport() == viewport ? new LogViewport(viewport, log) : nullptr;
    }
    return nullptr;
}

/** The role as the walk log names it. */
QString roleName(QAccessible::Role role)
{
    switch (role) {
    case QAccessible::List:
        return QStringLiteral("list");
    case QAccessible::ListItem:
        return QStringLiteral("item");
    case QAccessible::StaticText:
        return QStringLiteral("text");
    case QAccessible::Grouping:
        return QStringLiteral("group");
    case QAccessible::Button:
        return QStringLiteral("button");
    default:
        break;
    }
    return QString::number(static_cast<int>(role), 16);
}

void collect(QAccessibleInterface* node, QStringList& lines, int depth)
{
    if (!node || depth > 32) {
        return;
    }
    const QString name = node->text(QAccessible::Name);
    if (!name.isEmpty()) {
        lines.append(roleName(node->role()) + QLatin1Char('\t') + name);
    }
    for (int i = 0; i < node->childCount(); ++i) {
        collect(node->child(i), lines, depth + 1);
    }
}

} // namespace

namespace AirAccessible {

void install()
{
    static bool installed = false;
    if (!installed) {
        installed = true;
        QAccessible::installFactory(factory);
    }
}

QStringList spokenTree(QWidget* widget)
{
    QStringList lines;
    collect(QAccessible::queryAccessibleInterface(widget), lines, 0);
    return lines;
}

} // namespace AirAccessible
