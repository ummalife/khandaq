/*
    Copyright © 2019 by The qTox Project Contributors

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

#include <QObject>

#include <atomic>

/**
 * @fn void Audio::frameAvailable(const int16_t *pcm, size_t sample_count, uint8_t channels,
 * uint32_t sampling_rate);
 *
 * When there are input subscribers, we regularly emit captured audio frames with this signal
 * Always connect with a blocking queued connection lambda, else the behaviour is undefined
 */

class IAudioSource : public QObject
{
    Q_OBJECT
public:
    virtual ~IAudioSource() = default;

    virtual operator bool() const = 0;

    /**
     * KHANDAQ (0.2.60): a source that hears every frame, those under the voice threshold too: a
     * voice note keeps its pauses. A call keeps the threshold.
     */
    void setEveryFrame(bool on)
    {
        everyFrame = on;
    }
    bool hearsEveryFrame() const
    {
        return everyFrame;
    }

signals:
    void frameAvailable(const int16_t* pcm, size_t sample_count, uint8_t channels,
                        uint32_t sampling_rate);
    void volumeAvailable(qreal value);
    void invalidated();

private:
    std::atomic<bool> everyFrame{false};
};
