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

#pragma once

#include <QTextEdit>

#include <functional>

class ChatTextEdit final : public QTextEdit
{
    Q_OBJECT
public:
    explicit ChatTextEdit(QWidget* parent = nullptr);
    ~ChatTextEdit();
    void setLastMessage(QString lm);
    void sendKeyEvent(QKeyEvent* event);
    /**
     * Up in an empty field asks handler first: it may put a message that has not left yet back
     * into the field to edit, and returns whether it did. Otherwise the last text comes back.
     */
    void setEditLastHandler(std::function<bool()> handler);

    /** What a paste brings into the field, in the order it is tried. */
    enum class PasteKind
    {
        /** Files of this computer: they are sent, as when they are dropped. */
        Files,
        /** The text, inserted as typed; also what a paste with nothing else comes to. */
        Text,
        /** A picture with no text beside it, or only its address, offered to be sent. */
        Image,
    };
    /**
     * Files when the paste holds files of this computer, then any text that is more than spaces
     * and line breaks, and a picture only when there is no such text. A text that is only a web,
     * file or data address beside a picture is the picture's, as a browser's Copy image puts it.
     */
    static PasteKind pasteKind(bool hasLocalFiles, const QString& text, bool hasImage);

signals:
    void enterPressed();
    void escapePressed();
    void tabPressed();
    void keyPressed();
    void pasteImage(const QPixmap& pixmap);
    void pasteFiles(const QStringList& paths);

protected:
    void keyPressEvent(QKeyEvent* event) final;
    void insertFromMimeData(const QMimeData* source) override;
    void resizeEvent(QResizeEvent* event) final;

private:
    void retranslateUi();
    void updateHeightForContent();
    bool tryHandlePaste(const QMimeData* mimeData);

private:
    QString lastMessage;
    std::function<bool()> editLast;
};
