/*
    KHANDAQ (Figma): fullscreen in-app image viewer, replacing "open in OS" for
    received/sent picture files in the chat. Isolated widget — does not touch the
    file-transfer or send paths.
*/

#pragma once

#include <QDialog>
#include <QPixmap>

class QLabel;

class ImageViewerWidget : public QDialog
{
    Q_OBJECT

public:
    explicit ImageViewerWidget(const QString& imagePath, QWidget* parent = nullptr);

    static bool isImagePath(const QString& path);

protected:
    void wheelEvent(QWheelEvent* event) override;
    void mousePressEvent(QMouseEvent* event) override;
    void keyPressEvent(QKeyEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;

private:
    void applyZoom();

    QLabel* imageLabel;
    QPixmap original;
    double scale;
};
