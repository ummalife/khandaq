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

    /// The head: the name, the state and the link; on a call without video the person's tile too.
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

    /// KHANDAQ (calls stage, 26.11): the person on a call without video (R9): their photo, else their
    /// initials in their tone. Nil shows the initials of the name on the screen.
    var person: AirCallPerson? {
        didSet {
            guard isViewLoaded else {
                return
            }
            applyPerson()
        }
    }

    /// KHANDAQ (calls stage, 26.11): a call without video either way gathers the person's tile, the
    /// name, the state and the link in the middle of `headArea` (R9); otherwise the head stands at the
    /// top without the tile.
    var centresHead = false {
        didSet {
            guard isViewLoaded, centresHead != oldValue else {
                return
            }
            applyHeadPlacement()
        }
    }

    /// KHANDAQ (calls stage, 26.11): over video the name and the state get a soft shadow so they read
    /// on any picture (R8); no band of gradient behind them.
    var headOverVideo = false {
        didSet {
            guard isViewLoaded, headOverVideo != oldValue else {
                return
            }
            applyHeadShadow()
        }
    }

    /// Where a centred head stands: from the safe top down to the controls, which a subclass pins its
    /// bottom to with `pinHeadArea(above:)` (the safe bottom until it does).
    let headArea = UILayoutGuide()

    /// The link as a chip under the state (R8).
    let linkChip = AirCallLinkChip()

    fileprivate var topContainerTopConstraint: Constraint!
    fileprivate var headCentreConstraints: [Constraint] = []
    fileprivate var headAreaBottomConstraint: Constraint!
    fileprivate var personTile: AirTileView!
    fileprivate var personTileSideConstraint: Constraint!
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
        applyPerson()
        applyHeadPlacement()
        applyHeadShadow()
        infoFontCategory = UIApplication.shared.preferredContentSizeCategory
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        // KHANDAQ (calls stage, 26.11): the traits can change while loadView is still building the head
        // (the dark override reaching the new view); loadView applies them itself then.
        guard personTileSideConstraint != nil else {
            return
        }
        let category = UIApplication.shared.preferredContentSizeCategory
        if category != infoFontCategory {
            infoFontCategory = category
            applyInfoFont()
        }
        if traitCollection.userInterfaceIdiom != previousTraitCollection?.userInterfaceIdiom {
            personTileSideConstraint.update(offset: personTileSide)
        }
        applyHeadShadow()
    }

    /**
        Prepare for removal by disabling all active views.
     */
    func prepareForRemoval() {
        setInfo(String(localized: "air_call_ended"), isTimer: false)
        UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, infoLabel.text)
    }

    func toggleTopContainer(hidden: Bool) {
        // KHANDAQ (calls stage, 26.11): hidden, the head goes above the screen whole; moved up by its own
        // height only, the chip stayed in sight under the status bar.
        let offset = hidden ? -(topContainer.frame.size.height + KQSpace.x40 + view.safeAreaInsets.top) : 0.0
        topContainerTopConstraint.update(offset: KQSpace.x40 + offset)
    }

    /// KHANDAQ (calls stage, 26.11): the bottom of `headArea` at the top of the controls. A required pin,
    /// not one above a fallback of low priority: the solver stretched the row of controls up to the
    /// safe bottom to keep such a fallback once the row went down out of sight.
    func pinHeadArea(above item: ConstraintItem) {
        headAreaBottomConstraint.deactivate()
        headArea.snp.makeConstraints {
            headAreaBottomConstraint = $0.bottom.equalTo(item).constraint
        }
    }

    /// KHANDAQ (calls stage, 26.11): what of the head is on the screen, in the coordinates of `view`,
    /// for the picture in picture to keep clear of (R5); empty while the head is hidden or empty.
    func headContentFrame() -> CGRect {
        topContainer.layoutIfNeeded()
        var frame = CGRect.null
        for part in (topContainer as? UIStackView)?.arrangedSubviews ?? [] where !part.isHidden {
            frame = frame.union(part.convert(part.bounds, to: view))
        }
        return frame.isNull ? .zero : frame
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
    /// R9: the person's tile is 112 pt on phones and 128 pt on tablets.
    var personTileSide: CGFloat {
        return traitCollection.userInterfaceIdiom == .pad ? AirCallStage.avatarSideTablet : AirCallStage.avatarSidePhone
    }

    func createTopViews() {
        // KHANDAQ (calls stage, 26.11): one column, each part in the middle at its own width, so the
        // picture in picture can stand beside a short name (R5, R11) and the group can be centred (R9).
        let head = UIStackView()
        head.axis = .vertical
        head.alignment = .center
        topContainer = head
        view.addSubview(topContainer)
        view.addLayoutGuide(headArea)

        // The tile of a row, at the size of the call screen: the display letters and the sheet corners.
        personTile = AirTileView(side: AirCallStage.avatarSidePhone, initialsFont: KQFont.font(.display), cornerRadius: KQRadius.sheet)
        head.addArrangedSubview(personTile)

        callerLabel = UILabel()
        callerLabel.text = callerName
        callerLabel.textColor = KQColor.fixedWhite
        callerLabel.textAlignment = .center
        callerLabel.font = KQFont.font(.display)
        callerLabel.adjustsFontForContentSizeCategory = true
        callerLabel.numberOfLines = 2
        callerLabel.accessibilityTraits = UIAccessibilityTraitHeader
        head.addArrangedSubview(callerLabel)

        infoLabel = UILabel()
        infoLabel.textColor = KQColor.fixedWhite
        infoLabel.textAlignment = .center
        infoLabel.numberOfLines = 0
        applyInfoFont()
        head.addArrangedSubview(infoLabel)

        head.addArrangedSubview(linkChip)

        // R9: 16 between the tile and the name, 6 between the name and the state, 12 before the chip.
        head.setCustomSpacing(KQSpace.x16, after: personTile)
        head.setCustomSpacing(KQSpace.x6, after: callerLabel)
        head.setCustomSpacing(KQSpace.x12, after: infoLabel)
    }

    func installConstraints() {
        topContainer.snp.makeConstraints {
            // Under the notch and the Dynamic Island; the stored constraint hides the head during a video
            // call by moving it above the screen.
            topContainerTopConstraint = $0.top.equalTo(view.safeAreaLayoutGuide).offset(KQSpace.x40).constraint
            $0.leading.trailing.equalTo(view.safeAreaLayoutGuide).inset(KQSpace.x24)
        }
        // On a phone on its side the group needs nearly all the height above the row: it may reach the
        // safe top and keeps 16 above the row. Short of room it leaves the middle first; then the tile
        // gives way, not the words.
        headCentreConstraints = topContainer.snp.prepareConstraints {
            $0.centerY.equalTo(headArea).priority(.medium)
            $0.top.greaterThanOrEqualTo(view.safeAreaLayoutGuide)
            $0.bottom.lessThanOrEqualTo(headArea).offset(-KQSpace.x16)
        }

        headArea.snp.makeConstraints {
            $0.top.leading.trailing.equalTo(view.safeAreaLayoutGuide)
            headAreaBottomConstraint = $0.bottom.equalTo(view.safeAreaLayoutGuide).constraint
        }

        personTile.snp.makeConstraints {
            personTileSideConstraint = $0.size.equalTo(personTileSide).priority(700).constraint
            $0.width.equalTo(personTile.snp.height)
        }
        personTile.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        personTile.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        for part in [callerLabel!, infoLabel!, linkChip] as [UIView] {
            part.snp.makeConstraints {
                $0.width.lessThanOrEqualToSuperview()
            }
        }
        callerLabel.setContentCompressionResistancePriority(UILayoutPriority(rawValue: 999), for: .vertical)
        infoLabel.setContentCompressionResistancePriority(UILayoutPriority(rawValue: 999), for: .vertical)
    }

    func applyHeadPlacement() {
        personTile.isHidden = !centresHead
        if centresHead {
            topContainerTopConstraint.deactivate()
            headCentreConstraints.forEach { $0.activate() }
        }
        else {
            headCentreConstraints.forEach { $0.deactivate() }
            topContainerTopConstraint.activate()
        }
    }

    func applyPerson() {
        let tile = person?.tile ?? .person(initials: AirInitials.from(callerName), tone: AirTone.index(for: callerName))
        personTile.configure(style: tile, photo: person?.photo)
    }

    /// R8: `fixed-black` at 50 %, a blur of 4 and 1 pt down; the radius of a layer shadow is half the blur.
    func applyHeadShadow() {
        for label in [callerLabel!, infoLabel!] {
            label.layer.shadowColor = label.airCGColor(KQColor.fixedBlack)
            label.layer.shadowOpacity = headOverVideo ? 0.5 : 0
            label.layer.shadowRadius = KQSpace.x4 / 2
            label.layer.shadowOffset = CGSize(width: 0, height: 1)
        }
    }

    func applyInfoFont() {
        // The timer is the larger line; a word in the body size.
        infoLabel.font = infoIsTimer ? KQFont.tabular(KQFont.font(.heading)) : KQFont.font(.body)
    }

    func applyLink() {
        guard let link = link else {
            linkChip.isHidden = true
            return
        }
        linkChip.isHidden = false
        linkChip.configure(link)
    }
}
