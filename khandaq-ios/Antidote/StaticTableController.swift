// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

class StaticTableController: UIViewController {
    fileprivate let theme: Theme
    fileprivate let tableViewStyle: UITableViewStyle
    fileprivate var modelArray: [[StaticTableBaseCellModel]]
    fileprivate let footerArray: [String?]?

    fileprivate var tableView: UITableView?

    init(theme: Theme, style: UITableViewStyle, model: [[StaticTableBaseCellModel]], footers: [String?]? = nil) {
        self.theme = theme
        self.tableViewStyle = style
        self.modelArray = model
        self.footerArray = footers

        super.init(nibName: nil, bundle: nil)

        edgesForExtendedLayout = UIRectEdge()
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    // KHANDAQ design (Figma): grouped screens use white inset cards on grey (.insetGrouped, iOS 13+).
    static var insetGroupedStyle: UITableViewStyle {
        if #available(iOS 13.0, *) {
            return .insetGrouped
        }
        return .grouped
    }

    override func loadView() {
        loadViewWithBackgroundColor(theme.colorForType(.NormalBackground))

        createTableView()

        installConstraints()
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        // KHANDAQ design (Figma): every pushed StaticTable sub-screen (Settings/Profile details, etc.)
        // gets the grey circle back button; the helper skips tab roots (nav stack count == 1).
        installKhandaqCircleBackButton(theme: theme)
    }

    func reloadTableView() {
        tableView?.reloadData()
    }

    func updateModelArray(_ model: [[StaticTableBaseCellModel]]) {
        modelArray = model
        reloadTableView()
    }
}

extension StaticTableController: UITableViewDataSource {
    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let model = modelArray[indexPath.section][indexPath.row]
        let cell: StaticTableBaseCell

        switch model {
            case _ as StaticTableButtonCellModel:
                cell = dequeueCellForClass(StaticTableButtonCell.staticReuseIdentifier)
            case _ as StaticTableAvatarCellModel:
                cell = dequeueCellForClass(StaticTableAvatarCell.staticReuseIdentifier)
            case _ as StaticTableDefaultCellModel:
                cell = dequeueCellForClass(StaticTableDefaultCell.staticReuseIdentifier)
            case _ as StaticTableChatButtonsCellModel:
                cell = dequeueCellForClass(StaticTableChatButtonsCell.staticReuseIdentifier)
            case _ as StaticTableSwitchCellModel:
                cell = dequeueCellForClass(StaticTableSwitchCell.staticReuseIdentifier)
            case _ as StaticTableInfoCellModel:
                cell = dequeueCellForClass(StaticTableInfoCell.staticReuseIdentifier)
            case _ as StaticTableMultiChoiceButtonCellModel:
                cell = dequeueCellForClass(StaticTableMultiChoiceButtonCell.staticReuseIdentifier)
            default:
                fatalError("Static model class \(model) has not been implemented")
        }

        cell.setupWithTheme(theme, model: model)

        let isLastRow = (indexPath.row == (modelArray[indexPath.section].count - 1))
        let isLastSection = (indexPath.section == (modelArray.count - 1))

        switch tableViewStyle {
            case .plain:
                cell.setBottomSeparatorHidden(!isLastRow || isLastSection)
            case .grouped:
                cell.setBottomSeparatorHidden(isLastRow)

            case .insetGrouped:
                // KHANDAQ design (Figma): white rounded inset card. The system grouped background
                // configuration rounds the section's first/last cell corners automatically; we just
                // supply the white fill and hide the separator under the last row of each section.
                // The avatar cell stays on the plain grey backdrop (no card) — own-profile header.
                cell.setBottomSeparatorHidden(isLastRow)
                if cell is StaticTableAvatarCell {
                    cell.backgroundColor = theme.colorForType(.SettingsBackground)
                    cell.contentView.backgroundColor = .clear
                } else if #available(iOS 14.0, *) {
                    cell.automaticallyUpdatesBackgroundConfiguration = false
                    var config = UIBackgroundConfiguration.listGroupedCell()
                    // White (light) / elevated #2C2C2E (dark) so cards stand off the backdrop in both.
                    config.backgroundColor = theme.colorForType(.ChatIncomingBubble)
                    cell.backgroundConfiguration = config
                    cell.contentView.backgroundColor = .clear
                } else {
                    cell.backgroundColor = theme.colorForType(.ChatIncomingBubble)
                    cell.contentView.backgroundColor = .clear
                }
        }

        return cell
    }

    func numberOfSections(in tableView: UITableView) -> Int {
        return modelArray.count
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return modelArray[section].count
    }

    func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        guard let text = footerText(for: section) else {
            return nil
        }
        if footerContainsLink(text) {
            return nil
        }
        return text
    }

    func tableView(_ tableView: UITableView, viewForFooterInSection section: Int) -> UIView? {
        guard let text = footerText(for: section), footerContainsLink(text) else {
            return nil
        }

        let textView = UITextView()
        textView.backgroundColor = .clear
        textView.isEditable = false
        textView.isSelectable = true
        textView.isScrollEnabled = false
        textView.dataDetectorTypes = [.link]
        textView.textContainerInset = UIEdgeInsets(top: 4, left: 16, bottom: 10, right: 16)
        textView.textContainer.lineFragmentPadding = 0
        textView.font = UIFont.systemFont(ofSize: 13)
        textView.textColor = theme.colorForType(.ChatInformationText)
        textView.linkTextAttributes = [
            NSAttributedStringKey.foregroundColor.rawValue: theme.colorForType(.LinkText),
        ]
        textView.text = text
        return textView
    }

    func tableView(_ tableView: UITableView, heightForFooterInSection section: Int) -> CGFloat {
        // KHANDAQ (#58): a section with no footer text must have ZERO footer height. Returning
        // UITableViewAutomaticDimension made a .plain table render a default (light-grey) section-footer
        // strip below the last row — visible against the dark theme on the Profile screen.
        guard let text = footerText(for: section) else {
            return 0
        }
        guard footerContainsLink(text) else {
            return UITableViewAutomaticDimension
        }
        let width = tableView.bounds.width - 32
        guard width > 0 else {
            return UITableViewAutomaticDimension
        }
        let font = UIFont.systemFont(ofSize: 13)
        let rect = (text as NSString).boundingRect(
            with: CGSize(width: width, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: [.font: font],
            context: nil
        )
        return ceil(rect.height) + 18
    }
}

