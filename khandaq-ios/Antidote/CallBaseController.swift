// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

/// The ground and the head of the call screens (redesign stage 3, E9): dark in both themes; at the top
/// the person, the state of the call (a word, or the timer in tabular figures) and the link; the
/// controls of the subclasses sit at the bottom, in the thumb zone.
class CallBaseController: UIViewController {
    let theme: Theme

    let callerName: String

    /// The head: the name, the state and the link.
    var topContainer: UIView!
    var callerLabel: UILabel!
    /// The state of the call: "Calling", "Incoming call", the timer, "Call ended".
    var infoLabel: UILabel!

    /// The link to the person right now, under the state; nil hides the line.
    var link: AirLinkState? {
        didSet {
            guard isViewLoaded, link != oldValue else {
                return
            }
            applyLink()
        }
    }

    fileprivate var topContainerTopConstraint: Constraint!
    fileprivate let linkRow = UIStackView()
    fileprivate let linkMark = AirLinkMarkView()
    fileprivate let linkLabel = UILabel()
    fileprivate var infoIsTimer = false
    fileprivate var infoFontCategory = UIApplication.shared.preferredContentSizeCategory

    init(theme: Theme, callerName: String) {
        self.theme = theme
        self.callerName = callerName

        super.init(nibName: nil, bundle: nil)

        overrideUserInterfaceStyle = .dark
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var preferredStatusBarStyle: UIStatusBarStyle {
        return .lightContent
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        // HINT: turn on ProximitySensor to blank screen when near the ear
        UIDevice.current.isProximityMonitoringEnabled = true
    }

    deinit {
        // HINT: reset ProximitySensor when calling screen is closed
        UIDevice.current.isProximityMonitoringEnabled = false
    }

    override func loadView() {
        view = AirCallBackdropView()

        createTopViews()
        installConstraints()
        applyLink()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        let category = UIApplication.shared.preferredContentSizeCategory
        if category != infoFontCategory {
            infoFontCategory = category
            applyInfoFont()
        }
    }

    /**
        Prepare for removal by disabling all active views.
     */
    func prepareForRemoval() {
        setInfo(String(localized: "air_call_ended"), isTimer: false)
        UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, infoLabel.text)
    }

    func toggleTopContainer(hidden: Bool) {
        let offset = hidden ? -topContainer.frame.size.height : 0.0
        topContainerTopConstraint.update(offset: offset)
    }

    /// The state of the call: a word, or the timer in tabular figures so its width does not jump.
    func setInfo(_ text: String?, isTimer: Bool) {
        if isTimer != infoIsTimer {
            infoIsTimer = isTimer
            applyInfoFont()
        }
        infoLabel.text = text
    }
}

private extension CallBaseController {
    func createTopViews() {
        topContainer = UIView()
        view.addSubview(topContainer)

        callerLabel = UILabel()
        callerLabel.text = callerName
        callerLabel.textColor = KQColor.fixedWhite
        callerLabel.textAlignment = .center
        callerLabel.font = KQFont.font(.display)
        callerLabel.adjustsFontForContentSizeCategory = true
        callerLabel.numberOfLines = 2
        callerLabel.accessibilityTraits = UIAccessibilityTraitHeader
        topContainer.addSubview(callerLabel)

        infoLabel = UILabel()
        infoLabel.textColor = KQColor.fixedWhite
        infoLabel.textAlignment = .center
        infoLabel.numberOfLines = 0
        applyInfoFont()
        topContainer.addSubview(infoLabel)

        linkLabel.font = KQFont.font(.label)
        linkLabel.adjustsFontForContentSizeCategory = true
        linkLabel.textColor = KQColor.fixedWhite
        linkLabel.numberOfLines = 0
        linkLabel.isAccessibilityElement = true
        linkRow.axis = .horizontal
        linkRow.alignment = .center
        linkRow.spacing = KQSpace.x8
        linkRow.addArrangedSubview(linkMark)
        linkRow.addArrangedSubview(linkLabel)
        topContainer.addSubview(linkRow)
    }

    func installConstraints() {
        topContainer.snp.makeConstraints {
            // Under the notch and the Dynamic Island; the stored constraint hides the head during a video
            // call by moving it up by its own height.
            topContainerTopConstraint = $0.top.equalTo(view.safeAreaLayoutGuide).constraint
            $0.leading.trailing.equalTo(view.safeAreaLayoutGuide).inset(KQSpace.x24)
        }

        callerLabel.snp.makeConstraints {
            $0.top.equalToSuperview().offset(KQSpace.x40)
            $0.leading.trailing.equalToSuperview()
        }

        infoLabel.snp.makeConstraints {
            $0.top.equalTo(callerLabel.snp.bottom).offset(KQSpace.x8)
            $0.leading.trailing.equalToSuperview()
        }

        linkRow.snp.makeConstraints {
            $0.top.equalTo(infoLabel.snp.bottom).offset(KQSpace.x12)
            $0.centerX.equalToSuperview()
            $0.leading.greaterThanOrEqualToSuperview()
            $0.trailing.lessThanOrEqualToSuperview()
            $0.bottom.equalToSuperview().offset(-KQSpace.x16)
        }
    }

    func applyInfoFont() {
        // The timer is the larger line; a word in the body size.
        infoLabel.font = infoIsTimer ? KQFont.tabular(KQFont.font(.heading)) : KQFont.font(.body)
    }

    func applyLink() {
        guard let link = link else {
            linkRow.isHidden = true
            return
        }
        linkRow.isHidden = false
        linkMark.configure(link)
        linkLabel.text = AirCallText.line(link)
    }
}