extension StaticTableController: UITableViewDelegate {
    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)

        guard let cell = tableView.cellForRow(at: indexPath) as? StaticTableBaseCell else {
            return
        }

        let model = modelArray[indexPath.section][indexPath.row]

        switch model {
            case let model as StaticTableSelectableCellModel:
                model.didSelectHandler?(cell)
            default:
                // nop
                break;
        }
    }
}

private extension StaticTableController {
    func footerText(for section: Int) -> String? {
        guard let array = footerArray, section < array.count else {
            return nil
        }
        return array[section]
    }

    func footerContainsLink(_ text: String) -> Bool {
        return text.contains("https://") || text.contains("http://")
    }

    func createTableView() {
        tableView = UITableView(frame: CGRect.zero, style: tableViewStyle)
        tableView!.dataSource = self
        tableView!.delegate = self
        tableView!.estimatedRowHeight = 44.0;
        tableView!.separatorStyle = .none;
        // KHANDAQ: theme the table's own background. A grouped UITableView defaults to
        // systemGroupedBackground, which showed as a stray gray band in the footer/below the last
        // cell (e.g. the profile "Выйти" screen). Bind it to the theme so it matches the view.
        tableView!.backgroundColor = theme.colorForType(.NormalBackground)

        switch tableViewStyle {
            case .plain:
                tableView!.backgroundColor = theme.colorForType(.NormalBackground)
            case .grouped:
                tableView!.backgroundColor = theme.colorForType(.SettingsBackground)
            case .insetGrouped:
                // KHANDAQ design (Figma): grey backdrop, white inset cards (cells provide the fill).
                tableView!.backgroundColor = theme.colorForType(.SettingsBackground)
        }

        view.addSubview(tableView!)

        tableView!.register(StaticTableButtonCell.self, forCellReuseIdentifier: StaticTableButtonCell.staticReuseIdentifier)
        tableView!.register(StaticTableAvatarCell.self, forCellReuseIdentifier: StaticTableAvatarCell.staticReuseIdentifier)
        tableView!.register(StaticTableDefaultCell.self, forCellReuseIdentifier: StaticTableDefaultCell.staticReuseIdentifier)
        tableView!.register(StaticTableChatButtonsCell.self, forCellReuseIdentifier: StaticTableChatButtonsCell.staticReuseIdentifier)
        tableView!.register(StaticTableSwitchCell.self, forCellReuseIdentifier: StaticTableSwitchCell.staticReuseIdentifier)
        tableView!.register(StaticTableInfoCell.self, forCellReuseIdentifier: StaticTableInfoCell.staticReuseIdentifier)
        tableView!.register(StaticTableMultiChoiceButtonCell.self, forCellReuseIdentifier: StaticTableMultiChoiceButtonCell.staticReuseIdentifier)
    }

    func installConstraints() {
        tableView!.snp.makeConstraints {
            $0.edges.equalTo(view)
        }
    }

    func dequeueCellForClass(_ identifier: String) -> StaticTableBaseCell {
        return tableView!.dequeueReusableCell(withIdentifier: identifier) as! StaticTableBaseCell
    }
}
